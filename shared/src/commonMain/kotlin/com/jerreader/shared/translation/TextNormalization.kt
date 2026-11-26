package com.jerreader.shared.translation

/** Unicode NFC, like Swift's `precomposedStringWithCanonicalMapping`. */
expect fun canonicallyComposed(text: String): String
