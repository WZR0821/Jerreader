package com.jerreader.android.reader

import android.graphics.PointF
import android.graphics.RectF
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import com.jerreader.android.translation.AndroidTranslationSettingsStore
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.QuickTranslationUnit
import com.jerreader.shared.translation.ReaderSentenceSegmenter
import com.jerreader.shared.translation.ReaderTextNormalizer
import com.jerreader.shared.translation.TranslationFailure
import com.jerreader.shared.translation.TranslationInputPolicy
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationService
import com.jerreader.shared.ui.TranslationCardState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
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
    private val onAnnotateRequested: (() -> Unit)? = null,
    private val onHapticFeedback: (() -> Unit)? = null,
    private val onHighlightChanged: ((List<RectF>) -> Unit)? = null,
    private val timeSource: () -> Long = System::currentTimeMillis
) {
    private var navigator: EpubNavigatorFragment? = null
    private var translationJob: Job? = null
    private var lastRequest: TranslationRequest? = null
    private var lastTapPoint: PointF? = null
    private var requestToken = 0L
    private var currentIdentity: String? = null
    private var currentState: TranslationCardState? = null
    private val manualRetryAt = mutableMapOf<String, Long>()

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
        onHighlightChanged?.invoke(emptyList())
        currentIdentity = null
        currentState = null
    }

    fun retry() {
        val request = lastRequest ?: return
        startTranslation(request)
    }

    /**
     * Forces the writing mode of the visible resource.
     *
     * Readium resolves an EPUB's layout from its metadata, and for a publication
     * that declares Japanese vertical text it keeps rendering vertically even
     * after `verticalText = false` is submitted. This injects a stylesheet into
     * the rendered document only; the EPUB file itself is never touched.
     */
    suspend fun applyWritingModeOverride(mode: String?) {
        val navigator = navigator ?: return
        navigator.evaluateJavascript(writingModeScript(mode))
    }

    private fun writingModeScript(mode: String?): String {
        val css = when (mode) {
            "horizontal" ->
                "html,body,* { writing-mode: horizontal-tb !important;" +
                    " -webkit-writing-mode: horizontal-tb !important;" +
                    " -epub-writing-mode: horizontal-tb !important;" +
                    " text-orientation: mixed !important; }"
            "vertical" ->
                "html,body { writing-mode: vertical-rl !important;" +
                    " -webkit-writing-mode: vertical-rl !important;" +
                    " -epub-writing-mode: vertical-rl !important; }"
            else -> ""
        }
        val encoded = JSONObject.quote(css)
        return """
            (() => {
              const id = 'jerreader-writing-mode';
              const previous = document.getElementById(id);
              if (previous) previous.remove();
              const css = $encoded;
              if (!css) return true;
              const style = document.createElement('style');
              style.id = id;
              style.textContent = css;
              (document.head || document.documentElement).appendChild(style);
              return true;
            })()
        """.trimIndent()
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

    /**
     * Tap selection now runs in three steps instead of duplicating sentence
     * rules in JavaScript: the page returns the tapped block's text and caret
     * offset, `ReaderSentenceSegmenter` decides the range with the same rules
     * the cross-page expansion uses (quotation particles, quoted terminators,
     * abbreviations), then the page selects exactly that range and returns its
     * client rects so the reader can draw its own highlight.
     */
    internal suspend fun translateAt(
        point: PointF,
        unit: QuickTranslationUnit = settings.preferences.value.quickTranslationUnit
    ): TranslationCardState? {
        val navigator = navigator ?: return null
        lastTapPoint = PointF(point.x, point.y)
        val raw = navigator.evaluateJavascript(blockTextScript(point)) ?: return null
        val block = decodeJson(raw) ?: return null
        val text = block.optString("text")
        if (text.isBlank()) return null
        val caret = block.optInt("offset").coerceIn(0, (text.length - 1).coerceAtLeast(0))

        val range = if (unit == QuickTranslationUnit.PARAGRAPH) {
            text.indices
        } else {
            ReaderSentenceSegmenter.sentenceRange(text, caret, bookLanguageHint()) ?: return null
        }
        var start = range.first
        var end = range.last + 1
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (end <= start) return null

        val selectedRaw = navigator.evaluateJavascript(selectRangeScript(point, start, end)) ?: return null
        val selected = decodeJson(selectedRaw) ?: return null
        if (!selected.optBoolean("ok")) return null
        onHighlightChanged?.invoke(parseRects(selected.optJSONArray("rects")))

        val selection = navigator.currentSelection() ?: return null
        return translateSelection(selection)
    }

    private fun decodeJson(raw: String): JSONObject? {
        if (raw == "null") return null
        return runCatching {
            when (val decoded = JSONTokener(raw).nextValue()) {
                is JSONObject -> decoded
                is String -> JSONObject(decoded)
                else -> null
            }
        }.getOrNull()
    }

    private fun parseRects(array: org.json.JSONArray?): List<RectF> {
        if (array == null) return emptyList()
        val density = navigator?.publicationView?.resources?.displayMetrics?.density ?: 1f
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            RectF(
                (item.optDouble("x") * density).toFloat(),
                (item.optDouble("y") * density).toFloat(),
                ((item.optDouble("x") + item.optDouble("w")) * density).toFloat(),
                ((item.optDouble("y") + item.optDouble("h")) * density).toFloat()
            )
        }
    }

    /** Reads the tapped block's text and caret offset, excluding ruby. */
    private fun blockTextScript(point: PointF): String = """
        (() => {
              const textNodes = (root) => {
                const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
                  acceptNode(node) {
                    const parent = node.parentElement;
                    if (!parent || parent.closest('rt,rp,script,style,noscript')) {
                      return NodeFilter.FILTER_REJECT;
                    }
                    return NodeFilter.FILTER_ACCEPT;
                  }
                });
                const list = [];
                let node = walker.nextNode();
                while (node) { list.push(node); node = walker.nextNode(); }
                return list;
              };
              const blockAt = (x, y) => {
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
                    caret.startContainer.nodeType !== Node.TEXT_NODE) return null;
                const element = caret.startContainer.parentElement;
                if (!element || element.closest(
                      'a,button,input,textarea,select,option,video,audio,canvas,svg,rt,rp,script,style'
                    )) return null;
                const block = element.closest(
                  'p,li,blockquote,dd,dt,figcaption,h1,h2,h3,h4,h5,h6,td,th,pre'
                ) || element;
                return {block: block, caret: caret};
              };
          const hit = blockAt(${point.x}, ${point.y});
          if (!hit) return null;
          const nodes = textNodes(hit.block);
          if (!nodes.length || nodes.indexOf(hit.caret.startContainer) < 0) return null;
          let text = '';
          let offset = 0;
          for (const node of nodes) {
            if (node === hit.caret.startContainer) {
              offset = text.length +
                Math.max(0, Math.min(hit.caret.startOffset, node.data.length));
            }
            text += node.data;
          }
          return JSON.stringify({text: text, offset: offset});
        })()
    """.trimIndent()

    /** Selects the resolved range and returns its client rects in CSS pixels. */
    private fun selectRangeScript(point: PointF, start: Int, end: Int): String = """
        (() => {
              const textNodes = (root) => {
                const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
                  acceptNode(node) {
                    const parent = node.parentElement;
                    if (!parent || parent.closest('rt,rp,script,style,noscript')) {
                      return NodeFilter.FILTER_REJECT;
                    }
                    return NodeFilter.FILTER_ACCEPT;
                  }
                });
                const list = [];
                let node = walker.nextNode();
                while (node) { list.push(node); node = walker.nextNode(); }
                return list;
              };
              const blockAt = (x, y) => {
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
                    caret.startContainer.nodeType !== Node.TEXT_NODE) return null;
                const element = caret.startContainer.parentElement;
                if (!element || element.closest(
                      'a,button,input,textarea,select,option,video,audio,canvas,svg,rt,rp,script,style'
                    )) return null;
                const block = element.closest(
                  'p,li,blockquote,dd,dt,figcaption,h1,h2,h3,h4,h5,h6,td,th,pre'
                ) || element;
                return {block: block, caret: caret};
              };
          const hit = blockAt(${point.x}, ${point.y});
          if (!hit) return JSON.stringify({ok: false});
          const nodes = textNodes(hit.block);
          if (!nodes.length) return JSON.stringify({ok: false});
          const locate = (offset, isEnd) => {
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
          };
          const from = locate($start, false);
          const to = locate($end, true);
          const range = document.createRange();
          range.setStart(from.node, from.offset);
          range.setEnd(to.node, to.offset);
          const selection = window.getSelection();
          if (!selection) return JSON.stringify({ok: false});
          selection.removeAllRanges();
          selection.addRange(range);
          const rects = Array.from(range.getClientRects())
            .filter(rect => rect.width > 0.5 && rect.height > 0.5)
            .map(rect => ({x: rect.left, y: rect.top, w: rect.width, h: rect.height}));
          return JSON.stringify({ok: selection.toString().trim().length > 0, rects: rects});
        })()
    """.trimIndent()

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

    /**
     * Manual retry, mirroring iOS: the same text may be retried at most once
     * every five seconds, and the cooldown key deliberately excludes the
     * provider so switching to a fallback cannot bypass it.
     */
    fun retryManually(): Boolean {
        val request = lastRequest ?: return false
        val key = cooldownKey(request)
        val now = timeSource()
        val previous = manualRetryAt[key]
        if (previous != null && now - previous < MANUAL_RETRY_COOLDOWN_MILLIS) return false
        if (currentState is TranslationCardState.Loading) return false
        manualRetryAt[key] = now
        startTranslation(request)
        return true
    }

    private fun requestIdentity(request: TranslationRequest): String = buildString {
        append(navigator?.currentLocator?.value?.href?.toString().orEmpty())
        append('|')
        append(ReaderTextNormalizer.normalized(request.text))
        append('|')
        append(request.sourceLanguage?.tag ?: "auto")
        append("->")
        append(request.targetLanguage.tag)
        append('|')
        append(settings.preferences.value.providerMode.name)
    }

    private fun cooldownKey(request: TranslationRequest): String = buildString {
        append(ReaderTextNormalizer.normalized(request.text))
        append('|')
        append(request.sourceLanguage?.tag ?: "auto")
        append("->")
        append(request.targetLanguage.tag)
    }

    private suspend fun translateNow(request: TranslationRequest): TranslationCardState {
        val identity = requestIdentity(request)
        // The same sentence tapped again while it is loading, or already shown
        // as a success, must not spend another request.
        val visible = currentState
        if (identity == currentIdentity && visible != null &&
            visible !is TranslationCardState.Failure
        ) {
            onStateChanged(visible)
            return visible
        }

        lastRequest = request
        currentIdentity = identity
        val token = ++requestToken
        publish(token, TranslationCardState.Loading(request.text))
        return try {
            val result = withTimeout(TRANSLATION_TIMEOUT_MILLIS) {
                translationService.translate(request)
            }
            TranslationCardState.Success(request.text, result).also { publish(token, it) }
        } catch (_: TimeoutCancellationException) {
            // Must precede the CancellationException branch: the timeout type
            // extends it, so catching the parent first swallowed the watchdog
            // and left the card spinning forever.
            TranslationCardState.Failure(
                sourceText = request.text,
                message = "翻译超过 30 秒未返回，请重试或更换翻译服务。"
            ).also { publish(token, it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: TranslationFailure) {
            TranslationCardState.Failure(
                sourceText = request.text,
                message = error.message ?: "翻译失败。"
            ).also { publish(token, it) }
        } catch (_: Exception) {
            TranslationCardState.Failure(
                sourceText = request.text,
                message = "翻译服务暂时不可用，请稍后重试。"
            ).also { publish(token, it) }
        }
    }

    /** Drops results from superseded requests so a late reply cannot win. */
    private fun publish(token: Long, state: TranslationCardState) {
        if (token != requestToken) return
        currentState = state
        onStateChanged(state)
        if (state !is TranslationCardState.Loading &&
            settings.preferences.value.translationHapticsEnabled
        ) {
            onHapticFeedback?.invoke()
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
        const val TRANSLATION_TIMEOUT_MILLIS = 30_000L
        const val MANUAL_RETRY_COOLDOWN_MILLIS = 5_000L
    }
}
