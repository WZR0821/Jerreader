package com.jerreader.android.reader

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.jerreader.android.library.PublicationIntegrity
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
        createMinimalEpub(publicationFile)
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
        createMinimalEpub(publicationFile)
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

    private fun createMinimalEpub(file: File) {
        ZipOutputStream(file.outputStream().buffered()).use { archive ->
            val mimetype = "application/epub+zip".encodeToByteArray()
            val checksum = CRC32().apply { update(mimetype) }
            archive.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimetype.size.toLong()
                    compressedSize = mimetype.size.toLong()
                    crc = checksum.value
                }
            )
            archive.write(mimetype)
            archive.closeEntry()

            archive.addTextEntry(
                "META-INF/container.xml",
                """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                """.trimIndent()
            )
            archive.addTextEntry(
                "EPUB/package.opf",
                """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package version="3.0" unique-identifier="book-id" xmlns="http://www.idpf.org/2007/opf">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:identifier id="book-id">jerreader-test</dc:identifier>
                        <dc:title>Jerreader Test</dc:title>
                        <dc:language>zh-CN</dc:language>
                        <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
                      </metadata>
                      <manifest>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine><itemref idref="chapter"/></spine>
                    </package>
                """.trimIndent()
            )
            archive.addTextEntry(
                "EPUB/nav.xhtml",
                """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="zh-CN">
                      <head><title>目录</title></head>
                      <body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">测试章节</a></li></ol></nav></body>
                    </html>
                """.trimIndent()
            )
            archive.addTextEntry(
                "EPUB/chapter.xhtml",
                """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml" lang="zh-CN">
                      <head><title>测试章节</title></head>
                      <body><h1>Jerreader</h1><p>这是不含版权内容的合成 EPUB 测试夹具。</p></body>
                    </html>
                """.trimIndent()
            )
        }
    }

    private fun ZipOutputStream.addTextEntry(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.encodeToByteArray())
        closeEntry()
    }
}
