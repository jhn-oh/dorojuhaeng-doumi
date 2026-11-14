package kr.juhyu.licensenote

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

private val symbolStroke = Stroke(width = 2.6f, cap = StrokeCap.Round)

// Dashboard symbols have a 48-unit view box; lesson diagrams reuse them at a larger scale.
private inline fun DrawScope.symbol(x: Float, y: Float, scale: Float, draw: DrawScope.() -> Unit) {
    withTransform({ translate(x, y); scale(scale, scale, Offset.Zero) }) { draw() }
}

internal fun DrawScope.mirrorSymbol(color: Color) {
    val frame = Path().apply {
        moveTo(6f, 17f); quadraticBezierTo(6f, 10f, 15f, 11f)
        lineTo(35f, 15f); quadraticBezierTo(41f, 17f, 41f, 25f); lineTo(41f, 31f)
        lineTo(16f, 32f); quadraticBezierTo(5f, 31f, 6f, 17f); close()
    }
    drawPath(frame, color, style = symbolStroke)
    drawLine(color, Offset(35f, 32f), Offset(42f, 38f), 3f, StrokeCap.Round)
}

internal fun DrawScope.doorSymbol(color: Color) {
    drawRoundRect(color, Offset(16f, 5f), Size(19f, 38f), CornerRadius(7f), style = symbolStroke)
    drawLine(color, Offset(16f, 15f), Offset(7f, 22f), 3f, StrokeCap.Round)
    drawLine(color, Offset(7f, 22f), Offset(9f, 31f), 3f, StrokeCap.Round)
    drawLine(color, Offset(21f, 15f), Offset(30f, 15f), 2f)
}

private fun pane(rear: Boolean) = Path().apply {
    if (rear) {
        moveTo(7f, 10f); lineTo(41f, 10f); lineTo(43f, 37f); lineTo(5f, 37f)
    } else {
        moveTo(3f, 13f); quadraticBezierTo(24f, 3f, 45f, 13f)
        lineTo(37f, 36f); quadraticBezierTo(24f, 31f, 11f, 36f)
    }
    close()
}

internal fun DrawScope.windowSymbol(rear: Boolean, color: Color) {
    drawPath(pane(rear), color, style = symbolStroke)
    for (x in listOf(16f, 24f, 32f)) {
        drawLine(color, Offset(x, 27f), Offset(x, 16f), 2f, StrokeCap.Round)
        drawLine(color, Offset(x - 2.5f, 19f), Offset(x, 16f), 2f, StrokeCap.Round)
        drawLine(color, Offset(x + 2.5f, 19f), Offset(x, 16f), 2f, StrokeCap.Round)
    }
}

internal fun DrawScope.beamSymbol(high: Boolean, color: Color) {
    val lamp = Path().apply {
        moveTo(29f, 12f); quadraticBezierTo(43f, 12f, 44f, 24f)
        quadraticBezierTo(43f, 36f, 29f, 36f); close()
    }
    drawPath(lamp, color, style = symbolStroke)
    for (y in listOf(15f, 23f, 31f)) {
        drawLine(color, Offset(23f, y), Offset(5f, y + if (high) 0f else 6f), 3f, StrokeCap.Round)
    }
}

internal fun DrawScope.hazardSymbol(color: Color) {
    val shape = Path().apply { moveTo(24f, 6f); lineTo(44f, 40f); lineTo(4f, 40f); close() }
    drawPath(shape, color, style = symbolStroke)
    val center = Path().apply { moveTo(24f, 16f); lineTo(35f, 34f); lineTo(13f, 34f); close() }
    drawPath(center, color, style = Stroke(2f))
}

internal fun DrawScope.tireSymbol(color: Color) {
    val tire = Path().apply {
        moveTo(13f, 7f); cubicTo(13f, 17f, 3f, 21f, 9f, 35f)
        quadraticBezierTo(24f, 42f, 39f, 35f); cubicTo(45f, 22f, 35f, 16f, 35f, 7f)
    }
    drawPath(tire, color, style = Stroke(3.5f, cap = StrokeCap.Round))
    for (x in listOf(14f, 21f, 28f, 35f)) drawLine(color, Offset(x, 36f), Offset(x, 40f), 2f)
    drawLine(color, Offset(24f, 14f), Offset(24f, 25f), 3.5f, StrokeCap.Round)
    drawCircle(color, 2f, Offset(24f, 30f))
}

