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
    CHEVRON_DOWN,
    CHEVRON_DOUBLE_LEFT,
    CHEVRON_DOUBLE_RIGHT,
    SPEECH_BUBBLE,
    SPEECH_BUBBLE_FILLED,
    MORE,
    CHEVRON_FORWARD,
    TREND,
    GRID,
    CLOCK,
    PLUS,
    SORT,
    CHECKLIST,
    SEARCH,
    INFO,
    SLIDERS,
    PALETTE,
    QUESTION,
    SHIELD,
    BOOKS,
    DICTIONARY,
    GEAR,
    STAR_OUTLINE,
    STAR_FILLED,
    DOWNLOAD,
    LOCK,
    SWAP,
    COPY,
    WARNING,
    CHECK_CIRCLE,
    TRASH,
    SHARE
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
            JerreaderIcon.CHEVRON_FORWARD ->
                drawChevrons(tint, stroke, unit, pointsLeft = false, count = 1)
            JerreaderIcon.TREND -> drawTrend(tint, stroke, unit)
            JerreaderIcon.GRID -> drawGrid(tint, unit)
            JerreaderIcon.CLOCK -> drawClock(tint, stroke, unit)
            JerreaderIcon.PLUS -> drawPlus(tint, stroke, unit)
            JerreaderIcon.SORT -> drawSort(tint, stroke, unit)
            JerreaderIcon.CHECKLIST -> drawChecklist(tint, stroke, unit)
            JerreaderIcon.SEARCH -> drawSearch(tint, stroke, unit)
            JerreaderIcon.INFO -> drawInfo(tint, stroke, unit)
            JerreaderIcon.SLIDERS -> drawSliders(tint, stroke, unit)
            JerreaderIcon.PALETTE -> drawPalette(tint, stroke, unit)
            JerreaderIcon.QUESTION -> drawQuestion(tint, stroke, unit)
            JerreaderIcon.SHIELD -> drawShield(tint, stroke, unit)
            JerreaderIcon.BOOKS -> drawBooks(tint, unit)
            JerreaderIcon.DICTIONARY -> drawDictionary(tint, stroke, unit)
            JerreaderIcon.GEAR -> drawGear(tint, stroke, unit)
            JerreaderIcon.STAR_OUTLINE -> drawStar(tint, stroke, unit, filled = false)
            JerreaderIcon.STAR_FILLED -> drawStar(tint, stroke, unit, filled = true)
            JerreaderIcon.DOWNLOAD -> drawDownload(tint, stroke, unit)
            JerreaderIcon.LOCK -> drawLock(tint, stroke, unit)
            JerreaderIcon.CHEVRON_DOWN -> drawChevronDown(tint, stroke, unit)
            JerreaderIcon.SWAP -> drawSwap(tint, stroke, unit)
            JerreaderIcon.COPY -> drawCopy(tint, stroke, unit)
            JerreaderIcon.WARNING -> drawWarning(tint, stroke, unit)
            JerreaderIcon.CHECK_CIRCLE -> drawCheckCircle(tint, unit)
            JerreaderIcon.TRASH -> drawTrash(tint, stroke, unit)
            JerreaderIcon.SHARE -> drawShare(tint, stroke, unit)
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

private fun DrawScope.drawChevronDown(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    val width = unit * 0.22f
    val height = unit * 0.13f
    drawLine(tint, Offset(c.x - width, c.y - height), Offset(c.x, c.y + height), stroke.width, stroke.cap)
    drawLine(tint, Offset(c.x, c.y + height), Offset(c.x + width, c.y - height), stroke.width, stroke.cap)
}

