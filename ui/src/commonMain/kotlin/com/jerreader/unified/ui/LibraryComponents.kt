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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jerreader.unified.design.JerreaderCopy
import kotlin.math.roundToInt

/**
 * Library building blocks copied from the iOS `LibraryView`: the reading
 * overview card, the section titles with their accent chip, and the recently
 * viewed row. Geometry follows the Swift source (21dp overview radius, 18dp
 * card radius, 72x108 thumbnail, 42dp accent chip).
 */
@Composable
fun LibraryReadingOverview(
    totalSeconds: Double,
    startedCount: Int,
    averageProgress: Double,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        JerreaderCopy.libraryOverviewTitle,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (startedCount == 0) {
                            JerreaderCopy.libraryOverviewIdleDetail
                        } else {
                            JerreaderCopy.libraryOverviewActiveDetail
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    JerreaderGlyph(
                        icon = JerreaderIcon.TREND,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OverviewMetric(
                    value = JerreaderCopy.readingDuration(totalSeconds),
                    label = JerreaderCopy.libraryOverviewTotalLabel
                )
                VerticalDivider(
                    modifier = Modifier.height(38.dp),
                    color = MaterialTheme.colorScheme.outline
                )
                OverviewMetric(
                    value = startedCount.toString(),
                    label = JerreaderCopy.libraryOverviewStartedLabel
                )
                VerticalDivider(
                    modifier = Modifier.height(38.dp),
                    color = MaterialTheme.colorScheme.outline
                )
                OverviewMetric(
                    // iOS rounds rather than truncating, so 49.7% reads 50%
                    // there and read 49% here on the very same shelf.
                    value = "${(averageProgress * 100).roundToInt()}%",
                    label = JerreaderCopy.libraryOverviewAverageLabel
                )
            }
        }
    }
}

@Composable
private fun RowScope.OverviewMetric(value: String, label: String) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** `JerreaderSectionTitle`: accent chip, bold title, trailing detail. */
@Composable
fun LibrarySectionTitle(
    title: String,
    detail: String? = null,
    icon: JerreaderIcon = JerreaderIcon.GRID,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            JerreaderGlyph(
                icon = icon,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
            )
        }
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The folder row from the iOS shelf: horizontally scrolling cards, each showing
 * a folder name and how many books are in it, with 「全部书籍」 first.
 *
 * Android used to filter folders through a strip of Material `FilterChip`s.
 * They worked, but a chip cannot show a count, so the two shelves disagreed
 * about what a folder even looks like. Geometry follows the Swift source:
 * 132dp minimum width, 64dp height, 14dp radius, 0.75dp outline when unselected.
 */
@Composable
fun LibraryFolderSection(
    categories: List<String>,
    bookCount: Int,
    countForCategory: (String) -> Int,
    selectedCategory: String?,
    onSelectCategory: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        LibrarySectionTitle(
            title = JerreaderCopy.libraryFolderSectionTitle,
            detail = JerreaderCopy.folderCountDetail(categories.size),
            icon = JerreaderIcon.FOLDER
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LibraryFolderCard(
                title = JerreaderCopy.libraryAllBooksFilter,
                count = bookCount,
                isSelected = selectedCategory == null,
                icon = JerreaderIcon.GRID,
                onClick = { onSelectCategory(null) }
            )
            categories.forEach { category ->
                LibraryFolderCard(
                    title = category,
                    count = countForCategory(category),
                    isSelected = selectedCategory == category,
                    icon = JerreaderIcon.FOLDER,
                    onClick = { onSelectCategory(category) }
                )
            }
        }
    }
}

@Composable
private fun LibraryFolderCard(
    title: String,
    count: Int,
    isSelected: Boolean,
    icon: JerreaderIcon,
    onClick: () -> Unit
) {
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = Modifier.widthIn(min = 132.dp).height(64.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = if (isSelected) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            JerreaderGlyph(
                icon = icon,
                tint = if (isSelected) contentColor else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    JerreaderCopy.bookCount(count),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) {
                        contentColor
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

/** The 继续阅读 row: thumbnail, metadata, progress and a trailing chevron. */
@Composable
fun LibraryRecentlyViewedRow(
    title: String,
    author: String,
    format: String,
    lastOpenedLabel: String?,
    progress: Double,
    onOpen: () -> Unit,
    cover: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .height(108.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) { cover() }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    format.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                lastOpenedLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (progress > 0) {
                    LinearProgressIndicator(
                        progress = { progress.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                    )
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box(
                modifier = Modifier.align(Alignment.CenterVertically),
                contentAlignment = Alignment.Center
            ) {
                JerreaderGlyph(
                    icon = JerreaderIcon.CHEVRON_FORWARD,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/** Icon-only toolbar action, like the library's ⌄ / ↑↓ / ＋ buttons. */
@Composable
fun LibraryToolbarButton(
    icon: JerreaderIcon,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        JerreaderGlyph(
            icon = icon,
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * Segmented navigation used by the iOS 学习 page: a white pill container with
 * an icon and a label per item, the selected one filled with the accent.
 */
@Composable
fun <T> JerreaderSegmentedNav(
    options: List<Triple<T, String, JerreaderIcon>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(modifier = Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { (value, title, icon) ->
                val isSelected = value == selected
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            }
                        )
                        .clickable { onSelect(value) }
                        .padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    JerreaderGlyph(
                        icon = icon,
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = title,
                        modifier = Modifier.padding(start = 5.dp),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }
        }
    }
}

/** Hint banner: a rounded glyph tile, a bold headline and a grey subtitle. */
@Composable
fun JerreaderHintCard(
    badge: String,
    title: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Bold field label above an input, as used across the iOS learning forms. */
@Composable
fun JerreaderFieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall
    )
}

/** Full-width primary action, matching the iOS 「查询词语」 button. */
@Composable
fun JerreaderPrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: JerreaderIcon? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        },
        onClick = onClick,
        enabled = enabled
    ) {
        Row(
            modifier = Modifier.padding(vertical = 15.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.let {
                JerreaderGlyph(
                    icon = it,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp).padding(end = 2.dp)
                )
            }
            Text(
                text,
                modifier = Modifier.padding(start = if (icon == null) 0.dp else 8.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

/** Small tinted suggestion chip, like the iOS 「试一试」 example. */
@Composable
fun JerreaderSuggestionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        onClick = onClick
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
