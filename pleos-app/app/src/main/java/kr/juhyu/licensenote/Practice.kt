package kr.juhyu.licensenote

import java.util.UUID
import kotlin.math.*

data class GeoPoint(val lat: Double, val lon: Double)
object PracticeCourse {
    const val id = "yangjae-turn-course-01"
    const val version = 1
    const val name = "양재 좌·우회전"
    const val description = "지도 기반 에뮬레이터 검증 코스"
    // Connected OSM road geometry; legal turns and public-road suitability are not reviewed.
    val start = GeoPoint(37.4844415, 127.0321124)
    val finish = GeoPoint(37.4860285, 127.0293493)
    val turns = listOf(
        CourseTurn("left-01", TurnDirection.LEFT, GeoPoint(37.48460, 127.032045), GeoPoint(37.4849111, 127.0318935), GeoPoint(37.48485, 127.03144)),
        CourseTurn("right-01", TurnDirection.RIGHT, GeoPoint(37.48468, 127.0302), GeoPoint(37.4846113, 127.0296948), GeoPoint(37.4850, 127.02961)),
    )
    val points = listOf(start, turns[0].corner, GeoPoint(37.4847141, 127.0304318), turns[1].corner, GeoPoint(37.4852267, 127.0295627), finish)
}
object LegacyLaneCourse {
    const val id = "yangjae-lane-sim-01"
    val start = GeoPoint(37.48200, 127.03300)
    val entry = GeoPoint(37.48260, 127.03300)
    val exit = GeoPoint(37.48320, 127.03300)
}

enum class TurnDirection(val label: String, val signal: String) { LEFT("좌회전", "LEFT"), RIGHT("우회전", "RIGHT") }
data class CourseTurn(val id: String, val direction: TurnDirection, val entry: GeoPoint, val corner: GeoPoint, val exit: GeoPoint)
data class TurnEvent(
    val id: String, val direction: TurnDirection, val entryMs: Long,
    val exitMs: Long? = null, val entryHeadingDeg: Double? = null,
    val headingChangeDeg: Double? = null, val steeringPeakDeg: Double? = null,
    val observation: Observation = Observation.UNANSWERED,
    val evidenceReason: String? = null,
    val evidenceStatus: String = "PENDING",
)
data class TurnAssessment(val event: TurnEvent, val status: String, val mistake: Boolean, val reason: String) {
    val practiceSuggested get() = mistake || status == "SIGNAL_ABSENT"
}
data class TargetOutcome(val courseTurnId: String, val label: String, val previousStatus: String?, val currentStatus: String)

data class RecordedSignal(val atMs: Long, val key: String, val value: String?, val quality: String, val source: String, val origin: String)
data class Passage(val id: String, val entryMs: Long, val exitMs: Long? = null)
data class EvidenceGap(val start: Long, val end: Long)
enum class Observation { UNANSWERED, LEFT, NOT_LEFT, UNSEEN, CONFIRMED, NOT_PERFORMED }
enum class SessionPhase { RECORDING, REVIEW, COMPLETE, INTERRUPTED }
data class PracticeSession(
    val id: String = UUID.randomUUID().toString(),
    val courseId: String = PracticeCourse.id,
    val courseVersion: Int = PracticeCourse.version,
    val rulesVersion: Int = 1,
    val startedAtMs: Long,
    val startedWallMs: Long,
    val endedAtMs: Long? = null,
    val phase: SessionPhase = SessionPhase.RECORDING,
    val manualDoor: Boolean = false,
    val manualBelt: Boolean = false,
    val expectedDoors: List<String>,
    val samples: List<RecordedSignal> = emptyList(),
    val passage: Passage? = null,
    val observation: Observation = Observation.UNANSWERED,
    val interrupted: Boolean = false,
    val safetyIssue: Boolean = false,
    val dataGap: Boolean = false,
    val evidenceGaps: List<EvidenceGap> = emptyList(),
    val nextPractice: Boolean = false,
    val revision: Int = 1,
    val turns: List<TurnEvent> = emptyList(),
    val practiceTargets: List<String> = emptyList(),
    val previousSessionId: String? = null,
    val previousTargetStatuses: Map<String, String> = emptyMap(),
    val nativeDestination: NativeDestination? = null,
)
internal const val VEHICLE_REVIEW_RULES = 5
val PracticeSession.vehicleReview get() = rulesVersion >= VEHICLE_REVIEW_RULES
data class Assessment(val status: String, val score: Int?, val checked: Int, val mistake: Boolean, val title: String,
                      val reason: String, val practiceSuggested: Boolean = mistake)

