package com.jerreader.shared.parity

import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.learning.AdaptiveReadingAssistPolicy
import com.jerreader.shared.learning.ChapterClosurePolicy
import com.jerreader.shared.learning.ContinuationRecapPolicy
import com.jerreader.shared.learning.ReadingAssistLevel
import com.jerreader.shared.learning.ReadingCompanionMode
import com.jerreader.shared.learning.ReadingConfusionItem
import com.jerreader.shared.learning.ReadingConfusionKind
import com.jerreader.shared.learning.ReadingConfusionMapPolicy
import com.jerreader.shared.learning.ReadingErrorSignal
import com.jerreader.shared.learning.ReadingReviewSummary
import com.jerreader.shared.learning.ReviewAnswer
import com.jerreader.shared.learning.ReviewGrade
import com.jerreader.shared.learning.SentencePractice
import com.jerreader.shared.learning.WordReviewState
import com.jerreader.shared.library.LibraryBackupArchiveInfo
import com.jerreader.shared.library.LibraryBackupPolicy
import com.jerreader.shared.library.LibraryBackupPruner
import com.jerreader.shared.translation.AIReadingLensMode
import com.jerreader.shared.translation.AIReadingLensPromptPolicy
import com.jerreader.shared.translation.ReadingLensOptions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs the cases in `shared/parity/` against the shared Kotlin rules. The iOS
 * host suite `parity-fixtures` (scripts/test_parity_fixtures.swift) runs the
 * same files against the Swift rules, so a change on one side that the other
 * does not make fails one of the two.
 */
class ParityFixturesTest {
    @Test
    fun wordReviewMatchesFixtures() {
        val root = fixture("word_review.json")
        val now = root.getLong("nowMs")
        root.getJSONArray("cases").objects().forEach { case ->
            val name = case.getString("name")
            var state = WordReviewState(
                step = case.getInt("startStep"),
                paused = case.getBoolean("paused"),
                answers = List(case.getInt("prefilledAnswers")) { ReviewAnswer(at = 0, known = false) }
            )
            case.getJSONArray("steps").objects().forEachIndexed { index, step ->
                val grade = ReviewGrade.valueOf(step.getString("grade").uppercase())
                state = state.grade(grade, now)
                assertEquals(step.getInt("step"), state.step, "$name, answer ${index + 1}: step")
                assertEquals(step.getLong("delayMs"), state.dueAt - now, "$name, answer ${index + 1}: delay")
                assertEquals(grade == ReviewGrade.KNOWN, state.answers.last().known, "$name, answer ${index + 1}: recorded")
            }
            assertEquals(case.getBoolean("paused"), state.paused, "$name: paused")
            assertEquals(case.getInt("finalAnswerCount"), state.answers.size, "$name: history size")
        }
    }

    @Test
    fun sentencePracticeMatchesFixtures() {
        fixture("sentence_practice.json").getJSONArray("cases").objects().forEach { case ->
            val name = case.getString("name")
            val made = SentencePractice.make(case.getString("source"), case.getString("answer"))
            val expected = case.optJSONObject("expected")
            if (expected == null) {
                assertNull(made, name)
            } else {
                assertEquals(
                    SentencePractice(expected.getString("answer"), expected.getString("prompt")),
                    made,
                    name
                )
            }
        }
    }

    @Test
    fun backupPruningMatchesFixtures() {
        val root = fixture("backup_prune.json")
        val now = root.getLong("nowMs")
        root.getJSONArray("cases").objects().forEach { case ->
            val name = case.getString("name")
            val backups = case.getJSONArray("backups").objects().map {
                LibraryBackupArchiveInfo(
                    name = it.getString("name"),
                    createdAtEpochMillis = it.getLong("createdAtMs"),
                    sizeBytes = it.getLong("sizeBytes")
                )
            }
            val plan = LibraryBackupPruner.plan(
                backups,
                LibraryBackupPolicy(
                    retentionDays = case.getInt("retentionDays"),
                    maximumBackupCount = case.getInt("maximumBackupCount"),
                    maximumTotalBytes = case.getLong("maximumTotalBytes")
                ),
                now
            )
            assertEquals(case.getJSONArray("removed").strings(), plan.removed.map { it.name }, "$name: removed")
            assertEquals(case.getJSONArray("remaining").strings(), plan.remaining.map { it.name }, "$name: remaining")
            assertEquals(case.getBoolean("isOverLimit"), plan.isOverLimit, "$name: over limit")
        }
    }

