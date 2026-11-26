package com.jerreader.android.learning

import com.jerreader.android.data.LearningDao
import com.jerreader.android.data.TranslationFavoriteEntity
import com.jerreader.android.data.WordLookupEntity
import com.jerreader.shared.lexical.WordExplanation
import com.jerreader.shared.translation.TranslationResult
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

/**
 * 保存到语法知识库 makes a new draft, with a new id, each time it is tapped, so
 * saving the same explanation twice used to leave two identical notes. A draft
 * that is not in the notebook yet takes the place of the note it repeats.
 */
internal fun grammarNoteToSave(
    note: com.jerreader.android.data.GrammarNoteEntity,
    existing: List<com.jerreader.android.data.GrammarNoteEntity>
): com.jerreader.android.data.GrammarNoteEntity {
    if (existing.any { it.id == note.id }) return note
    val same = existing.firstOrNull {
        it.title == note.title && it.sentence == note.sentence && it.explanation == note.explanation
    } ?: return note
    return note.copy(id = same.id)
}

class LearningRecordRepository(
    private val dao: LearningDao,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun observeGrammarNotes() = dao.observeGrammarNotes()
    suspend fun saveGrammarNote(note: com.jerreader.android.data.GrammarNoteEntity) {
        require(note.title.isNotBlank() && note.sentence.isNotBlank() && note.explanation.isNotBlank())
        dao.upsertGrammarNote(grammarNoteToSave(note, dao.allGrammarNotes()))
    }
    suspend fun deleteGrammarNote(id: String) = dao.deleteGrammarNote(id)

    fun observeWords(): Flow<List<WordLookupEntity>> = dao.observeWords()

    /** Every word met in a book, as the confusion map and AI lens read it. */
    suspend fun readingErrorSignals(): List<com.jerreader.shared.learning.ReadingErrorSignal> =
        ReadingErrorSignals.from(dao.allWords())
    fun observeTranslationFavorites(): Flow<List<TranslationFavoriteEntity>> =
        dao.observeTranslationFavorites()

    suspend fun recordLookup(
        explanation: WordExplanation,
        sourceBookId: String? = null,
        sourceBookTitle: String? = null,
        favorite: Boolean = false,
        /**
         * `false` saves the entry without counting one more lookup: the count
         * and 上次 stay as they were (2.1.0 §8, see [sheetLookupCounts]). A
         * word not recorded before still starts at one.
         */
        counted: Boolean = true
    ): WordLookupEntity {
        val key = wordKey(explanation)
        val timestamp = now()
        val existing = dao.word(key)
        val kept = existing?.takeIf { !counted }
        val sameContext = existing != null && existing.surfaceForm == explanation.surfaceForm &&
            existing.sentenceContext == explanation.sentenceContext && existing.sourceBookId == sourceBookId &&
            existing.definitionsText == explanation.definitions.joinToString(DEFINITION_SEPARATOR) &&
            existing.reading == explanation.reading && existing.partOfSpeech == explanation.partOfSpeech
        val record = WordLookupEntity(
            lookupKey = key,
            surfaceForm = explanation.surfaceForm,
            lemma = explanation.lemma,
            reading = explanation.reading,
            language = explanation.language.tag,
            partOfSpeech = explanation.partOfSpeech,
            definitionsText = explanation.definitions.joinToString(DEFINITION_SEPARATOR),
            inflectionNote = explanation.inflectionNote,
            usageNote = explanation.usageNote,
            aiAnalysis = existing?.aiAnalysis.takeIf { sameContext },
            aiProviderIdentifier = existing?.aiProviderIdentifier.takeIf { sameContext },
            sentenceContext = explanation.sentenceContext,
            sourceBookId = sourceBookId,
            sourceBookTitle = sourceBookTitle,
            providerIdentifier = explanation.providerIdentifier,
            lookupCount = kept?.lookupCount ?: ((existing?.lookupCount ?: 0) + 1),
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: timestamp,
            lastLookedUpAtEpochMillis = kept?.lastLookedUpAtEpochMillis ?: timestamp,
            isFavorite = favorite || existing?.isFavorite == true,
            isInHistory = true,
            reviewJson = existing?.reviewJson
        )
        dao.upsertWord(record)
        return record
    }

    suspend fun setWordFavorite(key: String, favorite: Boolean) {
        dao.setWordFavorite(key, favorite)
        dao.removeOrphanedWords()
    }

    /**
     * The stored record of a word, read before [recordLookup] overwrites its
     * 上次 time, for 第 N 次查 · 上次 … (2.1.0 §8).
     */
    suspend fun lookupRecord(key: String): WordLookupEntity? = dao.word(key)

    suspend fun setContextualFavorite(key: String, favorite: Boolean): Boolean {
        val changed = dao.setWordFavorite(key, favorite) == 1
        dao.removeOrphanedWords()
        return changed
    }

    suspend fun saveReview(key: String, state: com.jerreader.shared.learning.WordReviewState) {
        dao.updateWordReview(key, WordReviewCodec.encode(state))
    }

    suspend fun updateWordAnalysis(key: String, analysis: String, provider: String) {
        dao.updateWordAnalysis(key, analysis, provider)
    }

    suspend fun updateContextualAnalysis(snapshot: WordLookupEntity, result: com.jerreader.shared.translation.ContextExplanationResult): Boolean =
        dao.updateContextualWordAnalysis(snapshot.lookupKey, snapshot.surfaceForm, snapshot.sentenceContext,
            snapshot.sourceBookId, snapshot.lastLookedUpAtEpochMillis, snapshot.definitionsText, snapshot.reading, snapshot.partOfSpeech,
            result.explanation, result.providerIdentifier) == 1

    suspend fun removeFromHistory(key: String) {
        dao.removeWordFromHistory(key)
        dao.removeOrphanedWords()
    }

    suspend fun clearHistory() {
        dao.retainFavoritesOnlyInHistoryClear()
        dao.deleteUnfavoritedWords()
    }

    suspend fun saveTranslationFavorite(
        sourceText: String,
        result: TranslationResult,
        bookId: String? = null,
        bookTitle: String? = null,
        locatorJson: String? = null
    ): String {
        val key = translationKey(sourceText, result.translatedText, result.targetLanguage.tag)
        val existing = dao.translationFavorite(key)
        val timestamp = now()
        dao.upsertTranslationFavorite(
            TranslationFavoriteEntity(
                favoriteKey = key,
                sourceText = sourceText,
                translatedText = result.translatedText,
                sourceLanguage = result.sourceLanguage.tag,
                targetLanguage = result.targetLanguage.tag,
                providerIdentifier = result.providerIdentifier,
                bookId = bookId,
                bookTitle = bookTitle,
                locatorJson = locatorJson,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: timestamp,
                updatedAtEpochMillis = timestamp,
                practiceJson = existing?.practiceJson
            )
        )
        return key
    }

    suspend fun savePractice(key: String, json: String) { dao.updatePractice(key, json) }

    suspend fun deleteTranslationFavorite(key: String) {
        dao.deleteTranslationFavorite(key)
    }

    companion object {
        const val DEFINITION_SEPARATOR = "\u001F"

        fun wordKey(explanation: WordExplanation): String =
            "${explanation.language.tag}|${(explanation.lemma ?: explanation.surfaceForm).trim().lowercase()}"

        /**
         * Whether the lookup sheet's record of [explanation] counts as a lookup
         * (2.1.0 §8). [cardCountedKey] is the word a one-word translation card
         * already counted, when 查词 on that card opened the sheet: the same
         * word is then not counted a second time. Any other record counts.
         */
        fun sheetLookupCounts(explanation: WordExplanation, cardCountedKey: String?): Boolean =
            cardCountedKey == null || wordKey(explanation) != cardCountedKey

        fun translationKey(source: String, translated: String, target: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$source\u001F$translated\u001F$target".encodeToByteArray())
            return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }
}
