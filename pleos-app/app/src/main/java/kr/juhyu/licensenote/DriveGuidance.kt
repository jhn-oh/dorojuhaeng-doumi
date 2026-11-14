package kr.juhyu.licensenote

import kotlin.math.*

internal data class DriveCue(val title: String, val source: String, val turnKey: String? = null, val metres: Int? = null)
private data class Projection(val distanceAlong: Double, val offset: Double, val heading: Double)

private fun projectOnCourse(route: PracticeRoute, point: GeoPoint): List<Projection> {
    var travelled = 0.0
    val scaleX = 111_195.0 * cos(Math.toRadians(point.lat))
    return route.points.zipWithNext().mapNotNull { (a, b) ->
        val x = (b.lon - a.lon) * scaleX
        val y = (b.lat - a.lat) * 111_195.0
        val length = hypot(x, y)
        if (length < 0.01) return@mapNotNull null
        val px = (point.lon - a.lon) * scaleX
        val py = (point.lat - a.lat) * 111_195.0
        val t = ((px * x + py * y) / (length * length)).coerceIn(0.0, 1.0)
        val match = Projection(travelled + t * length, hypot(px - t*x, py - t*y), bearingDegrees(a,b))
        travelled += length
        match
    }
}

private fun markerProgress(route: PracticeRoute, point: GeoPoint) =
    projectOnCourse(route, point).minByOrNull { it.offset }?.takeIf { it.offset <= 8 }?.distanceAlong

internal fun nextPracticeCue(route: PracticeRoute, point: GeoPoint, accuracy: Double, heading: Double? = null): DriveCue {
    val unavailable = DriveCue("코스 위치 확인 중", "코스와 현재 위치가 맞는지 확인하고 있어요.")
    if (!accuracy.isFinite() || accuracy !in 0.01..18.0 || !point.lat.isFinite() || !point.lon.isFinite()) return unavailable
    val matches = projectOnCourse(route, point).sortedBy { it.offset }
    val best = matches.firstOrNull() ?: return unavailable
    if (best.offset > 12) return DriveCue("연습 코스를 벗어났어요", "연습 지점 거리 표시를 멈췄어요.")
    val candidates = matches.filter { it.offset <= best.offset + maxOf(2.0, accuracy * .5) }
    if (candidates.any { abs(it.distanceAlong - best.distanceAlong) > maxOf(18.0, accuracy * 2) }) return unavailable
    if (heading != null && heading.isFinite() && candidates.none { abs(headingChangeDegrees(it.heading, heading)) < 75 }) return unavailable
    val targets = if (route.rulesVersion == 1) listOf(Triple("차로 변경", LegacyLaneCourse.entry, "LANE"))
        else route.turns.map { Triple(it.direction.label, it.corner, it.direction.name) }
    val remaining = targets.mapNotNull { (label, position, key) ->
        markerProgress(route, position)?.let { Triple(label, it - best.distanceAlong, key) }
    }.firstOrNull { it.second >= -maxOf(6.0, accuracy) }
    if (remaining == null) {
        val meters = markerProgress(route, route.finish)?.minus(best.distanceAlong) ?: return unavailable
        return if (meters <= maxOf(8.0, accuracy)) DriveCue("연습 도착 지점 부근", "선택 코스 · 차량 위치 기준")
        else DriveCue("도착 지점까지 약 ${roundedMetres(meters)} m", "선택 코스 · 차량 위치 기준")
    }
    val (label, distance, key) = remaining
    val close = distance <= maxOf(8.0, accuracy)
    return DriveCue(if (close) "$label 연습 지점 부근" else "약 ${roundedMetres(distance)} m 앞 $label 연습",
        "선택 코스 · 차량 위치 기준", key, distance.roundToInt().coerceAtLeast(0))
}

private fun roundedMetres(distance: Double) = maxOf(5, (distance / 5.0).roundToInt() * 5)

internal fun driveCue(session: PracticeSession, signals: Map<String, Signal>, now: Long): DriveCue {
    val fix = signals["location"]
    val point = locationPoint(fix, now) ?: return DriveCue("위치 신호 대기 중", "연습 구간을 확인하고 있어요.")
    if (fix == null || fix.receivedMs < session.startedAtMs) return DriveCue("위치 신호 대기 중", "이번 회차의 위치를 기다리고 있어요.")
    val fields = fix.value.orEmpty().split(',')
    val heading = if (signals["speed"].fresh(now) && (signals["speed"]?.value?.toDoubleOrNull() ?: 0.0) > 2.0)
        fields.getOrNull(2)?.toDoubleOrNull()?.takeIf { it in 0.0..360.0 } else null
    val practice = nextPracticeCue(session.practiceRoute, point, fields.getOrNull(3)?.toDoubleOrNull() ?: Double.NaN, heading)
    // The first provider TBT is usable only when the provider independently sees the same car,
    // active destination/waypoints, direction, and nearby course distance.
    val native = signals["course_tbt"]?.takeIf { it.fresh(now, 3500) && it.receivedMs >= session.startedAtMs }?.value?.split('|') ?: return practice
    val route = signals["navigation_route"]?.takeIf { it.fresh(now, 6500) }?.value
    val status = signals["navigation_status"]
    if (route?.startsWith("true,") != true || status?.quality != "VALID" || status.value != "ROUTE_DRIVE" ||
        native.size != 3 || native[0] != session.courseId || native[1] != "TURN_${practice.turnKey}") return practice
    val nativePosition = signals["navigation_location"]?.takeIf { it.fresh(now, 3500) && it.receivedMs >= session.startedAtMs }?.value?.split(',') ?: return practice
    val lat = nativePosition.getOrNull(0)?.toDoubleOrNull() ?: return practice
    val lon = nativePosition.getOrNull(1)?.toDoubleOrNull() ?: return practice
    if (!lat.isFinite() || !lon.isFinite() || distanceMeters(point, GeoPoint(lat, lon)) > 18) return practice
    val distance = native[2].toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..3000.0 } ?: return practice
    val estimate = practice.metres ?: return practice
    if (abs(distance-estimate) > maxOf(15.0, 0.20*estimate)) return practice
    val direction = if (practice.turnKey == "LEFT") "좌회전" else "우회전"
    return DriveCue(if (distance < 5.0) "$direction 지점 부근" else "${distance.roundToInt()} m 후 ${direction}이에요", "기본 내비 안내", practice.turnKey, estimate)
}
