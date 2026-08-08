package com.jerreader.android.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.jerreader.android.JerreaderApplication
import com.jerreader.android.library.PublicationIntegrity
import com.jerreader.android.test.createSyntheticEpub
import com.jerreader.shared.library.ReaderAppearance
import com.jerreader.shared.library.ReaderThemeOption
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferencesSerializer
import org.readium.r2.navigator.preferences.Theme

class ReaderMilestoneTwoTest {
    @Test
    fun tableOfContentsAppearanceAndFullLocatorPersistAcrossReaderSessions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context.applicationContext as JerreaderApplication
        val graph = application.graph
        val source = File(context.cacheDir, "jerreader-m2-${UUID.randomUUID()}.epub")
        createSyntheticEpub(source)
        source.setLastModified(1_700_300_000_000)
        val sourceSnapshot = PublicationIntegrity.capture(source)
        val imported = graph.importService.importEpub(Uri.fromFile(source))
        val book = imported.book
        val storedFile = graph.publicationStore.resolvePublication(book.publicationFileName)
        val storedSnapshot = PublicationIntegrity.capture(storedFile)
        val intent = Intent(context, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)

        try {
            ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                waitForNavigator(scenario)

                var navigated = false
                for (attempt in 0 until 100) {
                    scenario.onActivity { activity ->
                        navigated = activity.navigateToChapter("toc-1")
                    }
                    if (navigated) break
                    SystemClock.sleep(100)
                }
                assertTrue("Readium did not accept the second TOC link", navigated)

                scenario.onActivity { activity ->
                    activity.applyAppearance(
                        ReaderAppearance(
                            fontScale = 1.3,
                            theme = ReaderThemeOption.DARK
                        )
                    )
                }

                val saved = awaitSavedState(book.id)
                val href = JSONObject(checkNotNull(saved.locatorJson)).getString("href")
                assertTrue("Full Locator did not point to chapter two: $href", href.contains("chapter-two"))
                val preferences = decodeStoredReaderPreferences(
                    checkNotNull(saved.preferencesJson),
                    ReaderAppearance()
                ).epub
                assertEquals(1.3, preferences.fontSize ?: 0.0, 0.001)
                assertEquals(Theme.DARK, preferences.theme)
            }

            ActivityScenario.launch<ReaderActivity>(intent).use { reopened ->
                waitForNavigator(reopened)
                var restoredHref = ""
                for (attempt in 0 until 100) {
                    reopened.onActivity { activity ->
                        restoredHref = activity.supportFragmentManager.fragments
                            .filterIsInstance<EpubNavigatorFragment>()
                            .firstOrNull()
                            ?.currentLocator
                            ?.value
                            ?.href
                            ?.toString()
                            .orEmpty()
                    }
                    if (restoredHref.contains("chapter-two")) break
                    SystemClock.sleep(100)
                }
                assertTrue(
                    "Reader did not restore the saved chapter-two Locator: $restoredHref",
                    restoredHref.contains("chapter-two")
                )
            }

            assertTrue(PublicationIntegrity.isUnchanged(sourceSnapshot))
            assertTrue(PublicationIntegrity.isUnchanged(storedSnapshot))
        } finally {
            graph.bookService.delete(book.id)
            source.delete()
        }
    }

    @Test
    fun legacyReadiumPreferencesRemainReadable() {
        val legacyJson = EpubPreferencesSerializer().serialize(
            ReaderAppearance(
                fontScale = 1.25,
                theme = ReaderThemeOption.DARK
            ).toEpubPreferences()
        )

        val decoded = decodeStoredReaderPreferences(legacyJson, ReaderAppearance())

        assertEquals(1.25, decoded.epub.fontSize ?: 0.0, 0.001)
        assertEquals(Theme.DARK, decoded.epub.theme)
    }

    private fun waitForNavigator(scenario: ActivityScenario<ReaderActivity>) {
        var navigatorReady = false
        repeat(100) {
            scenario.onActivity { activity ->
                navigatorReady = activity.supportFragmentManager.fragments.any { fragment ->
                    fragment is EpubNavigatorFragment && fragment.view != null
                }
            }
            if (navigatorReady) return
            SystemClock.sleep(100)
        }
        assertTrue("ReaderActivity did not attach Readium's EPUB navigator", navigatorReady)
    }

    private suspend fun awaitSavedState(bookId: String): com.jerreader.shared.library.LibraryBook {
        val application = ApplicationProvider.getApplicationContext<JerreaderApplication>()
        repeat(100) {
            val saved = application.graph.repository.book(bookId)
            if (
                saved?.locatorJson?.contains("chapter-two") == true &&
                saved.preferencesJson != null
            ) {
                return saved
            }
            delay(100)
        }
        error("Reader state was not persisted within 10 seconds")
    }
}
