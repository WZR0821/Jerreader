package com.jerreader.shared.library

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One selection rectangle in CSS pixels, in the page's own coordinate space. */
data class ReaderSelectionRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
) {
    val right: Double get() = x + width
    val bottom: Double get() = y + height
}

/**
 * The boxes of one ruby annotation (`rt`) that belongs to a selection.
 *
 * [baseIndices] point into the base-text rects handed to
 * [ReaderSelectionRectMerger.merge]: the boxes of the selected kanji this
 * reading sits over. [rects] are the annotation's own boxes. [baseFullySelected]
 * is false when only part of the text the reading belongs to is selected; the
 * reading is then clipped, along the line, to the selected kanji.
 */
data class ReaderRubyAnnotationRects(
    val baseIndices: List<Int>,
    val rects: List<ReaderSelectionRect>,
    val baseFullySelected: Boolean = true
)

/**
 * `Range.getClientRects()` hands back one rectangle per inline fragment, so a
 * single selected sentence arrives as a pile of boxes that overlap each other:
 * a ruby base and its surrounding text report boxes that share pixels, fragments
 * on the same line touch or overrun by a fraction, and a line's boxes can bleed
 * into the neighbouring line's leading. Painting those raw boxes stacks the
 * translucent fill and draws the outline several times, which is what shows up
 * as a doubled, patchy highlight in both horizontal and vertical books.
 *
 * This merges the raw boxes into exactly one rectangle per contiguous run per
 * line, so every painted pixel is covered once. The line axis flips with the
 * writing mode: horizontal text stacks lines down the y axis and runs along x,
 * vertical text stacks lines along x and runs down y.
 *
 * Ruby annotations join the tile of the kanji they sit over, so a selection
 * with furigana is one region from the top of the kana to the bottom of the
 * kanji (in vertical text, from the kanji to the outer edge of the kana)
 * instead of a strip for the text and a separate strip for the reading. The
 * same rules run in the reader's page script on both platforms
 * (`ReaderSelectionTileScript`); the shared cases are in
 * `shared/parity/selection_tiles.json`.
 */
object ReaderSelectionRectMerger {

    /** Fragments this thin are layout artefacts rather than glyphs. */
    private const val MINIMUM_EXTENT = 0.5

    /** Two fragments belong to one line once they share this much of the shorter cross extent. */
    private const val SAME_LINE_OVERLAP_RATIO = 0.55

    /** Runs closer than this along the line are one visual run. */
    private const val RUN_GAP_TOLERANCE = 1.5

    /** Candidate kanji whose overlap with a reading differs by less than this are a tie. */
    private const val OVERLAP_TIE = 0.01

    fun merge(rects: List<ReaderSelectionRect>, vertical: Boolean): List<ReaderSelectionRect> =
        merge(rects, vertical, emptyList())

    /**
     * Merges [rects] (base text, in document order) into tiles and joins each
     * annotation box to the tile of the selected base box it sits over: the one
     * it overlaps most along the line, then the nearest across it. It joins only
     * when it overlaps that box along the line and sits no further from it,
     * across the line, than the box is thick; otherwise it stays a tile of its
     * own. Lines are told apart by the base boxes alone, so a reading reaching
     * into the line above never merges the two lines; the joined boxes only
     * decide what is painted.
     */
    fun merge(
        rects: List<ReaderSelectionRect>,
        vertical: Boolean,
        annotations: List<ReaderRubyAnnotationRects>
    ): List<ReaderSelectionRect> {
        val axis = Axis(vertical)
        val items: List<Item?> = rects.map { if (it.isUsable()) Item(line = it, paint = it) else null }
        val extra = mutableListOf<Item>()
        for (annotation in annotations) {
            val candidates = annotation.baseIndices.mapNotNull { items.getOrNull(it) }
            for (raw in annotation.rects) {
                if (!raw.isUsable()) continue
                var rect = raw
                if (!annotation.baseFullySelected && candidates.isNotEmpty()) {
                    // Only the kana over the selected kanji belong to the selection.
                    val start = max(axis.runStart(rect), candidates.minOf { axis.runStart(it.line) })
                    val end = min(axis.runEnd(rect), candidates.maxOf { axis.runEnd(it.line) })
                    if (end - start <= MINIMUM_EXTENT) continue
                    rect = axis.withRun(rect, start, end)
                }
                var best: Item? = null
                var bestOverlap = Double.NEGATIVE_INFINITY
                var bestGap = Double.POSITIVE_INFINITY
                for (candidate in candidates) {
                    val overlap = min(axis.runEnd(candidate.line), axis.runEnd(rect)) -
                        max(axis.runStart(candidate.line), axis.runStart(rect))
                    val gap = maxOf(
                        0.0,
                        axis.crossStart(candidate.line) - axis.crossEnd(rect),
                        axis.crossStart(rect) - axis.crossEnd(candidate.line)
                    )
                    if (overlap > bestOverlap + OVERLAP_TIE ||
                        (abs(overlap - bestOverlap) <= OVERLAP_TIE && gap < bestGap)
                    ) {
                        best = candidate
                        bestOverlap = overlap
                        bestGap = gap
                    }
                }
                val target = best
                if (target != null && bestOverlap > 0.0 &&
                    bestGap <= axis.crossEnd(target.line) - axis.crossStart(target.line)
                ) {
                    target.paint = target.paint.union(rect)
                } else {
                    extra.add(Item(line = rect, paint = rect))
                }
            }
        }

        val live = items.filterNotNull() + extra
        if (live.size <= 1) return live.map { it.paint }

        val buckets = mutableListOf<MutableList<Item>>()
        for (item in live.sortedBy { axis.crossStart(it.line) }) {
            val bucket = buckets.firstOrNull { existing ->
                existing.any { sharesLine(it.line, item.line, axis) }
            }
            if (bucket == null) buckets.add(mutableListOf(item)) else bucket.add(item)
        }
        val lines = buckets.map { bucket ->
            Line(
                crossStart = bucket.minOf { axis.crossStart(it.paint) },
                crossEnd = bucket.maxOf { axis.crossEnd(it.paint) },
                runs = mergeRuns(bucket.map { Run(axis.runStart(it.paint), axis.runEnd(it.paint)) })
            )
        }.sortedBy { it.crossStart }

        return separateLines(lines).flatMap { line ->
            line.runs.map { run -> line.rectFor(run, vertical) }
        }
    }