/** `arrow.left.arrow.right`: the language swap control. */
private fun DrawScope.drawSwap(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    val half = unit * 0.26f
    val gap = unit * 0.12f
    val head = unit * 0.09f
    drawLine(tint, Offset(c.x - half, c.y - gap), Offset(c.x + half, c.y - gap), stroke.width, stroke.cap)
    drawLine(
        tint,
        Offset(c.x + half - head, c.y - gap - head),
        Offset(c.x + half, c.y - gap),
        stroke.width,
        stroke.cap
    )
    drawLine(
        tint,
        Offset(c.x + half - head, c.y - gap + head),
        Offset(c.x + half, c.y - gap),
        stroke.width,
        stroke.cap
    )
    drawLine(tint, Offset(c.x + half, c.y + gap), Offset(c.x - half, c.y + gap), stroke.width, stroke.cap)
    drawLine(
        tint,
        Offset(c.x - half + head, c.y + gap - head),
        Offset(c.x - half, c.y + gap),
        stroke.width,
        stroke.cap
    )
    drawLine(
        tint,
        Offset(c.x - half + head, c.y + gap + head),
        Offset(c.x - half, c.y + gap),
        stroke.width,
        stroke.cap
    )
}

private fun DrawScope.drawCopy(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    val side = unit * 0.38f
    drawRoundRect(
        color = tint,
        topLeft = Offset(c.x - unit * 0.30f, c.y - unit * 0.30f),
        size = androidx.compose.ui.geometry.Size(side, side),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 0.07f),
        style = stroke
    )
    drawRoundRect(
        color = tint,
        topLeft = Offset(c.x - unit * 0.08f, c.y - unit * 0.08f),
        size = androidx.compose.ui.geometry.Size(side, side),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 0.07f),
        style = stroke
    )
}

private fun DrawScope.drawWarning(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    val path = Path().apply {
        moveTo(c.x, c.y - unit * 0.30f)
        lineTo(c.x + unit * 0.32f, c.y + unit * 0.26f)
        lineTo(c.x - unit * 0.32f, c.y + unit * 0.26f)
        close()
    }
    drawPath(path, tint, style = stroke)
    drawLine(
        tint,
        Offset(c.x, c.y - unit * 0.08f),
        Offset(c.x, c.y + unit * 0.08f),
        stroke.width,
        stroke.cap
    )
    drawCircle(tint, radius = unit * 0.035f, center = Offset(c.x, c.y + unit * 0.18f))
}

