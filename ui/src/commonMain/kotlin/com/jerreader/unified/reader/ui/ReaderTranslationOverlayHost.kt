package com.jerreader.unified.reader.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.jerreader.unified.reader.geometry.ReaderPoint
import com.jerreader.unified.reader.geometry.ReaderRect
import com.jerreader.unified.reader.geometry.ReaderSize
import com.jerreader.unified.translation.TranslationDisplayMode
import com.jerreader.unified.reader.overlay.ReaderOverlayLayout
import com.jerreader.unified.reader.overlay.ReaderOverlayPlacement
import com.jerreader.unified.reader.overlay.ReaderOverlayRequest
import com.jerreader.unified.reader.overlay.ReaderTranslationLayoutPolicy
import kotlin.math.roundToInt

/**
 * Everything the placement policy needs to know about the current request.
 * Grouped into one object so a call site cannot supply half of it and silently
 * get a different rule than the other platform.
 */
data class ReaderOverlayOptions(
    val displayMode: TranslationDisplayMode = TranslationDisplayMode.NEAR_SELECTION,
    val isParagraph: Boolean = false,
    val isReflowable: Boolean = true,
    val preservesPublicationOrientation: Boolean = true,
    val publicationIsVertical: Boolean = false,
    val isLoading: Boolean = false,
    val isFailure: Boolean = false,
    val sourceCharacterCount: Int = 0,
    val translatedCharacterCount: Int = 0,
    /** Safe-area/chrome insets in dp, measured by the host app. */
    val topInset: Double = 58.0,
    val bottomInset: Double = 42.0
)

/**
 * Places the translation card so it never covers the text it is translating.
 *
 * All of the reasoning is in [ReaderOverlayPlacement] and
 * [ReaderTranslationLayoutPolicy]; this composable only supplies the viewport,
 * feeds the measured bounds back through the shared correction pass, and
 * handles dragging. Keeping it that thin is the point — the previous Android
 * layout expressed its rules *as* a chain of Compose modifiers, which is why
 * none of them could be tested or shared.
 *
 * The correction is applied as an absolute shift recomputed from the solved
 * position on every measurement, not accumulated, so a card cannot walk itself
 * across the page over successive recompositions.
 */