fun practiceTargetLabel(id: String, route: PracticeRoute = CourseCatalog.full): String {
    val index = route.turns.indexOfFirst { it.id == id }
    val turn = route.turns.getOrNull(index) ?: return "회전"
    if (route.turns.size == 1) return turn.direction.label
    return "${if (index == 0) "첫 번째" else "두 번째"} ${turn.direction.label}"
}
fun practiceTargetIds(s: PracticeSession): List<String> = when {
    s.rulesVersion < 2 -> emptyList()
    s.nextPractice -> PracticeEvaluator.turnAssessments(s).filter { it.practiceSuggested }.map { it.event.id.substringAfterLast(':') }
    else -> s.practiceTargets
}
fun practiceTargetOutcomes(s: PracticeSession): List<TargetOutcome> {
    val results = PracticeEvaluator.turnAssessments(s)
    return s.practiceTargets.map { id ->
        val result = results.firstOrNull { it.event.id.endsWith(":$id") }
        TargetOutcome(id, practiceTargetLabel(id, s.practiceRoute), s.previousTargetStatuses[id],
            if (!s.vehicleReview && result?.event?.observation == Observation.UNANSWERED) "UNANSWERED" else result?.status ?: "NO_OPPORTUNITY")
    }
}

fun Signal?.fresh(now: Long, maxAge: Long = 2500): Boolean = this != null && quality == "VALID" && receivedMs <= now && now - receivedMs <= maxAge

internal fun Signal?.driverBeltFastened(now: Long) = fresh(now) && this?.value == "true"

internal data class DepartureDoors(val firstOpen: String?, val allClosed: Boolean) {
    fun canConfirm(manual: Boolean) = firstOpen == null && (allClosed || manual)
}

internal fun departureDoors(signals: Map<String, Signal>, now: Long): DepartureDoors {
    val positions = signals.filterKeys { it.startsWith("door:") }.mapValues { (_, signal) ->
        signal.takeIf { it.fresh(now) }?.value?.toIntOrNull()
    }
    val open = positions.entries.firstOrNull { (it.value ?: 0) > 0 }?.key
    return DepartureDoors(open, positions.keys.containsAll(cabinDoors) && positions.values.all { it == 0 })
}

fun canReview(signals: Map<String, Signal>, now: Long): Boolean {
    val speed = signals["speed"]
    val gear = signals["gear"]
    val ux = signals["ux"]
    // UX is an ON_CHANGE signal: its age alone does not mean the restriction changed.
    return speed.fresh(now) && abs(speed?.value?.toDoubleOrNull() ?: Double.POSITIVE_INFINITY) < 0.05 &&
        gear.fresh(now) && gear?.value == "4" && ux?.quality == "VALID" && ux.value == "false,0"
}
fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val lat = Math.toRadians((a.lat + b.lat) / 2)
    return hypot((a.lat - b.lat) * 111_195.0, (a.lon - b.lon) * 111_195.0 * cos(lat))
}
fun locationPoint(signal: Signal?, now: Long): GeoPoint? {
    if (!signal.fresh(now, 3000)) return null
    if (signal?.origin == "LAST_KNOWN") return null
    return locationPoint(signal?.value?.split(',') ?: return null)
}

private fun locationPoint(v: List<String>): GeoPoint? {
    val lat = v.getOrNull(0)?.toDoubleOrNull() ?: return null
    val lon = v.getOrNull(1)?.toDoubleOrNull() ?: return null
    if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    if ((v.getOrNull(4)?.toLongOrNull() ?: 0) <= 0) return null
    val accuracy = v.getOrNull(3)?.toDoubleOrNull() ?: return null
    if (!accuracy.isFinite() || accuracy <= 0 || accuracy > 25) return null
    return GeoPoint(lat, lon)
}

fun bearingDegrees(from: GeoPoint, to: GeoPoint): Double {
    val latitude = Math.toRadians((from.lat + to.lat) / 2)
    return (Math.toDegrees(atan2((to.lon - from.lon) * cos(latitude), to.lat - from.lat)) + 360) % 360
}
fun headingChangeDegrees(from: Double, to: Double): Double = ((to - from + 540) % 360) - 180

