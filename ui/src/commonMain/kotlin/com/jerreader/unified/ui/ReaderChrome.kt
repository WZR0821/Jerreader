package com.jerreader.unified.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Reader controls, laid out like the iOS `readerChrome`: two floating rounded
 * cards over a full-bleed page, rather than docked bars. Geometry (17dp radius,
 * hairline border, 10dp inner padding, 44dp round chrome buttons and 54dp
 * page buttons) follows `EPUBReaderView.swift`.
 */
@Composable
fun ReaderTopChrome(
    chapterTitle: String,
    bookTitle: String,
    isBookmarked: Boolean,
    onClose: () -> Unit,
    onTableOfContents: () -> Unit,
    onToggleBookmark: () -> Unit,
    onAppearance: () -> Unit,
    modifier: Modifier = Modifier
) {
    ChromeCard(modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ChromeButton(JerreaderIcon.CLOSE, onClick = onClose)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    text = chapterTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = bookTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            ChromeButton(JerreaderIcon.TABLE_OF_CONTENTS, onClick = onTableOfContents)
            ChromeButton(
                icon = if (isBookmarked) {
                    JerreaderIcon.BOOKMARK_FILLED
                } else {
                    JerreaderIcon.BOOKMARK_OUTLINE
                },
                isActive = isBookmarked,
                onClick = onToggleBookmark
            )
            // iOS labels this control 「格式」 rather than using a glyph.
            TextButton(
                onClick = onAppearance,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    "格式",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun ReaderBottomChrome(
    chapterLabel: String,
    progress: Double,
    showProgress: Boolean,
    onProgressChange: (Double) -> Unit,
    canGoToAdjacentChapter: Boolean,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    isQuickTranslationEnabled: Boolean,
    onToggleQuickTranslation: () -> Unit,
    modifier: Modifier = Modifier
) {
    ChromeCard(modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showProgress) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = chapterLabel,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = progress.toFloat().coerceIn(0f, 1f),
                    onValueChange = { onProgressChange(it.toDouble()) },
                    modifier = Modifier.fillMaxWidth().height(24.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (canGoToAdjacentChapter) {
                    PageButton(JerreaderIcon.CHEVRON_DOUBLE_LEFT, onClick = onPreviousChapter)
                }
                PageButton(JerreaderIcon.CHEVRON_LEFT, onClick = onPreviousPage)
                PageButton(
                    icon = if (isQuickTranslationEnabled) {
                        JerreaderIcon.SPEECH_BUBBLE_FILLED
                    } else {
                        JerreaderIcon.SPEECH_BUBBLE
                    },
                    isActive = isQuickTranslationEnabled,
                    onClick = onToggleQuickTranslation
                )
                PageButton(JerreaderIcon.CHEVRON_RIGHT, onClick = onNextPage)
                if (canGoToAdjacentChapter) {
                    PageButton(JerreaderIcon.CHEVRON_DOUBLE_RIGHT, onClick = onNextChapter)
                }
            }
        }
    }
}

@Composable
private fun ChromeCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth().widthIn(max = 780.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 6.dp
    ) {
        Box(modifier = Modifier.padding(10.dp)) { content() }
    }
}

@Composable
private fun ChromeButton(
    icon: JerreaderIcon,
    onClick: () -> Unit,
    isActive: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        JerreaderGlyph(
            icon = icon,
            tint = if (isActive) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun RowScope.PageButton(
    icon: JerreaderIcon,
    onClick: () -> Unit,
    isActive: Boolean = false
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        JerreaderGlyph(
            icon = icon,
            tint = if (isActive) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.size(26.dp)
        )
    }
}
