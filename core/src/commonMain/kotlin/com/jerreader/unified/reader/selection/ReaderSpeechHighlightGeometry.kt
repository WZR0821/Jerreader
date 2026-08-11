package com.jerreader.unified.reader.selection

import com.jerreader.unified.reader.geometry.ReaderRect
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The fraction of the selected text that is currently being spoken, as a range
 * over the whole selection.
 */
data class ReaderSpeechHighlight(
    val lowerBound: Double,
    val upperBound: Double
) {
    companion object {
        fun of(lower: Double, upper: Double): ReaderSpeechHighlight? {
            if (!lower.isFinite() || !upper.isFinite()) return null
            val low = lower.coerceIn(0.0, 1.0)
            val high = upper.coerceIn(low, 1.0)
            if (high <= low) return null
            return ReaderSpeechHighlight(low, high)
        }
    }
}

/**
 * Slices the painted selection tiles down to just the span being read aloud.
 *
 * Progress arrives as a fraction of the whole utterance, so the tiles are
 * treated as equal shares of it and the first and last tiles are cut
 * proportionally. That is an approximation — tiles are not equal in glyph count
 * — but it tracks speech closely enough to follow, and it needs nothing from
 * the speech engine beyond a progress fraction, which is all either platform
 * reliably reports.
 *
 * The cut runs along the line axis: down a tile in vertical text, across it in
 * horizontal text.
 */
object ReaderSpeechHighlightGeometry {

    /** A tile this much taller than it is wide is a vertical line of text. */
    private const val VERTICAL_ASPECT_RATIO = 1.55

    fun rectangles(
        sourceRectangles: List<ReaderRect>,
        highlight: ReaderSpeechHighlight?,
        mode: ReaderWritingMode? = null
    ): List<ReaderRect> {
        if (highlight == null) return emptyList()
        val tiles = sourceRectangles.map { it.standardized() }.filter { it.isUsable }
        if (tiles.isEmpty()) return emptyList()

        val lower = highlight.lowerBound.coerceIn(0.0, 1.0)
        val upper = highlight.upperBound.coerceIn(lower, 1.0)
        if (upper <= lower) return emptyList()

        val count = tiles.size.toDouble()
        val firstIndex = floor(lower * count).toInt().coerceIn(0, tiles.lastIndex)
        val lastIndex = (ceil(upper * count).toInt() - 1)
            .coerceIn(firstIndex, tiles.lastIndex)

        return (firstIndex..lastIndex).mapNotNull { index ->
            val tile = tiles[index]
            val segmentStart = index / count
            val segmentEnd = (index + 1) / count
            val segmentLength = (segmentEnd - segmentStart).coerceAtLeast(Double.MIN_VALUE)
            val localLower = ((lower - segmentStart) / segmentLength).coerceIn(0.0, 1.0)
            val localUpper = ((upper - segmentStart) / segmentLength).coerceIn(localLower, 1.0)
            if (localUpper <= localLower) return@mapNotNull null

            val vertical = mode?.isVertical
                ?: (tile.height > tile.width * VERTICAL_ASPECT_RATIO)
            if (vertical) {
                ReaderRect(
                    x = tile.x,
                    y = tile.y + tile.height * localLower,
                    width = tile.width,
                    height = tile.height * (localUpper - localLower)
                )
            } else {
                ReaderRect(
                    x = tile.x + tile.width * localLower,
                    y = tile.y,
                    width = tile.width * (localUpper - localLower),
                    height = tile.height
                )
            }
        }
    }
}
