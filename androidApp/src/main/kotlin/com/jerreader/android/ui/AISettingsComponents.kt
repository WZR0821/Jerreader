package com.jerreader.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.jerreader.shared.ui.ExactSystemLineHeight
import com.jerreader.shared.ui.withSystemLineHeights
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jerreader.android.translation.AIAPIStyle
import com.jerreader.android.translation.AIModelCatalogConfiguration
import com.jerreader.android.translation.AIModelCatalogException
import com.jerreader.android.translation.AIModelCatalogService
import com.jerreader.android.translation.AIProviderKind
import com.jerreader.android.translation.AIProviderSettingsError
import com.jerreader.android.translation.ConfiguredTranslationService
import com.jerreader.android.translation.DirectAIConfiguration
import com.jerreader.android.translation.RemoteAIModelCatalogService
import com.jerreader.shared.translation.TranslationFailure
import com.jerreader.shared.ui.JerreaderGlyph
import com.jerreader.shared.ui.PageSheetShape
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderRowChevron
import com.jerreader.shared.ui.JerreaderRowSeparator
import com.jerreader.shared.ui.JerreaderUpDownChevron
import com.jerreader.shared.ui.JerreaderNavigationBar
import java.util.Locale

/*
 * The building blocks of 设置 › AI 大模型, ported from the iOS
 * `AISettingsComponents.swift`: inset groups on the canvas, provider marks,
 * labelled rows, and the sheets that slide up over a page.
 */

// MARK: Colours and metrics

@Composable
internal fun aiIsDark(): Boolean = MaterialTheme.colorScheme.onSurface != Color.Black

/** `secondarySystemGroupedBackground`: the card behind a group of rows. */
@Composable
internal fun aiGroupedColor(): Color = if (aiIsDark()) Color(0xFF1C1C1E) else Color.White

/** `tertiaryLabel`: chevrons, unchecked circles and placeholders. */
@Composable
internal fun aiTertiary(): Color = if (aiIsDark()) Color(0x4DEBEBF5) else Color(0x4D3C3C43)

@Composable
internal fun aiWarning(): Color = if (aiIsDark()) Color(0xFFFF9F0A) else Color(0xFFFF9500)

@Composable
internal fun aiRed(): Color = if (aiIsDark()) Color(0xFFFF453A) else Color(0xFFFF3B30)

/** The inset-grouped margin: 20 on wide phones, 16 on narrow ones. */
@Composable
internal fun aiPageMargin(): Dp = if (LocalConfiguration.current.screenWidthDp >= 400) 20.dp else 16.dp

/** The least height of a row; lists whose rows carry a provider mark are taller. */
internal val LocalAIRowHeight = compositionLocalOf { 52.dp }

internal object AIListMetrics {
    val rowHeight = 52.dp
    val iconRowHeight = 60.dp
    /** Where the line under a row with a 34-point mark starts. */
    val iconDivider = 67.dp
    /** Above the first group under the navigation bar. */
    val topMargin = 12.dp
}

// MARK: Glyphs the shared icon set does not have

internal enum class AIGlyph { SPARKLE, SERVER, EYE, EYE_SLASH, ARROW_UP_RIGHT, EXCLAMATION_CIRCLE, CHECK_CIRCLE_FILLED, CIRCLE, WARNING_FILLED, CHECKMARK }