/** One explicit session, public SDK input only; clocks are injected for deterministic tests. */
class PracticeEngine(var session: PracticeSession, private val courseTurns: List<CourseTurn> = session.practiceRoute.turns) {
    private var lastTick = session.startedAtMs - 1000
    private var lastPoint: GeoPoint? = null
    private var lastPointAt: Long? = null
    private var lastLocationStamp: Long? = null
    private val trail = ArrayDeque<Pair<Long, GeoPoint>>()
    private val closedTurns = mutableSetOf<String>()

    private fun noteGap(start: Long, end: Long) {
        val from = start.coerceIn(session.startedAtMs, end)
        val last = session.evidenceGaps.lastOrNull()
        val gaps = if (last != null && from <= last.end + 1 && end >= last.start)
            session.evidenceGaps.dropLast(1) + EvidenceGap(minOf(last.start, from), maxOf(last.end, end))
        else session.evidenceGaps + EvidenceGap(from, end)
        session = session.copy(dataGap = true, evidenceGaps = gaps)
    }

    fun signalChanged(key: String, signal: Signal, previous: Signal?) {
        if (session.phase != SessionPhase.RECORDING || signal.receivedMs < session.startedAtMs) return
        val discrete = key in setOf("belt", "turn", "turn_subscription", "gear", "steering", "steering_subscription") || key.startsWith("door:")
        val changed = previous?.value != signal.value || previous?.quality != signal.quality
        if (discrete && changed && session.samples.size < 50_000) {
            session = session.copy(samples = session.samples + RecordedSignal(signal.receivedMs, key, signal.value, signal.quality, signal.source, signal.origin))
        }
        if (signal.quality != "VALID" && (key in setOf("turn", "turn_subscription", "speed", "gear") || key.startsWith("door:") || (key == "location" && session.passage != null && session.passage?.exitMs == null))) {
            noteGap(signal.receivedMs, signal.receivedMs)
        }
    }

    fun tick(signals: Map<String, Signal>, now: Long) {
        if (session.phase != SessionPhase.RECORDING || now - lastTick < 800) return
        val gap = now - lastTick > 2500 || session.samples.size >= 50_000
        if (gap) noteGap(lastTick, now)
        lastTick = now
        val keys = listOf("belt", "turn", "turn_subscription", "steering", "steering_subscription", "speed", "gear", "location") + session.expectedDoors +
            listOf("brake_pressed", "drive_mode", "odometer_km", "vehicle_usage_minutes").filter { signals.containsKey(it) }
        val next = keys.map { key ->
            val s = signals[key]
            // Registration is a state transition, unlike the periodically read vehicle measurements.
            val ready = key.endsWith("_subscription") && s?.quality == "VALID" && s.value == "READY"
            val valid = if (session.vehicleReview && key == "location") locationPoint(s, now) != null else ready || s.fresh(now)
            RecordedSignal(now, key, s?.value, if (valid) "VALID" else "UNAVAILABLE", s?.source ?: "unavailable", s?.origin ?: "")
        }
        session = session.copy(samples = if (session.samples.size < 50_000) session.samples + next else session.samples, dataGap = session.dataGap || gap)
        updatePosition(signals, now)
    }

    private fun updatePosition(signals: Map<String, Signal>, now: Long) {
        val point = locationPoint(signals["location"], now) ?: return
        val parts = signals.getValue("location").value!!.split(',')
        val stamp = parts[4].toLongOrNull() ?: return
        if (stamp == lastLocationStamp) return
        if (lastLocationStamp != null && stamp < lastLocationStamp!!) {
            noteGap(lastPointAt ?: now, now)
            return
        }
        lastLocationStamp = stamp
        val heading = parts.getOrNull(2)?.toDoubleOrNull() ?: return
        val headingNorth = heading in 0.0..45.0 || heading in 315.0..360.0
        val previous = lastPoint
        val previousAt = lastPointAt
        lastPoint = point
        lastPointAt = now
        if (previous != null && previousAt != null && (now - previousAt > 3500 || distanceMeters(previous, point) > 60.0 * ((now - previousAt) / 1000.0).coerceAtLeast(1.0))) {
            noteGap(previousAt, now)
            return
        }
        val moving = signals["speed"].fresh(now) && (signals["speed"]?.value?.toDoubleOrNull() ?: 0.0) > 0.5
        if (session.rulesVersion >= 2 && session.courseId != LegacyLaneCourse.id) {
            updateTurns(signals, point, moving, now)
            return
        }
        if (!moving || !headingNorth || (session.vehicleReview && (!signals["gear"].fresh(now) || signals["gear"]?.value != "8"))) return
        if (session.passage == null && distanceMeters(point, LegacyLaneCourse.entry) < 18) {
            session = session.copy(passage = Passage("${session.id}:lane-01", now))
        } else if (session.passage?.exitMs == null && session.passage != null && distanceMeters(point, LegacyLaneCourse.exit) < 18 && now - session.passage!!.entryMs >= 2000) {
            session = session.copy(passage = session.passage!!.copy(exitMs = now))
        }
    }

