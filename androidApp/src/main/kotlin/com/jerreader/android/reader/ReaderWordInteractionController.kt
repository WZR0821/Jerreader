package com.jerreader.android.reader

import android.graphics.PointF
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import com.jerreader.unified.domain.LanguageCode
import com.jerreader.unified.lexical.LexicalLookupFailure
import com.jerreader.unified.lexical.LexicalLookupService
import com.jerreader.unified.lexical.WordAnalysis
import com.jerreader.unified.lexical.WordAnalysisRequest
import com.jerreader.unified.lexical.WordMorphologyAnalyzer
import com.jerreader.unified.lexical.WordSelectionSource
import com.jerreader.unified.reader.selection.ReaderSelectionScripts
import com.jerreader.unified.ui.WordLookupCardState
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
    /** A tap that landed on no word: the reader chrome toggles instead. */
    private val onContentTap: (() -> Unit)? = null
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
                lookupAt(
                    event.point,
                    onNoWordAtPoint = { onContentTap?.invoke() },
                    pointIsReadiumPixels = true
                )
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

    internal suspend fun lookupAt(
        point: PointF,
        /** The tap landed on no selectable word. See `onContentTap`. */
        onNoWordAtPoint: (() -> Unit)? = null,
        pointIsReadiumPixels: Boolean = false
    ): WordLookupCardState? {
        val navigator = navigator ?: return null
        val selected = navigator.evaluateJavascript(
            shortTapSelectionScript(point, pointIsReadiumPixels)
        )?.let(::decodeSelectedWord)
        if (selected == null) {
            navigator.clearSelection()
            onNoWordAtPoint?.invoke()
            return null
        }
        // The DOM selection and Readium's native Selection flow do not update
        // atomically. Reading currentSelection() here could therefore return
        // null or, worse, the previous tapped word. The same script which hit
        // tests the glyph already knows the exact word and neighbouring text,
        // so use that payload as the authoritative short-tap input.
        val analysis = analyzer.analyze(
            WordAnalysisRequest(
                text = selected.highlight,
                languageHint = languageHint(),
                source = WordSelectionSource.SHORT_TAP
            )
        )?.copy(sentenceContext = selected.context)
            ?: return null
        return lookupNow(analysis)
    }

    private fun decodeSelectedWord(raw: String): SelectedWord? {
        if (raw == "null" || raw == "false") return null
        val objectValue = when (val decoded = runCatching { JSONTokener(raw).nextValue() }.getOrNull()) {
            is JSONObject -> decoded
            is String -> runCatching { JSONObject(decoded) }.getOrNull()
            else -> null
        } ?: return null
        val highlight = objectValue.optString("highlight").trim()
        if (highlight.isEmpty()) return null
        val context = buildString {
            append(objectValue.optString("before"))
            append(highlight)
            append(objectValue.optString("after"))
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

    private suspend fun lookupCurrentSelection(): WordLookupCardState? {
        val selection = navigator?.currentSelection() ?: return null
        val analysis = analyzeSelection(selection, WordSelectionSource.NATIVE_SELECTION) ?: return null
        return lookupNow(analysis)
    }

    private fun analyzeSelection(
        selection: Selection,
        source: WordSelectionSource
    ): WordAnalysis? {
        val text = selection.locator.text
        val highlight = text.highlight ?: return null
        val context = buildString {
            append(text.before?.takeLast(CONTEXT_SIDE_LIMIT).orEmpty())
            append(highlight)
            append(text.after?.take(CONTEXT_SIDE_LIMIT).orEmpty())
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

    private fun shortTapSelectionScript(point: PointF, pointIsReadiumPixels: Boolean): String {
        val scale = if (pointIsReadiumPixels) {
            "Math.max(Number(window.devicePixelRatio) || 1, 0.0001)"
        } else {
            "1"
        }
        return """
        (() => {
          // Readium emits clientX/Y multiplied by this WebView's DPR. Undo it
          // here, in the document which supplied it, rather than with the
          // Android display density (the two can diverge on scaled WebViews).
          const jerreaderPointScale = $scale;
          const x = ${point.x} / jerreaderPointScale;
          const y = ${point.y} / jerreaderPointScale;
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
          // The tap has to be *on* a glyph, not merely nearest to one.
          // `caretRangeFromPoint` never misses — it reaches across the margin,
          // the short tail of a line, the blank end of a chapter — so without
          // this gate a tap on empty page silently looked up whichever word
          // happened to be closest. Same rule and same 28px as
          // `ReaderSelectionScripts.blockAt`, which gates the translate path.
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
          if (!(hitDistance <= ${ReaderSelectionScripts.TAP_PROXIMITY_SQUARED})) return null;
          const selection = window.getSelection();
          if (!selection || typeof selection.modify !== 'function') return null;
          const caretOffset = range.startOffset;
          const probe = node.data.charAt(Math.max(0, Math.min(caretOffset, node.data.length - 1)));
          const japaneseTap = /[\u3040-\u30FF\u3400-\u9FFF]/.test(probe);
          let segmented = false;
          if (typeof Intl !== 'undefined' && typeof Intl.Segmenter === 'function') {
            const segments = Array.from(
              new Intl.Segmenter(japaneseTap ? 'ja' : 'en', {granularity: 'word'}).segment(node.data)
            );
            let segmentIndex = segments.findIndex(item =>
              item.isWordLike && caretOffset >= item.index && caretOffset <= item.index + item.segment.length
            );
            if (segmentIndex >= 0) {
              let start = segments[segmentIndex].index;
              let end = start + segments[segmentIndex].segment.length;
              let lexicalIndex = segmentIndex;
              if (japaneseTap && /^(まし|ます|ました|ません|た|て|ない)$/.test(segments[segmentIndex].segment)) {
                for (let previous = segmentIndex - 1; previous >= 0; previous--) {
                  if (segments[previous].isWordLike) {
                    start = segments[previous].index;
                    lexicalIndex = previous;
                    break;
                  }
                }
              }
              // Some WebView ICU versions split kanji from okurigana
              // ("食" + "べ"). Join that single lexical boundary before
              // considering an auxiliary suffix.
              if (japaneseTap && /^[\u3040-\u309F]/.test(segments[lexicalIndex].segment)) {
                const previous = segments[lexicalIndex - 1];
                if (previous && previous.isWordLike &&
                    previous.index + previous.segment.length === segments[lexicalIndex].index &&
                    /[\u3400-\u9FFF]$/.test(previous.segment)) {
                  start = previous.index;
                }
              }
              range.setStart(node, start);
              range.setEnd(node, end);
              selection.removeAllRanges();
              selection.addRange(range);
              segmented = true;
            }
          }
          if (!segmented) {
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

          // Chromium can split Japanese auxiliaries from the lexical stem.
          // Extend only a known inflection suffix in the same text node.
          if (selectedRange.startContainer === node && selectedRange.endContainer === node &&
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
          // Programmatic selection changes are observable asynchronously in
          // WebView. Dispatching the standard event makes Readium refresh its
          // native Selection bridge immediately on WebView versions that do
          // not emit it for `addRange()` alone.
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
    }
}