/** SF Symbols the AI pages use, drawn on the same 24-point grid as `JerreaderGlyph`. */
@Composable
internal fun AIGlyphIcon(glyph: AIGlyph, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        val dx = (size.width - size.minDimension) / 2f
        val dy = (size.height - size.minDimension) / 2f
        fun p(x: Float, y: Float) = Offset(dx + x * unit, dy + y * unit)
        val width = 1.65f * unit
        val line = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            AIGlyph.SPARKLE -> drawPath(sparkle(p(12f, 12f), 10.5f * unit), tint)
            AIGlyph.SERVER -> for (top in listOf(4f, 13.5f)) {
                drawRoundRect(tint, topLeft = p(3.5f, top), size = Size(17f * unit, 6.5f * unit),
                    cornerRadius = CornerRadius(1.8f * unit), style = line)
                drawCircle(tint, radius = 1.15f * unit, center = p(7.4f, top + 3.25f))
                drawLine(tint, p(11.2f, top + 3.25f), p(16.6f, top + 3.25f), strokeWidth = width, cap = StrokeCap.Round)
            }
            AIGlyph.EYE, AIGlyph.EYE_SLASH -> {
                val eye = Path().apply {
                    moveTo(dx + 2.5f * unit, dy + 12f * unit)
                    quadraticTo(dx + 12f * unit, dy + 2.2f * unit, dx + 21.5f * unit, dy + 12f * unit)
                    quadraticTo(dx + 12f * unit, dy + 21.8f * unit, dx + 2.5f * unit, dy + 12f * unit)
                    close()
                }
                drawPath(eye, tint, style = line)
                drawCircle(tint, radius = 3.2f * unit, center = p(12f, 12f), style = line)
                if (glyph == AIGlyph.EYE_SLASH) {
                    drawLine(tint, p(4.2f, 3.8f), p(19.8f, 20.2f), strokeWidth = width, cap = StrokeCap.Round)
                }
            }
            AIGlyph.ARROW_UP_RIGHT -> {
                drawLine(tint, p(7f, 17f), p(17f, 7f), strokeWidth = width, cap = StrokeCap.Round)
                drawPath(Path().apply {
                    moveTo(dx + 9.5f * unit, dy + 7f * unit)
                    lineTo(dx + 17f * unit, dy + 7f * unit)
                    lineTo(dx + 17f * unit, dy + 14.5f * unit)
                }, tint, style = line)
            }
            AIGlyph.EXCLAMATION_CIRCLE -> {
                drawCircle(tint, radius = 9.2f * unit, center = p(12f, 12f), style = line)
                drawLine(tint, p(12f, 7.4f), p(12f, 12.8f), strokeWidth = width, cap = StrokeCap.Round)
                drawCircle(tint, radius = 1.15f * unit, center = p(12f, 16.4f))
            }
            AIGlyph.CHECK_CIRCLE_FILLED -> {
                drawCircle(tint, radius = 10.2f * unit, center = p(12f, 12f))
                drawPath(Path().apply {
                    moveTo(dx + 7.4f * unit, dy + 12.3f * unit)
                    lineTo(dx + 10.6f * unit, dy + 15.4f * unit)
                    lineTo(dx + 16.6f * unit, dy + 9f * unit)
                }, Color.White, style = Stroke(width = 2f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            AIGlyph.CIRCLE -> drawCircle(tint, radius = 9.8f * unit, center = p(12f, 12f), style = Stroke(width = 1.5f * unit))
            // `checkmark` at semibold, measured off the iOS list: at 17 points
            // its ink is 15 wide and 14.7 tall, 2 short of the right edge, with
            // a stroke of about 2.2.
            AIGlyph.CHECKMARK -> drawPath(Path().apply {
                moveTo(dx + 1.55f * unit, dy + 12.64f * unit)
                lineTo(dx + 8.33f * unit, dy + 20.82f * unit)
                lineTo(dx + 19.62f * unit, dy + 3.18f * unit)
            }, tint, style = Stroke(width = 3.1f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
            AIGlyph.WARNING_FILLED -> {
                val triangle = Path().apply {
                    moveTo(dx + 12f * unit, dy + 3.4f * unit)
                    lineTo(dx + 21.4f * unit, dy + 20f * unit)
                    lineTo(dx + 2.6f * unit, dy + 20f * unit)
                    close()
                }
                drawPath(triangle, tint)
                drawPath(triangle, tint, style = Stroke(width = 2f * unit, join = StrokeJoin.Round))
                drawLine(Color.White, p(12f, 9.2f), p(12f, 13.8f), strokeWidth = 1.9f * unit, cap = StrokeCap.Round)
                drawCircle(Color.White, radius = 1.1f * unit, center = p(12f, 16.9f))
            }
        }
    }
}

/** A four-pointed star with concave sides: Gemini's `sparkle`. */
private fun sparkle(center: Offset, radius: Float): Path = Path().apply {
    val k = radius * 0.18f
    val x = center.x
    val y = center.y
    moveTo(x, y - radius)
    quadraticTo(x + k, y - k, x + radius, y)
    quadraticTo(x + k, y + k, x, y + radius)
    quadraticTo(x - k, y + k, x - radius, y)
    quadraticTo(x - k, y - k, x, y - radius)
    close()
}

// MARK: Groups

internal class AIRowSlot(val divider: Dp, val key: Any?, val content: @Composable () -> Unit)

/** Collects the rows of one group so the lines between them can be drawn. */
internal class AISectionScope {
    internal val rows = mutableListOf<AIRowSlot>()

    /** [divider] is where the line under this row starts, from the card's leading edge. */
    fun row(divider: Dp = 20.dp, key: Any? = null, content: @Composable () -> Unit) {
        rows += AIRowSlot(divider, key, content)
    }
}

/** An inset-grouped `Section`: an optional title, rows on a card, an optional footer. */
@Composable
internal fun AISection(
    title: String? = null,
    footer: String? = null,
    warnFooter: Boolean = false,
    leading: Boolean = false,
    modifier: Modifier = Modifier,
    build: AISectionScope.() -> Unit
) {
    val rows = AISectionScope().apply(build).rows
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                // Measured against the iOS list: two groups with a title between
                // them are 43.7 points apart, the title's ink centred 29.9 below
                // the group above. SwiftUI sets the title of the first group
                // ([leading]) right under the navigation bar and leaves the list's
                // top margin between it and its card.
                modifier = Modifier
                    .then(if (leading) Modifier.offset(y = -AIListMetrics.topMargin) else Modifier)
                    .padding(start = 20.dp, end = 20.dp, top = 1.3.dp, bottom = 6.4.dp)
                    .semantics { heading() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (rows.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(aiGroupedColor())) {
                rows.forEachIndexed { index, slot ->
                    key(slot.key ?: index) {
                        slot.content()
                        if (index < rows.lastIndex) JerreaderRowSeparator(start = slot.divider)
                    }
                }
            }
        }
        if (footer != null) AISectionFooter(footer, warnFooter)
    }
}

@Composable
private fun AISectionFooter(text: String, warn: Boolean) {
    val color = if (warn) aiWarning() else MaterialTheme.colorScheme.onSurfaceVariant
    // iOS centres a one-line footer's ink 14 points under its card; with it a
    // card and the next group are 53.6 points apart.
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 5.3.dp, bottom = 12.3.dp),
        horizontalArrangement = Arrangement.spacedBy(7.6.dp)
    ) {
        if (warn) AIGlyphIcon(AIGlyph.EXCLAMATION_CIRCLE, color, Modifier.padding(top = 1.2.dp).size(15.6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

// MARK: Rows

@Composable
internal fun AIRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    padding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 4.75.dp),
    spacing: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = LocalAIRowHeight.current)
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}

/** A plain list `Button`: accent text, red when destructive, grey when disabled. */
@Composable
internal fun AIActionRow(title: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    AIRow(onClick = onClick, enabled = enabled) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> aiTertiary()
                destructive -> aiRed()
                else -> MaterialTheme.colorScheme.primary
            }
        )
    }
}