    private fun updateTurns(signals: Map<String, Signal>, point: GeoPoint, moving: Boolean, now: Long) {
        if (!moving || !signals["gear"].fresh(now) || signals["gear"]?.value != "8") {
            trail.clear()
            return
        }
        trail.addLast(now to point)
        while (trail.isNotEmpty() && now - trail.first().first > 15_000) trail.removeFirst()
        val behind = trail.lastOrNull { distanceMeters(it.second, point) >= 12.0 }
        val heading = behind?.let { bearingDegrees(it.second, point) } ?: return
        for (course in courseTurns) {
            val id = "${session.id}:${course.id}"
            if (id in closedTurns) continue
            val event = session.turns.firstOrNull { it.id == id }
            if (event == null) {
                val expectedHeading = bearingDegrees(course.entry, course.corner)
                if (distanceMeters(point, course.entry) <= 14 && abs(headingChangeDegrees(expectedHeading, heading)) <= 30) {
                    session = session.copy(turns = session.turns + TurnEvent(id, course.direction, now, entryHeadingDeg = heading))
                }
                continue
            }
            if (event.exitMs != null || distanceMeters(point, course.exit) > 12 || now - event.entryMs < 4000) continue
            closedTurns += id
            val delta = headingChangeDegrees(event.entryHeadingDeg ?: heading, heading)
            val steering = session.samples.filter { it.key == "steering" && it.atMs in event.entryMs..now }
            val validSteering = steering.filter { it.quality == "VALID" }.mapNotNull { s -> s.value?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { s.atMs to it } }
            val peak = validSteering.maxByOrNull { abs(it.second) }?.second
            // Prototype threshold, not a calibrated turn radius or lane-change detector.
            val matchingSteering = validSteering.filter { (_, angle) -> if (course.direction == TurnDirection.LEFT) angle <= -2.0 else angle >= 2.0 }
            val steeringSupported = steering.isNotEmpty() && steering.all { it.quality == "VALID" } && matchingSteering.size >= 2 &&
                matchingSteering.last().first - matchingSteering.first().first >= 600 && peak != null &&
                (if (course.direction == TurnDirection.LEFT) peak < 0 else peak > 0)
            val matchingHeading = if (course.direction == TurnDirection.LEFT) delta in -135.0..-55.0 else delta in 55.0..135.0
            val completed = matchingHeading && steeringSupported
            session = session.copy(turns = session.turns.map { if (it.id != id) it else it.copy(
                exitMs = if (completed) now else null, headingChangeDeg = delta, steeringPeakDeg = peak,
                evidenceReason = if (completed) "조향각과 위치 진행 방향 변화가 일치해요." else "회전으로 연결할 조향·위치 근거가 부족해요.",
                evidenceStatus = if (completed) "ESTIMATED" else "REJECTED",
            ) })
        }
    }

    fun finish(signals: Map<String, Signal>, now: Long): Boolean {
        if (!canReview(signals, now) || session.phase != SessionPhase.RECORDING) return false
        // A final snapshot belongs to the actual end, even if the last periodic tick just ran.
        lastTick = now - 1000
        tick(signals, now)
        val phase = if (session.vehicleReview || session.nativeDestination != null) SessionPhase.COMPLETE else SessionPhase.REVIEW
        session = session.copy(endedAtMs = now, phase = phase)
        return true
    }

