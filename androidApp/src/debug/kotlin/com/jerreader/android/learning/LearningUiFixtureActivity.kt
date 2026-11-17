package com.jerreader.android.learning

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import androidx.room.withTransaction
import com.jerreader.android.AppGraph
import com.jerreader.android.data.*
import com.jerreader.android.ui.*
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.WordExplanation
import com.jerreader.shared.ui.JerreaderTheme
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Debug-only, unexported activity. Learning records exist only in memory. */
class LearningUiFixtureActivity : ComponentActivity() {
    private var coverSelection: ((String) -> Unit)? = null
    private val chooseCover = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val selected = coverSelection
        coverSelection = null
        if (uri != null) selected?.invoke(uri.toString())
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Room.inMemoryDatabaseBuilder(this, JerreaderDatabase::class.java).build()
        val graph = AppGraph(application, db)
        lifecycleScope.launch {
            if (!intent.getBooleanExtra("empty", false)) {
                val now = System.currentTimeMillis()
                db.bookDao().insert(BookEntity(id = "ui-book", title = "中日阅读练习", author = "Jerreader", language = "ja",
                    publicationFileName = "ui-fixture-not-a-publication.epub", coverFileName = null, fingerprint = "ui-fixture",
                    fileSize = 0, publicationLastModified = 0, importedAtEpochMillis = now, lastOpenedAtEpochMillis = null,
                    locatorJson = null, preferencesJson = null,
                    activityJson = ReadingActivity(mapOf(LocalDate.now().toString() to ReadingDay(1200.0, 4))).encode()))
                db.withTransaction {
                    repeat(intent.getIntExtra("wordCount", 1).coerceIn(1, 2000)) { index ->
                        val term = if (index == 0) "食べる" else "単語${index + 1}"
                        db.learningDao().upsertWord(WordLookupEntity("ui-word-$index", term, term, "たべる", LanguageCode.JAPANESE.tag, "一段动词", "吃",
                            null, null, null, null, "毎朝、ご飯を食べる。", "ui-book", "中日阅读练习", "ui-fixture", 1, now - index, now - index, true, true))
                    }
                }
                db.learningDao().upsertTranslationFavorite(TranslationFavoriteEntity("ui-sentence", "毎日、日本語の本を読む。", "每天读日语书。",
                    LanguageCode.JAPANESE.tag, LanguageCode.CHINESE_SIMPLIFIED.tag, "ui-fixture", "ui-book", "中日阅读练习", null, now, now,
                    """{"answer":"読む","prompt":"毎日、日本語の本を［____］。"}"""))
                graph.learningRecords.saveGrammarNote(GrammarNoteEntity(title = "〜ながら：同时进行的动作", sentence = "音楽を聞きながら、本を読む。",
                    explanation = "动词ます形去掉ます后接「ながら」，表示同一个人同时进行两个动作，重点通常在后面的动作。",
                    language = "ja", tags = "同时进行，接续", bookId = "ui-book", bookTitle = "中日阅读练习"))
            }
            setContent {
                val density = LocalDensity.current
                val large = intent.getBooleanExtra("large", false)
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.5f else 1f)) {
                    JerreaderTheme(dark = large) {
                        JerreaderSystemBars()
                        if (intent.getBooleanExtra("contextual", false)) {
                            ContextualLookupSheet(
                                input = ContextualLookupInput(intent.getStringExtra("word") ?: "食べました", "昨日、家でご飯を食べました。", LanguageCode.JAPANESE),
                                dictionary = { graph.offlineDictionary.japaneseChinese },
                                lookup = com.jerreader.shared.lexical.MockLexicalLookupService(),
                                explanationService = object : com.jerreader.shared.translation.ContextExplanationService {
                                    override suspend fun explain(request: com.jerreader.shared.translation.ContextExplanationRequest) =
                                        com.jerreader.shared.translation.ContextExplanationResult("这是离线测试的语境建议：原句描述已经发生的动作。", "ui-mock")
                                },
                                onRecord = { value ->
                                    check(!intent.getBooleanExtra("failSave", false)) { "Mock persistence failure" }
                                    graph.learningRecords.recordLookup(value, "ui-book", "中日阅读练习")
                                },
                                onAnalysis = { snapshot, result -> graph.learningRecords.updateContextualAnalysis(snapshot, result) },
                                onFavorite = { snapshot, favorite -> graph.learningRecords.setContextualFavorite(snapshot.lookupKey, favorite) },
                                onDismiss = { finish() })
                        } else if (intent.getBooleanExtra("aiModels", false)) {
                            // The AI 大模型 pages on their own settings and a
                            // key store in memory; requests never leave the device.
                            val store = androidx.compose.runtime.remember {
                                this@LearningUiFixtureActivity.deleteSharedPreferences(FIXTURE_AI_PREFERENCES)
                                com.jerreader.android.translation.AndroidTranslationSettingsStore(
                                    this@LearningUiFixtureActivity,
                                    FixtureKeyStore(),
                                    FIXTURE_AI_PREFERENCES
                                )
                            }
                            val requests = androidx.compose.runtime.remember {
                                AIProviderRequests(check = { 0.4 }, models = { _, _, _ -> listOf("mock-text-latest") })
                            }
                            AIModelsScreen(store, requests, backTitle = "设置", onBack = { finish() })
                        } else {
                            val fixtureTab = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(AndroidAppTab.LEARNING) }
                            val shelf by androidx.compose.runtime.remember(graph) { graph.repository.observeBooks() }.collectAsStateWithLifecycle(emptyList())
                            val bar: @androidx.compose.runtime.Composable () -> Unit = {
                                AppNavigationBar(fixtureTab.value) { if (it != AndroidAppTab.SETTINGS) fixtureTab.value = it }
                            }
                            if (fixtureTab.value == AndroidAppTab.LIBRARY) {
                                com.jerreader.shared.ui.JerreaderLibraryApp(
                                    state = com.jerreader.shared.ui.LibraryUiState(books = shelf, isLoading = false),
                                    onImport = {}, onOpenBook = {}, onDeleteBook = {}, bottomBar = bar,
                                    onPickCover = { selected -> coverSelection = selected; chooseCover.launch(arrayOf("image/*")) })
                            } else LearningScreen(graph, initialSection = "translate", bottomBar = bar)
                        }
                    }
                }
            }
        }
    }
}

private const val FIXTURE_AI_PREFERENCES = "fixture_ai_providers"

/** Keys for the AI 大模型 fixture, kept in memory only. */
private class FixtureKeyStore : com.jerreader.android.translation.TranslationCredentialStore {
    private val values = mutableMapOf<String, String>()

    override fun read(account: String): String? = values[account]

    override fun save(account: String, secret: String?) {
        if (secret == null) values.remove(account) else values[account] = secret
    }
}
