package com.jerreader.shared.ui

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Edge-aligned reader controls matching iOS readerChrome. The publication is
 * still laid out by Readium; these views only draw app controls above it.
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
    transparency: Float = .15f,
    modifier: Modifier = Modifier
) {
    ChromeBar(modifier = modifier, transparency = transparency) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ChromeButton(JerreaderIcon.CLOSE, "关闭阅读器", onClick = onClose)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
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
            ChromeButton(JerreaderIcon.TABLE_OF_CONTENTS, "目录与阅读导航", onClick = onTableOfContents)
            ChromeButton(
                icon = if (isBookmarked) {
                    JerreaderIcon.BOOKMARK_FILLED
                } else {
                    JerreaderIcon.BOOKMARK_OUTLINE
                },
                isActive = isBookmarked,
                label = if (isBookmarked) "移除当前书签" else "添加当前书签",
                onClick = onToggleBookmark
            )
            // iOS labels this control 「格式」 rather than using a glyph.
            Box(Modifier.size(44.dp).semantics { contentDescription = "阅读设置" }
                .clickable(role = Role.Button, onClick = onAppearance), contentAlignment = Alignment.Center) {
                Text(
                    "格式",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
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
    transparency: Float = .15f,
    modifier: Modifier = Modifier
) {
    ChromeBar(modifier = modifier, bottom = true, transparency = transparency) {
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
                    modifier = Modifier.fillMaxWidth().height(31.dp).semantics { contentDescription = "快速调整阅读进度" },
                    thumb = { Box(Modifier.size(28.dp).shadow(3.dp, CircleShape).background(Color.White, CircleShape)) },
                    track = { state ->
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFD2D4D6))) {
                            Box(Modifier.fillMaxWidth(state.value.coerceIn(0f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                        }
                    }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (canGoToAdjacentChapter) {
                    PageButton(JerreaderIcon.CHEVRON_DOUBLE_LEFT, "上一章", onClick = onPreviousChapter)
                }
                PageButton(JerreaderIcon.CHEVRON_LEFT, "上一页", onClick = onPreviousPage)
                PageButton(
                    icon = if (isQuickTranslationEnabled) {
                        JerreaderIcon.TRANSLATION_BUBBLE_FILLED
                    } else {
                        JerreaderIcon.SPEECH_BUBBLE
                    },
                    isActive = isQuickTranslationEnabled,
                    label = if (isQuickTranslationEnabled) "关闭轻点翻译" else "开启轻点翻译",
                    onClick = onToggleQuickTranslation
                )
                PageButton(JerreaderIcon.CHEVRON_RIGHT, "下一页", onClick = onNextPage)
                if (canGoToAdjacentChapter) {
                    PageButton(JerreaderIcon.CHEVRON_DOUBLE_RIGHT, "下一章", onClick = onNextChapter)
                }
            }
        }
    }
}

@Composable
private fun ChromeBar(
    modifier: Modifier = Modifier,
    bottom: Boolean = false,
    transparency: Float = .15f,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = (if (MaterialTheme.colorScheme.onSurface.luminance() > .5f) Color(0xFF202020) else Color(0xFFF2F2F2)).copy(alpha = 1f - transparency.coerceIn(0f, 1f))
    ) {
        Column {
            if (bottom) JerreaderDivider()
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = if (bottom) 660.dp else 780.dp).fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = if (bottom) 6.dp else 4.dp)) { content() }
            }
            if (!bottom) JerreaderDivider()
        }
    }
}

@Composable
private fun ChromeButton(
    icon: JerreaderIcon,
    label: String,
    onClick: () -> Unit,
    isActive: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        JerreaderGlyph(
            icon = icon,
            tint = if (isActive) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun RowScope.PageButton(
    icon: JerreaderIcon,
    label: String,
    onClick: () -> Unit,
    isActive: Boolean = false
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    LocalJerreaderColors.current.accentFill.let { it.copy(alpha = it.alpha * .72f) }
                }
            )
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
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