    fun observe(answer: Observation, safetyIssue: Boolean = false, eventId: String? = null) {
        if (session.vehicleReview) return
        check(session.phase != SessionPhase.RECORDING)
        val unanswered = session.turns.firstOrNull { it.exitMs != null && it.observation == Observation.UNANSWERED }
        val target = session.turns.firstOrNull { it.id == eventId && it.exitMs != null } ?: unanswered ?: session.turns.firstOrNull { it.exitMs != null }
        val turns = if (session.rulesVersion >= 2 && target != null) session.turns.map { if (it.id == target.id) it.copy(observation = answer) else it } else session.turns
        val pending = session.rulesVersion >= 2 && turns.any { it.exitMs != null && it.observation == Observation.UNANSWERED }
        val updated = session.copy(observation = answer, turns = turns, safetyIssue = session.safetyIssue || safetyIssue, phase = if (pending) SessionPhase.REVIEW else SessionPhase.COMPLETE, revision = session.revision + 1)
        session = updated.copy(nextPractice = updated.nextPractice && PracticeEvaluator.evaluate(updated).practiceSuggested)
    }

    fun setSafetyIssue(reported: Boolean) {
        val updated = session.copy(safetyIssue = reported, revision = session.revision + 1)
        session = updated.copy(nextPractice = updated.nextPractice && PracticeEvaluator.evaluate(updated).practiceSuggested)
    }
}

object PracticeEvaluator {
    private fun covered(samples: List<RecordedSignal>, key: String, from: Long, until: Long, predicate: (String?) -> Boolean): Boolean {
        val values = samples.filter { it.key == key && it.atMs in from..until }.sortedBy { it.atMs }
        return values.size >= 2 && values.first().atMs <= from + 1200 && values.last().atMs >= until - 1200 &&
            values.all { it.quality == "VALID" && predicate(it.value) } && values.zipWithNext().all { (a, b) -> b.atMs - a.atMs <= 2500 }
    }
    fun evaluate(s: PracticeSession): Assessment {
        if (s.vehicleReview) return evaluateVehicleReview(s)
        if (s.nativeDestination != null) return if (s.interrupted || s.safetyIssue)
            Assessment("HELD", null, 0, false, "중단한 목적지 연습", "중단이나 안전 문제로 여기까지의 차량 기록을 보관했어요.")
        else Assessment("DESTINATION_PRACTICE", null, 0, false, "목적지까지 연습 기록", "기본 내비가 안내한 목적지와 차량 기록을 보관했어요.")
        if (s.rulesVersion >= 2) return evaluateTurns(s)
        fun held(reason: String) = Assessment("HELD", null, 0, false, "이번 점수는 보류했어요", reason)
        if (s.interrupted || s.safetyIssue) return held("중단이나 안전 문제가 있어, 여기까지의 기록만 남겼어요.")
        val end = s.endedAtMs ?: return held("아직 연습을 마치지 않았어요.")
        val passage = s.passage
        val exit = passage?.exitMs
        if (passage == null || exit == null) return Assessment("NO_OPPORTUNITY", null, 0, false, "이번에는 구간을 확인하지 못했어요", "위치와 진행 방향이 확인된 통과 기회가 없어요. 다음 연습에서 다시 기록해요.")
        if (s.observation == Observation.NOT_LEFT) return Assessment("NO_DEDUCTION", null, 0, false, "이 구간은\n감점하지 않았어요", "왼쪽으로 바꾸지 않았다고 답했어요. 이 기록은 오답에 넣지 않아요.")
        if (s.observation != Observation.LEFT) return held("채점에 필요한 동승자의 관찰이 부족해요. 확인된 차량 기록만 남겼어요.")
        if (s.dataGap) return held("기록 중 수신 공백이나 위치 불확실성이 있었어요.")
        val from = (passage.entryMs - 2000).coerceAtLeast(s.startedAtMs)
        val until = exit + 2000
        if (end < until) return held("구간 통과 뒤의 신호 기록이 충분하지 않아요.")
        val off = covered(s.samples, "turn", from, until) { it == "OFF" }
        if (!off) {
            val valid = covered(s.samples, "turn", from, until) { it in setOf("OFF", "LEFT", "RIGHT") }
            return if (valid && s.samples.any { it.key == "turn" && it.atMs in from..until && it.value == "LEFT" })
                Assessment("OBSERVED", null, 1, false, "깜빡이를 켠 기록이 있어요", "이번 구간에서 왼쪽 신호를 받았어요. 실제 조작 시점은 차량 기록으로 다시 볼 수 있어요.")
            else held("구간 전후의 좌측 신호를 연속으로 확인하지 못했어요.")
        }
        val passengerDoors = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_RIGHT").map { "door:$it" }
        val doorsCovered = !s.manualDoor && s.expectedDoors.containsAll(passengerDoors) && s.expectedDoors.all { key ->
            covered(s.samples, key, s.startedAtMs, end) { it == "0" }
        }
        val gear = s.samples.lastOrNull { it.key == "gear" && it.atMs == end }
        val parked = gear?.quality == "VALID" && gear.value == "4"
        if (!doorsCovered || !parked) return held("문 상태 또는 종료 시 P단을 자동으로 확인한 근거가 부족해요.")
        val unbuckledInMotion = unbuckledWhileMoving(s)
        if (unbuckledInMotion) return Assessment("HELD", null, 3, true, "이번 점수는 보류했어요", "주행 중 벨트 미착용 신호를 받았어요. 확인한 차로 변경 기록은 오답노트에 남겼어요.")
        val penalty = 7 // Version 1: criterion 30 with corroborated lane direction and signal coverage.
        val score = 100 - penalty
        return Assessment("SCORED", score, 3, true, "${score}점", "동승자가 왼쪽 변경을 확인했고, 구간 전후에 왼쪽 깜빡이는 계속 꺼져 있었어요.")
    }

