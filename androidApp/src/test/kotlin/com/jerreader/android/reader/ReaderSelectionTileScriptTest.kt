package com.jerreader.android.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page-side tile merge must stay the exact script iOS tests in
 * JavaScriptCore against shared/parity/selection_tiles.json (host-test suite
 * `selection-tiles`); the Kotlin twin, ReaderSelectionRectMerger, runs the
 * same cases in SelectionTilesParityTest.
 */
class ReaderSelectionTileScriptTest {

    @Test
    fun tileScriptIsTheSameSourceAsIos() {
        assertEquals(iosScript("source"), ReaderSelectionTileScript.SOURCE)
    }

    @Test
    fun scriptsCanBeSplicedIntoKotlinTemplates() {
        for (
            script in listOf(
                ReaderSelectionTileScript.SOURCE,
                ReaderSelectionTileScript.DOM_SOURCE
            )
        ) {
            assertFalse(script.contains('$'))
            assertFalse(script.contains("\"\"\""))
        }
        assertTrue(ReaderSelectionTileScript.ALL.contains("function jerreaderSelectionTiles("))
        assertTrue(ReaderSelectionTileScript.ALL.contains("function jerreaderSelectionGeometry("))
        assertTrue(ReaderSelectionTileScript.ALL.contains("function jerreaderSelectionIsVertical("))
    }

    /** A `static let <name> = #\"\"\"` script in ReaderSelectionTileScript.swift, as Swift reads it. */
    private fun iosScript(name: String): String {
        val swift = repositoryFile("Jerreader/Features/Reader/ReaderSelectionTileScript.swift").readText()
        val opening = "static let $name = #\"\"\"\n"
        val closing = "\n    \"\"\"#"
        val start = swift.indexOf(opening)
        assertTrue("iOS script $name not found", start >= 0)
        val body = swift.substring(start + opening.length, swift.indexOf(closing, start))
        // A Swift multi-line literal drops the closing delimiter's indentation
        // (four spaces) from every line.
        return body.lines().joinToString("\n") { line ->
            if (line.isBlank()) "" else line.removePrefix("    ")
        }
    }

    private fun repositoryFile(path: String): File {
        // Gradle runs unit tests from the module directory; walk up to the repository.
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$path not found above ${File("").absolutePath}")
    }
}
