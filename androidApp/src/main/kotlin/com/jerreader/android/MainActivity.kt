package com.jerreader.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jerreader.android.reader.ReaderActivity
import com.jerreader.android.ui.AndroidAppTab
import com.jerreader.android.ui.AppNavigationBar
import com.jerreader.android.ui.SettingsRoute
import com.jerreader.android.ui.SettingsScreen
import com.jerreader.android.ui.testTranslationConnection
import com.jerreader.android.ui.translationSettingsActions
import com.jerreader.android.settings.AppThemeChoice
import com.jerreader.android.reader.encodeStoredReaderPreferences
import com.jerreader.shared.library.LibraryBook
import com.jerreader.shared.ui.JerreaderLibraryApp
import com.jerreader.shared.ui.JerreaderTheme
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val graph: AppGraph
        get() = (application as JerreaderApplication).graph

    private val viewModel by viewModels<LibraryViewModel> {
        LibraryViewModel.factory(graph.repository, graph.importService, graph.bookService)
    }

    private val choosePublication =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::importPublication)
        }

    /** Counts the reader's requests to show 翻译与 AI (`EXTRA_OPEN_SETTINGS`). */
    private var translationSettingsRequests by mutableIntStateOf(0)

    private var pendingCoverBookId: String? = null
    private var pendingCoverSelection: ((Uri) -> Unit)? = null
    private val chooseCover =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val bookId = pendingCoverBookId
            val selection = pendingCoverSelection
            pendingCoverBookId = null
            pendingCoverSelection = null
            if (uri != null) {
                if (selection != null) selection(uri)
                else if (bookId != null) viewModel.updateCover(bookId, uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Automatic backup runs off the app's own scope so rotating the screen
        // or leaving the library cannot cancel an archive halfway through, and
        // it stays silent: a due backup must never interrupt reading.
        graph.applicationScope.launch {
            runCatching { graph.backupService.backupIfDue() }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is LibraryEvent.OpenBook -> openBook(event.bookId)
                    }
                }
            }
        }

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val translationPreferences by graph.translationSettings.preferences.collectAsStateWithLifecycle()
            val appPreferences by graph.appSettings.preferences.collectAsStateWithLifecycle()
            val opensTranslationSettings = intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)
            var selectedTab by rememberSaveable {
                mutableStateOf(
                    if (opensTranslationSettings) AndroidAppTab.SETTINGS else AndroidAppTab.LIBRARY
                )
            }
            val tabStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            var settingsRoute by rememberSaveable {
                mutableStateOf(
                    if (opensTranslationSettings) SettingsRoute.TRANSLATION else SettingsRoute.ROOT
                )
            }
            // The reader's 翻译设置 button. This screen is normally still alive
            // under the book, so the request arrives through onNewIntent; the
            // start-up values above only cover a screen the system had to
            // rebuild. Until 2.0.1 the button closed the book and left the
            // reader on the shelf.
            LaunchedEffect(translationSettingsRequests) {
                if (translationSettingsRequests > 0) {
                    settingsRoute = SettingsRoute.TRANSLATION
                    selectedTab = AndroidAppTab.SETTINGS
                }
            }

            // Secondary screens are Compose state, not activities, so back has to
            // unwind them explicitly. Without this, back from a settings sub-page
            // or from a non-library tab dropped straight to the launcher.
            BackHandler(enabled = selectedTab == AndroidAppTab.SETTINGS && settingsRoute != SettingsRoute.ROOT) {
                settingsRoute = SettingsRoute.ROOT
            }
            BackHandler(
                enabled = (selectedTab != AndroidAppTab.SETTINGS || settingsRoute == SettingsRoute.ROOT) &&
                    selectedTab != AndroidAppTab.LIBRARY
            ) { selectedTab = AndroidAppTab.LIBRARY }

            val bottomBar: @Composable () -> Unit = {
                AppNavigationBar(selectedTab) { tab ->
                    // 设置 opens on its home page: the route is dropped when
                    // the reader leaves the tab, and when the tab is tapped
                    // again. It used to outlive both, so after an hour in a
                    // book 设置 still showed whichever sub-page was open last.
                    if (selectedTab == AndroidAppTab.SETTINGS) {
                        settingsRoute = SettingsRoute.ROOT
                        // The route alone is not enough: the tab's saved state
                        // still holds whatever a sub-page had open (the
                        // provider being edited under AI 大模型, a notice, a
                        // scroll position) and hands it back the next time
                        // that page appears.
                        if (tab != AndroidAppTab.SETTINGS) {
                            tabStates.removeState(AndroidAppTab.SETTINGS.name)
                        }
                    }
                    selectedTab = tab
                }
            }

            JerreaderTheme(
                accent = appPreferences.theme,
                // 界面主题 → 外观模式 and 背景光晕.
                dark = appPreferences.appearanceMode.isDark(isSystemInDarkTheme()),
                backdropGlow = appPreferences.backdropGlow
            ) {
                com.jerreader.android.ui.JerreaderSystemBars()
                tabStates.SaveableStateProvider(selectedTab.name) { when (selectedTab) {
                    AndroidAppTab.LIBRARY -> JerreaderLibraryApp(
                        state = state,
                        onImport = { mimeTypes -> choosePublication.launch(mimeTypes.toTypedArray()) },
                        onOpenBook = { openBook(it) },
                        // Opens the reader by scaling up from the tapped cover.
                        onOpenBookAt = { bookId, cover -> openBook(bookId, cover) },
                        // The shelf's date line ("11月19日 星期四") in local time.
                        utcOffsetMillisAt = { java.util.TimeZone.getDefault().getOffset(it) },
                        onDeleteBook = viewModel::deleteBook,
                        onRestoreBackup = {
                            settingsRoute = SettingsRoute.BACKUP_IMPORT
                            selectedTab = AndroidAppTab.SETTINGS
                        },
                        onDismissMessage = viewModel::clearMessage,
                        onChangeCover = { bookId ->
                            pendingCoverSelection = null
                            pendingCoverBookId = bookId
                            chooseCover.launch(arrayOf("image/*"))
                        },
                        onPickCover = { onPicked ->
                            pendingCoverBookId = null
                            pendingCoverSelection = { uri -> onPicked(uri.toString()) }
                            chooseCover.launch(arrayOf("image/*"))
                        },
                        coverDraftContent = { uri -> AndroidCoverDraft(uri) },
                        onSaveCover = { id, uri -> viewModel.updateCover(id, uri.toUri()) },
                        onUpdateBook = viewModel::updateBook,
                        onBatchUpdateBooks = { ids, category, series, language, tags ->
                            viewModel.updateBooks(
                                books = state.books.filter { it.id in ids },
                                category = category,
                                series = series,
                                language = language,
                                tags = tags
                            )
                        },
                        coverContent = { book -> AndroidBookCover(book) },
                        bottomBar = bottomBar
                    )

                    AndroidAppTab.LEARNING -> com.jerreader.android.ui.LearningScreen(
                        graph = graph,
                        bottomBar = bottomBar
                    )

                    AndroidAppTab.SETTINGS -> SettingsScreen(
                        graph = graph,
                        appPreferences = appPreferences,
                        route = settingsRoute,
                        onRouteChanged = { settingsRoute = it },
                        onThemeChanged = graph.appSettings::updateTheme,
                        onDefaultReaderChanged = { appearance ->
                            graph.appSettings.updateReaderAppearance(appearance)
                            if (appPreferences.applyReaderDefaultsToExistingBooks) {
                                val serialized = encodeStoredReaderPreferences(appearance)
                                graph.applicationScope.launch {
                                    state.books.forEach { book ->
                                        graph.repository.updatePreferences(book.id, serialized)
                                    }
                                }
                            }
                        },
                        onShowProgressChanged = graph.appSettings::updateShowReadingProgress,
                        onApplyDefaultsAutomaticallyChanged = { enabled ->
                            graph.appSettings.updateApplyReaderDefaultsToExistingBooks(enabled)
                            if (enabled) {
                                val serialized = encodeStoredReaderPreferences(
                                    appPreferences.defaultReaderAppearance
                                )
                                graph.applicationScope.launch {
                                    state.books.forEach { book ->
                                        graph.repository.updatePreferences(book.id, serialized)
                                    }
                                }
                            }
                        },
                        onApplyDefaultsToExistingBooks = {
                            val serialized = encodeStoredReaderPreferences(
                                appPreferences.defaultReaderAppearance
                            )
                            graph.applicationScope.launch {
                                state.books.forEach { book ->
                                    graph.repository.updatePreferences(book.id, serialized)
                                }
                            }
                        },
                        preferences = translationPreferences,
                        backendToken = graph.translationSettings.backendAccessToken(),
                        actions = remember(graph) { translationSettingsActions(graph.translationSettings) },
                        onTestConnection = { testTranslationConnection(graph, translationPreferences) },
                        bottomBar = bottomBar,
                        bookCount = state.books.size
                    )
                } }
            }
        }
        handleIncomingPublication(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) translationSettingsRequests++
        handleIncomingPublication(intent)
    }

    private fun handleIncomingPublication(intent: Intent?) {
        val source = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            // "分享到读鼠" from a browser, mail client or cloud drive.
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(
                intent,
                Intent.EXTRA_STREAM,
                Uri::class.java
            )
            else -> null
        }
        source?.let(viewModel::importPublication)
    }

    /**
     * Opens the reader. With [cover] (the tapped cover in window pixels) the
     * reader scales up from it, the Android counterpart of the iOS zoom
     * transition; without it the system's default activity animation runs.
     */
    private fun openBook(bookId: String, cover: androidx.compose.ui.geometry.Rect? = null) {
        lifecycleScope.launch {
            val book = graph.repository.book(bookId)
            val options = cover?.takeIf { it.width >= 1f && it.height >= 1f }?.let {
                androidx.core.app.ActivityOptionsCompat.makeScaleUpAnimation(
                    window.decorView,
                    it.left.roundToInt(),
                    it.top.roundToInt(),
                    it.width.roundToInt(),
                    it.height.roundToInt()
                ).toBundle()
            }
            startActivity(
                Intent(this@MainActivity, ReaderActivity::class.java)
                    .putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
                    .putExtra(ReaderActivity.EXTRA_SOURCE_FORMAT, book?.sourceFormat),
                options
            )
        }
    }

    @Composable
    private fun AndroidBookCover(book: LibraryBook) {
        val fileName = book.coverFileName
        val cover by produceState<Pair<String?, android.graphics.Bitmap?>?>(null, fileName) {
            val bitmap = fileName?.let { com.jerreader.android.library.BookCoverCache.load(graph.publicationStore.resolveCover(it)) }
            value = fileName to bitmap
        }

        val bitmap = cover?.takeIf { it.first == fileName }?.second
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "${book.title}封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            com.jerreader.shared.ui.JerreaderBookPlaceholder(book)
        }
    }

    /** Decode a bounded preview; picking or cancelling never modifies the stored cover. */
    @Composable
    private fun AndroidCoverDraft(source: String) {
        val preview by produceState<Pair<String, android.graphics.Bitmap?>?>(null, source) {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val uri = source.toUri()
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                    check(bounds.outWidth > 0 && bounds.outHeight > 0)
                    var sample = 1
                    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 768) sample *= 2
                    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                }.getOrNull()
            }
            value = source to bitmap
        }
        val bitmap = preview?.takeIf { it.first == source }?.second
        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "待保存的新封面",
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(if (preview?.first == source) "无法预览所选图片，请重新选择。" else "正在准备封面…",
            style = MaterialTheme.typography.labelMedium)
    }

    companion object {
        const val EXTRA_OPEN_SETTINGS = "com.jerreader.android.OPEN_SETTINGS"
    }
}
