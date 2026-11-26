package com.jerreader.android.reader

import android.graphics.PointF
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.LexicalLookupFailure
import com.jerreader.shared.lexical.LexicalLookupService
import com.jerreader.shared.lexical.WordAnalysis
import com.jerreader.shared.lexical.WordAnalysisRequest
import com.jerreader.shared.lexical.WordMorphologyAnalyzer
import com.jerreader.shared.lexical.WordSelectionSource
import com.jerreader.shared.ui.WordLookupCardState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.r2.navigator.Selection
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.BaseActionModeCallback
import org.readium.r2.shared.ExperimentalReadiumApi

@OptIn(ExperimentalReadiumApi::class)
class ReaderWordInteractionController(
    private val scope: CoroutineScope,
    private val lookupService: LexicalLookupService,
    private val languageHint: () -> LanguageCode?,
    private val onStateChanged: (WordLookupCardState) -> Unit,
    private val shortTapEnabled: () -> Boolean = { true },
    private val onSelectionInvalidated: (() -> Unit)? = null,
    /** A short tap that landed on no word, so the reader can show or hide its bars. */
    private val onBlankTap: (() -> Unit)? = null
) {
    private val analyzer = WordMorphologyAnalyzer(AndroidWordBoundaryTokenizer())
    private var navigator: EpubNavigatorFragment? = null
    private var lookupJob: Job? = null
    private var lastAnalysis: WordAnalysis? = null
    private var inputListenerRegistered = false
    private var requestToken = 0L

    val selectionActionModeCallback: ActionMode.Callback = object : BaseActionModeCallback() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, LOOKUP_MENU_ITEM_ID, Menu.NONE, "查词")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != LOOKUP_MENU_ITEM_ID) return false
            mode.finish()
            invalidateForNewLookup()
            lookupJob?.cancel()
            lookupJob = scope.launch { lookupCurrentSelection() }
            return true
        }
    }

    private val inputListener = object : InputListener {
        override fun onTap(event: TapEvent): Boolean {
            if (!shortTapEnabled()) return false
            invalidateForNewLookup()
            lookupJob?.cancel()
            lookupJob = scope.launch {
                lookupAt(event.point, pointIsReadiumPixels = true, reportsBlankTap = true)
            }
            // Readium filters links and other interactive elements before this
            // callback. Consuming a plain tap gives it one meaning: word lookup.
            return true
        }
    }

    fun attach(navigator: EpubNavigatorFragment, registerShortTap: Boolean = true) {
        detach()
        this.navigator = navigator
        inputListenerRegistered = registerShortTap
        if (registerShortTap) navigator.addInputListener(inputListener)
    }

    fun detach() {
        if (inputListenerRegistered) navigator?.removeInputListener(inputListener)
        inputListenerRegistered = false
        navigator = null
        lookupJob?.cancel()
        lookupJob = null
        requestToken++
        lastAnalysis = null
    }

    fun clearSelection() {
        lookupJob?.cancel()
        lookupJob = null
        navigator?.clearSelection()
        requestToken++
        lastAnalysis = null
    }

    /** Invalidates only the transient card before a new lookup attempt. */
    private fun invalidateForNewLookup() {
        val hadAnalysis = lastAnalysis != null
        lookupJob?.cancel()
        lookupJob = null
        requestToken++
        lastAnalysis = null
        if (hadAnalysis) onSelectionInvalidated?.invoke()
    }

    fun retry() {
        val analysis = lastAnalysis ?: return
        startLookup(analysis)
    }

    fun lookupSelectionFromMenu() {
        invalidateForNewLookup()
        lookupJob?.cancel()
        lookupJob = scope.launch { lookupCurrentSelection() }
    }

    /**
     * `pointIsReadiumPixels` is true for a real tap: Readium reports it in
     * navigator pixels (CSS pixels times this WebView's devicePixelRatio).
     * Tests pass CSS pixels directly.
     */
    internal suspend fun lookupAt(
        point: PointF,
        pointIsReadiumPixels: Boolean = false,
        reportsBlankTap: Boolean = false
    ): WordLookupCardState? {
        val navigator = navigator ?: return null
        val selected = runShortTapScript(navigator, point, pointIsReadiumPixels)?.let(::decodeSelectedWord)
        if (selected == null) {
            navigator.clearSelection()
            // As on iOS, a tap on no word still shows or hides the bars.
            if (reportsBlankTap) onBlankTap?.invoke()
            return null
        }
        // The DOM selection and Readium's native Selection flow do not update
        // atomically, so currentSelection() could still be null or the
        // previous word here. The script that hit-tested the glyph already
        // knows the exact word and its neighbours; that payload is the input.
        val analysis = analyzer.analyze(
            WordAnalysisRequest(
                text = selected.highlight,
                languageHint = languageHint(),
                source = WordSelectionSource.SHORT_TAP
            )
        )?.copy(sentenceContext = selected.context) ?: return null
        return lookupNow(analysis)
    }

    private fun decodeSelectedWord(raw: String): SelectedWord? {
        if (raw == "null" || raw == "false") return null
        val decoded = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        val json = when (decoded) {
            is JSONObject -> decoded
            // evaluateJavascript hands back a JSON.stringify result as a quoted string.
            is String -> runCatching { JSONObject(decoded) }.getOrNull()
            else -> null
        } ?: return null
        val highlight = json.optString("highlight").trim()
        if (highlight.isEmpty()) return null
        val context = buildString {
            append(json.optString("before"))
            append(highlight)
            append(json.optString("after"))
        }.trim().takeIf(String::isNotBlank)
        return SelectedWord(highlight = highlight, context = context)
    }

    internal fun analyzeSelectedTextForTesting(
        text: String,
        language: LanguageCode? = null
    ): WordAnalysis? = analyzer.analyze(
        WordAnalysisRequest(
            text = text,
            languageHint = language,
            source = WordSelectionSource.NATIVE_SELECTION
        )
    )

    /** The word a short tap at [point] (CSS pixels) selects, without the dictionary. */
    internal suspend fun shortTapWordForTesting(point: PointF): String? {
        val navigator = navigator ?: return null
        return runShortTapScript(navigator, point, pointIsReadiumPixels = false)
            ?.let(::decodeSelectedWord)
            ?.highlight
    }

    private suspend fun lookupCurrentSelection(): WordLookupCardState? {
        val navigator = navigator ?: return null
        val selection = navigator.currentSelection() ?: return null
        // Readium's text carries the <rt> readings of a ruby selection, and a
        // long press on a reading selects only the reading: look up the base
        // text, with the rest of its paragraph as the sentence, as iOS does.
        val page = ReaderRubySelectionText.read { script -> navigator.evaluateJavascript(script) }
        val analysis = analyzeSelection(selection, WordSelectionSource.NATIVE_SELECTION, page) ?: return null
        return lookupNow(analysis)
    }

    private fun analyzeSelection(
        selection: Selection,
        source: WordSelectionSource,
        page: ReaderRubySelectionText.Page? = null
    ): WordAnalysis? {
        val text = selection.locator.text
        val highlight = page?.text ?: text.highlight ?: return null
        // Readium's text around the selection has the readings and other
        // paragraphs in it; it is used only when the page could not be read.
        val before = if (page != null) page.before else text.before
        val after = if (page != null) page.after else text.after
        val context = buildString {
            append(before?.takeLast(CONTEXT_SIDE_LIMIT).orEmpty())
            append(highlight)
            append(after?.take(CONTEXT_SIDE_LIMIT).orEmpty())
        }.trim().takeIf(String::isNotBlank)
        return analyzer.analyze(
            WordAnalysisRequest(
                text = highlight,
                languageHint = languageHint(),
                source = source
            )
        )?.copy(sentenceContext = context)
    }

    private fun startLookup(analysis: WordAnalysis) {
        lookupJob?.cancel()
        lookupJob = scope.launch { lookupNow(analysis) }
    }

    private suspend fun lookupNow(analysis: WordAnalysis): WordLookupCardState {
        val token = ++requestToken
        lastAnalysis = analysis
        val loading = WordLookupCardState.Loading(analysis)
        if (token == requestToken) onStateChanged(loading)
        return try {
            val result = withContext(Dispatchers.IO) {
                lookupService.lookup(
                    word = analysis.surfaceForm,
                    sentenceContext = analysis.sentenceContext,
                    language = analysis.language,
                    candidates = buildList {
                        analysis.lemma?.let(::add)
                        addAll(analysis.lemmaCandidates)
                    }.distinct()
                )
            }
            WordLookupCardState.Success(analysis, result).also {
                if (token == requestToken) onStateChanged(it)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: LexicalLookupFailure) {
            WordLookupCardState.Failure(
                analysis = analysis,
                message = error.message ?: "查词失败。"
            ).also {
                if (token == requestToken) onStateChanged(it)
            }
        } catch (_: Exception) {
            WordLookupCardState.Failure(
                analysis = analysis,
                message = "词典服务暂时不可用，请稍后重试。"
            ).also {
                if (token == requestToken) onStateChanged(it)
            }
        }
    }

    /**
     * Runs the short-tap script at [point] and returns its JSON. A tap on
     * Japanese takes two calls: the first only answers with the tapped text
     * node's text, which the platform's ICU segments (see
     * [JapaneseWordBreaks]), and the second selects the word with those
     * breaks. Without them the script falls back to WebView's segmenter.
     */
    private suspend fun runShortTapScript(
        navigator: EpubNavigatorFragment,
        point: PointF,
        pointIsReadiumPixels: Boolean
    ): String? {
        val first = navigator.evaluateJavascript(
            shortTapSelectionScript(point, pointIsReadiumPixels, askForBreaks = true)
        ) ?: return null
        val text = breaksRequest(first) ?: return first
        val breaks = withContext(Dispatchers.Default) { JapaneseWordBreaks.json(text) }
        return navigator.evaluateJavascript(shortTapSelectionScript(point, pointIsReadiumPixels, breaks = breaks))
    }

    /** The text whose breaks the first call asks for, or null for any other answer. */
    private fun breaksRequest(raw: String): String? {
        if (!raw.contains("breaksFor")) return null
        val json = when (val decoded = runCatching { JSONTokener(raw).nextValue() }.getOrNull()) {
            is JSONObject -> decoded
            is String -> runCatching { JSONObject(decoded) }.getOrNull()
            else -> null
        } ?: return null
        return if (json.has("breaksFor")) json.optString("breaksFor") else null
    }

    private fun shortTapSelectionScript(
        point: PointF,
        pointIsReadiumPixels: Boolean,
        breaks: String? = null,
        askForBreaks: Boolean = false
    ): String {
        val scale = if (pointIsReadiumPixels) {
            "Math.max(Number(window.devicePixelRatio) || 1, 0.0001)"
        } else {
            "1"
        }
        val breaksLiteral = breaks ?: "null"
        val askLiteral = if (askForBreaks) "true" else "false"
        return """
        (() => {
          // Undo Readium's DPR scaling in the document that produced the
          // point; the Android display density can differ on scaled WebViews.
          const pointScale = $scale;
          const x = ${point.x} / pointScale;
          const y = ${point.y} / pointScale;
          let range = null;
          if (document.caretRangeFromPoint) {
            range = document.caretRangeFromPoint(x, y);
          } else if (document.caretPositionFromPoint) {
            const position = document.caretPositionFromPoint(x, y);
            if (position) {
              range = document.createRange();
              range.setStart(position.offsetNode, position.offset);
              range.collapse(true);
            }
          }
          if (!range || !range.startContainer || range.startContainer.nodeType !== Node.TEXT_NODE) {
            return null;
          }
          const node = range.startContainer;
          const parent = node.parentElement;
          if (!parent || parent.closest('a,button,input,textarea,select,option,video,audio,rt,rp')) {
            return null;
          }
          if (!node.data || !node.data.length) return null;
          // The tap has to land on a glyph, not just near one: caretRangeFromPoint
          // always answers, even for a margin or the blank end of a chapter.
          const hitOffset = Math.max(0, Math.min(range.startOffset, node.data.length - 1));
          const hitProbe = document.createRange();
          hitProbe.setStart(node, hitOffset);
          hitProbe.setEnd(node, Math.min(hitOffset + 1, node.data.length));
          let hitDistance = Number.POSITIVE_INFINITY;
          for (const rect of hitProbe.getClientRects()) {
            if (rect.width <= 0.5 && rect.height <= 0.5) continue;
            const dx = Math.max(rect.left - x, 0, x - rect.right);
            const dy = Math.max(rect.top - y, 0, y - rect.bottom);
            hitDistance = Math.min(hitDistance, dx * dx + dy * dy);
          }
          if (!(hitDistance <= ${TAP_PROXIMITY_PX * TAP_PROXIMITY_PX})) return null;
          const selection = window.getSelection();
          if (!selection || typeof selection.modify !== 'function') return null;
          // The word is the one holding the glyph under the finger, not the
          // caret: a tap on the first half of a word's first glyph puts the
          // caret on the boundary before it, where the previous word ended.
          const writingStyle = getComputedStyle(parent);
          const verticalTap = /^(vertical|sideways|tb)/i.test(
            (writingStyle.writingMode || writingStyle.webkitWritingMode || '').toString()
          );
          const glyphHoldsTap = (offset) => {
            if (offset < 0 || offset >= node.data.length) return false;
            const glyph = document.createRange();
            glyph.setStart(node, offset);
            glyph.setEnd(node, offset + 1);
            return Array.from(glyph.getClientRects()).some((rect) => verticalTap
              ? y >= rect.top - 0.5 && y <= rect.bottom + 0.5
              : x >= rect.left - 0.5 && x <= rect.right + 0.5);
          };
          const tapOffset = glyphHoldsTap(range.startOffset)
            ? range.startOffset
            : glyphHoldsTap(range.startOffset - 1)
              ? range.startOffset - 1
              : Math.max(0, Math.min(range.startOffset, node.data.length - 1));
          const probe = node.data.charAt(tapOffset);
          const japaneseTap = /[\u3040-\u30FF\u3400-\u9FFF]/.test(probe);
          // Chromium's Android ICU has no Chinese/Japanese dictionary: its
          // Intl.Segmenter('ja') only chains runs of kanji and of kana, so a
          // particle runs on into the word after it (\u5BB6|\u306B\u3044\u308B, \u6642\u9593|\u304C\u306A\u3044).
          // Japanese is segmented by the platform's ICU instead, which has the
          // dictionary (JapaneseWordBreaks): a first call asks for this text
          // node's breaks and changes nothing, a second one brings them.
          const platformBreaks = $breaksLiteral;
          if (japaneseTap && platformBreaks === null && $askLiteral) {
            return JSON.stringify({breaksFor: node.data});
          }
          // The word a tap on segment `tapped` means, by the same rules as iOS
          // ReaderWordSegmenter.japaneseWordBounds, so a tap selects the same
          // word on both. WebView ICU splits a word finely (食|べ|ま|した), so a
          // word is built from segments: an honorific prefix takes the word
          // after it, a kanji the okurigana after it, and a stem the endings
          // that inflect it. Whichever glyph of a word is tapped, the word is
          // the same.
          const japaneseWordBounds = (text, segments, tapped) => {
            // The same rules as ReaderWordSegmenter.japaneseWordBounds on iOS,
            // so a tap selects the same word on both. Tokenizers split a word
            // differently (NLTokenizer gives 食べ|まし|た, ICU 食|べ|ま|した), so
            // a word is built from segments: an honorific prefix takes the word
            // after it, a kanji the okurigana after it, and a stem the endings
            // that inflect it. Whichever glyph of a word is tapped, the word is
            // the same.
            const prefixes = new Set(['お', 'ご', '御']);
            // Particles: words of their own, never okurigana and never
            // inflected (彼|は, 扉|を, 時間|が|ない, 東京|では).
            const particles = new Set(['は', 'を', 'へ', 'の', 'も', 'が', 'に', 'で', 'と', 'から', 'まで', 'より', 'ので', 'のに',
              'けど', 'けれど', 'けれども', 'しか', 'だけ', 'ほど', 'など', 'ばかり', 'くらい', 'ぐらい', 'こそ', 'さえ', 'かも', 'では', 'には', 'とは',
              'でも', 'にも', 'とも', 'へは', 'へも', 'からは', 'までは', 'のは', 'ながら', 'なのに', 'なので', 'として', 'について', 'にとって', 'によって',
              'において', 'という', 'とか', 'なら']);
            // The copula after a noun (雨|でし|た, 夜|だ).
            const copulas = new Set(['だ', 'だっ', 'だろ', 'だろう', 'です', 'でし', 'でしょ', 'でしょう']);
            // A particle after a noun, okurigana after a verb stem (何|か|ある,
            // 行|か|ない).
            const ambiguous = new Set(['か', 'や']);
            // Negatives that also follow a noun (問題|ない); they join only a
            // one-glyph stem (見|ない).
            const nounNegatives = new Set(['ない', 'なく', 'なかっ', 'なけれ']);
            const sentenceFinals = new Set(['よ', 'ね', 'ぞ', 'ぜ', 'わ', 'さ']);
            // One-glyph pronouns, never a verb stem (彼|って): only plural and
            // name suffixes join them, and か joins 何 and 誰.
            const pronouns = new Set(['彼', '私', '僕', '俺', '君', '我', '誰', '何', '皆', '己']);
            const pronounSuffixes = new Set(['ら', 'たち', 'ども', 'さん', 'さま']);
            // After a noun of several kanji only a する verb and name or plural
            // suffixes join (勉強|して, 学生|たち).
            const suruStarts = ['し', 'す', 'さ', 'せ', 'でき'];
            const nounSuffixes = new Set(['さん', 'くん', 'ちゃん', 'さま', 'たち', 'ども']);
            // Before a negative or a voice ending, わ, さ and から are okurigana
            // (思|わ|ない, 報道|さ|れ|た, 分|から|ない).
            const negativesAfterStem = ['ない', 'なかっ', 'なかった', 'なく', 'なくて', 'なけれ', 'ないで', 'ず', 'ずに'];
            const voicesAfterStem = ['せ', 'せる', 'せた', 'せて', 'せない', 'せられ', 'せられる', 'れ', 'れる', 'れた', 'れて', 'れない', 'れます',
              'れました'];
            // After に these end a verb (死|に|たい).
            const endingsAfterNi = new Set(['たい', 'たく', 'たかっ', 'たかった', 'たくて', 'たくない', 'たくなかった', 'ます', 'まし', 'ました',
              'ません', 'ませ', 'ましょう', 'ましょ']);
            // Kanji of ぐ verbs (泳|が|ない, 脱い|で).
            const guKanji = new Set(['泳', '急', '騒', '脱', '防', '注', '継', '稼', '嗅', '仰', '剥', '繋']);
            // Before these, な belongs to the word (死|な|ない); before anything
            // else it is the copula (静か|な|部屋).
            const naContinuations = new Set(['い', 'ない', 'なかっ', 'なか', 'なく', 'なけれ', 'かっ', 'く', 'くて', 'けれ']);
            // A stem ending like a dictionary form takes no single kana as
            // okurigana (待つ|べ, 優しい|し).
            const dictionaryEndings = new Set(['い', 'う', 'く', 'ぐ', 'す', 'ず', 'つ', 'づ', 'ぬ', 'ふ', 'ぶ', 'ぷ', 'む', 'ゆ',
              'る']);
            // Single kana that still go on a stem ending in て or で (捨て|る).
            const kanaAfterTe = new Set(['る', 'れ', 'ろ']);
            // Auxiliary verbs after a te-form (忘れて|しまった) and verbs often
            // written in kana, matched over kana segments (し|まっ|た).
            const auxiliaryVerbs = ['しまう', 'しまっ', 'しまい', 'しまわ', 'しまえ', 'しまおう', 'くれる', 'くれ', 'もらう', 'もらっ', 'もらい',
              'もらわ', 'もらえ', 'もらおう', 'あげる', 'あげ', 'おく', 'おい', 'おか', 'おき', 'おけ', 'おこう', 'いく', 'いっ', 'いか', 'いき', 'いけ',
              'いこう', 'くる', 'き', 'こ', 'こい', 'こよう', 'みる', 'み', 'みよう', 'みろ', 'ください', 'くださ', 'くださっ', 'くださる', 'ほしい', 'ほしく',
              'ほしかっ', 'いただく', 'いただい', 'いただき', 'いただけ', 'いただか', 'やる', 'やっ', 'やり', 'やら', 'やろう', 'ある', 'あっ', 'あり', 'あれ',
              'おる', 'おり', 'おっ'];
            // Endings that inflect a stem, longest first, each with the stem it
            // takes. One is joined only when it ends where a segment ends.
            const endings = [['いませんでした', 'te'], ['ませんでした', 'any'], ['たくなかった', 'kana'], ['ていました', 'any'],
              ['でいました', 'n'], ['させられる', 'any'], ['いましょう', 'te'], ['ています', 'any'], ['なかった', 'kana'], ['たかった', 'kana'],
              ['でいます', 'n'], ['いました', 'te'], ['いません', 'te'], ['ましょう', 'any'], ['させられ', 'any'], ['せられる', 'kana'],
              ['なければ', 'kana'], ['たくない', 'kana'], ['んだら', 'any'], ['ません', 'any'], ['ました', 'any'], ['すぎる', 'kana'],
              ['すぎた', 'kana'], ['すぎて', 'kana'], ['やすい', 'kana'], ['やすく', 'kana'], ['にくい', 'kana'], ['にくく', 'kana'],
              ['づらい', 'kana'], ['えて', 'kana'], ['えた', 'kana'], ['える', 'kana'], ['ている', 'any'], ['ていた', 'any'],
              ['かった', 'any'], ['でいる', 'n'], ['でいた', 'n'], ['います', 'te'], ['いない', 'te'], ['させる', 'any'],
              ['される', 'any'], ['せられ', 'kana'], ['られる', 'kana'], ['ったら', 'any'], ['なくて', 'kana'], ['たくて', 'kana'],
              ['なさい', 'kana'], ['すぎ', 'kana'], ['ます', 'any'], ['った', 'any'], ['って', 'kana'], ['いた', 'any'],
              ['いだ', 'any'], ['いで', 'gu'], ['んだ', 'any'], ['した', 'any'], ['ない', 'kana'], ['たい', 'kana'], ['いる', 'te'],
              ['させ', 'any'], ['され', 'any'], ['られ', 'kana'], ['せる', 'kana'], ['れる', 'kana'], ['たら', 'kana'],
              ['だら', 'n'], ['たり', 'kana'], ['だり', 'n'], ['しょう', 'ma'], ['ろう', 'kana'], ['んで', 'kana'], ['なく', 'kana'],
              ['たく', 'kana'], ['た', 'kana'], ['て', 'kana'], ['で', 'n'], ['だ', 'n'], ['で', 'guI'], ['だ', 'guI'],
              ['せ', 'kana'], ['れ', 'kana'], ['ず', 'kana'], ['ば', 'kana']];
            const isKana = (s) => /^[\u3040-\u309F]+$/.test(s);
            const isKanji = (c) => /^[\u3400-\u9FFF]$/.test(c);
            const allKanji = (s) => /^[\u3400-\u9FFF]+$/.test(s);
            const hasKanji = (s) => /[\u3400-\u9FFF]/.test(s);
            const startsWithKanji = (s) => /^[\u3400-\u9FFF]/.test(s);
            const startsWithHiragana = (s) => /^[\u3040-\u309F]/.test(s);
            const isJapanese = (s) => /[\u3040-\u30FF\u3400-\u9FFF]/.test(s);
            // ん after a kanji, or after a kanji and one kana (楽しん, 浮かん).
            const isNStem = (s) => /[\u3400-\u9FFF][しからず]?ん$/.test(s);
            // A ぐ verb's kanji and い (急い).
            const isGuIStem = (s) => s.length >= 2 && s.charAt(s.length - 1) === 'い' &&
              guKanji.has(s.charAt(s.length - 2));
            // A one-glyph kanji stem that is not a pronoun (見|ない).
            const isVerbKanji = (s) => isKanji(s) && !pronouns.has(s);
            const word = (i) => segments[i].segment;
            const endOf = (i) => segments[i].index + segments[i].segment.length;
            const touching = (i, j) => i >= 0 && j < segments.length && endOf(i) === segments[j].index;
            const segmentEnds = new Set(segments.map((s) => s.index + s.segment.length));
            const startingAt = (position) => segments.findIndex((s) => s.index === position);
            const nextWord = (i) => (touching(i, i + 1) ? word(i + 1) : '');
            const textBefore = (i) => text.slice(Math.max(0, segments[i].index - 3), segments[i].index);
            const followsNStem = (i) => isNStem(textBefore(i));
            const followsGuStem = (i) => isGuIStem(textBefore(i));
            // で before し or しょ unless a stem in ん comes before it (雨|で|した,
            // 読ん|で|しまう), な before anything but the rest of a word, and だ,
            // です and じゃ forms (本当|じゃない).
            const isCopula = (i) => {
              const w = word(i);
              if (copulas.has(w)) return true;
              const next = nextWord(i);
              if (w === 'で') return ['し', 'した', 'しょ', 'しょう'].includes(next) && !followsNStem(i);
              if (w === 'な') return !naContinuations.has(next);
              if (particles.has(w)) return false;
              if (w.startsWith('です') || w.startsWith('でしょ') || w.startsWith('じゃ')) return true;
              return w.startsWith('だ') && w.length >= 2 && w !== 'だら' && w !== 'だり';
            };
            // The ending may span segments (合|わ|なか|っ|た) but must end on one.
            const isOkuriganaBeforeEnding = (i) => {
              if (!['わ', 'さ', 'から'].includes(word(i))) return false;
              const after = endOf(i);
              return negativesAfterStem.concat(voicesAfterStem).some((form) =>
                text.startsWith(form, after) && segmentEnds.has(after + form.length));
            };
            const isFinal = (i) => sentenceFinals.has(word(i)) && !isOkuriganaBeforeEnding(i);
            // Kana that read as a particle but here are okurigana: no word
            // starts with っ (上|が|っ|た), a verb ending after が (下|が|ら|ない,
            // 泳|が|ない), a polite or desire ending after に (死|に|たい), the
            // te-form after ん or a ぐ verb's い (読ん|で, 脱い|で), もらう
            // (も|ら|っ|た), a negative after から (分|から|ない) and ならない.
            const isParticle = (i) => {
              const w = word(i);
              if (!particles.has(w) || isCopula(i)) return false;
              const next = nextWord(i);
              if (next.startsWith('っ')) return false;
              switch (w) {
                case 'が':
                  if (['ら', 'り', 'る', 'れ', 'ろ'].includes(next)) return false;
                  if (segments[i].index > 0 && guKanji.has(text.charAt(segments[i].index - 1)) &&
                      ['な', 'せ', 'れ', 'ず', 'ぬ'].some((k) => next.startsWith(k))) return false;
                  break;
                case 'に': if (endingsAfterNi.has(next)) return false; break;
                case 'で': if (followsNStem(i) || followsGuStem(i)) return false; break;
                case 'も': if (next === 'ら') return false; break;
                case 'から': if (isOkuriganaBeforeEnding(i)) return false; break;
                case 'なら': if (negativesAfterStem.includes(next)) return false; break;
                default: break;
              }
              return true;
            };
            // Where the word whose head is segment i starts: at an honorific
            // prefix before it (お|茶, ご|主人).
            const wordStart = (i) => (touching(i - 1, i) && prefixes.has(word(i - 1)) && startsWithKanji(word(i))
              ? segments[i - 1].index : segments[i].index);
            const joins = (kind, stem) => {
              if (!stem) return false;
              const last = stem.charAt(stem.length - 1);
              switch (kind) {
                case 'kana': return /^[\u3040-\u309F]$/.test(last) || isVerbKanji(stem);
                case 'n': return isNStem(stem);
                case 'te': return /[てで]$/.test(stem);
                case 'ma': return last === 'ま' && hasKanji(stem);
                case 'gu': return guKanji.has(last);
                case 'guI': return isGuIStem(stem);
                default: return true;
              }
            };
            // Where the word that starts at `start` and so far ends at `upper`
            // ends, with its okurigana and endings. A particle takes none
            // (それ|は|ない).
            const wordEnd = (start, upperBound, head) => {
              if (isParticle(head)) return upperBound;
              let upper = upperBound;
              let ambiguousOkurigana = false;
              for (let steps = 0; steps < 8; steps++) {
                const stem = text.slice(start, upper);
                const ending = endings.find(([form, kind]) => text.startsWith(form, upper) &&
                  segmentEnds.has(upper + form.length) && joins(kind, stem) &&
                  (!ambiguousOkurigana || kind === 'kana'));
                if (ending) {
                  upper += ending[0].length;
                  ambiguousOkurigana = false;
                  continue;
                }
                if (ambiguousOkurigana) break;
                const next = startingAt(upper);
                if (next < 0 || !stem) break;
                const kana = word(next);
                const single = kana.length === 1;
                const last = stem.charAt(stem.length - 1);
                if (!isKana(kana) || isParticle(next) || isCopula(next) || isFinal(next) || prefixes.has(kana) ||
                    (nounNegatives.has(kana) && !isVerbKanji(stem))) break;
                // A te-form ends the word (忘れて|しまう).
                if (/[てで]$/.test(stem) && !kanaAfterTe.has(kana)) break;
                if (pronouns.has(stem)) {
                  if (!(pronounSuffixes.has(kana) || (kana === 'か' && (stem === '何' || stem === '誰')))) break;
                  upper = endOf(next);
                  continue;
                }
                // ください is a word of its own, never okurigana (ご注意|ください).
                if (kana.startsWith('くださ')) break;
                const afterKanji = isKanji(last);
                if (afterKanji) {
                  if (!single && stem.length > 1 && allKanji(stem) && !suruStarts.some((s) => kana.startsWith(s)) &&
                      !nounSuffixes.has(kana)) break;
                } else {
                  // A single kana may also go on a stem that has a kanji
                  // (上が|っ) unless the stem ends like a dictionary form
                  // (待つ|べ); so may から before a negative (見つ|から|ない).
                  if (!hasKanji(stem)) break;
                  // お願い|します: an お〜する humble verb.
                  const humbleSuru = [...prefixes].some((p) => stem.startsWith(p)) &&
                    suruStarts.some((s) => kana.startsWith(s));
                  if (!humbleSuru) {
                    if (single) {
                      if (dictionaryEndings.has(last)) break;
                    } else if (!isOkuriganaBeforeEnding(next)) {
                      break;
                    }
                  }
                }
                if (ambiguous.has(kana)) {
                  if (!afterKanji || !touching(next, next + 1) || !startsWithHiragana(word(next + 1)) ||
                      isParticle(next + 1)) break;
                  ambiguousOkurigana = true;
                }
                upper = endOf(next);
              }
              return upper;
            };
            // The kana segments, after a te-form, a particle or nothing, that
            // make up the auxiliary verb the tapped segment is part of
            // (忘れて|し|まっ|た, お金を|も|ら|っ|た).
            const stops = (i) => {
              const w = word(i);
              return !isKana(w) || isParticle(i) || isCopula(i) || isFinal(i) || ambiguous.has(w);
            };
            const endsTe = (i) => /[てで]$/.test(word(i));
            const inflects = (rest, stem, depth) => depth < 3 && endings.some(([form, kind]) =>
              rest.startsWith(form) && joins(kind, stem) &&
              (rest.length === form.length || inflects(rest.slice(form.length), stem + form, depth + 1)));
            const isAuxiliaryVerb = (s) => auxiliaryVerbs.some((verb) => s.startsWith(verb) &&
              (s.length === verb.length || inflects(s.slice(verb.length), verb, 0)));
            const auxiliaryAt = (t) => {
              if (stops(t)) return null;
              let first = t;
              while (first > t - 4 && touching(first - 1, first) && !stops(first - 1) && !endsTe(first - 1)) first--;
              if (touching(first - 1, first) && !endsTe(first - 1) && !isParticle(first - 1)) return null;
              let joined = '';
              let best = -1;
              for (let end = first; end < segments.length && end < first + 5; end++) {
                if (end > first && (!touching(end - 1, end) || stops(end))) break;
                joined += word(end);
                if (isAuxiliaryVerb(joined)) best = end;
                if (endsTe(end)) break;
              }
              return best >= t ? [first, best] : null;
            };
            const tappedWord = word(tapped);
            if (!isJapanese(tappedWord)) return [segments[tapped].index, endOf(tapped)];
            if (prefixes.has(tappedWord) && touching(tapped, tapped + 1) && startsWithKanji(word(tapped + 1))) {
              // A tap on the お of お茶 means the word it starts.
              const start = segments[tapped].index;
              return [start, wordEnd(start, endOf(tapped + 1), tapped + 1)];
            }
            // A tap on kana may be on the ending of a word before it
            // (食|べ|ま|した): the word is the one, from as far back as it goes,
            // whose endings reach the tapped segment.
            let head = tapped;
            if (isKana(tappedWord) && !isParticle(tapped)) {
              for (let candidate = tapped - 1; candidate >= 0 && candidate >= tapped - 6; candidate--) {
                const w = word(candidate);
                if (!touching(candidate, candidate + 1) || !isKana(word(candidate + 1)) || !isJapanese(w) ||
                    isParticle(candidate) || prefixes.has(w)) break;
                if (wordEnd(wordStart(candidate), endOf(candidate), candidate) >= endOf(tapped)) head = candidate;
              }
            }
            // An auxiliary verb after a te-form is a word of its own, however
            // the tokenizer split it (忘れて|し|まっ|た).
            const auxiliary = auxiliaryAt(tapped);
            if (auxiliary && head >= auxiliary[0]) return [segments[auxiliary[0]].index, endOf(auxiliary[1])];
            // A tap on the 茶 of お茶 means the word the prefix starts.
            const start = wordStart(head);
            return [start, Math.max(wordEnd(start, endOf(head), head), endOf(tapped))];
          };
          // ICU, which segments text here, puts a boundary in the wrong place in
          // a few common phrases: はい|け|ない (は|いけ|ない), 待つ|べ|きだ
          // (べき|だ), ありま|すか (あり|ます|か), ご|ざ|いま|した (ござい|ま|した),
          // 勉強|した|いと (し|たい|と), くだ|さい (ください), も|しそう (もし|そう),
          // 悲|しそう (悲|し|そう), おい|し|そうですね (おいし|そう|です|ね),
          // ことに|した (こと|に|した), 帰|ったらしい (帰|った|らしい), 読|んじゃ
          // (読|ん|じゃ), よ|さ|そう (よさ|そう), すみません|で|した
          // (すみませんでした) and a compound verb (振り|返った). Moving those
          // boundaries gives the segments iOS's NLTokenizer gives, by the same
          // rules as ReaderWordSegmenter.repairedJapaneseTokens, which also joins
          // two set phrases NLTokenizer splits (ご|ちそう|さま, お|めでとう).
          const repairJapaneseSegments = (text, segments) => {
            const dictionaryEndings = new Set(['い', 'う', 'く', 'ぐ', 'す', 'ず', 'つ', 'づ', 'ぬ', 'ふ', 'ぶ', 'ぷ', 'む', 'ゆ',
              'る']);
            const masuStems = new Set(['い', 'き', 'ぎ', 'し', 'じ', 'ち', 'ぢ', 'に', 'ひ', 'び', 'ぴ', 'み', 'り', 'え', 'け',
              'げ', 'せ', 'ぜ', 'て', 'で', 'ね', 'へ', 'べ', 'ぺ', 'め', 'れ']);
            const masuTails = new Set(['す', 'すか', 'すね', 'すよ', 'すが', 'すけど', 'すから']);
            const particles = new Set(['は', 'を', 'へ', 'の', 'も', 'が', 'に', 'で', 'と', 'から', 'まで', 'より', 'ので', 'のに',
              'けど', 'けれど', 'けれども', 'しか', 'だけ', 'ほど', 'など', 'ばかり', 'くらい', 'ぐらい', 'こそ', 'さえ', 'かも', 'では', 'には', 'とは',
              'でも', 'にも', 'とも', 'へは', 'へも', 'からは', 'までは', 'のは', 'ながら', 'なのに', 'なので', 'として', 'について', 'にとって', 'によって',
              'において', 'という', 'とか', 'なら']);
            const isKana = (s) => /^[\u3040-\u309F]+$/.test(s);
            const isKanjiChar = (c) => /^[\u3400-\u9FFF]$/.test(c || '');
            const chars = (s) => Array.from(s);
            const word = (i) => (i >= 0 && i < segments.length ? segments[i].segment : '');
            // A kanji stem and one masu-stem kana (振り, 立ち, 食べ).
            const isMasuStem = (s) => {
              const g = chars(s);
              return g.length >= 2 && g.slice(0, -1).every(isKanjiChar) && masuStems.has(g[g.length - 1]);
            };
            // The first half of a compound (振り|返った, 読み|方): a masu stem, not
            // an adjective (高い), a noun and a particle (本当に, 全て) or an
            // adverb (少し).
            const isCompoundStem = (s) => isMasuStem(s) && !/[いにでてへ]$/.test(s) && s !== '少し' && s !== '同じ';
            // Kana an adjective ends in (早く, 高い), which no compound ends in.
            const adjectiveTails = new Set(['く', 'い', 'さ', 'しい', 'しく', 'かった', 'くて', 'くない']);
            const startsWithKanji = (s) => /^[\u3400-\u9FFF]/.test(s);
            // The second half of a compound (振り|返|っ|た, 読み|方): a kanji, not
            // an adjective (少し|早く) or the stem of a compound of its own
            // before another kanji (立ち|止まり|振り|返った).
            const isCompoundHalf = (s, next) => {
              if (!startsWithKanji(s)) return false;
              const m = /^[^\u3040-\u309F]*([\u3040-\u309F]+)$/.exec(s);
              if (m !== null && adjectiveTails.has(m[1])) return false;
              return !(isMasuStem(s) && startsWithKanji(next));
            };
            const touching = (a, b) => a >= 0 && b < segments.length &&
              segments[a].index + segments[a].segment.length === segments[b].index;
            // The offset `count` characters into segment i.
            const at = (i, count) => segments[i].index + chars(segments[i].segment).slice(0, count).join('').length;
            const endOf = (i) => segments[i].index + segments[i].segment.length;
            const repaired = [];
            // The pieces between consecutive bounds, leaving out empty ones.
            const append = (bounds) => {
              for (let k = 0; k + 1 < bounds.length; k++) {
                if (bounds[k] < bounds[k + 1]) {
                  repaired.push({segment: text.substring(bounds[k], bounds[k + 1]), index: bounds[k], isWordLike: true});
                }
              }
            };
            let i = 0;
            while (i < segments.length) {
              const current = word(i);
              const glyphs = chars(current);
              const next = touching(i, i + 1) ? word(i + 1) : '';
              const start = segments[i].index;
              const beforeLast = glyphs.length >= 2 ? glyphs[glyphs.length - 2] : '';
              if ((current === 'はい' || current === 'ばい') && next.startsWith('け')) {
                append([start, at(i, 1), endOf(i + 1)]);
                i += 2;
              } else if (next.startsWith('き') && (current === 'すべ' || (current === 'べ' && touching(i - 1, i) &&
                  dictionaryEndings.has(chars(word(i - 1)).slice(-1)[0])))) {
                append([start, at(i, glyphs.length - 1), at(i + 1, 1), endOf(i + 1)]);
                i += 2;
              } else if (current.endsWith('ま') && masuStems.has(beforeLast) &&
                  (masuTails.has(next) || (next.startsWith('しょう') && current !== 'いま'))) {
                append([start, at(i, glyphs.length - 1), at(i + 1, next.startsWith('す') ? 1 : 3), endOf(i + 1)]);
                i += 2;
              } else if (current === 'ご' && next === 'ざ' && touching(i + 1, i + 2) && word(i + 2).startsWith('い')) {
                append([start, at(i + 2, 1), endOf(i + 2)]);
                i += 3;
              } else if (current.endsWith('た') && (next === 'い' || next.startsWith('いと'))) {
                append([start, at(i, glyphs.length - 1), at(i + 1, 1), endOf(i + 1)]);
                i += 2;
              } else if (current === 'くだ' && next.startsWith('さい')) {
                append([start, at(i + 1, 2), endOf(i + 1)]);
                i += 2;
              } else if (next.startsWith('しそう') && isKana(current) &&
                  ((glyphs.length >= 2 && !particles.has(current)) || (current === 'も' && !touching(i - 1, i)))) {
                append([start, at(i + 1, 1), at(i + 1, 3), endOf(i + 1)]);
                i += 2;
              } else if (current.startsWith('しそう')) {
                append([start, at(i, 1), at(i, 3), endOf(i)]);
                i += 1;
              } else if (next === 'し' && isKana(current) && glyphs.length >= 2 && !particles.has(current) &&
                  touching(i + 1, i + 2) && word(i + 2).startsWith('そう')) {
                append([start, endOf(i + 1)]);
                i += 2;
              } else if (current.startsWith('そう') && glyphs.length > 2 && 'だでなに'.includes(glyphs[2])) {
                // そう|です|ね: a final particle after the copula is a word too.
                const final = glyphs.length > 3 && 'ねよか'.includes(glyphs[glyphs.length - 1]);
                append([start, at(i, 2), at(i, final ? glyphs.length - 1 : glyphs.length), endOf(i)]);
                i += 1;
              } else if ((current === 'よ' || current === 'な') && next === 'さ' && touching(i + 1, i + 2) &&
                  word(i + 2).startsWith('そう')) {
                append([start, endOf(i + 1)]);
                i += 2;
              } else if (current.endsWith('ません') && next === 'で' && touching(i + 1, i + 2) && word(i + 2) === 'した') {
                append([start, endOf(i + 2)]);
                i += 3;
              } else if (current === 'ことに' && next !== '' && 'しすな'.includes(chars(next)[0])) {
                append([start, at(i, 2), endOf(i)]);
                i += 1;
              } else if ((current === 'ったらしい' || current.startsWith('んじゃ')) && touching(i - 1, i) &&
                  isKanjiChar(chars(word(i - 1)).slice(-1)[0])) {
                append([start, at(i, current.startsWith('ん') ? 1 : 2), endOf(i)]);
                i += 1;
              } else if (isCompoundStem(current) && isCompoundHalf(next, touching(i + 1, i + 2) ? word(i + 2) : '')) {
                append([start, endOf(i + 1)]);
                i += 2;
              } else if ((current === 'ご' && next === 'ちそう') || (current === 'お' && next.startsWith('めでとう'))) {
                const end = next === 'ちそう' && touching(i + 1, i + 2) && word(i + 2) === 'さま' ? i + 2 : i + 1;
                append([start, endOf(end)]);
                i = end + 1;
              } else {
                repaired.push(segments[i]);
                i += 1;
              }
            }
            return repaired;
          };
          let segmented = false;
          let segments = null;
          if (japaneseTap && platformBreaks !== null && platformBreaks.text === node.data) {
            segments = platformBreaks.breaks.map((item) => ({
              segment: node.data.substring(item[0], item[1]), index: item[0], isWordLike: item[2] === 1
            }));
          } else if (typeof Intl !== 'undefined' && typeof Intl.Segmenter === 'function') {
            segments = Array.from(
              new Intl.Segmenter(japaneseTap ? 'ja' : 'en', {granularity: 'word'}).segment(node.data)
            );
          }
          if (segments !== null) {
            // Like NLTokenizer on iOS, the Japanese rules see words only: a
            // 。 or a space before も is no word it could follow.
            if (japaneseTap) {
              segments = repairJapaneseSegments(node.data, segments.filter(item => item.isWordLike));
            }
            let segmentIndex = segments.findIndex(item =>
              item.isWordLike && tapOffset >= item.index && tapOffset < item.index + item.segment.length
            );
            if (segmentIndex >= 0) {
              let start = segments[segmentIndex].index;
              let end = start + segments[segmentIndex].segment.length;
              if (japaneseTap) {
                const bounds = japaneseWordBounds(node.data, segments, segmentIndex);
                start = bounds[0];
                end = bounds[1];
              }
              range.setStart(node, start);
              range.setEnd(node, end);
              selection.removeAllRanges();
              selection.addRange(range);
              segmented = true;
            }
          }
          if (!segmented) {
            // From just after the tapped glyph, back to its word's start and
            // on to that word's end.
            range.setStart(node, Math.min(tapOffset + 1, node.data.length));
            range.collapse(true);
            selection.removeAllRanges();
            selection.addRange(range);
            selection.modify('move', 'backward', 'word');
            selection.modify('extend', 'forward', 'word');
          }
          if (!selection.rangeCount) return null;
          let selectedRange = selection.getRangeAt(0);
          let selectedText = selection.toString();
          if (!/[A-Za-z\u00C0-\u024F\u3040-\u30FF\u3400-\u9FFF]/.test(selectedText)) {
            selection.removeAllRanges();
            return null;
          }

          // Without segments, Chromium's word can stop before Japanese
          // auxiliaries: extend only a known inflection suffix in the same text
          // node. Segmented words already hold their endings, and a particle
          // keeps the verb after it out (に|した, が|ない), as on iOS.
          if (!segmented && selectedRange.startContainer === node && selectedRange.endContainer === node &&
              /[\u3040-\u30FF\u3400-\u9FFF]/.test(selectedText)) {
            const tail = node.data.substring(selectedRange.endOffset);
            const suffix = tail.match(/^(ませんでした|ません|ました|ます|ている|ない|かった|った|いた|いだ|んだ|した|た)/);
            if (suffix) {
              selectedRange.setEnd(node, selectedRange.endOffset + suffix[0].length);
              selection.removeAllRanges();
              selection.addRange(selectedRange);
              selectedText = selection.toString();
            }
          }
          // Readium's native selection bridge listens for this; addRange()
          // alone does not raise it on every WebView version.
          document.dispatchEvent(new Event('selectionchange'));
          if (!selectedText.trim().length) return null;
          selectedRange = selection.getRangeAt(0);
          let before = '';
          let after = '';
          if (selectedRange.startContainer === node && selectedRange.endContainer === node) {
            before = node.data.substring(
              Math.max(0, selectedRange.startOffset - ${CONTEXT_SIDE_LIMIT}),
              selectedRange.startOffset
            );
            after = node.data.substring(
              selectedRange.endOffset,
              Math.min(node.data.length, selectedRange.endOffset + ${CONTEXT_SIDE_LIMIT})
            );
          }
          return JSON.stringify({highlight: selectedText, before, after});
        })()
        """.trimIndent()
    }

    private data class SelectedWord(
        val highlight: String,
        val context: String?
    )

    private companion object {
        const val LOOKUP_MENU_ITEM_ID = 0x4A455257
        const val CONTEXT_SIDE_LIMIT = 120
        /** How far (CSS px) from a glyph a tap may land and still count as on it. */
        const val TAP_PROXIMITY_PX = 28
    }
}