private fun DrawScope.drawCheckCircle(tint: Color, unit: Float) {
    val c = centered(unit)
    drawCircle(tint, radius = unit * 0.34f, center = c)
    val tick = Stroke(width = unit * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val path = Path().apply {
        moveTo(c.x - unit * 0.15f, c.y)
        lineTo(c.x - unit * 0.04f, c.y + unit * 0.12f)
        lineTo(c.x + unit * 0.17f, c.y - unit * 0.13f)
    }
    drawPath(path, Color.White, style = tick)
}

private fun DrawScope.drawTrash(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    drawLine(
        tint,
        Offset(c.x - unit * 0.28f, c.y - unit * 0.18f),
        Offset(c.x + unit * 0.28f, c.y - unit * 0.18f),
        stroke.width,
        stroke.cap
    )
    val body = Path().apply {
        moveTo(c.x - unit * 0.21f, c.y - unit * 0.18f)
        lineTo(c.x - unit * 0.17f, c.y + unit * 0.28f)
        lineTo(c.x + unit * 0.17f, c.y + unit * 0.28f)
        lineTo(c.x + unit * 0.21f, c.y - unit * 0.18f)
    }
    drawPath(body, tint, style = stroke)
    val lid = Path().apply {
        moveTo(c.x - unit * 0.11f, c.y - unit * 0.18f)
        lineTo(c.x - unit * 0.11f, c.y - unit * 0.28f)
        lineTo(c.x + unit * 0.11f, c.y - unit * 0.28f)
        lineTo(c.x + unit * 0.11f, c.y - unit * 0.18f)
    }
    drawPath(lid, tint, style = stroke)
}

/** `square.and.arrow.up`: a tray with an arrow leaving through the top. */
private fun DrawScope.drawShare(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    val tray = Path().apply {
        moveTo(c.x - unit * 0.13f, c.y - unit * 0.05f)
        lineTo(c.x - unit * 0.25f, c.y - unit * 0.05f)
        lineTo(c.x - unit * 0.25f, c.y + unit * 0.29f)
        lineTo(c.x + unit * 0.25f, c.y + unit * 0.29f)
        lineTo(c.x + unit * 0.25f, c.y - unit * 0.05f)
        lineTo(c.x + unit * 0.13f, c.y - unit * 0.05f)
    }
    drawPath(tray, tint, style = stroke)
    drawLine(
        tint,
        Offset(c.x, c.y - unit * 0.30f),
        Offset(c.x, c.y + unit * 0.10f),
        stroke.width,
        stroke.cap
    )
    drawLine(
        tint,
        Offset(c.x - unit * 0.12f, c.y - unit * 0.18f),
        Offset(c.x, c.y - unit * 0.30f),
        stroke.width,
        stroke.cap
    )
    drawLine(
        tint,
        Offset(c.x + unit * 0.12f, c.y - unit * 0.18f),
        Offset(c.x, c.y - unit * 0.30f),
        stroke.width,
        stroke.cap
    )
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

private fun DrawScope.drawTrend(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    val points = listOf(
        Offset(c.x - unit * 0.30f, c.y + unit * 0.16f),
        Offset(c.x - unit * 0.08f, c.y - unit * 0.06f),
        Offset(c.x + unit * 0.06f, c.y + unit * 0.06f),
        Offset(c.x + unit * 0.30f, c.y - unit * 0.22f)
    )
    points.zipWithNext { a, b -> drawLine(tint, a, b, stroke.width, stroke.cap) }
    drawLine(
        tint,
        Offset(c.x + unit * 0.16f, c.y - unit * 0.22f),
        Offset(c.x + unit * 0.30f, c.y - unit * 0.22f),
        stroke.width,
        stroke.cap
    )
    drawLine(
        tint,
        Offset(c.x + unit * 0.30f, c.y - unit * 0.22f),
        Offset(c.x + unit * 0.30f, c.y - unit * 0.08f),
        stroke.width,
        stroke.cap
    )
}

private fun DrawScope.drawGrid(tint: Color, unit: Float) {
    val c = centered(unit)
    val gap = unit * 0.15f
    val side = unit * 0.22f
    listOf(-1, 1).forEach { row ->
        listOf(-1, 1).forEach { column ->
            val left = c.x + column * gap - side / 2f
            val top = c.y + row * gap - side / 2f
            drawRoundRect(
                color = tint,
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(side, side),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 0.04f)
            )
        }
    }
}

private fun DrawScope.drawClock(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    drawCircle(tint, radius = unit * 0.30f, center = c, style = stroke)
    drawLine(tint, c, Offset(c.x, c.y - unit * 0.17f), stroke.width, stroke.cap)
    drawLine(tint, c, Offset(c.x + unit * 0.13f, c.y), stroke.width, stroke.cap)
}

private fun DrawScope.drawPlus(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    val r = unit * 0.28f
    drawLine(tint, Offset(c.x - r, c.y), Offset(c.x + r, c.y), stroke.width, stroke.cap)
    drawLine(tint, Offset(c.x, c.y - r), Offset(c.x, c.y + r), stroke.width, stroke.cap)
}

private fun DrawScope.drawSort(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    val h = unit * 0.28f
    val dx = unit * 0.14f
    drawLine(tint, Offset(c.x - dx, c.y - h), Offset(c.x - dx, c.y + h), stroke.width, stroke.cap)
    drawLine(
        tint,
        Offset(c.x - dx - unit * 0.08f, c.y + h - unit * 0.10f),
        Offset(c.x - dx, c.y + h),
        stroke.width,
        stroke.cap
    )
    drawLine(
        tint,
        Offset(c.x - dx + unit * 0.08f, c.y + h - unit * 0.10f),
        Offset(c.x - dx, c.y + h),
        stroke.width,
        stroke.cap
    )
    drawLine(tint, Offset(c.x + dx, c.y - h), Offset(c.x + dx, c.y + h), stroke.width, stroke.cap)
    drawLine(
        tint,
        Offset(c.x + dx - unit * 0.08f, c.y - h + unit * 0.10f),
        Offset(c.x + dx, c.y - h),
        stroke.width,
        stroke.cap
    )
    drawLine(
        tint,
        Offset(c.x + dx + unit * 0.08f, c.y - h + unit * 0.10f),
        Offset(c.x + dx, c.y - h),
        stroke.width,
        stroke.cap
    )
}

private fun DrawScope.drawChecklist(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    listOf(-unit * 0.20f, unit * 0.14f).forEachIndexed { index, dy ->
        if (index == 0) {
            drawCircle(tint, radius = unit * 0.09f, center = Offset(c.x - unit * 0.24f, c.y + dy), style = stroke)
        } else {
            drawCircle(tint, radius = unit * 0.09f, center = Offset(c.x - unit * 0.24f, c.y + dy), style = stroke)
        }
        drawLine(
            tint,
            Offset(c.x - unit * 0.08f, c.y + dy),
            Offset(c.x + unit * 0.30f, c.y + dy),
            stroke.width,
            stroke.cap
        )
    }
}

private fun DrawScope.drawSearch(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    drawCircle(
        tint,
        radius = unit * 0.20f,
        center = Offset(c.x - unit * 0.05f, c.y - unit * 0.05f),
        style = stroke
    )
    drawLine(
        tint,
        Offset(c.x + unit * 0.10f, c.y + unit * 0.10f),
        Offset(c.x + unit * 0.26f, c.y + unit * 0.26f),
        stroke.width,
        stroke.cap
    )
}

private fun DrawScope.drawInfo(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    drawCircle(tint, radius = unit * 0.32f, center = c, style = stroke)
    drawCircle(tint, radius = unit * 0.035f, center = Offset(c.x, c.y - unit * 0.14f))
    drawLine(
        tint,
        Offset(c.x, c.y - unit * 0.02f),
        Offset(c.x, c.y + unit * 0.16f),
        stroke.width,
        stroke.cap
    )
}

private fun DrawScope.drawSliders(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    listOf(-unit * 0.18f to -unit * 0.06f, unit * 0.18f to unit * 0.10f).forEach { (dy, knob) ->
        drawLine(
            tint,
            Offset(c.x - unit * 0.30f, c.y + dy),
            Offset(c.x + unit * 0.30f, c.y + dy),
            stroke.width,
            stroke.cap
        )
        drawCircle(tint, radius = unit * 0.075f, center = Offset(c.x + knob, c.y + dy))
    }
}

private fun DrawScope.drawPalette(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    drawCircle(tint, radius = unit * 0.30f, center = c, style = stroke)
    listOf(
        Offset(c.x - unit * 0.13f, c.y - unit * 0.10f),
        Offset(c.x + unit * 0.02f, c.y - unit * 0.16f),
        Offset(c.x + unit * 0.15f, c.y - unit * 0.02f)
    ).forEach { drawCircle(tint, radius = unit * 0.045f, center = it) }
}

private fun DrawScope.drawQuestion(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    drawCircle(tint, radius = unit * 0.32f, center = c, style = stroke)
    val path = Path().apply {
        moveTo(c.x - unit * 0.09f, c.y - unit * 0.09f)
        quadraticTo(c.x - unit * 0.02f, c.y - unit * 0.22f, c.x + unit * 0.08f, c.y - unit * 0.12f)
        quadraticTo(c.x + unit * 0.14f, c.y - unit * 0.02f, c.x, c.y + unit * 0.04f)
    }
    drawPath(path, tint, style = stroke)
    drawCircle(tint, radius = unit * 0.035f, center = Offset(c.x, c.y + unit * 0.17f))
}

private fun DrawScope.drawShield(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    val path = Path().apply {
        moveTo(c.x, c.y - unit * 0.30f)
        lineTo(c.x + unit * 0.24f, c.y - unit * 0.18f)
        lineTo(c.x + unit * 0.24f, c.y + unit * 0.04f)
        quadraticTo(c.x + unit * 0.20f, c.y + unit * 0.24f, c.x, c.y + unit * 0.31f)
        quadraticTo(c.x - unit * 0.20f, c.y + unit * 0.24f, c.x - unit * 0.24f, c.y + unit * 0.04f)
        lineTo(c.x - unit * 0.24f, c.y - unit * 0.18f)
        close()
    }
    drawPath(path, tint, style = stroke)
}

private fun DrawScope.drawBooks(tint: Color, unit: Float) {
    val c = centered(unit)
    val heights = listOf(0.46f, 0.56f, 0.40f)
    val width = unit * 0.13f
    heights.forEachIndexed { index, factor ->
        val height = unit * factor
        val left = c.x - unit * 0.24f + index * (width + unit * 0.05f)
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, c.y + unit * 0.26f - height),
            size = androidx.compose.ui.geometry.Size(width, height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 0.03f)
        )
    }
}

