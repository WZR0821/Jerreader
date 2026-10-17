package com.jerreader.shared.translation

/** User-facing limits count composed characters, never UTF-16 code units. */
expect fun textCharacterCount(text: String): Int
