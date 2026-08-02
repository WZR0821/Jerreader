package com.jerreader.android

import android.app.Application
import com.jerreader.android.data.JerreaderDatabase
import com.jerreader.android.data.RoomLibraryRepository
import com.jerreader.android.library.ImmutablePublicationStore
import com.jerreader.android.library.LibraryBookService
import com.jerreader.android.library.LibraryImportService
import com.jerreader.android.lexical.DefaultLexicalLookupService
import com.jerreader.android.lexical.ResilientLexicalLookupService
import com.jerreader.android.learning.LearningRecordRepository
import com.jerreader.android.reader.ReaderRecordRepository
import com.jerreader.android.reader.ReadiumEnvironment
import com.jerreader.android.translation.AndroidTranslationSettingsStore
import com.jerreader.android.translation.ConfiguredTranslationService
import com.jerreader.android.translation.CachedTranslationService
import com.jerreader.android.translation.TranslationCacheRepository
import com.jerreader.android.speech.AndroidSpeechService
import com.jerreader.android.settings.AndroidAppSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.jerreader.shared.translation.TranslationService

class JerreaderApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

class AppGraph(application: Application) {
    val database = JerreaderDatabase.build(application)
    val repository = RoomLibraryRepository(database.bookDao())
    val publicationStore = ImmutablePublicationStore(application)
    val readium = ReadiumEnvironment(application)
    val importService = LibraryImportService(publicationStore, readium, repository)
    val bookService = LibraryBookService(publicationStore, repository, application)
    val learningRecords = LearningRecordRepository(database.learningDao())
    val readerRecords = ReaderRecordRepository(database.readerRecordDao())
    val translationSettings = AndroidTranslationSettingsStore(application)
    val appSettings = AndroidAppSettingsStore(application)
    private val configuredTranslationService = ConfiguredTranslationService(translationSettings)
    val translationCache = TranslationCacheRepository(database.translationCacheDao())
    var translationService: TranslationService = CachedTranslationService(
        configuredTranslationService,
        translationCache,
        translationSettings
    )
    val lexicalLookupService = ResilientLexicalLookupService(
        DefaultLexicalLookupService(),
        translationService as com.jerreader.shared.translation.ContextExplanationService
    )
    val speechService = AndroidSpeechService(application)
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
