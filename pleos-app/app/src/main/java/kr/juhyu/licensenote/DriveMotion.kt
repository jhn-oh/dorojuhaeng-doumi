package kr.juhyu.licensenote

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

internal data class DriveG(val right: Float?, val forward: Float, val timeNs: Long) {
    val magnitude: Float get() = right?.let { hypot(it, forward) } ?: abs(forward)
    fun fresh(nowNs: Long): Boolean = timeNs > 0 && nowNs - timeNs in 0L..1_500_000_000L
}

internal fun motionSample(right: Float, forward: Float, timeNs: Long, nowNs: Long): DriveG? {
    if (!right.isFinite() || !forward.isFinite() || timeNs <= 0L || nowNs - timeNs !in 0L..1_500_000_000L) return null
    // AAOS body frame x=right, y=forward. TYPE_LINEAR_ACCELERATION excludes gravity.
    return DriveG(right / 9.80665f, forward / 9.80665f, timeNs)
}

internal class SpeedG {
    private var previousTime = 0L
    private var previousSpeed = 0f
    private var last: DriveG? = null

    fun sample(speed: Signal?, now: Long): DriveG? {
        val t = speed?.sampleNs ?: 0L
        val value = speed?.value?.toFloatOrNull()
        if (speed?.quality != "VALID" || value == null || !value.isFinite() || t <= 0 || now - t !in 0..1_500_000_000L) {
            previousTime = 0
            last = null
            return null
        }
        if (t == previousTime) return last?.takeIf { it.fresh(now) }
        val delta = t - previousTime
        if (previousTime != 0L && delta in 1..79_999_999L) return last?.takeIf { it.fresh(now) }
        last = if (previousTime > 0L && delta in 80_000_000L..2_000_000_000L)
            DriveG(null, (value - previousSpeed) / (delta / 1e9f) / 9.80665f, t) else null
        previousTime = t
        previousSpeed = value
        return last
    }
}

@Composable internal fun DriveMotionMeter(speed: Signal?) {
    val manager = LocalContext.current.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val speedState by rememberUpdatedState(speed)
    var motion by remember { mutableStateOf<DriveG?>(null) }
    LaunchedEffect(lifecycle, manager) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var latest: DriveG? = null
            val derivative = SpeedG()
            val sensor = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            val listener = object : SensorEventListener {
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                    if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) latest = null
                }
                override fun onSensorChanged(event: SensorEvent) {
                    latest = if (event.accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE && event.values.size >= 2)
                        motionSample(event.values[0], event.values[1], event.timestamp, SystemClock.elapsedRealtimeNanos()) else null
                }
            }
            if (sensor != null) manager.registerListener(listener, sensor, 100_000)
            try {
                while (true) {
                    val now = SystemClock.elapsedRealtimeNanos()
                    val longitudinal = derivative.sample(speedState, now)
                    motion = latest?.takeIf { it.fresh(now) } ?: longitudinal
                    delay(125)
                }
            } finally {
                manager.unregisterListener(listener)
                motion = null
            }
        }
    }
    val sample = motion
    val palette = LocalPalette.current
    val label = when {
        sample == null -> "가속도 신호 대기"
        sample.right == null -> "차속 기반 전후 추정"
        else -> "차체 전후 · 좌우"
    }
    val value = sample?.let { String.format(Locale.US, "%.2f g", it.magnitude) } ?: "—"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Canvas(Modifier.size(88.dp).semantics { contentDescription = "G미터 · $label · $value" }) {
            val radius = size.minDimension * 0.44f
            drawCircle(palette.line, radius, center, style = Stroke(2.dp.toPx()))
            drawLine(palette.line, Offset(center.x, center.y-radius), Offset(center.x, center.y+radius))
            drawLine(palette.line, Offset(center.x-radius, center.y), Offset(center.x+radius, center.y))
            if (sample?.right != null) {
                val range = maxOf(0.5f, sample.magnitude)
                drawCircle(palette.blue, 6.dp.toPx(), Offset(center.x + radius * sample.right / range, center.y - radius * sample.forward / range))
            } else if (sample != null) {
                val y = center.y - radius * (sample.forward / 0.5f).coerceIn(-1f, 1f)
                drawLine(palette.blue, Offset(center.x-5.dp.toPx(), y), Offset(center.x+5.dp.toPx(), y), 4.dp.toPx())
            }
        }
        Column {
            Copy("G미터  $value", 26, palette.ink)
            Copy(label, 20, palette.second)
        }
    }
}
