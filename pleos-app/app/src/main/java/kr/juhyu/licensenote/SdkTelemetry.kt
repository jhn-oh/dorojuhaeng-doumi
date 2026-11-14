package kr.juhyu.licensenote

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.listener.*
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
)

/** Owns public SDK subscriptions independently of the Compose Activity. */
class SdkTelemetry(private val context: Context, private val emit: (String, Signal) -> Unit, private val guideVisible: () -> Boolean) {
    private val inputThread = HandlerThread("PleosInput").apply { start() }
    private val main = Handler(inputThread.looper)
    private val navigationThread = HandlerThread("PleosNavigation").apply { start() }
    private val navigationHandler = Handler(navigationThread.looper)
    private val vehicle = Vehicle(context)
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
    private var nextDiscoveryAt = 0L
    private var beltSubscribed = false
    private var turnSubscribed = false
    private var doorsSubscribed = false
    private var steeringSubscribed = false
    private var beltPending = false
    private var turnPending = false
    private var doorsPending = false
    private var steeringPending = false
    private var beltAttempted = false
    private var turnAttempted = false
    private var steeringAttempted = false
    private val registeredDoors = mutableSetOf<DoorArea>()
    private val guideReadDue = mutableMapOf<String, Long>()
    private val guideRequests = mutableMapOf<String, Long>()
    @Volatile private var seatArea: SeatArea? = null
    private val doors = java.util.concurrent.ConcurrentHashMap<DoorArea, DoorPositionListener>()
    private fun send(key: String, value: Any?, source: String, origin: String = "READ") {
        if (!closed) emit(key, Signal(value?.toString(), if (value == null) "UNAVAILABLE" else "VALID", source, SystemClock.elapsedRealtime(), origin))
    }
    private fun fail(key: String, error: Exception) {
        if (!closed) emit(key, Signal(quality = "ERROR", source = "SDK", receivedMs = SystemClock.elapsedRealtime(), error = error.javaClass.simpleName + ": " + error.message))
    }
    private fun onInput(action: () -> Unit) {
        if (closed) return
        if (Looper.myLooper() == main.looper) action() else main.post { if (!closed) action() }
    }
    private fun onNavigation(action: () -> Unit) {
        if (closed) return
        if (Looper.myLooper() == navigationHandler.looper) action()
        else navigationHandler.post { if (!closed) action() }
    }
    private fun beltFailed(error: Exception) = onInput {
        beltSubscribed = false
        fail("belt", error)
    }
    private fun turnFailed(error: Exception) = onInput {
        turnSubscribed = false
        fail("turn", error)
        fail("turn_subscription", error)
    }
    private fun steeringFailed(error: Exception) = onInput {
        steeringSubscribed = false
        fail("steering", error)
        fail("steering_subscription", error)
    }
    private fun steeringAngle(angle: Float?, origin: String) {
        // Vehicle 2.3.2 reports front-wheel degrees; left is negative, not steering-wheel rotation.
        send("steering", angle?.takeIf { it.isFinite() }, "Vehicle/SteeringWheel front-wheel degrees", origin)
    }
    private fun doorFailed(area: DoorArea, error: Exception) = onInput {
        registeredDoors.remove(area)
        doorsSubscribed = false
        fail("door:" + area.values.joinToString("+") { it.name }, error)
    }
    private val beltListener = object : SeatBeltBuckledStateListener {
        override fun onSeatBeltBuckledStateUpdated(state: Boolean?) = send("belt", state, "Vehicle/Seat", "CALLBACK")
        override fun onFailed(e: Exception) = beltFailed(e)
    }
    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = send("turn", state?.name, "Vehicle/TurnSignal", "CALLBACK")
        override fun onFailed(e: Exception) = turnFailed(e)
    }
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) = steeringAngle(angle, "CALLBACK")
        override fun onFailed(e: Exception) = steeringFailed(e)
    }
    private val locationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            if (!collectingLocation) return
            send("location", location?.let { "${it.latitude},${it.longitude},${it.heading},${it.confidence},${it.timestampMillis}" }, "FusedLocation/1.1.3", "CALLBACK")
        }
        override fun onError(exception: Exception) = fail("location", exception)
    }
    private val lastLocationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            if (!collectingLocation) return
            send("location", location?.let { "${it.latitude},${it.longitude},${it.heading},${it.confidence},${it.timestampMillis}" }, "FusedLocation/1.1.3", "LAST_KNOWN")
        }
        override fun onError(exception: Exception) = fail("location", exception)
    }
    private val carListener = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val key = if (value.propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) "speed" else "gear"
            send(key, if (value.status == CarPropertyValue.STATUS_AVAILABLE) value.value else null, "AAOS/CarPropertyManager", "CALLBACK")
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
            navigationDestinationMatches = distanceMeters(PracticeCourse.finish, GeoPoint(destinationInfo.latitude, destinationInfo.longitude)) <= 50
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
            navigationWaypointsMatch = PracticeCourse.turns.all { turn -> waypointInfoList.any { distanceMeters(turn.corner, GeoPoint(it.latitude, it.longitude)) <= 50 } }
            verifyNavigationRoute()
        }
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = onNavigation {
            send("navigation_tbt", JSONArray().also { array -> tbtInfoList.forEach { array.put(JSONObject().put("type", it.type.name).put("distance", it.distance).put("description", it.description)) } }.toString(), "NaviHelper/3.0.5", "CALLBACK")
        }
    }

    private fun verifyNavigationRoute() {
        if (navigationRequestActive && navigationRouteActive && navigationDestinationMatches && navigationWaypointsMatch) {
            navigationRequestActive = false
            send("navigation_request", "ROUTE_READY", "NaviHelper/3.0.5 destination and two waypoints confirmed", "CALLBACK")
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
        vehicle.initialize()
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

    /** A user action: configure the fixed course only while parked, otherwise show the existing route. */
    fun openNavigation(withCourseRoute: Boolean) = onNavigation {
        pendingNavigationScreen = NavigationScreenRequest.Foreground
        pendingNavigationOpen = withCourseRoute
        if (withCourseRoute) send("navigation_request", "CONNECTING", "NaviHelper/3.0.5", "REQUEST")
        else send("navigation_screen", "OPENING", "NaviHelper/3.0.5", "REQUEST")
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
        if (screen == NavigationScreenRequest.Settings) {
            val signals = (context.applicationContext as? LicenseApp)?.signals?.value.orEmpty()
            if (!canReview(signals, SystemClock.elapsedRealtime())) {
                send("navigation_screen", "SETTINGS_BLOCKED_WHILE_MOVING", "NaviHelper/3.0.5", "REQUEST")
                return
            }
        } else if (screen == NavigationScreenRequest.Foreground) navigationOpenedAt = SystemClock.elapsedRealtime()
        // SDK foreground requests are recorded separately from Android launch requests and actual rendering.
        runCatching {
            navigation.requestNavigationScreen(screen)
            send("navigation_screen", if (screen == NavigationScreenRequest.Settings) "SDK_SETTINGS_REQUESTED" else "SDK_FOREGROUND_REQUESTED", "NaviHelper/3.0.5", "REQUEST")
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
            val destination = RouteInfo(latitude = PracticeCourse.finish.lat, longitude = PracticeCourse.finish.lon, poiName = "양재 좌·우회전 검증 코스 도착", poiId = "", address = "서울 서초구 서운로", poiSubId = "")
            val waypoints = PracticeCourse.turns.mapIndexed { index, turn ->
                RequestWaypointInfo(latitude = turn.corner.lat, longitude = turn.corner.lon, poiName = "${turn.direction.label} 검증 지점", waypointIndex = if (index == 0) WaypointIndex.FIRST else WaypointIndex.SECOND, poiId = "", address = "서울 서초구 ${if (index == 0) "남부순환로347길·강남대로37길" else "강남대로37길·서운로"}", poiSubId = "")
            }
            navigation.requestRouteWithWaypoints(RouteWithWaypointsRequest(destination, waypoints))
            send("navigation_request", "REQUESTED", "NaviHelper/3.0.5 destination and two waypoints", "REQUEST")
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
            fused.getLastKnownLocation(lastLocationListener)
            fused.registerFusedLocationCallback(locationListener)
        }.onFailure { fail("location", Exception(it)) }
    }
    fun stopLocationUpdates() {
        collectingLocation = false
        runCatching { fused.unregisterFusedLocationCallback(locationListener) }
        send("location", null, "FusedLocation/not recording")
    }
    private fun discover() {
        if (!beltSubscribed && !beltPending) {
            beltPending = true
            runCatching {
                vehicle.getSeat().checkSeatBeltBuckledCapabilities(onComplete = { config, areas -> onInput {
                    beltPending = false
                    send("belt_capability", "${config.accessType}:${areas.joinToString { it.values.joinToString { v -> v.name } }}", "Vehicle/Seat")
                    if (config.accessType.name !in setOf("READ", "READ_WRITE")) {
                        beltFailed(UnsupportedOperationException("Seat belt read is not supported"))
                        return@onInput
                    }
                    seatArea = areas.firstOrNull { it.values.contentEquals(arrayOf(SeatValue.ROW_1_LEFT)) }
                    seatArea?.let { area ->
                        if (beltAttempted) runCatching { vehicle.getSeat().unregisterSeatBeltBuckledState(area, beltListener) }
                        beltAttempted = true
                        beltSubscribed = true
                        runCatching { vehicle.getSeat().registerSeatBeltBuckledState(area, beltListener) }.onFailure { beltFailed(Exception(it)) }
                    } ?: send("belt", null, "Vehicle/Seat unsupported driver area")
                } }, onFailed = { error -> onInput { beltPending = false; fail("belt_capability", error); beltFailed(error) } })
            }.onFailure { beltPending = false; beltFailed(Exception(it)) }
        }
        if (!doorsSubscribed && !doorsPending) {
            doorsPending = true
            runCatching {
                vehicle.getDoor().checkDoorPositionCapabilities(onComplete = { config, areas -> onInput {
                    doorsPending = false
                    send("door_capability", "${config.accessType}:${areas.keys.joinToString { it.values.joinToString { v -> v.name } }}", "Vehicle/Door")
                    if (config.accessType.name !in setOf("READ", "READ_WRITE")) {
                        doorsSubscribed = false
                        fail("door_capability", UnsupportedOperationException("Door read is not supported"))
                        return@onInput
                    }
                    for (area in areas.keys) {
                        if (registeredDoors.contains(area)) continue
                        val key = "door:" + area.values.joinToString("+") { it.name }
                        val existing = doors[area]
                        val listener = existing ?: object : DoorPositionListener {
                            override fun onPositionUpdated(pos: Int?) = send(key, pos, "Vehicle/Door", "CALLBACK")
                            override fun onFailed(e: Exception) = doorFailed(area, e)
                        }
                        doors[area] = listener
                        if (existing != null) runCatching { vehicle.getDoor().unregisterDoorPosition(area, listener) }
                        registeredDoors.add(area)
                        runCatching { vehicle.getDoor().registerDoorPosition(area, listener) }.onFailure { doorFailed(area, Exception(it)) }
                    }
                    doorsSubscribed = areas.isNotEmpty() && registeredDoors.containsAll(areas.keys)
                } }, onFailed = { error -> onInput { doorsPending = false; doorsSubscribed = false; fail("door_capability", error) } })
            }.onFailure { doorsPending = false; doorsSubscribed = false; fail("door_capability", Exception(it)) }
        }
        if (!turnSubscribed && !turnPending) {
            turnPending = true
            send("turn_subscription", "PENDING", "Vehicle/TurnSignal subscription", "SUBSCRIPTION")
            runCatching {
                vehicle.getTurnSignal().checkTurnSignalStateCapabilities(onComplete = { config -> onInput {
                    turnPending = false
                    send("turn_capability", config.accessType.name, "Vehicle/TurnSignal")
                    if (config.accessType.name !in setOf("READ", "READ_WRITE")) {
                        turnFailed(UnsupportedOperationException("Turn signal read is not supported"))
                        return@onInput
                    }
                    if (turnAttempted) runCatching { vehicle.getTurnSignal().unregisterTurnSignalState(turnListener) }
                    turnAttempted = true
                    turnSubscribed = true
                    runCatching {
                        vehicle.getTurnSignal().registerTurnSignalState(turnListener)
                        if (turnSubscribed) send("turn_subscription", "READY", "Vehicle/TurnSignal subscription", "SUBSCRIPTION")
                    }.onFailure { turnFailed(Exception(it)) }
                } }, onFailed = { error -> onInput { turnPending = false; fail("turn_capability", error); turnFailed(error) } })
            }.onFailure { turnPending = false; turnFailed(Exception(it)) }
        }
        if (!steeringSubscribed && !steeringPending) {
            steeringPending = true
            send("steering_subscription", "PENDING", "Vehicle/SteeringWheel subscription", "SUBSCRIPTION")
            runCatching {
                vehicle.getSteeringWheel().checkSteeringWheelAngleCapabilities(onComplete = { config, bounds -> onInput {
                    steeringPending = false
                    send("steering_capability", "${config.accessType}:${bounds.minValue},${bounds.maxValue}", "Vehicle/SteeringWheel front-wheel degrees")
                    if (config.accessType.name !in setOf("READ", "READ_WRITE")) {
                        steeringFailed(UnsupportedOperationException("Front-wheel steering angle read is not supported"))
                        return@onInput
                    }
                    if (steeringAttempted) runCatching { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
                    steeringAttempted = true
                    steeringSubscribed = true
                    runCatching {
                        vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener)
                        if (steeringSubscribed) send("steering_subscription", "READY", "Vehicle/SteeringWheel subscription", "SUBSCRIPTION")
                    }.onFailure { steeringFailed(Exception(it)) }
                } }, onFailed = { error -> onInput { steeringPending = false; fail("steering_capability", error); steeringFailed(error) } })
            }.onFailure { steeringPending = false; steeringFailed(Exception(it)) }
        }
    }
    private fun readGuideSignals(now: Long) {
        if (!guideVisible()) {
            guideReadDue.clear()
            guideRequests.clear()
            return
        }
        fun <T> read(key: String, source: String, request: ((T?) -> Unit, (Exception) -> Unit, () -> Unit) -> Unit) {
            if (now < (guideReadDue[key] ?: 0L)) return
            guideRequests[key] = now
            guideReadDue[key] = now + 5000 // Retry if the SDK never completes the request.
            fun finish(signal: Signal, retryMs: Long) = onInput {
                if (guideRequests[key] == now) {
                    guideRequests.remove(key)
                    val received = SystemClock.elapsedRealtime()
                    guideReadDue[key] = received + retryMs
                    emit(key, signal.copy(source = source, receivedMs = received, origin = "READ"))
                }
            }
            val failed: (Exception) -> Unit = { error ->
                // The distributed 2.3.2 AAR exposes Exception; the public symbolic codes
                // survive obfuscation in its message. Other errors stay indeterminate.
                val unsupported = error.message == "ERROR_NOT_SUPPORTED_PROPERTY" || error.message == "ERROR_NOT_SUPPORTED_AREA"
                finish(Signal(quality = if (unsupported) "UNSUPPORTED" else "ERROR", error = error.message),
                    if (unsupported) 30000 else 4000)
            }
            try {
                request({ value -> finish(Signal(value?.toString(), if (value == null) "UNAVAILABLE" else "VALID"), 1500) },
                    failed, { finish(Signal(quality = "UNSUPPORTED"), 30000) })
            } catch (e: Exception) { failed(e) }
        }
        val safety = vehicle.getSafety()
        read<Boolean>(GUIDE_BELT_WARNING, "Vehicle/Safety") { value, failed, unsupported ->
            safety.checkSeatNotBuckledWarningCapabilities({ config, areas ->
                val driver = areas.firstOrNull { it.values.contentEquals(arrayOf(SeatValue.ROW_1_LEFT)) }
                if (driver != null && config.accessType in listOf(AccessType.READ, AccessType.READ_WRITE)) {
                    try { safety.hasSeatBeltNotBuckledWarning(driver, value, failed) } catch (e: Exception) { failed(e) }
                } else unsupported()
            }, failed)
        }
        read<Boolean>(GUIDE_WASHER_WARNING, "Vehicle/Safety") { value, failed, unsupported ->
            safety.checkLowWasherFluidWarningStateCapabilities({ config ->
                if (config.accessType in listOf(AccessType.READ, AccessType.READ_WRITE)) {
                    try { safety.hasLowWasherFluidWarning(value, failed) } catch (e: Exception) { failed(e) }
                } else unsupported()
            }, failed)
        }
        val light = vehicle.getLight()
        for (key in listOf(GUIDE_LOW_BEAM, GUIDE_HIGH_BEAM)) {
            read<LightState>(key, "Vehicle/Light") { value, failed, unsupported ->
                val checked: (CapabilityConfig, List<LightState>) -> Unit = { config, _ ->
                    if (config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE) {
                        try {
                            if (key == GUIDE_LOW_BEAM) light.getCurrentLowBeamLightState(value, failed)
                            else light.getCurrentHighBeamLightState(value, failed)
                        } catch (e: Exception) { failed(e) }
                    } else unsupported()
                }
                if (key == GUIDE_LOW_BEAM) light.checkLowBeamLightStateCapabilities(checked, failed)
                else light.checkHighBeamLightStateCapabilities(checked, failed)
            }
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            val now = SystemClock.elapsedRealtime()
            readGuideSignals(now)
            if (now >= nextDiscoveryAt && (!beltSubscribed || !doorsSubscribed || !turnSubscribed || !steeringSubscribed)) {
                nextDiscoveryAt = now + 5000
                discover()
            }
            seatArea?.let { area -> runCatching { vehicle.getSeat().getCurrentSeatBeltBuckledState(area, { send("belt", it, "Vehicle/Seat") }, { beltFailed(it) }) }.onFailure { beltFailed(Exception(it)) } }
            for (area in doors.keys.toList()) {
                val key = "door:" + area.values.joinToString("+") { it.name }
                runCatching { vehicle.getDoor().getCurrentDoorPosition(area, { send(key, it, "Vehicle/Door") }, { doorFailed(area, it) }) }.onFailure { doorFailed(area, Exception(it)) }
            }
            runCatching { vehicle.getTurnSignal().getCurrentTurnSignalState({ send("turn", it?.name, "Vehicle/TurnSignal") }, { turnFailed(it) }) }.onFailure { turnFailed(Exception(it)) }
            if (steeringSubscribed) runCatching {
                vehicle.getSteeringWheel().getCurrentSteeringWheelAngle({ steeringAngle(it, "READ") }, { steeringFailed(it) })
            }.onFailure { steeringFailed(Exception(it)) }
            properties?.let { p ->
                for ((id, key) in listOf(VehiclePropertyIds.PERF_VEHICLE_SPEED to "speed", VehiclePropertyIds.GEAR_SELECTION to "gear")) {
                    runCatching {
                        val v = p.getProperty<Any>(id, 0)
                        send(key, if (v.status == CarPropertyValue.STATUS_AVAILABLE) v.value else null, "AAOS/CarPropertyManager")
                    }.onFailure { fail(key, Exception(it)) }
                }
            }
            main.postDelayed(this, 1000)
        }
    }
    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        runCatching { seatArea?.let { vehicle.getSeat().unregisterSeatBeltBuckledState(it, beltListener) } }
        doors.forEach { (area, listener) -> runCatching { vehicle.getDoor().unregisterDoorPosition(area, listener) } }
        runCatching { vehicle.getTurnSignal().unregisterTurnSignalState(turnListener) }
        runCatching { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
        runCatching { fused.unregisterFusedLocationCallback(locationListener); fused.release() }
        navigationHandler.removeCallbacksAndMessages(null)
        // A remote navigation call may stall: releasing it must not hold up the vehicle collector.
        navigationHandler.post { runCatching { navigation.removeListener(navigationListener); navigation.release() } }
        navigationThread.quitSafely()
        runCatching { properties?.unregisterCallback(carListener); ux?.unregisterListener(); car?.disconnect() }
        vehicle.release()
        inputThread.quitSafely()
    }
}
