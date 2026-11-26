package com.jerreader.android.test

import android.util.Base64
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phrases a WebView's ICU splits apart (くだ|さい, おい|し|そうですね,
 * すみません|で|した). iOS's ReaderServicesTests has the same paragraph, and
 * expects the same word for every glyph.
 */
const val SHORT_TAP_PHRASES = "ご注意ください　おいしそうですね　悲しそうだ　帰ったらしい　本を読んじゃった　彼は振り返った　" +
    "すみませんでした　よさそうだ　行くことにした　もしそうなら行く　雨が降り出した　そうですね"

fun createSyntheticEpub(
    file: File,
    identifier: String = "jerreader-${System.nanoTime()}",
    // Paragraphs added after chapter one's fixed content, for tests that need
    // a chapter longer than one screen.
    fillerParagraphs: Int = 0,
    // A Japanese sentence with two furigana readings, and one more set as a
    // div paragraph as some books set them, for the ruby selection tests. Off
    // by default so the other tests keep their exact layout.
    rubySentence: Boolean = false,
    // A paragraph of phrases ICU splits apart, for the short-tap test.
    shortTapPhrases: Boolean = false,
    // A sentence whose ruby gives each kanji its own reading, the first one
    // longer than its kanji (玄 くろう, 人 と), so the line has blank room
    // between the two kanji, for the long-press test. Off by default too.
    rubyRoomSentence: Boolean = false
) {
    val filler = (1..fillerParagraphs).joinToString("") { "<p>这是让第一章超过一屏的填充段落，第 $it 段。</p>" }
    val ruby = if (!rubySentence) "" else
        "<p id=\"ruby-sentence\">彼は<ruby id=\"ruby-door\">扉<rt id=\"ruby-door-reading\">とびら</rt></ruby>を" +
            "<ruby id=\"ruby-open\">開<rt id=\"ruby-open-reading\">あ</rt></ruby>けた。</p>" +
            "<div id=\"ruby-div-sentence\">外は<ruby id=\"ruby-rain\">雨<rt>あめ</rt></ruby>だった。</div>"
    val phrases = if (!shortTapPhrases) "" else "<p id=\"tap-phrases\">$SHORT_TAP_PHRASES</p>"
    val rubyRoom = if (!rubyRoomSentence) "" else
        "<p id=\"ruby-room-sentence\">彼は<ruby id=\"ruby-room\">玄<rt id=\"ruby-room-reading\">くろう</rt>人<rt>と</rt></ruby>だ。</p>"
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
                    <dc:identifier id="book-id">$identifier</dc:identifier>
                    <dc:title>Jerreader 合成测试书</dc:title>
                    <dc:creator>读鼠测试</dc:creator>
                    <dc:language>zh-CN</dc:language>
                    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>
                    <item id="chapter-one" href="chapter-one.xhtml" media-type="application/xhtml+xml"/>
                    <item id="chapter-two" href="chapter-two.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="chapter-one"/>
                    <itemref idref="chapter-two"/>
                  </spine>
                </package>
            """.trimIndent()
        )
        archive.addBinaryEntry(
            "EPUB/cover.png",
            Base64.decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
                Base64.DEFAULT
            )
        )
        archive.addTextEntry(
            "EPUB/nav.xhtml",
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="zh-CN">
                  <head><title>目录</title></head>
                  <body>
                    <nav epub:type="toc">
                      <ol>
                        <li><a href="chapter-one.xhtml">第一章</a></li>
                        <li><a href="chapter-two.xhtml">第二章</a></li>
                      </ol>
                    </nav>
                  </body>
                </html>
            """.trimIndent()
        )
        archive.addTextEntry(
            "EPUB/chapter-one.xhtml",
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" lang="zh-CN">
                  <head><title>第一章</title></head>
                  <body>
                    <h1>第一章</h1>
                    <p>这是不含版权内容的合成 EPUB 测试夹具。</p>
                    <p id="english-sentence">She <span id="english-word">went</span> home. Then she opened a book.</p>
                    <p id="japanese-sentence">昨日ご飯を<span id="japanese-word">食べました</span>。</p>$ruby$rubyRoom$phrases$filler
                  </body>
                </html>
            """.trimIndent()
        )
        archive.addTextEntry(
            "EPUB/chapter-two.xhtml",
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" lang="zh-CN">
                  <head><title>第二章</title></head>
                  <body><h1>第二章</h1><p>用于验收目录跳转和 Locator 恢复。</p></body>
                </html>
            """.trimIndent()
        )
    }
}

private fun ZipOutputStream.addBinaryEntry(path: String, content: ByteArray) {
    putNextEntry(ZipEntry(path))
    write(content)
    closeEntry()
}

private fun ZipOutputStream.addTextEntry(path: String, content: String) {
    putNextEntry(ZipEntry(path))
    write(content.encodeToByteArray())
    closeEntry()
}