@Composable
internal fun AIChevron() {
    JerreaderRowChevron(aiTertiary())
}

/** A provider's mark: its colour with a letter, or a symbol for Gemini and a custom service. */
@Composable
internal fun ProviderIcon(kind: AIProviderKind, name: String?, size: Dp) {
    val tint = Color(0xFF000000L or kind.tint)
    val letter = if (kind.isCustom) name?.trim()?.firstOrNull()?.uppercase() else kind.monogram
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.27f))
            .background(Brush.verticalGradient(listOf(lerp(tint, Color.White, 0.16f), tint)))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center
    ) {
        if (letter != null) {
            // Sized in points, like the iOS mark, so a larger font scale does not overflow it.
            val fontSize = with(LocalDensity.current) { (size * 0.46f).toSp() }
            Text(letter, color = Color.White, fontSize = fontSize, lineHeight = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
        } else {
            AIGlyphIcon(
                if (kind == AIProviderKind.GEMINI) AIGlyph.SPARKLE else AIGlyph.SERVER,
                Color.White,
                Modifier.size(size * 0.52f)
            )
        }
    }
}

/** 正在用 / 推荐: a small accent capsule after a title. */
@Composable
internal fun AITagLabel(text: String) {
    val accent = MaterialTheme.colorScheme.primary
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.5.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = accent,
        maxLines = 1
    )
}

