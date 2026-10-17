package com.jerreader.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import com.jerreader.shared.library.LibraryBook

/** The same deterministic fallback cover as BookCoverView on iOS. */
@Composable
fun JerreaderBookPlaceholder(book: LibraryBook) {
    val palettes = listOf(
        listOf(Color(.08f,.22f,.32f), Color(.16f,.42f,.56f)),
        listOf(Color(.10f,.20f,.28f), Color(.28f,.47f,.60f)),
        listOf(Color(.13f,.19f,.32f), Color(.28f,.40f,.62f)),
        listOf(Color(.15f,.20f,.30f), Color(.34f,.40f,.57f)))
    var index = 0; var total = 0
    while (index < book.title.length) {
        val first = book.title[index++].code
        val scalar = if (first in 0xD800..0xDBFF && index < book.title.length && book.title[index].code in 0xDC00..0xDFFF)
            0x10000 + ((first - 0xD800) shl 10) + book.title[index++].code - 0xDC00 else first
        total = (total + scalar) % palettes.size
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.linearGradient(palettes[total]))) {
        val scale = minOf(maxWidth.value / 168f, maxHeight.value / 252f, 1f)
        // Cover artwork scales as one image; accessible metadata is outside it.
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1f)) {
            Box(Modifier.fillMaxHeight().padding(vertical = (6 * scale).dp).width((6 * scale).dp).background(Color.White.copy(alpha = .16f)))
            Column(Modifier.fillMaxSize().padding((18 * scale).dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy((13 * scale).dp, Alignment.CenterVertically)) {
                JerreaderGlyph(JerreaderIcon.DICTIONARY, Color.White.copy(alpha = .88f), Modifier.size((27 * scale).dp))
                // Android's CJK serif fallback loses the bold weight used by the iOS fallback cover.
                val titleFamily = if (book.title.any { it in '\u2E80'..'\u9FFF' }) FontFamily.SansSerif else FontFamily.Serif
                Text(book.title, fontSize = (17 * scale).sp, lineHeight = (21 * scale).sp, fontFamily = titleFamily,
                    fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Box(Modifier.width((34 * scale).dp).height(1.dp).background(Color.White.copy(alpha = .55f)))
                Text(book.author ?: "未知作者", fontSize = (12 * scale).sp, lineHeight = (15 * scale).sp,
                    color = Color.White, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
