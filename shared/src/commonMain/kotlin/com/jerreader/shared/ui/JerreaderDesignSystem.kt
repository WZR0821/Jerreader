package com.jerreader.shared.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

// Jerreader 2.0「纸墨」design system. The values come from
// docs/DESIGN_2.0.md, and iOS keeps the same ones in
// Jerreader/App/JerreaderDesignSystem.swift.

// region Tokens

object JerreaderSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object JerreaderRadius {
    val cover = 6.dp
    val small = 10.dp
    val control = 14.dp
    val card = 20.dp
    val hero = 26.dp
    val sheet = 28.dp
}

/** e1 cards, e2 hero cards and covers, e3 floating controls. */
enum class JerreaderElevation(val opacity: Float, val radius: Dp, val y: Dp) {
    CARD(0.06f, 8.dp, 4.dp),
    RAISED(0.12f, 16.dp, 8.dp),
    FLOATING(0.18f, 24.dp, 12.dp);

    /** Compose draws shadows from an elevation; this one gives about the iOS blur. */
    internal val elevation: Dp get() = radius / 2
}

/** The 2.0 motion table, the Compose half of `JerreaderMotion`. */
object JerreaderMotion {
    /** Pressing down: fast and flat. */
    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1500f)
    /** Letting go: a small overshoot. */
    fun <T> release(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 600f)
    fun <T> stateChange(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = 500f)
    fun <T> selection(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 700f)
    fun <T> reveal(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.86f, stiffness = 260f)
    fun <T> hero(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
    /** Reader bars: frequent, so short and without bounce. */
    fun <T> chrome(): FiniteAnimationSpec<T> = tween(durationMillis = 200, easing = FastOutSlowInEasing)
    fun <T> reduced(): FiniteAnimationSpec<T> = tween(durationMillis = 180)

    /** The delay of the `order`th item of a staggered first appearance. */
    fun revealDelayMillis(order: Int): Long = min(max(order, 0) * 40L, 300L)
}

/** The theme colours of the current composition. */
val jerreaderPalette: JerreaderColors
    @Composable get() = LocalJerreaderColors.current

// endregion

// region Modifiers

/**
 * A soft ink shadow in light mode; in dark mode no shadow but a 1dp white 6%
 * edge, which reads better on dark surfaces.
 */
fun Modifier.jerreaderElevation(
    level: JerreaderElevation,
    shape: Shape = RoundedCornerShape(JerreaderRadius.card),
    tint: Color? = null
): Modifier = this.composed {
    val colors = LocalJerreaderColors.current
    if (colors.isDark) {
        Modifier.border(1.dp, Color.White.copy(alpha = 0.06f), shape)
    } else {
        // Android multiplies this alpha by the platform's shadow alpha (about
        // 0.19 for the key light), so 5x lands near the iOS opacities.
        val shadowColor = (tint ?: jerreaderHex(0x1B2433)).copy(alpha = min(1f, level.opacity * 5f))
        Modifier.shadow(
            elevation = level.elevation,
            shape = shape,
            clip = false,
            ambientColor = shadowColor,
            spotColor = shadowColor
        )
    }
}

/** Paper with a hairline and an e1 shadow: the 2.0 card. */
fun Modifier.jerreaderPaperCard(
    shape: Shape = RoundedCornerShape(JerreaderRadius.card),
    raised: Boolean = false
): Modifier = this.composed {
    val colors = LocalJerreaderColors.current
    val shadow = if (colors.isDark) Modifier
    else Modifier.jerreaderElevation(if (raised) JerreaderElevation.RAISED else JerreaderElevation.CARD, shape)
    shadow
        .background(colors.paper, shape)
        .border(0.75.dp, colors.line, shape)
}

/**
 * Shrinks the view while [interactionSource] is pressed: down quickly and
 * flat, back up with a small spring, as on iOS.
 */
fun Modifier.jerreaderPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f
): Modifier = this.composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (pressed) JerreaderMotion.press() else JerreaderMotion.release(),
        label = "press"
    )
    Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * The staggered first appearance: fade in, rise 12dp and grow from 98%.
 * Runs once per composition of the item; the system's animator scale turns it
 * off along with every other animation.
 */
