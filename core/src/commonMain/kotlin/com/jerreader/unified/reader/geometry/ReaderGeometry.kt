package com.jerreader.unified.reader.geometry

import kotlin.math.max
import kotlin.math.min

/**
 * The geometry primitives every selection and overlay calculation is written
 * against.
 *
 * iOS expressed all of this in `CGRect`/`CGPoint` and Android in a mixture of
 * `RectF`, Compose `Rect` and `Dp`, which is why the two readers drifted apart:
 * a rule written in one type system could not be shared, so it was rewritten by
 * hand on the other side and diverged at the first bug fix. These types are
 * deliberately plain data classes so the placement solver stays a pure function
 * that both platforms — and `commonTest` — can call.
 *
 * Units are density-independent points (iOS points, Android dp) unless a call
 * site documents otherwise; selection rectangles arriving from a web view are in
 * CSS pixels and are converted at the boundary.
 */
data class ReaderPoint(val x: Double, val y: Double) {
    val isFinite: Boolean get() = x.isFinite() && y.isFinite()

    companion object {
        val Zero = ReaderPoint(0.0, 0.0)
    }
}

data class ReaderSize(val width: Double, val height: Double) {
    val isFinite: Boolean get() = width.isFinite() && height.isFinite()

    companion object {
        val Zero = ReaderSize(0.0, 0.0)
    }
}

data class ReaderRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
) {
    val left: Double get() = x
    val top: Double get() = y
    val right: Double get() = x + width
    val bottom: Double get() = y + height
    val midX: Double get() = x + width / 2.0
    val midY: Double get() = y + height / 2.0
    val center: ReaderPoint get() = ReaderPoint(midX, midY)
    val size: ReaderSize get() = ReaderSize(width, height)

    val isEmpty: Boolean get() = width <= 0.0 || height <= 0.0

    val isFinite: Boolean
        get() = x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite()

    /** A usable rectangle: finite, non-empty, and positively sized. */
    val isUsable: Boolean get() = isFinite && !isEmpty

    /** Normalises negative extents the way `CGRect.standardized` does. */
    fun standardized(): ReaderRect {
        val minX = min(x, x + width)
        val minY = min(y, y + height)
        return ReaderRect(minX, minY, kotlin.math.abs(width), kotlin.math.abs(height))
    }

    fun inset(dx: Double, dy: Double): ReaderRect = ReaderRect(
        x = x + dx,
        y = y + dy,
        width = width - dx * 2,
        height = height - dy * 2
    )

    fun offset(dx: Double, dy: Double): ReaderRect = ReaderRect(x + dx, y + dy, width, height)

    fun intersects(other: ReaderRect): Boolean =
        left < other.right && right > other.left &&
            top < other.bottom && bottom > other.top

    /** Null when the rectangles do not overlap, mirroring `CGRect.intersection`. */
    fun intersection(other: ReaderRect): ReaderRect? {
        val left = max(this.left, other.left)
        val top = max(this.top, other.top)
        val right = min(this.right, other.right)
        val bottom = min(this.bottom, other.bottom)
        if (right <= left || bottom <= top) return null
        return ReaderRect(left, top, right - left, bottom - top)
    }

    fun union(other: ReaderRect): ReaderRect {
        val left = min(this.left, other.left)
        val top = min(this.top, other.top)
        val right = max(this.right, other.right)
        val bottom = max(this.bottom, other.bottom)
        return ReaderRect(left, top, right - left, bottom - top)
    }

    /** Scales every edge, e.g. CSS pixels to device pixels. */
    fun scaled(factor: Double): ReaderRect =
        ReaderRect(x * factor, y * factor, width * factor, height * factor)

    companion object {
        val Zero = ReaderRect(0.0, 0.0, 0.0, 0.0)

        fun fromEdges(left: Double, top: Double, right: Double, bottom: Double): ReaderRect =
            ReaderRect(left, top, right - left, bottom - top)

        fun centered(center: ReaderPoint, size: ReaderSize): ReaderRect = ReaderRect(
            x = center.x - size.width / 2.0,
            y = center.y - size.height / 2.0,
            width = size.width,
            height = size.height
        )
    }
}

/** The union of a list of rectangles, or null when it holds nothing usable. */
fun List<ReaderRect>.unionOrNull(): ReaderRect? {
    val usable = filter { it.isUsable }
    if (usable.isEmpty()) return null
    return usable.drop(1).fold(usable.first()) { accumulated, rect -> accumulated.union(rect) }
}
