package kr.juhyu.licensenote

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Spoken instructions use the same evidence as the currently visible parked screen. */
internal fun parkedInstruction(ui: PracticeUi, signals: Map<String, Signal>): String? = when (ui.page) {
    Page.WELCOME -> "연습면허와 주행연습 표지를 준비하고, 자격을 갖춘 지도자와 함께 연습하세요. 준비사항을 확인한 뒤 출발 준비를 시작할게요."
    Page.BELT -> when (signals["belt"].departureBelt(ui.now)) {
        DepartureBelt.FASTENED -> "운전석 안전벨트가 채워졌어요. 다음으로 넘어가세요."
        DepartureBelt.UNFASTENED -> "운전석 안전벨트를 매 주세요."
        DepartureBelt.UNAVAILABLE -> "안전벨트 신호를 확인할 수 없어요. 벨트를 매고 직접 확인해 주세요."
    }
    Page.MIRROR -> "운전석 위치를 편하게 맞추고, 룸미러와 양쪽 사이드미러로 옆과 뒤가 잘 보이는지 확인해 주세요."
    Page.DOORS -> {
        val doors = departureDoors(signals, ui.now)
        when {
            doors.firstOpen != null -> "열린 문을 닫고 차 주변을 살펴보세요."
            doors.allClosed -> "문이 모두 닫혔어요. 차 주변에 사람이나 장애물이 있는지 살펴보세요."
            else -> "문을 모두 닫고 차 주변을 살펴보세요."
        }
    }
    Page.COURSE -> if (ui.courseStep == CourseStep.PREVIEW) {
        val routeName = ui.chosenNative?.name ?: ui.plannedRoute?.name ?: CourseCatalog.find(ui.selectedRouteId)?.name
        routeName?.let { "${it.replace('·', ' ')} 코스를 선택했어요. 준비되면 연습을 시작하세요." }
    } else if (ui.courseStep == CourseStep.LIST) "원하는 연습 시간을 선택하세요. 현재 위치에서 출발해 다시 돌아오는 코스를 찾아드려요." else null
    Page.SHARE -> "휴대폰 카메라로 결과 큐알 코드를 읽으면 차량 밖에서도 이번 연습을 복습할 수 있어요."
    Page.RESULT -> ui.assessment?.let { assessment ->
        when {
            ui.session?.vehicleReview != true -> null
            assessment.status == "HELD" -> assessment.reason.substringBefore('\n')
            assessment.practiceSuggested -> "깜빡이가 계속 꺼져 있던 구간이 있어요. 다시 연습할 구간을 확인해 보세요."
            assessment.status == "VEHICLE_RECORDED" -> "연습을 마쳤어요. 구간별 깜빡이 기록을 확인해 보세요."
            else -> assessment.reason.substringBefore('\n')
        }
    }
    else -> null
}

internal fun instrumentInstruction(step: Int): String? = when (step) {
    0 -> "출발 전 속도, 기어, 연료나 배터리 잔량을 한 번에 확인하세요."
    1 -> "정차한 상태에서 왼쪽이나 오른쪽 깜빡이를 켜 보세요. 이미 켜져 있다면 끄고 다시 켜세요."
    2 -> "와이퍼를 한 번 작동해 보세요. 유리가 마른 상태라면 물을 충분히 묻힌 뒤 조작하세요."
    else -> null
}

@Composable internal fun SpeakParkedInstruction(text: String?, replay: Int = 0) {
    var spoken by rememberSaveable { mutableStateOf<String?>(null) }
    if (text == null) return
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    val client = remember(context) { PleosSpeech(context) { _, _ -> } }
    val key = "$replay:$text"
    DisposableEffect(client, key, state) { onDispose { client.stop() } }
    LaunchedEffect(key, state) {
        if (!state.isAtLeast(Lifecycle.State.RESUMED) || spoken == key) return@LaunchedEffect
        // Ignore transient frames during parking checks, permission dialogs and page transitions.
        delay(550)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        spoken = key
        client.speak(text, SystemClock.elapsedRealtime() + 4500)
    }
}

internal data class SpokenCourseCue(val key: String, val words: String)

internal fun spokenCourseCue(cue: DriveCue): SpokenCourseCue? {
    if (cue.title == "정차 후 경로를 확인하세요")
        return SpokenCourseCue("conflict", "차량과 내비게이션 위치가 달라요. 정차한 뒤 경로를 확인하세요.")
    val metres = cue.metres ?: return null
    // Native turn-by-turn has its own voice. Within 30 m an extra coaching prompt would be late.
    if (cue.source != "선택 코스 · 차량 위치 기준" || metres !in 30..150) return null
    val turn = when (cue.turnKey) { "LEFT" -> "좌회전"; "RIGHT" -> "우회전"; else -> return null }
    val rounded = (metres / 5.0).roundToInt() * 5
    return SpokenCourseCue(turn, "약 ${rounded}미터 앞에 ${turn} 연습 지점이 있어요.")
}

@Composable internal fun SpokenDriveCue(session: PracticeSession, parked: Boolean, signals: Map<String, Signal>,
                                        now: Long, canUseVoice: Boolean, client: PleosSpeech) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val speed = signals["speed"]?.takeIf { it.fresh(now) }?.value?.toDoubleOrNull()
    val cue = if (!parked && canUseVoice && speed != null && speed > 0.5)
        spokenCourseCue(driveCue(session, signals, now)) else null
    var delivered by rememberSaveable(session.id) { mutableStateOf(emptyList<String>()) }
    val currentKey by rememberUpdatedState(cue?.key)
    LaunchedEffect(cue?.key, session.id) {
        if (cue == null || cue.key in delivered) return@LaunchedEffect
        delivered = delivered + cue.key
        if (!client.busy && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            client.speak(cue.words, SystemClock.elapsedRealtime() + 2500) {
                currentKey == cue.key && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
        }
    }
}
