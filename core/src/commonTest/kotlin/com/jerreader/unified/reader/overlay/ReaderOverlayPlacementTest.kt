package com.jerreader.unified.reader.overlay

import com.jerreader.unified.reader.geometry.ReaderRect
import com.jerreader.unified.reader.geometry.ReaderSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderOverlayPlacementTest {

    private val phone = ReaderSize(390.0, 844.0)
    private val card = ReaderSize(320.0, 140.0)

    private fun request(
        selection: ReaderRect?,
        cardSize: ReaderSize = card,
        block: ReaderOverlayRequest.() -> ReaderOverlayRequest = { this }
    ) = ReaderOverlayRequest(
        selectionFrame = selection,
        viewportSize = phone,
        cardSize = cardSize
    ).block()

    @Test
    fun `the card never covers the selection it translates`() {
        val selection = ReaderRect(40.0, 300.0, 300.0, 60.0)

        val layout = ReaderOverlayPlacement.solve(request(selection))
        val frame = layout.frame(card.height)

        assertNull(frame.intersection(selection), "card overlapped the selected text")
    }

    @Test
    fun `a selection near the top pushes the card below it`() {
        val selection = ReaderRect(40.0, 80.0, 300.0, 60.0)

        val layout = ReaderOverlayPlacement.solve(request(selection))

        assertEquals(ReaderOverlayEdge.BELOW, layout.edge)
        assertTrue(layout.position.y > selection.bottom)
    }

    @Test
    fun `a selection near the bottom pushes the card above it`() {
        val selection = ReaderRect(40.0, 700.0, 300.0, 60.0)

        val layout = ReaderOverlayPlacement.solve(request(selection))

        assertEquals(ReaderOverlayEdge.ABOVE, layout.edge)
        assertTrue(layout.position.y < selection.top)
    }

    @Test
    fun `a tall vertical column is avoided sideways rather than vertically`() {
        // A vertical-rl column: narrow and nearly full height, so there is no
        // usable room above or below but plenty beside it.
        val column = ReaderRect(300.0, 70.0, 40.0, 700.0)

        val layout = ReaderOverlayPlacement.solve(
            request(column, cardSize = ReaderSize(200.0, 160.0)) {
                copy(prefersHorizontalAvoidance = true, minimumHorizontalCardWidth = 144.0)
            }
        )

        assertEquals(ReaderOverlayEdge.LEFT, layout.edge)
        assertNull(layout.frame(160.0).intersection(column))
    }

    @Test
    fun `sideways placement falls back to above-below when no side fits`() {
        // A wide selection leaves less than the minimum card width on either side.
        val selection = ReaderRect(30.0, 300.0, 330.0, 200.0)

        val layout = ReaderOverlayPlacement.solve(
            request(selection) {
                copy(prefersHorizontalAvoidance = true, minimumHorizontalCardWidth = 220.0)
            }
        )

        assertTrue(layout.edge == ReaderOverlayEdge.ABOVE || layout.edge == ReaderOverlayEdge.BELOW)
    }

    @Test
    fun `the top banner preference yields when it would cover the selection`() {
        val selectionUnderBanner = ReaderRect(40.0, 60.0, 300.0, 60.0)

        val layout = ReaderOverlayPlacement.solve(
            request(selectionUnderBanner) { copy(prefersTop = true) }
        )

        assertEquals(ReaderOverlayEdge.BELOW, layout.edge)
    }

    @Test
    fun `the top banner is kept when the selection is far below it`() {
        val selection = ReaderRect(40.0, 600.0, 300.0, 60.0)

        val layout = ReaderOverlayPlacement.solve(
            request(selection) { copy(prefersTop = true) }
        )

        assertEquals(ReaderOverlayEdge.TOP, layout.edge)
    }

    @Test
    fun `the region does not flip when the card grows from loading to result`() {
        val selection = ReaderRect(40.0, 260.0, 300.0, 80.0)

        val loading = ReaderOverlayPlacement.solve(
            request(selection, cardSize = ReaderSize(304.0, 108.0))
        )
        val result = ReaderOverlayPlacement.solve(
            request(selection, cardSize = ReaderSize(304.0, 240.0))
        )

        assertEquals(loading.edge, result.edge)
    }

    @Test
    fun `a selection with no room on either side gets a readable card anyway`() {
        // A paragraph selection running most of a phone screen leaves under
        // `MINIMUM_CARD_HEIGHT` free above and below. Squeezing the card into
        // the larger scrap capped it there with its own text cut off inside —
        // 「有时候，译文界面不展开，会这样很小」.
        val minimum = ReaderTranslationLayoutPolicy.MINIMUM_CARD_HEIGHT
        val preferred = ReaderTranslationLayoutPolicy
            .preferredMaximumCardHeight(phone, prefersExpanded = false)
        val selection = ReaderRect(40.0, 150.0, 300.0, 560.0)
        val layout = ReaderOverlayPlacement.solve(
            request(selection) {
                copy(minimumCardHeight = minimum, preferredMaximumCardHeight = preferred)
            }
        )

        assertEquals(preferred, layout.maximumCardHeight)
        // And it is pushed to the edge of the page rather than left sitting in
        // the middle of the sentence it is translating.
        val frame = layout.frame(preferred)
        assertTrue(
            frame.top < selection.top || frame.bottom > selection.bottom,
            "card was not pushed clear: $frame against $selection"
        )
    }

    @Test
    fun `no selection falls back to a clamped top card`() {
        val layout = ReaderOverlayPlacement.solve(request(null))

        assertEquals(ReaderOverlayEdge.TOP, layout.edge)
        assertTrue(layout.position.y >= 58.0)
    }

    @Test
    fun `a card that overflows the viewport is clamped back inside`() {
        val selection = ReaderRect(360.0, 400.0, 20.0, 40.0)

        val layout = ReaderOverlayPlacement.solve(request(selection))
        val frame = layout.frame(card.height)

        assertTrue(frame.right <= phone.width + 0.001, "card ran off the right edge")
        assertTrue(frame.left >= -0.001, "card ran off the left edge")
    }

    /**
     * 横排: a wrapped sentence low on the page leaves no usable room below it,
     * and a long result is the tallest card the policy ever asks for. The
     * Android reader used to size the card to the room it had, floor it at the
     * compact minimum, and let it overrun onto the following lines.
     */
    @Test
    fun `a long horizontal result never lands on the sentence it translates`() {
        val sentence = ReaderRect(20.0, 560.0, 350.0, 96.0)
        val safeBounds = ReaderRect.fromEdges(16.0, 74.0, 374.0, 688.0)

        val layout = ReaderOverlayPlacement.solve(
            request(sentence, cardSize = ReaderSize(358.0, 300.0)) {
                copy(
                    topInset = 74.0,
                    bottomInset = 156.0,
                    minimumCardHeight = 108.0,
                    preferredMaximumCardHeight = 300.0,
                    prefersHorizontalAvoidance = true,
                    minimumHorizontalCardWidth = 220.0
                )
            }
        )
        val solved = layout.frame(300.0)
        val shift = ReaderOverlayPlacement.correct(solved, sentence, safeBounds, gap = 18.0)
        val placed = solved.offset(shift.dx, shift.dy)

        assertNull(placed.intersection(sentence), "the card covered the translated sentence")
        assertTrue(placed.top >= safeBounds.top - 0.001, "the card ran under the reader chrome")
        assertTrue(placed.bottom <= safeBounds.bottom + 0.001, "the card ran under the page controls")
    }

    // ------------------------------------------------------------- correction

    @Test
    fun `correction is absolute so repeating it converges`() {
        val selection = ReaderRect(40.0, 300.0, 300.0, 100.0)
        val safeBounds = ReaderRect.fromEdges(16.0, 58.0, 374.0, 802.0)
        val measured = ReaderRect(40.0, 340.0, 300.0, 160.0)

        val first = ReaderOverlayPlacement.correct(measured, selection, safeBounds, gap = 18.0)
        val corrected = measured.offset(first.dx, first.dy)
        val second = ReaderOverlayPlacement.correct(corrected, selection, safeBounds, gap = 18.0)

        assertTrue(first.magnitude > 0.0, "an overlapping card should be corrected")
        assertEquals(0.0, second.magnitude, "a corrected card must not be corrected again")
    }

    @Test
    fun `a card already clear of the selection is left alone`() {
        val selection = ReaderRect(40.0, 300.0, 300.0, 100.0)
        val safeBounds = ReaderRect.fromEdges(16.0, 58.0, 374.0, 802.0)
        val measured = ReaderRect(40.0, 430.0, 300.0, 140.0)

        val shift = ReaderOverlayPlacement.correct(measured, selection, safeBounds, gap = 18.0)

        assertEquals(0.0, shift.magnitude)
    }

    @Test
    fun `correction prefers clearing the text over fitting the safe bounds`() {
        // The card is taller than the room below, so every candidate is flawed;
        // the winner must still be the one that stops covering the sentence.
        val selection = ReaderRect(40.0, 120.0, 300.0, 100.0)
        val safeBounds = ReaderRect.fromEdges(16.0, 58.0, 374.0, 400.0)
        val measured = ReaderRect(40.0, 150.0, 300.0, 300.0)

        val shift = ReaderOverlayPlacement.correct(measured, selection, safeBounds, gap = 18.0)
        val corrected = measured.offset(shift.dx, shift.dy)

        val overlap = corrected.intersection(selection.inset(-18.0, -18.0))
        val originalOverlap = measured.intersection(selection.inset(-18.0, -18.0))!!
        val correctedArea = overlap?.let { it.width * it.height } ?: 0.0
        assertTrue(
            correctedArea < originalOverlap.width * originalOverlap.height,
            "correction did not reduce the overlap"
        )
    }
}
