package com.jerreader.android.learning

import com.jerreader.android.data.LearningDao
import com.jerreader.android.data.TranslationFavoriteEntity
import com.jerreader.android.data.WordLookupEntity
import com.jerreader.shared.lexical.WordExplanation
import com.jerreader.shared.translation.TranslationResult
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

class LearningRecordRepository(
    private val dao: LearningDao,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun observeWords(): Flow<List<WordLookupEntity>> = dao.observeWords()
    fun observeTranslationFavorites(): Flow<List<TranslationFavoriteEntity>> =
        dao.observeTranslationFavorites()

    suspend fun recordLookup(
        explanation: WordExplanation,
        sourceBookId: String? = null,
        sourceBookTitle: String? = null,
        favorite: Boolean = false
    ): WordLookupEntity {
        val key = wordKey(explanation)
        val timestamp = now()
        val existing = dao.word(key)
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
            aiAnalysis = existing?.aiAnalysis,
            aiProviderIdentifier = existing?.aiProviderIdentifier,
            sentenceContext = explanation.sentenceContext,
            sourceBookId = sourceBookId,
            sourceBookTitle = sourceBookTitle,
            providerIdentifier = explanation.providerIdentifier,
            lookupCount = (existing?.lookupCount ?: 0) + 1,
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: timestamp,
            lastLookedUpAtEpochMillis = timestamp,
            isFavorite = favorite || existing?.isFavorite == true,
            isInHistory = true
        )
        dao.upsertWord(record)
        return record
    }

    suspend fun setWordFavorite(key: String, favorite: Boolean) {
        dao.setWordFavorite(key, favorite)
        dao.removeOrphanedWords()
    }

    suspend fun updateWordAnalysis(key: String, analysis: String, provider: String) {
        dao.updateWordAnalysis(key, analysis, provider)
    }

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
                createdAtEpochMillis = timestamp,
                updatedAtEpochMillis = timestamp
            )
        )
        return key
    }

    suspend fun deleteTranslationFavorite(key: String) {
        dao.deleteTranslationFavorite(key)
    }

    companion object {
        const val DEFINITION_SEPARATOR = "\u001F"

        fun wordKey(explanation: WordExplanation): String =
            "${explanation.language.tag}|${(explanation.lemma ?: explanation.surfaceForm).trim().lowercase()}"

        fun translationKey(source: String, translated: String, target: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$source\u001F$translated\u001F$target".encodeToByteArray())
            return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }
}
