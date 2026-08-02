package com.jerreader.android.reader

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.jerreader.android.translation.AndroidTranslationSettingsStore
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.QuickTranslationUnit
import com.jerreader.shared.translation.TranslationFailure
import com.jerreader.shared.translation.TranslationInputPolicy
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationService
import com.jerreader.shared.ui.TranslationCardState
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.hypot
import kotlin.math.min

@OptIn(ExperimentalReadiumApi::class)
class PdfTapTranslationController(
    file: File,
    private val scope: CoroutineScope,
    private val translationService: TranslationService,
    private val settings: AndroidTranslationSettingsStore,
    private val languageHint: () -> LanguageCode?,
    private val currentPageIndex: () -> Int,
    private val paperModeEnabled: () -> Boolean,
    private val onStateChanged: (TranslationCardState) -> Unit
) : Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val japaneseRecognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build()
    )
    private var navigator: PdfNavigatorFragment<*, *>? = null
    private var job: Job? = null
    private var lastRequest: TranslationRequest? = null

    private val inputListener = object : InputListener {
        override fun onTap(event: TapEvent): Boolean {
            if (!settings.preferences.value.quickTranslationEnabled) return false
            job?.cancel()
            job = scope.launch { translateAt(event.point) }
            return true
        }
    }

    fun attach(navigator: PdfNavigatorFragment<*, *>) {
        detach()
        this.navigator = navigator
        navigator.addInputListener(inputListener)
    }

    fun detach() {
        navigator?.removeInputListener(inputListener)
        navigator = null
        job?.cancel()
        job = null
    }

    fun retry() {
        val request = lastRequest ?: return
        job?.cancel()
        job = scope.launch { translate(request) }
    }

    suspend fun translateAt(point: PointF): TranslationCardState? {
        val navigator = navigator ?: return null
        val pageIndex = currentPageIndex().coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
        val extracted = extractText(
            pageIndex = pageIndex,
            point = point,
            viewWidth = navigator.publicationView.width,
            viewHeight = navigator.publicationView.height,
            unit = settings.preferences.value.quickTranslationUnit
        ) ?: return null
        val preferences = settings.preferences.value
        val request = TranslationRequest(
            text = extracted,
            sourceLanguage = preferences.sourceChoice.language ?: languageHint(),
            targetLanguage = preferences.targetLanguage
        )
        return translate(request)
    }

    private suspend fun extractText(
        pageIndex: Int,
        point: PointF,
        viewWidth: Int,
        viewHeight: Int,
        unit: QuickTranslationUnit
    ): String? = withContext(Dispatchers.Default) {
        if (viewWidth <= 0 || viewHeight <= 0 || renderer.pageCount <= 0) return@withContext null
        val rendered = withContext(Dispatchers.IO) {
            renderer.openPage(pageIndex).use { page ->
                val scale = min(viewWidth.toFloat() / page.width, viewHeight.toFloat() / page.height)
                    .coerceAtLeast(0.25f)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
        try {
            val pageRect = fittedPageRect(
                rendered.width.toFloat(),
                rendered.height.toFloat(),
                viewWidth.toFloat(),
                viewHeight.toFloat()
            )
            if (!pageRect.contains(point.x, point.y)) return@withContext null
            val bitmapPoint = PointF(
                (point.x - pageRect.left) / pageRect.width() * rendered.width,
                (point.y - pageRect.top) / pageRect.height() * rendered.height
            )
            val recognizer = if (languageHint() == LanguageCode.JAPANESE) {
                japaneseRecognizer
            } else {
                latinRecognizer
            }
            val text = recognizer.process(InputImage.fromBitmap(rendered, 0)).await()
            selectText(text, bitmapPoint, rendered.width.toFloat(), unit)
        } finally {
            rendered.recycle()
        }
    }

    private fun selectText(
        recognized: Text,
        point: PointF,
        pageWidth: Float,
        unit: QuickTranslationUnit
    ): String? {
        val allLines = recognized.textBlocks.flatMap { block ->
            block.lines.map { line -> block to line }
        }.filter { it.second.boundingBox != null }
        val lines = if (paperModeEnabled()) {
            val leftColumn = point.x < pageWidth / 2f
            allLines.filter { (block, line) ->
                val blockBox = block.boundingBox
                val lineBox = line.boundingBox ?: return@filter false
                val spansMostOfPage = blockBox != null && blockBox.width() >= pageWidth * 0.72f
                spansMostOfPage || ((lineBox.centerX() < pageWidth / 2f) == leftColumn)
            }.ifEmpty { allLines }
        } else {
            allLines
        }
        val nearest = lines.minByOrNull { (_, line) ->
            val box = checkNotNull(line.boundingBox)
            val dx = when {
                point.x < box.left -> box.left - point.x
                point.x > box.right -> point.x - box.right
                else -> 0f
            }
            val dy = when {
                point.y < box.top -> box.top - point.y
                point.y > box.bottom -> point.y - box.bottom
                else -> 0f
            }
            hypot(dx, dy)
        } ?: return null
        val blockText = nearest.first.text.trim()
        if (blockText.isEmpty()) return null
        val selected = if (unit == QuickTranslationUnit.PARAGRAPH) {
            blockText
        } else {
            sentenceContainingLine(blockText, nearest.second.text)
        }
        return runCatching { TranslationInputPolicy.validate(selected) }.getOrNull()
    }

    private fun sentenceContainingLine(block: String, line: String): String {
        val focus = block.indexOf(line).takeIf { it >= 0 }?.plus(line.length / 2) ?: 0
        var start = focus.coerceIn(0, block.length)
        var end = start
        while (start > 0 && block[start - 1] !in SENTENCE_ENDINGS) start--
        while (end < block.length && block[end] !in SENTENCE_ENDINGS) end++
        if (end < block.length) end++
        return block.substring(start, end).trim().ifEmpty { line.trim() }
    }

    private suspend fun translate(request: TranslationRequest): TranslationCardState {
        lastRequest = request
        onStateChanged(TranslationCardState.Loading(request.text))
        return try {
            TranslationCardState.Success(
                request.text,
                translationService.translate(request)
            ).also(onStateChanged)
        } catch (error: CancellationException) {
            throw error
        } catch (error: TranslationFailure) {
            TranslationCardState.Failure(
                request.text,
                error.message ?: "翻译失败。"
            ).also(onStateChanged)
        } catch (_: Exception) {
            TranslationCardState.Failure(
                request.text,
                "未能识别或翻译这一页，请稍后重试。"
            ).also(onStateChanged)
        }
    }

    private fun fittedPageRect(
        pageWidth: Float,
        pageHeight: Float,
        viewWidth: Float,
        viewHeight: Float
    ): RectF {
        val scale = min(viewWidth / pageWidth, viewHeight / pageHeight)
        val width = pageWidth * scale
        val height = pageHeight * scale
        val left = (viewWidth - width) / 2f
        val top = (viewHeight - height) / 2f
        return RectF(left, top, left + width, top + height)
    }

    override fun close() {
        detach()
        latinRecognizer.close()
        japaneseRecognizer.close()
        renderer.close()
        descriptor.close()
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener { continuation.cancel() }
    }

    private companion object {
        val SENTENCE_ENDINGS = charArrayOf('。', '！', '？', '.', '!', '?')
    }
}
