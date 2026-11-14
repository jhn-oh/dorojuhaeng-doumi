package kr.juhyu.licensenote

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.HandlerThread
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import org.json.JSONArray
import org.json.JSONObject

data class Signal(
    val value: String? = null,
    val quality: String = "WAITING",
    val source: String = "",
    val receivedMs: Long = 0,
    val origin: String = "",
    val error: String? = null,
    val sampleNs: Long? = null,
)

internal const val LOCATION_MAX_AGE_MS = 3000L

internal fun fusedLocationSignal(location: LocationData?, wallNowMs: Long, elapsedNowMs: Long): Signal {
    val measuredMs = location?.timestampMillis
    val ageMs = measuredMs?.takeIf { it > 0 && it <= wallNowMs }?.let { wallNowMs - it }
    val measuredElapsedMs = ageMs?.takeIf { it <= LOCATION_MAX_AGE_MS && it <= elapsedNowMs }?.let { elapsedNowMs - it }
    return Signal(
        value = location?.let { "${it.latitude},${it.longitude},${it.heading},${it.confidence},${it.timestampMillis}" },
        quality = if (measuredElapsedMs != null) "VALID" else "UNAVAILABLE",
        source = "FusedLocation/1.1.3", receivedMs = elapsedNowMs, origin = "CALLBACK",
        sampleNs = measuredElapsedMs?.times(1_000_000L),
    )
}

