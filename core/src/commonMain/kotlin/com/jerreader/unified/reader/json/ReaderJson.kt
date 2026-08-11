package com.jerreader.unified.reader.json

/**
 * A minimal JSON reader for the payloads [com.jerreader.unified.reader.selection.ReaderSelectionScripts]
 * produces.
 *
 * The two apps previously parsed those payloads with whatever their platform
 * offered — `org.json` on Android, `JSONSerialization` plus hand-written
 * `as? [String: Any]` casts on iOS — which meant the *decoding* rules differed
 * too: one side coerced a missing number to 0, the other dropped the whole
 * snapshot. Parsing in common code makes the tolerance rules shared and
 * testable.
 *
 * Deliberately small: enough JSON for a known, machine-generated payload, with
 * accessors that return null rather than throwing, because a web view that
 * returns something unexpected should degrade to "no selection", never crash
 * the reader.
 */
sealed class ReaderJsonValue {
    data object Null : ReaderJsonValue()
    data class Bool(val value: Boolean) : ReaderJsonValue()
    data class Num(val value: Double) : ReaderJsonValue()
    data class Text(val value: String) : ReaderJsonValue()
    data class Array(val items: List<ReaderJsonValue>) : ReaderJsonValue()
    data class Object(val entries: Map<String, ReaderJsonValue>) : ReaderJsonValue()

    fun objectOrNull(): Object? = this as? Object
    fun arrayOrNull(): Array? = this as? Array

    operator fun get(key: String): ReaderJsonValue? = (this as? Object)?.entries?.get(key)

    fun string(key: String): String? = (get(key) as? Text)?.value
    fun double(key: String): Double? = when (val value = get(key)) {
        is Num -> value.value
        is Text -> value.value.toDoubleOrNull()
        else -> null
    }

    fun int(key: String): Int? = double(key)?.toInt()
    fun boolean(key: String): Boolean? = when (val value = get(key)) {
        is Bool -> value.value
        is Text -> value.value.toBooleanStrictOrNull()
        else -> null
    }

    fun array(key: String): List<ReaderJsonValue> = (get(key) as? Array)?.items ?: emptyList()
}

object ReaderJson {

    /**
     * Parses [raw], tolerating the two shapes a web view actually returns: the
     * JSON itself, or that JSON wrapped in a quoted string (Android's
     * `evaluateJavascript` hands back a JSON *string literal* containing the
     * payload). Returns null for anything unparseable, including the literal
     * `null` a probe returns when it found nothing.
     */
    fun parse(raw: String?): ReaderJsonValue? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed == "null") return null
        val value = runCatching { Parser(trimmed).parseValue() }.getOrNull() ?: return null
        // Unwrap the double-encoded form.
        if (value is ReaderJsonValue.Text) {
            val inner = value.value.trim()
            if (inner.startsWith("{") || inner.startsWith("[")) {
                return runCatching { Parser(inner).parseValue() }.getOrNull()
            }
        }
        return value.takeUnless { it is ReaderJsonValue.Null }
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun parseValue(): ReaderJsonValue {
            skipWhitespace()
            return when (val character = peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> ReaderJsonValue.Text(parseString())
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else ->
                    if (character == '-' || character.isDigit()) parseNumber()
                    else error("unexpected character '$character' at $index")
            }
        }

        private fun parseObject(): ReaderJsonValue {
            expect('{')
            val entries = LinkedHashMap<String, ReaderJsonValue>()
            skipWhitespace()
            if (peek() == '}') { index++; return ReaderJsonValue.Object(entries) }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                entries[key] = parseValue()
                skipWhitespace()
                when (val character = peek()) {
                    ',' -> index++
                    '}' -> { index++; return ReaderJsonValue.Object(entries) }
                    else -> error("expected ',' or '}' but found '$character'")
                }
            }
        }

        private fun parseArray(): ReaderJsonValue {
            expect('[')
            val items = mutableListOf<ReaderJsonValue>()
            skipWhitespace()
            if (peek() == ']') { index++; return ReaderJsonValue.Array(items) }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                when (val character = peek()) {
                    ',' -> index++
                    ']' -> { index++; return ReaderJsonValue.Array(items) }
                    else -> error("expected ',' or ']' but found '$character'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                val character = next()
                when (character) {
                    '"' -> return builder.toString()
                    '\\' -> when (val escape = next()) {
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        '/' -> builder.append('/')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            val hex = source.substring(index, index + 4)
                            index += 4
                            builder.append(hex.toInt(16).toChar())
                        }
                        else -> error("unsupported escape '\\$escape'")
                    }
                    else -> builder.append(character)
                }
            }
        }

        private fun parseNumber(): ReaderJsonValue {
            val start = index
            if (peek() == '-') index++
            while (index < source.length && (source[index].isDigit() || source[index] in ".eE+-")) {
                index++
            }
            val text = source.substring(start, index)
            return ReaderJsonValue.Num(text.toDoubleOrNull() ?: error("bad number '$text'"))
        }

        private fun parseBoolean(): ReaderJsonValue = when {
            source.startsWith("true", index) -> { index += 4; ReaderJsonValue.Bool(true) }
            source.startsWith("false", index) -> { index += 5; ReaderJsonValue.Bool(false) }
            else -> error("bad boolean at $index")
        }

        private fun parseNull(): ReaderJsonValue {
            if (!source.startsWith("null", index)) error("bad null at $index")
            index += 4
            return ReaderJsonValue.Null
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }

        private fun peek(): Char =
            if (index < source.length) source[index] else error("unexpected end of input")

        private fun next(): Char =
            if (index < source.length) source[index++] else error("unexpected end of input")

        private fun expect(character: Char) {
            if (next() != character) error("expected '$character' at ${index - 1}")
        }
    }
}
