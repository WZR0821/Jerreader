package com.jerreader.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.LayoutScopeMarker
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The Android side of the iOS `OneScreenPage` and `FillStack`
 * (Jerreader/App/OneScreenLayout.swift). A page is exactly one screen tall
 * whenever its content can be: the rows share whatever height a taller phone
 * leaves over, so nothing scrolls and no gap is left above the tab bar. Content
 * that cannot be that short (large text, a short screen, the keyboard) keeps
 * its natural height and scrolls as usual.
 */

/** How a child of a [FillColumn] gives way when the room is too short. */
internal enum class FillYield {
    /** It keeps its height. */
    NO,

    /** It steps down to its least, together with every other such child, first. */
    FIRST,

    /** It gives up only what is still missing once the others have stepped down. */
    LAST
}

private class FillData(
    var stretch: Float = 0f,
    var yields: FillYield = FillYield.NO,
    /** [Dp.Unspecified]: as short as the child's own minimum intrinsic height. */
    var least: Dp = Dp.Unspecified
)

/** What a [FillColumn] child can say about the height it is handed. */
@LayoutScopeMarker
@Immutable
internal interface FillColumnScope {
    /**
     * This child may grow by up to [limit] when there is spare height; every
     * child that stretches grows by the same amount until it reaches its limit.
     * The child is handed its whole height and should centre or fill itself.
     */
    fun Modifier.stretch(limit: Dp = Dp.Infinity): Modifier

    /**
     * This child can be as short as [least], in a step: text that drops a
     * line, say. All such children step down together, before anything is
     * squeezed. Unspecified, the least is the child's minimum intrinsic
     * height, which for a nested [FillColumn] is the sum of its own leasts.
     */
    fun Modifier.shrinks(least: Dp = Dp.Unspecified): Modifier

    /**
     * This child can be any height down to [least]. It gives up only what is
     * still missing once the others have stepped down. Unspecified, the least
     * is as for [shrinks].
     */
    fun Modifier.squeezes(least: Dp = Dp.Unspecified): Modifier
}

private object FillColumnScopeInstance : FillColumnScope {
    override fun Modifier.stretch(limit: Dp): Modifier =
        this.then(FillDataElement(stretch = if (limit == Dp.Infinity) Float.POSITIVE_INFINITY else limit.value))

    override fun Modifier.shrinks(least: Dp): Modifier =
        this.then(FillDataElement(yields = FillYield.FIRST, least = least))

    override fun Modifier.squeezes(least: Dp): Modifier =
        this.then(FillDataElement(yields = FillYield.LAST, least = least))
}

/** Stretch is kept in dp; `null` fields leave what an earlier modifier set. */
private data class FillDataElement(
    val stretch: Float? = null,
    val yields: FillYield? = null,
    val least: Dp? = null
) : ModifierNodeElement<FillDataNode>() {
    override fun create() = FillDataNode(stretch, yields, least)

    override fun update(node: FillDataNode) {
        node.stretch = stretch
        node.yields = yields
        node.least = least
    }
}

private class FillDataNode(
    var stretch: Float?,
    var yields: FillYield?,
    var least: Dp?
) : Modifier.Node(), ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any {
        val data = parentData as? FillData ?: FillData()
        stretch?.let { data.stretch = it }
        yields?.let { data.yields = it }
        least?.let { data.least = it }
        return data
    }
}

/**
 * A page body that fills the height its parent gives it exactly when it can,
 * and scrolls only when it cannot. [tag] names it for tests.
 *
 * @param maxWidth the widest the column gets on a tablet; it is centred.
 * @param horizontalPadding the page margin on each side.
 */
@Composable
internal fun OneScreenPage(
    tag: String,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 720.dp,
    horizontalPadding: Dp = 0.dp,
    content: @Composable FillColumnScope.() -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val room = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
        val scroll = rememberScrollState()
        Box(
            Modifier
                .fillMaxSize()
                .testTag(tag)
                // One screen does not move; only a page that cannot fit scrolls.
                .verticalScroll(scroll, enabled = scroll.maxValue > 0),
            contentAlignment = Alignment.TopCenter
        ) {
            FillColumn(
                room = room,
                modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth().padding(horizontal = horizontalPadding),
                content = content
            )
        }
    }
}

/**
 * A vertical stack that hands spare height to the children that ask for it,
 * with [FillColumnScope.stretch]. When the height is too little, the children
 * that [FillColumnScope.shrinks] step down first and those that
 * [FillColumnScope.squeezes] make up what is still missing. When it cannot be
 * made that short, every child keeps its natural height.
 *
 * The height to fill is the one the parent bounds it to, as when it is nested
 * in a card that an outer stack hands a height; inside a scrolling page, whose
 * height is unbounded, it is [room] pixels.
 *
 * Each child is measured once, at the height it is handed; its natural height
 * comes from its maximum intrinsic height and its least from its minimum one,
 * so a child must not be a lazy list or another subcomposing layout. A stack's
 * own intrinsic heights are the sums of those, so an outer stack squeezes a
 * nested one no shorter than it can go.
 */
