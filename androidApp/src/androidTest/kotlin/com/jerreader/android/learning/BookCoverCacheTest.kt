package com.jerreader.android.learning

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.jerreader.android.library.BookCoverCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BookCoverCacheTest {
    @Test fun thumbnailsAreBoundedReusedAndInvalidatedAfterReplacement() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("cover-regression", ".png", context.cacheDir)
        fun write(width: Int, height: Int, color: Int) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
        BookCoverCache.clear()
        try {
            write(1280, 2560, Color.BLUE)
            val originalTime = file.lastModified()
            val first = checkNotNull(BookCoverCache.load(file))
            assertTrue(maxOf(first.width, first.height) <= 768)
            assertSame(first, BookCoverCache.load(file))
            write(384, 512, Color.RED)
            assertTrue(file.setLastModified(originalTime + 2000))
            val replacement = checkNotNull(BookCoverCache.load(file))
            assertNotSame(first, replacement)
            assertEquals(512, replacement.height)
            assertEquals(Color.RED, replacement.getPixel(0, 0))
            BookCoverCache.clear()
            assertNotSame(replacement, BookCoverCache.load(file))
        } finally {
            BookCoverCache.clear()
            file.delete()
        }
    }
}