    private fun gapIn(s: PracticeSession, from: Long, until: Long) =
        s.dataGap && (s.rulesVersion < 3 || s.evidenceGaps.isEmpty() || s.evidenceGaps.any { it.start <= until && it.end >= from })

    private fun unbuckledWhileMoving(s: PracticeSession): Boolean {
        val unbuckled = s.samples.asSequence().filter { it.key == "belt" && it.quality == "VALID" && it.value == "false" }
            .map { it.atMs }.toHashSet()
        return s.samples.any { it.key == "speed" && it.quality == "VALID" && it.atMs in unbuckled &&
            it.value?.toDoubleOrNull()?.let { v -> v.isFinite() && abs(v) > 0.5 } == true }
    }

    private fun evaluateVehicleReview(s: PracticeSession): Assessment {
        if (s.interrupted || s.safetyIssue) return Assessment("HELD", null, 0, false, "여기까지의 차량 기록",
            if (s.interrupted) "연습이 중단돼 수신한 기록만 남겼어요." else "안전 문제가 기록돼 구간별 평가는 보류했어요.")
        val end = s.endedAtMs ?: return Assessment("RECORDING", null, 0, false, "차량 기록 중", "주차 후 기록을 마치면 구간별로 볼 수 있어요.")
        val result = when {
            s.nativeDestination != null -> Assessment("DESTINATION_PRACTICE", null, 0, false, "목적지 연습 기록", "선택한 목적지와 차량 기록을 저장했어요.")
            s.practiceRoute.isLanePractice -> evaluateLaneSignals(s, end)
            s.practiceRoute.turns.isEmpty() -> Assessment("DRIVE_RECORDED", null, 0, false, "주행 기록", "이 코스의 연습 시간과 수신한 차량 정보를 저장했어요.")
            else -> {
                val turns = turnAssessments(s)
                val checked = turns.count { it.status == "OBSERVED" || it.status == "SIGNAL_ABSENT" }
                val suggested = turns.any { it.practiceSuggested }
                when {
                    checked == 0 -> Assessment("HELD", null, 0, false, "비교할 구간 기록이 부족해요",
                        if (turns.isEmpty()) "코스의 위치와 조향이 함께 확인된 회전 구간이 없어요." else "해당 구간의 신호가 부족하거나 일치하지 않아 복습을 보류했어요.")
                    else -> Assessment("VEHICLE_RECORDED", null, checked, false,
                        if (suggested) "다시 연습할 깜빡이" else "구간별 깜빡이 기록",
                        "차량 위치와 조향으로 추정한 회전 구간 ${checked}곳에서 방향지시등을 확인했어요.", practiceSuggested = suggested)
                }
            }
        }
        return if (unbuckledWhileMoving(s)) result.copy(reason = result.reason + " 주행 중 운전석 벨트 미착용 신호가 있었어요.") else result
    }

