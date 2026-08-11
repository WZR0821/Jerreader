package com.jerreader.android.reader

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import com.github.barteksc.pdfviewer.PDFView
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.jerreader.android.translation.AndroidTranslationSettingsStore
import com.jerreader.unified.domain.LanguageCode
import com.jerreader.unified.translation.QuickTranslationUnit
import com.jerreader.unified.translation.ReaderCrossPageContextBuilder
import com.jerreader.unified.translation.ReaderCrossPageTextFragment
import com.jerreader.unified.translation.ReaderTextNormalizer
import com.jerreader.unified.translation.TranslationFailure
import com.jerreader.unified.translation.TranslationInputPolicy
import com.jerreader.unified.translation.TranslationRequest
import com.jerreader.unified.translation.TranslationService
import com.jerreader.unified.ui.TranslationCardState
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    private val onStateChanged: (TranslationCardState) -> Unit,
    private val onHapticFeedback: (() -> Unit)? = null,
    private val onPreviousPageRequested: (() -> Unit)? = null,
    private val onNextPageRequested: (() -> Unit)? = null,
    private val onHighlightChanged: ((List<RectF>) -> Unit)? = null,
    private val onSelectionInvalidated: (() -> Unit)? = null,
    /** A tap on no recognised text: the reader chrome toggles instead. */
    private val onContentTap: (() -> Unit)? = null,
    private val timeSource: () -> Long = System::currentTimeMillis
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
    /** Prevents an uncancellable OCR/network result from replacing a newer tap. */
    private var requestToken = 0L
    private var lastTapPoint: PointF? = null
    private var lastSelectionGeometry: RectF? = null
    private val lastRetryAtMillis = mutableMapOf<String, Long>()
    private val ocrCache = linkedMapOf<OcrCacheKey, PageOcr>()

    private data class OcrCacheKey(
        val pageIndex: Int,
        val viewWidth: Int,
        val viewHeight: Int,
        val language: LanguageCode?
    )

    private data class PageOcr(
        val text: Text,
        val width: Int,
        val height: Int
    )

    private data class ExtractedText(
        val text: String,
        val bounds: RectF,
        /** Bounds in the PDF page's normalized coordinate space (0..1). */
        val normalizedBounds: RectF
    )

    private val inputListener = object : InputListener {
        override fun onTap(event: TapEvent): Boolean {
            val preferences = settings.preferences.value
            if (!preferences.quickTranslationEnabled) return false
            if (!preferences.disablesTapPageTurnsDuringQuickTranslation) {
                val width = navigator?.publicationView?.width?.takeIf { it > 0 } ?: 0
                if (width > 0 && event.point.x <= width * EDGE_PAGE_TURN_RATIO) {
                    invalidateForNewTap()
                    onPreviousPageRequested?.invoke()
                    return true
                }
                if (width > 0 && event.point.x >= width * (1f - EDGE_PAGE_TURN_RATIO)) {
                    invalidateForNewTap()
                    onNextPageRequested?.invoke()
                    return true
                }
            }
            invalidateForNewTap()
            lastTapPoint = PointF(event.point.x, event.point.y)
            requestToken++
            job?.cancel()
            job = scope.launch { translateAt(event.point) { onContentTap?.invoke() } }
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
        requestToken++
        lastRequest = null
        lastTapPoint = null
        lastSelectionGeometry = null
        onHighlightChanged?.invoke(emptyList())
    }

    fun retry(): Boolean {
        val request = lastRequest ?: return false
        val key = retryKey(request)
        val now = timeSource()
        val previous = lastRetryAtMillis[key]
        if (previous != null && now - previous < RETRY_COOLDOWN_MILLIS) return false
        lastRetryAtMillis[key] = now
        if (lastRetryAtMillis.size > RETRY_KEY_LIMIT) {
            lastRetryAtMillis.minByOrNull { it.value }?.key?.let(lastRetryAtMillis::remove)
        }
        job?.cancel()
        job = scope.launch { translate(request) }
        return true
    }

    /** Reuses the normal PDF request path for a locally completed cross-page segment. */
    suspend fun translateExpanded(request: TranslationRequest): TranslationCardState {
        invalidateForNewTap()
        val token = ++requestToken
        return translate(request, token)
    }

    suspend fun translateAt(
        point: PointF,
        /**
         * The tap landed on no recognised text — the page margin, a figure, a
         * blank area. Mirrors the EPUB controller so both reader types toggle
         * the chrome on a tap that means nothing else.
         */
        onNoTextAtPoint: (() -> Unit)? = null
    ): TranslationCardState? {
        val token = ++requestToken
        val navigator = navigator ?: return null
        lastTapPoint = PointF(point.x, point.y)
        val pageIndex = currentPageIndex().coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
        val pageRect = withContext(Dispatchers.Main.immediate) {
            pageRectFor(pageIndex, navigator.publicationView.width, navigator.publicationView.height)
        } ?: return null
        val extracted = extractText(
            pageIndex = pageIndex,
            point = point,
            viewWidth = navigator.publicationView.width,
            viewHeight = navigator.publicationView.height,
            pageRect = pageRect,
            unit = settings.preferences.value.quickTranslationUnit
        ) ?: run {
            onNoTextAtPoint?.invoke()
            return null
        }
        lastSelectionGeometry = RectF(extracted.normalizedBounds)
        onHighlightChanged?.invoke(listOf(extracted.bounds))
        val preferences = settings.preferences.value
        val request = TranslationRequest(
            text = extracted.text,
            sourceLanguage = preferences.sourceChoice.language ?: languageHint(),
            targetLanguage = preferences.targetLanguage
        )
        return translate(request, token)
    }

    fun focusPoint(): PointF? = lastTapPoint?.let { PointF(it.x, it.y) }

    /** Returns the last OCR selection in page-normalized coordinates. */
    fun selectionGeometry(): RectF? = lastSelectionGeometry?.let(::RectF)

    /** Maps a normalized page rectangle into the current PDF navigator view. */
    suspend fun mapPageGeometryToView(
        geometry: RectF,
        pageIndex: Int,
        viewWidth: Int,
        viewHeight: Int
    ): RectF? {
        if (viewWidth <= 0 || viewHeight <= 0 || pageIndex !in 0 until renderer.pageCount) {
            return null
        }
        val pageRect = withContext(Dispatchers.Main.immediate) {
            pageRectFor(pageIndex, viewWidth, viewHeight)
        } ?: run {
            val dimensions = withContext(Dispatchers.IO) {
                runCatching {
                    renderer.openPage(pageIndex).use { page -> page.width to page.height }
                }.getOrNull()
            } ?: return null
            fittedPageRect(
                dimensions.first.toFloat(),
                dimensions.second.toFloat(),
                viewWidth.toFloat(),
                viewHeight.toFloat()
            )
        }
        return RectF(
            pageRect.left + geometry.left.coerceIn(0f, 1f) * pageRect.width(),
            pageRect.top + geometry.top.coerceIn(0f, 1f) * pageRect.height(),
            pageRect.left + geometry.right.coerceIn(0f, 1f) * pageRect.width(),
            pageRect.top + geometry.bottom.coerceIn(0f, 1f) * pageRect.height()
        )
    }

    fun clearSelection() {
        job?.cancel()
        job = null
        requestToken++
        lastRequest = null
        lastTapPoint = null
        lastSelectionGeometry = null
        onHighlightChanged?.invoke(emptyList())
    }

    /** Invalidates only the transient PDF translation state for a new tap. */
    private fun invalidateForNewTap() {
        val hadVisibleState = lastRequest != null || lastSelectionGeometry != null
        job?.cancel()
        job = null
        requestToken++
        lastRequest = null
        lastTapPoint = null
        lastSelectionGeometry = null
        onHighlightChanged?.invoke(emptyList())
        if (hadVisibleState) onSelectionInvalidated?.invoke()
    }

    /**
     * Builds a bounded window from the previous, current and next page so a
     * sentence broken by a page break can be completed before translating.
     * Neighbouring pages are tagged with their own identity, so the shared
     * builder can refuse text that does not belong to the same document flow.
     */
    suspend fun crossPageContext(sourceText: String): String? {
        val source = ReaderTextNormalizer.normalized(sourceText)
        if (source.isEmpty() || renderer.pageCount <= 0) return null
        val pageIndex = currentPageIndex().coerceIn(0, renderer.pageCount - 1)
        val documentIdentity = "pdf"
        val current = recognizePage(pageIndex) ?: return null
        val previous = if (pageIndex > 0) recognizePage(pageIndex - 1) else null
        val next = if (pageIndex + 1 < renderer.pageCount) recognizePage(pageIndex + 1) else null
        return ReaderCrossPageContextBuilder.context(
            sourceText = source,
            currentResourceIdentifier = documentIdentity,
            currentChapterIdentifier = null,
            previousPage = previous?.let {
                ReaderCrossPageTextFragment(documentIdentity, null, it)
            },
            localContext = current,
            nextPage = next?.let {
                ReaderCrossPageTextFragment(documentIdentity, null, it)
            }
        )
    }

    private suspend fun recognizePage(pageIndex: Int): String? {
        val page = withContext(Dispatchers.IO) {
            runCatching { renderer.openPage(pageIndex).use { it.width to it.height } }.getOrNull()
        } ?: return null
        val ocr = ocrPage(pageIndex, page.first, page.second) ?: return null
        return ReaderTextNormalizer.normalized(ocr.text.text).takeIf(String::isNotEmpty)
    }

    private suspend fun ocrPage(
        pageIndex: Int,
        viewWidth: Int,
        viewHeight: Int
    ): PageOcr? = withContext(Dispatchers.Default) {
        if (viewWidth <= 0 || viewHeight <= 0 || renderer.pageCount <= 0) {
            return@withContext null
        }
        val key = OcrCacheKey(pageIndex, viewWidth, viewHeight, ocrLanguageHint())
        synchronized(ocrCache) { ocrCache[key] }?.let { return@withContext it }
        val rendered = withContext(Dispatchers.IO) {
            runCatching {
                renderer.openPage(pageIndex).use { page ->
                    val scale = min(
                        viewWidth.toFloat() / page.width.coerceAtLeast(1),
                        viewHeight.toFloat() / page.height.coerceAtLeast(1)
                    ).coerceAtLeast(0.25f)
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }.getOrNull()
        } ?: return@withContext null
        val result = try {
            val preferred = ocrLanguageHint()
            val candidates = when (preferred) {
                LanguageCode.JAPANESE -> listOf(LanguageCode.JAPANESE to japaneseRecognizer)
                LanguageCode.ENGLISH -> listOf(LanguageCode.ENGLISH to latinRecognizer)
                LanguageCode.CHINESE_SIMPLIFIED -> listOf(LanguageCode.JAPANESE to japaneseRecognizer)
                null -> listOf(
                    LanguageCode.JAPANESE to japaneseRecognizer,
                    LanguageCode.ENGLISH to latinRecognizer
                )
            }
            candidates.mapNotNull { (language, recognizer) ->
                runCatching {
                    val text = recognizer.process(InputImage.fromBitmap(rendered, 0)).await()
                    PageOcr(text = text, width = rendered.width, height = rendered.height)
                }.getOrNull()?.let { language to it }
            }.maxByOrNull { (language, page) -> ocrScore(page.text.text, language) }?.second
        } finally {
            rendered.recycle()
        }
        if (result != null) {
            synchronized(ocrCache) {
                ocrCache[key] = result
                while (ocrCache.size > OCR_CACHE_LIMIT) {
                    ocrCache.remove(ocrCache.entries.first().key)
                }
            }
        }
        result
    }

    private fun ocrLanguageHint(): LanguageCode? =
        settings.preferences.value.sourceChoice.language ?: languageHint()

    /** Chooses the OCR model that best matches the visible script when metadata is absent. */
    private fun ocrScore(value: String, language: LanguageCode?): Int {
        val cjk = value.count { it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff' }
        val latin = value.count { it in 'A'..'Z' || it in 'a'..'z' }
        return when (language) {
            LanguageCode.JAPANESE -> cjk * 3 + latin
            LanguageCode.ENGLISH,
            null -> latin * 2 + cjk
            else -> cjk + latin
        }
    }

    private suspend fun extractText(
        pageIndex: Int,
        point: PointF,
        viewWidth: Int,
        viewHeight: Int,
        pageRect: RectF,
        unit: QuickTranslationUnit
    ): ExtractedText? = withContext(Dispatchers.Default) {
        if (viewWidth <= 0 || viewHeight <= 0 || renderer.pageCount <= 0) return@withContext null
        val ocr = ocrPage(pageIndex, viewWidth, viewHeight) ?: return@withContext null
        if (!pageRect.contains(point.x, point.y)) return@withContext null
        val bitmapPoint = PointF(
            (point.x - pageRect.left) / pageRect.width() * ocr.width,
            (point.y - pageRect.top) / pageRect.height() * ocr.height
        )
        val selected = selectText(
            recognized = ocr.text,
            point = bitmapPoint,
            pageWidth = ocr.width.toFloat(),
            pageHeight = ocr.height.toFloat(),
            unit = unit
        )
            ?: return@withContext null
        val bounds = selected.bounds
        ExtractedText(
            text = selected.text,
            bounds = RectF(
                pageRect.left + bounds.left / ocr.width * pageRect.width(),
                pageRect.top + bounds.top / ocr.height * pageRect.height(),
                pageRect.left + bounds.right / ocr.width * pageRect.width(),
                pageRect.top + bounds.bottom / ocr.height * pageRect.height()
            ),
            normalizedBounds = RectF(
                (bounds.left / ocr.width).coerceIn(0f, 1f),
                (bounds.top / ocr.height).coerceIn(0f, 1f),
                (bounds.right / ocr.width).coerceIn(0f, 1f),
                (bounds.bottom / ocr.height).coerceIn(0f, 1f)
            )
        )
    }

    private fun selectText(
        recognized: Text,
        point: PointF,
        pageWidth: Float,
        pageHeight: Float,
        unit: QuickTranslationUnit
    ): ExtractedText? {
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
        val nearestBox = checkNotNull(nearest.second.boundingBox)
        val dx = when {
            point.x < nearestBox.left -> nearestBox.left - point.x
            point.x > nearestBox.right -> point.x - nearestBox.right
            else -> 0f
        }
        val dy = when {
            point.y < nearestBox.top -> nearestBox.top - point.y
            point.y > nearestBox.bottom -> point.y - nearestBox.bottom
            else -> 0f
        }
        val normalizedDistanceSquared =
            (dx / pageWidth).let { it * it } + (dy / pageHeight).let { it * it }
        if (normalizedDistanceSquared > MAX_LINE_HIT_DISTANCE_SQUARED) return null
        val blockText = nearest.first.text.trim()
        if (blockText.isEmpty()) return null
        val selected = if (unit == QuickTranslationUnit.PARAGRAPH) {
            blockText
        } else {
            sentenceContainingLine(blockText, nearest.second.text)
        }
        val validated = runCatching { TranslationInputPolicy.validate(selected) }.getOrNull()
            ?: return null
        val bounds = if (unit == QuickTranslationUnit.PARAGRAPH) {
            RectF().also { result ->
                nearest.first.lines.mapNotNull { it.boundingBox }.forEach { result.union(RectF(it)) }
            }
        } else {
            sentenceBounds(
                block = nearest.first,
                blockText = blockText,
                sentence = selected,
                fallback = nearestBox
            )
        }
        if (bounds.isEmpty) return null
        return ExtractedText(
            text = validated,
            bounds = bounds,
            normalizedBounds = RectF(
                (bounds.left / pageWidth).coerceIn(0f, 1f),
                (bounds.top / pageHeight).coerceIn(0f, 1f),
                (bounds.right / pageWidth).coerceIn(0f, 1f),
                (bounds.bottom / pageHeight).coerceIn(0f, 1f)
            )
        )
    }

    /** Maps a selected sentence back to only the OCR lines it occupies. */
    private fun sentenceBounds(
        block: Text.TextBlock,
        blockText: String,
        sentence: String,
        fallback: android.graphics.Rect
    ): RectF {
        val start = blockText.indexOf(sentence).takeIf { it >= 0 } ?: 0
        val end = (start + sentence.length).coerceAtMost(blockText.length)
        val result = RectF()
        var cursor = 0
        var included = false
        block.lines.forEach { line ->
            val lineText = line.text
            val lineStart = blockText.indexOf(lineText, cursor).takeIf { it >= 0 } ?: cursor
            val lineEnd = (lineStart + lineText.length).coerceAtMost(blockText.length)
            if (lineEnd > start && lineStart < end) {
                line.boundingBox?.let {
                    result.union(RectF(it))
                    included = true
                }
            }
            cursor = maxOf(cursor, lineEnd)
        }
        return if (included) result else RectF(fallback)
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

    private fun retryKey(request: TranslationRequest): String = buildString {
        append(currentPageIndex())
        append('|')
        append(ReaderTextNormalizer.normalized(request.text))
        append('|')
        append(request.sourceLanguage?.tag ?: "auto")
        append("->")
        append(request.targetLanguage.tag)
    }

    private suspend fun translate(
        request: TranslationRequest,
        token: Long = ++requestToken
    ): TranslationCardState {
        lastRequest = request
        if (token == requestToken) {
            publish(TranslationCardState.Loading(request.text))
        }
        return try {
            TranslationCardState.Success(
                request.text,
                withTimeout(TRANSLATION_TIMEOUT_MILLIS) {
                    withContext(Dispatchers.IO) { translationService.translate(request) }
                }
            ).also { if (token == requestToken) publish(it) }
        } catch (_: TimeoutCancellationException) {
            TranslationCardState.Failure(
                request.text,
                "翻译超过 30 秒未返回，请重试或更换翻译服务。"
            ).also { if (token == requestToken) publish(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: TranslationFailure) {
            TranslationCardState.Failure(
                request.text,
                error.message ?: "翻译失败。"
            ).also { if (token == requestToken) publish(it) }
        } catch (_: Exception) {
            TranslationCardState.Failure(
                request.text,
                "未能识别或翻译这一页，请稍后重试。"
            ).also { if (token == requestToken) publish(it) }
        }
    }

    private fun publish(state: TranslationCardState) {
        // Loading is emitted by the current request only; terminal states are
        // additionally checked at the call site so a late result cannot win.
        onStateChanged(state)
        if (state !is TranslationCardState.Loading &&
            settings.preferences.value.translationHapticsEnabled
        ) {
            onHapticFeedback?.invoke()
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

    /**
     * Returns the actual visible page rectangle from AndroidPdfViewer. Unlike
     * a view-fit rectangle this follows pinch zoom and scroll offsets, so OCR
     * hit testing and saved annotation markers remain aligned after a gesture.
     */
    private fun pageRectFor(pageIndex: Int, viewWidth: Int, viewHeight: Int): RectF? {
        val pdfView = actualPdfView() ?: return null
        val actualWidth = pdfView.width.takeIf { it > 0 } ?: viewWidth
        val actualHeight = pdfView.height.takeIf { it > 0 } ?: viewHeight
        if (actualWidth <= 0 || actualHeight <= 0 || pageIndex !in 0 until pdfView.pageCount) {
            return null
        }
        val pageSize = pdfView.getPageSize(pageIndex)
        val zoom = pdfView.zoom.takeIf { it.isFinite() && it > 0f } ?: return null
        val pageWidth = pageSize.width * zoom
        val pageHeight = pageSize.height * zoom
        if (pageWidth <= 0f || pageHeight <= 0f) return null
        val maxWidth = (0 until pdfView.pageCount)
            .map { pdfView.getPageSize(it).width }
            .filter { it > 0f }
            .maxOrNull()
            ?: pageSize.width
        val maxHeight = (0 until pdfView.pageCount)
            .map { pdfView.getPageSize(it).height }
            .filter { it > 0f }
            .maxOrNull()
            ?: pageSize.height
        val beforeLength = if (pdfView.isSwipeVertical) {
            (0 until pageIndex).sumOf { pdfView.getPageSize(it).height.toDouble() }
                .toFloat() * zoom + pageIndex * pdfView.spacingPx
        } else {
            (0 until pageIndex).sumOf { pdfView.getPageSize(it).width.toDouble() }
                .toFloat() * zoom + pageIndex * pdfView.spacingPx
        }
        val pageRect = if (pdfView.isSwipeVertical) {
            val left = pdfView.currentXOffset + (maxWidth * zoom - pageWidth) / 2f
            val top = pdfView.currentYOffset + beforeLength
            RectF(left, top, left + pageWidth, top + pageHeight)
        } else {
            val left = pdfView.currentXOffset + beforeLength
            val top = pdfView.currentYOffset + (maxHeight * zoom - pageHeight) / 2f
            RectF(left, top, left + pageWidth, top + pageHeight)
        }
        // TapEvent coordinates are relative to Readium's outer container while
        // AndroidPdfViewer may be nested one level below it.
        val root = navigator?.publicationView
        if (root != null) {
            val rootLocation = IntArray(2)
            val pdfLocation = IntArray(2)
            root.getLocationInWindow(rootLocation)
            pdfView.getLocationInWindow(pdfLocation)
            pageRect.offset(
                (pdfLocation[0] - rootLocation[0]).toFloat(),
                (pdfLocation[1] - rootLocation[1]).toFloat()
            )
        }
        return pageRect
    }

    /**
     * Readium exposes its PDF document as a container fragment. The actual
     * AndroidPdfViewer instance lives in that fragment's child view, so using
     * `publicationView` directly would silently fall back to a fit-to-screen
     * rectangle and lose pinch-zoom/scroll alignment.
     */
    private fun actualPdfView(): PDFView? {
        val root = navigator?.publicationView ?: return null
        return findPdfView(root)
    }

    private fun findPdfView(view: View): PDFView? {
        if (view is PDFView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findPdfView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    override fun close() {
        detach()
        synchronized(ocrCache) { ocrCache.clear() }
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
        const val TRANSLATION_TIMEOUT_MILLIS = 30_000L
        const val RETRY_COOLDOWN_MILLIS = 5_000L
        const val RETRY_KEY_LIMIT = 128
        const val OCR_CACHE_LIMIT = 8
        const val EDGE_PAGE_TURN_RATIO = 0.12f
        const val MAX_LINE_HIT_DISTANCE_SQUARED = 0.008f
    }
}
