package com.jerreader.shared.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The vocabulary and history collections, ported from the iOS `VocabularyView`
 * and `LookupHistoryView`. Both pages are the same shape: a control bar of
 * search field, count badge and one toolbar button, a hairline, then sectioned
 * record cards or an empty state. The Android versions used to be a heading, a
 * row of filled buttons and an outlined text field, which shared none of that.
 */

/** Rounded search box with a leading magnifier and a trailing clear button. */
@Composable
fun JerreaderSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            JerreaderGlyph(
                icon = JerreaderIcon.SEARCH,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    textStyle = LocalTextStyle.current.merge(
                        MaterialTheme.typography.bodyMedium
                    ).copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(LocalJerreaderColors.current.accent)
                )
            }
            if (value.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable { onValueChange("") },
                    contentAlignment = Alignment.Center
                ) {
                    JerreaderGlyph(
                        icon = JerreaderIcon.CLOSE,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}

/** Accent capsule holding how many records the collection currently has. */
@Composable
fun JerreaderCountBadge(count: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.defaultMinSize(minWidth = 30.dp, minHeight = 30.dp),
        shape = CircleShape,
        color = LocalJerreaderColors.current.accentFill
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = LocalJerreaderColors.current.accent,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Bordered 42dp square holding one accent glyph — the iOS toolbar button. */
@Composable
fun JerreaderToolbarIconButton(
    icon: JerreaderIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = LocalJerreaderColors.current
    Surface(
        modifier = modifier.size(42.dp),
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Box(
            modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            JerreaderGlyph(
                icon = icon,
                tint = if (enabled) colors.accent else colors.secondaryText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * The tinted strip the search field sits in, closed by a hairline. iOS uses
 * `.ultraThinMaterial` over the page; `mutedSurface` is the flat equivalent.
 */
@Composable
fun JerreaderCollectionControls(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(LocalJerreaderColors.current.mutedSurface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
        JerreaderDivider()
    }
}

/** Bold title over a grey explanatory line, the iOS plain-list section header. */
@Composable
fun JerreaderCollectionSectionHeader(
    title: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One record in a collection list: paper, hairline border, 18dp corners. */
@Composable
fun JerreaderRecordCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}

/**
 * Serif headword, optional lemma and reading, definitions, then a footer of
 * language capsule, part of speech and relative time — the iOS `WordRecordRow`.
 */
@Composable
fun JerreaderWordRecordRow(
    surfaceForm: String,
    lemma: String?,
    reading: String?,
    definitions: String,
    languageName: String,
    partOfSpeech: String?,
    trailingNote: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = surfaceForm,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold
            )
            if (!lemma.isNullOrEmpty() && lemma != surfaceForm) {
                Text(
                    text = "→ $lemma",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            trailing()
        }
        if (!reading.isNullOrEmpty()) {
            Text(
                text = reading,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (definitions.isNotBlank()) {
            Text(
                text = definitions,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            JerreaderTagCapsule(languageName)
            if (!partOfSpeech.isNullOrEmpty()) {
                Text(
                    text = partOfSpeech,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = trailingNote,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * Borderless glyph button sized for a record card's header. iOS puts these
 * actions behind swipe gestures, which Android users have no way to discover,
 * so the same actions sit in the row itself.
 */
@Composable
fun JerreaderRowIconButton(
    icon: JerreaderIcon,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        JerreaderGlyph(icon = icon, tint = tint, modifier = Modifier.size(17.dp))
    }
}

/** Small accent-on-accentFill capsule used for languages and short tags. */
@Composable
fun JerreaderTagCapsule(text: String, modifier: Modifier = Modifier) {
    val colors = LocalJerreaderColors.current
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = colors.accentFill
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.accent
        )
    }
}

/** Language pair, translation, source text and book — the iOS favourite row. */
@Composable
fun JerreaderTranslationFavoriteRow(
    languagePair: String,
    translatedText: String,
    sourceText: String,
    bookTitle: String?,
    trailingNote: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val colors = LocalJerreaderColors.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            JerreaderGlyph(
                icon = JerreaderIcon.DICTIONARY,
                tint = colors.accent,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = languagePair,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.accent
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = trailingNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            trailing()
        }
        Text(
            text = translatedText,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = sourceText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (!bookTitle.isNullOrEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                JerreaderGlyph(
                    icon = JerreaderIcon.DICTIONARY,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = bookTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Accent-tinted glyph tile over a title and a message, centred on the page. */
@Composable
fun JerreaderEmptyState(
    title: String,
    message: String,
    icon: JerreaderIcon,
    modifier: Modifier = Modifier
) {
    val colors = LocalJerreaderColors.current
    Column(
        modifier = modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            modifier = Modifier.size(68.dp),
            shape = RoundedCornerShape(20.dp),
            color = colors.accentFill
        ) {
            Box(contentAlignment = Alignment.Center) {
                JerreaderGlyph(
                    icon = icon,
                    tint = colors.accent,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
        Column(
            modifier = Modifier.widthIn(max = 340.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * The app's single relative-time policy, matching iOS `JerreaderRelativeTime`:
 * minute, hour and day buckets only, so a list of records never reads like a
 * running clock.
 */
object JerreaderRelativeTime {
    fun format(epochMillis: Long, nowMillis: Long): String {
        val elapsed = ((nowMillis - epochMillis) / 1000L).coerceAtLeast(0L)
        return when {
            elapsed < 60 -> "刚刚"
            elapsed < 3_600 -> "${(elapsed / 60).coerceAtLeast(1)} 分钟前"
            elapsed < 86_400 -> "${(elapsed / 3_600).coerceAtLeast(1)} 小时前"
            else -> "${(elapsed / 86_400).coerceAtLeast(1)} 天前"
        }
    }
}
