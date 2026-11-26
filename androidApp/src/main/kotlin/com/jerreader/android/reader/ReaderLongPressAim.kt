package com.jerreader.android.reader

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.WebView
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Puts a long press on an EPUB page onto the glyph under the finger before
 * WebView turns it into a selection (2.1.0).
 *
 * WebView selects the word at the caret nearest the touch. On the second
 * half of a glyph that caret sits after the glyph, and where a word ends
 * there Chromium takes the word that starts at it: a long press on the end
 * of 昨日 selected ご飯. iOS corrects its selection from script
 * (`longPressWordGuardScript`). On Android a selection changed from script
 * loses WebView's handles and its 翻译/查词 menu, so the press itself is
 * moved instead. As a finger comes down on a page, the page is asked where
 * it is. When it is on the second half of a glyph, the touch so far is
 * cancelled and starts again a quarter of the way into the same glyph, and
 * the rest of the gesture moves with it. WebView then takes the word that
 * holds the glyph. A press in a ruby's blank room (between its kanji, or
 * beside them under a longer reading) is moved onto the ruby's nearest kanji
 * the same way.
 *
 * The page answers while the finger is still down, well before the
 * long-press timeout. Only a still finger is moved, and only before that
 * timeout: a drag or a swipe is left as it is, and a tap lands on the same
 * glyph either way. Readium puts no touch listener on its page WebViews
 * (its own sits on the page's container), so this one replaces nothing.
 * It never consumes an event, so WebView's own clicks and accessibility
 * actions are untouched.
 */
@SuppressLint("ClickableViewAccessibility")
internal class ReaderLongPressAim(private val enabled: () -> Boolean) : View.OnTouchListener {
    private val attached: MutableSet<WebView> = Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())
    private var gesture: Gesture? = null
    private var replaying = false

    /** Presses moved so far, for tests. */
    var aimedPresses = 0
        private set

    /**
     * What became of the last press, for tests: "aimed", "kept" (already on
     * a glyph's first half, or not on text), "late" (the page answered after
     * the deadline), "moved" or "lifted" (before the page answered).
     */
    var lastOutcome = "none"
        private set

    private class Gesture(
        val webView: WebView,
        val down: MotionEvent,
        val slop: Float,
        val deadline: Long
    ) {
        /** The page has answered, or the finger moved or lifted first. */
        var settled = false
        var shifted = false
        var shiftX = 0f
        var shiftY = 0f
    }

    /** Listens on every page WebView under [root] that it is not on yet. */
    fun attach(root: View?) {
        fun visit(view: View) {
            if (view is WebView) {
                if (attached.add(view)) view.setOnTouchListener(this)
                return
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        root?.let(::visit)
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (replaying || view !is WebView) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            end()
            if (event.pointerCount == 1 && view.width > 0 && enabled()) start(view, event)
            return false
        }
        val current = gesture?.takeIf { it.webView === view } ?: return false
        if (current.shifted) {
            // WebView already has the moved touch; keep the rest in line with it.
            event.offsetLocation(current.shiftX, current.shiftY)
        } else if (!current.settled &&
            (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || moved(current, event))
        ) {
            current.settled = true
            lastOutcome = "moved"
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (!current.settled) lastOutcome = "lifted"
            end()
        }
        return false
    }

    private fun start(webView: WebView, event: MotionEvent) {
        val current = Gesture(
            webView = webView,
            down = MotionEvent.obtain(event),
            slop = ViewConfiguration.get(webView.context).scaledTouchSlop.toFloat(),
            deadline = event.eventTime + ViewConfiguration.getLongPressTimeout() - TIMEOUT_MARGIN_MILLIS
        )
        gesture = current
        webView.evaluateJavascript(aimScript(event.x, event.y, webView.width)) { raw -> answer(current, raw) }
    }

    private fun answer(current: Gesture, raw: String?) {
        if (gesture !== current || current.settled) return
        current.settled = true
        val shift = decodeShift(raw)
        if (shift == null) {
            lastOutcome = "kept"
            return
        }
        if (SystemClock.uptimeMillis() > current.deadline || !current.webView.isAttachedToWindow) {
            lastOutcome = "late"
            return
        }
        // Both carry the first touch's times, so the stream WebView sees
        // stays in order: down, cancel, down again, then the finger's own
        // later events.
        val cancel = MotionEvent.obtain(current.down).apply { action = MotionEvent.ACTION_CANCEL }
        val down = MotionEvent.obtain(current.down).apply { offsetLocation(shift.first, shift.second) }
        replaying = true
        try {
            current.webView.dispatchTouchEvent(cancel)
            current.webView.dispatchTouchEvent(down)
        } finally {
            replaying = false
            cancel.recycle()
            down.recycle()
        }
        current.shiftX = shift.first
        current.shiftY = shift.second
        current.shifted = true
        aimedPresses += 1
        lastOutcome = "aimed"
    }

    private fun moved(current: Gesture, event: MotionEvent): Boolean {
        val index = event.findPointerIndex(current.down.getPointerId(0))
        if (index < 0) return true
        return abs(event.getX(index) - current.down.x) > current.slop ||
            abs(event.getY(index) - current.down.y) > current.slop
    }

    private fun end() {
        gesture?.down?.recycle()
        gesture = null
    }

    internal companion object {
        /** How long before WebView's long-press timeout a press may still be moved. */
        private const val TIMEOUT_MARGIN_MILLIS = 60L

        /** No glyph is this wide on screen; a larger shift is a bad answer. */
        private const val MAX_SHIFT_PX = 400f

        /** The page's answer as (dx, dy) in the WebView's pixels, or null to leave the press. */
        fun decodeShift(raw: String?): Pair<Float, Float>? {
            if (raw == null || raw == "null") return null
            val json = when (val decoded = runCatching { JSONTokener(raw).nextValue() }.getOrNull()) {
                is JSONObject -> decoded
                // evaluateJavascript hands back a JSON.stringify result as a quoted string.
                is String -> runCatching { JSONObject(decoded) }.getOrNull()
                else -> null
            } ?: return null
            val dx = json.optDouble("dx", Double.NaN).toFloat()
            val dy = json.optDouble("dy", Double.NaN).toFloat()
            if (!dx.isFinite() || !dy.isFinite()) return null
            if (abs(dx) < 0.5f && abs(dy) < 0.5f) return null
            if (abs(dx) > MAX_SHIFT_PX || abs(dy) > MAX_SHIFT_PX) return null
            return dx to dy
        }

        /**
         * Asks the page whether the point ([x], [y] in the WebView's pixels)
         * is on the second half of a glyph of base text, along the line. If
         * so, it answers how far to move the point, in the WebView's pixels,
         * to a quarter of the way into the same glyph; otherwise null.
         *
         * A point in a ruby's blank room (between its kanji, or beside them
         * under a reading longer than they are, the part of that reading over
         * the room included) is moved a quarter of the way into the ruby's
         * nearest kanji on the point's line, the earlier one on a tie, and
         * onto that line. There WebView selected nothing, or a word after the
         * ruby (a real book's run on 11-24); iOS counts such a press as that
         * kanji too (longPressWordGuardScript).
         *
         * The page looks in this order: the glyph at the caret, when the
         * point is on it; the room of a ruby the point is in, or a kanji of
         * it the point is on and the caret missed; the glyph at the caret,
         * when the point is along it and within one glyph across (between
         * lines); the nearest kanji of a ruby at the point or next to the
         * caret, within one glyph either way. Rubies are found from
         * the hit, from the caret and by where they are on the page, since
         * engines disagree on what a point in a ruby's room hits and where
         * its caret lands: on 11-25 the emulator's WebView (Chrome 124) left
         * such a press in a real book as it was, where the synthetic page
         * was moved.
         *
         * It reads the page and changes nothing. Links and form controls
         * under the point are left to WebView, as are right-to-left lines.
         * With [trace] (tests only) every answer, null included, comes back
         * as JSON that says which way it went, in tags and numbers, never
         * the page's text.
         */
        fun aimScript(x: Float, y: Float, widthPx: Int, trace: Boolean = false): String = """
            (() => {
              const facts = {};
              const exit = (why) => ${if (trace) "JSON.stringify(Object.assign({ exit: why }, facts))" else "null"};
              const scale = $widthPx / Math.max(Number(window.innerWidth) || 0, 1);
              if (!(scale > 0) || typeof document.caretRangeFromPoint !== 'function') return exit('no-caret');
              const x = $x / scale;
              const y = $y / scale;
              const caret = document.caretRangeFromPoint(x, y);
              const node = caret && caret.startContainer;
              const onText = !!node && node.nodeType === Node.TEXT_NODE;
              const unsafe = 'a,button,input,textarea,select,option,video,audio';
              const isVertical = (style) => /^(vertical|sideways|tb)/i.test(
                (style.writingMode || style.webkitWritingMode || '').toString()
              );
              const isBase = (text) => !!text && text.nodeType === Node.TEXT_NODE && !!text.data &&
                !!text.parentElement && !text.parentElement.closest('rt,rp');
              // One glyph of base text; a surrogate pair stays whole.
              const glyphAt = (text, offset) => {
                if (!isBase(text) || offset < 0 || offset >= text.data.length) return null;
                let start = offset;
                const unit = text.data.charCodeAt(start);
                if (unit >= 0xDC00 && unit <= 0xDFFF && start > 0) start -= 1;
                const lead = text.data.charCodeAt(start);
                const end = lead >= 0xD800 && lead <= 0xDBFF && start + 1 < text.data.length
                  ? start + 2
                  : start + 1;
                return { text, start, end };
              };
              const blank = (glyph) => /^\s+$/.test(glyph.text.data.slice(glyph.start, glyph.end));
              // The caret just after a node's last glyph can come back as the
              // start of the next text node, a reading's included.
              const lastGlyphBefore = (text) => {
                const walker = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT);
                walker.currentNode = text;
                let previous = walker.previousNode();
                for (let step = 0; previous && step < 4; step += 1, previous = walker.previousNode()) {
                  if (isBase(previous)) return glyphAt(previous, previous.data.length - 1);
                }
                return null;
              };
              // A box's extent along the line (from, to) and across it (low, high).
              const extent = (rect, vertical) => vertical
                ? { from: rect.top, to: rect.bottom, low: rect.left, high: rect.right }
                : { from: rect.left, to: rect.right, low: rect.top, high: rect.bottom };
              const boxes = (glyph, vertical) => {
                const range = document.createRange();
                range.setStart(glyph.text, glyph.start);
                range.setEnd(glyph.text, glyph.end);
                return Array.from(range.getClientRects())
                  .filter((rect) => rect.width > 0.5 && rect.height > 0.5)
                  .map((rect) => extent(rect, vertical));
              };
              const answer = (vertical, along, across, via) => {
                const shift = vertical
                  ? { dx: across * scale, dy: along * scale }
                  : { dx: along * scale, dy: across * scale };
                ${if (trace) "Object.assign(shift, { via: via }, facts);" else ""}
                return JSON.stringify(shift);
              };

              // The glyphs either side of the caret, the one after it first,
              // and where the point is to each: on it, or along it and within
              // one glyph across (between lines).
              const candidates = (!onText ? [] : [
                glyphAt(node, caret.startOffset),
                caret.startOffset > 0 ? glyphAt(node, caret.startOffset - 1) : lastGlyphBefore(node)
              ]).filter((glyph) => glyph && !blank(glyph));
              facts.caret = !node ? 'none' : !onText ? 'element' : isBase(node) ? 'base'
                : node.parentElement && node.parentElement.closest('rt,rp') ? 'reading' : 'other';
              facts.candidates = candidates.length;
              let on = null;
              let near = null;
              for (const glyph of candidates) {
                const style = getComputedStyle(glyph.text.parentElement);
                const vertical = isVertical(style);
                const along = vertical ? y : x;
                const across = vertical ? x : y;
                for (const box of boxes(glyph, vertical)) {
                  if (along < box.from || along > box.to) continue;
                  const depth = box.high - box.low;
                  if (across >= box.low && across <= box.high) {
                    if (!on) on = { glyph, style, vertical, box };
                  } else if (across >= box.low - depth && across <= box.high + depth) {
                    if (!near) near = { glyph, style, vertical, box };
                  }
                }
              }
              facts.on = !!on;
              facts.near = !!near;
              // On a glyph's first half the caret is before it, and WebView
              // already takes the word that holds it.
              const onGlyph = (item, via) => {
                if (item.glyph.text.parentElement.closest(unsafe)) return exit(via + '-unsafe');
                if (item.style.direction === 'rtl') return exit(via + '-rtl');
                const along = item.vertical ? y : x;
                const size = item.box.to - item.box.from;
                if (along <= item.box.from + size / 2) return exit(via + '-first-half');
                return answer(item.vertical, item.box.from + size / 4 - along, 0, via);
              };
              if (on) return onGlyph(on, 'glyph');

              // A ruby's kanji by line. Each line has its room: the box its
              // kanji and the readings over them fill.
              const layouts = new Map();
              const layoutOf = (ruby) => {
                if (layouts.has(ruby)) return layouts.get(ruby);
                const style = getComputedStyle(ruby);
                const vertical = isVertical(style);
                const lines = [];
                const walker = document.createTreeWalker(ruby, NodeFilter.SHOW_TEXT);
                while (walker.nextNode()) {
                  const text = walker.currentNode;
                  if (!isBase(text)) continue;
                  for (let offset = 0; offset < text.data.length;) {
                    const glyph = glyphAt(text, offset);
                    offset = glyph.end;
                    if (blank(glyph)) continue;
                    for (const box of boxes(glyph, vertical)) {
                      let line = lines.find((item) => box.low < item.high - 0.5 && box.high > item.low + 0.5);
                      if (!line) {
                        line = { glyphs: [], from: box.from, to: box.to, low: box.low, high: box.high };
                        lines.push(line);
                      }
                      line.glyphs.push(box);
                      line.from = Math.min(line.from, box.from);
                      line.to = Math.max(line.to, box.to);
                      line.low = Math.min(line.low, box.low);
                      line.high = Math.max(line.high, box.high);
                    }
                  }
                }
                const readings = [];
                for (const rt of Array.from(ruby.getElementsByTagName('rt'))) {
                  for (const rect of Array.from(rt.getClientRects())) {
                    if (rect.width > 0.5 && rect.height > 0.5) readings.push(extent(rect, vertical));
                  }
                }
                for (const line of lines) {
                  const depth = line.high - line.low;
                  const room = { from: line.from, to: line.to, low: line.low, high: line.high };
                  for (const reading of readings) {
                    // Over these kanji: along, overlapping them; across, next to them.
                    if (reading.to <= line.from || reading.from >= line.to) continue;
                    if (reading.high < line.low - depth || reading.low > line.high + depth) continue;
                    room.from = Math.min(room.from, reading.from);
                    room.to = Math.max(room.to, reading.to);
                    room.low = Math.min(room.low, reading.low);
                    room.high = Math.max(room.high, reading.high);
                  }
                  line.room = room;
                }
                const layout = { vertical, skip: !!ruby.closest(unsafe) || style.direction === 'rtl', lines };
                layouts.set(ruby, layout);
                return layout;
              };
              const holds = (room, vertical) => {
                const along = vertical ? y : x;
                const across = vertical ? x : y;
                return along >= room.from && along <= room.to && across >= room.low && across <= room.high;
              };

              // The rubies at the point or next to the caret. Engines disagree
              // on what a point in a ruby's room hits: the ruby, its block with
              // the caret in the reading, or the text beside the ruby with the
              // caret at its edge.
              const hit = document.elementFromPoint(x, y);
              facts.hit = hit ? hit.tagName.toLowerCase() : 'none';
              const rubies = [];
              const consider = (item) => {
                const ruby = item && item.nodeType === Node.ELEMENT_NODE ? item.closest('ruby') : null;
                if (ruby && !rubies.includes(ruby)) rubies.push(ruby);
              };
              consider(hit);
              if (onText) {
                consider(node.parentElement);
                if (caret.startOffset === 0) consider(node.previousSibling);
                if (caret.startOffset >= node.data.length) consider(node.nextSibling);
              } else if (node) {
                consider(node);
                consider(node.childNodes[caret.startOffset - 1]);
                consider(node.childNodes[caret.startOffset]);
              }
              facts.adjacent = rubies.length;
              // And every ruby whose room holds the point, in the blocks of the
              // hit and of the caret (the body without either).
              const blocks = 'p,li,dd,dt,td,th,blockquote,figcaption,h1,h2,h3,h4,h5,h6,div,section,article,body';
              const scopes = [];
              for (const item of [hit, node]) {
                const element = !item ? null : item.nodeType === Node.ELEMENT_NODE ? item : item.parentElement;
                const block = element ? element.closest(blocks) : null;
                if (!block || scopes.some((scope) => scope.contains(block))) continue;
                for (let index = scopes.length - 1; index >= 0; index -= 1) {
                  if (block.contains(scopes[index])) scopes.splice(index, 1);
                }
                scopes.push(block);
              }
              if (!scopes.length && document.body) scopes.push(document.body);
              const em = parseFloat(getComputedStyle(scopes[0] || document.documentElement).fontSize) || 16;
              let scanned = 0;
              for (const scope of scopes) {
                const all = scope.getElementsByTagName('ruby');
                for (let index = 0; index < all.length && scanned < 5000; index += 1) {
                  const ruby = all[index];
                  if (rubies.includes(ruby)) continue;
                  scanned += 1;
                  const box = ruby.getBoundingClientRect();
                  if (x < box.left - 2 * em || x > box.right + 2 * em || y < box.top - 2 * em || y > box.bottom + 2 * em) continue;
                  const layout = layoutOf(ruby);
                  if (layout.lines.some((line) => holds(line.room, layout.vertical))) rubies.push(ruby);
                }
              }
              facts.placed = rubies.length - facts.adjacent;
              rubies.sort((a, b) => a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1);

              // Their kanji nearest the point: on the point's line first, then
              // along it, the earlier one on a tie; within one glyph both ways.
              // With inRoom, only lines whose room holds the point.
              const nearest = (inRoom) => {
                let best = null;
                for (const ruby of rubies) {
                  const layout = layoutOf(ruby);
                  if (layout.skip) continue;
                  const along = layout.vertical ? y : x;
                  const across = layout.vertical ? x : y;
                  for (const line of layout.lines) {
                    if (inRoom && !holds(line.room, layout.vertical)) continue;
                    for (const box of line.glyphs) {
                      const gap = Math.max(box.low - across, across - box.high, 0);
                      const distance = Math.max(box.from - along, along - box.to, 0);
                      if (gap > box.high - box.low || distance > box.to - box.from) continue;
                      if (best && !(gap < best.gap - 0.5 || (gap <= best.gap + 0.5 && distance < best.distance - 0.5))) continue;
                      best = { box, vertical: layout.vertical, gap, distance };
                    }
                  }
                }
                return best;
              };
              // A quarter of the way into the kanji, and onto its line.
              const moveOnto = (best, via) => {
                const along = best.vertical ? y : x;
                const across = best.vertical ? x : y;
                const box = best.box;
                facts.gap = Math.round(best.gap * 100) / 100;
                facts.distance = Math.round(best.distance * 100) / 100;
                const shiftAcross = across >= box.low && across <= box.high ? 0 : (box.low + box.high) / 2 - across;
                return answer(best.vertical, box.from + (box.to - box.from) / 4 - along, shiftAcross, via);
              };
              // In a ruby's room: between its kanji, or beside them under a
              // longer reading, that reading included; not over a kanji. On a
              // kanji the caret missed (it landed on another line, say), the
              // press goes onto that kanji the same way.
              const room = nearest(true);
              if (room && room.distance > 0) return moveOnto(room, 'room');
              if (room && room.gap === 0) return moveOnto(room, 'kanji');
              if (near) return onGlyph(near, 'near');
              // Next to a ruby at the point or the caret, or over one of its kanji.
              const ruby = nearest(false);
              if (ruby) return moveOnto(ruby, 'ruby');
              return exit('nothing');
            })()
        """.trimIndent()
    }
}
