package com.jerreader.shared.translation

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.precomposedStringWithCanonicalMapping

@OptIn(ExperimentalForeignApi::class)
actual fun canonicallyComposed(text: String): String =
    NSString.create(string = text).precomposedStringWithCanonicalMapping
