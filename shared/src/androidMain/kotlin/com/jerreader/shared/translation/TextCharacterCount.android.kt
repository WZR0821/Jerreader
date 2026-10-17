package com.jerreader.shared.translation

import java.util.regex.Pattern

// Android's ICU regex backend and the host JDK support extended grapheme clusters.
private val composedCharacter = Pattern.compile("\\X")

actual fun textCharacterCount(text: String): Int {
    val matcher = composedCharacter.matcher(text)
    var count = 0
    while (matcher.find()) count++
    return count
}