/** Owns public SDK subscriptions independently of the Compose Activity. */
class SdkTelemetry(private val context: Context, private val emit: (String, Signal) -> Unit, private val guideVisible: () -> Boolean) {
    private val inputThread = HandlerThread("PleosInput").apply { start() }
    private val main = Handler(inputThread.looper)
    private val navigationThread = HandlerThread("PleosNavigation").apply { start() }
    private val navigationHandler = Handler(navigationThread.looper)
    private val vehicleService = Intent().setClassName("ai.pleos.playground.service.vehicle",
        "ai.pleos.playground.service.vehicle.VehicleService")
    private var vehicleSession: PleosVehicleSession? = null
    private val fused = FusedLocation(context)
    private val navigation = NaviHelper(context)
    @Volatile private var collectingLocation = false
    private var navigationStarted = false
    private var navigationConnected = false
    private var navigationListenerAdded = false
    private var pendingNavigationOpen: Boolean? = null
    private var pendingNavigationScreen: NavigationScreenRequest = NavigationScreenRequest.Foreground
    private var navigationRequestActive = false
    private var navigationRequestId = 0L
    // Public callback DTOs have no request token; this is the local request epoch at receipt.
    private var navigationCourseRequestGeneration = 0L
    private val navigationWaypointArrivals = ArrayDeque<JSONObject>()
    private var navigationRouteActive = false
    private var navigationDestinationMatches = false
    private var navigationWaypointsMatch = false
    private var requestedCourse = CourseCatalog.full
    private var navigationProviderReady = false
    private var waitingCourseNavigation = false
    private var navigationGuiding = false
    private var lastNavigationStatus: NavigationStatus? = null
    private var navigationOpenedAt = 0L
    private var nextNavigationQuery = 0L
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null
    @Volatile private var closed = false
    private fun send(key: String, value: Any?, source: String, origin: String = "READ", sampleNs: Long? = null) {
        if (!closed) emit(key, Signal(value?.toString(), if (value == null) "UNAVAILABLE" else "VALID", source, SystemClock.elapsedRealtime(), origin, sampleNs = sampleNs))
    }
    private fun fail(key: String, error: Exception) {
        if (!closed) emit(key, Signal(quality = "ERROR", source = "SDK", receivedMs = SystemClock.elapsedRealtime(), error = error.javaClass.simpleName + ": " + error.message))
    }
    private fun onNavigation(action: () -> Unit) {
        if (closed) return
        if (Looper.myLooper() == navigationHandler.looper) action()
        else navigationHandler.post { if (!closed) action() }
    }
    private val locationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            if (collectingLocation && !closed) {
                emit("location", fusedLocationSignal(location, System.currentTimeMillis(), SystemClock.elapsedRealtime()))
            }
        }
        override fun onError(exception: Exception) = fail("location", exception)
    }
    private val carListener = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val key = if (value.propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) "speed" else "gear"
            send(key, if (value.status == CarPropertyValue.STATUS_AVAILABLE) value.value else null, "AAOS/CarPropertyManager", "CALLBACK", value.timestamp)
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) = fail(if (propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) "speed" else "gear", IllegalStateException("property unavailable"))
    }
    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r ->
        send("ux", "${r.isRequiresDistractionOptimization},${r.activeRestrictions}", "AAOS/CarUxRestrictions", "CALLBACK")
    }
    private val navigationListener = object : NaviHelperEventListener {
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = onNavigation {
            send("navigation_route", "${routeStateInfo.isRouting},${routeStateInfo.naviMode}", "NaviHelper/3.0.5", "CALLBACK")
            send("navigation_route_details", JSONObject().put("termsAgreement", routeStateInfo.termsAgreement).put("isRouting", routeStateInfo.isRouting).put("naviMode", routeStateInfo.naviMode.name).toString(), "NaviHelper/3.0.5", "CALLBACK")
            navigationRouteActive = routeStateInfo.isRouting
            // The service can return cached SAFE_GUIDING before the native app reports INITIALIZING.
            val readyStatus = lastNavigationStatus == NavigationStatus.FREE_DRIVE || lastNavigationStatus == NavigationStatus.ROUTE_DRIVE
            val noStatusAfterOpening = lastNavigationStatus == null && navigationOpenedAt > 0 && SystemClock.elapsedRealtime() - navigationOpenedAt >= 3000
            if (routeStateInfo.naviMode != NaviMode.NOT_WORKING && (readyStatus || noStatusAfterOpening)) markNavigationReady()
            verifyNavigationRoute()
        }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = onNavigation {
            send("navigation_search", "${routeStartInfo.name},${routeStartInfo.distance},${routeStartInfo.duration}", "NaviHelper/3.0.5", "CALLBACK")
            if (navigationRequestActive) send("navigation_request", "SEARCHING", "NaviHelper/3.0.5", "CALLBACK")
        }
        override fun onRoutingCompleted() = onNavigation {
            send("navigation_progress", "ROUTING_COMPLETED", "NaviHelper/3.0.5", "CALLBACK")
            queryNavigation()
        }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onRouteEnded() = onNavigation {
            send("navigation_progress", "LEGACY_ROUTE_ENDED", "NaviHelper/3.0.5", "CALLBACK")
            queryNavigation()
        }
        override fun onError(errorCode: NaviErrorCode) = onNavigation {
            send("navigation_error", errorCode.name, "NaviHelper/3.0.5", "CALLBACK")
            if (navigationRequestActive || waitingCourseNavigation) {
                navigationRequestActive = false
                waitingCourseNavigation = false
                fail("navigation_request", IllegalStateException(errorCode.name))
            }
        }
        override fun onNavigationStatus(status: NavigationStatus) = onNavigation {
            send("navigation_status", status.name, "NaviHelper/3.0.5", "CALLBACK")
            lastNavigationStatus = status
            navigationGuiding = status == NavigationStatus.ROUTE_DRIVE
            if (status == NavigationStatus.FREE_DRIVE || navigationGuiding) markNavigationReady()
            else navigationProviderReady = false
            verifyNavigationRoute()
        }
        override fun onRouteCancelled() = onNavigation {
            navigationRequestActive = false
            waitingCourseNavigation = false
            navigationRouteActive = false
            send("navigation_request", "CANCELLED", "NaviHelper/3.0.5", "CALLBACK")
        }
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = onNavigation {
            val route = waypointChangedInfo.routeStartInfo
            send("navigation_waypoint_changed", JSONObject()
                .put("index", waypointChangedInfo.index.name).put("cmdType", waypointChangedInfo.cmdType.name)
                .put("routeStartInfo", JSONObject().put("duration", route.duration).put("distance", route.distance)
                    .put("tollFare", route.tollFare).put("name", route.name)
                    .put("remainDestinationChargingValue", route.remainDestinationChargingValue))
                .toString(), "NaviHelper/3.0.5", "CALLBACK")
        }
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) = onNavigation {
            val data = JSONObject().put("index", waypointArrivedInfo.index.name)
                .put("name", waypointArrivedInfo.name).put("address", waypointArrivedInfo.address)
                .put("distance", waypointArrivedInfo.distance).put("duration", waypointArrivedInfo.duration)
            send("navigation_waypoint_arrived", data.toString(), "NaviHelper/3.0.5", "CALLBACK")
            if (navigationCourseRequestGeneration > 0) {
                if (navigationWaypointArrivals.size == 20) navigationWaypointArrivals.removeFirst()
                navigationWaypointArrivals.addLast(JSONObject().put("receivedMs", SystemClock.elapsedRealtime()).put("data", data))
                send("navigation_waypoint_arrivals", JSONObject().put("requestGeneration", navigationCourseRequestGeneration)
                    .put("events", JSONArray(navigationWaypointArrivals.toList())).toString(), "NaviHelper/3.0.5", "CALLBACK")
            }
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = onNavigation {
            navigationRequestActive = false
            waitingCourseNavigation = false
            navigationRouteActive = false
            send("navigation_request", "ARRIVED", "NaviHelper/3.0.5", "CALLBACK")
        }
        override fun onDestinationChanged() = onNavigation {
            send("navigation_destination_changed", "CHANGED", "NaviHelper/3.0.5", "CALLBACK")
            queryNavigation()
        }
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = onNavigation {
            send("navigation_location", "${currentLocationInfo.latitude},${currentLocationInfo.longitude},${currentLocationInfo.heading},${currentLocationInfo.address}", "NaviHelper/3.0.5", "CALLBACK")
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = onNavigation {
            send("navigation_destination", JSONObject()
                .put("latitude", destinationInfo.latitude).put("longitude", destinationInfo.longitude)
                .put("displayLatitude", destinationInfo.displayLatitude ?: JSONObject.NULL)
                .put("displayLongitude", destinationInfo.displayLongitude ?: JSONObject.NULL)
                .put("name", destinationInfo.name).put("address", destinationInfo.address)
                .put("remainDistance", destinationInfo.remainDistance).put("remainTime", destinationInfo.remainTime)
                .put("tollFare", destinationInfo.tollFare).put("poiId", destinationInfo.poiId)
                .put("poiSubId", destinationInfo.poiSubId ?: JSONObject.NULL).put("poiProvider", destinationInfo.poiProvider)
                .toString(), "NaviHelper/3.0.5", "CALLBACK")
            navigationDestinationMatches = distanceMeters(requestedCourse.finish, GeoPoint(destinationInfo.latitude, destinationInfo.longitude)) <= 50
            verifyNavigationRoute()
        }
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = onNavigation {
            send("navigation_waypoints", JSONArray().also { array -> waypointInfoList.forEachIndexed { index, waypoint ->
                array.put(JSONObject()
                    .put("listIndex", index) // Returned list order; WaypointInfo exposes no WaypointIndex.
                    .put("latitude", waypoint.latitude).put("longitude", waypoint.longitude)
                    .put("displayLatitude", waypoint.displayLatitude ?: JSONObject.NULL)
                    .put("displayLongitude", waypoint.displayLongitude ?: JSONObject.NULL)
                    .put("name", waypoint.name).put("address", waypoint.address)
                    .put("remainDistance", waypoint.remainDistance).put("remainTime", waypoint.remainTime)
                    .put("poiId", waypoint.poiId).put("poiSubId", waypoint.poiSubId ?: JSONObject.NULL)
                    .put("poiProvider", waypoint.poiProvider))
            } }.toString(), "NaviHelper/3.0.5", "CALLBACK")
            navigationWaypointsMatch = waypointInfoList.size == requestedCourse.waypoints.size &&
                requestedCourse.waypoints.zip(waypointInfoList).all { (point, waypoint) ->
                    distanceMeters(point, GeoPoint(waypoint.latitude, waypoint.longitude)) <= 50
                }
            verifyNavigationRoute()
        }
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = onNavigation {
            send("navigation_tbt", JSONArray().also { array -> tbtInfoList.forEach { array.put(JSONObject().put("type", it.type.name).put("distance", it.distance).put("description", it.description)) } }.toString(), "NaviHelper/3.0.5", "CALLBACK")
            val first = tbtInfoList.firstOrNull()?.takeIf {
                navigationCourseRequestGeneration > 0 && navigationGuiding && navigationRouteActive && navigationDestinationMatches && navigationWaypointsMatch
            }
            send("course_tbt", first?.let { "${requestedCourse.id}|${it.type.name}|${it.distance}" }, "NaviHelper/3.0.5", "CALLBACK")
        }
    }

    private fun verifyNavigationRoute() {
        if (navigationRequestActive && navigationRouteActive && navigationDestinationMatches && navigationWaypointsMatch) {
            navigationRequestActive = false
            send("navigation_request", "ROUTE_READY", "NaviHelper/3.0.5 destination and ${requestedCourse.waypoints.size} waypoints confirmed", "CALLBACK")
        } else if (navigationRequestActive && navigationGuiding && navigationDestinationMatches) {
            send("navigation_request", "GUIDING_UNCONFIRMED", "NaviHelper/3.0.5 guidance and destination confirmed; route/waypoint response incomplete", "CALLBACK")
        }
    }

    private fun markNavigationReady() {
        navigationProviderReady = true
        if (waitingCourseNavigation) {
            waitingCourseNavigation = false
            requestCourseNavigation()
        }
    }

    private fun queryNavigation() {
        if (!navigationConnected) return
        runCatching {
            navigation.getRouteStateInfo()
            navigation.getCurrentLocationInfo()
            navigation.getDestinationInfo()
            navigation.getWaypointInfo()
            navigation.getTBTInfo()
        }.onFailure { fail("navigation_query", Exception(it)) }
    }

    private val navigationPoll = object : Runnable {
        override fun run() {
            if (closed) return
            val now = SystemClock.elapsedRealtime()
            if (navigationConnected && now >= nextNavigationQuery) {
                nextNavigationQuery = now + 5000
                queryNavigation()
            }
            navigationHandler.postDelayed(this, 1000)
        }
    }

    fun start() {
        runCatching {
            if (fused.isSupported()) {
                fused.initialize()
                send("location", null, "FusedLocation/not recording")
            } else send("location", null, "FusedLocation/unsupported")
        }.onFailure { fail("location", it as? Exception ?: Exception(it)) }
        car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
            if (closed) return@createCar
            if (!ready) {
                properties = null
                ux = null
                listOf("speed", "gear", "ux").forEach { fail(it, IllegalStateException("car disconnected")) }
            } else {
                properties = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                ux = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                ux?.registerListener(uxListener)
                ux?.currentCarUxRestrictions?.let { uxListener.onUxRestrictionsChanged(it) }
                for (id in listOf(VehiclePropertyIds.PERF_VEHICLE_SPEED, VehiclePropertyIds.GEAR_SELECTION)) {
                    runCatching { properties?.registerCallback(carListener, id, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
                        .onFailure { fail(if (id == VehiclePropertyIds.PERF_VEHICLE_SPEED) "speed" else "gear", Exception(it)) }
                }
            }
        }
        main.post(poll)
        navigationHandler.post(navigationPoll)
    }
    fun probeNavigation() = onNavigation { initializeNavigation() }

    /** Configure the selected course only while parked, otherwise show the existing route. */
    fun openNavigation(withCourseRoute: Boolean, route: PracticeRoute? = null) = onNavigation {
        if (withCourseRoute && route != null) requestedCourse = route
        pendingNavigationScreen = NavigationScreenRequest.Foreground
        pendingNavigationOpen = withCourseRoute
        if (withCourseRoute) send("navigation_request", "CONNECTING", "NaviHelper/3.0.5", "REQUEST")
        else send("navigation_screen", "OPENING", "NaviHelper/3.0.5", "REQUEST")
        if (navigationConnected) openConnectedNavigation() else initializeNavigation()
    }

    fun cancelDestinationSearch() = onNavigation {
        if (pendingNavigationScreen is NavigationScreenRequest.SearchPlace) {
            pendingNavigationOpen = null
            pendingNavigationScreen = NavigationScreenRequest.Foreground
        }
    }
    fun searchDestination(keyword: String) = onNavigation {
        if (keyword.isBlank()) return@onNavigation
        pendingNavigationScreen = NavigationScreenRequest.SearchPlace(keyword.trim().take(160))
        pendingNavigationOpen = false
        send("navigation_screen", "SEARCH_CONNECTING", "NaviHelper/3.0.5", "REQUEST")
        if (navigationConnected) openConnectedNavigation() else initializeNavigation()
    }
    fun openNavigationSettings() = onNavigation {
        pendingNavigationScreen = NavigationScreenRequest.Settings
        pendingNavigationOpen = false
        send("navigation_screen", "SETTINGS_CONNECTING", "NaviHelper/3.0.5", "REQUEST")
        if (navigationConnected) openConnectedNavigation() else initializeNavigation()
    }

    private fun initializeNavigation() {
        if (navigationStarted) {
            if (navigationConnected) queryNavigation()
            return
        }
        navigationStarted = true
        runCatching {
            if (!navigationListenerAdded) { navigation.addListener(navigationListener); navigationListenerAdded = true }
            navigation.initialize({ onNavigation {
                navigationConnected = true
                send("navigation", "CONNECTED", "NaviHelper/3.0.5", "CONNECTED")
                if (pendingNavigationOpen != null) openConnectedNavigation() else queryNavigation()
            } }, { error -> onNavigation { navigationConnectionFailed(Exception(error)) } })
        }.onFailure { navigationConnectionFailed(Exception(it)) }
    }

    private fun navigationConnectionFailed(error: Exception) {
        navigationStarted = false
        navigationConnected = false
        fail("navigation", error)
        pendingNavigationOpen?.let { withCourse ->
            fail(if (withCourse) "navigation_request" else "navigation_screen", error)
        }
    }

    private fun openConnectedNavigation() {
        val withCourse = pendingNavigationOpen ?: return
        val screen = pendingNavigationScreen
        pendingNavigationOpen = null
        pendingNavigationScreen = NavigationScreenRequest.Foreground
        if (screen == NavigationScreenRequest.Settings || screen is NavigationScreenRequest.SearchPlace) {
            val signals = (context.applicationContext as? LicenseApp)?.signals?.value.orEmpty()
            if (!canReview(signals, SystemClock.elapsedRealtime())) {
                send("navigation_screen", "SCREEN_BLOCKED_WHILE_MOVING", "NaviHelper/3.0.5", "REQUEST")
                return
            }
        } else if (screen == NavigationScreenRequest.Foreground) navigationOpenedAt = SystemClock.elapsedRealtime()
        // SDK foreground requests are recorded separately from Android launch requests and actual rendering.
        runCatching {
            navigation.requestNavigationScreen(screen)
            val requested = when (screen) {
                NavigationScreenRequest.Settings -> "SDK_SETTINGS_REQUESTED"
                is NavigationScreenRequest.SearchPlace -> "SDK_SEARCH_REQUESTED"
                else -> "SDK_FOREGROUND_REQUESTED"
            }
            send("navigation_screen", requested, "NaviHelper/3.0.5", "REQUEST")
        }.onFailure { fail("navigation_screen", Exception(it)) }
        if (screen == NavigationScreenRequest.Settings) { queryNavigation(); return }
        if (screen == NavigationScreenRequest.Foreground) runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage("ai.umos.maps.android.navigation.app") ?: error("Built-in navigation app is unavailable")
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            send("navigation_launch", "ANDROID_ACTIVITY_REQUESTED", "Android package launch for Pleos 2.1 built-in navigation", "REQUEST")
        }.onFailure { fail("navigation_launch", Exception(it)) }
        if (!withCourse) { queryNavigation(); return }
        if (!navigationProviderReady) {
            waitingCourseNavigation = true
            send("navigation_request", "WAITING_FOR_PROVIDER", "NaviHelper/3.0.5 waiting for native navigation initialization", "REQUEST")
            val waitId = ++navigationRequestId
            queryNavigation()
            navigationHandler.postDelayed({
                if (waitingCourseNavigation && navigationRequestId == waitId) {
                    waitingCourseNavigation = false
                    fail("navigation_request", IllegalStateException("Native navigation did not initialize within 90 seconds"))
                }
            }, 90_000)
            return
        }
        requestCourseNavigation()
    }

    private fun requestCourseNavigation() {
        // Initialization is asynchronous: recheck parking before changing the destination.
        val signals = (context.applicationContext as? LicenseApp)?.signals?.value.orEmpty()
        if (!canReview(signals, SystemClock.elapsedRealtime())) {
            send("navigation_request", "OPENING", "NaviHelper/3.0.5 screen only; destination was not changed while moving", "REQUEST")
            return
        }
        navigationRouteActive = false
        navigationDestinationMatches = false
        navigationWaypointsMatch = false
        navigationRequestActive = true
        val requestId = ++navigationRequestId
        navigationCourseRequestGeneration = requestId
        navigationWaypointArrivals.clear()
        send("navigation_waypoint_arrivals", JSONObject().put("requestGeneration", requestId)
            .put("events", JSONArray()).toString(), "NaviHelper/3.0.5", "REQUEST")
        runCatching {
            val destination = RouteInfo(latitude = requestedCourse.finish.lat, longitude = requestedCourse.finish.lon, poiName = requestedCourse.destination, poiId = "", address = "", poiSubId = "")
            val waypoints = requestedCourse.waypoints.mapIndexed { index, point ->
                val label = requestedCourse.turns.firstOrNull { distanceMeters(it.corner, point) < 1 }?.direction?.label
                    ?: if (requestedCourse.isLanePractice) "차로 변경" else "경유"
                RequestWaypointInfo(latitude = point.lat, longitude = point.lon, poiName = "$label 검증 지점", waypointIndex = if (index == 0) WaypointIndex.FIRST else WaypointIndex.SECOND, poiId = "", address = "", poiSubId = "")
            }
            if (waypoints.isEmpty()) navigation.requestRoute(destination)
            else navigation.requestRouteWithWaypoints(RouteWithWaypointsRequest(destination, waypoints))
            send("navigation_request", "REQUESTED", "NaviHelper/3.0.5 destination and ${waypoints.size} waypoints", "REQUEST")
            nextNavigationQuery = SystemClock.elapsedRealtime() + 5000
            navigationHandler.postDelayed({
                if (navigationRequestActive && navigationRequestId == requestId) {
                    navigationRequestActive = false
                    if (navigationGuiding && navigationDestinationMatches) send("navigation_request", "GUIDING_UNCONFIRMED", "NaviHelper/3.0.5 guidance active; waypoint coordinates or routing flag not confirmed", "CALLBACK")
                    else fail("navigation_request", IllegalStateException("No confirmed active course route within 40 seconds"))
                }
            }, 40_000)
        }.onFailure { navigationRequestActive = false; fail("navigation_request", Exception(it)) }
    }
    fun startLocationUpdates() {
        collectingLocation = true
        runCatching {
            fused.registerFusedLocationCallback(locationListener)
        }.onFailure { fail("location", Exception(it)) }
    }
    fun stopLocationUpdates() {
        collectingLocation = false
        runCatching { fused.unregisterFusedLocationCallback(locationListener) }
        send("location", null, "FusedLocation/not recording")
    }
    private fun pollVehicle(now: Long) {
        val available = context.packageManager.resolveService(vehicleService, 0) != null
        if (!available) {
            vehicleSession?.close()
            vehicleSession = null
            return
        }
        val session = vehicleSession ?: PleosVehicleSession(Vehicle(context), main, emit, guideVisible) { collectingLocation }.also {
            it.start()
            vehicleSession = it
        }
        session.poll(now)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            val now = SystemClock.elapsedRealtime()
            pollVehicle(now)
            properties?.let { p ->
                for ((id, key) in listOf(VehiclePropertyIds.PERF_VEHICLE_SPEED to "speed", VehiclePropertyIds.GEAR_SELECTION to "gear")) {
                    runCatching {
                        val v = p.getProperty<Any>(id, 0)
                        send(key, if (v.status == CarPropertyValue.STATUS_AVAILABLE) v.value else null, "AAOS/CarPropertyManager", sampleNs = v.timestamp)
                    }.onFailure { fail(key, Exception(it)) }
                }
            }
            main.postDelayed(this, 1000)
        }
    }
    fun close() {
        send("turn_subscription", null, "Vehicle/TurnSignal disconnected", "SUBSCRIPTION")
        send("steering_subscription", null, "Vehicle/SteeringWheel disconnected", "SUBSCRIPTION")
        closed = true
        main.removeCallbacksAndMessages(null)
        main.post { vehicleSession?.close(); vehicleSession = null }
        runCatching { fused.unregisterFusedLocationCallback(locationListener); fused.release() }
        navigationHandler.removeCallbacksAndMessages(null)
        // A remote navigation call may stall: releasing it must not hold up the vehicle collector.
        navigationHandler.post { runCatching { navigation.removeListener(navigationListener); navigation.release() } }
        navigationThread.quitSafely()
        runCatching { properties?.unregisterCallback(carListener); ux?.unregisterListener(); car?.disconnect() }
        inputThread.quitSafely()
    }
}
