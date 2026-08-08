package com.jerreader.shared.library

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
 */
object ReaderSelectionRectMerger {

    /** Fragments this thin are layout artefacts rather than glyphs. */
    private const val MINIMUM_EXTENT = 0.5

    /** Two fragments belong to one line once they share this much of the shorter cross extent. */
    private const val SAME_LINE_OVERLAP_RATIO = 0.55

    /** Runs closer than this along the line are one visual run. */
    private const val RUN_GAP_TOLERANCE = 1.5

    fun merge(rects: List<ReaderSelectionRect>, vertical: Boolean): List<ReaderSelectionRect> {
        val usable = rects.filter { it.width > MINIMUM_EXTENT && it.height > MINIMUM_EXTENT }
        if (usable.size <= 1) return usable

        val lines = groupIntoLines(usable, vertical)
            .map { line -> Line(crossStart = line.crossStart, crossEnd = line.crossEnd, runs = line.runs) }
            .sortedBy { it.crossStart }

        return separateLines(lines).flatMap { line ->
            line.runs.map { run -> line.rectFor(run, vertical) }
        }
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

    private fun groupIntoLines(rects: List<ReaderSelectionRect>, vertical: Boolean): List<Line> {
        val crossStart: (ReaderSelectionRect) -> Double = if (vertical) { it -> it.x } else { it -> it.y }
        val crossEnd: (ReaderSelectionRect) -> Double = if (vertical) { it -> it.right } else { it -> it.bottom }
        val runStart: (ReaderSelectionRect) -> Double = if (vertical) { it -> it.y } else { it -> it.x }
        val runEnd: (ReaderSelectionRect) -> Double = if (vertical) { it -> it.bottom } else { it -> it.right }

        val buckets = mutableListOf<MutableList<ReaderSelectionRect>>()
        for (rect in rects.sortedBy(crossStart)) {
            val bucket = buckets.firstOrNull { existing ->
                existing.any { sharesLine(it, rect, crossStart, crossEnd) }
            }
            if (bucket == null) buckets.add(mutableListOf(rect)) else bucket.add(rect)
        }

        return buckets.map { bucket ->
            Line(
                crossStart = bucket.minOf(crossStart),
                crossEnd = bucket.maxOf(crossEnd),
                runs = mergeRuns(bucket.map { Run(runStart(it), runEnd(it)) })
            )
        }
    }

    private fun sharesLine(
        first: ReaderSelectionRect,
        second: ReaderSelectionRect,
        crossStart: (ReaderSelectionRect) -> Double,
        crossEnd: (ReaderSelectionRect) -> Double
    ): Boolean {
        val overlap = min(crossEnd(first), crossEnd(second)) - max(crossStart(first), crossStart(second))
        if (overlap <= 0.0) return false
        val shorter = min(crossEnd(first) - crossStart(first), crossEnd(second) - crossStart(second))
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
