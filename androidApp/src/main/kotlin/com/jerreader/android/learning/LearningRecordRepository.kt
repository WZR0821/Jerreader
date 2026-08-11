package com.jerreader.android.learning

import com.jerreader.android.data.LearningDao
import com.jerreader.android.data.TranslationFavoriteEntity
import com.jerreader.android.data.WordLookupEntity
import com.jerreader.unified.lexical.WordExplanation
import com.jerreader.unified.lexical.VocabularyLearningPolicy
import com.jerreader.unified.lexical.VocabularyStatus
import com.jerreader.unified.lexical.VocabularyReviewRating
import com.jerreader.unified.lexical.VocabularyReviewScheduler
import com.jerreader.unified.lexical.WordExample
import com.jerreader.unified.translation.TranslationResult
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
        val currentStatus = existing?.vocabularyStatus
            ?.let(VocabularyStatus::fromStorageId)
            ?: VocabularyLearningPolicy.initialStatus(favorite)
        val status = if (favorite) {
            VocabularyLearningPolicy.statusAfterSaving(currentStatus)
        } else {
            currentStatus
        }
        val contexts = VocabularyLearningPolicy.contextsAfterEncounter(
            VocabularyLearningPolicy.decodeContexts(existing?.contextHistoryText),
            explanation.sentenceContext
        )
        val record = WordLookupEntity(
            lookupKey = key,
            surfaceForm = explanation.surfaceForm,
            lemma = explanation.lemma,
            reading = explanation.reading,
            language = explanation.language.tag,
            partOfSpeech = explanation.partOfSpeech,
            definitionsText = explanation.definitions.joinToString(DEFINITION_SEPARATOR),
            inflectionNote = explanation.inflectionNote,
            examplesText = encodeExamples(explanation.examples),
            usageNote = explanation.usageNote,
            aiAnalysis = existing?.aiAnalysis,
            aiProviderIdentifier = existing?.aiProviderIdentifier,
            sentenceContext = explanation.sentenceContext?.takeIf(String::isNotBlank)
                ?: existing?.sentenceContext,
            sourceBookId = sourceBookId ?: existing?.sourceBookId,
            sourceBookTitle = sourceBookTitle ?: existing?.sourceBookTitle,
            providerIdentifier = explanation.providerIdentifier,
            lookupCount = (existing?.lookupCount ?: 0) + 1,
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: timestamp,
            lastLookedUpAtEpochMillis = timestamp,
            isFavorite = favorite || existing?.isFavorite == true,
            isInHistory = true,
            vocabularyStatus = status.storageId,
            contextHistoryText = VocabularyLearningPolicy.encodeContexts(contexts),
            reviewCount = existing?.reviewCount ?: 0,
            reviewStage = existing?.reviewStage ?: 0,
            reviewIntervalDays = existing?.reviewIntervalDays ?: 0,
            reviewLapseCount = existing?.reviewLapseCount ?: 0,
            lastReviewedAtEpochMillis = existing?.lastReviewedAtEpochMillis ?: 0,
            nextReviewAtEpochMillis = existing?.nextReviewAtEpochMillis ?: 0
        )
        dao.upsertWord(record)
        return record
    }

    suspend fun setWordFavorite(key: String, favorite: Boolean) {
        val existing = dao.word(key) ?: return
        val current = VocabularyStatus.fromStorageId(existing.vocabularyStatus)
        val status = if (favorite) {
            VocabularyLearningPolicy.statusAfterSaving(current)
        } else {
            current
        }
        dao.setWordFavorite(key, favorite, status.storageId)
        dao.removeOrphanedWords()
    }

    suspend fun setVocabularyStatus(key: String, status: VocabularyStatus) {
        dao.setVocabularyStatus(key, status.storageId)
    }

    suspend fun reviewWord(key: String, rating: VocabularyReviewRating) {
        val existing = dao.word(key) ?: return
        val result = VocabularyReviewScheduler.review(
            reviewCount = existing.reviewCount,
            reviewStage = existing.reviewStage,
            currentIntervalDays = existing.reviewIntervalDays,
            currentLapseCount = existing.reviewLapseCount,
            currentStatus = VocabularyStatus.fromStorageId(existing.vocabularyStatus),
            rating = rating,
            reviewedAtEpochMillis = now()
        )
        dao.updateReviewState(
            key = key,
            status = result.status.storageId,
            reviewCount = result.reviewCount,
            reviewStage = result.reviewStage,
            intervalDays = result.intervalDays,
            lapseCount = result.lapseCount,
            lastReviewedAt = result.lastReviewedAtEpochMillis,
            nextReviewAt = result.nextReviewAtEpochMillis
        )
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
        private const val EXAMPLE_SEPARATOR = "\u001D"
        private const val EXAMPLE_FIELD_SEPARATOR = "\u001C"

        fun encodeExamples(examples: List<WordExample>): String = examples.joinToString(
            EXAMPLE_SEPARATOR
        ) { example ->
            listOf(
                example.sourceText,
                example.translatedText.orEmpty(),
                example.sourceLabel.orEmpty()
            ).joinToString(EXAMPLE_FIELD_SEPARATOR) { value ->
                value.replace(EXAMPLE_SEPARATOR, " ").replace(EXAMPLE_FIELD_SEPARATOR, " ")
            }
        }

        fun decodeExamples(raw: String): List<WordExample> = raw
            .split(EXAMPLE_SEPARATOR)
            .filter(String::isNotBlank)
            .map { row ->
                val fields = row.split(EXAMPLE_FIELD_SEPARATOR)
                WordExample(
                    sourceText = fields.getOrElse(0) { "" },
                    translatedText = fields.getOrNull(1)?.takeIf(String::isNotBlank),
                    sourceLabel = fields.getOrNull(2)?.takeIf(String::isNotBlank)
                )
            }

        fun wordKey(explanation: WordExplanation): String =
            "${explanation.language.tag}|${(explanation.lemma ?: explanation.surfaceForm).trim().lowercase()}"

        fun translationKey(source: String, translated: String, target: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$source\u001F$translated\u001F$target".encodeToByteArray())
            return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }
}
