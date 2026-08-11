package com.jerreader.android.library

import com.jerreader.unified.domain.BookFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

class DocumentToEpubConverter {
    fun convert(source: StagedPublication, format: BookFormat): ByteArray {
        val paragraphs = when (format) {
            BookFormat.TXT -> textParagraphs(source.file.readBytes())
            BookFormat.DOCX -> docxParagraphs(source.file)
            else -> error("只有 DOCX 和 TXT 需要转换。")
        }
        if (paragraphs.isEmpty()) throw LibraryImportException.EmptyDocument
        val title = source.displayName.substringBeforeLast('.').trim().ifEmpty { "未命名文档" }
        return makeEpub(title, paragraphs)
    }

    internal fun textParagraphs(bytes: ByteArray): List<String> {
        val decoded = when {
            bytes.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) ->
                bytes.copyOfRange(3, bytes.size).toString(StandardCharsets.UTF_8)
            bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) ->
                bytes.copyOfRange(2, bytes.size).toString(StandardCharsets.UTF_16LE)
            bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) ->
                bytes.copyOfRange(2, bytes.size).toString(StandardCharsets.UTF_16BE)
            else -> decodeStrictUtf8(bytes) ?: bytes.toString(charset("GB18030"))
        }
        return decoded
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split(Regex("\\n\\s*\\n"))
            .map { it.lines().joinToString("\n") { line -> line.trimEnd() }.trim() }
            .filter(String::isNotBlank)
    }

    internal fun docxParagraphs(file: File): List<String> {
        val documentXml = ZipFile(file).use { archive ->
            val entry = archive.getEntry("word/document.xml")
                ?: throw LibraryImportException.InvalidDocument
            archive.getInputStream(entry).use { it.readBytes() }
        }
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val handler = object : DefaultHandler() {
            private var inText = false

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (localName ?: qName?.substringAfter(':')) {
                    "t" -> inText = true
                    "tab" -> current.append('\t')
                    "br", "cr" -> current.append('\n')
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText) current.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (localName ?: qName?.substringAfter(':')) {
                    "t" -> inText = false
                    "p" -> {
                        current.toString().trim().takeIf(String::isNotBlank)?.let(paragraphs::add)
                        current.clear()
                    }
                }
            }
        }
        try {
            factory.newSAXParser().xmlReader.apply {
                contentHandler = handler
                parse(InputSource(ByteArrayInputStream(documentXml)))
            }
        } catch (_: Exception) {
            throw LibraryImportException.InvalidDocument
        }
        return paragraphs
    }

    private fun makeEpub(title: String, paragraphs: List<String>): ByteArray {
        val identifier = "urn:uuid:${java.util.UUID.nameUUIDFromBytes(paragraphs.joinToString("\n").encodeToByteArray())}"
        val chapterBody = paragraphs.joinToString("\n") { paragraph ->
            "<p>${xmlEscape(paragraph).replace("\n", "<br/>")}</p>"
        }
        val files = linkedMapOf(
            "META-INF/container.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""".trimIndent(),
            "OEBPS/package.opf" to """<?xml version="1.0" encoding="UTF-8"?>
                <package version="3.0" unique-identifier="book-id" xmlns="http://www.idpf.org/2007/opf">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="book-id">$identifier</dc:identifier>
                    <dc:title>${xmlEscape(title)}</dc:title>
                    <dc:language>und</dc:language>
                    <meta property="dcterms:modified">2000-01-01T00:00:00Z</meta>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                    <item id="style" href="styles.css" media-type="text/css"/>
                  </manifest>
                  <spine><itemref idref="chapter"/></spine>
                </package>""".trimIndent(),
            "OEBPS/nav.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <head><title>${xmlEscape(title)}</title></head>
                  <body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">${xmlEscape(title)}</a></li></ol></nav></body>
                </html>""".trimIndent(),
            "OEBPS/chapter.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>${xmlEscape(title)}</title><link rel="stylesheet" type="text/css" href="styles.css"/></head>
                  <body><h1>${xmlEscape(title)}</h1>$chapterBody</body>
                </html>""".trimIndent(),
            "OEBPS/styles.css" to "body{line-height:1.6;margin:5%;}p{margin:0 0 1em;}"
        )
        return ByteArrayOutputStream().use { buffer ->
            ZipOutputStream(buffer).use { zip ->
                val mime = "application/epub+zip".encodeToByteArray()
                val crc = CRC32().apply { update(mime) }
                zip.putNextEntry(ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mime.size.toLong()
                    compressedSize = mime.size.toLong()
                    this.crc = crc.value
                    time = 0
                })
                zip.write(mime)
                zip.closeEntry()
                files.forEach { (path, content) ->
                    zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                    zip.write(content.encodeToByteArray())
                    zip.closeEntry()
                }
            }
            buffer.toByteArray()
        }
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

    private fun xmlEscape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character
                }
            )
        }
    }
}
