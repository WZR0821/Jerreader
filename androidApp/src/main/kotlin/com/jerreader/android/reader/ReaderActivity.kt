package com.jerreader.android.reader

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.commitNow
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
import com.jerreader.unified.domain.BookFormat
import com.jerreader.unified.library.LibraryBook
import com.jerreader.unified.library.ReaderAnnotationColor
import com.jerreader.unified.library.ReaderAppearance
import com.jerreader.unified.reader.geometry.ReaderPoint
import com.jerreader.unified.reader.geometry.ReaderRect
import com.jerreader.unified.reader.geometry.ReaderSize
import com.jerreader.unified.reader.overlay.ReaderOverlayPlacement
import com.jerreader.unified.reader.overlay.ReaderOverlayRequest
import com.jerreader.unified.reader.overlay.ReaderTranslationLayoutPolicy
import com.jerreader.unified.reader.selection.ReaderSelectionVisualStyle
import com.jerreader.unified.reader.selection.ReaderWritingMode
import com.jerreader.unified.reader.ui.ReaderSelectionHighlightLayer
import com.jerreader.unified.library.ReaderTextOrientation
import com.jerreader.unified.domain.LanguageCode
import com.jerreader.unified.lexical.WordAnalysis
import com.jerreader.unified.translation.ContextExplanationRequest
import com.jerreader.unified.translation.ContextExplanationService
import com.jerreader.unified.translation.QuickTranslationUnit
import com.jerreader.unified.translation.ReaderCrossPageTranslationResolver
import com.jerreader.unified.translation.TranslationDisplayMode
import com.jerreader.unified.translation.TranslationRequest
import com.jerreader.unified.translation.TranslationResult
import com.jerreader.unified.ui.JerreaderTheme
import com.jerreader.unified.ui.ReaderAnnotationEditor
import com.jerreader.unified.ui.ReaderAnnotationItem
import com.jerreader.unified.ui.ReaderAppearancePanel
import com.jerreader.unified.ui.ReaderBookmarkItem
import com.jerreader.unified.ui.ReaderChapter
import com.jerreader.unified.ui.ReaderNavigationPanel
import com.jerreader.unified.ui.ReaderSearchResultItem
import com.jerreader.unified.ui.ReaderBottomChrome
import com.jerreader.unified.ui.ReaderTopChrome
import com.jerreader.unified.ui.TranslationCard
import com.jerreader.unified.ui.TranslationCardState
import com.jerreader.unified.ui.WordLookupCard
import com.jerreader.unified.ui.WordLookupCardState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
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
    private var wordInteractionController: ReaderWordInteractionController? = null
    private var tapTranslationController: ReaderTapTranslationController? = null
    private var pdfTranslationController: PdfTapTranslationController? = null
    private var annotationDialog: Dialog? = null
    private var readingStartedAtMillis: Long? = null
    private var accumulatedReadingSeconds = 0.0
    private val searchLocators = linkedMapOf<String, Locator>()
    private var pendingAnnotation: PendingAnnotation? = null

    internal var currentWordLookupState by mutableStateOf<WordLookupCardState?>(null)
        private set

    internal var currentTranslationState by mutableStateOf<TranslationCardState?>(null)
        private set

    private var currentTranslationLocatorJson: String? = null

    internal var currentAppearance by mutableStateOf(ReaderAppearance())
        private set

    /**
     * Whether the top and bottom reader bars are showing.
     *
     * iOS has had this since the first reader (`EPUBReaderViewModel.controlsVisible`);
     * Android pinned both bars permanently on screen because nothing ever
     * reported a tap that hit no text, so there was no event to toggle on.
     */
    private var chromeVisible by mutableStateOf(true)

    private var bookmarks by mutableStateOf(emptyList<ReadingBookmarkEntity>())
    private var annotations by mutableStateOf(emptyList<ReadingAnnotationEntity>())
    private var currentProgress by mutableStateOf(0.0)
    private var isCurrentPositionBookmarked by mutableStateOf(false)
    private var currentChapterIndex by mutableStateOf(-1)
    private var currentChapterName by mutableStateOf("")
    private var searchQuery by mutableStateOf("")
    private var searchResults by mutableStateOf(emptyList<ReaderSearchResultItem>())
    private var isSearching by mutableStateOf(false)
    private var searchMessage by mutableStateOf<String?>(null)
    private var crossPageMessage by mutableStateOf<String?>(null)
    private var isExpandingCrossPage by mutableStateOf(false)
    private var anchorTopPx by mutableStateOf(0)
    private var anchorBottomPx by mutableStateOf(0)
    private var cardDragX by mutableStateOf(0f)
    private var cardDragY by mutableStateOf(0f)
    private var anchorLeftPx by mutableStateOf(0)
    private var anchorRightPx by mutableStateOf(0)
    // Quick EPUB selections include the paired furigana rects. Keep that
    // merged geometry as the popup anchor instead of replacing it with
    // Readium's base-only selection bounds on the next coroutine turn.
    private var hasSelectionAnchor by mutableStateOf(false)
    // A post-measure collision correction. The first placement is chosen from
    // the available room; this value handles real card height, padding and
    // font-scale differences that are not known during the initial measure.
    private var popupAvoidanceX by mutableStateOf(0f)
    private var popupAvoidanceY by mutableStateOf(0f)
    private var selectionRects by mutableStateOf(emptyList<RectF>())
    private var pdfAnnotationRects by mutableStateOf(emptyList<RectF>())
    private var floatingLayer: ComposeView? = null
    private var highlightLayer: ComposeView? = null
    private var locatorObservationJob: Job? = null
    private var progressPersistenceJob: Job? = null
    private var writingModeJob: Job? = null
    private val progressPersistenceMutex = Mutex()
    private var readerContentLeftPx = 0
    private var readerContentTopPx = 0
    private var readerContentBottomPx = 0
    private var publicationWritingModeIsVertical by mutableStateOf(false)
    private var pdfGeometryView: View? = null
    private var pdfGeometryScrollListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var pdfGeometryLayoutListener: View.OnLayoutChangeListener? = null
    private var pdfGeometryTouchListener: View.OnTouchListener? = null

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
            PdfNavigatorFragment.createDummyFactory(PdfiumEngineProvider())
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
        if (book != null && publication != null && readingStartedAtMillis == null) {
            readingStartedAtMillis = System.currentTimeMillis()
        }
    }

    override fun onPause() {
        accumulateReadingTime()
        flushLatestLocator()
        flushReadingTime()
        super.onPause()
    }

    @SuppressLint("LogNotTimber")
    override fun onDestroy() {
        accumulateReadingTime()
        flushLatestLocator()
        flushReadingTime()
        progressPersistenceJob?.cancel()
        progressPersistenceJob = null
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

        if (::expectedSnapshot.isInitialized && !PublicationIntegrity.isUnchanged(expectedSnapshot)) {
            Log.e(INTEGRITY_TAG, "Readium session changed immutable publication bytes or mtime")
        }
        super.onDestroy()
    }

    private fun looksLikePdf(): Boolean {
        val declaredFormat = intent.getStringExtra(EXTRA_SOURCE_FORMAT)
        if (declaredFormat != null) return declaredFormat.equals(BookFormat.PDF.fileExtension, true)
        return intent.getStringExtra(EXTRA_PUBLICATION_PATH)?.endsWith(".pdf", true) == true
    }

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
        if (!PublicationIntegrity.isUnchanged(snapshot)) {
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
        val root = FrameLayout(this).apply {
            setBackgroundColor(currentAppearance.pageBackgroundArgb())
        }
        // iOS lets the page run edge to edge and floats the controls above it.
        val readerContainer = FrameLayout(this).apply { id = R.id.reader_container }
        root.addView(
            readerContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            ProgressBar(this).apply { id = R.id.reader_loading },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        val topChrome = readerComposeView {
            // `AnimatedVisibility` rather than an alpha fade: a fully
            // transparent bar still swallows every tap in its band, and this
            // one sits over the first line of text.
            ChromeVisibility(enter = slideInVertically { -it }) {
                ReaderTopChrome(
                    chapterTitle = currentChapterName,
                    bookTitle = book?.title ?: "Jerreader",
                    isBookmarked = isCurrentPositionBookmarked,
                    onClose = onBackPressedDispatcher::onBackPressed,
                    onTableOfContents = ::showNavigationPanel,
                    onToggleBookmark = ::toggleBookmark,
                    onAppearance = ::showAppearance
                )
            }
        }
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
            ChromeVisibility(enter = slideInVertically { it }) {
            ReaderBottomChrome(
                chapterLabel = currentChapterName,
                progress = currentProgress,
                showProgress = appPreferences.showReadingProgress,
                onProgressChange = ::goToProgress,
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
                }
            )
            }
        }
        root.addView(
            bottomChrome,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

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

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topChrome.updatePadding(top = bars.top)
            bottomChrome.updatePadding(bottom = bars.bottom)
            // The page runs from the status bar to the navigation bar, and the
            // chrome floats above it — the same shape as iOS, where the reader
            // content is `.ignoresSafeArea()` and only the bars are inset.
            //
            // It used to reserve a further 74dp above and 156dp below so a
            // floating card could never land on text. On a phone that is most
            // of a page's worth of reading area given up permanently for two
            // bars that are usually hidden, and because the window behind it
            // was plain white the reserved strips read as the reader occupying
            // a box in the middle of the screen rather than the screen.
            readerContainer.updatePadding(top = bars.top, bottom = bars.bottom)
            // Only a seed for the first frame; `updateReaderContentOffset()`
            // measures the page for real once there is one, and Readium pads it
            // further than this.
            readerContentTopPx = 0
            highlightView.updatePadding(top = bars.top, bottom = bars.bottom)
            overlayView.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        rootView = root
        setContentView(root)
    }

    private fun readerComposeView(
        content: @androidx.compose.runtime.Composable () -> Unit
    ): ComposeView = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { ReaderTheme(content) }
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
        if (book != null && readingStartedAtMillis == null) {
            readingStartedAtMillis = System.currentTimeMillis()
        }
        readingOrder = openedPublication.readingOrder
        positions = runCatching { openedPublication.positions() }.getOrDefault(emptyList())

        val stored = decodeStoredReaderPreferences(
            json = libraryBook?.preferencesJson,
            fallback = graph.appSettings.preferences.value.defaultReaderAppearance
        )
        basePreferences = stored.epub
        currentAppearance = stored.appearance
        applyReaderSurfaceColor()

        when {
            openedPublication.conformsTo(Publication.Profile.EPUB) -> {
                buildTableOfContents(openedPublication.tableOfContents)
                val initialLocator = libraryBook?.locatorJson?.let(::parseLocator)
                val wordController = ReaderWordInteractionController(
                    scope = lifecycleScope,
                    lookupService = graph.lexicalLookupService,
                    languageHint = { currentBookLanguage() },
                    onStateChanged = ::showWordLookup,
                    onSelectionInvalidated = ::clearTransientCardPresentation,
                    shortTapEnabled = {
                        !graph.translationSettings.preferences.value.quickTranslationEnabled
                    },
                    onContentTap = ::toggleReaderChrome
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
                    onWritingModeObserved = ::observePublicationWritingMode,
                    appearance = { currentAppearance },
                    onContentTap = ::toggleReaderChrome
                )
                tapTranslationController = tapController
                val factory = EpubNavigatorFactory(openedPublication).createFragmentFactory(
                    initialLocator = initialLocator,
                    initialPreferences = basePreferences,
                    configuration = EpubNavigatorFragment.Configuration(
                        selectionActionModeCallback = tapController.selectionActionModeCallback
                    )
                )
                attachEpubNavigator(factory)
            }

            openedPublication.conformsTo(Publication.Profile.PDF) -> {
                buildTableOfContents(openedPublication.tableOfContents)
                val initialLocator = libraryBook?.locatorJson?.let(::parseLocator)
                val factory = PdfNavigatorFactory(
                    openedPublication,
                    PdfiumEngineProvider()
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
            onContentTap = ::toggleReaderChrome
        )
        pdfTranslationController = controller
        controller.attach(fragment)
        installPdfGeometryListeners(fragment.publicationView)
        latestLocator?.let(::refreshPdfAnnotationRects)
        observeLocator { fragment.currentLocator.collectLatest(it) }
    }

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
            collect { locator ->
                resetTransientReaderState()
                latestLocator = locator
                latestLocatorJson = locator.toJSON().toString()
                currentProgress = locator.locations.totalProgression ?: currentProgress
                currentChapterIndex = readingOrder.indexOfFirst {
                    it.url().toString() == locator.href.toString()
                }
                currentChapterName = currentChapterTitle(locator)
                applyWritingModeOverride()
                refreshBookmarkState()
                refreshPdfAnnotationRects(locator)
                delay(PROGRESS_SAVE_DEBOUNCE_MILLIS)
                persistLatestLocator()
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

    private fun goToPreviousPage() {
        navigator?.goBackward(animated = false) ?: pdfNavigator?.goBackward(animated = false)
    }

    private fun goToNextPage() {
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

    private fun bookmarkKey(locator: Locator): String = AndroidReaderRecordKeys.bookmark(
        bookId = book?.id.orEmpty(),
        href = locator.href.toString(),
        progression = locator.locations.progression ?: 0.0
    )

    private fun refreshBookmarkState() {
        val locator = latestLocator
        isCurrentPositionBookmarked = locator != null &&
            bookmarks.any { it.bookmarkKey == bookmarkKey(locator) }
    }

    private fun toggleBookmark() {
        val locator = latestLocator ?: return
        val libraryBook = book ?: return
        lifecycleScope.launch {
            graph.readerRecords.toggleBookmark(
                key = bookmarkKey(locator),
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
        // iOS brings the controls back after any settings change, so a reader
        // who dismissed the bars and then reopened them via a panel is not left
        // looking at a page with no visible way out.
        showReaderChrome()
        val previousOrientation = currentAppearance.orientation
        currentAppearance = appearance
        applyReaderSurfaceColor()
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

    /**
     * Paints the window behind the page in the page's own colour.
     *
     * The Readium fragment only covers the area between the system bars, and a
     * PDF page is letterboxed inside even that. Without this the exposed window
     * is whatever the Activity theme says — white — so a sepia or dark book was
     * a coloured panel floating on a white sheet.
     */
    private fun applyReaderSurfaceColor() {
        rootView?.setBackgroundColor(currentAppearance.pageBackgroundArgb())
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
        val factory = EpubNavigatorFactory(openedPublication).createFragmentFactory(
            initialLocator = resumeLocator,
            initialPreferences = preferences,
            configuration = EpubNavigatorFragment.Configuration(
                selectionActionModeCallback = tapController.selectionActionModeCallback
            )
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
            // One injection was not enough. Readium loads the resource into the
            // web view asynchronously, and an orientation change rebuilds the
            // navigator outright, so the single attempt regularly landed on the
            // outgoing document or on no document at all. Nothing then re-ran
            // it until the next locator change — which is exactly why 横排 only
            // took effect after turning a page. Keep asking until the document
            // reports back the mode that was asked for.
            repeat(WRITING_MODE_ATTEMPTS) { attempt ->
                if (attempt > 0) delay(WRITING_MODE_RETRY_DELAY_MILLIS)
                val resolved = controller.applyWritingModeOverride(mode) ?: return@repeat
                val vertical = resolved.startsWith("vertical") || resolved.startsWith("sideways")
                publicationWritingModeIsVertical = if (mode == null) vertical else mode == "vertical"
                // 原书 asks the page rather than telling it, so the first
                // answer is the answer.
                if (mode == null || vertical == (mode == "vertical")) return@launch
            }
        }
    }

    /**
     * A block the reader has just selected reported its own writing mode. Only
     * a publication that is being left to decide its own orientation listens to
     * it — an explicit reader setting stays authoritative.
     */
    private fun observePublicationWritingMode(vertical: Boolean) {
        if (currentAppearance.orientation != ReaderTextOrientation.PUBLICATION) return
        if (publicationWritingModeIsVertical == vertical) return
        publicationWritingModeIsVertical = vertical
    }

    private fun showNavigationPanel() {
        showReaderChrome()
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

    private fun showAppearance() {
        showReaderChrome()
        showComposeDialog {
            // Colour sets are global, so the reader reads them from app
            // settings rather than from this book's own appearance.
            val appPreferences by graph.appSettings.preferences.collectAsState()
            ReaderAppearancePanel(
                appearance = currentAppearance,
                onChange = ::applyAppearance,
                isPdf = pdfNavigator != null,
                colorPresets = appPreferences.colorPresets,
                onSaveColorPreset = { name ->
                    graph.appSettings.saveColorPreset(name, currentAppearance)
                },
                onDeleteColorPreset = graph.appSettings::removeColorPreset
            )
        }
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
        val translation = currentTranslationState
        val lookup = currentWordLookupState
        val translationPreferences by graph.translationSettings.preferences.collectAsState()
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val viewport = with(density) {
                ReaderSize(
                    width = constraints.maxWidth.toDp().value.toDouble(),
                    height = constraints.maxHeight.toDp().value.toDouble()
                )
            }
            // Keep clear of the bars, but only while they are on screen. These
            // were fixed at 74 and 156 regardless, which is what made a card
            // near the foot of a page collapse to the 108dp minimum and clip
            // its own text: the solver was told 156dp of empty page was
            // occupied. This layer is already padded by the system bars, so
            // these are measured from inside them, as on iOS.
            val topInset = if (chromeVisible) CHROME_TOP_INSET_DP else IDLE_INSET_DP
            val bottomInset = if (chromeVisible) CHROME_BOTTOM_INSET_DP else IDLE_INSET_DP
            val anchorLeft = with(density) { minOf(anchorLeftPx, anchorRightPx).toDp().value.toDouble() }
            val anchorTop = with(density) { minOf(anchorTopPx, anchorBottomPx).toDp().value.toDouble() }
            val anchorRight = with(density) { maxOf(anchorLeftPx, anchorRightPx).toDp().value.toDouble() }
            val anchorBottom = with(density) { maxOf(anchorTopPx, anchorBottomPx).toDp().value.toDouble() }
            val hasAnchor = hasSelectionAnchor ||
                anchorRight > anchorLeft || anchorBottom > anchorTop
            // A quick tap anchors on a point rather than a run of text, and the
            // solver discards a degenerate rectangle — which would drop the card
            // to the top banner instead of next to the word that was tapped.
            val selectionAnchor = if (!hasAnchor) null else ReaderRect.fromEdges(
                left = anchorLeft,
                top = anchorTop,
                right = maxOf(anchorRight, anchorLeft + 1.0),
                bottom = maxOf(anchorBottom, anchorTop + 1.0)
            )

            val isParagraph = translationPreferences.quickTranslationUnit ==
                QuickTranslationUnit.PARAGRAPH
            val success = translation as? TranslationCardState.Success
            val usesSideAvoidance = ReaderTranslationLayoutPolicy.usesVerticalSideAvoidance(
                isReflowable = pdfNavigator == null,
                preservesPublicationOrientation =
                    currentAppearance.orientation == ReaderTextOrientation.PUBLICATION,
                publicationIsVertical = publicationWritingModeIsVertical,
                selectionFrame = selectionAnchor
            )
            val prefersExpanded = ReaderTranslationLayoutPolicy.prefersExpandedMaximumHeight(
                sourceCharacterCount = translation?.sourceText?.length
                    ?: lookup?.analysis?.surfaceForm?.length ?: 0,
                translatedCharacterCount = success?.result?.translatedText?.length ?: 0,
                isParagraph = isParagraph
            )
            val cardWidth = ReaderTranslationLayoutPolicy.preferredCardWidth(
                viewportWidth = viewport.width,
                translatedCharacterCount = success?.result?.translatedText?.length ?: 0,
                isLoading = translation is TranslationCardState.Loading ||
                    lookup is WordLookupCardState.Loading,
                isFailure = translation is TranslationCardState.Failure ||
                    lookup is WordLookupCardState.Failure,
                usesVerticalSideAvoidance = usesSideAvoidance
            )
            val maximumHeight = ReaderTranslationLayoutPolicy.preferredMaximumCardHeight(
                viewportSize = viewport,
                prefersExpanded = prefersExpanded
            )
            val gap = ReaderTranslationLayoutPolicy.selectionGap(
                isParagraph = isParagraph,
                usesVerticalSideAvoidance = usesSideAvoidance
            )
            val horizontalInset = ReaderTranslationLayoutPolicy.horizontalInset(usesSideAvoidance)

            // The solver picks its region from the free space *around* the
            // selection rather than from the card's own height, so the measured
            // height only resizes the card in place — it can no longer flip the
            // card to the other side when a result replaces the spinner.
            var measuredHeight by androidx.compose.runtime.remember(selectionAnchor) {
                mutableStateOf(ReaderTranslationLayoutPolicy.MINIMUM_CARD_HEIGHT)
            }

            val layout = ReaderOverlayPlacement.solve(
                ReaderOverlayRequest(
                    selectionFrame = selectionAnchor,
                    viewportSize = viewport,
                    cardSize = ReaderSize(
                        width = cardWidth,
                        height = measuredHeight
                            .coerceAtLeast(ReaderTranslationLayoutPolicy.MINIMUM_CARD_HEIGHT)
                    ),
                    topInset = topInset,
                    bottomInset = bottomInset,
                    horizontalInset = horizontalInset,
                    gap = gap,
                    minimumCardHeight = ReaderTranslationLayoutPolicy.MINIMUM_CARD_HEIGHT,
                    preferredMaximumCardHeight = maximumHeight,
                    prefersTop = translationPreferences.displayMode ==
                        TranslationDisplayMode.TOP_BANNER && !usesSideAvoidance,
                    // A long horizontal result squeezed between the sentence and
                    // the page edge used to be placed anyway and covered the
                    // lines underneath. Given the choice it goes beside the
                    // sentence instead, which is what iOS already did.
                    prefersHorizontalAvoidance = usesSideAvoidance || prefersExpanded,
                    minimumHorizontalCardWidth =
                        ReaderTranslationLayoutPolicy.minimumHorizontalCardWidth(usesSideAvoidance)
                )
            )

            val safeBounds = ReaderRect.fromEdges(
                left = horizontalInset,
                top = topInset,
                right = viewport.width - horizontalInset,
                bottom = viewport.height - bottomInset
            )

            // The solve above only knows the requested card height. Compose can
            // measure a different one after text wrapping, font scaling, or a
            // loading/result swap, so the *measured* frame is corrected here.
            // The correction is recomputed from the solved position each pass
            // rather than accumulated, so a card cannot walk across the page.
            val popupCollisionModifier = Modifier.onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInParent()
                val height = with(density) { bounds.height.toDp().value.toDouble() }
                if (abs(height - measuredHeight) > 0.5) measuredHeight = height

                // Where the reader has put the card is not a collision to solve.
                // The solver runs on every layout pass and its own correction
                // moves the card, which triggers the next pass — so while a drag
                // was in progress it kept shoving the card back off the
                // selection, one measured frame behind the finger. That is the
                // shake, and when the two happened to pull the same distance in
                // opposite directions it was a card that would not move at all.
                // The avoidance already applied stays where it is, so taking
                // hold of the card does not make it jump.
                if (cardDragX != 0f || cardDragY != 0f) return@onGloballyPositioned
                if (selectionAnchor == null) return@onGloballyPositioned

                val measuredFrame = with(density) {
                    ReaderRect.fromEdges(
                        left = bounds.left.toDp().value.toDouble(),
                        top = bounds.top.toDp().value.toDouble(),
                        right = bounds.right.toDp().value.toDouble(),
                        bottom = bounds.bottom.toDp().value.toDouble()
                    )
                }
                // Undo the correction already applied so every pass scores the
                // *solved* frame and converges on one answer.
                val applied = with(density) {
                    ReaderPoint(
                        x = popupAvoidanceX.toDp().value.toDouble(),
                        y = popupAvoidanceY.toDp().value.toDouble()
                    )
                }
                val shift = ReaderOverlayPlacement.correct(
                    measuredFrame = measuredFrame.offset(-applied.x, -applied.y),
                    selectionFrame = selectionAnchor,
                    safeBounds = safeBounds,
                    gap = gap
                )
                val nextX = with(density) { shift.dx.dp.toPx() }
                val nextY = with(density) { shift.dy.dp.toPx() }
                if (abs(nextX - popupAvoidanceX) > 0.5f ||
                    abs(nextY - popupAvoidanceY) > 0.5f
                ) {
                    popupAvoidanceX = nextX
                    popupAvoidanceY = nextY
                }
            }
            val cardModifier = Modifier
                .widthIn(max = layout.cardWidth.dp)
                .heightIn(max = layout.maximumCardHeight.dp)
                .offset {
                    // Compose offsets from the top-left; the solver answers in
                    // centres, so half the card comes back off here.
                    with(density) {
                        IntOffset(
                            x = ((layout.position.x - layout.cardWidth / 2.0).dp.toPx() +
                                popupAvoidanceX + cardDragX).roundToInt(),
                            y = ((layout.position.y - measuredHeight / 2.0).dp.toPx() +
                                popupAvoidanceY + cardDragY).roundToInt()
                        )
                    }
                }
                .then(popupCollisionModifier)

            if (translation != null) {
                FloatingSurface(cardModifier) {
                    TranslationCard(
                        state = translation,
                        onDismiss = ::dismissTranslation,
                        onRetry = ::retryTranslation,
                        onCopy = ::copyToClipboard,
                        onFavorite = { result -> favoriteTranslation(translation, result) },
                        onExplain = { explainTranslation(translation) },
                        onAnnotate = ::annotateTranslatedText,
                        onExpandCrossPage = { expandCrossPageSegment(translation) },
                        onAddToVocabulary = { addTranslatedWordToVocabulary(translation) },
                        crossPageMessage = crossPageMessage,
                        isExpandingCrossPage = isExpandingCrossPage,
                        onDrag = { dx, dy ->
                            val maxX = constraints.maxWidth * 0.45f
                            val maxY = constraints.maxHeight * 0.35f
                            cardDragX = (cardDragX + dx).coerceIn(-maxX, maxX)
                            cardDragY = (cardDragY + dy).coerceIn(-maxY, maxY)
                        },
                        onOpenSettings = ::openTranslationSettings
                    )
                }
            } else if (lookup != null) {
                FloatingSurface(cardModifier) {
                    WordLookupCard(
                        state = lookup,
                        onDismiss = ::dismissWordLookup,
                        onRetry = { wordInteractionController?.retry() },
                        onCopy = ::copyToClipboard,
                        onFavorite = { explanation ->
                            lifecycleScope.launch {
                                graph.learningRecords.recordLookup(
                                    explanation = explanation,
                                    sourceBookId = book?.id,
                                    sourceBookTitle = book?.title,
                                    favorite = true
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun FloatingSurface(
        modifier: Modifier,
        content: @androidx.compose.runtime.Composable () -> Unit
    ) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 10.dp,
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
                // Readium has already folded the page fragment's own padding
                // into this rect, so it is measured from `publicationView` and
                // not from the WebView that `readerContentTopPx` tracks.
                // Adding that padding a second time drops the anchor a text
                // line too low.
                val offset = overlayOffsetOf(navigator?.publicationView)
                anchorTopPx = (offset.y + rect.top).toInt()
                anchorBottomPx = (offset.y + rect.bottom).toInt()
                anchorLeftPx = (offset.x + rect.left).toInt()
                anchorRightPx = (offset.x + rect.right).toInt()
                return@launch
            }
            val epubPoint = tapTranslationController?.focusPoint()
            val pdfPoint = pdfTranslationController?.focusPoint()
            if (epubPoint != null) {
                // CSS pixels inside the WebView, so they are measured from the
                // page rather than from the pager that carries it.
                val page = navigator?.publicationView?.let { visiblePageView(it) }
                val offset = overlayOffsetOf(page)
                val x = epubPoint.x * density + offset.x
                val y = epubPoint.y * density + offset.y
                anchorLeftPx = x.toInt()
                anchorRightPx = x.toInt()
                anchorTopPx = (y - 12 * density).toInt()
                anchorBottomPx = (y + 12 * density).toInt()
            } else if (pdfPoint != null) {
                val offset = overlayOffsetOf(pdfNavigator?.publicationView)
                val x = pdfPoint.x + offset.x
                val y = pdfPoint.y + offset.y
                anchorLeftPx = x.toInt()
                anchorRightPx = x.toInt()
                anchorTopPx = (y - 12 * density).toInt()
                anchorBottomPx = (y + 12 * density).toInt()
            }
        }
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

    private fun showWordLookup(state: WordLookupCardState) {
        popupAvoidanceX = 0f
        popupAvoidanceY = 0f
        currentWordLookupState = state
        setFloatingLayerVisible(true)
        updateFloatingAnchor()
    }

    private fun showTranslation(state: TranslationCardState) {
        // The card and the bars compete for the same edges of the screen.
        // iOS clears the bars when a translation starts; mirroring that keeps
        // the card's placement solver working with the room it expects.
        chromeVisible = false
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
            anchorLeftPx = rects.minOf { it.left }.toInt()
            anchorRightPx = rects.maxOf { it.right }.toInt()
        }
    }

    private fun updateReaderContentOffset() {
        val publicationView = navigator?.publicationView ?: pdfNavigator?.publicationView
        val offset = overlayOffsetOf(publicationView?.let { visiblePageView(it) })
        readerContentLeftPx = offset.x
        readerContentTopPx = offset.y
    }

    /**
     * Where [view] sits inside the overlay's *content* box. The overlay carries
     * the system-bar padding and Compose lays out inside it, so an offset that
     * kept the padding placed every anchor one status bar too low.
     */
    private fun overlayOffsetOf(view: View?): android.graphics.Point {
        val location = IntArray(2)
        val overlayLocation = IntArray(2)
        view?.getLocationInWindow(location)
        val overlay = highlightLayer ?: floatingLayer
        overlay?.getLocationInWindow(overlayLocation)
        val overlayPadTop = overlay?.paddingTop ?: 0
        return android.graphics.Point(
            location[0] - overlayLocation[0],
            (location[1] - overlayLocation[1] - overlayPadTop).coerceAtLeast(0)
        )
    }

    /**
     * EPUB selection rects come from `getClientRects()`, so they are measured
     * from the WebView's own top-left. `publicationView` is only the pager that
     * carries the WebView, and Readium pads the page inside it — about 39 dp
     * here, one whole text line. Anchoring on the pager put the card that much
     * too high, so a card placed below a sentence landed on its last line.
     *
     * PDF has no WebView and its controller already reports rects relative to
     * `publicationView`, so that path falls through to the pager unchanged.
     */
    private fun visiblePageView(root: View): View {
        var best: View? = null
        var bestArea = 0
        val bounds = Rect()
        fun walk(view: View) {
            if (view is android.webkit.WebView) {
                if (view.isShown && view.getGlobalVisibleRect(bounds)) {
                    val area = bounds.width() * bounds.height()
                    if (area > bestArea) {
                        bestArea = area
                        best = view
                    }
                }
                return
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(root)
        return best ?: root
    }

    /**
     * Shows or hides a reader bar, matching iOS's fade-and-settle.
     *
     * The content is removed from composition while hidden, not just made
     * transparent, so a dismissed bar stops taking taps over the text it
     * covers — the whole point of being able to dismiss it.
     */
    @androidx.compose.runtime.Composable
    private fun ChromeVisibility(
        enter: EnterTransition,
        content: @androidx.compose.runtime.Composable () -> Unit
    ) {
        AnimatedVisibility(
            visible = chromeVisible,
            enter = enter + fadeIn(),
            exit = fadeOut()
        ) {
            content()
        }
    }

    /** A tap that hit no text: show the bars if hidden, hide them if shown. */
    private fun toggleReaderChrome() {
        chromeVisible = !chromeVisible
    }

    /**
     * Brings the bars back. Called wherever iOS sets `controlsVisible = true`:
     * after a settings change, and whenever a panel is opened, so the reader
     * can never end up in a state with no way back to the controls.
     */
    private fun showReaderChrome() {
        chromeVisible = true
    }

    /**
     * Draws the PDF selection through the same Compose layer iOS uses.
     *
     * This used to be a hand-rolled `Canvas` with a flat 4px corner and a 1px
     * outline, while the shared layer paints iOS's tuned 3px-max proportional
     * corner and 0.75px stroke with a half-point outset. Same selection, two
     * different shapes — the exact class of drift `:ui` exists to end.
     */
    @androidx.compose.runtime.Composable
    private fun SelectionHighlightLayer() {
        val rects = selectionRects
        val persistedRects = pdfAnnotationRects
        if (rects.isEmpty() && persistedRects.isEmpty()) return
        val left = readerContentLeftPx.toDouble()
        val top = readerContentTopPx.toDouble()
        fun RectF.toOverlayRect(): ReaderRect = ReaderRect(
            x = this.left + left,
            y = this.top + top,
            width = width().toDouble(),
            height = height().toDouble()
        )
        ReaderSelectionHighlightLayer(
            tiles = rects.map { it.toOverlayRect() },
            palette = ReaderSelectionVisualStyle.palette(currentAppearance),
            modifier = Modifier.fillMaxSize(),
            writingMode = ReaderWritingMode.of(
                currentAppearance.orientation == ReaderTextOrientation.VERTICAL
            ),
            persistedTiles = persistedRects.map { it.toOverlayRect() },
            persistedColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
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

    private fun explainTranslation(state: TranslationCardState) {
        val service = graph.translationService as? ContextExplanationService ?: return
        lifecycleScope.launch {
            crossPageMessage = "正在请求 AI 解析…"
            val explained = runCatching {
                withContext(Dispatchers.IO) { service.explain(
                    ContextExplanationRequest(
                        focusedText = state.sourceText,
                        contextText = null,
                        sourceLanguage = translationSourceLanguage(state)
                    )
                ) }
            }.getOrNull()
            if (explained == null) {
                crossPageMessage = "AI 解析不可用，请先在设置里配置 AI 服务。"
                return@launch
            }
            crossPageMessage = null
            val current = currentTranslationState
            if (current is TranslationCardState.Success && current.sourceText == state.sourceText) {
                currentTranslationState = current.copy(analysisText = explained.explanation)
            }
        }
    }

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

    internal suspend fun lookupAtForTesting(point: PointF): WordLookupCardState? =
        wordInteractionController?.lookupAt(point)

    /** Feeds the pixel space produced by Readium's JavaScript through the real lookup path. */
    internal suspend fun lookupAtDevicePointForTesting(point: PointF): WordLookupCardState? =
        wordInteractionController?.lookupAt(point, pointIsReadiumPixels = true)

    internal suspend fun translateAtForTesting(point: PointF): TranslationCardState? =
        tapTranslationController?.translateAt(point)
            ?: pdfTranslationController?.translateAt(point)

    /** Feeds Readium's JavaScript pixel point through the real tap conversion. */
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

    private fun dialogComposeView(
        content: @androidx.compose.runtime.Composable () -> Unit
    ): ComposeView = ComposeView(this).apply {
        setViewTreeLifecycleOwner(this@ReaderActivity)
        setViewTreeViewModelStoreOwner(this@ReaderActivity)
        setViewTreeSavedStateRegistryOwner(this@ReaderActivity)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent(content)
    }

    private fun persistLatestLocator() {
        val bookId = book?.id ?: return
        val locatorJson = latestLocatorJson ?: return
        val progress = currentProgress
        progressPersistenceJob?.cancel()
        progressPersistenceJob = graph.applicationScope.launch {
            progressPersistenceMutex.withLock {
                graph.repository.updateReadingProgress(
                    id = bookId,
                    locatorJson = locatorJson,
                    openedAtEpochMillis = System.currentTimeMillis(),
                    progress = progress
                )
            }
        }
    }

    /** Flushes the newest locator before Android can destroy the Activity/process. */
    @SuppressLint("LogNotTimber")
    private fun flushLatestLocator() {
        val bookId = book?.id ?: return
        val locatorJson = latestLocatorJson ?: return
        val progress = currentProgress
        progressPersistenceJob?.cancel()
        runCatching {
            runBlocking(Dispatchers.IO) {
                progressPersistenceMutex.withLock {
                    graph.repository.updateReadingProgress(
                        id = bookId,
                        locatorJson = locatorJson,
                        openedAtEpochMillis = System.currentTimeMillis(),
                        progress = progress
                    )
                }
            }
        }.onFailure { error ->
            Log.w(INTEGRITY_TAG, "Unable to flush reading progress", error)
        }
        progressPersistenceJob = null
    }

    private fun accumulateReadingTime() {
        val started = readingStartedAtMillis ?: return
        val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(0L) / 1000.0
        accumulatedReadingSeconds += elapsed
        readingStartedAtMillis = null
    }

    @SuppressLint("LogNotTimber")
    private fun flushReadingTime() {
        val bookId = book?.id ?: return
        val seconds = accumulatedReadingSeconds
        if (seconds < MINIMUM_RECORDED_READING_SECONDS) return
        runCatching {
            runBlocking(Dispatchers.IO) {
                graph.repository.addReadingTime(bookId, seconds)
            }
            // Clear the accumulator only after the database confirms the write;
            // a later lifecycle callback can retry a failed write safely.
            accumulatedReadingSeconds = 0.0
        }.onFailure { error ->
            Log.w(INTEGRITY_TAG, "Unable to flush reading time", error)
        }
    }

    private fun showError(message: String) {
        val root = rootView ?: return
        root.removeAllViews()
        root.addView(
            TextView(this).apply {
                text = message
                setTextColor(Color.DKGRAY)
                textSize = 16f
                setPadding(48, 48, 48, 48)
                gravity = Gravity.CENTER
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
    }.getOrNull()

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
        const val EXTRA_BOOK_ID = "com.jerreader.reader.BOOK_ID"
        const val EXTRA_PUBLICATION_PATH = "com.jerreader.reader.PUBLICATION_PATH"
        const val EXTRA_EXPECTED_SHA256 = "com.jerreader.reader.EXPECTED_SHA256"
        const val EXTRA_EXPECTED_LAST_MODIFIED = "com.jerreader.reader.EXPECTED_LAST_MODIFIED"
        const val EXTRA_SOURCE_FORMAT = "com.jerreader.reader.SOURCE_FORMAT"

        // EPUB draws the highlight inside the WebView, where CSS client rects
        // and the page share one coordinate space. PDF still uses the Compose
        // layer for OCR selections and persisted page rectangles.
        private const val DRAW_OWN_SELECTION_HIGHLIGHT = false
        /**
         * How much of the page a floating card stays off, in dp, measured from
         * inside the system bars. iOS uses the same three numbers
         * (`EPUBReaderView.topInset` / `bottomInset`).
         */
        private const val CHROME_TOP_INSET_DP = 70.0
        private const val CHROME_BOTTOM_INSET_DP = 94.0
        private const val IDLE_INSET_DP = 12.0
        private const val ANNOTATION_DECORATION_GROUP = "jerreader-reading-annotations"
        private const val NAVIGATOR_TAG = "JerreaderNavigator"
        private const val INTEGRITY_TAG = "JerreaderIntegrity"
        private const val PROGRESS_SAVE_DEBOUNCE_MILLIS = 500L
        private const val MINIMUM_RECORDED_READING_SECONDS = 3.0
        private const val READER_OPEN_TIMEOUT_MILLIS = 25_000L
        private const val PDF_GEOMETRY_REFRESH_DELAY_MILLIS = 80L

        /**
         * Enough attempts to outlast a navigator rebuild. 12 × 120ms is about
         * 1.4 seconds, comfortably longer than Readium takes to load a resource
         * on the slowest device this ships to, and the loop exits the moment the
         * page confirms the mode, so the usual cost is one call.
         */
        private const val WRITING_MODE_ATTEMPTS = 12
        private const val WRITING_MODE_RETRY_DELAY_MILLIS = 120L
        private const val SEARCH_RESULT_LIMIT = 80
        private const val SEARCH_PAGE_LIMIT = 6
    }
}
