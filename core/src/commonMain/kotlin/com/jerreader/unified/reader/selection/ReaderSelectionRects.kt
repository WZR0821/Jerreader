package com.jerreader.unified.reader.selection

import com.jerreader.unified.reader.geometry.ReaderRect
import kotlin.math.max
import kotlin.math.min

/** Which axis lines advance along. Vertical is Japanese `vertical-rl` and kin. */
enum class ReaderWritingMode {
    HORIZONTAL,
    VERTICAL;

    val isVertical: Boolean get() = this == VERTICAL

    companion object {
        fun of(vertical: Boolean): ReaderWritingMode = if (vertical) VERTICAL else HORIZONTAL
    }
}

/**
 * Turns the raw `Range.getClientRects()` output into exactly one rectangle per
 * contiguous run per line, so every painted pixel is covered once.
 *
 * This is the merged form of what used to be two separate implementations. The
 * Android reader had the line-aware merger (writing-mode axis flip, run
 * merging, ruby line separation); the iOS reader had the containment pass that
 * drops a ruby annotation box which completely covers its own kanji band. Each
 * fixed a real artefact the other still showed, so the unified merger runs
 * both: containment first, then line grouping.
 *
 * Without this, painting the raw boxes stacks the translucent fill and strokes
 * the outline several times, which is the doubled, patchy highlight both apps
 * showed on ruby text and on fragment boundaries within a line.
 */
object ReaderSelectionRectMerger {

    /** Fragments this thin are layout artefacts rather than glyphs. */
    private const val MINIMUM_EXTENT = 0.5

    /** Two fragments belong to one line once they share this much of the shorter cross extent. */
    private const val SAME_LINE_OVERLAP_RATIO = 0.55

    /** Runs closer than this along the line are one visual run. */
    private const val RUN_GAP_TOLERANCE = 1.5

    /** Slack allowed when testing whether one box swallows another. */
    private const val CONTAINMENT_SLACK = 0.5

    /** How far apart two bands may sit and still count as annotation and text. */
    private const val ADJACENT_BAND_SLACK = 2.0

    /**
     * How much thinner than its text a band must be to be read as an annotation.
     *
     * A ruby reading is set at roughly half the size of the characters it
     * annotates. Two consecutive lines of body text are the same extent as each
     * other, so this is what stops the line below from bridging a gap in the
     * line above when the leading is tight enough for their bands to touch.
     */
    private const val ANNOTATION_BAND_RATIO = 0.9

    /** Slack allowed when testing whether an annotation run covers a gap. */
    private const val BRIDGE_SLACK = 1.0

    fun merge(rects: List<ReaderRect>, mode: ReaderWritingMode): List<ReaderRect> {
        val usable = rects
            .map { it.standardized() }
            .filter { it.width > MINIMUM_EXTENT && it.height > MINIMUM_EXTENT }
        if (usable.size <= 1) return usable

        val distinct = dropContained(usable)
        if (distinct.size <= 1) return distinct

        val vertical = mode.isVertical
        val lines = groupIntoLines(distinct, vertical).sortedBy { it.crossStart }
        return separateLines(bridgeAnnotationGaps(lines)).flatMap { line ->
            line.runs.map { run -> line.rectFor(run, vertical) }
        }
    }

    fun merge(rects: List<ReaderRect>, vertical: Boolean): List<ReaderRect> =
        merge(rects, ReaderWritingMode.of(vertical))

    /**
     * A ruby annotation reserves a box that can completely cover the kanji band
     * beneath it. Painting both stacks the fill twice in exactly the same place,
     * so the swallowed box is dropped before any line grouping happens.
     *
     * "Swallowed" has to be decided by a *strict total order*, which is the part
     * the original rule got wrong. It kept a box unless some other box contained
     * it and was wider **or** taller **or** earlier in the list. Two boxes that
     * differ by less than the containment slack each contain the other, and that
     * disjunction then fires in both directions — the larger one loses on area,
     * the smaller one loses on index, and *both* are dropped. Sub-pixel-different
     * overlapping fragments are exactly what ruby geometry produces, so a whole
     * highlighted line could disappear.
     *
     * Ordering by area, with the earlier index winning ties, is antisymmetric:
     * the largest box (earliest among equals) can never be dropped, so at least
     * one survivor is guaranteed.
     */
    private fun dropContained(rects: List<ReaderRect>): List<ReaderRect> =
        rects.filterIndexed { index, candidate ->
            val candidateArea = candidate.width * candidate.height
            rects.withIndex().none { (otherIndex, other) ->
                if (otherIndex == index) return@none false
                if (!other.inset(-CONTAINMENT_SLACK, -CONTAINMENT_SLACK).contains(candidate)) {
                    return@none false
                }
                val otherArea = other.width * other.height
                otherArea > candidateArea ||
                    (otherArea == candidateArea && otherIndex < index)
            }
        }