@Composable
internal fun ProviderRowLabel(
    kind: AIProviderKind,
    name: String?,
    title: String,
    detail: String?,
    modifier: Modifier = Modifier,
    detailColor: Color = Color.Unspecified,
    tag: String? = null
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        ProviderIcon(kind, name, 34.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (tag != null) AITagLabel(tag)
            }
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (detailColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else detailColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** A model id with its description underneath. */
@Composable
internal fun ModelLabel(
    id: String,
    detail: String?,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    tag: String? = null
) {
    Column(modifier.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                id,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyLarge,
                color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (tag != null) AITagLabel(tag)
        }
        if (!detail.isNullOrEmpty()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Title on the left, its value and a chevron on the right. */
@Composable
internal fun RowScope.AIValueRowContent(title: String, value: String, warn: Boolean = false) {
    Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
    Text(
        value,
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.bodyLarge,
        color = if (warn) aiWarning() else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    AIChevron()
}

/** A row that folds the rows after it, without indenting them. */
@Composable
internal fun AIDisclosureRow(title: String, expanded: Boolean, onToggle: () -> Unit, enabled: Boolean = true) {
    AIRow(
        modifier = Modifier.semantics { stateDescription = if (expanded) "已展开" else "已收起" },
        onClick = onToggle,
        enabled = enabled
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        // `chevron.down`, turned to point right while folded, centred 27.6
        // points in from the card's edge as on iOS.
        JerreaderRowChevron(
            aiTertiary(),
            Modifier.size(width = 15.3.dp, height = 13.dp),
            rotation = if (expanded) 90f else 0f,
            alignEnd = false
        )
    }
}

/** A row that opens a web page outside the app. */
@Composable
internal fun AIExternalLinkRow(title: String, url: String) {
    val handler = LocalUriHandler.current
    AIRow(onClick = { runCatching { handler.openUri(url) } }) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        AIGlyphIcon(AIGlyph.ARROW_UP_RIGHT, aiTertiary(), Modifier.size(15.dp))
    }
}

/** The header card of a provider or model page. */
@Composable
internal fun ProviderHeader(kind: AIProviderKind, name: String?, caption: String?, title: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        ProviderIcon(kind, name, 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 15.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A model with a check circle on the right, for choosing several at once. */
@Composable
internal fun AICheckRow(id: String, detail: String?, checked: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = LocalAIRowHeight.current)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ModelLabel(id, detail, Modifier.weight(1f))
        AIGlyphIcon(
            if (checked) AIGlyph.CHECK_CIRCLE_FILLED else AIGlyph.CIRCLE,
            if (checked) MaterialTheme.colorScheme.primary else aiTertiary(),
            Modifier.size(23.dp)
        )
    }
}

// MARK: Feedback

@Composable
internal fun AIErrorBanner(message: String) {
    // Red marks the state; the message stays in the primary colour so a long
    // provider message remains easy to read.
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(aiRed().copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AIGlyphIcon(AIGlyph.WARNING_FILLED, aiRed(), Modifier.padding(top = 1.dp).size(17.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The error and notice shown above a list. */
@Composable
internal fun AIFeedback(error: String?, notice: String?) {
    if (error == null && notice == null) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (error != null) AIErrorBanner(error)
        if (notice != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AIGlyphIcon(AIGlyph.CHECK_CIRCLE_FILLED, MaterialTheme.colorScheme.primary, Modifier.size(17.dp))
                Text(notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** The bar shown while a request to the provider is under way. */
@Composable
internal fun AIBusyBar(onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text("正在连接…", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            "取消",
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onCancel)
                .heightIn(min = 44.dp)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// MARK: Inputs

/** A borderless single-line field, like a `TextField` in a `Form`. */
@Composable
internal fun AITextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String = placeholder,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    val textColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = 36.dp).semantics { contentDescription = label },
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = keyboardType
        ),
        visualTransformation = visualTransformation,
        decorationBox = { input ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = aiTertiary(), maxLines = 1)
                }
                input()
            }
        }
    )
}

/** An API key field with a show/hide button. */
@Composable
internal fun AIAPIKeyField(key: String, onKeyChange: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var revealed by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AITextField(
            value = key,
            onValueChange = onKeyChange,
            placeholder = "API Key",
            modifier = Modifier.weight(1f),
            keyboardType = KeyboardType.Password,
            enabled = enabled,
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation()
        )
        Box(
            Modifier
                .size(44.dp)
                .semantics { contentDescription = if (revealed) "隐藏密钥" else "显示密钥" }
                .clickable(role = Role.Button) { revealed = !revealed },
            contentAlignment = Alignment.Center
        ) {
            AIGlyphIcon(
                if (revealed) AIGlyph.EYE_SLASH else AIGlyph.EYE,
                MaterialTheme.colorScheme.onSurfaceVariant,
                Modifier.size(21.dp)
            )
        }
    }
}

/** The key field as a whole row: it sits flush against the trailing edge. */
@Composable
internal fun AIAPIKeyRow(key: String, onKeyChange: (String) -> Unit, enabled: Boolean = true) {
    AIRow(padding = PaddingValues(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
        AIAPIKeyField(key, onKeyChange, Modifier.weight(1f), enabled)
    }
}

/** One text field filling a row. */
@Composable
internal fun AIFieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String = placeholder,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true
) {
    AIRow(padding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
        AITextField(value, onValueChange, placeholder, Modifier.weight(1f), label, keyboardType, enabled)
    }
}

/** `Picker` in a `Form`: the title, then the choice and up/down chevrons in the accent colour. */
@Composable
internal fun <T> AIMenuRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean = true
) {
    var open by remember { mutableStateOf(false) }
    val tint = if (enabled) MaterialTheme.colorScheme.primary else aiTertiary()
    Box(Modifier.fillMaxWidth()) {
        AIRow(onClick = { open = true }, enabled = enabled) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(
                options.firstOrNull { it.first == selected }?.second.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
                maxLines = 1
            )
            // 7 points after the value and 22 inside the card's edge, as on iOS.
            JerreaderUpDownChevron(tint, Modifier.size(width = 9.2.dp, height = 13.dp), endInset = 2.dp)
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp)) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            open = false
                            if (value != selected) onSelect(value)
                        },
                        trailingIcon = if (value == selected) {
                            { JerreaderGlyph(JerreaderIcon.CHECK, MaterialTheme.colorScheme.primary, Modifier.size(16.dp)) }
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }
}

/** The question-mark toolbar button that opens 说明. */
@Composable
internal fun AIHelpButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .semantics { contentDescription = "说明" }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        JerreaderGlyph(JerreaderIcon.QUESTION, MaterialTheme.colorScheme.primary, Modifier.size(22.dp))
    }
}

