package kr.juhyu.licensenote

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun GuideGlyph(topic: GuideTopic, ink: Color, modifier: Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension / 48f
        withTransform({ scale(unit, unit, Offset.Zero) }) {
            val stroke = Stroke(3f, cap = StrokeCap.Round)
            when (topic) {
                GuideTopic.SPEED -> {
                    drawArc(ink, 155f, 230f, false, Offset(5f, 8f), Size(38f, 38f), style = stroke)
                    drawLine(ink, Offset(24f, 28f), Offset(33f, 17f), 3f, StrokeCap.Round)
                    drawCircle(ink, 3f, Offset(24f, 28f))
                }
                GuideTopic.SIGNAL -> arrow(24f, 24f, true, 1f, ink)
                GuideTopic.WIPER, GuideTopic.WASHER -> {
                    val glass = Path().apply { moveTo(4f, 16f); quadraticBezierTo(24f, 7f, 44f, 16f); lineTo(37f, 34f); quadraticBezierTo(24f, 30f, 11f, 34f); close() }
                    drawPath(glass, ink, style = stroke)
                    if (topic == GuideTopic.WIPER) drawLine(ink, Offset(14f, 32f), Offset(30f, 21f), 3f, StrokeCap.Round)
                    else for (x in listOf(16f, 24f, 32f)) drawLine(ink, Offset(24f + (x - 24f) * .5f, 22f), Offset(x, 17f), 2f, StrokeCap.Round)
                }
                GuideTopic.BELT -> seatBelt(Color.Transparent, ink, true)
                GuideTopic.MIRRORS -> mirrorSymbol(ink)
                GuideTopic.DOOR -> doorSymbol(ink)
                GuideTopic.DEFOG -> windowSymbol(false, ink)
                GuideTopic.BEAMS -> beamSymbol(false, ink)
                GuideTopic.HAZARD -> hazardSymbol(ink)
                GuideTopic.TIRE -> tireSymbol(ink)
                GuideTopic.PARKING_BRAKE -> brakeSymbol(ink)
                GuideTopic.HOLD -> holdSymbol(ink)
            }
        }
    }
}

internal fun DrawScope.arrow(x: Float, y: Float, right: Boolean, factor: Float, color: Color) {
    val direction = if (right) factor else -factor
    val arrow = Path().apply {
        moveTo(x - 16f * direction, y - 5f * factor); lineTo(x + 2f * direction, y - 5f * factor)
        lineTo(x + 2f * direction, y - 12f * factor); lineTo(x + 16f * direction, y)
        lineTo(x + 2f * direction, y + 12f * factor); lineTo(x + 2f * direction, y + 5f * factor)
        lineTo(x - 16f * direction, y + 5f * factor); close()
    }
    drawPath(arrow, color)
}

private fun DrawScope.seatBelt(ground: Color, ink: Color, fastened: Boolean) {
    drawCircle(ink, 5f, Offset(24f, 8f))
    drawRoundRect(ink, Offset(15f, 16f), Size(18f, 24f), CornerRadius(7f))
    drawLine(ink, Offset(13f, 41f), Offset(35f, 41f), 4f, StrokeCap.Round)
    if (fastened) {
        drawLine(if (ground == Color.Transparent) Color.White else ground, Offset(14f, 34f), Offset(32f, 15f), 6f)
        drawLine(ink, Offset(12f, 35f), Offset(34f, 13f), 2.5f, StrokeCap.Round)
    }
}

private fun dialPoint(angle: Float, radius: Float): Offset {
    val radians = angle * PI / 180
    return Offset(180f + radius * cos(radians).toFloat(), 113f + radius * sin(radians).toFloat())
}

internal fun guideSpeedFraction(step: Int, t: Float) = when (step) { 0 -> .5f * (1f - t); 1 -> t; else -> 1f - t }

