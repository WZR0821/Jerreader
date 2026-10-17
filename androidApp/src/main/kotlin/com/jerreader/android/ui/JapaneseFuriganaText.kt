package com.jerreader.android.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jerreader.android.lexical.JapaneseReadingFormatter
import com.jerreader.android.lexical.OfflineDictionaryPackageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max

/** Ruby is drawn only in the translated card, never into Readium's document. */
@Composable
fun JapaneseFuriganaText(text: String, store: OfflineDictionaryPackageStore, modifier: Modifier = Modifier) {
    val segments by produceState(listOf(JapaneseReadingFormatter.Segment(text)), text, store) {
        value = try { withContext(Dispatchers.Default) { JapaneseReadingFormatter.segments(text, store.japaneseChinese) } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { listOf(JapaneseReadingFormatter.Segment(text)) }
    }
    val density = LocalDensity.current
    val size = with(density) { 17.sp.toPx() }
    val spacing = with(density) { 5f * density.density }
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val annotated = remember(segments) {
        val full = segments.joinToString("") { it.text }
        SpannableString(full).apply {
            var offset = 0
            for (segment in segments) {
                segment.reading?.let { setSpan(FuriganaSpan(it), offset, offset + segment.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                offset += segment.text.length
            }
        }
    }
    AndroidView(modifier = modifier, factory = { context -> TextView(context).apply {
        setPadding(0, 0, 0, 0); includeFontPadding = false
        setTextIsSelectable(true); setBackgroundColor(android.graphics.Color.TRANSPARENT)
    } }, update = { view ->
        view.setTextColor(foreground); view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        view.setLineSpacing(spacing, 1f)
        if (view.text != annotated) view.text = annotated
        view.contentDescription = text
    })
}

private class FuriganaSpan(private val reading: String) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val ruby = Paint(paint).apply { textSize = paint.textSize * .48f }
        fm?.let {
            paint.getFontMetricsInt(it)
            val extra = ceil(ruby.fontSpacing + paint.textSize * .10f).toInt()
            it.ascent -= extra; it.top -= extra
        }
        return ceil(max(paint.measureText(text, start, end), ruby.measureText(reading))).toInt()
    }
    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val ruby = Paint(paint).apply { textSize = paint.textSize * .48f }
        val baseWidth = paint.measureText(text, start, end)
        val rubyWidth = ruby.measureText(reading)
        val width = max(baseWidth, rubyWidth)
        canvas.drawText(text, start, end, x + (width - baseWidth) / 2, y.toFloat(), paint)
        canvas.drawText(reading, x + (width - rubyWidth) / 2, y + paint.fontMetrics.ascent - paint.textSize * .10f, ruby)
    }
}
