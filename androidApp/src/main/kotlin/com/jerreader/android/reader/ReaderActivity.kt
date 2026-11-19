package com.jerreader.android.reader

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.activity.ComponentDialog
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.material3.Text
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.commitNow
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.jerreader.android.JerreaderApplication
import com.jerreader.android.MainActivity
import com.jerreader.android.R
import com.jerreader.android.data.ReadingAnnotationEntity
import com.jerreader.android.data.ReadingBookmarkEntity
import com.jerreader.android.library.PublicationIntegrity
import com.jerreader.android.library.PublicationSnapshot
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanMessages
import com.jerreader.shared.domain.BookFormat
import com.jerreader.shared.library.LibraryBook
import com.jerreader.shared.library.ReaderAnnotationColor
import com.jerreader.shared.library.ReaderAppearance
import com.jerreader.shared.library.ReaderInterfaceDefaults
import com.jerreader.shared.library.ReaderLayoutMode
import com.jerreader.shared.library.ReaderSelectionVisualStyle
import com.jerreader.shared.library.ReaderTextOrientation
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.WordAnalysis
import com.jerreader.shared.lexical.WordExplanation
import com.jerreader.android.learning.LearningRecordRepository
import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.ContextExplanationService
import com.jerreader.shared.translation.ReaderCrossPageTranslationResolver
import com.jerreader.shared.translation.TranslationDisplayMode
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationResult
import com.jerreader.shared.learning.AdaptiveReadingAssistPolicy
import com.jerreader.shared.learning.ChapterClosurePolicy
import com.jerreader.shared.learning.ContinuationRecapPolicy
import com.jerreader.shared.learning.ReadingCompanionMode
import com.jerreader.shared.learning.ReadingConfusionMap
import com.jerreader.shared.learning.ReadingConfusionMapPolicy
import com.jerreader.shared.learning.ReadingErrorSignal
import com.jerreader.shared.translation.AIReadingLensMode
import com.jerreader.shared.translation.ReadingLensOptions
import com.jerreader.shared.ui.AIReadingLensChooser
import com.jerreader.shared.ui.JerreaderGlyph
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderTheme
import com.jerreader.shared.ui.ReaderChapterClosurePanel
import com.jerreader.shared.ui.ReaderCompanionPanel
import com.jerreader.shared.ui.ReaderAnnotationEditor
import com.jerreader.shared.ui.ReaderAnnotationItem
import com.jerreader.shared.ui.ReaderBookmarkItem
import com.jerreader.shared.ui.ReaderChapter
import com.jerreader.shared.ui.ReaderNavigationPanel
import com.jerreader.shared.ui.ReaderSearchResultItem
import com.jerreader.shared.ui.ReaderBottomChrome
import com.jerreader.shared.ui.ReaderFailureView
import com.jerreader.shared.ui.ReaderLoadingView
import com.jerreader.shared.ui.ReaderTopChrome
import com.jerreader.shared.ui.JerreaderElevation
import com.jerreader.shared.ui.JerreaderRadius
import com.jerreader.shared.ui.jerreaderElevation
import com.jerreader.shared.ui.jerreaderPalette
import com.jerreader.shared.ui.jerreaderReveal
import com.jerreader.shared.ui.TranslationCard
import com.jerreader.shared.ui.TranslationCardState
import com.jerreader.shared.ui.WordLookupCard
import com.jerreader.shared.ui.WordLookupCardState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.readium.adapter.pdfium.navigator.PdfiumDefaults
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse
import java.io.File

@OptIn(ExperimentalReadiumApi::class)
class ReaderActivity : AppCompatActivity() {
    private val graph
        get() = (application as JerreaderApplication).graph

    // Position and reading-time saving outlive rotation and never block the UI thread.
    private val session by viewModels<ReaderSessionViewModel> {
        ReaderSessionViewModel.factory(graph.repository, graph.applicationScope)
    }

    private lateinit var expectedSnapshot: PublicationSnapshot
    private var publication: Publication? = null
    private var book by mutableStateOf<LibraryBook?>(null)
    private var navigator: EpubNavigatorFragment? = null
    private var pdfNavigator: PdfNavigatorFragment<*, *>? = null
    private var basePreferences = EpubPreferences()
    private var latestLocator: Locator? = null
    private var latestLocatorJson: String? = null
    private var rootView: FrameLayout? = null
    private var tableOfContents = emptyList<ReaderChapter>()
    private val tableOfContentsLinks = linkedMapOf<String, Link>()
    private var readingOrder = emptyList<Link>()
    private var positions = emptyList<Locator>()
    private var pdfPublicationOpen = false
    private var wordInteractionController: ReaderWordInteractionController? = null
    private var tapTranslationController: ReaderTapTranslationController? = null
    private var pdfTranslationController: PdfTapTranslationController? = null
    private var annotationDialog: Dialog? = null
    private val searchLocators = linkedMapOf<String, Locator>()
    private var pendingAnnotation: PendingAnnotation? = null

    private var contextualLookupInput by mutableStateOf<com.jerreader.android.ui.ContextualLookupInput?>(null)
    internal var currentWordLookupState by mutableStateOf<WordLookupCardState?>(null)
    /** Key of the last word lookup this reader saved, see `recordReaderLookup`. */
    private var recordedReaderLookupKey: String? = null
        private set

    internal var currentTranslationState by mutableStateOf<TranslationCardState?>(null)
        private set

    internal var translationCardFrameForTesting: RectF? = null
        private set
    internal var translationViewportForTesting: RectF? = null
        private set
    internal var translationSourceFrameForTesting: RectF? = null
        private set
    private var currentTranslationLocatorJson: String? = null

    internal var currentAppearance by mutableStateOf(ReaderAppearance())
        private set

    private var bookmarks by mutableStateOf(emptyList<ReadingBookmarkEntity>())
    private var annotations by mutableStateOf(emptyList<ReadingAnnotationEntity>())
    private var currentProgress by mutableDoubleStateOf(0.0)
    private var isCurrentPositionBookmarked by mutableStateOf(false)
    private var currentChapterIndex by mutableIntStateOf(-1)
    private var currentChapterName by mutableStateOf("")
    private var searchQuery by mutableStateOf("")
    private var searchResults by mutableStateOf(emptyList<ReaderSearchResultItem>())
    private var isSearching by mutableStateOf(false)
    private var searchMessage by mutableStateOf<String?>(null)
    private var companionMode by mutableStateOf(ReadingCompanionMode.FICTION)
    /** A recap or a finished chapter is waiting in the companion panel. */
    private var hasCompanionUpdate by mutableStateOf(false)
    /** The chapter the reader last finished by reading on into the next one. */
    private var finishedChapterTitle: String? = null
    private var crossPageMessage by mutableStateOf<String?>(null)
    private var isExpandingCrossPage by mutableStateOf(false)
    private var anchorTopPx by mutableIntStateOf(0)
    private var anchorBottomPx by mutableIntStateOf(0)
    private var cardDragX by mutableFloatStateOf(0f)
    private var cardDragY by mutableFloatStateOf(0f)
    private var anchorLeftPx by mutableIntStateOf(0)
    private var anchorRightPx by mutableIntStateOf(0)
    // Quick EPUB selections include the paired furigana rects. Keep that
    // merged geometry as the popup anchor instead of replacing it with
    // Readium's base-only selection bounds on the next coroutine turn.
    private var hasSelectionAnchor by mutableStateOf(false)
    // A post-measure collision correction. The first placement is chosen from
    // the available room; this value handles real card height, padding and
    // font-scale differences that are not known during the initial measure.
    private var popupAvoidanceX by mutableFloatStateOf(0f)
    private var popupAvoidanceY by mutableFloatStateOf(0f)
    private var selectionRects by mutableStateOf(emptyList<RectF>())
    private var pdfAnnotationRects by mutableStateOf(emptyList<RectF>())
    private var floatingLayer: ComposeView? = null
    private var highlightLayer: ComposeView? = null
    private var locatorObservationJob: Job? = null
    private var writingModeJob: Job? = null
    private var readerContentLeftPx = 0
    private var readerContentTopPx = 0
    private var readerContentBottomPx = 0
    private var publicationWritingModeIsVertical by mutableStateOf(false)
    /** The EPUB navigator was built with `disablePageTurnsWhileScrolling`, see [epubConfiguration]. */
    private var scrollPageTurnsDisabled = false
    private var pdfGeometryView: View? = null
    private var pdfGeometryScrollListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var pdfGeometryLayoutListener: View.OnLayoutChangeListener? = null
    private var pdfGeometryTouchListener: View.OnTouchListener? = null

    // The two layouts, after iOS `EPUBReaderViewModel`: the standard one keeps
    // its bars and lays the page out between them; the immersive one starts
    // with the bars away and brings them back for a few seconds on request.
    private val layoutModeState = mutableStateOf(ReaderLayoutMode.STANDARD)
    private val layoutMode: ReaderLayoutMode get() = layoutModeState.value
    private val controlsVisibleState = mutableStateOf(true)
    private val controlsVisible: Boolean get() = controlsVisibleState.value
    private var chromeAutoHideJob: Job? = null
    private var isChromeAutoHideSuspended = false
    /** The right-edge "点句翻译已开启" reminder is still due (iOS `isQuickTranslationIndicatorVisible`). */
    private var isQuickTranslationIndicatorVisible by mutableStateOf(false)
    private var systemBarTopPx by mutableIntStateOf(0)
    private var systemBarBottomPx by mutableIntStateOf(0)
    /** Measured, including the system bar each bar paints behind. */
    private var topChromeHeightPx by mutableIntStateOf(0)
    private var bottomChromeHeightPx by mutableIntStateOf(0)
    private var topChromeView: View? = null
    private var bottomChromeView: View? = null
    private var revealTopView: View? = null
    private var revealBottomView: View? = null
    private var readerContainerView: View? = null

    /** iOS `isChromeVisible`: the standard layout always shows its bars. */
    internal val isChromeVisible: Boolean
        get() = layoutMode == ReaderLayoutMode.STANDARD || controlsVisible

    internal val layoutModeForTesting: ReaderLayoutMode get() = layoutMode

    /** Top and bottom of the area cards may use, in the floating layer's window coordinates. */
    internal var translationSafeAreaForTesting: RectF? = null
        private set

    private data class PopupShift(
        val dx: Float,
        val dy: Float
    )

    /**
     * Where the floating card was last laid out: its resting corner before the
     * drag and collision offsets, and the range its corner may take so the
     * whole card stays inside the safe area.
     */
    private class FloatingCardPlacement {
        var originX = 0f
        var originY = 0f
        var minX = 0f
        var maxX = 0f
        var minY = 0f
        var maxY = 0f
    }

    private val floatingCardPlacement = FloatingCardPlacement()

    private data class PendingAnnotation(
        val selectedText: String,
        val locator: Locator,
        val existingId: String? = null,
        val note: String = "",
        val color: String = "yellow",
        val geometryJson: String? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android may restore Navigator fragments before the publication is
        // reopened. A dummy factory makes restoration safe until we replace it.
        supportFragmentManager.fragmentFactory = if (looksLikePdf()) {
            PdfNavigatorFragment.createDummyFactory(pdfiumEngineProvider())
        } else {
            EpubNavigatorFragment.createDummyFactory()
        }
        super.onCreate(savedInstanceState)

        supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG)?.let { restored ->
            supportFragmentManager.commitNow { remove(restored) }
        }

