package com.jerreader.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jerreader.shared.library.ReaderInterfaceDefaults

/**
 * The reader's top bar, matching iOS `topChromeBar`: a slim row in the page's
 * own colour. [topInset] is the status-bar height the bar paints behind, so
 * the colour runs to the top edge as it does on iOS.
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
    pageColor: Color,
    transparency: Float = .15f,
    showsDivider: Boolean = false,
    topInset: Dp = 0.dp,
    onCompanion: (() -> Unit)? = null,
    hasCompanionUpdate: Boolean = false,
    onInteraction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    ChromeBar(
        pageColor = pageColor,
        transparency = transparency,
        showsDivider = showsDivider,
        dividerAtTop = false,
        maxContentWidth = 780.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topInset)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            ChromeButton(JerreaderIcon.CLOSE, "关闭阅读器", onInteraction, onClick = onClose)
            Column(
                modifier = Modifier.weight(1f).padding(start = 4.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = chapterTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
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
            ChromeButton(
                JerreaderIcon.TABLE_OF_CONTENTS,
                "目录与阅读导航",
                onInteraction,
                onClick = onTableOfContents
            )
            // Bookmarking is a one-tap, frequently repeated action whose state
            // is worth showing continuously, as on iOS.
            ChromeButton(
                icon = if (isBookmarked) {
                    JerreaderIcon.BOOKMARK_FILLED
                } else {
                    JerreaderIcon.BOOKMARK_OUTLINE
                },
                label = if (isBookmarked) "移除当前书签" else "添加当前书签",
                onInteraction = onInteraction,
                isActive = isBookmarked,
                onClick = onToggleBookmark
            )
            if (onCompanion != null) {
                // The dot marks a recap or a finished chapter waiting in the
                // panel; nothing opens by itself while the reader reads.
                Box(
                    Modifier.size(44.dp)
                        .semantics {
                            contentDescription = if (hasCompanionUpdate) "阅读伴读，有新的回顾" else "阅读伴读"
                        }
                        .clickable(role = Role.Button) {
                            onInteraction()
                            onCompanion()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "AI",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (hasCompanionUpdate) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(5.dp)
                                .size(7.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                    }
                }
            }
            // iOS labels this control 「格式」 rather than using a glyph.
            Box(
                Modifier.size(44.dp)
                    .semantics { contentDescription = "阅读设置" }
                    .clickable(role = Role.Button) {
                        onInteraction()
                        onAppearance()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "格式",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * The reader's bottom bar, matching iOS `bottomChromeBar`: one slim row for
 * the position and one for the page buttons, which are bare glyphs on the page
 * colour rather than filled tiles. [bottomInset] is the navigation-bar height
 * the bar paints behind.
 *
 * Dragging the slider only previews the chapter under the thumb; the book
 * moves once the finger lifts, so it does not jump around mid-drag.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ReaderBottomChrome(
    chapterLabel: String,
    progress: Double,
    showProgress: Boolean,
    chapterLabelAt: (Double) -> String,
    onSeek: (Double) -> Unit,
    canGoToAdjacentChapter: Boolean,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    isQuickTranslationEnabled: Boolean,
    onToggleQuickTranslation: () -> Unit,
    pageColor: Color,
    transparency: Float = .15f,
    showsDivider: Boolean = false,
    bottomInset: Dp = 0.dp,
    onInteraction: () -> Unit = {},
    onScrubbingChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var scrubbedProgress by remember { mutableStateOf<Double?>(null) }
    ChromeBar(
        pageColor = pageColor,
        transparency = transparency,
        showsDivider = showsDivider,
        dividerAtTop = true,
        maxContentWidth = 660.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = bottomInset)
        ) {
            if (showProgress) {
                val scrubbing = scrubbedProgress
                val shownProgress = scrubbing ?: progress
                val percent = "${kotlin.math.round(shownProgress.coerceIn(0.0, 1.0) * 100).toInt()}%"
                val label = scrubbing?.let(chapterLabelAt) ?: chapterLabel
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 32.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // While dragging, the chapter the thumb is over matters far
                    // more than the raw percentage, so it takes the accent colour.
                    Text(
                        text = label,
                        modifier = Modifier.widthIn(max = 104.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (scrubbing == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis
                    )
                    Slider(
                        value = shownProgress.toFloat().coerceIn(0f, 1f),
                        onValueChange = {
                            if (scrubbedProgress == null) onScrubbingChanged(true)
                            scrubbedProgress = it.toDouble().coerceIn(0.0, 1.0)
                        },
                        onValueChangeFinished = {
                            val target = scrubbedProgress
                            scrubbedProgress = null
                            onScrubbingChanged(false)
                            if (target != null) onSeek(target)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(31.dp)
                            .semantics {
                                contentDescription = "快速调整阅读进度"
                                stateDescription = "$label，$percent"
                            },
                        thumb = {
                            Box(
                                Modifier.size(28.dp)
                                    .shadow(3.dp, CircleShape)
                                    .background(Color.White, CircleShape)
                            )
                        },
                        track = { state ->
                            Box(
                                Modifier.fillMaxWidth().height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .15f))
                            ) {
                                Box(
                                    Modifier.fillMaxWidth(state.value.coerceIn(0f, 1f))
                                        .fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                    )
                    Text(
                        text = percent,
                        modifier = Modifier.defaultMinSize(minWidth = 34.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 1
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                if (canGoToAdjacentChapter) {
                    PageButton(
                        JerreaderIcon.CHEVRON_DOUBLE_LEFT,
                        "上一章",
                        onInteraction,
                        onClick = onPreviousChapter
                    )
                }
                PageButton(JerreaderIcon.CHEVRON_LEFT, "上一页", onInteraction, onClick = onPreviousPage)
                PageButton(
                    icon = if (isQuickTranslationEnabled) {
                        JerreaderIcon.TRANSLATION_BUBBLE_FILLED
                    } else {
                        JerreaderIcon.SPEECH_BUBBLE
                    },
                    label = if (isQuickTranslationEnabled) "关闭轻点翻译" else "开启轻点翻译",
                    onInteraction = onInteraction,
                    isActive = isQuickTranslationEnabled,
                    onClick = onToggleQuickTranslation
                )
                PageButton(JerreaderIcon.CHEVRON_RIGHT, "下一页", onInteraction, onClick = onNextPage)
                if (canGoToAdjacentChapter) {
                    PageButton(
                        JerreaderIcon.CHEVRON_DOUBLE_RIGHT,
                        "下一章",
                        onInteraction,
                        onClick = onNextChapter
                    )
                }
            }
        }
    }
}

/**
 * The page colour at the chosen transparency, edge to edge. Hairlines only
 * where the bars float over text (the immersive layout); in the standard one
 * the bars are page-coloured margins and a rule would cut them off.
 */
@Composable
private fun ChromeBar(
    pageColor: Color,
    transparency: Float,
    showsDivider: Boolean,
    dividerAtTop: Boolean,
    maxContentWidth: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val line = MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(pageColor.copy(alpha = ReaderInterfaceDefaults.backgroundOpacity(transparency)))
            .drawBehind {
                if (showsDivider) {
                    val y = if (dividerAtTop) 0f else size.height
                    drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
                }
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Box(Modifier.widthIn(max = maxContentWidth).fillMaxWidth()) { content() }
    }
}

@Composable
private fun ChromeButton(
    icon: JerreaderIcon,
    label: String,
    onInteraction: () -> Unit,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button) {
                onInteraction()
                onClick()
            },
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

/**
 * A bare glyph in the page's own colours; only the active translation toggle
 * gets a filled capsule, so its state still reads at a glance.
 */
@Composable
private fun RowScope.PageButton(
    icon: JerreaderIcon,
    label: String,
    onInteraction: () -> Unit,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 44.dp)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button) {
                onInteraction()
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 32.dp)
                .background(
                    if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent,
                    RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            JerreaderGlyph(
                icon = icon,
                tint = if (isActive) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = .62f)
                },
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
