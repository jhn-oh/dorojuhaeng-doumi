package kr.juhyu.licensenote

import kotlin.math.ceil
import kotlin.math.roundToInt

data class PracticeRoute(
    val id: String,
    val name: String,
    val departure: String,
    val destination: String,
    val points: List<GeoPoint>,
    val turns: List<CourseTurn>,
    val rulesVersion: Int = 3,
    val routingSeconds: Double? = null,
    val waypoints: List<GeoPoint> = turns.map { it.corner },
    val distance: Int = points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }.roundToInt(),
    val verifiedArterial: Boolean = false,
) {
    val version = 1
    val finish get() = points.last()
    val minutes = ceil((routingSeconds ?: (distance / (15_000.0 / 3600) + 20 * waypoints.size)) / 60).toInt()
    val exercises = if (verifiedArterial) "편도 3~5차로 · 일반도로" else if (rulesVersion == 1) "왼쪽 차로 변경 1회" else turns.joinToString(" · ") { "${it.direction.label} 1회" }
    val stats = "${distance} m   약 ${minutes}분   $exercises"
}

object CourseCatalog {
    val full = PracticeRoute(PracticeCourse.id, PracticeCourse.name, "남부순환로347길 출발점", "서운로 북쪽 도착점",
        PracticeCourse.points, PracticeCourse.turns, routingSeconds = 56.8, distance = 413)
    val left = PracticeRoute("yangjae-left-01", "양재 좌회전", full.departure, "강남대로37길 도착점",
        PracticeCourse.points.take(3), PracticeCourse.turns.take(1), routingSeconds = 30.8, distance = 187)
    val right = PracticeRoute("yangjae-right-01", "양재 우회전", left.destination, "서운로 교차로 다음 지점",
        PracticeCourse.points.subList(2, 5), PracticeCourse.turns.takeLast(1), routingSeconds = 17.8, distance = 136)
    val lane = PracticeRoute("yangjae-lane-sim-01", "차로 변경 연습", "차로 변경 모의 구간 시작", "차로 변경 모의 구간 끝",
        listOf(LegacyLaneCourse.start, LegacyLaneCourse.entry, LegacyLaneCourse.exit, GeoPoint(37.48360, 127.03300)),
        emptyList(), rulesVersion = 1, waypoints = listOf(LegacyLaneCourse.entry))
    // 42dot destination from the Pleos NaviHelper example. OSRM car route, 2026-11-14.
    // The second waypoint continues south. Only the first left turn has a matching practice detector.
    val fortytwo = PracticeRoute("yangjae-42dot-01", "42dot까지 좌회전 연습", full.departure, "42dot 앞 남부순환로355길",
        listOf(
            GeoPoint(37.484442, 127.032112), GeoPoint(37.484911, 127.031894), GeoPoint(37.484714, 127.030432),
            GeoPoint(37.484625, 127.029796), GeoPoint(37.484611, 127.029695), GeoPoint(37.484220, 127.029776),
            GeoPoint(37.484007, 127.029828), GeoPoint(37.483856, 127.029859), GeoPoint(37.483884, 127.030076),
            GeoPoint(37.483896, 127.030170), GeoPoint(37.484045, 127.031343), GeoPoint(37.484081, 127.031534),
            GeoPoint(37.484149, 127.032222), GeoPoint(37.484291, 127.033019), GeoPoint(37.484347, 127.033445),
            GeoPoint(37.484415, 127.033619), GeoPoint(37.484474, 127.033791), GeoPoint(37.484496, 127.033853),
            GeoPoint(37.484538, 127.033954), GeoPoint(37.484551, 127.034036), GeoPoint(37.484568, 127.034182),
            GeoPoint(37.484707, 127.034076), GeoPoint(37.484802, 127.034028), GeoPoint(37.485135, 127.033849),
            GeoPoint(37.485499, 127.033667), GeoPoint(37.485542, 127.033776), GeoPoint(37.485777, 127.034381),
            GeoPoint(37.485815, 127.034433), GeoPoint(37.485957, 127.034810), GeoPoint(37.486105, 127.035193),
            GeoPoint(37.486251, 127.035551), GeoPoint(37.485570, 127.036159), GeoPoint(37.485497, 127.036189),
            GeoPoint(37.485422, 127.036208)
        ), turns = PracticeCourse.turns.take(1), routingSeconds = 155.0,
        waypoints = listOf(GeoPoint(37.484911, 127.031894), GeoPoint(37.484611, 127.029695)), distance = 1140)
    val all = listOf(full, right, left, lane, fortytwo) + ArterialCourses.routes
    fun destinations(query: String): List<PracticeRoute> = all.filter { route ->
        val text = query.trim()
        route.destination.contains(text, true) || route.name.contains(text, true) ||
            (route.verifiedArterial && route.departure.contains(text))
    }
    fun find(id: String?) = all.firstOrNull { it.id == id }

