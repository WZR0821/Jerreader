package com.jerreader.android.reader

import org.readium.r2.shared.publication.Locator

/**
 * Readium 3.4.0 fixed the PDFium navigator reporting every page position one
 * page too high (visible page 1 came back as position 2). Every PDF locator
 * Jerreader persists — reading progress, bookmarks, annotations, learning
 * records — is the navigator's own `currentLocator`, so all of them written by
 * Jerreader 1.4.1 and earlier carry the old, shifted position.
 *
 * Rather than rewriting the database once (which an old backup restored later
 * would undo), every PDF locator the reader observes from now on is marked, and
 * an unmarked PDF locator is corrected whenever it is read back. A record keeps
 * its stored JSON until the reader writes it again; reading it twice is
 * harmless because the corrected copy carries the marker.
 */
object PdfLocatorFormat {
    const val MARKER_KEY = "jerreaderPdfPositions"
    const val CURRENT = "readium-3.4"

    fun isCurrent(locator: Locator): Boolean =
        locator.locations.otherLocations[MARKER_KEY] == CURRENT

    fun mark(locator: Locator): Locator =
        if (isCurrent(locator)) locator
        else locator.copy(
            locations = locator.locations.copy(
                otherLocations = locator.locations.otherLocations + (MARKER_KEY to CURRENT)
            )
        )

    /**
     * Same correction as Readium's `Publication.migrateLegacyPdfiumLocator`,
     * using the reader's already loaded position list so call sites need not
     * suspend: the old position `p` meant page `p - 1`, whose locator is
     * `positions[p - 2]`. Position 1 (only ever the initial page) and
     * out-of-range positions are kept as they are.
     */
    fun normalize(locator: Locator, positions: List<Locator>): Locator {
        if (isCurrent(locator)) return locator
        val index = legacyPositionIndex(locator.locations.position, positions.size)
            ?: return mark(locator)
        val corrected = positions[index]
        return mark(
            locator.copy(
                locations = corrected.locations.copy(
                    otherLocations = locator.locations.otherLocations + corrected.locations.otherLocations
                )
            )
        )
    }

    /** Index into `positions()` of the page an old, shifted [storedPosition] meant. */
    fun legacyPositionIndex(storedPosition: Int?, positionCount: Int): Int? {
        val stored = storedPosition ?: return null
        val index = stored - 2
        return index.takeIf { it in 0 until positionCount }
    }
}
