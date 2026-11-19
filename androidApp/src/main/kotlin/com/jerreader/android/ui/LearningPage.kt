package com.jerreader.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jerreader.shared.ui.ExactSystemLineHeight
import com.jerreader.shared.ui.JerreaderElevation
import com.jerreader.shared.ui.JerreaderGlyph
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderLearningType
import com.jerreader.shared.ui.JerreaderMotion
import com.jerreader.shared.ui.JerreaderProgressBar
import com.jerreader.shared.ui.JerreaderRadius
import com.jerreader.shared.ui.jerreaderElevation
import com.jerreader.shared.ui.jerreaderPalette
import com.jerreader.shared.ui.jerreaderPaperCard
import com.jerreader.shared.ui.jerreaderReveal
import com.jerreader.shared.ui.plainSecondary
import com.jerreader.shared.ui.withSystemLineHeights
import kotlinx.coroutines.delay

/**
 * One scroll owner per tool, including large vocabulary collections: the iOS
 * `LearningPage` — spacing 16, padding h20, top 16, bottom 24, at most 720 wide.
 * The learning tab's canvas is painted by its scaffold. Pass a [state] held
 * above a pushed page to come back to the same scroll position, as a
 * navigation stack does.
 */
@Composable
fun LearningPage(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {
    LearningTypography {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 720.dp).fillMaxSize(),
                state = state,
                contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content
            )
        }
    }
}

/**
 * Matches SwiftUI body/headline/subheadline/caption metrics in the learning
 * tools, including SwiftUI's line height for lines of Chinese (without it a
 * practice tool row was 78 points tall against 71 on iOS).
 */
@Composable
fun LearningTypography(content: @Composable () -> Unit) {
    val theme = MaterialTheme.typography
    val base = remember(theme) { theme.withSystemLineHeights(ExactSystemLineHeight) }
    val body = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 20.5.sp, letterSpacing = 0.sp)
    MaterialTheme(typography = base.copy(
        bodyLarge = body, bodyMedium = body.copy(fontSize = 15.sp, lineHeight = 18.sp),
        bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 16.sp),
        titleMedium = body.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = body.copy(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = body.copy(fontSize = 22.sp, lineHeight = 26.5.sp, fontWeight = FontWeight.Bold),
        labelMedium = body.copy(fontSize = 12.sp, lineHeight = 14.5.sp)
    )) { CompositionLocalProvider(LocalTextStyle provides body, content = content) }
}

/** Native collection rows use body 17, subheadline 15 and title3 20. */
@Composable
fun LearningCollectionTypography(content: @Composable () -> Unit) {
    LearningTypography {
        val base = MaterialTheme.typography
        MaterialTheme(typography = base.copy(
            bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 18.sp)
        ), content = content)
    }
}

/** iOS `LearningCard`: VStack(12) on a paper card, padding 16, radius 20 (e2 when [raised]). */
@Composable
fun LearningCard(
    modifier: Modifier = Modifier,
    raised: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .jerreaderPaperCard(RoundedCornerShape(JerreaderRadius.card), raised = raised)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

enum class LearningButtonKind { PLAIN, FILLED, TINTED }

/** The iOS 4pt progress bar on its 14% track, growing with stateChange. */
@Composable
fun LearningProgress(value: Float, modifier: Modifier = Modifier, tint: Color = jerreaderPalette.accent) {
    JerreaderProgressBar(progress = value, modifier = modifier, tint = tint)
}

/**
 * The iOS toggle row: the title in body semibold and an accent switch, at least
 * 44 tall. The whole row toggles, as a SwiftUI `Toggle` does.
 */
@Composable
fun LearningToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = jerreaderPalette
    val off = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val knob by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = JerreaderMotion.selection(),
        label = "toggle-knob"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = JerreaderLearningType.body.copy(fontWeight = FontWeight.SemiBold)
        )
        Canvas(Modifier.size(51.dp, 31.dp)) {
            drawRoundRect(
                color = lerp(off, palette.accent, knob.coerceIn(0f, 1f)),
                cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
            )
            val radius = 13.5.dp.toPx()
            val inset = 2.dp.toPx()
            val x = inset + radius + (size.width - 2f * (inset + radius)) * knob
            drawCircle(Color.Black.copy(alpha = 0.06f), radius = radius + 0.5.dp.toPx(), center = Offset(x, size.height / 2f + 1.dp.toPx()))
            drawCircle(Color.White, radius = radius, center = Offset(x, size.height / 2f))
        }
    }
}