    fun recommendation(session: PracticeSession?): PracticeRoute? {
        if (session == null || !PracticeEvaluator.evaluate(session).mistake) return null
        if (session.rulesVersion == 1) return lane
        val goals = PracticeEvaluator.turnAssessments(session).filter { it.mistake }.map { it.event.direction }.toSet()
        return when (goals) {
            setOf(TurnDirection.LEFT) -> left
            setOf(TurnDirection.RIGHT) -> right
            setOf(TurnDirection.LEFT, TurnDirection.RIGHT) -> full
            else -> null
        }
    }
}

// Old lane-change reports were stored under the default turn-course ID.
val PracticeSession.practiceRoute get() = nativeDestination?.route() ?: if (rulesVersion == 1) CourseCatalog.lane else CourseCatalog.find(courseId) ?: CourseCatalog.full

data class CourseMatch(val routes: List<PracticeRoute> = emptyList(), val message: String = "")

/** Only accept conditions that the local catalog can substantiate. Retain unknown words for clarification. */
fun matchCourseRequest(request: String): CourseMatch {
    if (request.isBlank()) return CourseMatch(message = "원하는 연습이나 시간을 입력해 주세요.")
    var text = request.lowercase().trim().replace(Regex("[.,!?~]"), " ")
    val wideRoad = Regex("""(?:넓은\s*(?:도로|길)|다차로|왕복\s*여러\s*차로)""")
    val avoidHighway = Regex("""고속도로(?:는|를|가)?\s*(?:아직\s*)?(?:무서워요|무서워|빼\s*줘|빼고|제외하고|제외|피하고\s*싶어|피해서|없이|안\s*타고|말고)""")
    val wantsWide = wideRoad.containsMatchIn(text)
    val wantsLocal = avoidHighway.containsMatchIn(text)
    val yangjaeOnly = "양재" in text
    val arterialStop = listOf("올림픽공원", "잠실종합운동장", "역삼역").firstOrNull { it in text }
    if (arterialStop != null) text = text.replace(arterialStop, "").replace("까지", "")
    if (wantsWide || wantsLocal) text = text.replace(wideRoad, "").replace(avoidHighway, "")
    val approximate = listOf("정도", "쯤", "가량", "약 ", "대략").any { it in text }
    val toFortytwo = listOf("42dot", "포티투닷", "포티투도트").any { it in text }
    if (toFortytwo) text = text.replace(Regex("42dot|포티투닷|포티투도트|까지|갈래요|가고 싶어요|가고 싶어"), "")
    val duration = Regex("""(\d+)\s*분""").find(text)
    val minutes = duration?.groupValues?.get(1)?.toIntOrNull()
    if (duration != null && (minutes == null || minutes < 1)) return CourseMatch(message = "1분 이상으로 시간을 다시 입력해 주세요.")
    if (duration != null) text = text.removeRange(duration.range)
    val left = "좌회전" in text
    val right = "우회전" in text
    val lane = "차로 변경" in text || "차선 변경" in text || "차로변경" in text || "차선변경" in text
    val basic = "기본" in text || "좌·우회전" in text
    val words = Regex("""좌·우회전|좌회전|우회전|차[로선]\s*변경|기본|도로주행|운전|연습|코스|경로|양재|정도|되도록|다만|대략|가량|쯤|약\s|짜\s*줘|찾아\s*줘|만들어\s*줘|추천해\s*줘|하고\s*싶어요|하고\s*싶어|해\s*보고\s*싶어요|해\s*보고\s*싶어|해주세요|해\s*줘|할래요|할래|하고|이랑|하고요|그리고|와|과|만|에서|으로|로|를|을|에|요|총|\s""")
    if (text.replace(words, "").isNotEmpty() || (!wantsWide && !wantsLocal && arterialStop == null && !toFortytwo && !basic && !lane && !left && !right && minutes == null))
        return CourseMatch(message = "요청의 모든 조건을 확인할 수 없어요. 연습 동작, 넓은 일반도로, 예상 시간 또는 등록 목적지로 찾아보세요.")
    val routes = CourseCatalog.all.filter { route ->
        (!(wantsWide || wantsLocal) || route.verifiedArterial) &&
            (!yangjaeOnly || !route.verifiedArterial) && (arterialStop == null || route.destination.contains(arterialStop)) &&
            (!toFortytwo || route == CourseCatalog.fortytwo) && (!basic || route == CourseCatalog.full) && (!lane || route.rulesVersion == 1) &&
            (!left || route.turns.any { it.direction == TurnDirection.LEFT }) &&
            (!right || route.turns.any { it.direction == TurnDirection.RIGHT }) &&
            (minutes == null || route.minutes == minutes ||
                (approximate && kotlin.math.abs(route.minutes - minutes) <= (minutes * 0.1).toInt().coerceAtMost(3)))
    }.filter { route ->
        when {
            left && !right && !basic && "만" in text -> route.turns.all { it.direction == TurnDirection.LEFT }
            right && !left && !basic && "만" in text -> route == CourseCatalog.right
            else -> true
        }
    }.sortedWith(compareBy({ if (minutes == null) 0 else kotlin.math.abs(it.minutes - minutes) }, { it.distance }))
    return if (routes.isEmpty()) CourseMatch(message = "이 조건을 모두 충족하는 등록 코스가 없어요. 시간이나 연습 동작을 바꿔 다시 요청해 주세요.")
    else CourseMatch(routes)
}
