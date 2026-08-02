package com.jerreader.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Reader glyphs drawn with Canvas rather than pulled from an icon artifact.
 * Compose Multiplatform 1.11 does not publish `material-icons-core`, and the
 * shapes here are simple enough that drawing them keeps the app dependency
 * free and identical on every supported API level.
 */
enum class JerreaderIcon {
    CLOSE,
    TABLE_OF_CONTENTS,
    BOOKMARK_OUTLINE,
    BOOKMARK_FILLED,
    TEXT_FORMAT,
    CHEVRON_LEFT,
    CHEVRON_RIGHT,
    CHEVRON_DOUBLE_LEFT,
    CHEVRON_DOUBLE_RIGHT,
    SPEECH_BUBBLE,
    SPEECH_BUBBLE_FILLED,
    MORE,
    CHEVRON_FORWARD
}

@Composable
fun JerreaderGlyph(
    icon: JerreaderIcon,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val unit = size.minDimension
        val stroke = Stroke(
            width = unit * 0.11f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        when (icon) {
            JerreaderIcon.CLOSE -> drawClose(tint, stroke, unit)
            JerreaderIcon.TABLE_OF_CONTENTS -> drawTableOfContents(tint, stroke, unit)
            JerreaderIcon.BOOKMARK_OUTLINE -> drawBookmark(tint, stroke, unit, filled = false)
            JerreaderIcon.BOOKMARK_FILLED -> drawBookmark(tint, stroke, unit, filled = true)
            JerreaderIcon.TEXT_FORMAT -> drawTextFormat(tint, stroke, unit)
            JerreaderIcon.CHEVRON_LEFT -> drawChevrons(tint, stroke, unit, pointsLeft = true, count = 1)
            JerreaderIcon.CHEVRON_RIGHT -> drawChevrons(tint, stroke, unit, pointsLeft = false, count = 1)
            JerreaderIcon.CHEVRON_DOUBLE_LEFT ->
                drawChevrons(tint, stroke, unit, pointsLeft = true, count = 2)
            JerreaderIcon.CHEVRON_DOUBLE_RIGHT ->
                drawChevrons(tint, stroke, unit, pointsLeft = false, count = 2)
            JerreaderIcon.SPEECH_BUBBLE -> drawBubble(tint, stroke, unit, filled = false)
            JerreaderIcon.SPEECH_BUBBLE_FILLED -> drawBubble(tint, stroke, unit, filled = true)
            JerreaderIcon.MORE -> drawMore(tint, unit)
            JerreaderIcon.CHEVRON_FORWARD -> drawChevrons(tint, stroke, unit, pointsLeft = false, count = 1)
        }
    }
}

private fun DrawScope.centered(unit: Float): Offset =
    Offset(size.width / 2f, size.height / 2f)

private fun DrawScope.drawClose(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    val r = unit * 0.28f
    drawLine(tint, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), stroke.width, stroke.cap)
    drawLine(tint, Offset(c.x + r, c.y - r), Offset(c.x - r, c.y + r), stroke.width, stroke.cap)
}

private fun DrawScope.drawTableOfContents(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    val left = c.x - unit * 0.30f
    val right = c.x + unit * 0.30f
    val dotX = left
    val lineLeft = left + unit * 0.16f
    listOf(-unit * 0.22f, 0f, unit * 0.22f).forEach { dy ->
        drawCircle(tint, radius = unit * 0.045f, center = Offset(dotX, c.y + dy))
        drawLine(
            tint,
            Offset(lineLeft, c.y + dy),
            Offset(right, c.y + dy),
            stroke.width,
            stroke.cap
        )
    }
}

private fun DrawScope.drawBookmark(tint: Color, stroke: Stroke, unit: Float, filled: Boolean) {
    val c = centered(unit)
    val halfWidth = unit * 0.20f
    val top = c.y - unit * 0.30f
    val bottom = c.y + unit * 0.30f
    val path = Path().apply {
        moveTo(c.x - halfWidth, top)
        lineTo(c.x + halfWidth, top)
        lineTo(c.x + halfWidth, bottom)
        lineTo(c.x, bottom - unit * 0.16f)
        lineTo(c.x - halfWidth, bottom)
        close()
    }
    if (filled) drawPath(path, tint) else drawPath(path, tint, style = stroke)
}

