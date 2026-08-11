package com.jerreader.unified.reader.selection

import com.jerreader.unified.reader.geometry.ReaderRect

/**
 * An annotation-free view of whatever the native web view currently has
 * selected.
 *
 * The web view keeps its own editing range and handles; the reader consumes
 * only this. That separation is what stops a Japanese ruby *reading* from
 * becoming the text sent to translation: [baseText] excludes `rt`/`rp`, while
 * [rawText] preserves what the platform reported so a mismatch stays diagnosable.
 *
 * [rects] are in CSS pixels in the page's own coordinate space, exactly as
 * `Range.getClientRects()` returns them — un-merged. Callers run them through
 * [ReaderSelectionRectMerger] before painting.
 */
data class ReaderSelectionSnapshot(
    val rawText: String,
    val baseText: String,
    val before: String? = null,
    val after: String? = null,
    val rects: List<ReaderRect> = emptyList(),
    val writingMode: ReaderWritingMode = ReaderWritingMode.HORIZONTAL,
    /** True when the base text had to be recovered from around a ruby annotation. */
    val recoveredFromRuby: Boolean = false
) {
    val isUsable: Boolean
        get() = baseText.isNotBlank() && rects.any { it.isUsable }

    /** The painted tiles: merged, so no pixel is filled or stroked twice. */
    fun mergedRects(): List<ReaderRect> =
        ReaderSelectionRectMerger.merge(rects, writingMode)
}

/**
 * Arbitrates the release of a native long press against the reader's own
 * tap-to-translate gesture.
 *
 * Both web views can report an ordinary tap immediately after a text selection
 * finishes. Without a short monotonic-time gate that trailing callback clears
 * the selection the reader just made and replaces it with a whole-sentence tap
 * translation — the user drags out three words and gets the entire sentence.
 *
 * This was an iOS-only guard; Android had the same bug and no gate. Keeping the
 * decision in one small state object makes it testable instead of a scattering
 * of timing flags.
 */
class ReaderSelectionGestureGate(
    private val suppressionMillis: Long = DEFAULT_SUPPRESSION_MILLIS
) {
    var suppressTapUntilUptimeMillis: Long? = null
        private set

    fun noteNativeSelection(uptimeMillis: Long) {
        val deadline = uptimeMillis + suppressionMillis.coerceAtLeast(0)
        suppressTapUntilUptimeMillis = maxOf(suppressTapUntilUptimeMillis ?: deadline, deadline)
    }

    fun shouldSuppressTap(uptimeMillis: Long): Boolean {
        val deadline = suppressTapUntilUptimeMillis ?: return false
        if (uptimeMillis > deadline) {
            suppressTapUntilUptimeMillis = null
            return false
        }
        return true
    }

    fun reset() {
        suppressTapUntilUptimeMillis = null
    }

    companion object {
        const val DEFAULT_SUPPRESSION_MILLIS = 1_500L
    }
}
