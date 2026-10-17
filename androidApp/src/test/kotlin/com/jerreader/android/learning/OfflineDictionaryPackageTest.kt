package com.jerreader.android.learning

import com.jerreader.android.lexical.DictionaryCSVParser
import com.jerreader.android.lexical.DictionaryPackageValidation
import com.jerreader.android.lexical.OfflineDictionaryManifest
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class OfflineDictionaryPackageTest {
    @Test fun readsQuotedCommasNewlinesAndDoubledQuotes() {
        val rows = mutableListOf<List<String>>()
        DictionaryCSVParser.read(StringReader("word,definition\napple,\"fruit, round\"\nquote,\"a\n\"\"b\"\"\"")) { rows += it }
        assertEquals(listOf("apple", "fruit, round"), rows[1])
        assertEquals(listOf("quote", "a\n\"b\""), rows[2])
    }
    @Test fun rejectsCorruptDownloadBeforeInstallation() {
        val file = File.createTempFile("jerreader-dictionary", ".csv")
        try {
            file.writeText("word,definition\napple,fruit\n")
            val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val manifest = OfflineDictionaryManifest("https://example.invalid/fixture", sha, file.length(), 1)
            DictionaryPackageValidation.validate(file, manifest)
            file.appendText("corruption")
            assertThrows(IllegalStateException::class.java) { DictionaryPackageValidation.validate(file, manifest) }
        } finally { file.delete() }
    }
    @Test fun rejectsTruncatedQuotedRecord() {
        assertThrows(IllegalArgumentException::class.java) { DictionaryCSVParser.read(StringReader("word,\"unfinished")) {} }
    }
}