@Composable
internal fun FillColumn(
    modifier: Modifier = Modifier,
    room: Int = Constraints.Infinity,
    content: @Composable FillColumnScope.() -> Unit
) {
    val policy = remember(room) { FillColumnPolicy(room) }
    Layout(content = { FillColumnScopeInstance.content() }, modifier = modifier, measurePolicy = policy)
}

private class FillColumnPolicy(private val room: Int) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val available = if (constraints.hasBoundedHeight) constraints.maxHeight else room
        val heights = share(measurables, width, available)

        // Children meet on whole pixels, so no hairline shows between two
        // backgrounds. The last one ends where the stack does.
        val count = measurables.size
        val total = heights.sum().roundToInt()
        var edge = 0
        var sum = 0f
        val sizes = IntArray(count) { index ->
            sum += heights[index]
            val next = if (index == count - 1) total else min(sum.roundToInt(), total)
            val size = max(next - edge, 0)
            edge = next
            size
        }
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(Constraints(minWidth = width, maxWidth = width, minHeight = sizes[index], maxHeight = sizes[index]))
        }
        val height = placeables.sumOf { it.height }.coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(width, height) {
            var y = 0
            placeables.forEach { placeable ->
                placeable.place(0, y)
                y += placeable.height
            }
        }
    }

    /** The shortest the stack can be made: each child at its least. */
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        var total = 0f
        measurables.forEach { child ->
            total += leastHeight(child, fillData(child), child.maxIntrinsicHeight(width).toFloat(), width)
        }
        return ceil(total).toInt()
    }

    /** The stack at its natural height. */
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.sumOf { it.maxIntrinsicHeight(width) }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.maxOfOrNull { it.minIntrinsicWidth(Constraints.Infinity) } ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0
}

private fun fillData(child: IntrinsicMeasurable): FillData = child.parentData as? FillData ?: FillData()

/** How short [child] can be made; a child that does not yield keeps its [natural] height. */
private fun Density.leastHeight(child: IntrinsicMeasurable, data: FillData, natural: Float, width: Int): Float {
    if (data.yields == FillYield.NO) return natural
    val least = if (data.least.isSpecified) data.least.toPx() else child.minIntrinsicHeight(width).toFloat()
    return min(least, natural)
}

/** The height each child is handed when the stack fills [available] pixels. */
private fun Density.share(measurables: List<Measurable>, width: Int, available: Int): FloatArray {
    val count = measurables.size
    val data = measurables.map(::fillData)
    val natural = FloatArray(count) { measurables[it].maxIntrinsicHeight(width).toFloat() }
    val heights = natural.copyOf()
    if (available == Constraints.Infinity || count == 0) return heights

    var spare = available - heights.sum()
    if (spare < -0.5f) {
        // Too tall. The children that step down all do so together,
        // so rows of one group keep looking alike.
        for (index in 0 until count) {
            if (data[index].yields != FillYield.FIRST) continue
            val least = leastHeight(measurables[index], data[index], natural[index], width)
            spare += heights[index] - least
            heights[index] = least
        }
    }
    if (spare < -0.5f) {
        // Still too tall. The children that squeeze share what is missing.
        val give = FloatArray(count) { index ->
            if (data[index].yields != FillYield.LAST) return@FloatArray 0f
            max(heights[index] - leastHeight(measurables[index], data[index], natural[index], width), 0f)
        }
        val total = give.sum()
        if (total > 0.5f) {
            val part = min(-spare / total, 1f)
            for (index in 0 until count) {
                heights[index] -= give[index] * part
                spare += give[index] * part
            }
        }
    }
    if (spare < -0.5f) {
        // It cannot be one screen: everything keeps its height, and the page scrolls.
        natural.copyInto(heights)
        return heights
    }

    val open = FloatArray(count) { index ->
        val limit = data[index].stretch
        if (limit == Float.POSITIVE_INFINITY) limit else limit * density
    }
    var rounds = 0
    while (spare > 0.5f && rounds < 2 * count + 2) {
        rounds += 1
        val growing = (0 until count).filter { open[it] > 0.5f }
        if (growing.isEmpty()) break
        val share = min(spare / growing.size, growing.minOf { open[it] })
        for (index in growing) {
            heights[index] += share
            open[index] -= share
            spare -= share
        }
    }
    return heights
}
