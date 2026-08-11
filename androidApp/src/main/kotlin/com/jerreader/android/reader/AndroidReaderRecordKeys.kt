package com.jerreader.android.reader

import java.security.MessageDigest
import java.text.Normalizer
import org.json.JSONObject

/** Stable Android record keys shared by normal reading and backup restore. */
internal object AndroidReaderRecordKeys {
    fun bookmark(bookId: String, href: String, progression: Double): String = buildString {
        append(bookId)
        append('|')
        append(href)
        append('|')
        // Preserve the existing persisted-key contract exactly. Locator
        // progression is normally 0...1, but clamping here would make an old
        // out-of-range record impossible to toggle after an upgrade.
        append((progression * 10_000).toInt())
    }

    fun bookmark(bookId: String, locatorJson: String): String? {
        val locator = runCatching { JSONObject(locatorJson) }.getOrNull() ?: return null
        val href = locator.optString("href").takeIf(String::isNotBlank) ?: return null
        val progression = locator.optJSONObject("locations")
            ?.optDouble("progression", 0.0)
            ?: 0.0
        return bookmark(bookId, href, progression)
    }

    fun annotation(bookId: String, locatorJson: String, selectedText: String): String {
        val canonical = listOf(
            bookId.trim(),
            normalize(locatorJson),
            normalize(selectedText)
        ).joinToString("\u001F")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value.trim(), Normalizer.Form.NFC)
        .replace(Regex("[\\t\\r\\n ]+"), " ")
}