fun Modifier.jerreaderReveal(order: Int = 0, rise: Dp = 12.dp): Modifier = this.composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(JerreaderMotion.revealDelayMillis(order))
        progress.animateTo(1f, JerreaderMotion.reveal())
    }
    Modifier.graphicsLayer {
        val value = progress.value
        alpha = value.coerceIn(0f, 1f)
        translationY = (1f - value) * rise.toPx()
        val scale = 0.98f + 0.02f * value
        scaleX = scale
        scaleY = scale
    }
}

/** The app canvas: paper (dark: ink) with a faint static accent wash at the top left. */
fun Modifier.jerreaderCanvasBackground(): Modifier = this.composed {
    val colors = LocalJerreaderColors.current
    Modifier.drawWithContent {
        drawRect(colors.canvas)
        val radius = max(size.width * 1.1f, 1f)
        drawRect(
            Brush.radialGradient(
                colors = listOf(
                    colors.accent.copy(alpha = if (colors.isDark) 0.10f else 0.06f),
                    colors.accent.copy(alpha = 0f)
                ),
                center = Offset(size.width * 0.2f, 0f),
                radius = radius
            )
        )
        drawContent()
    }
}

/** The ink-and-accent ground of a hero card: 继续阅读, 今日练习. */
fun Modifier.jerreaderHeroBackground(
    shape: Shape = RoundedCornerShape(JerreaderRadius.hero)
): Modifier = this.composed {
    val colors = LocalJerreaderColors.current
    Modifier
        .jerreaderElevation(JerreaderElevation.RAISED, shape)
        .background(
            Brush.linearGradient(
                colors = listOf(colors.accentDeep, colors.ink),
                start = Offset.Zero,
                end = Offset.Infinite
            ),
            shape
        )
        .drawWithContent {
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0f)),
                    center = Offset.Zero,
                    radius = max(size.width * 0.9f, 1f)
                )
            )
            drawContent()
        }
}

/** Spine shade, gloss, a rounded edge and a two-layer shadow on a book cover. */
fun Modifier.jerreaderBookCover(
    shape: Shape = RoundedCornerShape(JerreaderRadius.cover),
    elevated: Boolean = true
): Modifier = this.composed {
    val colors = LocalJerreaderColors.current
    val base = if (elevated && !colors.isDark) {
        Modifier
            .shadow(JerreaderElevation.RAISED.elevation, shape, clip = false,
                ambientColor = jerreaderHex(0x1B2433, 0.6f), spotColor = jerreaderHex(0x1B2433, 0.6f))
            .shadow(1.dp, shape, clip = false,
                ambientColor = Color.Black.copy(alpha = 0.9f), spotColor = Color.Black.copy(alpha = 0.9f))
    } else if (colors.isDark) {
        // Drawn over the cover, so it stays visible on dark art.
        Modifier.border(1.dp, Color.White.copy(alpha = 0.06f), shape)
    } else {
        Modifier
    }
    base.clip(shape).drawWithContent {
        drawContent()
        val spine = max(size.width * 0.08f, 2f)
        drawRect(
            Brush.horizontalGradient(
                colors = listOf(Color.Black.copy(alpha = 0.22f), Color.Black.copy(alpha = 0f)),
                startX = 0f,
                endX = spine
            ),
            size = Size(spine, size.height)
        )
        drawRect(Color.White.copy(alpha = 0.12f), topLeft = Offset(spine, 0f), size = Size(1f, size.height))
        drawRect(
            Brush.linearGradient(
                colors = listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0f)),
                start = Offset.Zero,
                end = Offset(size.width / 2, size.height / 2)
            )
        )
    }
}

/** A one-off bounce of an icon, for a bookmark or star that was just set. */
fun Modifier.jerreaderBounce(trigger: Any?): Modifier = this.composed {
    val scale = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(trigger) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        scale.animateTo(1f, keyframes {
            durationMillis = 420
            1f at 0
            1.22f at 120
            0.96f at 240
            1f at 420
        })
    }
    Modifier.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

// endregion

// region Small pieces

/** A tinted rounded square with an icon: the leading mark of a tool row. */
@Composable
fun JerreaderIconTile(
    icon: JerreaderIcon,
    modifier: Modifier = Modifier,
    tint: Color = jerreaderPalette.accent,
    size: Dp = 36.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center
    ) {
        JerreaderGlyph(icon, tint, Modifier.size(size * 0.5f))
    }
}

/** The small accent label above a title, such as 继续阅读. */
@Composable
fun JerreaderEyebrow(text: String, modifier: Modifier = Modifier, tint: Color = jerreaderPalette.accent) {
    Text(
        text = text,
        modifier = modifier,
        color = tint,
        fontSize = 12.sp,
        lineHeight = 14.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        maxLines = 1
    )
}

