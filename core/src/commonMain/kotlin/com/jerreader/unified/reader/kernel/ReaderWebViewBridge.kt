package com.jerreader.unified.reader.kernel

import com.jerreader.unified.reader.geometry.ReaderRect
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The seam between shared reader logic and each platform's native Readium
 * navigator.
 *
 * The rendering engine stays native on purpose. Readium's Kotlin and Swift
 * toolkits are the two mature EPUB engines, they track their platform's web
 * view closely, and a common-code reimplementation would be a downgrade. What
 * moves into shared code is everything *above* the engine — what to select,
 * what to paint, where to put the card — which is exactly the code that had
 * drifted.
 *
 * The callback signature (rather than a `suspend fun`) is deliberate: this
 * interface is implemented in Swift as well as Kotlin, and a completion handler
 * is what the Kotlin/Native Objective-C export makes natural on that side.
 * Kotlin callers use the [evaluate] extension and never see the callback.
 */
fun interface ReaderScriptCallback {
    fun onResult(value: String?)
}

interface ReaderWebViewBridge {
    /**
     * Runs [script] in the current spread's web view and hands back its result
     * as a string, or null when the script failed or returned nothing.
     *
     * Implementations must invoke [callback] **exactly once**, on the main
     * thread — including when the work is abandoned. [evaluate] suspends on this
     * callback, so an implementation that drops it on cancellation leaves the
     * whole selection sequence waiting forever.
     */
    fun evaluateJavaScript(script: String, callback: ReaderScriptCallback)

    /**
     * How the web view's CSS pixel space maps onto the overlay's own space.
     *
     * Called on the main thread: both implementations read live view geometry,
     * which is only valid there. Callers of [ReaderSelectionController] are
     * responsible for running it on a main-thread dispatcher.
     */
    fun contentGeometry(): ReaderContentGeometry
}

/**
 * The transform from CSS pixels to overlay coordinates.
 *
 * Both platforms need this and both got it subtly wrong in their own way. On
 * iOS, Readium's vertical layout keeps the web view full-screen and expresses
 * the top safe-area inset as a *negative* scroll offset that DOM rectangles know
 * nothing about; on Android the web view sits inside a padded container. Naming
 * the transform once means a selection can no longer land a column away from
 * the text on one platform only.
 *
 * @param scale CSS pixels to overlay points (Android density; 1.0 on iOS).
 * @param originX overlay-space x of the web view's content origin.
 * @param originY overlay-space y of the same, already compensated for any
 *   negative scroll offset the platform uses to express a safe-area inset.
 */
data class ReaderContentGeometry(
    val scale: Double = 1.0,
    val originX: Double = 0.0,
    val originY: Double = 0.0
) {
    /** A CSS-pixel rectangle from the page, in overlay coordinates. */
    fun toOverlay(rect: ReaderRect): ReaderRect = ReaderRect(
        x = rect.x * scale + originX,
        y = rect.y * scale + originY,
        width = rect.width * scale,
        height = rect.height * scale
    )

    /** The inverse, for turning a touch into a point the DOM probes understand. */
    fun toCssPixels(x: Double, y: Double): Pair<Double, Double> {
        val safeScale = if (scale == 0.0 || !scale.isFinite()) 1.0 else scale
        return (x - originX) / safeScale to (y - originY) / safeScale
    }
}

/** Suspending wrapper over [ReaderWebViewBridge.evaluateJavaScript]. */
suspend fun ReaderWebViewBridge.evaluate(script: String): String? =
    suspendCancellableCoroutine { continuation ->
        evaluateJavaScript(script) { value ->
            if (continuation.isActive) continuation.resume(value)
        }
    }