private fun DrawScope.drawDictionary(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    val left = c.x - unit * 0.26f
    val right = c.x + unit * 0.26f
    val top = c.y - unit * 0.30f
    val bottom = c.y + unit * 0.30f
    drawRoundRect(
        color = tint,
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 0.06f),
        style = stroke
    )
    drawLine(tint, Offset(left + unit * 0.12f, top), Offset(left + unit * 0.12f, bottom), stroke.width, stroke.cap)
}

private fun DrawScope.drawGear(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    drawCircle(tint, radius = unit * 0.14f, center = c, style = stroke)
    repeat(8) { index ->
        val angle = index * kotlin.math.PI.toFloat() / 4f
        val inner = unit * 0.22f
        val outer = unit * 0.31f
        drawLine(
            tint,
            Offset(c.x + inner * kotlin.math.cos(angle), c.y + inner * kotlin.math.sin(angle)),
            Offset(c.x + outer * kotlin.math.cos(angle), c.y + outer * kotlin.math.sin(angle)),
            stroke.width,
            stroke.cap
        )
    }
}

private fun DrawScope.drawStar(tint: Color, stroke: Stroke, unit: Float, filled: Boolean) {
    val c = centered(unit)
    val outer = unit * 0.31f
    val inner = unit * 0.135f
    val path = Path()
    repeat(10) { index ->
        val radius = if (index % 2 == 0) outer else inner
        val angle = -kotlin.math.PI.toFloat() / 2f + index * kotlin.math.PI.toFloat() / 5f
        val x = c.x + radius * kotlin.math.cos(angle)
        val y = c.y + radius * kotlin.math.sin(angle)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    if (filled) drawPath(path, tint) else drawPath(path, tint, style = stroke)
}

private fun DrawScope.drawDownload(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    drawLine(tint, Offset(c.x, c.y - unit * 0.26f), Offset(c.x, c.y + unit * 0.08f), stroke.width, stroke.cap)
    drawLine(tint, Offset(c.x - unit * 0.12f, c.y - unit * 0.04f), Offset(c.x, c.y + unit * 0.08f), stroke.width, stroke.cap)
    drawLine(tint, Offset(c.x + unit * 0.12f, c.y - unit * 0.04f), Offset(c.x, c.y + unit * 0.08f), stroke.width, stroke.cap)
    drawLine(tint, Offset(c.x - unit * 0.24f, c.y + unit * 0.24f), Offset(c.x + unit * 0.24f, c.y + unit * 0.24f), stroke.width, stroke.cap)
}

private fun DrawScope.drawLock(tint: Color, stroke: Stroke, unit: Float) {
    val c = centered(unit)
    drawRoundRect(
        color = tint,
        topLeft = Offset(c.x - unit * 0.20f, c.y - unit * 0.02f),
        size = androidx.compose.ui.geometry.Size(unit * 0.40f, unit * 0.30f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 0.06f),
        style = stroke
    )
    val arc = Path().apply {
        moveTo(c.x - unit * 0.12f, c.y - unit * 0.02f)
        lineTo(c.x - unit * 0.12f, c.y - unit * 0.14f)
        quadraticTo(c.x, c.y - unit * 0.30f, c.x + unit * 0.12f, c.y - unit * 0.14f)
        lineTo(c.x + unit * 0.12f, c.y - unit * 0.02f)
    }
    drawPath(arc, tint, style = stroke)
}
