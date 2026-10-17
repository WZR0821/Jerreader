package com.jerreader.android.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Decoded thumbnails are shared across visible rows and screen revisits. */
object BookCoverCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val loading = Mutex()

    suspend fun load(file: File): Bitmap? = withContext(Dispatchers.IO) {
        loading.withLock {
            if (!file.isFile) return@withLock null
            val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
            cache.get(key)?.let { return@withLock it }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = coverSampleSize(bounds.outWidth, bounds.outHeight)
            })?.also { cache.put(key, it) }
        }
    }

    fun clear() = cache.evictAll()
}

internal fun coverSampleSize(width: Int, height: Int, maximum: Int = 768): Int {
    require(maximum > 0)
    var sample = 1
    while ((maxOf(width, height).toLong() + sample - 1) / sample > maximum && sample <= Int.MAX_VALUE / 2) sample *= 2
    return sample
}
