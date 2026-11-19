package com.jerreader.android.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReadiumCSS forces `background-color: transparent !important` on every
 * element of a page read on the sepia, night or a coloured paper, which beat
 * the plain inline fill of the selection marker: only its outline was left.
 * The marker's paint therefore has to be `!important` in every declaration.
 * `ReaderTapTranslationTest` checks the computed colour in a real WebView.
 */
class SelectionMarkerPaintTest {

    @Test
    fun everyDeclarationOutranksTheThemeRules() {
        val css = selectionMarkerPaintCss(fill = "rgba(1,2,3,0.4)", stroke = "rgba(4,5,6,0.9)")
        val declarations = css.split(';').filter { it.isNotBlank() }
        assertEquals(3, declarations.size)
        declarations.forEach { assertTrue(it, it.trim().endsWith("!important")) }
        assertTrue(css, css.contains("background-color:rgba(1,2,3,0.4) !important"))
        assertTrue(css, css.contains("background-image:none !important"))
        assertTrue(css, css.contains("border:0.75px solid rgba(4,5,6,0.9) !important"))
    }

    @Test
    fun paintCanBeEmbeddedInASingleQuotedScriptString() {
        val css = selectionMarkerPaintCss(fill = "rgba(1,2,3,0.4)", stroke = "rgba(4,5,6,0.9)")
        assertTrue(css, css.none { it == '\'' || it == '\n' || it == '\\' })
        assertTrue(css, css.endsWith(";"))
    }
}