internal fun DrawScope.brakeSymbol(color: Color) {
    drawCircle(color, 15f, Offset(24f, 24f), style = symbolStroke)
    drawArc(color, 138f, 84f, false, Offset(2f, 2f), Size(44f, 44f), style = symbolStroke)
    drawArc(color, -42f, 84f, false, Offset(2f, 2f), Size(44f, 44f), style = symbolStroke)
    val letter = Path().apply {
        moveTo(20f, 32f); lineTo(20f, 16f); lineTo(25f, 16f)
        cubicTo(32f, 16f, 32f, 25f, 25f, 25f); lineTo(20f, 25f)
    }
    drawPath(letter, color, style = symbolStroke)
}

internal fun DrawScope.holdSymbol(color: Color) {
    drawRoundRect(color, Offset(3f, 10f), Size(42f, 28f), CornerRadius(6f), style = symbolStroke)
    drawLine(color, Offset(19f, 17f), Offset(19f, 31f), 3f)
    drawLine(color, Offset(29f, 17f), Offset(29f, 31f), 3f)
    drawLine(color, Offset(19f, 24f), Offset(29f, 24f), 3f)
}

internal fun DrawScope.drawMirrors(step: Int, t: Float, right: Boolean, p: Palette) {
    for (side in listOf(false, true)) {
        val active = (right == side)
        val x = if (side) 194f else 30f
        val ink = if (active) p.blue else p.muted
        withTransform({
            translate(x, 16f)
            if (side) { translate(130f, 0f); scale(-1f, 1f, Offset.Zero) }
            scale(2.7f, 2.7f, Offset.Zero)
        }) {
            mirrorSymbol(ink)
            val rise = if (active && step == 1) 4f * t else 0f
            drawLine(ink, Offset(10f, 25f - rise), Offset(36f, 24f - rise), 1.3f)
            if (active) {
                val size = if (step == 2) 3.4f + t else 3f
                drawRoundRect(p.blue, Offset(21f, 24f - rise - size), Size(size * 1.8f, size), CornerRadius(.8f))
            }
        }
    }
}

internal fun DrawScope.drawDoorPlan(step: Int, t: Float, rearRight: Boolean, p: Palette) {
    drawRoundRect(p.paper, Offset(140f, 8f), Size(80f, 138f), CornerRadius(25f))
    drawRoundRect(p.navy, Offset(140f, 8f), Size(80f, 138f), CornerRadius(25f), style = Stroke(3f))
    drawRoundRect(p.line, Offset(149f, 30f), Size(62f, 23f), CornerRadius(7f))
    drawRoundRect(p.line, Offset(149f, 108f), Size(62f, 19f), CornerRadius(6f))
    for (rear in listOf(false, true)) for (right in listOf(false, true)) {
        val side = if (right) 1f else -1f
        val x = if (right) 220f else 140f
        val y = if (rear) 77f else 43f
        val chosen = right == rearRight && rear == rearRight
        val swing = if (!chosen) 0f else if (step == 2) 1f - t else t
        val angle = swing * PI * .32
        val end = Offset(x + side * 34f * sin(angle).toFloat(), y + 34f * cos(angle).toFloat())
        drawLine(if (chosen) p.blue else p.muted, Offset(x, y), end, if (chosen) 5f else 2f, StrokeCap.Round)
    }
}

internal fun DrawScope.drawDefog(step: Int, t: Float, rear: Boolean, p: Palette) {
    symbol(75f, -15f, 4.3f) {
        drawPath(pane(rear), p.paper)
        drawPath(pane(rear), p.muted, style = Stroke(1f))
        val mist = if (step < 2) 1f - t * .8f else .12f * (1f - t)
        for (row in 0..2) for (col in 0..4) drawCircle(p.muted.copy(alpha = mist * .45f), 1.1f,
            Offset(14f + 5f * col + (row % 2), 15f + row * 6f))
        if (rear) {
            for (y in listOf(16f, 21f, 26f, 31f)) drawLine(p.blue, Offset(10f, y), Offset(10f + 28f * (.2f + .8f * t), y), .8f)
        } else for (x in listOf(16f, 24f, 32f)) {
            val tip = 29f - 14f * (.25f + .75f * t)
            val air = Path().apply { moveTo(x, 29f); cubicTo(x - 2f, 25f, x + 2f, tip + 4f, x, tip) }
            drawPath(air, p.blue, style = Stroke(1f))
            drawLine(p.blue, Offset(x - 2f, tip + 3f), Offset(x, tip), 1f)
            drawLine(p.blue, Offset(x + 2f, tip + 3f), Offset(x, tip), 1f)
        }
    }
}

