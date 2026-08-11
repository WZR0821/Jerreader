package com.jerreader.unified.reader.selection

import com.jerreader.unified.reader.geometry.ReaderRect
import com.jerreader.unified.reader.json.ReaderJson
import com.jerreader.unified.reader.json.ReaderJsonValue

/**
 * Turns a raw web-view result into the reader's own selection model.
 *
 * All the tolerance decisions live here rather than at each call site: a
 * rectangle missing a dimension is dropped, a snapshot with no usable
 * rectangles or no base text is rejected outright, and the reader's configured
 * writing mode overrides what the page reported.
 *
 * That last rule is not a preference — some WebView builds report
 * `-epub-writing-mode: vertical-rl` as `horizontal-tb` through
 * `getComputedStyle`, and trusting the page there rotates the whole merge axis
 * and produces one tall tile per column instead of one per line.
 */
object ReaderSelectionDecoder {

    /** A rectangle needs both extents above this to be a glyph rather than noise. */
    private const val MINIMUM_EXTENT = 0.5

    fun decodeSnapshot(
        raw: String?,
        forcedWritingMode: ReaderWritingMode? = null
    ): ReaderSelectionSnapshot? {
        val json = ReaderJson.parse(raw)?.objectOrNull() ?: return null
        val baseText = json.string("normalizedText") ?: return null
        if (baseText.isBlank()) return null

        val rects = decodeRects(json.array("rects"))
        if (rects.isEmpty()) return null

        val pageIsVertical = json.boolean("vertical") == true
        return ReaderSelectionSnapshot(
            rawText = json.string("rawText").orEmpty(),
            baseText = baseText,
            before = json.string("before"),
            after = json.string("after"),
            rects = rects,
            writingMode = forcedWritingMode
                ?: ReaderWritingMode.of(pageIsVertical),
            recoveredFromRuby = json.boolean("recoveredFromRuby") == true
        )
    }

    /** The `selectRangeScript` result: the tiles it selected, or null on failure. */
    fun decodeSelectionResult(
        raw: String?,
        forcedWritingMode: ReaderWritingMode? = null
    ): ReaderSelectionResult? {
        val json = ReaderJson.parse(raw)?.objectOrNull() ?: return null
        if (json.boolean("ok") != true) return null
        val mode = forcedWritingMode ?: ReaderWritingMode.of(json.boolean("vertical") == true)
        return ReaderSelectionResult(
            rects = decodeRects(json.array("rects")),
            writingMode = mode,
            scrollX = json.double("scrollX") ?: 0.0,
            scrollY = json.double("scrollY") ?: 0.0
        )
    }

    /** The `blockTextScript` result: the tapped block's ruby-free text. */
    fun decodeBlockText(raw: String?): ReaderBlockText? {
        val json = ReaderJson.parse(raw)?.objectOrNull() ?: return null
        val text = json.string("text") ?: return null
        if (text.isEmpty()) return null
        val offset = (json.int("offset") ?: 0).coerceIn(0, (text.length - 1).coerceAtLeast(0))
        return ReaderBlockText(
            text = text,
            caretOffset = offset,
            writingMode = ReaderWritingMode.of(json.boolean("vertical") == true)
        )
    }

    private fun decodeRects(items: List<ReaderJsonValue>): List<ReaderRect> =
        items.mapNotNull { item ->
            val x = item.double("x") ?: return@mapNotNull null
            val y = item.double("y") ?: return@mapNotNull null
            val width = item.double("w") ?: item.double("width") ?: return@mapNotNull null
            val height = item.double("h") ?: item.double("height") ?: return@mapNotNull null
            val rect = ReaderRect(x, y, width, height).standardized()
            rect.takeIf {
                it.isFinite && it.width > MINIMUM_EXTENT && it.height > MINIMUM_EXTENT
            }
        }
}

/**
 * The tiles for a quick-tap selection, in CSS client pixels, together with the
 * scroll offsets they were measured under.
 *
 * The offsets travel with the rectangles because painting happens in a later
 * evaluation: converting client to document coordinates with a scroll position
 * read at paint time can put the highlight beside the text rather than on it.
 */
data class ReaderSelectionResult(
    val rects: List<ReaderRect>,
    val writingMode: ReaderWritingMode,
    val scrollX: Double = 0.0,
    val scrollY: Double = 0.0
) {
    fun mergedRects(): List<ReaderRect> = ReaderSelectionRectMerger.merge(rects, writingMode)
}

/** The tapped block's text and where in it the tap landed. */
data class ReaderBlockText(
    val text: String,
    val caretOffset: Int,
    val writingMode: ReaderWritingMode
)
