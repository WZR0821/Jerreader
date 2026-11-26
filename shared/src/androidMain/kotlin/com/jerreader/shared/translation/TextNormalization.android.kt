package com.jerreader.shared.translation

import java.text.Normalizer

actual fun canonicallyComposed(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