    @Test
    fun readingCompanionMatchesFixtures() {
        val root = fixture("reading_companion.json")

        root.getJSONArray("reviewSummary").objects().forEach { case ->
            val name = case.getString("name")
            assertEquals(
                ReadingReviewSummary(
                    failedAnswerCount = case.getInt("failedAnswerCount"),
                    lapseCount = case.getInt("lapseCount"),
                    successStreak = case.getInt("successStreak"),
                    hasUnresolvedFailure = case.getBoolean("hasUnresolvedFailure")
                ),
                ReadingReviewSummary.of(case.getJSONArray("known").booleans()),
                name
            )
        }

        root.getJSONArray("confusionMap").objects().forEach { case ->
            val name = case.getString("name")
            val map = ReadingConfusionMapPolicy.build(
                signals = case.getJSONArray("signals").objects().map(::signal),
                limit = case.getInt("limit"),
                bookId = case.nullableString("bookId")
            )
            val expected = case.getJSONArray("items").objects()
            assertEquals(expected.map { it.getString("lookupKey") }, map.items.map { it.lookupKey }, "$name: order")
            expected.zip(map.items).forEach { (want, got) ->
                val label = "$name, ${got.lookupKey}"
                assertEquals(kind(want.getString("kind")), got.kind, "$label: kind")
                assertEquals(want.getInt("score"), got.score, "$label: score")
                assertEquals(want.getString("reason"), got.reason, "$label: reason")
            }
            assertEquals(case.nullableString("topKind")?.let(::kind), map.topKind, "$name: top kind")
            assertEquals(case.getString("summary"), map.summary, "$name: summary")
        }

        root.getJSONArray("assist").objects().forEach { case ->
            val name = case.getString("name")
            val assist = AdaptiveReadingAssistPolicy.resolve(
                allSignals = case.getJSONArray("signals").objects().map(::signal),
                mode = ReadingCompanionMode.fromStorageId(case.getString("mode")),
                bookId = case.nullableString("bookId")
            )
            assertEquals(level(case.getString("level")), assist.level, "$name: level")
            assertEquals(case.getJSONArray("focusTerms").strings(), assist.focusTerms, "$name: focus")
            assertEquals(case.getString("instruction"), assist.instruction, "$name: instruction")
            assertEquals(case.getString("reason"), assist.reason, "$name: reason")
        }

        root.getJSONArray("closure").objects().forEach { case ->
            val name = case.getString("name")
            val plan = ChapterClosurePolicy.build(
                mode = ReadingCompanionMode.fromStorageId(case.getString("mode")),
                chapterTitle = case.getString("chapterTitle"),
                confusionItems = case.getJSONArray("items").objects().map {
                    confusionItem(it.getString("lookupKey"), it.getString("displayTerm"), it.getString("sentenceContext"))
                }
            )
            assertEquals(case.getString("chapterTitle"), plan.chapterTitle, "$name: chapter")
            assertEquals(90, plan.estimatedSeconds, "$name: seconds")
            val expected = case.getJSONArray("prompts").objects()
            assertEquals(expected.size, plan.prompts.size, "$name: prompt count")
            expected.zip(plan.prompts).forEachIndexed { index, (want, got) ->
                assertEquals(want.getString("title"), got.title, "$name, prompt ${index + 1}: title")
                assertEquals(want.getString("prompt"), got.prompt, "$name, prompt ${index + 1}: text")
                assertEquals(want.nullableString("searchTerm"), got.searchTerm, "$name, prompt ${index + 1}: search")
            }
        }

        root.getJSONArray("recap").objects().forEach { case ->
            val name = case.getString("name")
            val recap = ContinuationRecapPolicy.build(
                mode = ReadingCompanionMode.fromStorageId(case.getString("mode")),
                bookTitle = case.getString("bookTitle"),
                chapterTitle = case.getString("chapterTitle"),
                confusionItems = case.getJSONArray("terms").strings().map { confusionItem("k|$it", it, "") }
            )
            assertEquals(case.getString("title"), recap.title, "$name: title")
            assertEquals(case.getJSONArray("bullets").strings(), recap.bullets, "$name: bullets")
        }

        root.getJSONArray("lensPrompt").objects().forEach { case ->
            val name = case.getString("name")
            val options = ReadingLensOptions(
                mode = AIReadingLensMode.entries.single { it.storageId == case.getString("mode") },
                companionMode = ReadingCompanionMode.fromStorageId(case.getString("companionMode")),
                assistLevel = level(case.getString("assistLevel")),
                adaptiveInstruction = case.nullableString("adaptiveInstruction"),
                focusTerms = case.getJSONArray("focusTerms").strings()
            )
            assertEquals(
                case.getString("expected"),
                AIReadingLensPromptPolicy.rendered(
                    options = options,
                    sourceLanguage = case.nullableString("sourceLanguage")?.let(::language),
                    responseLanguage = language(case.getString("responseLanguage")),
                    grammarPrompt = case.nullableString("grammarPrompt")
                ),
                name
            )
        }
    }

    private fun signal(json: JSONObject) = ReadingErrorSignal(
        lookupKey = json.getString("lookupKey"),
        displayTerm = json.getString("displayTerm"),
        lemma = json.nullableString("lemma"),
        sentenceContext = json.getString("sentenceContext"),
        sourceBookId = json.getString("sourceBookId"),
        sourceBookTitle = json.getString("sourceBookTitle"),
        lookupCount = json.getInt("lookupCount"),
        review = ReadingReviewSummary.of(json.getJSONArray("known").booleans())
    )

    private fun confusionItem(key: String, term: String, sentence: String) = ReadingConfusionItem(
        lookupKey = key,
        displayTerm = term,
        kind = ReadingConfusionKind.WORD_MEANING,
        score = 1,
        reason = "",
        sentenceContext = sentence,
        sourceBookId = "b1",
        sourceBookTitle = "书一"
    )

    private fun kind(id: String) = ReadingConfusionKind.entries.single { it.storageId == id }

    private fun level(id: String) = ReadingAssistLevel.entries.single { it.storageId == id }

    private fun language(tag: String) = requireNotNull(LanguageCode.fromTag(tag)) { "unknown language $tag" }

    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)

    private fun JSONArray.booleans(): List<Boolean> = List(length()) { getBoolean(it) }

    private fun fixture(name: String): JSONObject {
        // Gradle runs host tests from the module directory, but walk up so
        // the test also finds the files when run from the repository root.
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, "parity/$name"), File(dir, "shared/parity/$name"))) {
                if (candidate.isFile) return JSONObject(candidate.readText())
            }
            dir = dir.parentFile
        }
        error("parity fixture $name not found above ${File("").absolutePath}")
    }

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
}