internal fun DrawScope.drawHeadlamps(step: Int, t: Float, high: Boolean, p: Palette) {
    val upper = if (high && step != 2) 1f else 0f
    val reach = 98f + (76f + 75f * upper) * t
    drawLine(p.muted, Offset(15f, 113f), Offset(344f, 113f), 2f)
    drawRoundRect(p.navy, Offset(40f, 84f), Size(63f, 20f), CornerRadius(6f))
    drawRoundRect(p.navy, Offset(50f, 73f), Size(37f, 19f), CornerRadius(8f))
    for (x in listOf(53f, 91f)) drawCircle(p.muted, 7f, Offset(x, 105f))
    val origin = Offset(102f, 88f)
    val tip = 104f - 52f * upper
    val spread = Path().apply { moveTo(origin.x, origin.y); lineTo(102f + reach, tip); lineTo(102f + reach, 111f); close() }
    drawPath(spread, p.blue.copy(alpha = .22f))
    drawLine(p.blue, origin, Offset(102f + reach, tip), 2f)
    symbol(16f, 14f, .95f) { beamSymbol(upper > 0f, p.blue) }
}

internal fun DrawScope.drawHazards(step: Int, t: Float, p: Palette) {
    val lit = step != 2 && (t >= 1f || cos(t * PI * 4) >= 0)
    val ink = if (lit) p.blue else p.line
    symbol(132f, 20f, 2f) { hazardSymbol(p.navy) }
    arrow(72f, 74f, false, 1.8f, ink)
    arrow(288f, 74f, true, 1.8f, ink)
}

internal fun DrawScope.drawTireWarning(step: Int, t: Float, blink: Boolean, p: Palette) {
    val lit = !blink || step != 1 || t > .72f || cos(t * PI * 6) >= 0
    symbol(85f, 22f, 2.1f) { tireSymbol(if (lit) p.navy else p.line) }
    if (step == 2) {
        drawRoundRect(p.paper, Offset(208f, 33f), Size(78f, 77f), CornerRadius(5f))
        drawRoundRect(p.muted, Offset(208f, 33f), Size(78f, 77f), CornerRadius(5f), style = Stroke(2f))
        for (i in 0..3) drawLine(if (i == 0) p.blue else p.muted, Offset(221f, 47f + 15f * i), Offset(226f + 43f * t, 47f + 15f * i), 3f)
    } else {
        drawLine(p.muted, Offset(213f, 97f), Offset(295f, 97f), 2f)
        for (i in 0..5) {
            val height = if (blink && i < 4 && i % 2 == 0) 7f else 30f
            drawLine(if (i / 6f <= t) p.blue else p.line, Offset(218f + i * 14f, 91f), Offset(218f + i * 14f, 91f - height), 6f)
        }
    }
}

internal fun DrawScope.drawParkingBrake(step: Int, t: Float, p: Palette) {
    val press = if (step == 0) 11f * t else 11f
    drawRoundRect(p.muted, Offset(57f, 52f + press), Size(38f, 49f), CornerRadius(8f))
    for (i in 0..3) drawLine(p.paper, Offset(64f, 63f + press + 7f * i), Offset(88f, 63f + press + 7f * i), 2f)
    val lift = when (step) { 0 -> 0f; 1 -> 18f * t; else -> 18f }
    drawRoundRect(p.line, Offset(123f, 83f), Size(76f, 22f), CornerRadius(6f))
    drawLine(p.blue, Offset(135f, 83f), Offset(185f, 82f - lift), 9f, StrokeCap.Round)
    symbol(225f, 39f, 1.6f) { brakeSymbol(if (step == 0 || (step == 1 && t < .45f)) p.line else p.navy) }
}

internal fun DrawScope.drawAutoHold(step: Int, t: Float, p: Palette) {
    val colors = listOf(Color.White, Color(0xFF76DA9C), Color(0xFFFFCC65))
    drawRoundRect(Color(0xFF152331), Offset(60f, 33f), Size(240f, 87f), CornerRadius(19f))
    for (i in 0..2) {
        val center = Offset(112f + i * 68f, 77f)
        if (step == i) drawCircle(colors[i], 21f + 4f * t, center, style = Stroke(2.5f))
        drawCircle(if (step == i) colors[i] else p.muted.copy(alpha = .45f), 13f, center)
    }
}