    private fun evaluateLaneSignals(s: PracticeSession, end: Long): Assessment {
        val passage = s.passage
        val exit = passage?.exitMs
        if (passage == null || exit == null) return Assessment("NO_OPPORTUNITY", null, 0, false, "차로 변경 연습 기록", "설정한 구간의 진입과 통과를 모두 확인하지 못했어요.")
        val from = passage.entryMs - 2000
        val until = exit + 2000
        if (from < s.startedAtMs || until > end || gapIn(s, from, until) ||
            !hasLocationCoverage(s, passage.entryMs, exit) ||
            !covered(s.samples, "turn_subscription", from, until) { it == "READY" } ||
            !covered(s.samples, "turn", from, until) { it in setOf("OFF", "LEFT", "RIGHT") }) {
            return Assessment("HELD", null, 0, false, "차로 변경 연습 기록", "구간 전후의 방향지시등 기록이 충분하지 않아요.")
        }
        val indicators = s.samples.filter { it.key == "turn" && it.atMs in from..until }.map { it.value }.toSet()
        val signal = when {
            "LEFT" in indicators && "RIGHT" in indicators -> "왼쪽·오른쪽 깜빡이가 각각 켜진 기록이 있어요."
            "LEFT" in indicators -> "왼쪽 깜빡이가 켜진 기록이 있어요."
            "RIGHT" in indicators -> "오른쪽 깜빡이가 켜진 기록이 있어요."
            else -> "깜빡이가 계속 꺼져 있었어요."
        }
        return Assessment("LANE_SIGNALS", null, 1, false, "차로 변경 연습 기록", "모의 구간 전후에 $signal 실제 차로 변경 여부는 차량 기록만으로 확인하기 어려워요.")
    }

    fun turnAssessments(s: PracticeSession): List<TurnAssessment> = s.turns.filter { it.exitMs != null }.map { event ->
        fun assessment(status: String, mistake: Boolean, reason: String) = TurnAssessment(event, status, mistake, reason)
        val end = s.endedAtMs
        val from = (event.entryMs - 2000).coerceAtLeast(s.startedAtMs)
        val until = event.exitMs!! + 2000
        when {
            !s.vehicleReview && event.observation == Observation.NOT_PERFORMED -> assessment("NO_TURN", false, "회전하지 않았다고 답했어요. 이 사건은 오답에서 제외해요.")
            !s.vehicleReview && event.observation != Observation.CONFIRMED -> assessment("HELD", false, "동승자의 회전 확인이 없어 미평가로 남겼어요.")
            s.interrupted || s.safetyIssue || end == null || end < until -> assessment("HELD", false, "중단·안전 문제 또는 회전 뒤 기록 부족으로 평가를 보류했어요.")
            s.vehicleReview && (event.entryMs - 2000 < s.startedAtMs || !hasVehicleTurnEvidence(s, event)) ->
                assessment("HELD", false, "위치와 조향 기록이 이 회전 구간을 충분히 뒷받침하지 못했어요.")
            gapIn(s, from, until) ->
                assessment("HELD", false, "이 회전 전후의 기록에 수신 공백이 있어 평가를 보류했어요.")
            s.rulesVersion >= 3 && !covered(s.samples, "turn_subscription", from, until) { it == "READY" } ->
                assessment("HELD", false, "회전 전후에 방향지시등 변경을 계속 받을 수 있었는지 확인하지 못했어요.")
            !covered(s.samples, "turn", from, until) { it in setOf("OFF", "LEFT", "RIGHT") } -> assessment("HELD", false, "회전 전후의 방향지시등 기록을 연속으로 확인하지 못했어요.")
            s.vehicleReview && s.samples.any { it.key == "turn" && it.atMs in from..until && it.value != "OFF" && it.value != event.direction.signal } ->
                assessment("HELD", false, "구간 방향과 다른 깜빡이 신호가 있어 복습을 보류했어요.")
            s.vehicleReview && covered(s.samples, "turn", from, until) { it == "OFF" } ->
                assessment("SIGNAL_ABSENT", false, "이 구간 전후에는 깜빡이가 계속 꺼져 있었어요.")
            s.vehicleReview -> assessment("OBSERVED", false, "이 구간 전후에 ${if (event.direction == TurnDirection.LEFT) "왼쪽" else "오른쪽"} 깜빡이가 켜진 기록이 있어요.")
            s.samples.any { it.key == "turn" && it.atMs in from..until && it.quality == "VALID" && it.value == event.direction.signal } -> assessment("OBSERVED", false, "${event.direction.label} 구간에서 ${if (event.direction == TurnDirection.LEFT) "왼쪽" else "오른쪽"} 깜빡이를 켠 기록이 있어요. 신호 시점이나 안전한 회전까지 확인한 것은 아니에요.")
            covered(s.samples, "turn", from, until) { it == "OFF" } -> assessment("OMISSION", true, "동승자가 ${event.direction.label}을 확인했고, 구간 전후 깜빡이는 계속 꺼져 있었어요.")
            else -> assessment("HELD", false, "회전 방향과 다른 신호가 있어 실제 조작을 다시 확인해야 해요.")
        }
    }

