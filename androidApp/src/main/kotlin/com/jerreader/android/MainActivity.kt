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
import com.jerreader.android.ui.SettingsScreen
import com.jerreader.android.ui.TranslationSettingsActions
import com.jerreader.android.settings.AppThemeChoice
import com.jerreader.android.reader.encodeStoredReaderPreferences
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.library.LibraryBook
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.ui.AppGuideScreen
import com.jerreader.shared.ui.JerreaderLibraryApp
import com.jerreader.shared.ui.JerreaderTheme
import java.io.File
import kotlinx.coroutines.launch

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
            var showGuide by rememberSaveable { mutableStateOf(false) }

            // Secondary screens are Compose state, not activities, so back has to
            // unwind them explicitly. Without this, back from the guide or from
            // the learning/settings tab dropped straight to the launcher.
            BackHandler(enabled = showGuide) { showGuide = false }
            BackHandler(enabled = !showGuide && selectedTab != AndroidAppTab.LIBRARY) {
                selectedTab = AndroidAppTab.LIBRARY
            }

            val bottomBar: @Composable () -> Unit = {
                AppNavigationBar(selectedTab) { selectedTab = it }
            }

            JerreaderTheme(accent = appPreferences.theme) {
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
                        onChangeCover = { bookId ->
                            pendingCoverBookId = bookId
                            chooseCover.launch(arrayOf("image/*"))
                        },
                        onUpdateBook = viewModel::updateBook,
                        coverContent = { book -> AndroidBookCover(book) },
                        bottomBar = bottomBar
                    )

                    AndroidAppTab.LEARNING -> com.jerreader.android.ui.LearningScreen(
                        graph = graph,
                        bottomBar = bottomBar
                    )

                    AndroidAppTab.SETTINGS -> if (showGuide) {
                        AppGuideScreen(onBack = { showGuide = false })
                    } else SettingsScreen(
                        appPreferences = appPreferences,
                        onThemeChanged = graph.appSettings::updateTheme,
                        onOpenGuide = { showGuide = true },
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
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.data?.let(viewModel::importPublication)
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
        val cover = remember(book.coverFileName) {
            book.coverFileName
                ?.let(graph.publicationStore::resolveCover)
                ?.takeIf(File::isFile)
                ?.let { file -> BitmapFactory.decodeFile(file.absolutePath) }
        }

        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
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
