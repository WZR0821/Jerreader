package com.jerreader.android

import android.app.Application
import com.jerreader.android.data.JerreaderDatabase
import com.jerreader.android.data.RoomLibraryRepository
import com.jerreader.android.library.ImmutablePublicationStore
import com.jerreader.android.library.LibraryBookService
import com.jerreader.android.library.LibraryImportService
import com.jerreader.android.lexical.DefaultLexicalLookupService
import com.jerreader.android.reader.ReadiumEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class JerreaderApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

class AppGraph(application: Application) {
    val database = JerreaderDatabase.build(application)
    val repository = RoomLibraryRepository(database.bookDao())
    val publicationStore = ImmutablePublicationStore(application)
    val readium = ReadiumEnvironment(application)
    val importService = LibraryImportService(publicationStore, readium, repository)
    val bookService = LibraryBookService(publicationStore, repository)
    val lexicalLookupService = DefaultLexicalLookupService()
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
