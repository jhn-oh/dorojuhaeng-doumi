package kr.juhyu.licensenote

import kotlin.math.abs

internal const val PRACTICE_SCORE_RULES = 6
internal data class PracticeFinding(val key: String, val title: String, val detail: String,
                                    val atMs: Long, val penalty: Int, val goal: String)
internal data class PracticeReview(val score: Int?, val checked: List<String>, val unassessed: List<String>,
                                   val findings: List<PracticeFinding>) {
    val goals get() = findings.map { it.goal }.distinct().take(3)
    val explanation get() = if (score == null) "주행 자료가 부족해 점수를 보류했어요. 확인한 기록과 복습 항목은 남겨요."
        else "확인한 ${checked.size}개 항목 기준의 앱 자체 연습 지표예요. 공식 시험 점수나 안전 운전 판정이 아니에요."
}

/** Versioned feedback from saved observations. Missing signals never count as success. */
internal fun practiceReview(s: PracticeSession): PracticeReview {
    val all = listOf("벨트", "문", "가감속", "회전 깜빡이")
    val end = s.endedAtMs ?: return PracticeReview(null, emptyList(), all, emptyList())
    val records = s.samples.filter { it.atMs in s.startedAtMs..end }.groupBy { it.key }
        .mapValues { (_, rows) -> rows.associateBy { it.atMs }.values.sortedBy { it.atMs } }
    fun value(key: String, at: Long): String? {
        val rows = records[key] ?: return null
        val found = rows.binarySearchBy(at) { it.atMs }
        val row = rows.getOrNull(if (found >= 0) found else -found - 2) ?: return null
        return row.value.takeIf { row.quality == "VALID" && at - row.atMs in 0..2500 }
    }
    val speeds = records["speed"].orEmpty().filter { it.quality == "VALID" &&
        it.value?.toDoubleOrNull()?.let { n -> n.isFinite() && n in 0.0..100.0 } == true }
    val moving = speeds.filter { it.value!!.toDouble() > 1.4 && value("gear", it.atMs) == "8" }
    val checked = mutableListOf<String>()
    val findings = mutableListOf<PracticeFinding>()
    fun covered(predicate: (Long) -> Boolean) = moving.size >= 5 && moving.count { predicate(it.atMs) } >= moving.size * .8
    if (covered { value("belt", it) in setOf("true", "false") }) checked += "벨트"
    if (s.expectedDoors.isNotEmpty() && covered { at -> s.expectedDoors.all { value(it, at) in setOf("0", "1") } }) checked += "문"
    fun sustained(key: String, title: String, detail: String, goal: String, test: (Long) -> Boolean) {
        val pair = moving.zipWithNext().firstOrNull { (a, b) -> b.atMs - a.atMs in 800..2500 && test(a.atMs) && test(b.atMs) }
        if (pair != null) findings += PracticeFinding(key, title, detail, pair.first.atMs, 20, goal)
    }
    sustained("belt", "주행 중 벨트 미체결 신호", "움직이는 동안 운전석 버클이 풀린 신호가 연속으로 수신됐어요.",
        "출발 전 벨트 체결을 확인하고 주행 중 유지하기") { value("belt", it) == "false" }
    sustained("door", "주행 중 문 열림 신호", "움직이는 동안 문 열림 신호가 연속으로 수신됐어요.",
        "출발 전 모든 문이 닫혔는지 확인하기") { at -> s.expectedDoors.any { value(it, at) == "1" } }
    val motionPairs = speeds.zipWithNext().filter { (a,b) -> b.atMs-a.atMs in 700..2500 &&
        value("gear", a.atMs) == "8" && value("gear", b.atMs) == "8" }
    if (motionPairs.size >= 5) checked += "가감속"
    var lastMotion = Long.MIN_VALUE / 2
    for ((a,b) in motionPairs) {
        val acceleration = (b.value!!.toDouble()-a.value!!.toDouble()) / ((b.atMs-a.atMs)/1000.0)
        if (abs(acceleration) >= 3.0 && b.atMs-lastMotion >= 5000) {
            val braking = acceleration < 0
            findings += PracticeFinding(if (braking) "braking" else "acceleration",
                if (braking) "급감속 관측" else "급가속 관측", "차속 변화가 크게 기록됐어요. 주변 상황과 불가피한 조작이었는지 주차 후 확인하세요.",
                b.atMs, 5, if (braking) "앞차와 거리를 두고 부드럽게 감속하기" else "가속 페달을 천천히 밟기")
            lastMotion = b.atMs
        }
    }
    // Local loops have no predefined corners. Require movement, heading change and matching steering.
    val positions = records["location"].orEmpty().filter { it.quality == "VALID" }
    var lastTurn = Long.MIN_VALUE / 2
    var observedTurns = 0
    for ((index, a) in positions.withIndex()) {
        if (a.atMs <= lastTurn+5000 || value("speed", a.atMs)?.toDoubleOrNull()?.let { it > 2 } != true) continue
        val heading = a.value?.split(',')?.getOrNull(2)?.toDoubleOrNull()?.takeIf { it in 0.0..360.0 } ?: continue
        val p = locationPoint(Signal(a.value, "VALID", a.source, a.atMs), a.atMs) ?: continue
        var candidate: RecordedSignal? = null
        for (j in index+1 until positions.size) {
            val next = positions[j]
            if (next.atMs-a.atMs > 8000) break
            val h = next.value?.split(',')?.getOrNull(2)?.toDoubleOrNull()
            if (next.atMs-a.atMs >= 3000 && h != null && abs(headingChangeDegrees(heading,h)) in 50.0..140.0) { candidate=next; break }
        }
        val b = candidate ?: continue
        val q = locationPoint(Signal(b.value, "VALID", b.source, b.atMs), b.atMs) ?: continue
        if (distanceMeters(p,q) < 15 || value("gear", b.atMs) != "8") continue
        val delta = headingChangeDegrees(heading,b.value!!.split(',')[2].toDouble())
        val side = if (delta < 0) "LEFT" else "RIGHT"
        val steering = records["steering"].orEmpty().count { it.atMs in a.atMs..b.atMs && it.quality == "VALID" &&
            it.value?.toDoubleOrNull()?.let { angle -> if (side == "LEFT") angle >= 15 else angle <= -15 } == true }
        if (steering < 2 || a.atMs-2000 < s.startedAtMs || b.atMs+2000 > end) continue
        val window = speeds.filter { it.atMs in (a.atMs-2000)..(b.atMs+2000) }
        if (window.size < 5 || window.first().atMs > a.atMs-1000 || window.last().atMs < b.atMs+1000 ||
            window.zipWithNext().any { (x,y) -> y.atMs-x.atMs > 2500 } ||
            window.any { value("turn",it.atMs) !in setOf("OFF","LEFT","RIGHT") }) continue
        observedTurns++
        if (window.all { value("turn",it.atMs) == "OFF" }) findings += PracticeFinding("signal-${side.lowercase()}",
            "${if (side == "LEFT") "좌" else "우"}회전 추정 구간 깜빡이 꺼짐", "위치·진행방향·조향이 함께 바뀐 구간 전후에 깜빡이가 계속 꺼져 있었어요.",
            a.atMs, 10, "회전 전 주변을 확인하고 깜빡이를 미리 켜기")
        lastTurn = b.atMs
    }
    if (observedTurns > 0) checked += "회전 깜빡이"
    val speedCoverage = speeds.firstOrNull()?.atMs?.let { it-s.startedAtMs <= 2500 } == true &&
        speeds.lastOrNull()?.atMs?.let { end-it <= 2500 } == true && speeds.zipWithNext().all { (a,b) -> b.atMs-a.atMs <= 2500 }
    val usable = moving.size >= 5 && end-s.startedAtMs >= 10_000 && checked.size >= 2 && speedCoverage && !s.interrupted && !s.safetyIssue
    val saved = findings.groupBy { it.key }.values.flatMap { rows -> rows.take(if (rows.first().penalty <= 10) 3 else 1) }.sortedBy { it.atMs }
    return PracticeReview(if (usable) (100-saved.sumOf { it.penalty }).coerceIn(0,100) else null, checked, all-checked.toSet(), saved)
}
