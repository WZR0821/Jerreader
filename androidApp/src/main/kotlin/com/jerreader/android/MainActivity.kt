package com.jerreader.android

import android.content.Intent
import android.graphics.BitmapFactory
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.jerreader.android.ui.TranslationSettingsActions
import com.jerreader.android.settings.AppThemeChoice
import com.jerreader.android.reader.encodeStoredReaderPreferences
import com.jerreader.unified.domain.LanguageCode
import com.jerreader.unified.library.LibraryBook
import com.jerreader.unified.translation.TranslationRequest
import com.jerreader.unified.ui.JerreaderLibraryApp
import com.jerreader.unified.ui.JerreaderTheme
import android.net.Uri
import androidx.core.content.IntentCompat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private var pendingCoverBookId: String? = null
    private val chooseCover =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val bookId = pendingCoverBookId
            pendingCoverBookId = null
            if (uri != null && bookId != null) viewModel.updateCover(bookId, uri)
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
            var selectedTab by rememberSaveable {
                mutableStateOf(
                    if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
                        AndroidAppTab.SETTINGS
                    } else {
                        AndroidAppTab.LIBRARY
                    }
                )
            }
            var settingsRoute by rememberSaveable { mutableStateOf(SettingsRoute.ROOT) }

            LaunchedEffect(appPreferences.learningModuleVisible) {
                if (!appPreferences.learningModuleVisible && selectedTab == AndroidAppTab.LEARNING) {
                    selectedTab = AndroidAppTab.LIBRARY
                }
            }

            // Secondary screens are Compose state, not activities, so back has to
            // unwind them explicitly. Without this, back from a settings sub-page
            // or from a non-library tab dropped straight to the launcher.
            BackHandler(enabled = settingsRoute != SettingsRoute.ROOT) {
                settingsRoute = SettingsRoute.ROOT
            }
            BackHandler(
                enabled = settingsRoute == SettingsRoute.ROOT &&
                    selectedTab != AndroidAppTab.LIBRARY
            ) { selectedTab = AndroidAppTab.LIBRARY }

            val bottomBar: @Composable () -> Unit = {
                AppNavigationBar(
                    selected = selectedTab,
                    showLearning = appPreferences.learningModuleVisible
                ) { selectedTab = it }
            }

            val systemIsDark = isSystemInDarkTheme()
            val appIsDark = appPreferences.appearanceMode.isDark(systemIsDark)
            JerreaderTheme(accent = appPreferences.theme, dark = appIsDark) {
                when (selectedTab) {
                    AndroidAppTab.LIBRARY -> JerreaderLibraryApp(
                        state = state,
                        onImport = {
                            choosePublication.launch(
                                arrayOf(
                                    "application/epub+zip",
                                    "application/pdf",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "text/plain"
                                )
                            )
                        },
                        onOpenBook = ::openBook,
                        onDeleteBook = viewModel::deleteBook,
                        onRestoreBackup = {
                            settingsRoute = SettingsRoute.BACKUP_IMPORT
                            selectedTab = AndroidAppTab.SETTINGS
                        },
                        onDismissMessage = viewModel::clearMessage,
                        onChangeCover = { bookId ->
                            pendingCoverBookId = bookId
                            chooseCover.launch(arrayOf("image/*"))
                        },
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
                        onAppearanceModeChanged = graph.appSettings::updateAppearanceMode,
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
                        onLearningModuleVisibleChanged =
                            graph.appSettings::updateLearningModuleVisible,
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
                        directApiKey = graph.translationSettings.directApiKey(),
                        backendToken = graph.translationSettings.backendAccessToken(),
                        actions = TranslationSettingsActions(
                            updateProviderMode = graph.translationSettings::updateProviderMode,
                            updateDirectProvider = graph.translationSettings::updateDirectProvider,
                            updateDirectEndpoint = graph.translationSettings::updateDirectEndpoint,
                            updateDirectModel = graph.translationSettings::updateDirectModel,
                            updateDirectApiKey = graph.translationSettings::updateDirectApiKey,
                            updateBackendEndpoint = graph.translationSettings::updateBackendEndpoint,
                            updateBackendModel = graph.translationSettings::updateBackendModel,
                            updateBackendToken = graph.translationSettings::updateBackendAccessToken,
                            updateSourceChoice = graph.translationSettings::updateSourceChoice,
                            updateTargetLanguage = graph.translationSettings::updateTargetLanguage,
                            updateQuickEnabled = graph.translationSettings::updateQuickTranslationEnabled,
                            updateQuickUnit = graph.translationSettings::updateQuickTranslationUnit,
                            updateDisablesTapPageTurns = graph.translationSettings::updateDisablesTapPageTurns,
                            updateDisplayMode = graph.translationSettings::updateDisplayMode,
                            updateTranslationHaptics = graph.translationSettings::updateTranslationHaptics,
                            updateAutomaticRetry = graph.translationSettings::updateAutomaticRetry,
                            updateFallbackMode = graph.translationSettings::updateFallbackMode,
                            updatePreferAIWhenConfigured =
                                graph.translationSettings::updatePreferAIWhenConfigured,
                            updatePrompt = graph.translationSettings::updateTranslationPrompt,
                            updateGrammarPrompt = graph.translationSettings::updateGrammarPrompt
                        ),
                        onTestConnection = {
                            val sourceLanguage = if (
                                translationPreferences.targetLanguage == LanguageCode.ENGLISH
                            ) {
                                LanguageCode.JAPANESE
                            } else {
                                LanguageCode.ENGLISH
                            }
                            runCatching {
                                graph.translationService.translate(
                                    TranslationRequest(
                                        text = if (sourceLanguage == LanguageCode.JAPANESE) "こんにちは" else "Hello",
                                        sourceLanguage = sourceLanguage,
                                        targetLanguage = translationPreferences.targetLanguage
                                    )
                                )
                            }.fold(
                                onSuccess = { "连接成功：${it.translatedText}" },
                                onFailure = { it.message ?: "连接失败，请检查配置。" }
                            )
                        },
                        bottomBar = bottomBar
                    )
                }
            }
        }
        handleIncomingPublication(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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

    private fun openBook(bookId: String) {
        lifecycleScope.launch {
            val book = graph.repository.book(bookId)
            startActivity(
                Intent(this@MainActivity, ReaderActivity::class.java)
                    .putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
                    .putExtra(ReaderActivity.EXTRA_SOURCE_FORMAT, book?.sourceFormat)
            )
        }
    }

    @Composable
    private fun AndroidBookCover(book: LibraryBook) {
        val cover by produceState<android.graphics.Bitmap?>(null, book.coverFileName) {
            value = withContext(Dispatchers.IO) {
                book.coverFileName
                    ?.let(graph.publicationStore::resolveCover)
                    ?.takeIf(File::isFile)
                    ?.let { file ->
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, bounds)
                        val sample = if (bounds.outWidth > 960 || bounds.outHeight > 960) 2 else 1
                        BitmapFactory.decodeFile(
                            file.absolutePath,
                            BitmapFactory.Options().apply { inSampleSize = sample }
                        )
                    }
            }
        }

        val bitmap = cover
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "${book.title}封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    book.title.take(1).ifEmpty { "J" },
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_SETTINGS = "com.jerreader.android.OPEN_SETTINGS"
    }
}
