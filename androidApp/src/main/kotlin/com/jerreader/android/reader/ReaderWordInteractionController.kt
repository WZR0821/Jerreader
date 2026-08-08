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
    private val onSelectionInvalidated: (() -> Unit)? = null
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
            lookupJob = scope.launch { lookupAt(toCssPoint(event.point)) }
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

    /**
     * Readium reports taps in navigator view pixels; `caretRangeFromPoint`
     * expects CSS pixels, which equal dp on a `width=device-width` page.
     */
    private fun toCssPoint(point: PointF): PointF {
        val density = navigator?.publicationView?.resources?.displayMetrics?.density ?: 0f
        if (density <= 0f) return PointF(point.x, point.y)
        return PointF(point.x / density, point.y / density)
    }

    fun lookupSelectionFromMenu() {
        invalidateForNewLookup()
        lookupJob?.cancel()
        lookupJob = scope.launch { lookupCurrentSelection() }
    }

    internal suspend fun lookupAt(point: PointF): WordLookupCardState? {
        val navigator = navigator ?: return null
        val selected = navigator.evaluateJavascript(shortTapSelectionScript(point))
        if (selected != "true") {
            navigator.clearSelection()
            return null
        }
        val selection = navigator.currentSelection() ?: return null
        val analysis = analyzeSelection(selection, WordSelectionSource.SHORT_TAP)
            ?: return null
        return lookupNow(analysis)
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

    private fun shortTapSelectionScript(point: PointF): String = """
        (() => {
          const x = ${point.x};
          const y = ${point.y};
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
            return false;
          }
          const node = range.startContainer;
          const parent = node.parentElement;
          if (!parent || parent.closest('a,button,input,textarea,select,option,video,audio,rt,rp')) {
            return false;
          }
          const selection = window.getSelection();
          if (!selection || typeof selection.modify !== 'function') return false;
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
          if (!selection.rangeCount) return false;
          let selectedRange = selection.getRangeAt(0);
          let selectedText = selection.toString();
          if (!/[A-Za-z\u00C0-\u024F\u3040-\u30FF\u3400-\u9FFF]/.test(selectedText)) {
            selection.removeAllRanges();
            return false;
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
          return selectedText.trim().length > 0;
        })()
    """.trimIndent()

    private companion object {
        const val LOOKUP_MENU_ITEM_ID = 0x4A455257
        const val CONTEXT_SIDE_LIMIT = 120
    }
}