// MARK: Bars, pages and sheets

internal class AIBarAction(
    val title: String,
    val enabled: Boolean = true,
    val emphasized: Boolean = false,
    val onClick: () -> Unit
)

/** The inline navigation bar of a sheet: a leading action or back button, the title, a trailing action. */
@Composable
internal fun AISheetBar(
    title: String,
    leading: AIBarAction? = null,
    trailing: AIBarAction? = null,
    back: AIBarAction? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp)) {
        Text(
            title,
            modifier = Modifier.align(Alignment.Center).widthIn(max = 200.dp).semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(Modifier.align(Alignment.CenterStart)) {
            when {
                back != null -> Row(
                    Modifier
                        .clickable(role = Role.Button, onClick = back.onClick)
                        .heightIn(min = 44.dp)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    JerreaderGlyph(JerreaderIcon.CHEVRON_LEFT, MaterialTheme.colorScheme.primary, Modifier.size(22.dp))
                    Text(back.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
                leading != null -> AIBarButton(leading)
            }
        }
        Box(Modifier.align(Alignment.CenterEnd)) {
            when {
                trailingContent != null -> trailingContent()
                trailing != null -> AIBarButton(trailing)
            }
        }
    }
}

@Composable
private fun AIBarButton(action: AIBarAction) {
    Box(
        Modifier
            .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            action.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (action.emphasized) FontWeight.SemiBold else FontWeight.Normal,
            color = if (action.enabled) MaterialTheme.colorScheme.primary else aiTertiary(),
            maxLines = 1
        )
    }
}

/**
 * The scrolling list of a page or sheet: [top] stays put above it (feedback, a
 * busy bar), the groups scroll below with the inset-grouped margins.
 */
@Composable
internal fun AIListContent(
    modifier: Modifier = Modifier,
    rowHeight: Dp = AIListMetrics.rowHeight,
    sheet: Boolean = false,
    top: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val margin = aiPageMargin()
    val theme = MaterialTheme.typography
    // Lines keep SwiftUI's height even when Chinese stands alone on one, which
    // is what the group titles, footers and two-line rows were measured with.
    val typography = remember(theme) { theme.withSystemLineHeights(ExactSystemLineHeight) }
    CompositionLocalProvider(LocalAIRowHeight provides rowHeight) {
      MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = typography, shapes = MaterialTheme.shapes) {
        Column(
            modifier
                .fillMaxSize()
                .then(if (sheet) Modifier.navigationBarsPadding() else Modifier)
                .imePadding()
        ) {
            top()
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                        .padding(horizontal = margin)
                        .padding(top = AIListMetrics.topMargin, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    content = content
                )
            }
        }
      }
    }
}

