package com.jerreader.android.lexical

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.LexicalLookupFailure
import com.jerreader.shared.lexical.LexicalLookupService
import com.jerreader.shared.lexical.WordExplanation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class OfflineDictionaryManifest(
    val url: String,
    val sha256: String,
    val bytes: Long,
    val entries: Int
) {
    companion object {
        val ENGLISH = OfflineDictionaryManifest(
            "https://raw.githubusercontent.com/skywind3000/ECDICT/bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b/ecdict.csv",
            "1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf", 65_933_428, 770_611)
        val LICENSE_TEXT = """
MIT License

Copyright (c) 2025 Linwei

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
""".trimIndent()
        const val SOURCE = "https://github.com/skywind3000/ECDICT"
        const val LICENSE = "https://github.com/skywind3000/ECDICT/blob/bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b/LICENSE"
    }
}

/** Streaming parser shared by actual package installation and offline fixtures. */
object DictionaryCSVParser {
    fun read(reader: Reader, emit: (List<String>) -> Unit) {
        val field = StringBuilder()
        val row = mutableListOf<String>()
        var quoted = false
        var closingQuote = false
        fun endField() { row += field.toString(); field.setLength(0) }
        val buffer = CharArray(65_536)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            for (i in 0 until count) {
                val char = buffer[i]
                if (quoted) {
                    if (char == '"') {
                        if (closingQuote) { field.append('"'); closingQuote = false } else closingQuote = true
                        continue
                    }
                    if (!closingQuote) { field.append(char); require(field.length < 1_000_000); continue }
                    quoted = false; closingQuote = false
                }
                when {
                    char == '"' && field.isEmpty() -> quoted = true
                    char == ',' -> endField()
                    char == '\n' -> { endField(); emit(row.toList()); row.clear() }
                    char != '\r' -> field.append(char)
                }
                require(field.length < 1_000_000 && row.size < 100)
            }
        }
        require(!quoted || closingQuote) { "Unclosed CSV quote" }
        if (field.isNotEmpty() || row.isNotEmpty()) { endField(); emit(row.toList()) }
    }
}

fun interface DictionaryPackageDownloader {
    suspend fun download(manifest: OfflineDictionaryManifest, target: File)
}

class HTTPSDictionaryPackageDownloader : DictionaryPackageDownloader {
    override suspend fun download(manifest: OfflineDictionaryManifest, target: File) = withContext(Dispatchers.IO) {
        val connection = URL(manifest.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000; connection.readTimeout = 30_000
        try {
            check(connection.responseCode == 200) { "下载失败，请检查网络后重试。" }
            connection.inputStream.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(65_536); var bytes = 0L
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count; check(bytes <= manifest.bytes) { "词典包体积异常。" }
                    output.write(buffer, 0, count)
                }
            } }
        } finally { connection.disconnect() }
    }
}

object DictionaryPackageValidation {
    fun validate(file: File, manifest: OfflineDictionaryManifest) {
        check(file.length() == manifest.bytes) { "词典包不完整，请重试。" }
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        check(hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == manifest.sha256) { "词典包校验失败，请重试。" }
    }
}

data class OfflinePackageStatus(val installed: Boolean, val busy: Boolean = false, val message: String? = null)

class OfflineDictionaryPackageStore(context: Context) : LexicalLookupService {
    val englishChinese by lazy { EnglishChineseLexicon(context.assets.open("en-zh-basic.tsv")
        .bufferedReader(Charsets.UTF_8).use { it.readText() }) }
    val englishChineseNotice by lazy { context.assets.open("en-zh-NOTICE.txt").bufferedReader(Charsets.UTF_8).use { it.readText() } }
    val japaneseChinese by lazy { JapaneseChineseLexicon(context.assets.open("ja-zh-core.tsv")
        .bufferedReader(Charsets.UTF_8).use { it.readText() },
        context.assets.open("ja-zh-wiktionary.tsv").bufferedReader(Charsets.UTF_8).use { it.readText() }) }
    val japaneseChineseNotice by lazy { context.assets.open("ja-zh-NOTICE.txt").bufferedReader(Charsets.UTF_8).use { it.readText() } }
    private val directory = File(context.noBackupFilesDir, "offline-dictionaries")
    private val index = File(directory, "ecdict-bc015ed.sqlite")
    private val installing = AtomicBoolean(false)
    private val mutableStatus = MutableStateFlow(OfflinePackageStatus(index.isFile))
    val status = mutableStatus.asStateFlow()