    private fun hasVehicleTurnEvidence(s: PracticeSession, event: TurnEvent): Boolean {
        val exit = event.exitMs ?: return false
        val change = event.headingChangeDeg ?: return false
        val peak = event.steeringPeakDeg ?: return false
        val left = event.direction == TurnDirection.LEFT
        val matches = if (left) change in -135.0..-55.0 && peak <= -2.0 else change in 55.0..135.0 && peak >= 2.0
        return event.evidenceStatus == "ESTIMATED" && matches && peak.isFinite() &&
            hasLocationCoverage(s, event.entryMs, exit) &&
            covered(s.samples, "steering", event.entryMs, exit) { it?.toDoubleOrNull()?.isFinite() == true } &&
            covered(s.samples, "steering_subscription", event.entryMs, exit) { it == "READY" }
    }

    /** Snapshots can repeat a fix. Both provider time and arrival of distinct fixes must advance. */
    private fun hasLocationCoverage(s: PracticeSession, from: Long, until: Long): Boolean {
        val samples = s.samples.filter { it.key == "location" && it.atMs in from..until }.sortedBy { it.atMs }
        if (samples.size < 2 || samples.first().atMs - from > 1200 || until - samples.last().atMs > 1200) return false
        var lastStamp = 0L
        var lastDistinctAt = from
        var fixes = 0
        for (sample in samples) {
            val fields = sample.value?.split(',') ?: return false
            if (sample.quality != "VALID" || sample.origin != "CALLBACK" || locationPoint(fields) == null) return false
            val stamp = fields[4].toLong()
            if (stamp < lastStamp || sample.atMs - lastDistinctAt > 3500) return false
            if (stamp > lastStamp) {
                if (fixes > 0 && stamp - lastStamp > 3500) return false
                lastStamp = stamp
                lastDistinctAt = sample.atMs
                fixes++
            }
        }
        return fixes >= 2 && until - lastDistinctAt <= 3500
    }

    private fun evaluateTurns(s: PracticeSession): Assessment {
        val results = turnAssessments(s)
        val mistakes = results.any { it.mistake }
        val checked = results.count { it.status in setOf("OMISSION", "OBSERVED") }
        if (s.interrupted || s.safetyIssue) return Assessment("HELD", null, 0, false, "중단한 연습", "연습 중단 기록이 있어 회전을 평가하지 않았습니다.")
        if (results.isEmpty()) return Assessment("NO_OPPORTUNITY", null, 0, false, "회전 기록 없음", "코스 통과와 조향·위치 변화를 함께 확인하지 못했습니다.")
        if (checked == 0) return Assessment("HELD", null, 0, false, if (results.all { it.status == "NO_TURN" }) "확인한 회전 없음" else "회전 확인이 남았습니다",
            if (results.all { it.status == "NO_TURN" }) "회전하지 않은 것으로 답한 구간은 평가에서 제외했습니다." else "보지 못했거나 신호 기록이 부족한 구간은 평가하지 않았습니다.")
        val unbuckledInMotion = unbuckledWhileMoving(s)
        val reason = if (unbuckledInMotion) "주행 중 안전벨트 미착용 신호가 있었습니다. 확인한 회전과 오답 기록은 남겼습니다."
            else if (results.any { it.status == "HELD" }) "확인한 회전 ${checked}곳만 기록했습니다. 확인하지 못한 구간은 평가하지 않았습니다."
            else "동승자가 확인한 회전 ${checked}곳의 신호 기록입니다. 공식 시험 점수는 계산하지 않습니다."
        return Assessment(if (results.any { it.status == "HELD" } || unbuckledInMotion) "HELD" else "REVIEWED", null, checked, mistakes,
            if (unbuckledInMotion) "안전벨트 미착용 기록" else "회전 확인 완료", reason)
    }
}
