package com.jerreader.shared.translation

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSString
import platform.Foundation.create

@OptIn(ExperimentalForeignApi::class)
actual fun textCharacterCount(text: String): Int {
    val source = NSString.create(string = text)
    var offset = 0uL
    var count = 0
    while (offset < source.length) {
        offset = source.rangeOfComposedCharacterSequenceAtIndex(offset).useContents { location + length }
        count++
    }
    return count
}
