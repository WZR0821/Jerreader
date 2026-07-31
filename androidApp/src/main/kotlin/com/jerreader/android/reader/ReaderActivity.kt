package com.jerreader.android.reader

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import com.jerreader.android.JerreaderApplication
import com.jerreader.android.R
import com.jerreader.android.library.PublicationIntegrity
import com.jerreader.android.library.PublicationSnapshot
import com.jerreader.shared.library.LibraryBook
import com.jerreader.shared.library.ReaderAppearance
import com.jerreader.shared.library.ReaderThemeOption
import com.jerreader.shared.ui.ReaderAppearancePanel
import com.jerreader.shared.ui.ReaderChapter
import com.jerreader.shared.ui.ReaderTableOfContents
import com.jerreader.shared.ui.ReaderToolbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.EpubPreferencesSerializer
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.isRestricted
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
    private var currentPreferences = EpubPreferences()
    private var latestLocatorJson: String? = null
    private var rootView: FrameLayout? = null
    private var tableOfContents = emptyList<ReaderChapter>()
    private val tableOfContentsLinks = linkedMapOf<String, Link>()

    internal var currentAppearance by mutableStateOf(ReaderAppearance())
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android may restore Navigator fragments before the publication is
        // reopened. A dummy factory makes restoration safe until we replace it.
        val publicationPath = intent.getStringExtra(EXTRA_PUBLICATION_PATH).orEmpty()
        supportFragmentManager.fragmentFactory = if (publicationPath.endsWith(".pdf", true)) {
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
        publication?.close()
        publication = null
        navigator = null

        if (::expectedSnapshot.isInitialized && !PublicationIntegrity.isUnchanged(expectedSnapshot)) {
            Log.e(INTEGRITY_TAG, "Readium session changed immutable publication bytes or mtime")
        }
        super.onDestroy()
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

        openPublication(File(snapshot.path), resolvedBook)
    }

    private fun showRoot() {
        val toolbarHeight = (56 * resources.displayMetrics.density).toInt()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val readerContainer = FrameLayout(this).apply { id = R.id.reader_container }
        root.addView(
            readerContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { topMargin = toolbarHeight }
        )
        root.addView(
            ProgressBar(this).apply { id = R.id.reader_loading },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        root.addView(
            ComposeView(this).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                setContent {
                    MaterialTheme {
                        ReaderToolbar(
                            title = book?.title ?: "Jerreader",
                            onBack = onBackPressedDispatcher::onBackPressed,
                            onTableOfContents = ::showTableOfContents,
                            onAppearance = ::showAppearance
                        )
                    }
                }
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                toolbarHeight,
                Gravity.TOP
            )
        )
        rootView = root
        setContentView(root)
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
        when {
            openedPublication.conformsTo(Publication.Profile.EPUB) -> {
                buildTableOfContents(openedPublication.tableOfContents)
                val initialLocator = libraryBook?.locatorJson?.let(::parseLocator)
                currentPreferences = libraryBook?.preferencesJson
                    ?.let(::parsePreferences)
                    ?: EpubPreferences()
                currentAppearance = currentPreferences.toAppearance()
                val factory = EpubNavigatorFactory(openedPublication).createFragmentFactory(
                    initialLocator = initialLocator,
                    initialPreferences = currentPreferences
                )
                attachEpubNavigator(factory)
            }

            // Kept only for the existing M0 integrity harness. M1 imports EPUB
            // exclusively and does not expose PDF on the production shelf.
            openedPublication.conformsTo(Publication.Profile.PDF) -> {
                val factory = PdfNavigatorFactory(
                    openedPublication,
                    PdfiumEngineProvider()
                ).createFragmentFactory()
                attachNavigator(factory, PdfNavigatorFragment::class.java)
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
        lifecycleScope.launch {
            epubNavigator.currentLocator.collectLatest { locator ->
                latestLocatorJson = locator.toJSON().toString()
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
        return navigator?.go(link, animated = false) ?: false
    }

    internal fun applyAppearance(appearance: ReaderAppearance) {
        currentAppearance = appearance
        currentPreferences = currentPreferences.copy(
            fontSize = appearance.fontScale,
            theme = appearance.theme.toReadiumTheme()
        )
        navigator?.submitPreferences(currentPreferences)

        val bookId = book?.id ?: return
        val serialized = EpubPreferencesSerializer().serialize(currentPreferences)
        graph.applicationScope.launch {
            graph.repository.updatePreferences(bookId, serialized)
        }
    }

    private fun showTableOfContents() {
        showComposeDialog { dialog ->
            ReaderTableOfContents(
                chapters = tableOfContents,
                onSelect = { id ->
                    navigateToChapter(id)
                    dialog.dismiss()
                }
            )
        }
    }

    private fun showAppearance() {
        showComposeDialog {
            ReaderAppearancePanel(
                appearance = currentAppearance,
                onChange = ::applyAppearance
            )
        }
    }

    private fun showComposeDialog(content: @androidx.compose.runtime.Composable (Dialog) -> Unit) {
        val dialog = Dialog(this)
        dialog.setContentView(
            ComposeView(this).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindow
                )
                setContent {
                    MaterialTheme {
                        Surface { content(dialog) }
                    }
                }
            }
        )
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    private fun persistLatestLocator() {
        val bookId = book?.id ?: return
        val locatorJson = latestLocatorJson ?: return
        graph.applicationScope.launch {
            graph.repository.updateReadingProgress(
                id = bookId,
                locatorJson = locatorJson,
                openedAtEpochMillis = System.currentTimeMillis()
            )
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

    private fun parsePreferences(json: String): EpubPreferences? = runCatching {
        EpubPreferencesSerializer().deserialize(json)
    }.getOrNull()

    private fun EpubPreferences.toAppearance(): ReaderAppearance = ReaderAppearance(
        fontScale = fontSize ?: 1.0,
        theme = when (theme) {
            Theme.DARK -> ReaderThemeOption.DARK
            Theme.SEPIA -> ReaderThemeOption.SEPIA
            else -> ReaderThemeOption.LIGHT
        }
    )

    private fun ReaderThemeOption.toReadiumTheme(): Theme = when (this) {
        ReaderThemeOption.LIGHT -> Theme.LIGHT
        ReaderThemeOption.SEPIA -> Theme.SEPIA
        ReaderThemeOption.DARK -> Theme.DARK
    }

    companion object {
        const val EXTRA_BOOK_ID = "com.jerreader.reader.BOOK_ID"
        const val EXTRA_PUBLICATION_PATH = "com.jerreader.reader.PUBLICATION_PATH"
        const val EXTRA_EXPECTED_SHA256 = "com.jerreader.reader.EXPECTED_SHA256"
        const val EXTRA_EXPECTED_LAST_MODIFIED = "com.jerreader.reader.EXPECTED_LAST_MODIFIED"

        private const val NAVIGATOR_TAG = "JerreaderNavigator"
        private const val INTEGRITY_TAG = "JerreaderIntegrity"
        private const val PROGRESS_SAVE_DEBOUNCE_MILLIS = 500L
    }
}
