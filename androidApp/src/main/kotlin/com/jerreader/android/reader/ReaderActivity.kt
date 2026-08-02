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
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
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
import com.jerreader.shared.domain.BookFormat
import com.jerreader.shared.library.LibraryBook
import com.jerreader.shared.library.ReaderAnnotationColor
import com.jerreader.shared.library.ReaderAppearance
import com.jerreader.shared.library.ReaderTextOrientation
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.WordAnalysis
import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.ContextExplanationService
import com.jerreader.shared.translation.ReaderCrossPageTranslationResolver
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationResult
import com.jerreader.shared.ui.JerreaderTheme
import com.jerreader.shared.ui.ReaderAnnotationEditor
import com.jerreader.shared.ui.ReaderAnnotationItem
import com.jerreader.shared.ui.ReaderAppearancePanel
import com.jerreader.shared.ui.ReaderBookmarkItem
import com.jerreader.shared.ui.ReaderChapter
import com.jerreader.shared.ui.ReaderNavigationPanel
import com.jerreader.shared.ui.ReaderSearchResultItem
import com.jerreader.shared.ui.ReaderBottomChrome
import com.jerreader.shared.ui.ReaderTopChrome
import com.jerreader.shared.ui.TranslationCard
import com.jerreader.shared.ui.TranslationCardState
import com.jerreader.shared.ui.WordLookupCard
import com.jerreader.shared.ui.WordLookupCardState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
    private val sessionStartedAtMillis = System.currentTimeMillis()
    private val searchLocators = linkedMapOf<String, Locator>()
    private var pendingAnnotation: PendingAnnotation? = null

    internal var currentWordLookupState by mutableStateOf<WordLookupCardState?>(null)
        private set

    internal var currentTranslationState by mutableStateOf<TranslationCardState?>(null)
        private set

    internal var currentAppearance by mutableStateOf(ReaderAppearance())
        private set

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
    private var selectionRects by mutableStateOf(emptyList<RectF>())
    private var floatingLayer: ComposeView? = null
    private var readerContentTopPx = 0
    private var readerContentBottomPx = 0

    private data class PendingAnnotation(
        val selectedText: String,
        val locator: Locator,
        val existingId: String? = null,
        val note: String = "",
        val color: String = "yellow"
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

    override fun onPause() {
        persistLatestLocator()
        super.onPause()
    }

    @SuppressLint("LogNotTimber")
    override fun onDestroy() {
        persistLatestLocator()
        persistReadingTime()
        navigator?.removeDecorationListener(annotationDecorationListener)
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
            }
        }
    }

    private fun showRoot() {
        val density = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
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
        root.addView(
            bottomChrome,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
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
        floatingLayer = overlayView

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topChrome.updatePadding(top = bars.top)
            bottomChrome.updatePadding(bottom = bars.bottom)
            // The page keeps clear of the floating cards so text is never hidden
            // behind them, while the background still reaches the screen edges.
            readerContainer.updatePadding(
                top = bars.top + (CONTENT_TOP_INSET_DP * density).toInt(),
                bottom = bars.bottom + (CONTENT_BOTTOM_INSET_DP * density).toInt()
            )
            // The floating layer already carries the status-bar padding, so the
            // page offset used for highlights and anchors must exclude it or
            // everything lands one inset too high.
            readerContentTopPx = (CONTENT_TOP_INSET_DP * density).toInt()
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
            showError(error.toString())
            return
        }
        val openedPublication = graph.readium.publicationOpener.open(
            asset,
            allowUserInteraction = false
        ).getOrElse { error ->
            showError(error.toString())
            return
        }

        if (openedPublication.isRestricted) {
            openedPublication.close()
            showError("当前出版物受保护，Jerreader 不会尝试绕过 DRM。")
            return
        }

        publication = openedPublication
        readingOrder = openedPublication.readingOrder
        positions = runCatching { openedPublication.positions() }.getOrDefault(emptyList())

        val stored = decodeStoredReaderPreferences(
            json = libraryBook?.preferencesJson,
            fallback = graph.appSettings.preferences.value.defaultReaderAppearance
        )
        basePreferences = stored.epub
        currentAppearance = stored.appearance

        when {
            openedPublication.conformsTo(Publication.Profile.EPUB) -> {
                buildTableOfContents(openedPublication.tableOfContents)
                val initialLocator = libraryBook?.locatorJson?.let(::parseLocator)
                val wordController = ReaderWordInteractionController(
                    scope = lifecycleScope,
                    lookupService = graph.lexicalLookupService,
                    languageHint = { currentBookLanguage() },
                    onStateChanged = ::showWordLookup
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
                    onAnnotateRequested = ::requestAnnotationForSelection,
                    onHapticFeedback = ::performTranslationHaptic,
                    onHighlightChanged = ::updateSelectionHighlight
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

            else -> showError("当前出版物不支持阅读。")
        }
    }

    private fun attachEpubNavigator(factory: FragmentFactory) {
        attachNavigator(factory, EpubNavigatorFragment::class.java)
        val epubNavigator = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG)
            as? EpubNavigatorFragment
            ?: return
        navigator = epubNavigator
        // Short taps belong to quick translation; word lookup is reachable from
        // the native selection menu, so the lookup controller does not register
        // a competing tap listener.
        wordInteractionController?.attach(epubNavigator, registerShortTap = false)
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
            onStateChanged = ::showTranslation
        )
        pdfTranslationController = controller
        controller.attach(fragment)
        observeLocator { fragment.currentLocator.collectLatest(it) }
    }

    private fun observeLocator(collect: suspend (suspend (Locator) -> Unit) -> Unit) {
        lifecycleScope.launch {
            collect { locator ->
                latestLocator = locator
                latestLocatorJson = locator.toJSON().toString()
                currentProgress = locator.locations.totalProgression ?: currentProgress
                currentChapterIndex = readingOrder.indexOfFirst {
                    it.url().toString() == locator.href.toString()
                }
                currentChapterName = currentChapterTitle(locator)
                applyWritingModeOverride()
                refreshBookmarkState()
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

    private fun bookmarkKey(locator: Locator): String = buildString {
        append(book?.id.orEmpty())
        append('|')
        append(locator.href)
        append('|')
        append(((locator.locations.progression ?: 0.0) * 10_000).toInt())
    }

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
        val previousOrientation = currentAppearance.orientation
        currentAppearance = appearance
        val updated = appearance.toEpubPreferences(basePreferences)
        basePreferences = updated
        navigator?.submitPreferences(updated)
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
        lifecycleScope.launch { tapTranslationController?.applyWritingModeOverride(mode) }
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
                                    color = record.color
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
                iterator.close()
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
        showComposeDialog {
            ReaderAppearancePanel(
                appearance = currentAppearance,
                onChange = ::applyAppearance,
                isPdf = pdfNavigator != null
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
                    color = record.color
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
                    progress = target.locator.locations.totalProgression ?: currentProgress
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
        // The self-drawn highlight is still a constant offset off vertically,
        // so the page keeps its native selection paint until that is resolved.
        if (DRAW_OWN_SELECTION_HIGHLIGHT) SelectionHighlightLayer()
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val available = maxHeight
            val anchorTop = with(LocalDensity.current) { anchorTopPx.toDp() }
                .coerceIn(0.dp, available)
            val anchorBottom = with(LocalDensity.current) { anchorBottomPx.toDp() }
                .coerceIn(0.dp, available)
            // Put the card wherever there is more room, so the sentence being
            // translated stays visible above or below it.
            val roomBelow = available - anchorBottom - GAP
            val roomAbove = anchorTop - GAP
            val placeBelow = roomBelow >= roomAbove
            val room = (if (placeBelow) roomBelow else roomAbove).coerceAtLeast(0.dp)
            val cardHeight = room.coerceIn(
                minimumValue = 0.dp,
                maximumValue = (available * 0.46f)
            ).coerceAtLeast(minOf(room, 150.dp))
            // A vertical Japanese page reads in columns, so iOS puts the card
            // beside the tapped column instead of above or below it.
            val isVertical = currentAppearance.orientation == ReaderTextOrientation.VERTICAL
            val widthPx = constraints.maxWidth
            val placeLeft = anchorLeftPx > widthPx / 2
            val sideWidth = with(LocalDensity.current) {
                (if (placeLeft) anchorLeftPx else widthPx - anchorRightPx).toDp()
            } - GAP * 2
            val cardModifier = if (isVertical && sideWidth >= 160.dp) {
                Modifier
                    .align(if (placeLeft) Alignment.TopStart else Alignment.TopEnd)
                    .padding(top = anchorTop.coerceAtMost(available - 200.dp))
                    .padding(horizontal = GAP)
                    .widthIn(max = minOf(sideWidth, 300.dp))
                    .heightIn(max = (available * 0.5f))
                    .offset { IntOffset(cardDragX.toInt(), cardDragY.toInt()) }
            } else Modifier
                .align(if (placeBelow) Alignment.TopStart else Alignment.BottomStart)
                .padding(
                    top = if (placeBelow) anchorBottom + GAP else 0.dp,
                    bottom = if (placeBelow) 0.dp else (available - anchorTop + GAP)
                        .coerceAtLeast(0.dp)
                )
                .padding(horizontal = 12.dp)
                // iOS keeps the overlay narrow so the page stays readable
                // beside it instead of spanning the full width.
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .heightIn(max = cardHeight)
                .offset { IntOffset(cardDragX.toInt(), cardDragY.toInt()) }

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
                        onDrag = { dx, dy -> cardDragX += dx; cardDragY += dy },
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
            val rect = navigator?.currentSelection()?.rect
            val density = resources.displayMetrics.density
            if (rect != null) {
                anchorTopPx = (readerContentTopPx + rect.top).toInt()
                anchorBottomPx = (readerContentTopPx + rect.bottom).toInt()
                anchorLeftPx = rect.left.toInt()
                anchorRightPx = rect.right.toInt()
                return@launch
            }
            val point = tapTranslationController?.focusPoint()
            if (point != null) {
                val y = readerContentTopPx + point.y * density
                anchorTopPx = (y - 12 * density).toInt()
                anchorBottomPx = (y + 12 * density).toInt()
            }
        }
    }

    private fun setFloatingLayerVisible(visible: Boolean) {
        floatingLayer?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun dismissTranslation() {
        selectionRects = emptyList()
        tapTranslationController?.clearSelection()
        currentTranslationState = null
        crossPageMessage = null
        setFloatingLayerVisible(currentWordLookupState != null)
    }

    private fun showWordLookup(state: WordLookupCardState) {
        currentWordLookupState = state
        setFloatingLayerVisible(true)
        updateFloatingAnchor()
    }

    private fun showTranslation(state: TranslationCardState) {
        if (currentTranslationState == null) {
            cardDragX = 0f
            cardDragY = 0f
        }
        currentTranslationState = state
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
        // Measure where the page actually sits instead of deriving it from the
        // inset constants; Readium adds its own padding inside the navigator,
        // so a computed offset put the highlight a line off.
        val location = IntArray(2)
        navigator?.publicationView?.getLocationInWindow(location)
        val overlayLocation = IntArray(2)
        floatingLayer?.getLocationInWindow(overlayLocation)
        readerContentTopPx = (location[1] - overlayLocation[1]).coerceAtLeast(0)
        selectionRects = rects
        if (rects.isNotEmpty()) {
            anchorTopPx = (readerContentTopPx + rects.minOf { it.top }).toInt()
            anchorBottomPx = (readerContentTopPx + rects.maxOf { it.bottom }).toInt()
            anchorLeftPx = rects.minOf { it.left }.toInt()
            anchorRightPx = rects.maxOf { it.right }.toInt()
        }
    }

    @androidx.compose.runtime.Composable
    private fun SelectionHighlightLayer() {
        val rects = selectionRects
        if (rects.isEmpty()) return
        val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
        val top = readerContentTopPx.toFloat()
        Canvas(modifier = Modifier.fillMaxSize()) {
            rects.forEach { rect ->
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(rect.left, rect.top + top),
                    size = Size(rect.width(), rect.height()),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
        }
    }

    private fun performTranslationHaptic() {
        rootView?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun retryTranslation() {
        val pdf = pdfTranslationController
        if (pdf != null) {
            pdf.retry()
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
                graph.lexicalLookupService.lookup(
                    word = word,
                    sentenceContext = null,
                    language = currentBookLanguage() ?: LanguageCode.ENGLISH,
                    candidates = emptyList()
                )
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
            graph.learningRecords.saveTranslationFavorite(
                sourceText = state.sourceText,
                result = result,
                bookId = book?.id,
                bookTitle = book?.title,
                locatorJson = latestLocatorJson
            )
        }
    }

    private fun explainTranslation(state: TranslationCardState) {
        val service = graph.translationService as? ContextExplanationService ?: return
        lifecycleScope.launch {
            val explained = runCatching {
                service.explain(
                    ContextExplanationRequest(
                        focusedText = state.sourceText,
                        contextText = null,
                        sourceLanguage = currentBookLanguage()
                    )
                )
            }.getOrNull() ?: return@launch
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
                val context = tapTranslationController?.crossPageContext(state.sourceText)
                    ?: pdfTranslationController?.crossPageContext(state.sourceText)
                val expansion = ReaderCrossPageTranslationResolver.expansion(
                    sourceText = state.sourceText,
                    contextText = context,
                    language = currentBookLanguage()
                )
                if (expansion == null) {
                    crossPageMessage = "没有找到可确认的完整句段，已保留当前译文。"
                    return@launch
                }
                val preferences = graph.translationSettings.preferences.value
                tapTranslationController?.translateExpanded(
                    TranslationRequest(
                        text = expansion.text,
                        sourceLanguage = preferences.sourceChoice.language ?: currentBookLanguage(),
                        targetLanguage = preferences.targetLanguage
                    )
                )
            } finally {
                isExpandingCrossPage = false
            }
        }
    }

    private fun annotateTranslatedText(text: String) {
        val locator = latestLocator ?: return
        dismissTranslation()
        showAnnotationEditor(PendingAnnotation(selectedText = text, locator = locator))
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
    }

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
        graph.applicationScope.launch {
            graph.repository.updateReadingProgress(
                id = bookId,
                locatorJson = locatorJson,
                openedAtEpochMillis = System.currentTimeMillis(),
                progress = progress
            )
        }
    }

    private fun persistReadingTime() {
        val bookId = book?.id ?: return
        val seconds = (System.currentTimeMillis() - sessionStartedAtMillis) / 1000.0
        if (seconds < MINIMUM_RECORDED_READING_SECONDS) return
        graph.applicationScope.launch {
            graph.repository.addReadingTime(bookId, seconds)
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

    companion object {
        const val EXTRA_BOOK_ID = "com.jerreader.reader.BOOK_ID"
        const val EXTRA_PUBLICATION_PATH = "com.jerreader.reader.PUBLICATION_PATH"
        const val EXTRA_EXPECTED_SHA256 = "com.jerreader.reader.EXPECTED_SHA256"
        const val EXTRA_EXPECTED_LAST_MODIFIED = "com.jerreader.reader.EXPECTED_LAST_MODIFIED"
        const val EXTRA_SOURCE_FORMAT = "com.jerreader.reader.SOURCE_FORMAT"

        // Enough clearance that the card never sits on the descenders of
        // the line it was anchored to.
        private const val DRAW_OWN_SELECTION_HIGHLIGHT = false
        private val GAP = 18.dp
        private const val CONTENT_TOP_INSET_DP = 74
        private const val CONTENT_BOTTOM_INSET_DP = 156
        private const val ANNOTATION_DECORATION_GROUP = "jerreader-reading-annotations"
        private const val NAVIGATOR_TAG = "JerreaderNavigator"
        private const val INTEGRITY_TAG = "JerreaderIntegrity"
        private const val PROGRESS_SAVE_DEBOUNCE_MILLIS = 500L
        private const val MINIMUM_RECORDED_READING_SECONDS = 3.0
        private const val SEARCH_RESULT_LIMIT = 80
        private const val SEARCH_PAGE_LIMIT = 6
    }
}
