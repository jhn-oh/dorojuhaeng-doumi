package kr.juhyu.licensenote

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A glance at the live instruments, then two real stalk operations while parked. */
@Composable internal fun CompactVehicleLesson(topic: GuideTopic, signals: Map<String, Signal>, now: Long,
                                               wide: Boolean, returnButton: @Composable (Boolean) -> Unit) {
    val p = LocalPalette.current
    val first = when (topic) { GuideTopic.SIGNAL -> 1; GuideTopic.WIPER -> 2; else -> 0 }
    var step by rememberSaveable(topic) { mutableIntStateOf(first) }
    var enteredAt by rememberSaveable(topic) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var turnDone by rememberSaveable(topic) { mutableStateOf(false) }
    var wiperDone by rememberSaveable(topic) { mutableStateOf(false) }
    var notice by rememberSaveable(topic) { mutableStateOf("") }
    fun enter(next: Int) { enteredAt = SystemClock.elapsedRealtime(); step = next }
    val turnActivation = signals["turn_activation"]
    val wiperActivation = signals["wiper_front_activation"]
    LaunchedEffect(step, turnActivation, wiperActivation) {
        val current = SystemClock.elapsedRealtime()
        if (step == 1 && observedTurnActivation(turnActivation, enteredAt, current)) {
            turnDone = true
            notice = "깜빡이가 켜진 것을 확인했어요. 레버를 가운데로 돌려 끄세요."
            enter(if (topic == GuideTopic.SIGNAL) 3 else 2)
        } else if (step == 2 && observedWiperActivation(wiperActivation, enteredAt, current)) {
            wiperDone = true
            notice = "와이퍼가 작동한 것을 확인했어요."
            enter(3)
        }
    }
    SpeakParkedInstruction(instrumentInstruction(step))
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Copy(when (topic) { GuideTopic.SIGNAL -> "깜빡이 켜 보기"; GuideTopic.WIPER -> "와이퍼 작동해 보기"; else -> "내 차 빠르게 익히기" },
                if (wide) 52 else 40, weight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            if (topic == GuideTopic.SPEED) {
                Copy("출발 전, 필요한 정보만 한눈에 확인하세요.", 26, p.second)
                val readings = listOf("speed" to "속도", "gear" to "기어", "fuel_percent" to "연료", "battery_percent" to "배터리")
                readings.chunked(if (wide) 4 else 2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { (key, label) ->
                            Column(Modifier.weight(1f).background(p.light, RoundedCornerShape(18.dp)).padding(20.dp)) {
                                Copy(label, 23, p.second)
                                Copy(vehicleOverviewValue(key, signals[key], now), if (wide) 32 else 29, p.ink, FontWeight.SemiBold)
                            }
                        }
                    }
                }
                Copy("속도는 도로의 제한속도와 함께, 연료·배터리는 다음 주행거리를 생각하며 확인하세요. 차량이 제공하지 않는 값은 표시하지 않아요.", 23, p.second)
            }
            if (notice.isNotEmpty()) Copy(notice, 25, p.navy, FontWeight.Medium,
                modifier = Modifier.fillMaxWidth().background(p.tint, RoundedCornerShape(16.dp)).padding(18.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite })
            when (step) {
                0 -> PracticeControlButton("깜빡이·와이퍼 해 보기") { enter(1) }
                1, 2 -> {
                    val signal = signals[if (step == 1) "turn" else "wiper_front"]
                    val unsupported = signal?.quality == "UNSUPPORTED" || signal?.error?.contains("ERROR_NOT_SUPPORTED") == true
                    val active = signal.fresh(now, 5000) && signal?.value in if (step == 1) setOf("LEFT", "RIGHT", "BOTH") else setOf("ON")
                    Column(Modifier.fillMaxWidth().background(p.light, RoundedCornerShape(20.dp)).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Copy(if (step == 1) "깜빡이를 켜 보세요" else "와이퍼를 한 번 작동해 보세요", 33, p.ink, FontWeight.SemiBold)
                        Copy(if (step == 1) "핸들 왼쪽 레버로 왼쪽 또는 오른쪽 깜빡이를 켜세요."
                            else "내 차의 MIST 또는 1회 표시로 작동하세요. 마른 유리에는 물을 충분히 묻힌 뒤 사용하세요.", 26, p.second)
                        Copy(when {
                            unsupported -> "이 차량은 동작 확인을 지원하지 않아요. 자동 완료할 수 없어요."
                            signal?.quality == "DENIED" || signal?.error?.contains("ERROR_SECURITY_PERMISSION") == true -> "동작을 읽을 권한이 없어요. 차량 연결을 확인해 주세요."
                            active -> "이미 켜져 있어요. 먼저 끈 뒤 다시 켜 주세요."
                            step == 2 -> wiperLabel(signal, now)
                            signal.fresh(now) && signal?.value == "OFF" -> "깜빡이 꺼짐 · 조작을 기다리고 있어요"
                            else -> "차량 신호를 기다리고 있어요"
                        }, 24, p.navy, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Copy("차량에서 실제 동작이 확인되면 자동으로 넘어가요.", 23, p.second)
                    }
                }
                else -> {
                    Copy(if (turnDone && wiperDone) "기본 조작을 확인했어요" else "조작을 확인했어요", 34, p.ink, FontWeight.SemiBold)
                    Copy(listOfNotNull(if (turnDone) "깜빡이 작동 확인" else null, if (wiperDone) "와이퍼 작동 확인" else null).joinToString(" · "), 25, p.second)
                    PracticeControlButton("다시 해 보기") {
                        turnDone = false; wiperDone = false; notice = ""; enter(if (first == 0) 1 else first)
                    }
                }
            }
        }
        returnButton(step == 0 || step == 3)
    }
}

@Composable private fun PracticeControlButton(text: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 72.dp), shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = p.blue), contentPadding = PaddingValues(16.dp)) {
        Copy(text, 27, p.onAction, FontWeight.SemiBold)
    }
}