    /** `line` decides which line a box belongs to; `paint` is what gets drawn. */
    private class Item(val line: ReaderSelectionRect, var paint: ReaderSelectionRect)

    private class Axis(private val vertical: Boolean) {
        fun crossStart(rect: ReaderSelectionRect) = if (vertical) rect.x else rect.y
        fun crossEnd(rect: ReaderSelectionRect) = if (vertical) rect.right else rect.bottom
        fun runStart(rect: ReaderSelectionRect) = if (vertical) rect.y else rect.x
        fun runEnd(rect: ReaderSelectionRect) = if (vertical) rect.bottom else rect.right

        fun withRun(rect: ReaderSelectionRect, start: Double, end: Double) = if (vertical) {
            rect.copy(y = start, height = end - start)
        } else {
            rect.copy(x = start, width = end - start)
        }
    }

    private fun ReaderSelectionRect.isUsable() =
        x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite() &&
            width > MINIMUM_EXTENT && height > MINIMUM_EXTENT

    private fun ReaderSelectionRect.union(other: ReaderSelectionRect): ReaderSelectionRect {
        val left = min(x, other.x)
        val top = min(y, other.y)
        return ReaderSelectionRect(
            x = left,
            y = top,
            width = max(right, other.right) - left,
            height = max(bottom, other.bottom) - top
        )
    }

    private data class Run(val start: Double, val end: Double)

    private data class Line(
        val crossStart: Double,
        val crossEnd: Double,
        val runs: List<Run>
    ) {
        fun rectFor(run: Run, vertical: Boolean): ReaderSelectionRect = if (vertical) {
            ReaderSelectionRect(
                x = crossStart,
                y = run.start,
                width = crossEnd - crossStart,
                height = run.end - run.start
            )
        } else {
            ReaderSelectionRect(
                x = run.start,
                y = crossStart,
                width = run.end - run.start,
                height = crossEnd - crossStart
            )
        }
    }

    private fun sharesLine(first: ReaderSelectionRect, second: ReaderSelectionRect, axis: Axis): Boolean {
        val overlap = min(axis.crossEnd(first), axis.crossEnd(second)) -
            max(axis.crossStart(first), axis.crossStart(second))
        if (overlap <= 0.0) return false
        val shorter = min(
            axis.crossEnd(first) - axis.crossStart(first),
            axis.crossEnd(second) - axis.crossStart(second)
        )
        if (shorter <= 0.0) return false
        return overlap / shorter >= SAME_LINE_OVERLAP_RATIO
    }

    private fun mergeRuns(runs: List<Run>): List<Run> {
        val sorted = runs.sortedBy { it.start }
        val merged = mutableListOf<Run>()
        for (run in sorted) {
            val last = merged.lastOrNull()
            if (last != null && run.start <= last.end + RUN_GAP_TOLERANCE) {
                merged[merged.lastIndex] = Run(last.start, max(last.end, run.end))
            } else {
                merged.add(run)
            }
        }
        return merged
    }

    /**
     * A ruby base reserves room for its annotation, so its box is taller (or, in
     * vertical text, wider) than the plain glyphs beside it and the line it joins
     * ends up overlapping its neighbour. Neighbouring lines meet at the midpoint
     * of the overlap instead, which keeps both bands centred on their own text
     * and still leaves no pixel painted twice.
     */
    private fun separateLines(lines: List<Line>): List<Line> {
        if (lines.size <= 1) return lines
        val adjusted = lines.toMutableList()
        for (index in 1 until adjusted.size) {
            val previous = adjusted[index - 1]
            val current = adjusted[index]
            if (current.crossStart >= previous.crossEnd) continue
            val boundary = (current.crossStart + previous.crossEnd) / 2.0
            if (boundary <= previous.crossStart || boundary >= current.crossEnd) continue
            adjusted[index - 1] = previous.copy(crossEnd = boundary)
            adjusted[index] = current.copy(crossStart = boundary)
        }
        return adjusted
    }
}
