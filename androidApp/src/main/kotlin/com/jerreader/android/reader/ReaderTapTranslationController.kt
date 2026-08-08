package com.jerreader.android.reader

import android.graphics.PointF
import android.graphics.RectF
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import com.jerreader.android.translation.AndroidTranslationSettingsStore
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.library.ReaderAppearance
import com.jerreader.shared.library.ReaderSelectionRect
import com.jerreader.shared.library.ReaderSelectionRectMerger
import com.jerreader.shared.library.ReaderSelectionVisualStyle
import com.jerreader.shared.library.ReaderTextOrientation
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val onShortTapWordRequested: (() -> Unit)? = null,
    private val onAnnotateRequested: (() -> Unit)? = null,
    private val onHapticFeedback: (() -> Unit)? = null,
    private val onHighlightChanged: ((List<RectF>) -> Unit)? = null,
    private val onSelectionInvalidated: (() -> Unit)? = null,
    private val appearance: () -> ReaderAppearance = { ReaderAppearance() },
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
            // The system action mode is created after WebView has a native
            // Range. Install our ruby-aware overlay at that point as well as
            // during attach, because a selection can be created by a long
            // press before the Readium page finishes settling.
            installNativeSelectionBridge()
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
                    invalidateForNewTap()
                    translationJob?.cancel()
                    translationJob = scope.launch { translateCurrentSelection() }
                    true
                }
                LOOKUP_MENU_ITEM_ID -> {
                    mode.finish()
                    invalidateForNewTap()
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
                    invalidateForNewTap()
                    onPreviousPageRequested?.invoke()
                    return true
                }
                if (width > 0 && event.point.x >= width * (1f - EDGE_PAGE_TURN_RATIO)) {
                    invalidateForNewTap()
                    onNextPageRequested?.invoke()
                    return true
                }
            }
            val cssPoint = toCssPoint(event.point)
            invalidateForNewTap()
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
        installNativeSelectionBridge()
    }

    /** Re-applies the Android selection colours after a reader theme change. */
    fun refreshSelectionAppearance() {
        installNativeSelectionBridge()
    }

    private fun installNativeSelectionBridge() {
        val target = navigator ?: return
        scope.launch { target.evaluateJavascript(nativeSelectionBridgeScript()) }
    }

    fun detach() {
        navigator?.removeInputListener(inputListener)
        val target = navigator
        scope.launch { target?.evaluateJavascript(clearHighlightScript()) }
        navigator = null
        translationJob?.cancel()
        translationJob = null
        requestToken++
        lastRequest = null
        currentIdentity = null
        currentState = null
        manualRetryAt.clear()
        onHighlightChanged?.invoke(emptyList())
    }

    fun clearSelection() {
        val target = navigator
        translationJob?.cancel()
        translationJob = null
        requestToken++
        lastRequest = null
        navigator?.clearSelection()
        scope.launch { target?.evaluateJavascript(clearHighlightScript()) }
        onHighlightChanged?.invoke(emptyList())
        currentIdentity = null
        currentState = null
    }

    /** Invalidates only transient translation UI for a new tap. */
    private fun invalidateForNewTap() {
        val hadVisibleState = currentState != null || lastRequest != null
        translationJob?.cancel()
        translationJob = null
        requestToken++
        lastRequest = null
        currentIdentity = null
        currentState = null
        onHighlightChanged?.invoke(emptyList())
        if (hadVisibleState) onSelectionInvalidated?.invoke()
    }

    /** Hides a translation while preserving the native selection for 查词. */
    fun forgetVisibleTranslation() {
        translationJob?.cancel()
        currentIdentity = null
        currentState = null
        lastRequest = null
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

    /** Reads the publication's actual DOM writing mode when the user chose 原书. */
    suspend fun detectPublicationVertical(): Boolean {
        val raw = navigator?.evaluateJavascript(
            """getComputedStyle(document.body || document.documentElement).writingMode"""
        ) ?: return false
        val value = raw.trim().trim('"').lowercase()
        return value == "vertical-rl" || value == "vertical-lr" || value.startsWith("sideways")
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
        // Drop the previous tiles here, in the same awaited sequence that draws
        // the new ones, so a tap that resolves to nothing cannot leave the old
        // sentence highlighted underneath.
        navigator.evaluateJavascript(clearHighlightScript())
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

        // The page paints the tiles itself; these are the same tiles, only so
        // the card can be anchored clear of the selected text. They go through
        // the merger a second time because the page probes its writing mode
        // from CSS, which some WebView builds report as horizontal for
        // `-epub-writing-mode` — here the reader knows what it configured.
        val merged = ReaderSelectionRectMerger.merge(
            parseSelectionRects(selected.optJSONArray("rects")),
            vertical = selected.optBoolean("vertical") ||
                appearance().orientation == ReaderTextOrientation.VERTICAL
        )
        onHighlightChanged?.invoke(merged.map(::toDeviceRect))

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

    private fun parseSelectionRects(array: org.json.JSONArray?): List<ReaderSelectionRect> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            ReaderSelectionRect(
                x = item.optDouble("x"),
                y = item.optDouble("y"),
                width = item.optDouble("w"),
                height = item.optDouble("h")
            )
        }
    }

    /** CSS pixels to device pixels, for anchoring views outside the WebView. */
    private fun toDeviceRect(rect: ReaderSelectionRect): RectF {
        val density = navigator?.publicationView?.resources?.displayMetrics?.density ?: 1f
        return RectF(
            (rect.x * density).toFloat(),
            (rect.y * density).toFloat(),
            (rect.right * density).toFloat(),
            (rect.bottom * density).toFloat()
        )
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
              const rubyBaseNodes = (ruby) => {
                if (!ruby) return [];
                const walker = document.createTreeWalker(ruby, NodeFilter.SHOW_TEXT, {
                  acceptNode(node) {
                    return node.parentElement?.closest?.('rt,rp')
                      ? NodeFilter.FILTER_REJECT
                      : NodeFilter.FILTER_ACCEPT;
                  }
                });
                const list = [];
                let node = walker.nextNode();
                while (node) {
                  if (node.data?.length) list.push(node);
                  node = walker.nextNode();
                }
                return list;
              };
              const pointDistance = (rect, x, y) => {
                const dx = Math.max(rect.left - x, 0, x - rect.right);
                const dy = Math.max(rect.top - y, 0, y - rect.bottom);
                return dx * dx + dy * dy;
              };
              const rubyAnnotationAtPoint = (x, y) => {
                let best = null;
                let bestDistance = Number.POSITIVE_INFINITY;
                document.querySelectorAll('rt,rp').forEach((annotation) => {
                  Array.from(annotation.getClientRects()).forEach((rect) => {
                    const distance = pointDistance(rect, x, y);
                    if (distance <= 400 && distance < bestDistance) {
                      best = annotation;
                      bestDistance = distance;
                    }
                  });
                });
                return best;
              };
              const caretForRuby = (ruby, x, y) => {
                const candidates = rubyBaseNodes(ruby);
                let bestNode = null;
                let bestDistance = Number.POSITIVE_INFINITY;
                candidates.forEach((node) => {
                  const range = document.createRange();
                  range.selectNodeContents(node);
                  Array.from(range.getClientRects()).forEach((rect) => {
                    const distance = pointDistance(rect, x, y);
                    if (distance < bestDistance) {
                      bestNode = node;
                      bestDistance = distance;
                    }
                  });
                });
                const node = bestNode || candidates[0];
                if (!node) return null;
                const resolved = document.createRange();
                resolved.setStart(node, Math.floor(node.data.length / 2));
                resolved.collapse(true);
                return resolved;
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
                const annotation = element?.closest?.('rt,rp') || rubyAnnotationAtPoint(x, y);
                const ruby = annotation?.closest?.('ruby') || element?.closest?.('ruby');
                if (annotation && ruby) {
                  caret = caretForRuby(ruby, x, y) || caret;
                }
                const resolvedElement = caret.startContainer.parentElement;
                if (!resolvedElement || resolvedElement.closest(
                      'a,button,input,textarea,select,option,video,audio,canvas,svg,rt,rp,script,style'
                    )) return null;
                const block = resolvedElement.closest(
                  'p,li,blockquote,dd,dt,figcaption,h1,h2,h3,h4,h5,h6,td,th,pre'
                ) || resolvedElement;
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

    /**
     * Selects the resolved range, paints the highlight and returns the tiles it
     * painted, in CSS pixels.
     *
     * Measuring and painting have to happen in one evaluation: `addRange`
     * scrolls the selection into view, so client rects read before that scroll
     * no longer line up with the page by the time a second call could paint
     * them, and the highlight lands next to the text instead of on it.
     */
    private fun selectRangeScript(point: PointF, start: Int, end: Int): String {
        val palette = ReaderSelectionVisualStyle.palette(appearance())
        val tint = palette.fillCss
        val stroke = palette.strokeCss
        return """
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
              const rubyBaseNodes = (ruby) => {
                if (!ruby) return [];
                const walker = document.createTreeWalker(ruby, NodeFilter.SHOW_TEXT, {
                  acceptNode(node) {
                    return node.parentElement?.closest?.('rt,rp')
                      ? NodeFilter.FILTER_REJECT
                      : NodeFilter.FILTER_ACCEPT;
                  }
                });
                const list = [];
                let node = walker.nextNode();
                while (node) {
                  if (node.data?.length) list.push(node);
                  node = walker.nextNode();
                }
                return list;
              };
              const pointDistance = (rect, x, y) => {
                const dx = Math.max(rect.left - x, 0, x - rect.right);
                const dy = Math.max(rect.top - y, 0, y - rect.bottom);
                return dx * dx + dy * dy;
              };
              const rubyAnnotationAtPoint = (x, y) => {
                let best = null;
                let bestDistance = Number.POSITIVE_INFINITY;
                document.querySelectorAll('rt,rp').forEach((annotation) => {
                  Array.from(annotation.getClientRects()).forEach((rect) => {
                    const distance = pointDistance(rect, x, y);
                    if (distance <= 400 && distance < bestDistance) {
                      best = annotation;
                      bestDistance = distance;
                    }
                  });
                });
                return best;
              };
              const caretForRuby = (ruby, x, y) => {
                const candidates = rubyBaseNodes(ruby);
                let bestNode = null;
                let bestDistance = Number.POSITIVE_INFINITY;
                candidates.forEach((node) => {
                  const range = document.createRange();
                  range.selectNodeContents(node);
                  Array.from(range.getClientRects()).forEach((rect) => {
                    const distance = pointDistance(rect, x, y);
                    if (distance < bestDistance) {
                      bestNode = node;
                      bestDistance = distance;
                    }
                  });
                });
                const node = bestNode || candidates[0];
                if (!node) return null;
                const resolved = document.createRange();
                resolved.setStart(node, Math.floor(node.data.length / 2));
                resolved.collapse(true);
                return resolved;
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
                const annotation = element?.closest?.('rt,rp') || rubyAnnotationAtPoint(x, y);
                const ruby = annotation?.closest?.('ruby') || element?.closest?.('ruby');
                if (annotation && ruby) {
                  caret = caretForRuby(ruby, x, y) || caret;
                }
                const resolvedElement = caret.startContainer.parentElement;
                if (!resolvedElement || resolvedElement.closest(
                      'a,button,input,textarea,select,option,video,audio,canvas,svg,rt,rp,script,style'
                    )) return null;
                const block = resolvedElement.closest(
                  'p,li,blockquote,dd,dt,figcaption,h1,h2,h3,h4,h5,h6,td,th,pre'
                ) || resolvedElement;
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
          // The Android-side overlay owns quick-tap painting. Suppress the
          // native-selection bridge for this range so the base text is not
          // tinted twice while the ruby annotation tiles are drawn outside
          // the WebView.
          globalThis.__jerreaderQuickSelectionActive = true;
          selection.removeAllRanges();
          selection.addRange(range);
          // Measure one sub-range per text node rather than the sentence range
          // as a whole. `getClientRects()` on the outer range also reports the
          // box of every `ruby` element it crosses, and that box sits on top of
          // the base characters it contains, so painting the raw list stacks
          // the fill and the outline over every annotated word.
          const rects = [];
          const rubyAnnotations = new Set();
          const pairedRubyAnnotation = (node) => {
            const parent = node?.nodeType === Node.TEXT_NODE
              ? node.parentElement
              : node;
            const ruby = parent?.closest?.('ruby');
            if (!ruby) return null;
            let top = node;
            while (top && top.parentNode && top.parentNode !== ruby) {
              top = top.parentNode;
            }
            let sibling = top?.nextSibling;
            while (sibling) {
              if (sibling.nodeType === Node.ELEMENT_NODE) {
                if (sibling.matches('rt')) return sibling;
                if (sibling.matches('rp')) {
                  sibling = sibling.nextSibling;
                  continue;
                }
                break;
              }
              if (sibling.nodeType === Node.TEXT_NODE && sibling.data?.trim()) break;
              sibling = sibling.nextSibling;
            }
            return ruby.querySelector('rt');
          };
          let consumed = 0;
          for (const node of nodes) {
            const nodeStart = consumed;
            consumed += node.data.length;
            const from = Math.max($start, nodeStart);
            const to = Math.min($end, consumed);
            if (to <= from) continue;
            const piece = document.createRange();
            piece.setStart(node, from - nodeStart);
            piece.setEnd(node, to - nodeStart);
            for (const rect of piece.getClientRects()) {
              if (rect.width > 0.5 && rect.height > 0.5) {
                rects.push({x: rect.left, y: rect.top, w: rect.width, h: rect.height});
              }
            }
            // A ruby reading is deliberately not part of the selectable text,
            // but iOS paints the selected kanji and its furigana as one visual
            // unit. Return the annotation's rect too so Android's transparent
            // native selection can be replaced by the same overlay.
            const annotation = pairedRubyAnnotation(node);
            if (annotation && !rubyAnnotations.has(annotation)) {
              rubyAnnotations.add(annotation);
              for (const rect of annotation.getClientRects()) {
                if (rect.width > 0.5 && rect.height > 0.5) {
                  rects.push({x: rect.left, y: rect.top, w: rect.width, h: rect.height});
                }
              }
            }
          }

          const mode = (getComputedStyle(hit.block).writingMode || '').toString();
          const vertical = mode.indexOf('vertical') === 0 || mode === 'tb' || mode === 'tb-rl';

          // Mirror of `ReaderSelectionRectMerger` in the shared module, which
          // owns the algorithm and its tests: one tile per contiguous run per
          // line, so no pixel is ever filled or outlined twice.
          const merge = (input) => {
            const usable = input.filter(r => r.w > 0.5 && r.h > 0.5);
            if (usable.length <= 1) return usable;
            const crossStart = r => vertical ? r.x : r.y;
            const crossEnd = r => vertical ? r.x + r.w : r.y + r.h;
            const runStart = r => vertical ? r.y : r.x;
            const runEnd = r => vertical ? r.y + r.h : r.x + r.w;
            const sharesLine = (a, b) => {
              const overlap = Math.min(crossEnd(a), crossEnd(b)) -
                Math.max(crossStart(a), crossStart(b));
              if (overlap <= 0) return false;
              const shorter = Math.min(
                crossEnd(a) - crossStart(a),
                crossEnd(b) - crossStart(b)
              );
              return shorter > 0 && overlap / shorter >= 0.55;
            };
            const buckets = [];
            usable.slice()
              .sort((a, b) => crossStart(a) - crossStart(b))
              .forEach(rect => {
                const bucket = buckets.find(list => list.some(item => sharesLine(item, rect)));
                if (bucket) bucket.push(rect); else buckets.push([rect]);
              });
            const lines = buckets.map(bucket => {
              const runs = [];
              bucket.map(r => ({start: runStart(r), end: runEnd(r)}))
                .sort((a, b) => a.start - b.start)
                .forEach(run => {
                  const last = runs[runs.length - 1];
                  if (last && run.start <= last.end + 1.5) {
                    last.end = Math.max(last.end, run.end);
                  } else {
                    runs.push({start: run.start, end: run.end});
                  }
                });
              return {
                crossStart: Math.min.apply(null, bucket.map(crossStart)),
                crossEnd: Math.max.apply(null, bucket.map(crossEnd)),
                runs: runs
              };
            }).sort((a, b) => a.crossStart - b.crossStart);
            // A ruby base reserves room for its annotation, so its line reaches
            // into the neighbouring one. Let them meet at the midpoint instead.
            for (let index = 1; index < lines.length; index++) {
              const previous = lines[index - 1];
              const current = lines[index];
              if (current.crossStart >= previous.crossEnd) continue;
              const boundary = (current.crossStart + previous.crossEnd) / 2;
              if (boundary <= previous.crossStart || boundary >= current.crossEnd) continue;
              previous.crossEnd = boundary;
              current.crossStart = boundary;
            }
            const output = [];
            lines.forEach(line => line.runs.forEach(run => {
              output.push(vertical
                ? {
                    x: line.crossStart,
                    y: run.start,
                    w: line.crossEnd - line.crossStart,
                    h: run.end - run.start
                  }
                : {
                    x: run.start,
                    y: line.crossStart,
                    w: run.end - run.start,
                    h: line.crossEnd - line.crossStart
                  });
            }));
            return output;
          };

          const tiles = merge(rects);

          // WebView's stock ::selection is transparent so it cannot tint the
          // <rt> line consistently. Paint the same merged tiles inside the
          // document instead; the overlay follows the CSS client-rect space,
          // covers both kanji and furigana, and stays click-through.
          const quickOverlayId = 'jerreader-quick-selection-highlight';
          document.getElementById(quickOverlayId)?.remove();
          if (tiles.length) {
            const overlay = document.createElement('div');
            overlay.id = quickOverlayId;
            overlay.setAttribute('aria-hidden', 'true');
            // A zero-sized anchor, then measure where it actually landed.
            // `scrollX`/`scrollY` were the wrong correction: in `vertical-rl`
            // the scroll origin sits at the right edge and Readium paginates
            // the columns with its own offset, so adding the window scroll put
            // the tiles a page-column away from the text they belong to.
            // `getBoundingClientRect()` on the anchor reports the viewport
            // position of its own (0, 0), which is exactly the correction the
            // client rects need, whatever the writing mode or pagination does.
            overlay.style.cssText =
              'position:absolute;left:0;top:0;width:0;height:0;' +
              'pointer-events:none;z-index:2147483645;';
            (document.body || document.documentElement)?.appendChild(overlay);
            const origin = overlay.getBoundingClientRect();
            tiles.forEach((tile) => {
              const marker = document.createElement('span');
              marker.style.cssText =
                'position:absolute;pointer-events:none;border-radius:3px;' +
                'box-sizing:border-box;border:0.75px solid $stroke;' +
                'background:$tint;' +
                'left:' + (tile.x - origin.left) + 'px;' +
                'top:' + (tile.y - origin.top) + 'px;' +
                'width:' + tile.w + 'px;height:' + tile.h + 'px;';
              overlay.appendChild(marker);
            });
          }

          // Keep the native range for handles and accessibility, but make its
          // stock paint transparent so the document overlay above is the only
          // visual layer and can include ruby annotations.
          const styleId = 'jerreader-selection-style';
          document.getElementById(styleId)?.remove();
          const style = document.createElement('style');
          style.id = styleId;
          style.textContent =
            '::selection { background: transparent !important; color: inherit !important; }' +
            '::-moz-selection { background: transparent !important; color: inherit !important; }';
          (document.head || document.documentElement).appendChild(style);

          return JSON.stringify({
            ok: selection.toString().trim().length > 0,
            vertical: vertical,
            rects: tiles
          });
        })()
        """.trimIndent()
    }

    /**
     * Makes Android's native WebView selection look like the iOS overlay.
     *
     * WebView's stock blue paint does not include the furigana line because
     * `rt` is intentionally not selectable. The bridge keeps the native
     * handles and action mode, hides only the stock paint, and draws a
     * click-through rectangle for each selected base fragment plus its paired
     * ruby annotation. A selectionchange listener keeps the rectangles aligned
     * while either handle moves.
     */
    private fun nativeSelectionBridgeScript(): String {
        val palette = ReaderSelectionVisualStyle.palette(appearance())
        val fill = palette.fillCss
        val stroke = palette.strokeCss
        return """
        (() => {
          const styleId = 'jerreader-native-selection-style';
          const overlayId = 'jerreader-native-selection-highlight';
          const oldSchedule = globalThis.__jerreaderNativeSelectionSchedule;
          if (oldSchedule) document.removeEventListener('selectionchange', oldSchedule);
          const oldViewportSchedule = globalThis.__jerreaderNativeSelectionViewportSchedule;
          if (oldViewportSchedule) {
            document.removeEventListener('scroll', oldViewportSchedule, true);
            globalThis.removeEventListener('resize', oldViewportSchedule);
          }

          let style = document.getElementById(styleId);
          if (!style) {
            style = document.createElement('style');
            style.id = styleId;
          }
          style.textContent =
            '::selection { background: transparent !important; color: inherit !important; }' +
            '::-moz-selection { background: transparent !important; color: inherit !important; }' +
            'rt, rp { -webkit-user-select: none !important; user-select: none !important;' +
            ' pointer-events: none !important; }';
          (document.head || document.documentElement)?.appendChild(style);

          const clear = () => {
            document.getElementById(overlayId)?.remove();
          };

          const isBaseTextNode = (node) => {
            if (!node || node.nodeType !== Node.TEXT_NODE || !node.data) return false;
            const parent = node.parentElement;
            if (!parent || parent.closest('rt,rp,script,style,noscript,svg')) return false;
            const computed = getComputedStyle(parent);
            return computed.display !== 'none' &&
              computed.visibility !== 'hidden' && computed.opacity !== '0';
          };

          const pairedAnnotation = (node) => {
            const parent = node?.nodeType === Node.TEXT_NODE ? node.parentElement : node;
            const ruby = parent?.closest?.('ruby');
            if (!ruby) return null;
            let top = node;
            while (top && top.parentNode && top.parentNode !== ruby) top = top.parentNode;
            let sibling = top?.nextSibling;
            while (sibling) {
              if (sibling.nodeType === Node.ELEMENT_NODE) {
                if (sibling.matches('rt')) return sibling;
                if (sibling.matches('rp')) {
                  sibling = sibling.nextSibling;
                  continue;
                }
                break;
              }
              if (sibling.nodeType === Node.TEXT_NODE && sibling.data?.trim()) break;
              sibling = sibling.nextSibling;
            }
            return ruby.querySelector('rt');
          };

          const selectedBaseNodes = (range) => {
            const root = document.body || document.documentElement;
            if (!root) return [];
            const nodes = [];
            const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
              acceptNode(node) {
                if (!isBaseTextNode(node)) return NodeFilter.FILTER_REJECT;
                try {
                  return range.intersectsNode(node)
                    ? NodeFilter.FILTER_ACCEPT
                    : NodeFilter.FILTER_REJECT;
                } catch (_) {
                  return NodeFilter.FILTER_REJECT;
                }
              }
            });
            while (walker.nextNode()) nodes.push(walker.currentNode);
            return nodes;
          };

          // WebView can leave the native range on an <rt> node even though the
          // annotation is user-select:none.  Recover the corresponding base
          // text for that transient range so a long press on furigana still
          // paints and translates the kanji, matching the iOS ruby policy.
          const rubyBaseNodes = (annotation) => {
            const ruby = annotation?.closest?.('ruby');
            if (!ruby) return [];
            const children = Array.from(ruby.childNodes);
            const directChild = children.find((child) =>
              child === annotation || child.contains?.(annotation)
            );
            const annotationIndex = children.indexOf(directChild);
            let segmentStart = 0;
            for (let index = annotationIndex - 1; index >= 0; index -= 1) {
              const child = children[index];
              const element = child.nodeType === Node.ELEMENT_NODE ? child : null;
              if (element?.matches?.('rt,rp') || element?.querySelector?.('rt,rp')) {
                segmentStart = index + 1;
                break;
              }
            }
            const roots = annotationIndex > 0
              ? children.slice(segmentStart, annotationIndex)
              : children;
            const nodes = [];
            roots.forEach((rootNode) => {
              if (rootNode.nodeType === Node.TEXT_NODE) {
                if (rootNode.data?.length) nodes.push(rootNode);
                return;
              }
              const walker = document.createTreeWalker(rootNode, NodeFilter.SHOW_TEXT, {
                acceptNode(node) {
                  return node.parentElement?.closest?.('rt,rp')
                    ? NodeFilter.FILTER_REJECT
                    : NodeFilter.FILTER_ACCEPT;
                }
              });
              while (walker.nextNode()) {
                if (walker.currentNode.data?.length) nodes.push(walker.currentNode);
              }
            });
            return nodes;
          };

          const selectionAnnotations = (selection, range) => {
            const annotations = new Set();
            [selection?.anchorNode, selection?.focusNode].forEach((node) => {
              const element = node?.nodeType === Node.ELEMENT_NODE
                ? node
                : node?.parentElement;
              const annotation = element?.closest?.('rt,rp');
              if (annotation) annotations.add(annotation);
            });
            if (!annotations.size) {
              document.querySelectorAll('rt,rp').forEach((annotation) => {
                try {
                  if (range.intersectsNode(annotation)) annotations.add(annotation);
                } catch (_) {}
              });
            }
            return annotations;
          };

          const selectionRects = (selection) => {
            if (!selection || !selection.rangeCount || selection.isCollapsed) return [];
            const range = selection.getRangeAt(0).cloneRange();
            const annotations = selectionAnnotations(selection, range);
            const rects = [];
            const nodes = selectedBaseNodes(range);
            if (!nodes.length) {
              annotations.forEach((annotation) => {
                rubyBaseNodes(annotation).forEach((node) => {
                  if (!nodes.includes(node)) nodes.push(node);
                });
              });
            }
            nodes.forEach((node) => {
              const start = range.startContainer === node ? range.startOffset : 0;
              const end = range.endContainer === node ? range.endOffset : node.data.length;
              const effectiveStart = end > start ? start : 0;
              const effectiveEnd = end > start ? end : node.data.length;
              if (effectiveEnd <= effectiveStart) return;
              const piece = document.createRange();
              piece.setStart(node, effectiveStart);
              piece.setEnd(node, effectiveEnd);
              Array.from(piece.getClientRects()).forEach((rect) => {
                if (rect.width > 0.5 && rect.height > 0.5) rects.push(rect);
              });
              const annotation = pairedAnnotation(node);
              if (annotation && !annotations.has(annotation)) {
                annotations.add(annotation);
              }
            });
            // Include a reading rect even when the native range only exposed
            // the base node; iOS paints the paired annotation as one unit.
            annotations.forEach((annotation) => {
              Array.from(annotation.getClientRects()).forEach((rect) => {
                if (rect.width > 0.5 && rect.height > 0.5) rects.push(rect);
              });
            });
            return rects;
          };

          const render = () => {
            if (globalThis.__jerreaderQuickSelectionActive) {
              clear();
              return;
            }
            const selection = globalThis.getSelection?.();
            const rects = selectionRects(selection);
            if (!rects.length) {
              clear();
              return;
            }
            let overlay = document.getElementById(overlayId);
            if (!overlay) {
              overlay = document.createElement('div');
              overlay.id = overlayId;
              overlay.setAttribute('aria-hidden', 'true');
              // Zero-sized anchor; the tiles are placed relative to where it
              // actually lands rather than by adding the window scroll. See the
              // quick-tap overlay for why the scroll offset is wrong in
              // `vertical-rl`.
              overlay.style.cssText =
                'position:absolute;left:0;top:0;width:0;height:0;' +
                'pointer-events:none;z-index:2147483646;';
              (document.body || document.documentElement)?.appendChild(overlay);
            }
            overlay.replaceChildren();
            const origin = overlay.getBoundingClientRect();
            rects.forEach((rect) => {
              const marker = document.createElement('span');
              marker.style.cssText =
                'position:absolute;pointer-events:none;border-radius:3px;' +
                'box-sizing:border-box;border:0.75px solid $stroke;' +
                'background:$fill;' +
                'left:' + (rect.left - origin.left) + 'px;' +
                'top:' + (rect.top - origin.top) + 'px;' +
                'width:' + rect.width + 'px;height:' + rect.height + 'px;';
              overlay.appendChild(marker);
            });
          };

          let pending = false;
          const schedule = () => {
            if (pending) return;
            pending = true;
            globalThis.requestAnimationFrame?.(() => {
              pending = false;
              render();
            }) || render();
          };
          globalThis.__jerreaderNativeSelectionSchedule = schedule;
          globalThis.__jerreaderNativeSelectionViewportSchedule = schedule;
          document.addEventListener('selectionchange', schedule);
          document.addEventListener('scroll', schedule, true);
          globalThis.addEventListener('resize', schedule);
          schedule();
          return true;
        })()
        """.trimIndent()
    }

    /**
     * Drops the highlight by clearing the selection that carries it and the
     * rule that tints it, so the page keeps its own colour for any selection
     * the user makes by hand afterwards.
     */
    private fun clearHighlightScript(): String = """
        (() => {
          globalThis.__jerreaderQuickSelectionActive = false;
          document.getElementById('jerreader-selection-style')?.remove();
          document.getElementById('jerreader-quick-selection-highlight')?.remove();
          document.getElementById('jerreader-native-selection-highlight')?.remove();
          window.getSelection()?.removeAllRanges();
          return true;
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
        if (onShortTapWordRequested != null && isSingleWord(sourceText)) {
            // The word lookup card takes over this selection. Invalidate any
            // previous translation identity so a late retry cannot resurrect
            // the dismissed translation card.
            requestToken++
            lastRequest = null
            currentIdentity = null
            currentState = null
            onShortTapWordRequested.invoke()
            return null
        }
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

    private fun isSingleWord(text: String): Boolean {
        val value = text.trim()
        return value.length in 1..32 &&
            !value.any(Char::isWhitespace) &&
            value.all { it.isLetter() || it == '\'' || it == '-' }
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
        if (manualRetryAt.size > RETRY_KEY_LIMIT) {
            manualRetryAt.minByOrNull { it.value }?.key?.let(manualRetryAt::remove)
        }
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
        append(settings.cacheNamespace())
        append('|')
        append(translationService.identifier)
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
            // The service reaches disk and the network — ML Kit blocks the
            // calling thread while it prepares a model — so it must not run on
            // the main dispatcher, or the reader stops answering input.
            val result = withTimeout(TRANSLATION_TIMEOUT_MILLIS) {
                withContext(Dispatchers.IO) { translationService.translate(request) }
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
        const val RETRY_KEY_LIMIT = 128
    }
}
