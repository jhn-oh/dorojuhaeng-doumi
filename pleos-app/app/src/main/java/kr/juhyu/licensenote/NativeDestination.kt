package kr.juhyu.licensenote

import org.json.JSONObject

/** The provider supplies a destination point and remaining distance, not a complete route polyline. */
data class NativeDestination(val name: String, val point: GeoPoint, val meters: Int, val seconds: Int) {
    fun route() = PracticeRoute("native-destination-v1", name, "현재 기본 내비 위치", name,
        points = listOf(point), turns = emptyList(), rulesVersion = 4, distance = meters,
        routingSeconds = seconds.toDouble(), waypoints = emptyList())
    fun samePlace(other: NativeDestination) = name == other.name && distanceMeters(point, other.point) < 25
    fun json() = JSONObject().put("name", name).put("latitude", point.lat).put("longitude", point.lon)
        .put("remainDistance", meters).put("remainTime", seconds)
    companion object {
        fun parse(json: JSONObject): NativeDestination? {
            val lat = json.optDouble("latitude")
            val lon = json.optDouble("longitude")
            val name = json.optString("name").trim().take(150)
            val distance = json.optInt("remainDistance", -1)
            val time = json.optInt("remainTime", -1)
            if (name.isEmpty() || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
                (lat == 0.0 && lon == 0.0) || distance < 0 || time < 0) return null
            return NativeDestination(name, GeoPoint(lat, lon), distance, time)
        }
    }
}

internal fun nativeDestinationEvent(signals: Map<String, Signal>, requested: Long, now: Long, selectedName: String? = null, selectedMeters: Int? = null): Signal? {
    if (requested <= 0 || now - requested !in 0L..600_000L) return null
    val sent = signals["navigation_screen"]?.takeIf { it.quality == "VALID" && it.value == "SDK_SEARCH_REQUESTED" && it.receivedMs in requested..now } ?: return null
    val changed = signals["navigation_destination_changed"]?.takeIf { it.quality == "VALID" && it.receivedMs in sent.receivedMs..now }
    val started = signals["navigation_search"]?.takeIf { it.quality == "VALID" && it.receivedMs in sent.receivedMs..now }
    // onRouteStarted.name and the new onDestinationInfo.name both come from the provider.
    // RouteStartInfo.distance/duration may change; never infer a selection from the query text.
    val startLabel = started?.value?.substringBeforeLast(',')?.substringBeforeLast(',')
    val startMeters = started?.value?.substringBeforeLast(',')?.substringAfterLast(',')?.toIntOrNull()
    val sameRouteStart = !selectedName.isNullOrBlank() && selectedName == startLabel &&
        selectedMeters != null && selectedMeters > 0 && startMeters == selectedMeters
    if (changed == null && !sameRouteStart) return null
    val changedAt = maxOf(changed?.receivedMs ?: 0L, started?.receivedMs ?: 0L)
    if (started != null && started.receivedMs > (changed?.receivedMs ?: 0) && !sameRouteStart) return null
    val status = signals["navigation_status"]?.takeIf { it.quality == "VALID" && it.receivedMs in requested..now }
    val route = signals["navigation_route"]?.takeIf { it.fresh(now, 10_000) && it.receivedMs >= changedAt }
    // Some Connect-S providers emit ROUTE_GUIDING mode without a NavigationStatus callback.
    // Only a newly confirmed destination change plus active guidance can adopt a searched destination.
    if (status?.value != "ROUTE_DRIVE" && route?.value?.substringAfter(',') != "ROUTE_GUIDING") return null
    if (status != null && status.value != "ROUTE_DRIVE" && (route == null || status.receivedMs >= route.receivedMs)) return null
    val end = signals["navigation_request"]?.takeIf { it.value in setOf("CANCELLED", "ARRIVED") }
    if (end != null && end.receivedMs >= changedAt) return null
    return signals["navigation_destination"]?.takeIf { it.fresh(now, 10_000) && it.receivedMs >= changedAt }
}
internal fun selectedNativeDestination(signals: Map<String, Signal>, requested: Long, now: Long): NativeDestination? {
    val event = signals["navigation_destination"] ?: return null
    val destination = runCatching { NativeDestination.parse(JSONObject(event.value ?: "")) }.getOrNull() ?: return null
    if (destination.meters <= 0 || nativeDestinationEvent(signals, requested, now, destination.name, destination.meters) == null) return null
    return destination
}
