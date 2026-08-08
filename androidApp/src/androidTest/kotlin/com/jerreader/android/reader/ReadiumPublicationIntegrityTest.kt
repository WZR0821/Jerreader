package com.jerreader.android.reader

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.jerreader.android.library.PublicationIntegrity
import com.jerreader.android.test.createSyntheticEpub
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.getOrElse

class ReadiumPublicationIntegrityTest {
    @Test
    fun readiumOpenAndCloseKeepEpubBytesAndModificationTimeUnchanged() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val publicationFile = File(context.cacheDir, "jerreader-immutable-fixture.epub")
        publicationFile.delete()
        createSyntheticEpub(publicationFile)
        publicationFile.setLastModified(1_700_000_000_000)
        val expected = PublicationIntegrity.capture(publicationFile)

        try {
            val readium = ReadiumEnvironment(context)
            val asset = readium.assetRetriever.retrieve(publicationFile).getOrElse { error ->
                throw AssertionError("Readium could not retrieve the EPUB: $error")
            }
            val publication = readium.publicationOpener.open(
                asset,
                allowUserInteraction = false
            ).getOrElse { error ->
                throw AssertionError("Readium could not open the EPUB: $error")
            }

            assertTrue(publication.conformsTo(Publication.Profile.EPUB))
            publication.close()

            assertTrue(
                "Readium changed the immutable EPUB bytes or modification time",
                PublicationIntegrity.isUnchanged(expected)
            )
        } finally {
            publicationFile.delete()
        }
    }

    @Test
    fun readerActivityRendersEpubAndKeepsItsInputImmutable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val publicationFile = File(context.cacheDir, "jerreader-navigator-fixture.epub")
        publicationFile.delete()
        createSyntheticEpub(publicationFile)
        publicationFile.setReadOnly()
        val expected = PublicationIntegrity.capture(publicationFile)
        val intent = Intent(context, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_PUBLICATION_PATH, expected.path)
            .putExtra(ReaderActivity.EXTRA_EXPECTED_SHA256, expected.sha256)
            .putExtra(ReaderActivity.EXTRA_EXPECTED_LAST_MODIFIED, expected.lastModified)

        try {
            ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                var navigatorReady = false
                for (attempt in 0 until 100) {
                    scenario.onActivity { activity ->
                        navigatorReady = activity.supportFragmentManager.fragments.any { fragment ->
                            fragment is EpubNavigatorFragment
                        }
                    }
                    if (navigatorReady) break
                    SystemClock.sleep(100)
                }
                assertTrue("ReaderActivity did not attach Readium's EPUB navigator", navigatorReady)
            }

            assertTrue(
                "ReaderActivity changed the immutable EPUB bytes or modification time",
                PublicationIntegrity.isUnchanged(expected)
            )
        } finally {
            publicationFile.setWritable(true)
            publicationFile.delete()
        }
    }
}