private class SheetMemory<T> {
    var last: T? = null
}

/**
 * A sheet that slides up over the page beneath it. It keeps the last value
 * while it slides away, and the system back gesture closes it.
 */
@Composable
internal fun <T : Any> AISheetHost(value: T?, onDismiss: () -> Unit, content: @Composable (T) -> Unit) {
    val memory = remember { SheetMemory<T>() }
    if (value != null) memory.last = value
    val shown = memory.last
    val visible = value != null
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            // Swallows taps so the page beneath cannot be used while the sheet is up.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it }
        ) {
            if (shown != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})
                        .statusBarsPadding()
                        .padding(top = 10.dp)
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .clip(PageSheetShape)
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        // Composed before the content, so a back handler inside
                        // the sheet (a pushed page) is asked first.
                        if (visible) BackHandler(onBack = onDismiss)
                        content(shown)
                    }
                }
            }
        }
    }
}

/** Content with a sheet over it; the content is hidden from accessibility while the sheet is up. */
@Composable
internal fun <T : Any> AIWithSheet(
    sheet: T?,
    onDismiss: () -> Unit,
    sheetContent: @Composable (T) -> Unit,
    content: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (sheet != null) Modifier.clearAndSetSemantics {} else Modifier)) {
            content()
        }
        AISheetHost(sheet, onDismiss, sheetContent)
    }
}