        showRoot()
        lifecycleScope.launch { resolveAndOpenPublication() }
    }

    override fun onResume() {
        super.onResume()
        if (book != null && publication != null) session.recorder.startClock()
    }

    override fun onPause() {
        session.recorder.pause()
        super.onPause()
    }

    @SuppressLint("LogNotTimber")
    override fun onDestroy() {
        session.recorder.pause()
        writingModeJob?.cancel()
        writingModeJob = null
        locatorObservationJob?.cancel()
        locatorObservationJob = null
        navigator?.removeDecorationListener(annotationDecorationListener)
        removePdfGeometryListeners()
        wordInteractionController?.detach()
        wordInteractionController = null
        tapTranslationController?.detach()
        tapTranslationController = null
        pdfTranslationController?.close()
        pdfTranslationController = null
        annotationDialog?.dismiss()
        annotationDialog = null
        publication?.close()
        publication = null
        navigator = null
        pdfNavigator = null

        if (::expectedSnapshot.isInitialized) {
            val closedSnapshot = expectedSnapshot
            // The application scope survives Activity destruction; never hash an entire book on UI.
            graph.applicationScope.launch {
                if (!PublicationIntegrity.isUnchanged(closedSnapshot)) {
                    Log.e(INTEGRITY_TAG, "Readium session changed immutable publication bytes or mtime")
                }
            }
        }
        super.onDestroy()
    }

    private fun looksLikePdf(): Boolean {
        val declaredFormat = intent.getStringExtra(EXTRA_SOURCE_FORMAT)
        if (declaredFormat != null) return declaredFormat.equals(BookFormat.PDF.fileExtension, true)
        return intent.getStringExtra(EXTRA_PUBLICATION_PATH)?.endsWith(".pdf", true) == true
    }

    /** The format the loading card names, as iOS `BookFormat.displayName` (EPUB, PDF, DOCX, TXT). */
    private fun loadingFormatName(): String =
        (book?.sourceFormat ?: intent.getStringExtra(EXTRA_SOURCE_FORMAT))
            ?.takeIf(String::isNotBlank)
            ?.uppercase()
            ?: if (looksLikePdf()) "PDF" else "EPUB"

    private suspend fun resolveAndOpenPublication() {
        try {
            withTimeout(READER_OPEN_TIMEOUT_MILLIS) {
                resolveAndOpenPublicationWithinTimeout()
            }
        } catch (_: TimeoutCancellationException) {
            publication?.close()
            publication = null
            showError("打开出版物超过 25 秒，请检查文件是否完整后重试。")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publication?.close()
            publication = null
            showError(error.message ?: "无法打开该出版物。")
        }
    }

    private suspend fun resolveAndOpenPublicationWithinTimeout() {
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        val resolvedBook = bookId?.let { graph.repository.book(it) }
        book = resolvedBook
        resolvedBook?.let { opened ->
            session.recorder.begin(opened.id)
            companionMode = loadCompanionMode(opened.id)
            // A recap only helps someone coming back to a book.
            hasCompanionUpdate = opened.lastReadProgress > CONTINUATION_RECAP_MIN_PROGRESS
        }

        val snapshot = when {
            resolvedBook != null -> {
                val file = graph.publicationStore.resolvePublication(resolvedBook.publicationFileName)
                PublicationSnapshot(
                    path = file.absolutePath,
                    sha256 = resolvedBook.fingerprint,
                    lastModified = resolvedBook.publicationLastModified
                )
            }

            else -> intentSnapshot()
        }

        if (snapshot == null) {
            showError(if (bookId == null) "缺少出版物信息。" else "书架中找不到该书籍。")
            return
        }
        expectedSnapshot = snapshot
        if (!withContext(Dispatchers.IO) { PublicationIntegrity.isUnchanged(snapshot) }) {
            showError("出版物副本在打开前已发生变化。")
            return
        }

        resolvedBook?.let(::observeReaderRecords)
        openPublication(File(snapshot.path), resolvedBook)
    }

    private fun observeReaderRecords(libraryBook: LibraryBook) {
        lifecycleScope.launch {
            graph.readerRecords.observeBookmarks(libraryBook.id).collectLatest { records ->
                bookmarks = records
                refreshBookmarkState()
            }
        }
        lifecycleScope.launch {
            graph.readerRecords.observeAnnotations(libraryBook.id).collectLatest { records ->
                annotations = records
                applyAnnotationDecorations()
                latestLocator?.let(::refreshPdfAnnotationRects)
            }
        }
    }

    private fun showRoot() {
        val density = resources.displayMetrics.density
        // The first state comes from the stored choice, as iOS sets it up when
        // the reader opens: the immersive layout opens with the bars away.
        val startPreferences = graph.appSettings.preferences.value
        layoutModeState.value = startPreferences.layoutMode
        controlsVisibleState.value = startPreferences.layoutMode == ReaderLayoutMode.STANDARD
        isQuickTranslationIndicatorVisible =
            graph.translationSettings.preferences.value.quickTranslationEnabled
        // Painted in the page colour, so the strips outside the page and the
        // translucent bars read as part of the page.
        val root = FrameLayout(this).apply { setBackgroundColor(pageColorArgb()) }
        val readerContainer = FrameLayout(this).apply { id = R.id.reader_container }
        root.addView(
            readerContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // iOS's loading card; attachNavigator hides it once the page is up.
        root.addView(
            readerComposeView {
                ReaderLoadingView(
                    title = book?.title?.let { "正在打开《$it》" } ?: "正在打开…",
                    detail = "正在由 Readium 解析 ${loadingFormatName()} 内容"
                )
            }.apply { id = R.id.reader_loading },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        val topChrome = readerComposeView {
            val appPreferences by graph.appSettings.preferences.collectAsState()
            ReaderTopChrome(
                chapterTitle = currentChapterName,
                bookTitle = book?.title ?: "Jerreader",
                isBookmarked = isCurrentPositionBookmarked,
                onClose = onBackPressedDispatcher::onBackPressed,
                onTableOfContents = ::showNavigationPanel,
                onToggleBookmark = ::toggleBookmark,
                onAppearance = ::showAppearance,
                pageColor = pageComposeColor(),
                transparency = appPreferences.topBarTransparency,
                topInset = with(LocalDensity.current) { systemBarTopPx.toDp() },
                onCompanion = if (book != null) ::showReadingCompanion else null,
                hasCompanionUpdate = hasCompanionUpdate,
                onInteraction = ::noteChromeInteraction
            )
        }.apply { id = R.id.reader_top_chrome }
        root.addView(
            topChrome,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        val bottomChrome = readerComposeView {
            val appPreferences by graph.appSettings.preferences.collectAsState()
            val translationPreferences by graph.translationSettings.preferences.collectAsState()
            ReaderBottomChrome(
                chapterLabel = currentChapterName,
                progress = currentProgress,
                showProgress = appPreferences.showReadingProgress,
                chapterLabelAt = ::chapterTitleAtProgress,
                onSeek = ::goToProgress,
                canGoToAdjacentChapter = readingOrder.size > 1,
                onPreviousChapter = { goToAdjacentChapter(-1) },
                onNextChapter = { goToAdjacentChapter(1) },
                onPreviousPage = ::goToPreviousPage,
                onNextPage = ::goToNextPage,
                isQuickTranslationEnabled = translationPreferences.quickTranslationEnabled,
                onToggleQuickTranslation = {
                    graph.translationSettings.updateQuickTranslationEnabled(
                        !translationPreferences.quickTranslationEnabled
                    )
                },
                pageColor = pageComposeColor(),
                transparency = appPreferences.bottomBarTransparency,
                bottomInset = with(LocalDensity.current) { systemBarBottomPx.toDp() },
                onInteraction = ::noteChromeInteraction,
                onScrubbingChanged = ::setChromeAutoHideSuspended
            )
        }.apply { id = R.id.reader_bottom_chrome }
        root.addView(
            bottomChrome,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        // In the immersive layout a tap on the text looks a word up or
        // translates, so the blank strips above and below the text bring the
        // bars back (iOS `immersiveRevealZones`). Each covers the system bar
        // and the page's own edge inset, never a line of text.
        val revealTop = revealZone(R.id.reader_reveal_top)
        root.addView(revealTop, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0, Gravity.TOP))
        val revealBottom = revealZone(R.id.reader_reveal_bottom)
        root.addView(revealBottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM))

        // Keep persisted PDF highlights in a non-interactive layer that is never
        // hidden with the translation card. This mirrors iOS's independent
        // PDFAnnotation overlay while keeping cards dismissible.
        val highlightView = readerComposeView { SelectionHighlightLayer() }.apply {
            isClickable = false
            isFocusable = false
        }
        root.addView(
            highlightView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val indicatorView = readerComposeView { QuickTranslationIndicator() }
        root.addView(
            indicatorView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val overlayView = readerComposeView { ReaderFloatingLayer() }
        root.addView(
            overlayView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        highlightLayer = highlightView
        floatingLayer = overlayView
        topChromeView = topChrome
        bottomChromeView = bottomChrome
        revealTopView = revealTop
        revealBottomView = revealBottom
        readerContainerView = readerContainer

        // Measured, so the page sits exactly between the bars in the standard
        // layout whatever the text size or the progress-row setting.
        topChrome.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (view.height != topChromeHeightPx) {
                topChromeHeightPx = view.height
                view.post(::updateReaderContentPadding)
            }
        }
        bottomChrome.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (view.height != bottomChromeHeightPx) {
                bottomChromeHeightPx = view.height
                view.post(::updateReaderContentPadding)
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            systemBarTopPx = bars.top
            systemBarBottomPx = bars.bottom
            val revealEdge = ((ReaderInterfaceDefaults.CONTENT_EDGE_INSET_DP - 2) * density).toInt()
            revealTop.updateLayoutParams { height = bars.top + revealEdge }
            revealBottom.updateLayoutParams { height = bars.bottom + revealEdge }
            // The floating layers work inside the system bars, the way the iOS
            // overlays work inside the safe area.
            highlightView.updatePadding(top = bars.top, bottom = bars.bottom)
            indicatorView.updatePadding(top = bars.top, bottom = bars.bottom)
            overlayView.updatePadding(top = bars.top, bottom = bars.bottom)
            updateReaderContentPadding()
            insets
        }
        rootView = root
        setContentView(root)
        updateChromePresentation(animated = false)
        lifecycleScope.launch {
            var lastStatusBarHidden: Boolean? = null
            graph.appSettings.preferences.collectLatest { preferences ->
                setLayoutMode(preferences.layoutMode)
                // The immersive layout is full screen by definition; the
                // standard one keeps the separate 隐藏状态栏 choice, as on iOS.
                val statusBarHidden = preferences.immersiveReading ||
                    preferences.layoutMode == ReaderLayoutMode.IMMERSIVE
                if (lastStatusBarHidden != statusBarHidden) {
                    lastStatusBarHidden = statusBarHidden
                    val controller = androidx.core.view.WindowCompat.getInsetsController(window, root)
                    controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (statusBarHidden) controller.hide(WindowInsetsCompat.Type.statusBars())
                    else controller.show(WindowInsetsCompat.Type.statusBars())
                    ViewCompat.requestApplyInsets(root)
                }
            }
        }
        lifecycleScope.launch {
            var lastEnabled: Boolean? = null
            var lastUnit: com.jerreader.shared.translation.QuickTranslationUnit? = null
            graph.translationSettings.preferences.collectLatest { preferences ->
                val enabled = preferences.quickTranslationEnabled
                val unit = preferences.quickTranslationUnit
                val previousEnabled = lastEnabled
                val previousUnit = lastUnit
                lastEnabled = enabled
                lastUnit = unit
                if (previousEnabled == null) return@collectLatest
                if (previousEnabled != enabled) {
                    // iOS `setQuickSentenceTranslationEnabled`: a fresh mode
                    // gets a fresh reminder, and the bars come back through the
                    // countdown so the immersive layout still hides them.
                    dismissTranslation()
                    isQuickTranslationIndicatorVisible = enabled
                    showReaderControls()
                } else if (previousUnit != unit) {
                    showReaderControls()
                }
            }
        }
    }

    private fun revealZone(viewId: Int): View = View(this).apply {
        id = viewId
        contentDescription = "显示阅读控制"
        visibility = View.GONE
        setOnClickListener { showReaderControls() }
    }

    /**
     * The standard layout lays the page out between the bars; the immersive
     * one runs it under them, inside the system bars only. Showing or hiding
     * the bars never changes this, so it never makes Readium paginate again.
     */
    private fun updateReaderContentPadding() {
        val container = readerContainerView ?: return
        val density = resources.displayMetrics.density
        val top: Int
        val bottom: Int
        if (layoutMode == ReaderLayoutMode.STANDARD) {
            // Until the bars are measured, iOS's own first guesses (44 and 78).
            val topBar = topChromeHeightPx.takeIf { it > 0 }
                ?: (systemBarTopPx + (44 * density).toInt())
            val bottomBar = bottomChromeHeightPx.takeIf { it > 0 }
                ?: (systemBarBottomPx + (78 * density).toInt())
            top = maxOf(topBar, systemBarTopPx)
            bottom = maxOf(bottomBar, systemBarBottomPx)
        } else {
            top = systemBarTopPx
            bottom = systemBarBottomPx
        }
        if (container.paddingTop != top || container.paddingBottom != bottom) {
            container.updatePadding(top = top, bottom = bottom)
        }
        // The floating layer already carries the system-bar padding, so the
        // page offset used for highlights and anchors excludes it. The exact
        // value is measured again from the page before each use.
        readerContentTopPx = (top - systemBarTopPx).coerceAtLeast(0)
    }

    private fun setLayoutMode(mode: ReaderLayoutMode) {
        if (layoutMode == mode) return
        layoutModeState.value = mode
        cancelChromeAutoHide()
        // Switching happens from the settings panel: the standard layout brings
        // the bars back for good, the immersive one starts with them away.
        controlsVisibleState.value = mode == ReaderLayoutMode.STANDARD
        updateReaderContentPadding()
        updateChromePresentation(animated = true)
    }

    private fun setControlsVisible(visible: Boolean) {
        if (controlsVisible == visible) return
        controlsVisibleState.value = visible
        updateChromePresentation(animated = true)
    }

    private fun showReaderControls() {
        setControlsVisible(true)
        scheduleChromeAutoHide()
    }

    /**
     * A tap on the page that neither turned it nor hit text to translate or
     * look up. The standard layout keeps its bars, so only the immersive one
     * reacts.
     */
    private fun handleContentTap() {
        if (layoutMode != ReaderLayoutMode.IMMERSIVE) {
            setControlsVisible(true)
            return
        }
        setControlsVisible(!controlsVisible)
        if (controlsVisible) scheduleChromeAutoHide() else cancelChromeAutoHide()
    }

    /** Any touch on the bars restarts the immersive auto-hide countdown. */
    private fun noteChromeInteraction() {
        if (layoutMode != ReaderLayoutMode.IMMERSIVE || !controlsVisible) return
        scheduleChromeAutoHide()
    }

    /** Dragging the progress slider must not lose the bars mid-drag. */
    private fun setChromeAutoHideSuspended(suspended: Boolean) {
        isChromeAutoHideSuspended = suspended
        if (suspended) cancelChromeAutoHide() else noteChromeInteraction()
    }

    private fun scheduleChromeAutoHide() {
        cancelChromeAutoHide()
        if (layoutMode != ReaderLayoutMode.IMMERSIVE || !controlsVisible ||
            isChromeAutoHideSuspended || isFinishing || isDestroyed
        ) return
        chromeAutoHideJob = lifecycleScope.launch {
            delay(ReaderInterfaceDefaults.IMMERSIVE_CHROME_AUTO_HIDE_MILLIS)
            chromeAutoHideJob = null
            if (layoutMode == ReaderLayoutMode.IMMERSIVE && !isChromeAutoHideSuspended) {
                setControlsVisible(false)
            }
        }
    }

    private fun cancelChromeAutoHide() {
        chromeAutoHideJob?.cancel()
        chromeAutoHideJob = null
    }

    /**
     * iOS `readerChromeBar`: a bar fades while it travels 12dp towards its own
     * edge (JerreaderMotion.chrome, 0.20 s, no bounce). Only drawing properties
     * change, so the measured heights, and with them the page and Readium's
     * pagination, stay put; only switching the layout re-paginates. A hidden
     * bar is invisible rather than gone, which also lets taps through to the
     * page. With the system animations off the bars just switch.
     */
    private fun updateChromePresentation(animated: Boolean) {
        val visible = isChromeVisible
        // areAnimatorsEnabled() needs API 26; below that the system scale
        // still shortens ViewPropertyAnimator, so animating is harmless.
        val animate = animated && (
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
                android.animation.ValueAnimator.areAnimatorsEnabled()
            )
        val travel = CHROME_TRAVEL_DP * resources.displayMetrics.density
        listOfNotNull(
            topChromeView?.let { it to -travel },
            bottomChromeView?.let { it to travel }
        ).forEach { (bar, hiddenOffset) ->
            bar.animate().cancel()
            bar.importantForAccessibility = if (visible) {
                View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            } else {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            val alpha = if (visible) 1f else 0f
            val offset = if (visible) 0f else hiddenOffset
            if (visible) bar.visibility = View.VISIBLE
            if (animate) {
                bar.animate()
                    .alpha(alpha)
                    .translationY(offset)
                    .setDuration(CHROME_FADE_MILLIS)
                    // FastOutSlowIn, the Compose JerreaderMotion.chrome() curve.
                    .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f))
                    .withEndAction { if (!isChromeVisible) bar.visibility = View.INVISIBLE }
                    .start()
            } else {
                bar.alpha = alpha
                bar.translationY = offset
                if (!visible) bar.visibility = View.INVISIBLE
            }
        }
        val showsRevealZones = layoutMode == ReaderLayoutMode.IMMERSIVE && !visible
        listOfNotNull(revealTopView, revealBottomView).forEach { zone ->
            zone.visibility = if (showsRevealZones) View.VISIBLE else View.GONE
        }
    }

    /** The colour Readium paints behind the page, for the root and the bars. */
    private fun pageColorArgb(): Int = ReaderInterfaceDefaults.pageColorArgb(currentAppearance).toInt()

    private fun pageComposeColor(): androidx.compose.ui.graphics.Color =
        androidx.compose.ui.graphics.Color(pageColorArgb())

    /**
     * A compact right-edge reminder that quick translation is on (iOS
     * `quickTranslationIndicator`). It only shows while the bars are away and
     * fades out after a few seconds; hiding it never turns the mode off.
     */
    @androidx.compose.runtime.Composable
    private fun QuickTranslationIndicator() {
        val translationPreferences by graph.translationSettings.preferences.collectAsState()
        val shows = translationPreferences.quickTranslationEnabled &&
            isQuickTranslationIndicatorVisible &&
            !isChromeVisible &&
            currentTranslationState == null &&
            currentWordLookupState == null &&
            !isExpandingCrossPage
        if (!shows) return
        LaunchedEffect(Unit) {
            delay(QUICK_TRANSLATION_INDICATOR_AUTO_EXIT_MILLIS)
            isQuickTranslationIndicatorVisible = false
        }
        val unit = if (translationPreferences.quickTranslationUnit ==
            com.jerreader.shared.translation.QuickTranslationUnit.PARAGRAPH
        ) "点段" else "点句"
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The page's outer gutter, not over the first line of every page.
            val centreX = (maxWidth - 18.dp).coerceAtLeast(18.dp)
            val centreY = minOf(maxHeight - 72.dp, maxOf(72.dp, maxHeight * 0.30f))
            // iOS: a 44dp paper circle with a 1dp accent ring and e1.
            val palette = jerreaderPalette
            Box(
                modifier = Modifier
                    .offset(x = centreX - 22.dp, y = centreY - 22.dp)
                    .size(44.dp)
                    .jerreaderElevation(JerreaderElevation.CARD, CircleShape)
                    .clip(CircleShape)
                    .background(palette.paper, CircleShape)
                    .border(1.dp, palette.accent.copy(alpha = 0.28f), CircleShape)
                    .semantics { contentDescription = "${unit}翻译已开启" }
                    .clickable(role = Role.Button, onClickLabel = "显示阅读控制") { showReaderControls() },
                contentAlignment = Alignment.Center
            ) {
                JerreaderGlyph(
                    icon = JerreaderIcon.TRANSLATION_BUBBLE_FILLED,
                    tint = palette.accent,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    /**
     * The chapter under the slider thumb, resolved without navigating so the
     * book does not jump around until the finger lifts (iOS
     * `outlineItem(atProgression:)`).
     */
    private fun chapterTitleAtProgress(progress: Double): String {
        if (positions.isEmpty()) return currentChapterName
        val index = ((positions.size - 1) * progress.coerceIn(0.0, 1.0)).toInt()
            .coerceIn(0, positions.lastIndex)
        val target = positions[index]
        if (pdfPublicationOpen) {
            val page = target.locations.position ?: (index + 1)
            val entry = tableOfContentsLinks.values
                .mapNotNull { link -> pdfOutlinePage(link)?.let { it to link } }
                .filter { (start, _) -> start <= page }
                .maxByOrNull { (start, _) -> start }
            return entry?.second?.title?.takeIf(String::isNotBlank) ?: currentChapterTitle(target)
        }
        val resource = readingOrder.indexOfFirst { it.url().toString() == target.href.toString() }
        // A chapter split into several files keeps the title of the file the
        // outline points at.
        for (candidate in resource downTo 0) {
            val href = readingOrder[candidate].url().toString()
            val title = tableOfContentsLinks.values.firstOrNull { link ->
                link.url().toString().substringBefore('#') == href
            }?.title
            if (!title.isNullOrBlank()) return title
        }
        return currentChapterTitle(target)
    }

    private fun pdfOutlinePage(link: Link): Int? =
        link.url().toString().substringAfter('#', "")
            .split('&')
            .firstOrNull { it.startsWith("page=") }
            ?.removePrefix("page=")
            ?.toIntOrNull()

    private fun readerComposeView(
        content: @androidx.compose.runtime.Composable () -> Unit
    ): ComposeView = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { ReaderPageTheme(content) }
    }

    private suspend fun openPublication(file: File, libraryBook: LibraryBook?) {
        val asset = graph.readium.assetRetriever.retrieve(file).getOrElse { error ->
            showError("无法读取该出版物文件。")
            return
        }
        val openedPublication = graph.readium.publicationOpener.open(
            asset,
            allowUserInteraction = false
        ).getOrElse { error ->
            showError("无法打开该出版物，请确认文件未损坏。")
            return
        }

        if (openedPublication.isRestricted) {
            openedPublication.close()
            showError("当前出版物受保护，Jerreader 不会尝试绕过 DRM。")
            return
        }

        publication = openedPublication
        if (book != null) session.recorder.startClock()
        readingOrder = openedPublication.readingOrder
        // Readium indexes can touch chapter resources; keep this work off the UI thread.
        positions = withContext(Dispatchers.IO) { openedPublication.positions() }

        val stored = decodeStoredReaderPreferences(
            json = libraryBook?.preferencesJson,
            fallback = graph.appSettings.preferences.value.defaultReaderAppearance
        )
        basePreferences = stored.epub
        currentAppearance = stored.appearance
        rootView?.setBackgroundColor(pageColorArgb())

        when {
            openedPublication.conformsTo(Publication.Profile.EPUB) -> {
                buildTableOfContents(openedPublication.tableOfContents)
                val initialLocator = (intent.getStringExtra(EXTRA_INITIAL_LOCATOR) ?: libraryBook?.locatorJson)?.let(::parseLocator)
                val wordController = ReaderWordInteractionController(
                    scope = lifecycleScope,
                    lookupService = graph.lexicalLookupService,
                    languageHint = { currentBookLanguage() },
                    onStateChanged = ::showWordLookup,
                    onSelectionInvalidated = ::clearTransientCardPresentation,
                    shortTapEnabled = {
                        !graph.translationSettings.preferences.value.quickTranslationEnabled
                    },
                    onBlankTap = ::handleContentTap
                )
                wordInteractionController = wordController
                val tapController = ReaderTapTranslationController(
                    scope = lifecycleScope,
                    translationService = graph.translationService,
                    settings = graph.translationSettings,
                    bookLanguageHint = { currentBookLanguage() },
                    onStateChanged = ::showTranslation,
                    onPreviousPageRequested = ::goToPreviousPage,
                    onNextPageRequested = ::goToNextPage,
                    onLookupRequested = { wordController.lookupSelectionFromMenu() },
                    onShortTapWordRequested = {
                        currentTranslationState = null
                        currentTranslationLocatorJson = null
                        setFloatingLayerVisible(false)
                        wordController.lookupSelectionFromMenu()
                    },
                    onAnnotateRequested = ::requestAnnotationForSelection,
                    onHapticFeedback = ::performTranslationHaptic,
                    onHighlightChanged = ::updateSelectionAnchor,
                    onSelectionInvalidated = ::clearTransientCardPresentation,
                    appearance = { currentAppearance },
                    onBlankTap = ::handleContentTap,
                    tapPageTurnsAvailable = { !scrollModeBlocksPageTurns() }
                )
                tapTranslationController = tapController
                val navigatorFactory = EpubNavigatorFactory(openedPublication)
                val factory = navigatorFactory.createFragmentFactory(
                    initialLocator = initialLocator,
                    initialPreferences = basePreferences,
                    configuration = epubConfiguration(navigatorFactory, tapController, basePreferences)
                )
                attachEpubNavigator(factory)
            }

            openedPublication.conformsTo(Publication.Profile.PDF) -> {
                buildTableOfContents(openedPublication.tableOfContents)
                // Must be set before any stored locator is parsed: PDF
                // locators from Jerreader 1.4.1 and earlier are one page ahead.
                pdfPublicationOpen = true
                val initialLocator = (intent.getStringExtra(EXTRA_INITIAL_LOCATOR) ?: libraryBook?.locatorJson)?.let(::parseLocator)
                val factory = PdfNavigatorFactory(
                    openedPublication,
                    pdfiumEngineProvider()
                ).createFragmentFactory(initialLocator = initialLocator)
                attachPdfNavigator(factory, file)
            }

            else -> {
                openedPublication.close()
                publication = null
                showError("当前出版物不支持阅读。")
            }
        }
    }

    /**
     * Readium pads each reflowable page by `readium_navigator_epub_vertical_padding`
     * (14dp here, iOS's content inset) and, by default, by the display cutout
     * as well. The container's own padding already keeps clear of the cutout,
     * so Readium adding it again only widened the blank margins.
     *
     * While a book scrolls, Readium reads any sideways finger movement past
     * the touch slop as a page turn; a scrolling chapter is a single page
     * wide, so the turn lands in the neighbouring chapter. A scroll flick that
     * drifted a few millimetres sideways was enough to lose one's place
     * (GitHub issue #2). iOS has always opened its navigator with
     * `disablePageTurnsWhileScrolling`; this sets the same switch.
     *
     * Vertical text is left alone. Readium forces it into scroll mode, the
     * page then scrolls sideways, and swiping past its edge is the only
     * gesture that reaches the next chapter. That turn is only taken at the
     * edge, so it is not the accidental one described above.
     */
    private fun epubConfiguration(
        navigatorFactory: EpubNavigatorFactory,
        tapController: ReaderTapTranslationController,
        preferences: EpubPreferences
    ): EpubNavigatorFragment.Configuration {
        // What Readium itself will resolve for these preferences and this
        // book's metadata. It only changes with 排版方向, which rebuilds the
        // navigator (`recreateNavigatorForOrientation`) and comes back here.
        val laysOutVerticalText = navigatorFactory
            .createPreferencesEditor(preferences)
            .verticalText
            .effectiveValue
        scrollPageTurnsDisabled = !laysOutVerticalText
        return EpubNavigatorFragment.Configuration(
            selectionActionModeCallback = tapController.selectionActionModeCallback,
            shouldApplyInsetsPadding = false
        ).apply { disablePageTurnsWhileScrolling = scrollPageTurnsDisabled }
    }

    /**
     * True while the EPUB scrolls with Readium's page turns switched off (see
     * [epubConfiguration]). `goForward()` and `goBackward()` do nothing at all
     * then, so whatever still turns a page has to say what it means instead.
     */
    internal fun scrollModeBlocksPageTurns(): Boolean {
        val epubNavigator = navigator ?: return false
        return scrollPageTurnsDisabled && epubNavigator.settings.value.scroll
    }

    private fun attachEpubNavigator(factory: FragmentFactory) {
        attachNavigator(factory, EpubNavigatorFragment::class.java)
        val epubNavigator = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG)
            as? EpubNavigatorFragment
            ?: return
        navigator = epubNavigator
        // Quick translation owns the tap while enabled. When it is disabled,
        // the same native tap path becomes direct word lookup instead of being
        // silently dropped; the two listeners never consume the same tap.
        wordInteractionController?.attach(epubNavigator, registerShortTap = true)
        tapTranslationController?.attach(epubNavigator)
        epubNavigator.addDecorationListener(
            ANNOTATION_DECORATION_GROUP,
            annotationDecorationListener
        )
        applyAnnotationDecorations()
        observeLocator { epubNavigator.currentLocator.collectLatest(it) }
    }

    private fun attachPdfNavigator(factory: FragmentFactory, file: File) {
        attachNavigator(factory, PdfNavigatorFragment::class.java)
        val fragment = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG)
            as? PdfNavigatorFragment<*, *>
            ?: return
        pdfNavigator = fragment
        val controller = PdfTapTranslationController(
            file = file,
            scope = lifecycleScope,
            translationService = graph.translationService,
            settings = graph.translationSettings,
            languageHint = { currentBookLanguage() },
            currentPageIndex = {
                (fragment.currentLocator.value.locations.position ?: 1) - 1
            },
            paperModeEnabled = { currentAppearance.pdfPaperModeEnabled },
            onStateChanged = ::showTranslation,
            onHapticFeedback = ::performTranslationHaptic,
            onPreviousPageRequested = ::goToPreviousPage,
            onNextPageRequested = ::goToNextPage,
            onHighlightChanged = ::updateSelectionHighlight,
            onSelectionInvalidated = ::clearTransientCardPresentation,
            onBlankTap = ::handleContentTap
        )
        pdfTranslationController = controller
        controller.attach(fragment)
        installPdfGeometryListeners(fragment.publicationView)
        latestLocator?.let(::refreshPdfAnnotationRects)
        observeLocator { fragment.currentLocator.collectLatest(it) }
    }

    // This listener observes release/cancel only. It returns false so PDFView
    // dispatches its own accessible click; calling performClick here would duplicate taps.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun installPdfGeometryListeners(view: View) {
        removePdfGeometryListeners()
        val scrollListener = ViewTreeObserver.OnScrollChangedListener {
            latestLocator?.let(::refreshPdfAnnotationRects)
        }
        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            latestLocator?.let(::refreshPdfAnnotationRects)
        }
        val touchListener = View.OnTouchListener { touchedView, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP ||
                event.actionMasked == android.view.MotionEvent.ACTION_CANCEL
            ) {
                touchedView.postDelayed(
                    { latestLocator?.let(::refreshPdfAnnotationRects) },
                    PDF_GEOMETRY_REFRESH_DELAY_MILLIS
                )
            }
            // Do not consume the event: PDFView must keep its own pan/zoom
            // gesture handling and Readium must keep receiving taps.
            false
        }
        view.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        view.addOnLayoutChangeListener(layoutListener)
        view.setOnTouchListener(touchListener)
        pdfGeometryView = view
        pdfGeometryScrollListener = scrollListener
        pdfGeometryLayoutListener = layoutListener
        pdfGeometryTouchListener = touchListener
    }

    private fun removePdfGeometryListeners() {
        val view = pdfGeometryView ?: return
        pdfGeometryScrollListener?.let { listener ->
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnScrollChangedListener(listener)
            }
        }
        pdfGeometryLayoutListener?.let(view::removeOnLayoutChangeListener)
        if (pdfGeometryTouchListener != null) view.setOnTouchListener(null)
        pdfGeometryView = null
        pdfGeometryScrollListener = null
        pdfGeometryLayoutListener = null
        pdfGeometryTouchListener = null
    }

    private fun observeLocator(collect: suspend (suspend (Locator) -> Unit) -> Unit) {
        locatorObservationJob?.cancel()
        locatorObservationJob = lifecycleScope.launch {
            collect { emitted ->
                // Every persisted PDF locator derives from this one; the marker
                // tells later reads it already uses Readium 3.4 page positions.
                val locator = if (pdfPublicationOpen) PdfLocatorFormat.mark(emitted) else emitted
                resetTransientReaderState()
                val locatorJson = locator.toJSON().toString()
                latestLocator = locator
                latestLocatorJson = locatorJson
                currentProgress = locator.locations.totalProgression ?: currentProgress
                val previousChapterIndex = currentChapterIndex
                val previousChapterName = currentChapterName
                currentChapterIndex = readingOrder.indexOfFirst {
                    it.url().toString() == locator.href.toString()
                }
                currentChapterName = currentChapterTitle(locator)
                // Reading on into the next chapter offers its 90-second loop
                // through the AI button's dot; nothing pops up over the page.
                if (book != null && previousChapterIndex >= 0 &&
                    currentChapterIndex == previousChapterIndex + 1 &&
                    previousChapterName.isNotBlank() && previousChapterName != currentChapterName
                ) {
                    finishedChapterTitle = previousChapterName
                    hasCompanionUpdate = true
                }
                applyWritingModeOverride()
                refreshBookmarkState()
                refreshPdfAnnotationRects(locator)
                session.recorder.recordPosition(locatorJson, currentProgress)
            }
        }
    }

    private fun attachNavigator(
        factory: FragmentFactory,
        fragmentClass: Class<out androidx.fragment.app.Fragment>
    ) {
        findViewById<View>(R.id.reader_loading)?.visibility = View.GONE
        supportFragmentManager.fragmentFactory = factory
        supportFragmentManager.commitNow {
            replace(R.id.reader_container, fragmentClass, Bundle(), NAVIGATOR_TAG)
        }
    }

    private fun buildTableOfContents(links: List<Link>) {
        val chapters = mutableListOf<ReaderChapter>()
        tableOfContentsLinks.clear()

        fun append(items: List<Link>, depth: Int) {
            items.forEach { link ->
                val id = "toc-${chapters.size}"
                chapters += ReaderChapter(
                    id = id,
                    title = link.title?.takeIf(String::isNotBlank) ?: link.href.toString(),
                    depth = depth
                )
                tableOfContentsLinks[id] = link
                append(link.children, depth + 1)
            }
        }

        append(links, 0)
        tableOfContents = chapters
    }

    internal fun navigateToChapter(chapterId: String): Boolean {
        val link = tableOfContentsLinks[chapterId] ?: return false
        return navigator?.go(link, animated = false)
            ?: pdfNavigator?.go(link, animated = false)
            ?: false
    }

    // A scrolling chapter has no pages, so 上一页 / 下一页 step one chapter
    // there. Readium did exactly that for these buttons before its page turns
    // were switched off for scrolling, and iOS does the same.
    private fun goToPreviousPage() {
        if (scrollModeBlocksPageTurns()) return goToAdjacentChapter(-1)
        navigator?.goBackward(animated = false) ?: pdfNavigator?.goBackward(animated = false)
    }

    private fun goToNextPage() {
        if (scrollModeBlocksPageTurns()) return goToAdjacentChapter(1)
        navigator?.goForward(animated = false) ?: pdfNavigator?.goForward(animated = false)
    }

    private fun goToAdjacentChapter(offset: Int) {
        if (readingOrder.isEmpty()) return
        val target = (currentChapterIndex + offset).coerceIn(0, readingOrder.lastIndex)
        if (target == currentChapterIndex) return
        val link = readingOrder[target]
        navigator?.go(link, animated = false) ?: pdfNavigator?.go(link, animated = false)
    }

    private fun goToProgress(progress: Double) {
        val clamped = progress.coerceIn(0.0, 1.0)
        currentProgress = clamped
        if (positions.isEmpty()) return
        val index = ((positions.size - 1) * clamped).toInt().coerceIn(0, positions.lastIndex)
        val target = positions[index]
        navigator?.go(target, animated = false) ?: pdfNavigator?.go(target, animated = false)
    }

    private fun bookmarkKey(locator: Locator): String = buildString {
        append(book?.id.orEmpty())
        append('|')
        append(locator.href)
        append('|')
        append(((locator.locations.progression ?: 0.0) * 10_000).toInt())
    }

    /**
     * The bookmark on this page. An old PDF bookmark's key was built from the
     * shifted progression, so it is also matched by its corrected locator.
     */
    private fun bookmarkAt(locator: Locator): ReadingBookmarkEntity? {
        val key = bookmarkKey(locator)
        return bookmarks.firstOrNull { it.bookmarkKey == key }
            ?: if (!pdfPublicationOpen) null else bookmarks.firstOrNull { record ->
                parseLocator(record.locatorJson)?.let(::bookmarkKey) == key
            }
    }

    private fun refreshBookmarkState() {
        val locator = latestLocator
        isCurrentPositionBookmarked = locator != null && bookmarkAt(locator) != null
    }

    private fun toggleBookmark() {
        val locator = latestLocator ?: return
        val libraryBook = book ?: return
        val key = bookmarkAt(locator)?.bookmarkKey ?: bookmarkKey(locator)
        lifecycleScope.launch {
            graph.readerRecords.toggleBookmark(
                key = key,
                bookId = libraryBook.id,
                bookTitle = libraryBook.title,
                locatorJson = locator.toJSON().toString(),
                chapterTitle = currentChapterTitle(locator),
                excerpt = locator.text.highlight ?: locator.text.after?.take(60),
                progress = locator.locations.totalProgression ?: currentProgress
            )
        }
    }

    private fun currentChapterTitle(locator: Locator): String =
        locator.title?.takeIf(String::isNotBlank)
            ?: readingOrder.firstOrNull { it.url().toString() == locator.href.toString() }
                ?.title
                ?.takeIf(String::isNotBlank)
            ?: "阅读位置"

    internal fun applyAppearance(appearance: ReaderAppearance) {
        val previousOrientation = currentAppearance.orientation
        currentAppearance = appearance
        rootView?.setBackgroundColor(pageColorArgb())
        val updated = appearance.toEpubPreferences(basePreferences)
        basePreferences = updated
        navigator?.submitPreferences(updated)
        tapTranslationController?.refreshSelectionAppearance()
        applyWritingModeOverride()

        // Readium resolves the EPUB layout when the navigator is created and
        // does not re-lay out an already loaded resource when the writing mode
        // changes, so a vertical Japanese book stayed vertical after choosing
        // 横排. Rebuilding the navigator at the current locator applies it.
        if (previousOrientation != appearance.orientation && publication != null) {
            recreateNavigatorForOrientation(updated)
        }

        val bookId = book?.id ?: return
        val serialized = encodeStoredReaderPreferences(appearance, updated)
        graph.applicationScope.launch {
            graph.repository.updatePreferences(bookId, serialized)
        }
    }

    private fun recreateNavigatorForOrientation(preferences: EpubPreferences) {
        val openedPublication = publication ?: return
        val tapController = tapTranslationController ?: return
        val resumeLocator = latestLocator
        navigator?.removeDecorationListener(annotationDecorationListener)
        wordInteractionController?.detach()
        tapController.detach()
        supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG)?.let { existing ->
            supportFragmentManager.commitNow { remove(existing) }
        }
        navigator = null
        val navigatorFactory = EpubNavigatorFactory(openedPublication)
        val factory = navigatorFactory.createFragmentFactory(
            initialLocator = resumeLocator,
            initialPreferences = preferences,
            configuration = epubConfiguration(navigatorFactory, tapController, preferences)
        )
        attachEpubNavigator(factory)
    }

    /**
     * Readium keeps rendering a publication that declares Japanese vertical
     * text vertically even when `verticalText = false` is submitted, so the
     * chosen direction is also enforced with an injected stylesheet. It is
     * reapplied whenever the visible resource changes.
     */
    private fun applyWritingModeOverride() {
        val mode = when (currentAppearance.orientation) {
            ReaderTextOrientation.HORIZONTAL -> "horizontal"
            ReaderTextOrientation.VERTICAL -> "vertical"
            ReaderTextOrientation.PUBLICATION -> null
        }
        writingModeJob?.cancel()
        writingModeJob = lifecycleScope.launch {
            val controller = tapTranslationController ?: return@launch
            if (mode == null) {
                publicationWritingModeIsVertical = controller.detectPublicationVertical()
            } else {
                publicationWritingModeIsVertical = mode == "vertical"
            }
            controller.applyWritingModeOverride(mode)
        }
    }

    private fun showNavigationPanel() {
        showComposeDialog { dialog ->
            ReaderNavigationPanel(
                chapters = tableOfContents,
                bookmarks = bookmarks.map { record ->
                    ReaderBookmarkItem(
                        id = record.id,
                        title = record.chapterTitle,
                        detail = record.excerpt?.take(80)
                            ?: "${(record.progress * 100).toInt()}%",
                        progress = record.progress
                    )
                },
                annotations = annotations.map { record ->
                    ReaderAnnotationItem(
                        id = record.id,
                        selectedText = record.selectedText,
                        noteText = record.noteText,
                        color = record.color,
                        progress = record.progress
                    )
                },
                searchQuery = searchQuery,
                searchResults = searchResults,
                isSearching = isSearching,
                searchMessage = searchMessage,
                onSearchQueryChange = { searchQuery = it },
                onSearch = ::runSearch,
                onSelectSearchResult = { id ->
                    searchLocators[id]?.let { locator ->
                        navigator?.go(locator, animated = false)
                            ?: pdfNavigator?.go(locator, animated = false)
                    }
                    dialog.dismiss()
                },
                onSelectChapter = { id ->
                    navigateToChapter(id)
                    dialog.dismiss()
                },
                onSelectBookmark = { id ->
                    bookmarks.firstOrNull { it.id == id }
                        ?.locatorJson
                        ?.let(::parseLocator)
                        ?.let { locator ->
                            navigator?.go(locator, animated = false)
                                ?: pdfNavigator?.go(locator, animated = false)
                        }
                    dialog.dismiss()
                },
                onDeleteBookmark = { id ->
                    lifecycleScope.launch { graph.readerRecords.deleteBookmark(id) }
                },
                onSelectAnnotation = { id ->
                    annotations.firstOrNull { it.id == id }
                        ?.locatorJson
                        ?.let(::parseLocator)
                        ?.let { locator ->
                            navigator?.go(locator, animated = false)
                                ?: pdfNavigator?.go(locator, animated = false)
                        }
                    dialog.dismiss()
                },
                onEditAnnotation = { id ->
                    annotations.firstOrNull { it.id == id }?.let { record ->
                        dialog.dismiss()
                        parseLocator(record.locatorJson)?.let { locator ->
                            showAnnotationEditor(
                                PendingAnnotation(
                                    selectedText = record.selectedText,
                                    locator = locator,
                                    existingId = record.id,
                                    note = record.noteText,
                                    color = record.color,
                                    geometryJson = record.geometryJson
                                )
                            )
                        }
                    }
                },
                onDeleteAnnotation = { id ->
                    lifecycleScope.launch { graph.readerRecords.deleteAnnotation(id) }
                }
            )
        }
    }

    private fun runSearch() {
        val query = searchQuery.trim()
        val currentPublication = publication ?: return
        if (query.isEmpty() || isSearching) return
        isSearching = true
        searchMessage = null
        searchResults = emptyList()
        searchLocators.clear()
        lifecycleScope.launch {
            try {
                val iterator = currentPublication.search(query)
                if (iterator == null) {
                    searchMessage = "当前出版物不支持全文搜索。"
                    return@launch
                }
                val collected = mutableListOf<ReaderSearchResultItem>()
                try {
                    var pages = 0
                    while (collected.size < SEARCH_RESULT_LIMIT && pages < SEARCH_PAGE_LIMIT) {
                        val locators = iterator.next().getOrNull()?.locators ?: break
                        if (locators.isEmpty()) break
                        locators.forEach { locator ->
                            if (collected.size >= SEARCH_RESULT_LIMIT) return@forEach
                            val id = "search-${searchLocators.size}"
                            searchLocators[id] = locator
                            collected += ReaderSearchResultItem(
                                id = id,
                                excerpt = buildString {
                                    append(locator.text.before?.takeLast(30).orEmpty())
                                    append(locator.text.highlight.orEmpty())
                                    append(locator.text.after?.take(30).orEmpty())
                                }.trim(),
                                chapterTitle = currentChapterTitle(locator)
                            )
                        }
                        pages++
                    }
                } finally {
                    iterator.close()
                }
                searchResults = collected
                searchMessage = if (collected.isEmpty()) {
                    "没有找到匹配结果。"
                } else {
                    "找到 ${collected.size} 处匹配。"
                }
            } catch (_: Exception) {
                searchMessage = "当前出版物不支持全文搜索。"
            } finally {
                isSearching = false
            }
        }
    }

    /**
     * 阅读与翻译 as iOS presents it: a large sheet over the page, with 翻译与 AI
     * and the pages under it pushed inside the sheet instead of leaving the
     * book. The window spans the screen so the sheet can dim the page and
     * slide in; it keeps the reader's status-bar choice and icon colours.
     */
    private fun showAppearance() {
        val dialog = ComponentDialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(dialogComposeView {
            ReaderTheme {
                ReaderSettingsSheet(
                    graph = graph,
                    bookTitle = book?.title.orEmpty(),
                    isPdf = pdfNavigator != null,
                    publicationIsVertical = publicationWritingModeIsVertical,
                    appearance = currentAppearance,
                    onAppearanceChange = ::applyAppearance,
                    onDismissed = dialog::dismiss
                )
            }
        })
        dialog.window?.let { sheetWindow ->
            sheetWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            sheetWindow.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(sheetWindow, false)
            val readerBars = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            val sheetBars = androidx.core.view.WindowCompat.getInsetsController(sheetWindow, sheetWindow.decorView)
            sheetBars.isAppearanceLightStatusBars = readerBars.isAppearanceLightStatusBars
            sheetBars.isAppearanceLightNavigationBars = readerBars.isAppearanceLightNavigationBars
            val preferences = graph.appSettings.preferences.value
            if (preferences.immersiveReading || preferences.layoutMode == ReaderLayoutMode.IMMERSIVE) {
                sheetBars.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                sheetBars.hide(WindowInsetsCompat.Type.statusBars())
            }
        }
        dialog.show()
    }

    private fun requestAnnotationForSelection() {
        lifecycleScope.launch {
            val selection = navigator?.currentSelection() ?: return@launch
            val text = selection.locator.text.highlight?.takeIf(String::isNotBlank) ?: return@launch
            showAnnotationEditor(PendingAnnotation(text, selection.locator))
        }
    }

    private fun showAnnotationEditor(target: PendingAnnotation) {
        pendingAnnotation = target
        annotationDialog?.dismiss()
        val dialog = ComponentDialog(this)
        annotationDialog = dialog
        dialog.setContentView(
            dialogComposeView {
                ReaderTheme {
                    Surface {
                        ReaderAnnotationEditor(
                            selectedText = target.selectedText,
                            initialNote = target.note,
                            initialColor = target.color,
                            onSave = { note, color ->
                                saveAnnotation(target, note, color)
                                dialog.dismiss()
                            },
                            onCancel = dialog::dismiss
                        )
                    }
                }
            }
        )
        dialog.setOnDismissListener {
            annotationDialog = null
            pendingAnnotation = null
        }
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * Draws saved markers on the page itself, the same way iOS applies a
     * dedicated Readium decoration group. Tapping a marker reopens its editor.
     */
    private fun applyAnnotationDecorations() {
        val decorable = navigator ?: return
        val snapshot = annotations
        lifecycleScope.launch {
            val decorations = snapshot.mapNotNull { record ->
                val locator = parseLocator(record.locatorJson) ?: return@mapNotNull null
                Decoration(
                    id = record.id,
                    locator = locator,
                    style = Decoration.Style.Highlight(
                        tint = ReaderAnnotationColor.fromId(record.color).tintArgb.toInt()
                    )
                )
            }
            decorable.applyDecorations(decorations, ANNOTATION_DECORATION_GROUP)
        }
    }

    /**
     * PDF annotations are stored as page-normalized rectangles because the
     * navigator view can change size after rotation, split-screen, or a
     * restored process. EPUB annotations continue to use Readium decorations.
     */
    private fun refreshPdfAnnotationRects(locator: Locator) {
        val controller = pdfTranslationController
        val view = pdfNavigator?.publicationView
        updateReaderContentOffset()
        if (controller == null || view == null || view.width <= 0 || view.height <= 0) {
            pdfAnnotationRects = emptyList()
            return
        }
        val pageIndex = ((locator.locations.position ?: 1) - 1)
            .coerceAtLeast(0)
        val currentRecords = annotations.filter { record ->
            val recordPage = parseLocator(record.locatorJson)?.locations?.position
            recordPage != null && recordPage - 1 == pageIndex
        }
        lifecycleScope.launch {
            val mapped = currentRecords.flatMap { record ->
                parseGeometry(record.geometryJson).orEmpty().mapNotNull { geometry ->
                    controller.mapPageGeometryToView(
                        geometry = geometry,
                        pageIndex = pageIndex,
                        viewWidth = view.width,
                        viewHeight = view.height
                    )
                }
            }
            // Ignore a stale mapping result if the reader moved while the
            // renderer was opening a page for its dimensions.
            if (latestLocator?.locations?.position == locator.locations.position) {
                pdfAnnotationRects = mapped
            }
        }
    }

    private fun parseGeometry(json: String?): List<RectF>? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val array = JSONArray(json)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val left = item.optDouble("x", Double.NaN).toFloat()
                    val top = item.optDouble("y", Double.NaN).toFloat()
                    val right = (left + item.optDouble("w", Double.NaN).toFloat())
                    val bottom = (top + item.optDouble("h", Double.NaN).toFloat())
                    if (left.isFinite() && top.isFinite() && right.isFinite() &&
                        bottom.isFinite() && right > left && bottom > top
                    ) {
                        add(RectF(
                            left.coerceIn(0f, 1f),
                            top.coerceIn(0f, 1f),
                            right.coerceIn(0f, 1f),
                            bottom.coerceIn(0f, 1f)
                        ))
                    }
                }
            }
        }.getOrNull()
    }

    private val annotationDecorationListener = object : DecorableNavigator.Listener {
        override fun onDecorationActivated(
            event: DecorableNavigator.OnActivatedEvent
        ): Boolean {
            val record = annotations.firstOrNull { it.id == event.decoration.id } ?: return false
            val locator = parseLocator(record.locatorJson) ?: return false
            showAnnotationEditor(
                PendingAnnotation(
                    selectedText = record.selectedText,
                    locator = locator,
                    existingId = record.id,
                    note = record.noteText,
                    color = record.color,
                    geometryJson = record.geometryJson
                )
            )
            return true
        }
    }

    private fun saveAnnotation(target: PendingAnnotation, note: String, color: String) {
        val libraryBook = book ?: return
        lifecycleScope.launch {
            val existingId = target.existingId
            if (existingId != null) {
                graph.readerRecords.updateAnnotation(existingId, note, color)
            } else {
                graph.readerRecords.saveAnnotation(
                    bookId = libraryBook.id,
                    bookTitle = libraryBook.title,
                    locatorJson = target.locator.toJSON().toString(),
                    selectedText = target.selectedText,
                    noteText = note,
                    color = color,
                    chapterTitle = currentChapterTitle(target.locator),
                    progress = target.locator.locations.totalProgression ?: currentProgress,
                    geometryJson = target.geometryJson
                )
            }
            navigator?.clearSelection()
        }
    }

    /**
     * Renders the translation and word cards as compact floating panels anchored
     * to the selection, instead of a bottom sheet that covered half the page.
     */
    @androidx.compose.runtime.Composable
    private fun ReaderFloatingLayer() {
        var grammarDraft by androidx.compose.runtime.remember { mutableStateOf<com.jerreader.android.data.GrammarNoteEntity?>(null) }
        // iOS drops the card's shadow while it is dragged.
        var floatingCardDragging by androidx.compose.runtime.remember { mutableStateOf(false) }
        grammarDraft?.let { draft ->
            com.jerreader.android.ui.GrammarNoteEditor(draft, graph.learningRecords) { grammarDraft = null }
        }
        val translation = currentTranslationState
        val lookup = currentWordLookupState
        val translationPreferences by graph.translationSettings.preferences.collectAsState()
        val interfacePreferences by graph.appSettings.preferences.collectAsState()
        BoxWithConstraints(modifier = Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            if (com.jerreader.android.BuildConfig.DEBUG) {
                val origin = coordinates.positionInWindow()
                translationViewportForTesting = RectF(origin.x, origin.y,
                    origin.x + coordinates.size.width, origin.y + coordinates.size.height)
            }
        }) {
            val available = maxHeight
            val isVertical = currentAppearance.orientation == ReaderTextOrientation.VERTICAL ||
                (currentAppearance.orientation == ReaderTextOrientation.PUBLICATION &&
                    publicationWritingModeIsVertical)
            val selectionGap = if (isVertical) 12.dp
                else if (translationPreferences.quickTranslationUnit == com.jerreader.shared.translation.QuickTranslationUnit.PARAGRAPH) 26.dp else 18.dp
            val density = LocalDensity.current
            val rawAnchorTop = with(density) { anchorTopPx.toDp() }
                .coerceIn(0.dp, available)
            val rawAnchorBottom = with(density) { anchorBottomPx.toDp() }
                .coerceIn(0.dp, available)
            // Keep clear of the bars while they show and of the screen edge
            // otherwise, as iOS `ReaderTranslationLayer` does. The old solver
            // compared against the full window and could put a result card on
            // top of the page controls. The layer itself already sits inside
            // the system bars, which the bars paint behind.
            val chromeShown = isChromeVisible
            val topChromeInLayer = with(density) {
                (if (chromeShown) (topChromeHeightPx - systemBarTopPx).coerceAtLeast(0) else 0).toDp()
            }
            val bottomChromeInLayer = with(density) {
                (if (chromeShown) (bottomChromeHeightPx - systemBarBottomPx).coerceAtLeast(0) else 0).toDp()
            }
            val edgeGap = ReaderInterfaceDefaults.FLOATING_CARD_EDGE_GAP_DP.dp
            val safeTop = (topChromeInLayer + edgeGap).coerceAtMost(available)
            val safeBottom = (available - bottomChromeInLayer - edgeGap)
                .coerceAtLeast(safeTop)
            if (com.jerreader.android.BuildConfig.DEBUG) {
                val safeTopPx = with(density) { safeTop.toPx() }
                val safeBottomPx = with(density) { safeBottom.toPx() }
                SideEffect {
                    val viewport = translationViewportForTesting
                    if (viewport != null) {
                        translationSafeAreaForTesting = RectF(
                            viewport.left, viewport.top + safeTopPx,
                            viewport.right, viewport.top + safeBottomPx
                        )
                    }
                }
            }
            val selectionTop = rawAnchorTop.coerceIn(safeTop, safeBottom)
            val selectionBottom = rawAnchorBottom.coerceIn(selectionTop, safeBottom)
            val roomBelow = (safeBottom - selectionBottom - selectionGap).coerceAtLeast(0.dp)
            val roomAbove = (selectionTop - safeTop - selectionGap).coerceAtLeast(0.dp)
            val minimumCardHeight = minOf(108.dp, (safeBottom - safeTop).coerceAtLeast(1.dp))
            val preferredCardHeight = minOf(
                (available * 0.30f).coerceAtLeast(166.dp),
                (safeBottom - safeTop).coerceAtLeast(1.dp)
            )
            val aboveFits = roomAbove >= minimumCardHeight
            val belowFits = roomBelow >= minimumCardHeight
            // Prefer the larger side, but use the side that can actually hold a
            // compact card whenever only one side can do so.
            val placeBelow = when {
                aboveFits && belowFits -> roomBelow > roomAbove
                belowFits -> true
                aboveFits -> false
                else -> roomBelow > roomAbove
            }
            val availableCardRoom = if (placeBelow) roomBelow else roomAbove
            // Never squeeze the card below a readable height, as iOS
            // `ReaderTranslationOverlayPlacement` does. A selection that fills
            // most of the page used to leave a sliver against the screen edge
            // with its header and drag handle cut off (GitHub issue #2); the
            // card now keeps its minimum and overlaps the selection instead.
            val cardHeight = minOf(
                preferredCardHeight,
                maxOf(availableCardRoom, minimumCardHeight)
            )
            // A vertical Japanese page reads in columns, so iOS puts the card
            // beside the tapped column instead of above or below it.

            val widthPx = constraints.maxWidth
            val availableWidth = with(density) { widthPx.toDp() }
            val anchorLeft = with(density) { anchorLeftPx.toDp() }.coerceIn(0.dp, availableWidth)
            val anchorRight = with(density) { anchorRightPx.toDp() }
                .coerceIn(anchorLeft, availableWidth)
            val safeHorizontalInset = 12.dp
            val roomLeft = (anchorLeft - safeHorizontalInset - selectionGap).coerceAtLeast(0.dp)
            val roomRight = (availableWidth - safeHorizontalInset - anchorRight - selectionGap)
                .coerceAtLeast(0.dp)
            val placeLeft = roomLeft >= roomRight
            val sideWidth = (if (placeLeft) roomLeft else roomRight)
                .coerceAtMost(if (availableWidth >= 700.dp) 320.dp else 220.dp)
            val verticalCardHeight = minOf(
                preferredCardHeight,
                (safeBottom - safeTop).coerceAtLeast(1.dp)
            )
            val verticalCardTop = (
                (selectionTop + selectionBottom) / 2 - verticalCardHeight / 2
            ).coerceIn(
                safeTop,
                (safeBottom - verticalCardHeight).coerceAtLeast(safeTop)
            )
            val topBannerHeight = minOf(preferredCardHeight, available * 0.30f)
            val topBannerCollides = rawAnchorTop < safeTop + topBannerHeight + selectionGap &&
                rawAnchorBottom > safeTop - selectionGap
            val useTopBanner = translationPreferences.displayMode == TranslationDisplayMode.TOP_BANNER &&
                !isVertical && !topBannerCollides

            // The initial solver above only knows the requested card height.
            // Compose can measure a different height after text wrapping,
            // font scaling, or a loading/result state change.  Correct the
            // *actual* measured bounds here so the card never remains over the
            // selected text just because the first estimate was optimistic.
            val safeLeftPx = with(density) { safeHorizontalInset.toPx() }
            val safeRightPx = with(density) { availableWidth.toPx() } - safeLeftPx
            val safeTopPx = with(density) { safeTop.toPx() }
            val safeBottomPx = with(density) { safeBottom.toPx() }
            val gapPx = with(density) { selectionGap.toPx() }
            val selectionLeftPx = minOf(anchorLeftPx, anchorRightPx).toFloat()
            val selectionRightPx = maxOf(anchorLeftPx, anchorRightPx).toFloat()
            val selectionTopPx = minOf(anchorTopPx, anchorBottomPx).toFloat()
            val selectionBottomPx = maxOf(anchorTopPx, anchorBottomPx).toFloat()
            val expandedSelectionLeft = selectionLeftPx - gapPx
            val expandedSelectionRight = selectionRightPx + gapPx
            val expandedSelectionTop = selectionTopPx - gapPx
            val expandedSelectionBottom = selectionBottomPx + gapPx

            val popupCollisionModifier = Modifier.onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInParent()
                if (com.jerreader.android.BuildConfig.DEBUG) {
                    val origin = coordinates.positionInWindow()
                    translationCardFrameForTesting = RectF(origin.x, origin.y,
                        origin.x + coordinates.size.width, origin.y + coordinates.size.height)
                }
                val hasAnchor = hasSelectionAnchor ||
                    selectionRightPx > selectionLeftPx ||
                    selectionBottomPx > selectionTopPx
                if (!hasAnchor) return@onGloballyPositioned
                // The reader moved the card: leave it where they put it.
                if (cardDragX != 0f || cardDragY != 0f) return@onGloballyPositioned
                val placement = floatingCardPlacement

                // Staying on screen comes before clearing the selection, so
                // every move stops at the safe area.
                fun withinSafeArea(shift: PopupShift) = PopupShift(
                    (bounds.left + shift.dx).coerceIn(placement.minX, placement.maxX) - bounds.left,
                    (bounds.top + shift.dy).coerceIn(placement.minY, placement.maxY) - bounds.top
                )

                fun isClear(shift: PopupShift): Boolean {
                    val left = bounds.left + shift.dx
                    val top = bounds.top + shift.dy
                    val right = bounds.right + shift.dx
                    val bottom = bounds.bottom + shift.dy
                    val withinSafeBounds = left >= safeLeftPx - 1f &&
                        right <= safeRightPx + 1f &&
                        top >= safeTopPx - 1f &&
                        bottom <= safeBottomPx + 1f
                    val intersectsSelection = left < expandedSelectionRight &&
                        right > expandedSelectionLeft &&
                        top < expandedSelectionBottom &&
                        bottom > expandedSelectionTop
                    return withinSafeBounds && !intersectsSelection
                }

                fun overlapArea(shift: PopupShift): Float {
                    val left = bounds.left + shift.dx
                    val top = bounds.top + shift.dy
                    val right = bounds.right + shift.dx
                    val bottom = bounds.bottom + shift.dy
                    val overlapWidth = (minOf(right, expandedSelectionRight) -
                        maxOf(left, expandedSelectionLeft)).coerceAtLeast(0f)
                    val overlapHeight = (minOf(bottom, expandedSelectionBottom) -
                        maxOf(top, expandedSelectionTop)).coerceAtLeast(0f)
                    return overlapWidth * overlapHeight
                }

                val current = PopupShift(0f, 0f)
                if (isClear(current)) return@onGloballyPositioned

                val candidates = listOf(
                    PopupShift(0f, expandedSelectionBottom - bounds.top),
                    PopupShift(0f, expandedSelectionTop - bounds.bottom),
                    PopupShift(expandedSelectionLeft - bounds.right, 0f),
                    PopupShift(expandedSelectionRight - bounds.left, 0f)
                ).map(::withinSafeArea)
                // No spot on screen clears the selection when it fills the
                // page. Cover less of it if a move really does, otherwise stay:
                // the old fallback pushed the card off screen to clear the
                // text, which left its drag handle out of reach (issue #2).
                // The margin is a pixel of movement, so rounding cannot make
                // the card hop between two equally poor spots.
                val worthMoving = overlapArea(current) - (bounds.width + bounds.height)
                val shift = candidates
                    .filter(::isClear)
                    .minByOrNull { abs(it.dx) + abs(it.dy) }
                    ?: candidates
                        .filter { overlapArea(it) < worthMoving }
                        .minByOrNull(::overlapArea)
                    ?: return@onGloballyPositioned
                val nextX = bounds.left + shift.dx - placement.originX
                val nextY = bounds.top + shift.dy - placement.originY
                if (abs(nextX - popupAvoidanceX) > 0.5f ||
                    abs(nextY - popupAvoidanceY) > 0.5f
                ) {
                    popupAvoidanceX = nextX
                    popupAvoidanceY = nextY
                }
            }
            // One placement step for every card, after iOS
            // `ReaderTranslationOverlayPlacement`: start beside the selection
            // using the measured size, add the drag and the collision
            // correction, then keep the whole card inside the safe area.
            fun placedCard(origin: (width: Int, height: Int) -> Offset) =
                Modifier.layout { measurable, layerConstraints ->
                    val placeable = measurable.measure(
                        androidx.compose.ui.unit.Constraints(
                            maxWidth = (safeRightPx - safeLeftPx).toInt().coerceAtLeast(0),
                            maxHeight = layerConstraints.maxHeight
                        )
                    )
                    layout(layerConstraints.maxWidth, layerConstraints.maxHeight) {
                        val start = origin(placeable.width, placeable.height)
                        val placement = floatingCardPlacement
                        placement.originX = start.x
                        placement.originY = start.y
                        placement.minX = safeLeftPx
                        placement.maxX = maxOf(safeLeftPx, safeRightPx - placeable.width)
                        placement.minY = safeTopPx
                        placement.maxY = maxOf(safeTopPx, safeBottomPx - placeable.height)
                        placeable.place(
                            (start.x + cardDragX + popupAvoidanceX)
                                .coerceIn(placement.minX, placement.maxX).roundToInt(),
                            (start.y + cardDragY + popupAvoidanceY)
                                .coerceIn(placement.minY, placement.maxY).roundToInt()
                        )
                    }
                }
            val layerWidthPx = constraints.maxWidth.toFloat()
            val cardModifier = when {
                useTopBanner ->
                    placedCard { width, _ -> Offset((layerWidthPx - width) / 2f, safeTopPx) }
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .heightIn(max = topBannerHeight)
                        .then(popupCollisionModifier)

                isVertical && sideWidth >= 144.dp -> {
                    val verticalCardTopPx = with(density) { verticalCardTop.toPx() }
                    placedCard { width, _ ->
                        Offset(
                            if (placeLeft) gapPx else layerWidthPx - gapPx - width,
                            verticalCardTopPx
                        )
                    }
                        .widthIn(max = sideWidth)
                        .heightIn(max = verticalCardHeight)
                        .then(popupCollisionModifier)
                }
                else -> {
                    val belowPx = with(density) { (selectionBottom + selectionGap).toPx() }
                    val abovePx = with(density) { (selectionTop - selectionGap).toPx() }
                    placedCard { _, height ->
                        Offset(safeLeftPx, if (placeBelow) belowPx else abovePx - height)
                    }
                        // iOS keeps the overlay narrow so the page stays readable
                        // beside it instead of spanning the full width.
                        .widthIn(max = 320.dp)
                        .fillMaxWidth()
                        .heightIn(max = cardHeight)
                        .then(popupCollisionModifier)
                }
            }

            contextualLookupInput?.let { input ->
                androidx.compose.runtime.key(input) {
                com.jerreader.android.ui.ContextualLookupSheet(
                    input = input, dictionary = { graph.offlineDictionary.japaneseChinese },
                    lookup = androidx.compose.runtime.remember { com.jerreader.android.lexical.DefaultLexicalLookupService(offline = graph.offlineDictionary) },
                    explanationService = (graph.translationService as? ContextExplanationService)
                        .takeIf { graph.translationSettings.preferredAIProviderMode() != null },
                    onRecord = { value -> graph.learningRecords.recordLookup(value, book?.id, book?.title) },
                    onAnalysis = { snapshot, result -> graph.learningRecords.updateContextualAnalysis(snapshot, result) },
                    onFavorite = { snapshot, favorite -> graph.learningRecords.setContextualFavorite(snapshot.lookupKey, favorite) },
                    onDismiss = { contextualLookupInput = null })
                }
            }
            if (translation != null) {
                FloatingSurface(cardModifier, dragging = floatingCardDragging) {
                    TranslationCard(
                        state = translation,
                        // The eyebrow names the direction while it loads, from
                        // the same choice the tap controllers send.
                        requestSourceLanguage = translationPreferences.sourceChoice.language ?: currentBookLanguage(),
                        requestTargetLanguage = translationPreferences.targetLanguage,
                        loadingDetail = if (translationPreferences.providerMode ==
                            com.jerreader.shared.translation.TranslationProviderMode.ON_DEVICE
                        ) "正在使用 Android 本机翻译" else "正在安全请求所选 AI 服务",
                        japaneseFuriganaEnabled = interfacePreferences.japaneseFuriganaEnabled,
                        onToggleFurigana = if (translation is TranslationCardState.Success &&
                            (translation.result.sourceLanguage == LanguageCode.JAPANESE || translation.result.targetLanguage == LanguageCode.JAPANESE)) {
                            { graph.appSettings.updateJapaneseFurigana(!interfacePreferences.japaneseFuriganaEnabled) }
                        } else null,
                        // iOS: a caption 原文注音 and the furigana above a
                        // hairline, then the translation in body text with 5pt
                        // of extra line spacing.
                        translationContent = { success ->
                            if (interfacePreferences.japaneseFuriganaEnabled && success.result.sourceLanguage == LanguageCode.JAPANESE) {
                                Text("原文注音", style = MaterialTheme.typography.labelMedium, color = jerreaderPalette.secondaryText)
                                com.jerreader.android.ui.JapaneseFuriganaText(success.sourceText, graph.offlineDictionary, Modifier.fillMaxWidth())
                                com.jerreader.shared.ui.JerreaderDivider(color = jerreaderPalette.line)
                            }
                            if (interfacePreferences.japaneseFuriganaEnabled && success.result.targetLanguage == LanguageCode.JAPANESE)
                                com.jerreader.android.ui.JapaneseFuriganaText(success.result.translatedText, graph.offlineDictionary, Modifier.fillMaxWidth())
                            else Text(
                                success.result.translatedText,
                                modifier = Modifier.semantics { contentDescription = "译文：${success.result.translatedText}" },
                                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.5.sp)
                            )
                        },
                        onDismiss = ::dismissTranslation,
                        onRetry = ::retryTranslation,
                        onCopy = ::copyToClipboard,
                        onFavorite = { result -> favoriteTranslation(translation, result) },
                        onExplain = { explainTranslation(translation) },
                        onLens = { showReadingLensChooser(translation) },
                        onSaveAnalysis = {
                            val success = translation as? TranslationCardState.Success
                            val analysis = success?.analysisText
                            if (analysis != null) {
                                grammarDraft = com.jerreader.android.data.GrammarNoteEntity(
                                    title = com.jerreader.android.data.grammarNoteTitle(success.sourceText), sentence = success.sourceText,
                                    explanation = analysis, language = success.result.sourceLanguage.tag,
                                    bookId = book?.id, bookTitle = book?.title,
                                    locatorJson = currentTranslationLocatorJson ?: latestLocatorJson)

                            }
                        },
                        onAnnotate = ::annotateTranslatedText,
                        onExpandCrossPage = { expandCrossPageSegment(translation) },
                        onAddToVocabulary = { addTranslatedWordToVocabulary(translation) },
                        onLookup = { lifecycleScope.launch {
                            val context = try {
                                kotlinx.coroutines.withTimeoutOrNull(1_000) { tapTranslationController?.lookupContext(translation.sourceText) }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { null } ?: translation.sourceText
                            if (currentTranslationState == translation) {
                                contextualLookupInput = com.jerreader.android.ui.ContextualLookupInput(
                                    translation.sourceText, context,
                                    translationSourceLanguage(translation) ?: LanguageCode.JAPANESE)
                            }
                        } },
                        crossPageMessage = crossPageMessage,
                        isExpandingCrossPage = isExpandingCrossPage,
                        onDrag = ::dragFloatingCard,
                        onDraggingChange = { floatingCardDragging = it },
                        onOpenSettings = ::openTranslationSettings
                    )
                }
            } else if (lookup != null) {
                FloatingSurface(cardModifier) {
                    WordLookupCard(
                        state = lookup,
                        onDismiss = ::dismissWordLookup,
                        onRetry = { wordInteractionController?.retry() },
                        onLookup = { contextualLookupInput = com.jerreader.android.ui.ContextualLookupInput(
                            lookup.analysis.surfaceForm, lookup.analysis.sentenceContext, lookup.analysis.language) },
                        onCopy = ::copyToClipboard,
                        onFavorite = { explanation ->
                            lifecycleScope.launch {
                                val key = LearningRecordRepository.wordKey(explanation)
                                // Already recorded when the card loaded; only
                                // star it, so one lookup is not counted twice.
                                if (recordedReaderLookupKey == key) {
                                    graph.learningRecords.setWordFavorite(key, true)
                                } else {
                                    graph.learningRecords.recordLookup(
                                        explanation = explanation,
                                        sourceBookId = book?.id,
                                        sourceBookTitle = book?.title,
                                        favorite = true
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    /**
     * Moves the card with the reader's finger, starting from where it really
     * is and stopping at the safe area. The old limit was a share of the
     * screen either way, which let the header and its drag handle leave the
     * screen with no way to bring the card back (GitHub issue #2).
     */
    private fun dragFloatingCard(dx: Float, dy: Float) {
        val placement = floatingCardPlacement
        val restX = placement.originX + popupAvoidanceX
        val restY = placement.originY + popupAvoidanceY
        val x = (restX + cardDragX).coerceIn(placement.minX, placement.maxX)
        val y = (restY + cardDragY).coerceIn(placement.minY, placement.maxY)
        cardDragX = (x + dx).coerceIn(placement.minX, placement.maxX) - restX
        cardDragY = (y + dy).coerceIn(placement.minY, placement.maxY) - restY
    }

    /**
     * The paper under the floating cards, after iOS `ReaderTranslationOverlay`:
     * radius 20, a 0.75dp `line` hairline, an e3 shadow on light paper only and
     * none while the card is dragged, and a single reveal when it appears. The
     * reveal and the shadow sit after [modifier], so the frame that places the
     * card (and that the tests read) never moves with them.
     */
    @androidx.compose.runtime.Composable
    private fun FloatingSurface(
        modifier: Modifier,
        dragging: Boolean = false,
        content: @androidx.compose.runtime.Composable () -> Unit
    ) {
        val palette = jerreaderPalette
        val shape = RoundedCornerShape(JerreaderRadius.card)
        Surface(
            modifier = modifier
                .jerreaderReveal(order = 0)
                .then(
                    if (palette.isDark || dragging) Modifier
                    else Modifier.jerreaderElevation(JerreaderElevation.FLOATING, shape)
                ),
            shape = shape,
            color = palette.paper,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(0.75.dp, palette.line),
            content = { content() }
        )
    }

    /** Anchors the floating card to the current selection, or to the tap point. */
    private fun updateFloatingAnchor() {
        lifecycleScope.launch {
            if (hasSelectionAnchor) return@launch
            val rect = navigator?.currentSelection()?.rect
            val density = resources.displayMetrics.density
            if (rect != null) {
                // Readium reports this rect in navigator coordinates: it adds
                // its own page padding, unlike the page script's rects.
                val offset = offsetInOverlay(navigator?.publicationView)
                anchorTopPx = (offset[1] + rect.top).toInt()
                anchorBottomPx = (offset[1] + rect.bottom).toInt()
                anchorLeftPx = (offset[0] + rect.left).toInt()
                anchorRightPx = (offset[0] + rect.right).toInt()
                return@launch
            }
            val epubPoint = tapTranslationController?.focusPoint()
            val pdfPoint = pdfTranslationController?.focusPoint()
            if (epubPoint != null) {
                // Readium hands taps over in the page WebView's own pixels.
                val offset = offsetInOverlay(visibleEpubWebView() ?: navigator?.publicationView)
                val x = epubPoint.x * density + offset[0]
                val y = epubPoint.y * density + offset[1]
                anchorLeftPx = x.toInt()
                anchorRightPx = x.toInt()
                anchorTopPx = (y - 12 * density).toInt()
                anchorBottomPx = (y + 12 * density).toInt()
            } else if (pdfPoint != null) {
                val offset = offsetInOverlay(pdfNavigator?.publicationView)
                val x = pdfPoint.x + offset[0]
                val y = pdfPoint.y + offset[1]
                anchorLeftPx = x.toInt()
                anchorRightPx = x.toInt()
                anchorTopPx = (y - 12 * density).toInt()
                anchorBottomPx = (y + 12 * density).toInt()
            }
        }
    }

    /**
     * Where [view] sits in the floating layers' content, which starts inside
     * their system-bar padding.
     */
    private fun offsetInOverlay(view: View?): IntArray {
        val result = IntArray(2)
        val target = view ?: return result
        val layer = highlightLayer ?: floatingLayer ?: return result
        val viewLocation = IntArray(2)
        val layerLocation = IntArray(2)
        target.getLocationInWindow(viewLocation)
        layer.getLocationInWindow(layerLocation)
        result[0] = viewLocation[0] - layerLocation[0] - layer.paddingLeft
        result[1] = viewLocation[1] - layerLocation[1] - layer.paddingTop
        return result
    }

    /**
     * The WebView of the page on screen. Readium insets each page inside the
     * navigator by its vertical padding, and the page script measures from
     * the WebView, so script rects and tap points are placed from here.
     */
    private fun visibleEpubWebView(): View? {
        val root = navigator?.publicationView ?: return null
        val rootLocation = IntArray(2)
        root.getLocationInWindow(rootLocation)
        val centreX = rootLocation[0] + root.width / 2
        var found: View? = null
        fun visit(view: View) {
            if (found != null) return
            if (view is android.webkit.WebView) {
                if (view.isShown && view.width > 0) {
                    val location = IntArray(2)
                    view.getLocationInWindow(location)
                    if (centreX >= location[0] && centreX < location[0] + view.width) found = view
                }
                return
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
        return found
    }

    /**
     * iOS puts the bars away when a card opens; in the standard layout they
     * stay regardless, so only the immersive one changes.
     */
    private fun hideControlsForCard() {
        if (layoutMode == ReaderLayoutMode.IMMERSIVE) cancelChromeAutoHide()
        setControlsVisible(false)
    }

    private fun setFloatingLayerVisible(visible: Boolean) {
        floatingLayer?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Clears cards without touching the always-visible PDF highlight layer. */
    private fun clearTransientCardPresentation() {
        currentTranslationState = null
        currentTranslationLocatorJson = null
        currentWordLookupState = null
        hasSelectionAnchor = false
        crossPageMessage = null
        isExpandingCrossPage = false
        cardDragX = 0f
        cardDragY = 0f
        popupAvoidanceX = 0f
        popupAvoidanceY = 0f
        setFloatingLayerVisible(false)
    }

    private fun dismissTranslation() {
        selectionRects = emptyList()
        hasSelectionAnchor = false
        tapTranslationController?.clearSelection()
        pdfTranslationController?.clearSelection()
        currentTranslationState = null
        currentTranslationLocatorJson = null
        crossPageMessage = null
        popupAvoidanceX = 0f
        popupAvoidanceY = 0f
        setFloatingLayerVisible(currentWordLookupState != null)
    }

    private fun resetTransientReaderState() {
        selectionRects = emptyList()
        hasSelectionAnchor = false
        tapTranslationController?.clearSelection()
        pdfTranslationController?.clearSelection()
        wordInteractionController?.clearSelection()
        currentTranslationState = null
        currentTranslationLocatorJson = null
        currentWordLookupState = null
        crossPageMessage = null
        isExpandingCrossPage = false
        cardDragX = 0f
        cardDragY = 0f
        popupAvoidanceX = 0f
        popupAvoidanceY = 0f
        setFloatingLayerVisible(false)
    }

    // The app uses the platform logger and does not ship a Timber dependency.
    @android.annotation.SuppressLint("LogNotTimber")
    private fun recordReadingQuery() {
        val id = book?.id ?: return
        lifecycleScope.launch {
            try { graph.repository.recordReadingQuery(id) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { android.util.Log.w("Jerreader", "Reading query statistics could not be saved") }
        }
    }

    private fun showWordLookup(state: WordLookupCardState) {
        if (state is WordLookupCardState.Loading) recordReadingQuery()
        hideControlsForCard()
        if (state is WordLookupCardState.Success) recordReaderLookup(state.explanation)
        popupAvoidanceX = 0f
        popupAvoidanceY = 0f
        currentWordLookupState = state
        setFloatingLayerVisible(true)
        updateFloatingAnchor()
    }

    /**
     * A short tap (or the selection menu's 查词) writes the learning record
     * as soon as the dictionary answers, with the tapped sentence, the same
     * as the iOS contextual lookup. The companion's confusion map reads these.
     */
    private fun recordReaderLookup(explanation: WordExplanation) {
        val key = LearningRecordRepository.wordKey(explanation)
        lifecycleScope.launch {
            try {
                graph.learningRecords.recordLookup(
                    explanation = explanation,
                    sourceBookId = book?.id,
                    sourceBookTitle = book?.title
                )
                recordedReaderLookupKey = key
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                android.util.Log.w("Jerreader", "Reader word lookup could not be recorded")
            }
        }
    }

    private fun showTranslation(state: TranslationCardState) {
        if (state is TranslationCardState.Loading) recordReadingQuery()
        hideControlsForCard()
        cardDragX = 0f
        cardDragY = 0f
        popupAvoidanceX = 0f
        popupAvoidanceY = 0f
        currentTranslationState = state
        currentTranslationLocatorJson = latestLocatorJson
        setFloatingLayerVisible(true)
        updateFloatingAnchor()
    }


    /**
     * The reader draws its own selection highlight, the way iOS uses a
     * non-interactive UIKit layer. Relying on the WebView's native ::selection
     * paint means the colour cannot follow the reading theme and the drag
     * handles fight with Readium's own gestures.
     */
    private fun updateSelectionHighlight(rects: List<RectF>) {
        applySelectionRects(rects, paints = true)
    }

    /**
     * EPUB paints its own tiles inside the page, where the rects were measured.
     * Painting the same rects again on this layer stacked a second translucent
     * fill and a second outline on top of the first, so the reflowable path only
     * reports geometry for anchoring the card.
     */
    private fun updateSelectionAnchor(rects: List<RectF>) {
        applySelectionRects(rects, paints = false)
    }

    private fun applySelectionRects(rects: List<RectF>, paints: Boolean) {
        // Measure where the page actually sits instead of deriving it from the
        // inset constants; Readium adds its own padding inside the navigator,
        // so a computed offset put the highlight a line off.
        updateReaderContentOffset()
        selectionRects = if (paints) rects else emptyList()
        hasSelectionAnchor = rects.isNotEmpty()
        if (rects.isNotEmpty()) {
            popupAvoidanceX = 0f
            popupAvoidanceY = 0f
            anchorTopPx = (readerContentTopPx + rects.minOf { it.top }).toInt()
            anchorBottomPx = (readerContentTopPx + rects.maxOf { it.bottom }).toInt()
            anchorLeftPx = (readerContentLeftPx + rects.minOf { it.left }).toInt()
            anchorRightPx = (readerContentLeftPx + rects.maxOf { it.right }).toInt()
        }
    }

    private fun updateReaderContentOffset() {
        // EPUB rects come from the page script, measured in the page's
        // WebView; PDF rects are measured in the PDF view itself.
        val contentView = visibleEpubWebView()
            ?: navigator?.publicationView
            ?: pdfNavigator?.publicationView
        val offset = offsetInOverlay(contentView)
        readerContentLeftPx = offset[0]
        readerContentTopPx = offset[1].coerceAtLeast(0)
    }

    @androidx.compose.runtime.Composable
    private fun SelectionHighlightLayer() {
        val rects = selectionRects
        val persistedRects = pdfAnnotationRects
        if (rects.isEmpty() && persistedRects.isEmpty()) return
        val selectionPalette = ReaderSelectionVisualStyle.palette(currentAppearance)
        val tint = selectionPalette.fillArgb.toComposeColor()
        val stroke = selectionPalette.strokeArgb.toComposeColor()
        val persistedTint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
        val top = readerContentTopPx.toFloat()
        Canvas(modifier = Modifier.fillMaxSize()) {
            persistedRects.forEach { rect ->
                drawRoundRect(
                    color = persistedTint,
                    topLeft = Offset(rect.left + readerContentLeftPx, rect.top + top),
                    size = Size(rect.width(), rect.height()),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
            rects.forEach { rect ->
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(rect.left + readerContentLeftPx, rect.top + top),
                    size = Size(rect.width(), rect.height()),
                    cornerRadius = CornerRadius(4f, 4f)
                )
                drawRoundRect(
                    color = stroke,
                    topLeft = Offset(rect.left + readerContentLeftPx, rect.top + top),
                    size = Size(rect.width(), rect.height()),
                    cornerRadius = CornerRadius(4f, 4f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
                )
            }
        }
    }

    private fun Long.toComposeColor(): androidx.compose.ui.graphics.Color {
        return androidx.compose.ui.graphics.Color(
            red = ((this shr 16) and 0xFF).toInt() / 255f,
            green = ((this shr 8) and 0xFF).toInt() / 255f,
            blue = (this and 0xFF).toInt() / 255f,
            alpha = ((this shr 24) and 0xFF).toInt() / 255f
        )
    }

    private fun performTranslationHaptic() {
        rootView?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun retryTranslation() {
        val pdf = pdfTranslationController
        if (pdf != null) {
            if (!pdf.retry()) {
                crossPageMessage = "同一段文字 5 秒内只能手动重试一次。"
            }
            return
        }
        val accepted = tapTranslationController?.retryManually() ?: false
        if (!accepted) {
            crossPageMessage = "同一段文字 5 秒内只能手动重试一次。"
        }
    }

    /** iOS adds the word with a real dictionary entry, not just the translation. */
    private fun addTranslatedWordToVocabulary(state: TranslationCardState) {
        val word = state.sourceText.trim()
        if (word.isEmpty()) return
        lifecycleScope.launch {
            val explanation = runCatching {
                withContext(Dispatchers.IO) { graph.lexicalLookupService.lookup(
                    word = word,
                    sentenceContext = null,
                    language = translationSourceLanguage(state) ?: LanguageCode.ENGLISH,
                    candidates = emptyList()
                ) }
            }.getOrNull()
            if (explanation == null) {
                crossPageMessage = "词典暂时不可用，未加入生词本。"
                return@launch
            }
            graph.learningRecords.recordLookup(
                explanation = explanation,
                sourceBookId = book?.id,
                sourceBookTitle = book?.title,
                favorite = true
            )
            crossPageMessage = "已加入生词本。"
        }
    }

    private fun favoriteTranslation(state: TranslationCardState, result: TranslationResult) {
        lifecycleScope.launch {
            crossPageMessage = "已收藏到学习页。"
            graph.learningRecords.saveTranslationFavorite(
                sourceText = state.sourceText,
                result = result,
                bookId = book?.id,
                bookTitle = book?.title,
                locatorJson = currentTranslationLocatorJson ?: latestLocatorJson
            )
        }
    }

    /**
     * Without [lensMode] this is the original sentence-structure analysis. With
     * one it is an AI reading-lens question, asked with a little surrounding
     * text and a hint level drawn from this book's real reading difficulties.
     */
    private fun explainTranslation(state: TranslationCardState, lensMode: AIReadingLensMode? = null) {
        val service = graph.translationService as? ContextExplanationService ?: return
        lifecycleScope.launch {
            crossPageMessage = if (lensMode == null) "正在请求 AI 解析…" else "正在生成“${lensMode.title}”…"
            val context = if (lensMode == null) null else readingLensContext(state.sourceText)
            val lens = lensMode?.let { mode ->
                ReadingLensOptions.from(
                    mode,
                    companionMode,
                    AdaptiveReadingAssistPolicy.resolve(readingSignals(), companionMode, book?.id)
                )
            }
            val outcome = runCatching {
                withContext(Dispatchers.IO) { service.explain(
                    ContextExplanationRequest(
                        focusedText = state.sourceText,
                        contextText = context,
                        sourceLanguage = translationSourceLanguage(state),
                        lens = lens
                    )
                ) }
            }
            val explained = outcome.getOrNull()
            if (explained == null) {
                // A ChatGPT plan out of quota or signed out says so instead.
                crossPageMessage = outcome.exceptionOrNull()?.takeIf { ChatGPTPlanMessages.isStop(it) }?.message
                    ?: if (lensMode == null) {
                        "AI 解析不可用，请先在设置里配置 AI 服务。"
                    } else {
                        "AI 阅读透镜不可用，请先在设置里配置 AI 服务。"
                    }
                return@launch
            }
            crossPageMessage = null
            val current = currentTranslationState
            if (current is TranslationCardState.Success && current.sourceText == state.sourceText) {
                currentTranslationState = if (lensMode == null || lens == null) {
                    current.copy(
                        analysisText = explained.explanation,
                        analysisTitle = "句子结构",
                        analysisDetail = null,
                        analysisSavable = true
                    )
                } else {
                    current.copy(
                        analysisText = explained.explanation,
                        analysisTitle = lensMode.title,
                        analysisDetail = buildString {
                            append(if (context.isNullOrBlank()) "依据：当前选区" else "依据：当前选区 + 有限前后文")
                            currentChapterName.takeIf(String::isNotBlank)?.let { append(" · $it") }
                            append(" · ${lens.companionMode.title}伴读 · ${lens.assistLevel.title} · 防剧透")
                        },
                        analysisSavable = lensMode == AIReadingLensMode.GRAMMAR
                    )
                }
            }
        }
    }

    /** A little text around the selection; a slow page never holds the request up. */
    private suspend fun readingLensContext(sourceText: String): String? = try {
        kotlinx.coroutines.withTimeoutOrNull(1_000) {
            pdfTranslationController?.crossPageContext(sourceText)
                ?: tapTranslationController?.crossPageContext(sourceText)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun readingSignals(): List<ReadingErrorSignal> = try {
        graph.learningRecords.readingErrorSignals()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }

    private fun showReadingLensChooser(state: TranslationCardState) {
        showComposeDialog { dialog ->
            AIReadingLensChooser(
                companionMode = companionMode,
                onChoose = { mode ->
                    dialog.dismiss()
                    explainTranslation(state, mode)
                },
                onModeChange = ::persistCompanionMode,
                onDismiss = dialog::dismiss
            )
        }
    }

    private fun showReadingCompanion() {
        val currentBook = book ?: return
        hasCompanionUpdate = false
        lifecycleScope.launch {
            val confusion = ReadingConfusionMapPolicy.build(readingSignals(), bookId = currentBook.id)
            val closureChapter = finishedChapterTitle ?: currentChapterName.ifBlank { "当前章节" }
            showComposeDialog { dialog ->
                ReaderCompanionPanel(
                    mode = companionMode,
                    recap = ContinuationRecapPolicy.build(
                        mode = companionMode,
                        bookTitle = currentBook.title,
                        chapterTitle = currentChapterName,
                        confusionItems = confusion.items
                    ),
                    confusion = confusion,
                    closureChapterTitle = closureChapter,
                    onModeChange = ::persistCompanionMode,
                    onSearchTerm = { term ->
                        dialog.dismiss()
                        searchBookFor(term)
                    },
                    onStartClosure = {
                        dialog.dismiss()
                        showChapterClosure(closureChapter, confusion)
                    },
                    onDismiss = dialog::dismiss
                )
            }
        }
    }

    private fun showChapterClosure(chapterTitle: String, confusion: ReadingConfusionMap) {
        finishedChapterTitle = null
        val plan = ChapterClosurePolicy.build(companionMode, chapterTitle, confusion.items)
        showComposeDialog { dialog ->
            ReaderChapterClosurePanel(
                plan = plan,
                onSearchTerm = { term ->
                    dialog.dismiss()
                    searchBookFor(term)
                },
                onDismiss = dialog::dismiss
            )
        }
    }

    /** Words keep no locator, so "back to the text" is a book search for them. */
    private fun searchBookFor(term: String) {
        searchQuery = term
        runSearch()
        showNavigationPanel()
    }

    private fun loadCompanionMode(bookId: String): ReadingCompanionMode =
        ReadingCompanionMode.fromStorageId(
            getSharedPreferences(COMPANION_PREFERENCES, MODE_PRIVATE)
                .getString(companionModeKey(bookId), null)
        )

    /** The mode belongs to the book: a novel and an essay collection read differently. */
    private fun persistCompanionMode(mode: ReadingCompanionMode) {
        companionMode = mode
        val bookId = book?.id ?: return
        getSharedPreferences(COMPANION_PREFERENCES, MODE_PRIVATE).edit {
            putString(companionModeKey(bookId), mode.storageId)
        }
    }

    private fun companionModeKey(bookId: String) = "mode_$bookId"

    /**
     * Completes a sentence cut by a screen or page boundary before asking the
     * translation service again. The reader only sends one extra request, and
     * only when it could rebuild a sentence it can confirm locally.
     */
    private fun expandCrossPageSegment(state: TranslationCardState) {
        if (isExpandingCrossPage) return
        isExpandingCrossPage = true
        crossPageMessage = null
        lifecycleScope.launch {
            try {
                val pdfController = pdfTranslationController
                val epubController = tapTranslationController
                val context = pdfController?.crossPageContext(state.sourceText)
                    ?: epubController?.crossPageContext(state.sourceText)
                val expansion = ReaderCrossPageTranslationResolver.expansion(
                    sourceText = state.sourceText,
                    contextText = context,
                    language = translationSourceLanguage(state)
                )
                if (expansion == null) {
                    crossPageMessage = "没有找到可确认的完整句段，已保留当前译文。"
                    return@launch
                }
                val preferences = graph.translationSettings.preferences.value
                val request = TranslationRequest(
                    text = expansion.text,
                    sourceLanguage = preferences.sourceChoice.language ?: translationSourceLanguage(state),
                    targetLanguage = preferences.targetLanguage
                )
                if (pdfController != null) {
                    pdfController.translateExpanded(request)
                } else {
                    epubController?.translateExpanded(request)
                }
            } finally {
                isExpandingCrossPage = false
            }
        }
    }

    private fun annotateTranslatedText(text: String) {
        val locator = latestLocator ?: return
        val geometryJson = pdfTranslationController?.selectionGeometry()?.let { geometry ->
            JSONArray().put(
                JSONObject()
                    .put("x", geometry.left)
                    .put("y", geometry.top)
                    .put("w", geometry.width())
                    .put("h", geometry.height())
            ).toString()
        }
        dismissTranslation()
        showAnnotationEditor(
            PendingAnnotation(
                selectedText = text,
                locator = locator,
                geometryJson = geometryJson
            )
        )
    }

    private fun openTranslationSettings() {
        dismissTranslation()
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun dismissWordLookup() {
        wordInteractionController?.clearSelection()
        currentWordLookupState = null
        setFloatingLayerVisible(currentTranslationState != null)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Jerreader", text))
        // These actions used to finish silently, which reads as "nothing
        // happened"; the card now confirms each one.
        crossPageMessage = "已复制到剪贴板。"
    }

    internal fun showTranslationGeometryForTesting(selection: RectF, vertical: Boolean, state: TranslationCardState.Success) {
        check(com.jerreader.android.BuildConfig.DEBUG)
        val viewport = checkNotNull(translationViewportForTesting)
        val density = resources.displayMetrics.density
        anchorLeftPx = (selection.left * density).toInt()
        anchorRightPx = (selection.right * density).toInt()
        anchorTopPx = (selection.top * density).toInt()
        anchorBottomPx = (selection.bottom * density).toInt()
        hasSelectionAnchor = true
        currentAppearance = currentAppearance.copy(orientation = if (vertical) ReaderTextOrientation.VERTICAL else ReaderTextOrientation.HORIZONTAL)
        cardDragX = 0f; cardDragY = 0f; popupAvoidanceX = 0f; popupAvoidanceY = 0f
        // Keep the last actual outer frame: changing bounded scroll content
        // does not necessarily produce a new onGloballyPositioned callback.
        translationSourceFrameForTesting = RectF(viewport.left + selection.left * density,
            viewport.top + selection.top * density, viewport.left + selection.right * density,
            viewport.top + selection.bottom * density)
        currentTranslationState = state
        setFloatingLayerVisible(true)
    }

    /** Drags the translation card by the same path as its handle, in pixels. */
    internal fun dragTranslationCardForTesting(dx: Float, dy: Float) {
        check(com.jerreader.android.BuildConfig.DEBUG)
        dragFloatingCard(dx, dy)
    }

    /** The bottom bar's 上一页 / 下一页. */
    internal fun turnPageForTesting(forward: Boolean) {
        check(com.jerreader.android.BuildConfig.DEBUG)
        if (forward) goToNextPage() else goToPreviousPage()
    }

    /** Window frames of the bars while they show, for the overlay tests. */
    internal fun chromeFramesForTesting(): Pair<RectF?, RectF?> {
        fun frame(view: View?): RectF? {
            if (view == null || !isChromeVisible || view.visibility != View.VISIBLE) return null
            val location = IntArray(2)
            view.getLocationInWindow(location)
            return RectF(
                location[0].toFloat(), location[1].toFloat(),
                (location[0] + view.width).toFloat(), (location[1] + view.height).toFloat()
            )
        }
        return frame(topChromeView) to frame(bottomChromeView)
    }

    /** The page container's top and bottom padding, in pixels. */
    internal fun readerContentPaddingForTesting(): Pair<Int, Int> =
        (readerContainerView?.paddingTop ?: 0) to (readerContainerView?.paddingBottom ?: 0)

    internal fun systemBarInsetsForTesting(): Pair<Int, Int> = systemBarTopPx to systemBarBottomPx

    internal fun revealZonesVisibleForTesting(): Boolean =
        revealTopView?.visibility == View.VISIBLE && revealBottomView?.visibility == View.VISIBLE

    internal fun tapRevealZoneForTesting(top: Boolean): Boolean =
        (if (top) revealTopView else revealBottomView)?.performClick() == true

    /** The path a tap on no text takes. */
    internal fun tapBlankPageForTesting() = handleContentTap()

    internal suspend fun lookupAtForTesting(point: PointF): WordLookupCardState? =
        wordInteractionController?.lookupAt(point)

    internal suspend fun translateAtForTesting(point: PointF): TranslationCardState? =
        tapTranslationController?.translateAt(point)
            ?: pdfTranslationController?.translateAt(point)

    /** Feeds a navigator-pixel point through the real Readium tap conversion. */
    internal suspend fun translateAtDevicePointForTesting(point: PointF): TranslationCardState? =
        tapTranslationController?.translateAtDevicePoint(point)

    internal fun analyzeSelectedTextForTesting(
        text: String,
        language: LanguageCode? = null
    ): WordAnalysis? = wordInteractionController?.analyzeSelectedTextForTesting(text, language)

    private fun showComposeDialog(content: @androidx.compose.runtime.Composable (Dialog) -> Unit) {
        val dialog = ComponentDialog(this)
        dialog.setContentView(dialogComposeView {
            ReaderTheme {
                Surface { content(dialog) }
            }
        })
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    @androidx.compose.runtime.Composable
    private fun ReaderTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
        val preferences by graph.appSettings.preferences.collectAsState()
        JerreaderTheme(accent = preferences.theme, content = content)
    }

    /**
     * Bars, cards and highlights take light or dark from the page, as iOS sets
     * the reader's colour scheme from `prefersDarkReaderChrome`, so a dark page
     * gets light controls whatever the system theme.
     */
    @androidx.compose.runtime.Composable
    private fun ReaderPageTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
        val preferences by graph.appSettings.preferences.collectAsState()
        JerreaderTheme(
            accent = preferences.theme,
            dark = ReaderInterfaceDefaults.prefersDarkChrome(currentAppearance),
            content = content
        )
    }

    private fun dialogComposeView(
        content: @androidx.compose.runtime.Composable () -> Unit
    ): ComposeView = ComposeView(this).apply {
        setViewTreeLifecycleOwner(this@ReaderActivity)
        setViewTreeViewModelStoreOwner(this@ReaderActivity)
        setViewTreeSavedStateRegistryOwner(this@ReaderActivity)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent(content)
    }

    /**
     * iOS `readerFailure`. 重新打开 starts the reader again from its intent,
     * the way the shelf opened it; 返回书架 closes it.
     */
    private fun showError(message: String) {
        val root = rootView ?: return
        root.removeAllViews()
        root.addView(
            readerComposeView {
                ReaderFailureView(
                    message = message,
                    onBackToLibrary = ::finish,
                    onReopen = ::recreate
                )
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun intentSnapshot(): PublicationSnapshot? {
        val path = intent.getStringExtra(EXTRA_PUBLICATION_PATH) ?: return null
        val sha256 = intent.getStringExtra(EXTRA_EXPECTED_SHA256) ?: return null
        val lastModified = intent.getLongExtra(EXTRA_EXPECTED_LAST_MODIFIED, Long.MIN_VALUE)
        if (lastModified == Long.MIN_VALUE) return null
        return PublicationSnapshot(path, sha256, lastModified)
    }

    private fun parseLocator(json: String): Locator? = runCatching {
        Locator.fromJSON(JSONObject(json))
    }.getOrNull()?.let { locator ->
        if (pdfPublicationOpen) PdfLocatorFormat.normalize(locator, positions) else locator
    }

    /**
     * Readium 3.4 pages PDFs horizontally by default; Jerreader keeps the
     * continuous vertical scroll its PDF tap translation and annotations map onto.
     */
    private fun pdfiumEngineProvider() = PdfiumEngineProvider(
        defaults = PdfiumDefaults(scroll = true)
    )

    private fun currentBookLanguage(): LanguageCode? {
        val tag = book?.language?.lowercase().orEmpty()
        return when {
            tag.startsWith("ja") -> LanguageCode.JAPANESE
            tag.startsWith("en") -> LanguageCode.ENGLISH
            tag.startsWith("zh") -> LanguageCode.CHINESE_SIMPLIFIED
            else -> null
        }
    }

    private fun translationSourceLanguage(state: TranslationCardState): LanguageCode? =
        (state as? TranslationCardState.Success)?.result?.sourceLanguage ?: currentBookLanguage()

    companion object {
        const val EXTRA_INITIAL_LOCATOR = "com.jerreader.reader.INITIAL_LOCATOR"
        const val EXTRA_BOOK_ID = "com.jerreader.reader.BOOK_ID"
        const val EXTRA_PUBLICATION_PATH = "com.jerreader.reader.PUBLICATION_PATH"
        const val EXTRA_EXPECTED_SHA256 = "com.jerreader.reader.EXPECTED_SHA256"
        const val EXTRA_EXPECTED_LAST_MODIFIED = "com.jerreader.reader.EXPECTED_LAST_MODIFIED"
        const val EXTRA_SOURCE_FORMAT = "com.jerreader.reader.SOURCE_FORMAT"

        // Enough clearance that the card never sits on the descenders of
        // the line it was anchored to.
        // EPUB draws the highlight inside the WebView, where CSS client rects
        // and the page share one coordinate space. PDF still uses the Compose
        // layer for OCR selections and persisted page rectangles.
        private const val DRAW_OWN_SELECTION_HIGHLIGHT = false
        private val GAP = 18.dp
        /** iOS `JerreaderMotion.chrome`, the bars' 0.20 s show and hide. */
        private const val CHROME_FADE_MILLIS = 200L
        /** iOS `ReaderChromeMetrics.barTravel`: how far a bar moves while it fades. */
        private const val CHROME_TRAVEL_DP = 12f
        /** iOS `ReaderQuickTranslationIndicatorTiming.standard`. */
        private const val QUICK_TRANSLATION_INDICATOR_AUTO_EXIT_MILLIS = 5_000L
        private const val ANNOTATION_DECORATION_GROUP = "jerreader-reading-annotations"
        private const val NAVIGATOR_TAG = "JerreaderNavigator"
        private const val INTEGRITY_TAG = "JerreaderIntegrity"
        private const val READER_OPEN_TIMEOUT_MILLIS = 25_000L
        private const val PDF_GEOMETRY_REFRESH_DELAY_MILLIS = 80L
        private const val SEARCH_RESULT_LIMIT = 80
        private const val SEARCH_PAGE_LIMIT = 6
        private const val COMPANION_PREFERENCES = "jerreader_reader_companion"
        private const val CONTINUATION_RECAP_MIN_PROGRESS = 0.01
    }
}
