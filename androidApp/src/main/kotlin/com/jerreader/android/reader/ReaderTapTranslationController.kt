package com.jerreader.android.reader

import android.graphics.PointF
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import com.jerreader.android.translation.AndroidTranslationSettingsStore
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.QuickTranslationUnit
import com.jerreader.shared.translation.ReaderTextNormalizer
import com.jerreader.shared.translation.TranslationFailure
import com.jerreader.shared.translation.TranslationInputPolicy
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationService
import com.jerreader.shared.ui.TranslationCardState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
class ReaderTapTranslationController(
    private val scope: CoroutineScope,
    private val translationService: TranslationService,
    private val settings: AndroidTranslationSettingsStore,
    private val bookLanguageHint: () -> LanguageCode?,
    private val onStateChanged: (TranslationCardState) -> Unit,
    private val onPreviousPageRequested: (() -> Unit)? = null,
    private val onNextPageRequested: (() -> Unit)? = null,
    private val onLookupRequested: (() -> Unit)? = null,
    private val onAnnotateRequested: (() -> Unit)? = null
) {
    private var navigator: EpubNavigatorFragment? = null
    private var translationJob: Job? = null
    private var lastRequest: TranslationRequest? = null
    private var lastTapPoint: PointF? = null

    val selectionActionModeCallback: ActionMode.Callback = object : BaseActionModeCallback() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, TRANSLATE_MENU_ITEM_ID, Menu.NONE, "翻译")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            if (onLookupRequested != null) {
                menu.add(Menu.NONE, LOOKUP_MENU_ITEM_ID, Menu.NONE, "查词")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
            if (onAnnotateRequested != null) {
                menu.add(Menu.NONE, ANNOTATE_MENU_ITEM_ID, Menu.NONE, "划线/批注")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                TRANSLATE_MENU_ITEM_ID -> {
                    mode.finish()
                    translationJob?.cancel()
                    translationJob = scope.launch { translateCurrentSelection() }
                    true
                }
                LOOKUP_MENU_ITEM_ID -> {
                    mode.finish()
                    onLookupRequested?.invoke()
                    true
                }
                ANNOTATE_MENU_ITEM_ID -> {
                    mode.finish()
                    onAnnotateRequested?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    private val inputListener = object : InputListener {
        override fun onTap(event: TapEvent): Boolean {
            val preferences = settings.preferences.value
            if (!preferences.quickTranslationEnabled) return false
            if (!preferences.disablesTapPageTurnsDuringQuickTranslation) {
                val width = navigator?.publicationView?.width?.takeIf { it > 0 } ?: 0
                if (width > 0 && event.point.x <= width * EDGE_PAGE_TURN_RATIO) {
                    onPreviousPageRequested?.invoke()
                    return true
                }
                if (width > 0 && event.point.x >= width * (1f - EDGE_PAGE_TURN_RATIO)) {
                    onNextPageRequested?.invoke()
                    return true
                }
            }
            val cssPoint = toCssPoint(event.point)
            lastTapPoint = cssPoint
            translationJob?.cancel()
            translationJob = scope.launch {
                translateAt(cssPoint, preferences.quickTranslationUnit)
            }
            // A plain body tap is reserved for quick translation. Links and
            // controls are filtered by Readium before reaching this listener.
            // Swipes remain available for page turning.
            return true
        }
    }

    fun attach(navigator: EpubNavigatorFragment) {
        detach()
        this.navigator = navigator
        navigator.addInputListener(inputListener)
    }

    fun detach() {
        navigator?.removeInputListener(inputListener)
        navigator = null
        translationJob?.cancel()
        translationJob = null
    }

    fun clearSelection() {
        navigator?.clearSelection()
    }

    fun retry() {
        val request = lastRequest ?: return
        startTranslation(request)
    }

    /** Issues exactly one request for a locally completed cross-page segment. */
    suspend fun translateExpanded(request: TranslationRequest): TranslationCardState =
        translateNow(request)

    fun focusPoint(): PointF? = lastTapPoint?.let { PointF(it.x, it.y) }

    /**
     * Readium reports taps in navigator view pixels, while the injected script
     * resolves a caret with `caretRangeFromPoint`, which is CSS pixels. Reflowable
     * EPUB pages use `width=device-width`, so one CSS pixel is one dp.
     */
    private fun toCssPoint(point: PointF): PointF {
        val density = navigator?.publicationView?.resources?.displayMetrics?.density ?: 0f
        if (density <= 0f) return PointF(point.x, point.y)
        return PointF(point.x / density, point.y / density)
    }

    internal suspend fun translateAt(
        point: PointF,
        unit: QuickTranslationUnit = settings.preferences.value.quickTranslationUnit
    ): TranslationCardState? {
        val navigator = navigator ?: return null
        lastTapPoint = PointF(point.x, point.y)
        val selected = navigator.evaluateJavascript(tapSelectionScript(point, unit))
        if (selected != "true") return null
        val selection = navigator.currentSelection() ?: return null
        return translateSelection(selection)
    }

    /**
     * Reads the visible Readium document as plain text around [sourceText],
     * excluding ruby annotations, so a sentence cut by a screen boundary can be
     * completed locally before any translation request is made.
     */
    suspend fun crossPageContext(sourceText: String): String? {
        val source = ReaderTextNormalizer.normalized(sourceText)
        if (source.isEmpty()) return null
        val raw = navigator?.evaluateJavascript(crossPageContextScript(source)) ?: return null
        if (raw == "null") return null
        val decoded = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            ?: raw.trim('"')
        val context = ReaderTextNormalizer.normalized(decoded)
        return context.takeIf { it.length > source.length && it.contains(source) }
    }

    /** Same entry point as a real Readium tap, including the pixel conversion. */
    internal suspend fun translateAtDevicePoint(
        point: PointF,
        unit: QuickTranslationUnit = settings.preferences.value.quickTranslationUnit
    ): TranslationCardState? = translateAt(toCssPoint(point), unit)

    internal fun tapSelectionScriptForTesting(point: PointF, unit: QuickTranslationUnit): String =
        tapSelectionScript(point, unit)

    private suspend fun translateCurrentSelection(): TranslationCardState? {
        val selection = navigator?.currentSelection() ?: return null
        return translateSelection(selection)
    }

    private suspend fun translateSelection(selection: Selection): TranslationCardState? {
        val sourceText = selection.locator.text.highlight ?: return null
        val normalized = try {
            TranslationInputPolicy.validate(sourceText)
        } catch (error: TranslationFailure) {
            return TranslationCardState.Failure(
                sourceText = sourceText.trim(),
                message = error.message ?: "无法翻译这段文字。"
            ).also(onStateChanged)
        }
        val preferences = settings.preferences.value
        val request = TranslationRequest(
            text = normalized,
            sourceLanguage = preferences.sourceChoice.language ?: bookLanguageHint(),
            targetLanguage = preferences.targetLanguage
        )
        return translateNow(request)
    }

    private fun startTranslation(request: TranslationRequest) {
        translationJob?.cancel()
        translationJob = scope.launch { translateNow(request) }
    }

    private suspend fun translateNow(request: TranslationRequest): TranslationCardState {
        lastRequest = request
        val loading = TranslationCardState.Loading(request.text)
        onStateChanged(loading)
        return try {
            val result = translationService.translate(request)
            TranslationCardState.Success(request.text, result).also(onStateChanged)
        } catch (error: CancellationException) {
            throw error
        } catch (error: TranslationFailure) {
            TranslationCardState.Failure(
                sourceText = request.text,
                message = error.message ?: "翻译失败。"
            ).also(onStateChanged)
        } catch (_: Exception) {
            TranslationCardState.Failure(
                sourceText = request.text,
                message = "翻译服务暂时不可用，请稍后重试。"
            ).also(onStateChanged)
        }
    }

    private fun tapSelectionScript(point: PointF, unit: QuickTranslationUnit): String {
        val granularity = if (unit == QuickTranslationUnit.PARAGRAPH) "paragraph" else "sentence"
        return """
            (() => {
              const x = ${point.x};
              const y = ${point.y};
              const requestedUnit = '$granularity';
              let caret = null;
              if (document.caretRangeFromPoint) {
                caret = document.caretRangeFromPoint(x, y);
              } else if (document.caretPositionFromPoint) {
                const position = document.caretPositionFromPoint(x, y);
                if (position) {
                  caret = document.createRange();
                  caret.setStart(position.offsetNode, position.offset);
                  caret.collapse(true);
                }
              }
              if (!caret || !caret.startContainer ||
                  caret.startContainer.nodeType !== Node.TEXT_NODE) return false;

              const tappedNode = caret.startContainer;
              const tappedElement = tappedNode.parentElement;
              if (!tappedElement || tappedElement.closest(
                    'a,button,input,textarea,select,option,video,audio,canvas,svg,rt,rp,script,style'
                  )) return false;

              const blockSelector =
                'p,li,blockquote,dd,dt,figcaption,h1,h2,h3,h4,h5,h6,td,th,pre';
              const block = tappedElement.closest(blockSelector) || tappedElement;
              const walker = document.createTreeWalker(
                block,
                NodeFilter.SHOW_TEXT,
                {
                  acceptNode(node) {
                    const parent = node.parentElement;
                    if (!parent || parent.closest('rt,rp,script,style,noscript')) {
                      return NodeFilter.FILTER_REJECT;
                    }
                    return NodeFilter.FILTER_ACCEPT;
                  }
                }
              );
              const nodes = [];
              let node = walker.nextNode();
              while (node) {
                nodes.push(node);
                node = walker.nextNode();
              }
              if (!nodes.length || !nodes.includes(tappedNode)) return false;

              let text = '';
              let tapOffset = 0;
              for (const textNode of nodes) {
                if (textNode === tappedNode) {
                  tapOffset = text.length + Math.max(
                    0,
                    Math.min(caret.startOffset, textNode.data.length)
                  );
                }
                text += textNode.data;
              }
              if (!text.trim()) return false;

              let start = 0;
              let end = text.length;
              if (requestedUnit === 'sentence') {
                let found = false;
                if (typeof Intl !== 'undefined' && typeof Intl.Segmenter === 'function') {
                  const locale = /[\u3040-\u30FF]/.test(text) ? 'ja' : 'en';
                  const segments = Array.from(
                    new Intl.Segmenter(locale, {granularity: 'sentence'}).segment(text)
                  );
                  const segment = segments.find(item =>
                    tapOffset >= item.index && tapOffset <= item.index + item.segment.length
                  );
                  if (segment) {
                    start = segment.index;
                    end = segment.index + segment.segment.length;
                    found = true;
                  }
                }
                if (!found) {
                  const stops = /[。！？.!?]/;
                  start = tapOffset;
                  while (start > 0 && !stops.test(text.charAt(start - 1))) start--;
                  end = tapOffset;
                  while (end < text.length && !stops.test(text.charAt(end))) end++;
                  if (end < text.length) end++;
                }
              }

              while (start < end && /\s/.test(text.charAt(start))) start++;
              while (end > start && /\s/.test(text.charAt(end - 1))) end--;
              if (end <= start) return false;

              function resolveOffset(offset, isEnd) {
                let consumed = 0;
                for (let index = 0; index < nodes.length; index++) {
                  const length = nodes[index].data.length;
                  if (offset < consumed + length ||
                      (offset === consumed + length && (isEnd || index === nodes.length - 1))) {
                    return {node: nodes[index], offset: offset - consumed};
                  }
                  consumed += length;
                }
                const last = nodes[nodes.length - 1];
                return {node: last, offset: last.data.length};
              }

              const rangeStart = resolveOffset(start, false);
              const rangeEnd = resolveOffset(end, true);
              const range = document.createRange();
              range.setStart(rangeStart.node, rangeStart.offset);
              range.setEnd(rangeEnd.node, rangeEnd.offset);
              const selection = window.getSelection();
              if (!selection) return false;
              selection.removeAllRanges();
              selection.addRange(range);
              return selection.toString().trim().length > 0;
            })()
        """.trimIndent()
    }

    private fun crossPageContextScript(source: String): String {
        val encoded = JSONObject.quote(source)
        return """
            (() => {
              const source = ($encoded || '').replace(/\s+/gu, ' ').trim();
              if (!source) return null;
              const root = document.body || document.documentElement;
              const walker = document.createTreeWalker(
                root,
                NodeFilter.SHOW_TEXT,
                {
                  acceptNode(node) {
                    const parent = node.parentElement;
                    if (!parent || parent.closest('rt,rp,script,style,noscript')) {
                      return NodeFilter.FILTER_REJECT;
                    }
                    return NodeFilter.FILTER_ACCEPT;
                  }
                }
              );
              const blockSelector =
                'p,li,blockquote,dd,dt,figcaption,h1,h2,h3,h4,h5,h6,td,th,pre';
              let raw = '';
              let previousBlock = null;
              let node = walker.nextNode();
              while (node) {
                const block = (node.parentElement &&
                  node.parentElement.closest(blockSelector)) || root;
                if (raw && previousBlock && block !== previousBlock) raw += ' ';
                raw += node.data;
                previousBlock = block;
                node = walker.nextNode();
              }
              const text = raw.replace(/\s+/gu, ' ').trim();
              if (!text) return null;

              const matches = [];
              let cursor = 0;
              while (cursor <= text.length - source.length) {
                const index = text.indexOf(source, cursor);
                if (index < 0) break;
                matches.push(index);
                cursor = index + Math.max(source.length, 1);
              }
              if (!matches.length) return null;
              const middle = text.length / 2;
              matches.sort(
                (left, right) => Math.abs(left - middle) - Math.abs(right - middle)
              );

              const selected = matches[0];
              const maximum = Math.max(1200, Math.min(source.length + 240, 2000));
              const remaining = Math.max(maximum - source.length, 0);
              let start = Math.max(0, selected - Math.floor(remaining / 2));
              let end = Math.min(
                text.length,
                selected + source.length + (remaining - Math.floor(remaining / 2))
              );
              if (end - start < maximum) {
                start = Math.max(0, start - (maximum - (end - start)));
                end = Math.min(text.length, start + maximum);
              }
              return text.slice(start, end);
            })()
        """.trimIndent()
    }

    private companion object {
        const val TRANSLATE_MENU_ITEM_ID = 0x4A455254
        const val LOOKUP_MENU_ITEM_ID = 0x4A455257
        const val ANNOTATE_MENU_ITEM_ID = 0x4A455241
        const val EDGE_PAGE_TURN_RATIO = 0.16f
    }
}