    private fun ReaderRect.contains(other: ReaderRect): Boolean =
        other.left >= left && other.right <= right &&
            other.top >= top && other.bottom <= bottom

    private data class Run(val start: Double, val end: Double)

    private data class Line(
        val crossStart: Double,
        val crossEnd: Double,
        val runs: List<Run>
    ) {
        fun rectFor(run: Run, vertical: Boolean): ReaderRect = if (vertical) {
            ReaderRect(
                x = crossStart,
                y = run.start,
                width = crossEnd - crossStart,
                height = run.end - run.start
            )
        } else {
            ReaderRect(
                x = run.start,
                y = crossStart,
                width = run.end - run.start,
                height = crossEnd - crossStart
            )
        }
    }

    private fun groupIntoLines(rects: List<ReaderRect>, vertical: Boolean): List<Line> {
        val crossStart: (ReaderRect) -> Double = if (vertical) { it -> it.left } else { it -> it.top }
        val crossEnd: (ReaderRect) -> Double = if (vertical) { it -> it.right } else { it -> it.bottom }
        val runStart: (ReaderRect) -> Double = if (vertical) { it -> it.top } else { it -> it.left }
        val runEnd: (ReaderRect) -> Double = if (vertical) { it -> it.bottom } else { it -> it.right }

        val buckets = mutableListOf<MutableList<ReaderRect>>()
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
        first: ReaderRect,
        second: ReaderRect,
        crossStart: (ReaderRect) -> Double,
        crossEnd: (ReaderRect) -> Double
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
     * Closes a gap on one line that the annotation band above it spans.
     *
     * A ruby reading is usually wider than the character it sits over — しょう
     * over 翔 — so the `ruby` element reserves the reading's advance and the
     * base glyph is centred inside it. Two neighbouring ruby words therefore
     * report base boxes several pixels apart even though the reader selected
     * them as one unbroken run, and the painted highlight breaks open between
     * them. That gap is a typesetting artefact, not a hole in the selection.
     *
     * It is only bridged when a band that is unmistakably an annotation — one
     * touching this line and distinctly thinner than it — has a run covering the
     * gap, which is the annotation whose advance opened it. A wide tolerance on
     * [RUN_GAP_TOLERANCE] would close these too, but it would equally close
     * every real gap on the line, so this asks the annotation itself.
     */
    private fun bridgeAnnotationGaps(lines: List<Line>): List<Line> {
        if (lines.size <= 1) return lines
        return lines.mapIndexed { index, line ->
            val spans = lines.filterIndexed { other, candidate ->
                other != index && candidate.annotates(line)
            }.flatMap { it.runs }
            if (spans.isEmpty()) line else line.copy(runs = bridge(line.runs, spans))
        }
    }

    /** Whether this band reads as an annotation of [text]: touching, and thinner. */
    private fun Line.annotates(text: Line): Boolean {
        val separation = max(crossStart, text.crossStart) - min(crossEnd, text.crossEnd)
        if (separation > ADJACENT_BAND_SLACK) return false
        val extent = crossEnd - crossStart
        val textExtent = text.crossEnd - text.crossStart
        return textExtent > 0.0 && extent < textExtent * ANNOTATION_BAND_RATIO
    }

    private fun bridge(runs: List<Run>, spans: List<Run>): List<Run> {
        val sorted = runs.sortedBy { it.start }
        val merged = mutableListOf<Run>()
        for (run in sorted) {
            val last = merged.lastOrNull()
            val spanned = last != null && spans.any { span ->
                span.start <= last.end + BRIDGE_SLACK && span.end >= run.start - BRIDGE_SLACK
            }
            if (last != null && spanned) {
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