/**
 * iOS `LearningTextField`: body text on a muted 14-radius field, padding v10 h14
 * (leading 38 beside the optional 16pt icon at x 14), at least 44 tall.
 */
@Composable
fun LearningTextField(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: JerreaderIcon? = null,
    onValueChange: (String) -> Unit
) {
    val palette = jerreaderPalette
    val secondary = MaterialTheme.colorScheme.plainSecondary
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = title },
        textStyle = JerreaderLearningType.body.copy(color = MaterialTheme.colorScheme.onSurface),
        singleLine = true,
        cursorBrush = SolidColor(palette.accent),
        decorationBox = { input ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .background(palette.mutedSurface, RoundedCornerShape(JerreaderRadius.control)),
                contentAlignment = Alignment.CenterStart
            ) {
                if (icon != null) {
                    JerreaderGlyph(icon = icon, tint = secondary, modifier = Modifier.padding(start = 14.dp).size(16.dp))
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = if (icon != null) 38.dp else 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(text = title, style = JerreaderLearningType.body, color = palette.secondaryText.copy(alpha = .3f), maxLines = 1)
                    }
                    input()
                }
            }
        }
    )
}

/**
 * The iOS `TextEditor` of the learning editors: body text on a muted 14-radius
 * ground. [minHeight] is the editor's own frame, inside iOS's 8pt padding.
 */
@Composable
fun LearningTextEditor(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    minHeight: Dp = 120.dp,
    onValueChange: (String) -> Unit
) {
    val palette = jerreaderPalette
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = title },
        textStyle = JerreaderLearningType.body.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(palette.accent),
        decorationBox = { input ->
            // TextEditor's own inset (5 across, 8 down) inside the 8pt padding.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight + 16.dp)
                    .background(palette.mutedSurface, RoundedCornerShape(JerreaderRadius.control))
                    .padding(horizontal = 13.dp, vertical = 16.dp)
            ) { input() }
        }
    )
}

/**
 * The buttons of the learning tools (iOS `LearningActionStyle`). FILLED is the
 * page's one accent capsule (a white 10% top gradient, an accent shadow in
 * light mode, headline text), TINTED an accent 12% capsule (subheadline
 * semibold), PLAIN accent body text. Destructive turns any kind vermilion.
 * Capsules press to 0.97, plain text to 60% opacity; disabled is 45%.
 */
@Composable
fun LearningButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    kind: LearningButtonKind = LearningButtonKind.PLAIN,
    destructive: Boolean = false,
    compact: Boolean = false,
    fullWidth: Boolean = false,
    tint: Color? = null,
    height: Dp = if (compact) 34.dp else 44.dp,
    content: @Composable RowScope.() -> Unit
) {
    val palette = jerreaderPalette
    val color = tint ?: if (destructive) palette.vermilion else palette.accent
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (pressed) JerreaderMotion.press() else JerreaderMotion.release(),
        label = "learning-button-press"
    )
    val capsule = kind != LearningButtonKind.PLAIN
    val foreground = if (kind == LearningButtonKind.FILLED) palette.onAccent else color
    val style = when (kind) {
        LearningButtonKind.FILLED -> JerreaderLearningType.headline
        LearningButtonKind.TINTED -> JerreaderLearningType.subheadline.copy(fontWeight = FontWeight.SemiBold)
        LearningButtonKind.PLAIN -> JerreaderLearningType.body
    }
    val ground = when (kind) {
        LearningButtonKind.FILLED -> Modifier
            .then(
                if (enabled && !palette.isDark) Modifier.jerreaderElevation(JerreaderElevation.CARD, CircleShape, tint = color)
                else Modifier
            )
            .background(color, CircleShape)
            .background(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0f))),
                CircleShape
            )
        LearningButtonKind.TINTED -> Modifier.background(color.copy(alpha = color.alpha * 0.12f), CircleShape)
        LearningButtonKind.PLAIN -> Modifier
    }
    Row(
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .graphicsLayer {
                val scale = if (capsule) 1f - 0.03f * press else 1f
                scaleX = scale
                scaleY = scale
                alpha = (if (enabled) 1f else 0.45f) * (if (capsule) 1f else 1f - 0.4f * press)
            }
            .then(ground)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .heightIn(min = height)
            .padding(horizontal = if (capsule) 18.dp else 0.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        CompositionLocalProvider(LocalContentColor provides foreground, LocalTextStyle provides style) {
            content()
        }
    }
}

