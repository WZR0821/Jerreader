package com.jerreader.unified.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.jerreader.unified.reader.geometry.ReaderRect
import com.jerreader.unified.reader.selection.ReaderSelectionHighlightMetrics
import com.jerreader.unified.reader.selection.ReaderSelectionPalette
import com.jerreader.unified.reader.selection.ReaderSpeechHighlight
import com.jerreader.unified.reader.selection.ReaderSpeechHighlightGeometry
import com.jerreader.unified.reader.selection.ReaderWritingMode

/**
 * Paints the selection above the reader's own content.
 *
 * This is the layer that made the two apps look different for the same
 * sentence: iOS drew it with Core Graphics in a UIKit sibling view, Android
 * with a Compose `Canvas`, and the two had picked different corner radii,
 * stroke widths and insets. One Compose Multiplatform layer means both readers
 * paint the same shape from the same tile list, and read-aloud gets the spoken
 * span on Android for the first time.
 *
 * The layer is deliberately non-interactive and coordinates are already in
 * overlay space — the controller converted them from CSS pixels — so nothing
 * here has to know about scroll offsets or column transforms.
 */
@Composable
fun ReaderSelectionHighlightLayer(
    tiles: List<ReaderRect>,
    palette: ReaderSelectionPalette,
    modifier: Modifier = Modifier,
    speechHighlight: ReaderSpeechHighlight? = null,
    writingMode: ReaderWritingMode = ReaderWritingMode.HORIZONTAL,
    /** Saved annotations, drawn beneath the live selection. */
    persistedTiles: List<ReaderRect> = emptyList(),
    persistedColor: Color? = null
) {
    if (tiles.isEmpty() && persistedTiles.isEmpty()) return

    val fill = Color(palette.fillArgb.toComposeArgb())
    val stroke = Color(palette.strokeArgb.toComposeArgb())
    val spokenFill = Color(palette.spokenFillArgb.toComposeArgb())

    Canvas(modifier = modifier) {
        persistedColor?.let { color ->
            persistedTiles.forEach { tile -> fillTile(tile, color) }
        }

        tiles.forEach { tile ->
            // The half-point outset closes the hairline seam that otherwise
            // shows between two tiles of one line at fractional scale factors.
            val outset = ReaderSelectionHighlightMetrics.OUTSET.toFloat()
            val painted = tile.inset(-outset.toDouble(), -outset.toDouble())
            fillTile(painted, fill)
            strokeTile(painted, stroke)
        }

        ReaderSpeechHighlightGeometry
            .rectangles(tiles, speechHighlight, writingMode)
            .forEach { spoken -> fillTile(spoken, spokenFill) }
    }
}

private fun DrawScope.fillTile(tile: ReaderRect, color: Color) {
    val radius = ReaderSelectionHighlightMetrics.cornerRadius(tile.height).toFloat()
    drawRoundRect(
        color = color,
        topLeft = Offset(tile.x.toFloat(), tile.y.toFloat()),
        size = Size(tile.width.toFloat(), tile.height.toFloat()),
        cornerRadius = CornerRadius(radius, radius)
    )
}

private fun DrawScope.strokeTile(tile: ReaderRect, color: Color) {
    val radius = ReaderSelectionHighlightMetrics.cornerRadius(tile.height).toFloat()
    drawRoundRect(
        color = color,
        topLeft = Offset(tile.x.toFloat(), tile.y.toFloat()),
        size = Size(tile.width.toFloat(), tile.height.toFloat()),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = ReaderSelectionHighlightMetrics.STROKE_WIDTH.toFloat())
    )
}

/** The palette stores 0xAARRGGBB as a Long; Compose wants an unsigned Int. */
private fun Long.toComposeArgb(): Int = (this and 0xFFFFFFFFL).toInt()
