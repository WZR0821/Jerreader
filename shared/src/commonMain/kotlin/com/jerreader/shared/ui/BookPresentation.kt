package com.jerreader.shared.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jerreader.shared.library.LibraryBook

/**
 * The cover of a book that has no image, as iOS `LibraryBookCoverView` draws
 * it: the generated ink-wash cover, with the smaller type when the cover is
 * narrower than 150dp. Like the iOS cover it is decorative (the cover button
 * around it carries the title for TalkBack), and its type keeps fixed sizes.
 */
@Composable
fun JerreaderBookPlaceholder(book: LibraryBook, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().clearAndSetSemantics {}) {
        val compact = maxWidth < 150.dp
        // iOS imports a book without an author as 未知作者, so its cover shows that.
        val author = book.author?.trim()?.takeIf(String::isNotEmpty) ?: "未知作者"
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1f)) {
            JerreaderGeneratedCover(title = book.title, author = author, compact = compact)
        }
    }
}