@Composable
internal fun GuideDrawing(topic: GuideTopic, step: Int, progress: () -> Float, alternate: Boolean, modifier: Modifier) {
    val palette = LocalPalette.current
    Canvas(modifier) {
        val unit = minOf(size.width / 360f, size.height / 154f)
        withTransform({
            translate((size.width - 360f * unit) / 2, (size.height - 154f * unit) / 2)
            scale(unit, unit, Offset.Zero)
        }) {
            val t = progress().coerceIn(0f, 1f)
            when (topic) {
                GuideTopic.SPEED -> {
                    val amount = guideSpeedFraction(step, t)
                    drawArc(palette.line, 160f, 220f, false, Offset(85f, 18f), Size(190f, 190f), style = Stroke(5f, cap = StrokeCap.Round))
                    drawArc(palette.blue, 160f, 110f * amount, false, Offset(85f, 18f), Size(190f, 190f), style = Stroke(5f, cap = StrokeCap.Round))
                    for (i in 0..16) {
                        val angle = 160f + 220f * i / 16f
                        drawLine(palette.muted, dialPoint(angle, if (i % 4 == 0) 76f else 81f), dialPoint(angle, 87f), if (i % 4 == 0) 2.4f else 1.4f)
                    }
                    drawLine(palette.blue, Offset(180f, 113f), dialPoint(160f + 110f * amount, 68f), 4f, StrokeCap.Round)
                    drawCircle(palette.navy, 6f, Offset(180f, 113f))
                }
                GuideTopic.SIGNAL -> {
                    val amount = when (step) { 0 -> 0f; 1 -> (t * 2).coerceAtMost(1f); else -> 1f - t }
                    val end = Offset(68f, 78f + (if (alternate) -1f else 1f) * amount * 26f)
                    drawCircle(palette.line, 36f, Offset(180f, 78f), style = Stroke(6f))
                    drawLine(palette.muted, Offset(160f, 78f), Offset(68f, 78f), 3f, StrokeCap.Round)
                    drawLine(palette.navy, Offset(162f, 78f), end, 10f, StrokeCap.Round)
                    drawLine(palette.blue, end, Offset(end.x + 13f, end.y), 13f, StrokeCap.Round)
                    if (step == 0) drawCircle(palette.blue, 11f + 9f * t, end, style = Stroke(2f))
                    val lit = step == 1 && (t >= 1f || sin(t * PI * 5) >= 0)
                    for (side in listOf(false, true)) arrow(if (side) 278f else 242f, 48f, side, .82f,
                        if (lit && alternate == side) palette.blue else palette.line)
                }
                GuideTopic.WIPER, GuideTopic.WASHER -> {
                    val glass = Path().apply {
                        moveTo(65f, 35f); quadraticBezierTo(180f, -4f, 295f, 35f)
                        lineTo(263f, 128f); quadraticBezierTo(180f, 110f, 97f, 128f); close()
                    }
                    drawPath(glass, palette.paper)
                    drawPath(glass, palette.muted, style = Stroke(3.5f))
                    if (topic == GuideTopic.WIPER) {
                        val sweep = if (step < 2) sin(PI * t).toFloat() else 0f
                        for (x in listOf(111f, 184f)) {
                            val angle = (-9f - 63f * sweep) * PI / 180
                            val pivot = Offset(x, 118f)
                            val delta = Offset(74f * cos(angle).toFloat(), 74f * sin(angle).toFloat())
                            drawLine(palette.muted, pivot, pivot + delta * .54f, 3f)
                            drawLine(palette.blue, pivot + delta * .35f, pivot + delta, 5f, StrokeCap.Round)
                            drawCircle(palette.navy, 3.6f, pivot)
                        }
                    } else {
                        val reach = if (step < 2) .35f + .65f * t else .85f
                        for (i in -2..2) for (j in 1..4) {
                            val d = j * reach / 4f
                            drawCircle(palette.blue, 2.6f, Offset(180f + i * 25f * d, 100f - 56f * d + i * i * 1.8f * d))
                        }
                    }
                }
                GuideTopic.BELT -> {
                    withTransform({ translate(108f, 7f); scale(3f, 3f, Offset.Zero) }) {
                        seatBelt(palette.tint, palette.muted, false)
                        val extent = if (step < 2) .35f + .65f * t else 1f
                        val start = Offset(34f, 13f)
                        val end = start + Offset(-22f, 23f) * extent
                        drawLine(palette.tint, start, end, 6f)
                        drawLine(palette.blue, start, end, 3f, StrokeCap.Round)
                        drawCircle(palette.navy, 2.3f, Offset(12f, 36f))
                    }
                }
                GuideTopic.MIRRORS -> drawMirrors(step, t, alternate, palette)
                GuideTopic.DOOR -> drawDoorPlan(step, t, alternate, palette)
                GuideTopic.DEFOG -> drawDefog(step, t, alternate, palette)
                GuideTopic.BEAMS -> drawHeadlamps(step, t, alternate, palette)
                GuideTopic.HAZARD -> drawHazards(step, t, palette)
                GuideTopic.TIRE -> drawTireWarning(step, t, alternate, palette)
                GuideTopic.PARKING_BRAKE -> drawParkingBrake(step, t, palette)
                GuideTopic.HOLD -> drawAutoHold(step, t, palette)
            }
        }
    }
}