    // Require actual free storage; do not reclaim other app caches to install a dictionary.
    @android.annotation.SuppressLint("UsableSpace")
    suspend fun install(manifest: OfflineDictionaryManifest = OfflineDictionaryManifest.ENGLISH,
                        downloader: DictionaryPackageDownloader = HTTPSDictionaryPackageDownloader()) = withContext(Dispatchers.IO) {
        if (!installing.compareAndSet(false, true)) return@withContext
        mutableStatus.value = OfflinePackageStatus(index.isFile, busy = true)
        val source = File(directory, UUID.randomUUID().toString() + ".csv")
        val staging = File(directory, UUID.randomUUID().toString() + ".sqlite")
        try {
            check(directory.isDirectory || directory.mkdirs()) { "无法创建词典目录。" }
            check(directory.usableSpace > maxOf(manifest.bytes * 4, 300_000_000)) { "存储空间不足，请预留至少 300 MB。" }
            downloader.download(manifest, source)
            ensureActive(); DictionaryPackageValidation.validate(source, manifest)
            SQLiteDatabase.openOrCreateDatabase(staging, null).use { db ->
                db.execSQL("CREATE TABLE entries (term TEXT PRIMARY KEY, reading TEXT, definition TEXT, pos TEXT)")
                db.beginTransaction()
                try {
                    db.compileStatement("INSERT OR REPLACE INTO entries VALUES (?,?,?,?)").use { statement ->
                        var first = true; var count = 0
                        source.bufferedReader(Charsets.UTF_8).use { reader ->
                            DictionaryCSVParser.read(reader) { fields ->
                                ensureActive()
                                if (first) { first = false; check(fields.firstOrNull() == "word"); return@read }
                                if (fields.size < 5 || fields[0].isEmpty() || (fields[2].isEmpty() && fields[3].isEmpty())) return@read
                                val values = listOf(fields[0].lowercase(Locale.ROOT), fields[1],
                                    fields[3].ifEmpty { fields[2] }.replace("\\n", "\n"), fields[4])
                                values.forEachIndexed { i, value -> statement.bindString(i + 1, value) }
                                statement.executeInsert(); statement.clearBindings(); count++
                            }
                        }
                        check(count == manifest.entries) { "词典条目不完整。" }
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            ensureActive()
            check(staging.renameTo(index)) { "词典安装失败，已有词典保留。" }
            mutableStatus.value = OfflinePackageStatus(true, message = "已安装，可以离线查词。")
        } catch (e: CancellationException) {
            mutableStatus.value = OfflinePackageStatus(index.isFile, message = "已取消，原有词典保留。")
            throw e
        } catch (e: Exception) {
            mutableStatus.value = OfflinePackageStatus(index.isFile, message = e.message ?: "安装失败，请重试。")
        } finally { source.delete(); staging.delete(); File(staging.path + "-journal").delete(); installing.set(false) }
    }

    suspend fun remove() = withContext(Dispatchers.IO) {
        if (!installing.compareAndSet(false, true)) return@withContext
        try {
            val success = !index.exists() || index.delete()
            mutableStatus.value = OfflinePackageStatus(index.isFile, message = if (success) "词典包已删除。" else "删除失败，请重试。")
        } finally { installing.set(false) }
    }

    override suspend fun lookup(word: String, sentenceContext: String?, language: LanguageCode, candidates: List<String>): WordExplanation = withContext(Dispatchers.IO) {
        if (language == LanguageCode.JAPANESE) {
            val analysis = com.jerreader.shared.lexical.WordMorphologyAnalyzer(
                com.jerreader.android.reader.AndroidWordBoundaryTokenizer()).analyze(
                com.jerreader.shared.lexical.WordAnalysisRequest(word, languageHint = language,
                    source = com.jerreader.shared.lexical.WordSelectionSource.NATIVE_SELECTION))
            val entry = japaneseChinese.japanese(word, candidates + analysis?.lemmaCandidates.orEmpty())
                ?: throw LexicalLookupFailure.ResultNotFound
            return@withContext WordExplanation(word, entry.term, entry.reading, language,
                entry.partOfSpeech, listOf(entry.definition),
                inflectionNote = japaneseChinese.inflectionNote(word, entry), usageNote = entry.attribution + " · 离线释义",
                sentenceContext = sentenceContext, providerIdentifier = "ja-zh-bundled-" + JapaneseChineseLexicon.VERSION)
        }
        if (language != LanguageCode.ENGLISH) throw LexicalLookupFailure.ResultNotFound
        val core = englishChinese.lookup(word, sentenceContext, candidates)
        if (!index.isFile) return@withContext core ?: throw LexicalLookupFailure.ResultNotFound
        val result = try {
            SQLiteDatabase.openDatabase(index.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                (listOf(word) + candidates).distinct().firstNotNullOfOrNull { candidate ->
                    db.rawQuery("SELECT term,reading,definition,pos FROM entries WHERE term=?", arrayOf(candidate.trim().lowercase(Locale.ROOT))).use { cursor ->
                        if (!cursor.moveToFirst()) null else WordExplanation(
                            surfaceForm = word, lemma = cursor.getString(0), reading = cursor.getString(1), language = language,
                            partOfSpeech = cursor.getString(3), definitions = cursor.getString(2).lines().filter(String::isNotBlank),
                            usageNote = "离线词库：ECDICT · MIT", sentenceContext = sentenceContext, providerIdentifier = "ecdict-offline-bc015ed")
                    }
                }
            }
        } catch (_: android.database.SQLException) { null }
        result ?: core ?: throw LexicalLookupFailure.ResultNotFound
    }
}