/** A pushed settings page: the navigation bar, the app's tab bar, and an optional sheet. */
@Composable
internal fun AIPageFrame(
    title: String,
    backTitle: String,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    sheet: String? = null,
    onDismissSheet: () -> Unit = {},
    sheetContent: @Composable (String) -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = if (sheet != null) Modifier.clearAndSetSemantics {} else Modifier,
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { JerreaderNavigationBar(title, backTitle, onBack, trailing) },
            bottomBar = bottomBar,
            content = content
        )
        AISheetHost(sheet, onDismissSheet, sheetContent)
    }
}

/** 选择模型: the models the account returned, with a search field. */
@Composable
internal fun AIFetchedModelPicker(
    models: List<String>,
    kept: Set<String>,
    onClose: () -> Unit,
    choose: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val all = remember(models) { models.distinct() }
    val needle = query.trim()
    val shown = remember(all, needle) { if (needle.isEmpty()) all else all.filter { it.contains(needle, ignoreCase = true) } }
    val margin = aiPageMargin()
    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        AISheetBar("选择模型", trailing = AIBarAction("取消", onClick = onClose))
        Row(
            Modifier
                .padding(horizontal = margin)
                .padding(bottom = 6.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            JerreaderGlyph(JerreaderIcon.SEARCH, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(16.dp))
            AITextField(query, { query = it }, "搜索模型", Modifier.weight(1f))
        }
        val grouped = aiGroupedColor()
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = margin, end = margin, top = 12.dp, bottom = 28.dp)
        ) {
            item(key = "header") {
                Text(
                    "账户返回的模型",
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 6.dp).semantics { heading() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(grouped)) {
                        Text(
                            if (all.isEmpty()) "服务没有返回可用模型，可以手动填写模型 ID。" else "没有匹配的模型。",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            itemsIndexed(shown, key = { _, id -> "model:$id" }) { index, id ->
                val first = index == 0
                val last = index == shown.lastIndex
                val shape = RoundedCornerShape(
                    topStart = if (first) 10.dp else 0.dp,
                    topEnd = if (first) 10.dp else 0.dp,
                    bottomStart = if (last) 10.dp else 0.dp,
                    bottomEnd = if (last) 10.dp else 0.dp
                )
                val isKept = id in kept
                Column(Modifier.fillMaxWidth().clip(shape).background(grouped)) {
                    AIRow(
                        onClick = {
                            choose(id)
                            onClose()
                        },
                        enabled = !isKept
                    ) {
                        Text(
                            id,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isKept) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isKept) {
                            Text("已添加", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!last) JerreaderRowSeparator(start = 20.dp)
                }
            }
        }
    }
}

// MARK: Requests

/**
 * The two calls the AI pages make to a provider. Tests pass fakes so no
 * request leaves the device.
 */
internal class AIProviderRequests(
    val check: suspend (DirectAIConfiguration) -> Double,
    val models: suspend (baseURL: String, style: AIAPIStyle, key: String) -> List<String>
) {
    companion object {
        fun live(
            service: ConfiguredTranslationService,
            catalog: AIModelCatalogService = RemoteAIModelCatalogService()
        ): AIProviderRequests = AIProviderRequests(
            check = { service.testConnection(it) },
            models = { baseURL, style, key ->
                catalog.fetch(AIModelCatalogConfiguration.forProvider(baseURL, style, key)).models.map { it.id }
            }
        )

        /** What to show for a failed request: the provider's or the settings' own words. */
        fun message(error: Throwable): String = when (error) {
            is TranslationFailure, is AIModelCatalogException, is AIProviderSettingsError ->
                error.message?.takeIf { it.isNotBlank() } ?: FALLBACK
            else -> FALLBACK
        }

        fun seconds(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

        private const val FALLBACK = "连接失败，请检查网络、模型和密钥。"
    }
}
