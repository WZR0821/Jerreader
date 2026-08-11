package com.jerreader.unified.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import com.jerreader.unified.reader.kernel.ReaderSelectionController
import com.jerreader.unified.library.ReaderAppearance
import com.jerreader.unified.reader.selection.ReaderSelectionVisualStyle
import platform.UIKit.UIViewController

/**
 * The Compose overlay as a `UIViewController`, for hosting above Readium's
 * navigator on iOS.
 *
 * This is the end state of the migration: one Compose layer paints the
 * selection and places the card on both platforms. Until the iOS app adopts it,
 * the Swift `ReaderSelectionHighlightView` and `TranslationOverlayPlacement`
 * shims call the same `:core` functions, so the *behaviour* is already shared —
 * this controller only removes the last duplicated view code.
 *
 * Note that Compose Multiplatform publishes no Intel simulator artifacts, so
 * this file cannot be built on an Intel Mac. Everything it calls lives in
 * `:core`, which can.
 */
fun ReaderOverlayViewController(
    controller: ReaderSelectionController,
    appearance: () -> ReaderAppearance,
    cardContent: @Composable (String) -> Unit
): UIViewController = ComposeUIViewController {
    val state by controller.state.collectAsState()
    val palette = ReaderSelectionVisualStyle.palette(appearance())

    Box(modifier = Modifier.fillMaxSize()) {
        ReaderSelectionHighlightLayer(
            tiles = state.tiles,
            palette = palette,
            modifier = Modifier.fillMaxSize(),
            speechHighlight = state.speechHighlight,
            writingMode = state.writingMode
        )

        val anchor = state.anchorFrame
        if (anchor != null) {
            ReaderTranslationOverlayHost(
                selectionAnchor = anchor,
                modifier = Modifier.fillMaxSize(),
                options = ReaderOverlayOptions(
                    translatedCharacterCount = state.text.length
                )
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    shadowElevation = 8.dp
                ) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        cardContent(state.text)
                    }
                }
            }
        }
    }
}

/** A default card body, so a host can adopt the overlay before wiring translation. */
@Composable
fun ReaderPlaceholderCard(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}