/** A large rounded number that rolls when it changes, with an icon and a label. */
@Composable
fun JerreaderStatValue(
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    icon: JerreaderIcon? = null,
    tint: Color = jerreaderPalette.accent,
    foreground: Color = MaterialTheme.colorScheme.onSurface,
    secondary: Color = jerreaderPalette.secondaryText,
    size: Int = 22
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (icon != null) {
                JerreaderGlyph(icon, tint, Modifier.padding(bottom = 3.dp).size((size * 0.62f).dp))
            }
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically(JerreaderMotion.stateChange()) { if (up) it / 2 else -it / 2 } + fadeIn())
                        .togetherWith(slideOutVertically(JerreaderMotion.stateChange()) { if (up) -it / 2 else it / 2 } + fadeOut())
                },
                label = "stat"
            ) { shown ->
                Text(
                    text = shown.toString(),
                    color = foreground,
                    fontSize = size.sp,
                    lineHeight = (size * 1.2f).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Default,
                    maxLines = 1
                )
            }
            if (unit != null) {
                Text(
                    text = unit,
                    color = secondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
        Text(
            text = label,
            color = secondary,
            fontSize = 12.sp,
            lineHeight = 14.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A capsule progress bar that grows from zero to its value. */
@Composable
fun JerreaderProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    tint: Color = jerreaderPalette.accent,
    track: Color = tint.copy(alpha = 0.14f),
    height: Dp = 4.dp
) {
    val target = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val shown = remember { Animatable(0f) }
    LaunchedEffect(target) { shown.animateTo(target, JerreaderMotion.stateChange()) }
    Canvas(modifier.fillMaxWidth().height(height)) {
        val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        val value = shown.value.coerceIn(0f, 1f)
        if (value > 0f) {
            drawRoundRect(tint, size = Size(max(size.width * value, size.height), size.height), cornerRadius = radius)
        }
    }
}

/** A ring that grows from zero to its value. */
@Composable
fun JerreaderProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    tint: Color = jerreaderPalette.accent,
    track: Color = tint.copy(alpha = 0.16f),
    lineWidth: Dp = 6.dp
) {
    val target = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val shown = remember { Animatable(0f) }
    LaunchedEffect(target) { shown.animateTo(target, JerreaderMotion.hero()) }
    Canvas(modifier) {
        val stroke = lineWidth.toPx()
        val diameter = min(size.width, size.height) - stroke
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        drawArc(track, 0f, 360f, false, topLeft, Size(diameter, diameter), style = Stroke(stroke))
        drawArc(
            tint, -90f, 360f * shown.value.coerceIn(0f, 1f), false, topLeft, Size(diameter, diameter),
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
    }
}

// endregion

// region Segmented picker

data class JerreaderPillOption<T>(
    val value: T,
    val title: String,
    val icon: JerreaderIcon? = null,
    val testTag: String? = null
)

/**
 * The 2.0 segmented control: the selected option sits on an accent capsule
 * that slides between options, with a segment tick.
 */
@Composable
fun <T> JerreaderPillPicker(
    options: List<JerreaderPillOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
    minHeight: Dp = 36.dp
) {
    val colors = jerreaderPalette
    val haptics = LocalHapticFeedback.current
    val index = options.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.mutedSurface, CircleShape)
            .padding(4.dp)
    ) {
        val gap = 4.dp
        val count = max(options.size, 1)
        val itemWidth = (maxWidth - gap * (count - 1)) / count
        val offset by animateDpAsState(
            targetValue = (itemWidth + gap) * index,
            animationSpec = JerreaderMotion.selection(),
            label = "pill"
        )
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .offset { IntOffset(offset.roundToPx(), 0) }
                    .width(itemWidth)
                    .fillMaxHeight()
                    .jerreaderElevation(JerreaderElevation.CARD, CircleShape, colors.accent)
                    .background(colors.accent, CircleShape)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEach { option ->
                val isSelected = option.value == selected
                val source = remember { MutableInteractionSource() }
                val textColor = if (isSelected) colors.onAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                Box(
                    modifier = Modifier
                        .width(itemWidth)
                        .heightIn(min = minHeight)
                        .jerreaderPressScale(source)
                        .clickable(interactionSource = source, indication = null, role = Role.Tab) {
                            if (!isSelected) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onSelect(option.value)
                            }
                        }
                        .semantics { this.selected = isSelected }
                        .then(option.testTag?.let { Modifier.testTag(it) } ?: Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    if (stacked) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.padding(vertical = 4.dp)) {
                            option.icon?.let { JerreaderGlyph(it, textColor, Modifier.size(17.dp).jerreaderBounce(isSelected)) }
                            Text(option.title, color = textColor, fontSize = 12.sp, lineHeight = 14.5.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 6.dp)) {
                            option.icon?.let { JerreaderGlyph(it, textColor, Modifier.size(15.dp).jerreaderBounce(isSelected)) }
                            Text(option.title, color = textColor, fontSize = 15.sp, lineHeight = 18.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// endregion

// region Buttons

/** The one filled accent action of a screen: a capsule with a faint top light. */
@Composable
fun JerreaderPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: JerreaderIcon? = null,
    enabled: Boolean = true,
    height: Dp = 50.dp,
    fullWidth: Boolean = true
) {
    val colors = jerreaderPalette
    val source = remember { MutableInteractionSource() }
    val shape = CircleShape
    val base = if (fullWidth) modifier.fillMaxWidth() else modifier
    Box(
        modifier = base
            .heightIn(min = height)
            .jerreaderPressScale(source)
            .let { if (enabled && !colors.isDark) it.shadow(6.dp, shape, clip = false,
                ambientColor = colors.accent, spotColor = colors.accent) else it }
            .background(colors.accent.copy(alpha = if (enabled) 1f else 0.45f), shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0f))), shape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let { JerreaderGlyph(it, colors.onAccent, Modifier.size(18.dp)) }
            Text(text, color = colors.onAccent, fontSize = 17.sp, lineHeight = 20.5.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A quieter action: accent text on an accent tint. */
@Composable
fun JerreaderTintedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: JerreaderIcon? = null,
    enabled: Boolean = true,
    tint: Color = jerreaderPalette.accent,
    height: Dp = 44.dp,
    fullWidth: Boolean = false
) {
    val source = remember { MutableInteractionSource() }
    val base = if (fullWidth) modifier.fillMaxWidth() else modifier
    Box(
        modifier = base
            .heightIn(min = height)
            .jerreaderPressScale(source)
            .background(tint.copy(alpha = if (enabled) 0.12f else 0.06f), CircleShape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            icon?.let { JerreaderGlyph(it, tint, Modifier.size(16.dp)) }
            Text(text, color = tint.copy(alpha = if (enabled) 1f else 0.45f), fontSize = 15.sp, lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// endregion

// region Covers

object JerreaderGeneratedCoverPalette {
    /** 藍 臙脂 松葉 柿 鈍色 藤 黄土 青磁: top and bottom of each wash. */
    val palettes: List<Pair<Long, Long>> = listOf(
        0x2C4A73L to 0x172A45L,
        0x8C3B4AL to 0x4E1F2BL,
        0x2F5D4EL to 0x18352CL,
        0xB45E3AL to 0x6E3420L,
        0x4B5563L to 0x262C35L,
        0x6A5A9EL to 0x3A2F5EL,
        0xA5793AL to 0x5E4320L,
        0x4E8A86L to 0x284B49L
    )

    /**
     * Stable across launches and platforms: `h = h * 31 + byte` over the
     * UTF-8 bytes, wrapping at 32 bits, as the Swift version does.
     */
    fun paletteIndex(title: String): Int {
        var hash = 0u
        for (byte in title.encodeToByteArray()) {
            hash = hash * 31u + byte.toUByte().toUInt()
        }
        return (hash % palettes.size.toUInt()).toInt()
    }
}

/** The cover of a book that has none: an ink-wash pair chosen by the title. */
@Composable
fun JerreaderGeneratedCover(
    title: String,
    modifier: Modifier = Modifier,
    author: String? = null,
    compact: Boolean = false
) {
    val (top, bottom) = JerreaderGeneratedCoverPalette.palettes[JerreaderGeneratedCoverPalette.paletteIndex(title)]
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(jerreaderHex(top), jerreaderHex(bottom)))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
            modifier = Modifier.padding(if (compact) 10.dp else 16.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = if (compact) 14.sp else 19.sp,
                lineHeight = if (compact) 17.sp else 23.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(
                Modifier
                    .width(if (compact) 22.dp else 30.dp)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.5f))
            )
            if (!author.isNullOrBlank()) {
                Text(
                    text = author,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = if (compact) 10.sp else 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// endregion
