package com.jerreader.shared.parity

import com.jerreader.shared.library.ReaderRubyAnnotationRects
import com.jerreader.shared.library.ReaderSelectionRect
import com.jerreader.shared.library.ReaderSelectionRectMerger
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ruby-aware selection tiles: the same cases iOS runs in JavaScriptCore against
 * ReaderSelectionTileScript (host-test suite `selection-tiles`), read from
 * shared/parity/selection_tiles.json.
 */
class SelectionTilesParityTest {

    @Test
    fun selectionTilesMatchTheSharedCases() {
        val cases = fixture("selection_tiles.json").getJSONArray("cases")
        assertTrue(cases.length() >= 10, "selection_tiles.json should carry at least 10 cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val vertical = case.getBoolean("vertical")
            val annotations = case.getJSONArray("annotations").let { array ->
                List(array.length()) { i ->
                    val annotation = array.getJSONObject(i)
                    ReaderRubyAnnotationRects(
                        baseIndices = annotation.getJSONArray("base").let { base -> List(base.length()) { base.getInt(it) } },
                        rects = annotation.getJSONArray("rects").rects(),
                        baseFullySelected = annotation.optBoolean("fullySelected", true)
                    )
                }
            }
            val actual = ReaderSelectionRectMerger.merge(case.getJSONArray("base").rects(), vertical, annotations)
            val expected = case.getJSONArray("tiles").rects()
            assertTrue(matches(actual, expected), "$name: expected $expected, got $actual")

            // The tiles survive a second plain merge unchanged (the reader merges
            // the page's tiles again to anchor the card).
            val again = ReaderSelectionRectMerger.merge(actual, vertical)
            assertTrue(matches(again, actual), "$name: re-merging changed the tiles to $again")
        }
    }

    private fun matches(actual: List<ReaderSelectionRect>, expected: List<ReaderSelectionRect>): Boolean =
        actual.size == expected.size && actual.zip(expected).all { (got, want) ->
            abs(got.x - want.x) <= 0.001 && abs(got.y - want.y) <= 0.001 &&
                abs(got.width - want.width) <= 0.001 && abs(got.height - want.height) <= 0.001
        }

    private fun JSONArray.rects(): List<ReaderSelectionRect> = List(length()) { index ->
        val r = getJSONArray(index)
        ReaderSelectionRect(x = r.getDouble(0), y = r.getDouble(1), width = r.getDouble(2), height = r.getDouble(3))
    }

    private fun fixture(name: String): JSONObject {
        // Gradle runs host tests from the module directory; walk up so the test
        // also finds the file when run from the repository root.
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, "parity/$name"), File(dir, "shared/parity/$name"))) {
                if (candidate.isFile) return JSONObject(candidate.readText())
            }
            dir = dir.parentFile
        }
        error("parity fixture $name not found above ${File("").absolutePath}")
    }
}
