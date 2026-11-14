package kr.juhyu.licensenote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.util.Locale

internal fun observedWiperActivation(signal: Signal?, opened: Long, now: Long): Boolean {
    if (!signal.fresh(now, 2000) || signal?.source != "Vehicle/Wiper") return false
    val off = signal.value?.toLongOrNull() ?: return false
    return opened > 0 && off >= opened && signal.receivedMs >= off
}

internal fun wiperLabel(signal: Signal?, now: Long): String = when {
    signal?.quality == "UNSUPPORTED" -> "이 차량은 와이퍼 동작 조회를 지원하지 않아요."
    !signal.fresh(now, 5000) -> "앞유리 와이퍼 신호를 기다리고 있어요."
    signal?.value == "OFF" -> "앞유리 와이퍼 · 꺼짐"
    signal?.value == "ON" -> "앞유리 와이퍼 · 작동 중"
    else -> "앞유리 와이퍼 · 작동 확인 필요"
}

internal fun vehicleCounterDelta(session: PracticeSession, key: String): Double? {
    val end = session.endedAtMs ?: return null
    val samples = session.samples.filter { it.key == key }
    val firstIndex = samples.indexOfFirst { it.quality == "VALID" }
    if (firstIndex < 0 || samples.size - firstIndex < 2) return null
    val observed = samples.drop(firstIndex)
    if (observed.first().atMs - session.startedAtMs !in 0..3500 || end - observed.last().atMs !in 0..3500) return null
    val values = observed.map {
        if (it.quality != "VALID") return null
        it.value?.toDoubleOrNull()?.takeIf { n -> n.isFinite() && n >= 0 } ?: return null
    }
    if (observed.zipWithNext().any { (a, b) -> b.atMs - a.atMs !in 0..3500 } ||
        values.zipWithNext().any { (a, b) -> b < a }) return null
    return values.last() - values.first()
}

@Composable internal fun VehicleDriveSummary(session: PracticeSession) {
    val distance = remember(session.id, session.revision) { vehicleCounterDelta(session, "odometer_km") }
    val minutes = remember(session.id, session.revision) { vehicleCounterDelta(session, "vehicle_usage_minutes") }
    val usedBrake = remember(session.id, session.revision) {
        session.samples.any { it.key == "brake_pressed" && it.quality == "VALID" && it.value == "true" }
    }
    if (distance != null) Copy("차량 누적거리 변화 · " + String.format(Locale.KOREA, "%.2f km", distance), 24, LocalPalette.current.second)
    if (minutes != null) Copy("차량 누적 운행시간 변화 · ${minutes.toInt()}분", 24, LocalPalette.current.second)
    if (usedBrake)
        Copy("브레이크 페달을 밟은 신호가 기록됐어요.", 24, LocalPalette.current.second)
}
