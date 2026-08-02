package com.jerreader.android.library

import android.content.Context
import android.net.Uri
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jerreader.android.data.JerreaderDatabase
import com.jerreader.android.data.RoomLibraryRepository
import com.jerreader.android.reader.ReadiumEnvironment
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentImportTest {
    @Test
    fun txtDocxAndPdfImportLocallyAndKeepSourcesImmutable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, JerreaderDatabase::class.java).build()
        val repository = RoomLibraryRepository(database.bookDao())
        val root = File(context.cacheDir, "jerreader-doc-${UUID.randomUUID()}").apply { mkdirs() }
        val store = ImmutablePublicationStore(context, root)
        val importer = LibraryImportService(store, ReadiumEnvironment(context), repository)
        val txt = File(root, "日语笔记.txt").apply {
            writeText("第一段。\n\nSecond paragraph.")
            setLastModified(1_700_600_000_000)
        }
        val docx = File(root, "论文摘要.docx").also(::createDocx)
        docx.setLastModified(1_700_600_100_000)
        val pdf = File(root, "双栏论文.pdf").also(::createPdf)
        pdf.setLastModified(1_700_600_200_000)
        val txtSnapshot = PublicationIntegrity.capture(txt)
        val docxSnapshot = PublicationIntegrity.capture(docx)
        val pdfSnapshot = PublicationIntegrity.capture(pdf)

        try {
            val txtBook = importer.importPublication(Uri.fromFile(txt)).book
            val docxBook = importer.importPublication(Uri.fromFile(docx)).book
            val pdfBook = importer.importPublication(Uri.fromFile(pdf)).book
            assertEquals("txt", txtBook.sourceFormat)
            assertEquals("docx", docxBook.sourceFormat)
            assertEquals("pdf", pdfBook.sourceFormat)
            assertTrue(txtBook.publicationFileName.endsWith(".epub"))
            assertTrue(docxBook.publicationFileName.endsWith(".epub"))
            assertTrue(pdfBook.publicationFileName.endsWith(".pdf"))
            assertTrue(store.resolvePublication(txtBook.publicationFileName).isFile)
            assertTrue(store.resolvePublication(docxBook.publicationFileName).isFile)
            assertTrue(store.resolvePublication(pdfBook.publicationFileName).isFile)
            assertTrue(PublicationIntegrity.isUnchanged(txtSnapshot))
            assertTrue(PublicationIntegrity.isUnchanged(docxSnapshot))
            assertTrue(PublicationIntegrity.isUnchanged(pdfSnapshot))
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    private fun createDocx(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body>
                        <w:p><w:r><w:t>这是 DOCX 转换测试。</w:t></w:r></w:p>
                        <w:p><w:r><w:t>Jerreader keeps the source unchanged.</w:t></w:r></w:p>
                      </w:body>
                    </w:document>
                """.trimIndent().encodeToByteArray()
            )
            zip.closeEntry()
        }
    }

    private fun createPdf(file: File) {
        val document = PdfDocument()
        try {
            val page = document.startPage(
                PdfDocument.PageInfo.Builder(595, 842, 1).create()
            )
            val paint = Paint().apply { textSize = 18f }
            page.canvas.drawText("Jerreader PDF immutable fixture", 48f, 72f, paint)
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }
}