/** iOS `LearningActions`: side by side 8 apart when they fit, otherwise stacked. */
@Composable
fun LearningActions(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val gaps = gap * (placeables.size - 1).coerceAtLeast(0)
        val rowWidth = placeables.sumOf { it.width } + gaps
        if (rowWidth <= constraints.maxWidth) {
            val height = placeables.maxOfOrNull { it.height } ?: 0
            layout(rowWidth.coerceAtLeast(constraints.minWidth), height) {
                var x = 0
                placeables.forEach {
                    it.placeRelative(x, (height - it.height) / 2)
                    x += it.width + gap
                }
            }
        } else {
            val width = placeables.maxOfOrNull { it.width } ?: 0
            val height = placeables.sumOf { it.height } + gaps
            layout(width.coerceAtLeast(constraints.minWidth), height) {
                var y = 0
                placeables.forEach {
                    it.placeRelative(0, y)
                    y += it.height + gap
                }
            }
        }
    }
}

/** Subheadline secondary guidance at the top of a tool card. */
@Composable
fun LearningHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = JerreaderLearningType.subheadline,
        color = MaterialTheme.colorScheme.plainSecondary
    )
}

/**
 * iOS `Picker` in its menu style outside a list: the choice in accent body
 * beside up-down chevrons, 34 tall with the label set 12 in, pressed to 60%;
 * a paper menu checks the current choice. TalkBack hears
 * 「[description]：choice」, as VoiceOver hears the picker's title and value.
 */
@Composable
internal fun <T> LearningMenuPicker(
    selected: T,
    options: List<T>,
    description: String,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = jerreaderPalette
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (pressed) JerreaderMotion.press() else JerreaderMotion.release(),
        label = "menu-picker-press"
    )
    val current = label(selected)
    Box(modifier) {
        Row(
            modifier = Modifier
                .graphicsLayer { alpha = 1f - 0.4f * press }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.DropdownList,
                    onClick = { expanded = true }
                )
                .semantics { contentDescription = "$description：$current" }
                .heightIn(min = 34.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = current,
                modifier = Modifier.weight(1f, fill = false),
                color = palette.accent,
                style = JerreaderLearningType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            JerreaderGlyph(JerreaderIcon.CHEVRON_UP_DOWN, palette.accent, Modifier.size(12.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(JerreaderRadius.control),
            containerColor = palette.raisedPaper,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(0.75.dp, palette.line)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label(option),
                            style = JerreaderLearningType.body,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    leadingIcon = {
                        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                            if (option == selected) {
                                JerreaderGlyph(JerreaderIcon.CHECK, MaterialTheme.colorScheme.onSurface, Modifier.size(14.dp))
                            }
                        }
                    }
                )
            }
        }
    }
}

/** iOS `LearningStagger.rowLimit`. */
internal const val LEARNING_STAGGER_ROWS = 8

/**
 * iOS `learningStagger(order:active:)`: a row reveals (opacity, +12, 98%) with
 * the reveal spring, `order` × 40 ms late, only when it is one of the first
 * rows and the page appeared under 700 ms ago — never on scroll.
 */
internal fun Modifier.learningStagger(order: Int?, active: Boolean): Modifier = this.composed {
    val animate = remember { order != null && order < LEARNING_STAGGER_ROWS && active }
    if (animate) Modifier.jerreaderReveal(order ?: 0) else Modifier
}

/** True for the first 700 ms after the page appears (iOS `LearningStagger.window`). */
@Composable
internal fun rememberLearningStaggerWindow(): Boolean {
    var active by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(700)
        active = false
    }
    return active
}
