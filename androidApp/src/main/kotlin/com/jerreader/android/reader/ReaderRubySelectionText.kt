package com.jerreader.android.reader

import org.json.JSONObject
import org.json.JSONTokener

/**
 * The selected text without ruby readings, and the rest of its paragraph
 * (2.1.0).
 *
 * Readium reports `Selection.toString()`. Android WebView includes the `<rt>`
 * text in it, although readings are not selectable, so a tap on 扉(とびら)
 * used to translate 「扉とびら」. Readium's text before and after the
 * selection carries the readings too, and runs on into other paragraphs.
 * iOS builds its selected text from base-text sub-ranges for the same reason
 * (`jerreaderReaderBaseSelectionSnapshot` in EPUBReaderScripts), maps a
 * selection that only covers readings to the kanji they annotate, and takes
 * the text around it from the base text of the selection's paragraph. This
 * does the same on Android. It reads the page and changes nothing: neither
 * the document nor the browser-owned selection and its handles.
 */
internal object ReaderRubySelectionText {

    /**
     * The page's view of the selection. [text] is null while the selection
     * crosses no ruby annotation, because Readium's text is already right
     * then; [before] and [after] are null when the selection does not start
     * and end in one paragraph, as on iOS.
     */
    data class Page(val text: String?, val before: String?, val after: String?)

    /** `{"text", "before", "after"}` for a selection, `null` without one. */
    fun script(): String = """
        (() => {
          const selection = window.getSelection();
          if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return null;
          const range = selection.getRangeAt(0);
          const intersects = (node) => {
            try { return range.intersectsNode(node); } catch (_) { return false; }
          };
          const isBase = (node) => {
            const parent = node.parentElement;
            return !!node.data && !!parent && !parent.closest('rt,rp,script,style,noscript');
          };
          // The part of a text node inside the range, as [start, end).
          const part = (node) => [
            node === range.startContainer ? Math.min(range.startOffset, node.data.length) : 0,
            node === range.endContainer ? Math.min(range.endOffset, node.data.length) : node.data.length
          ];
          const annotations = Array.from(document.querySelectorAll('rt,rp')).filter(intersects);
          [selection.anchorNode, selection.focusNode].forEach((node) => {
            const element = node && node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
            const annotation = element?.closest?.('rt,rp');
            if (annotation && !annotations.includes(annotation)) annotations.push(annotation);
          });

          // The base text of the selection while it crosses a reading; base text
          // nodes taken whole are the kanji of a selection of readings only.
          let text = null;
          const whole = new Set();
          if (annotations.length) {
            text = '';
            const common = range.commonAncestorContainer;
            const root = common.nodeType === Node.TEXT_NODE ? common.parentNode : common;
            const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
            for (let node = walker.nextNode(); node; node = walker.nextNode()) {
              if (!isBase(node) || !intersects(node)) continue;
              const [start, end] = part(node);
              if (end > start) text += node.data.slice(start, end);
            }
            if (!text.trim()) {
              // Only readings are selected: use the base text they annotate.
              const rubies = [];
              annotations.forEach((annotation) => {
                const ruby = annotation.closest('ruby');
                if (ruby && !rubies.includes(ruby)) rubies.push(ruby);
              });
              text = rubies.map((ruby) => {
                let base = '';
                const inner = document.createTreeWalker(ruby, NodeFilter.SHOW_TEXT);
                for (let node = inner.nextNode(); node; node = inner.nextNode()) {
                  if (isBase(node)) { base += node.data; whole.add(node); }
                }
                return base;
              }).join('');
            }
            text = text.replace(/\s+/g, ' ').trim() || null;
          }

          // The rest of the selection's paragraph, from base text only, found as
          // iOS finds it (jerreaderReaderBlock): the context sent with the request
          // carries no readings and no other paragraph.
          const elementOf = (node) => !node ? null
            : node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
          ${ReaderBlockScript.FUNCTION}
          let before = null;
          let after = null;
          const block = readerBlockOf(elementOf(range.startContainer));
          const endElement = elementOf(range.endContainer);
          if (block && endElement && (endElement === block || block.contains(endElement))) {
            let paragraph = '';
            let start = null;
            let end = null;
            const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
            for (let node = walker.nextNode(); node; node = walker.nextNode()) {
              if (!isBase(node)) continue;
              const [from, to] = whole.has(node) ? [0, node.data.length]
                : intersects(node) ? part(node) : [0, 0];
              if (to > from) {
                if (start === null) start = paragraph.length + from;
                end = paragraph.length + to;
              }
              paragraph += node.data;
            }
            if (start !== null) {
              before = paragraph.slice(0, start);
              after = paragraph.slice(end);
            }
          }
          if (text === null && before === null && after === null) return null;
          return JSON.stringify({text: text, before: before, after: after});
        })()
    """.trimIndent()

    /** [script]'s result as `evaluateJavascript` hands it back. */
    fun decode(raw: String?): Page? {
        if (raw == null || raw == "null") return null
        val json = runCatching {
            when (val value = JSONTokener(raw).nextValue()) {
                is JSONObject -> value
                // evaluateJavascript hands back a JSON.stringify result as a quoted string.
                is String -> JSONObject(value)
                else -> null
            }
        }.getOrNull() ?: return null
        val page = Page(
            text = json.stringOrNull("text")?.takeIf(String::isNotBlank),
            before = json.stringOrNull("before"),
            after = json.stringOrNull("after")
        )
        return page.takeUnless { it.text == null && it.before == null && it.after == null }
    }

    suspend fun read(evaluate: suspend (String) -> String?): Page? = decode(evaluate(script()))

    private fun JSONObject.stringOrNull(name: String): String? = if (isNull(name)) null else optString(name)
}