@Composable
fun ReaderTranslationOverlayHost(
    selectionAnchor: ReaderRect?,
    modifier: Modifier = Modifier,
    focusAnchor: ReaderRect? = null,
    options: ReaderOverlayOptions = ReaderOverlayOptions(),
    content: @Composable (ReaderOverlayLayout) -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val viewport = ReaderSize(
            width = with(density) { constraints.maxWidth.toDp().value.toDouble() },
            height = with(density) { constraints.maxHeight.toDp().value.toDouble() }
        )

        val usesSideAvoidance = ReaderTranslationLayoutPolicy.usesVerticalSideAvoidance(
            isReflowable = options.isReflowable,
            preservesPublicationOrientation = options.preservesPublicationOrientation,
            publicationIsVertical = options.publicationIsVertical,
            selectionFrame = selectionAnchor
        )
        val prefersExpanded = ReaderTranslationLayoutPolicy.prefersExpandedMaximumHeight(
            sourceCharacterCount = options.sourceCharacterCount,
            translatedCharacterCount = options.translatedCharacterCount,
            isParagraph = options.isParagraph
        )
        val cardWidth = ReaderTranslationLayoutPolicy.preferredCardWidth(
            viewportWidth = viewport.width,
            translatedCharacterCount = options.translatedCharacterCount,
            isLoading = options.isLoading,
            isFailure = options.isFailure,
            usesVerticalSideAvoidance = usesSideAvoidance
        )
        val maximumHeight = ReaderTranslationLayoutPolicy.preferredMaximumCardHeight(
            viewportSize = viewport,
            prefersExpanded = prefersExpanded
        )
        val gap = ReaderTranslationLayoutPolicy.selectionGap(
            isParagraph = options.isParagraph,
            usesVerticalSideAvoidance = usesSideAvoidance
        )
        val horizontalInset = ReaderTranslationLayoutPolicy.horizontalInset(usesSideAvoidance)

        // Measured height feeds the *next* solve; the solver picks its region
        // from the free space around the selection, so a growing card resizes
        // in place instead of flipping sides.
        var measuredHeight by remember(selectionAnchor) {
            mutableStateOf(ReaderTranslationLayoutPolicy.MINIMUM_CARD_HEIGHT)
        }
        var correction by remember(selectionAnchor) { mutableStateOf(ReaderPoint.Zero) }
        var drag by remember(selectionAnchor) { mutableStateOf(ReaderPoint.Zero) }

        val layout = ReaderOverlayPlacement.solve(
            ReaderOverlayRequest(
                selectionFrame = selectionAnchor,
                viewportSize = viewport,
                cardSize = ReaderSize(
                    cardWidth,
                    measuredHeight.coerceAtLeast(ReaderTranslationLayoutPolicy.MINIMUM_CARD_HEIGHT)
                ),
                horizontalAvoidanceFrame = if (usesSideAvoidance) focusAnchor else null,
                topInset = options.topInset,
                bottomInset = options.bottomInset,
                horizontalInset = horizontalInset,
                gap = gap,
                minimumCardHeight = ReaderTranslationLayoutPolicy.MINIMUM_CARD_HEIGHT,
                preferredMaximumCardHeight = maximumHeight,
                prefersTop = options.displayMode == TranslationDisplayMode.TOP_BANNER &&
                    !usesSideAvoidance,
                prefersHorizontalAvoidance = usesSideAvoidance || prefersExpanded,
                minimumHorizontalCardWidth =
                    ReaderTranslationLayoutPolicy.minimumHorizontalCardWidth(usesSideAvoidance)
            )
        )

        val safeBounds = ReaderRect.fromEdges(
            left = horizontalInset,
            top = options.topInset,
            right = viewport.width - horizontalInset,
            bottom = viewport.height - options.bottomInset
        )

        Box(
            modifier = Modifier
                .widthIn(max = layout.cardWidth.dp)
                .heightIn(max = layout.maximumCardHeight.dp)
                .offset {
                    // Compose offsets from the top-left; the solver answers in
                    // centres, so half the card comes back off here.
                    with(density) {
                        IntOffset(
                            x = ((layout.position.x - layout.cardWidth / 2.0 +
                                correction.x + drag.x).dp.toPx()).roundToInt(),
                            y = ((layout.position.y - measuredHeight / 2.0 +
                                correction.y + drag.y).dp.toPx()).roundToInt()
                        )
                    }
                }
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInParent()
                    val height = with(density) { bounds.height.toDp().value.toDouble() }
                    if (kotlin.math.abs(height - measuredHeight) > 0.5) {
                        measuredHeight = height
                    }

                    // Once the reader has dragged the card, where it sits is
                    // their decision. Correcting a dragged card pushes it back
                    // off the sentence the moment they park it there, and the
                    // drag can never win because every recomposition re-applies
                    // the shift.
                    if (drag != ReaderPoint.Zero) {
                        if (correction != ReaderPoint.Zero) correction = ReaderPoint.Zero
                        return@onGloballyPositioned
                    }

                    val measuredFrame = with(density) {
                        ReaderRect.fromEdges(
                            left = bounds.left.toDp().value.toDouble(),
                            top = bounds.top.toDp().value.toDouble(),
                            right = bounds.right.toDp().value.toDouble(),
                            bottom = bounds.bottom.toDp().value.toDouble()
                        )
                    }
                    // Undo the correction already applied so the pass always
                    // scores the *solved* frame and converges on one answer.
                    val solvedFrame = measuredFrame.offset(-correction.x, -correction.y)
                    val shift = ReaderOverlayPlacement.correct(
                        measuredFrame = solvedFrame,
                        selectionFrame = selectionAnchor,
                        safeBounds = safeBounds,
                        gap = gap
                    )
                    val next = ReaderPoint(shift.dx, shift.dy)
                    if (next != correction) correction = next
                }
                .pointerInput(selectionAnchor) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val maxX = viewport.width * 0.45
                        val maxY = viewport.height * 0.35
                        drag = with(density) {
                            ReaderPoint(
                                x = (drag.x + dragAmount.x.toDp().value)
                                    .coerceIn(-maxX, maxX),
                                y = (drag.y + dragAmount.y.toDp().value)
                                    .coerceIn(-maxY, maxY)
                            )
                        }
                    }
                }
        ) {
            content(layout)
        }
    }
}