private fun DrawScope.drawTextFormat(tint: Color, stroke: Stroke, unit: Float) {
    // A large and a small "A" side by side, the same idea as the iOS glyph.
    val c = centered(unit)
    fun letterA(centerX: Float, height: Float, baseline: Float) {
        val half = height * 0.42f
        drawLine(
            tint,
            Offset(centerX - half, baseline),
            Offset(centerX, baseline - height),
            stroke.width,
            stroke.cap
        )
        drawLine(
            tint,
            Offset(centerX + half, baseline),
            Offset(centerX, baseline - height),
            stroke.width,
            stroke.cap
        )
        drawLine(
            tint,
            Offset(centerX - half * 0.55f, baseline - height * 0.34f),
            Offset(centerX + half * 0.55f, baseline - height * 0.34f),
            stroke.width,
            stroke.cap
        )
    }
    letterA(c.x - unit * 0.17f, unit * 0.56f, c.y + unit * 0.28f)
    letterA(c.x + unit * 0.22f, unit * 0.34f, c.y + unit * 0.28f)
}

private fun DrawScope.drawChevrons(
    tint: Color,
    stroke: Stroke,
    unit: Float,
    pointsLeft: Boolean,
    count: Int
) {
    val c = centered(unit)
    val height = unit * 0.22f
    val width = unit * 0.15f
    val spacing = unit * 0.20f
    val startX = if (count == 1) c.x else c.x - spacing / 2f
    repeat(count) { index ->
        val x = startX + index * spacing
        val tipX = if (pointsLeft) x - width / 2f else x + width / 2f
        val backX = if (pointsLeft) x + width / 2f else x - width / 2f
        drawLine(tint, Offset(backX, c.y - height), Offset(tipX, c.y), stroke.width, stroke.cap)
        drawLine(tint, Offset(tipX, c.y), Offset(backX, c.y + height), stroke.width, stroke.cap)
    }
}

private fun DrawScope.drawBubble(tint: Color, stroke: Stroke, unit: Float, filled: Boolean) {
    val c = centered(unit)
    val halfWidth = unit * 0.30f
    val halfHeight = unit * 0.22f
    val radius = unit * 0.10f
    val path = Path().apply {
        moveTo(c.x - halfWidth + radius, c.y - halfHeight)
        lineTo(c.x + halfWidth - radius, c.y - halfHeight)
        quadraticTo(c.x + halfWidth, c.y - halfHeight, c.x + halfWidth, c.y - halfHeight + radius)
        lineTo(c.x + halfWidth, c.y + halfHeight - radius)
        quadraticTo(c.x + halfWidth, c.y + halfHeight, c.x + halfWidth - radius, c.y + halfHeight)
        lineTo(c.x - halfWidth * 0.30f, c.y + halfHeight)
        lineTo(c.x - halfWidth * 0.58f, c.y + halfHeight + unit * 0.14f)
        lineTo(c.x - halfWidth * 0.58f, c.y + halfHeight)
        lineTo(c.x - halfWidth + radius, c.y + halfHeight)
        quadraticTo(c.x - halfWidth, c.y + halfHeight, c.x - halfWidth, c.y + halfHeight - radius)
        lineTo(c.x - halfWidth, c.y - halfHeight + radius)
        quadraticTo(c.x - halfWidth, c.y - halfHeight, c.x - halfWidth + radius, c.y - halfHeight)
        close()
    }
    if (filled) drawPath(path, tint) else drawPath(path, tint, style = stroke)
}

private fun DrawScope.drawMore(tint: Color, unit: Float) {
    val c = centered(unit)
    val spacing = unit * 0.22f
    listOf(-spacing, 0f, spacing).forEach { dx ->
        drawCircle(tint, radius = unit * 0.055f, center = Offset(c.x + dx, c.y))
    }
}
