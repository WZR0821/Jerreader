package com.jerreader.unified.reader.selection

/**
 * The JavaScript both readers inject into their Readium web view.
 *
 * This is the part that used to diverge hardest. iOS and Android each carried
 * their own copy of the DOM probe, and because the copies were edited
 * independently the two apps disagreed about what "the selected sentence" even
 * *was* on a ruby-annotated page — different text sent to translation, and a
 * different set of rectangles to highlight. Generating the script from one
 * Kotlin object means a fix to the probe lands on both platforms at once.
 *
 * Conventions the native side relies on:
 *  - every entry point returns a JSON **string**, or the literal `null`;
 *  - rectangles are CSS pixels in client (viewport) space, keys `x`,`y`,`w`,`h`;
 *  - `rt`/`rp` ruby annotations are excluded from text but their boxes are
 *    reported for painting, so a highlighted kanji and its furigana read as one
 *    shape.
 */
object ReaderSelectionScripts {

    /** Element ids the injected DOM uses, exposed for teardown and for tests. */
    const val QUICK_OVERLAY_ID = "jerreader-quick-selection-highlight"
    const val NATIVE_OVERLAY_ID = "jerreader-native-selection-highlight"
    const val SELECTION_STYLE_ID = "jerreader-selection-style"

    /**
     * How far, in CSS pixels, a tap may land from the glyph it resolves to
     * before the reader treats it as a tap on blank space.
     *
     * `caretRangeFromPoint` never misses. Tap the empty half of a short last
     * line, the gutter beside a column, or the space under the final paragraph
     * of a chapter, and it still hands back the nearest caret — so every one of
     * those taps translated a sentence the reader never pointed at. iOS gated
     * this from the start by probing the character under the caret and
     * requiring the touch to be within 28px of its box; Android's copy of the
     * probe never grew the check, which is the whole of the "点击空白处误触翻译"
     * report. 28px is roughly one line height at default size: forgiving enough
     * that aiming between two lines still works, tight enough that the margin
     * is dead space.
     */
    const val TAP_PROXIMITY_CSS_PIXELS = 28.0

    /**
     * [TAP_PROXIMITY_CSS_PIXELS] squared, so injected scripts can compare
     * against a squared distance and skip the square root.
     */
    const val TAP_PROXIMITY_SQUARED = TAP_PROXIMITY_CSS_PIXELS * TAP_PROXIMITY_CSS_PIXELS

    /**
     * How far outside the tapped paragraph's own box, in CSS pixels, a tap may
     * still count as a tap on that paragraph.
     *
     * [TAP_PROXIMITY_CSS_PIXELS] alone measures to the nearest glyph, and above
     * the first line of a chapter the nearest glyph is the first line — so a
     * tap in the page's top margin translated the opening sentence, reported as
     * 「按最上面一段的稍微上边一点的空白的时候，会直接点到下面的句子」. The gap
     * between two paragraphs reads the same way. Inside a paragraph nothing
     * changes: the space between two lines is still within the block, so aiming
     * between lines keeps working. Small enough that a tap that grazes the
     * first line's ascender is not thrown away.
     */
    const val TAP_BLOCK_MARGIN_CSS_PIXELS = 6.0

    /**
     * Shared helpers every entry point below is built from: ruby-aware text
     * walking, hit testing that resolves a tap on furigana back onto its base
     * characters, and writing-mode detection.
     *
     * Public because the Android reader still assembles one script of its own —
     * `selectRangeScript` there has to measure and paint inside a single
     * `evaluateJavascript`, which the two-call shared flow does not express. It
     * splices these helpers in rather than keeping the private copy it used to
     * carry: that copy is how the tap-proximity gate and the `!important` fill
     * shipped on one platform and not the other.
     */
    val helpers: String = """
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
        // A tap on furigana should translate the word underneath it, so an
        // annotation within ~20px of the touch claims the hit.
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
        // Is the touch actually on the glyph the caret resolved to, or did
        // `caretRangeFromPoint` reach across empty space to find it? Probing one
        // character and measuring back to the touch is what separates "tapped a
        // sentence" from "tapped the margin"; without it the reader translates
        // on every tap anywhere on the page. See TAP_PROXIMITY_CSS_PIXELS.
        const caretIsNearPoint = (caret, x, y) => {
          const node = caret?.startContainer;
          if (!node || node.nodeType !== Node.TEXT_NODE || !node.data?.length) return false;
          let offset = Math.min(caret.startOffset, node.data.length);
          // A caret sitting past the last character has no glyph of its own;
          // step back one so the probe covers the character it trails.
          if (offset >= node.data.length) offset = Math.max(0, offset - 1);
          const probe = document.createRange();
          probe.setStart(node, offset);
          probe.setEnd(node, Math.min(offset + 1, node.data.length));
          let best = Number.POSITIVE_INFINITY;
          for (const rect of probe.getClientRects()) {
            if (rect.width <= 0.5 && rect.height <= 0.5) continue;
            const distance = pointDistance(rect, x, y);
            if (distance < best) best = distance;
          }
          return best <= $TAP_PROXIMITY_SQUARED;
        };
        // Nearness to a glyph is not the same as pointing at the paragraph that
        // glyph belongs to. The page's top margin, and the gap between two
        // paragraphs, are within a line height of real text and belong to no
        // sentence. See TAP_BLOCK_MARGIN_CSS_PIXELS.
        const pointIsInsideBlock = (block, x, y) => {
          const rect = block?.getBoundingClientRect?.();
          // A block that measures nothing is not evidence of a miss.
          if (!rect || (rect.width <= 0 && rect.height <= 0)) return true;
          const slack = $TAP_BLOCK_MARGIN_CSS_PIXELS;
          return x >= rect.left - slack && x <= rect.right + slack &&
            y >= rect.top - slack && y <= rect.bottom + slack;
        };
        const blockAt = (x, y) => {
          if (!Number.isFinite(x) || !Number.isFinite(y)) return null;
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
          // Checked after ruby resolution, on the caret actually used, so a tap
          // on furigana is measured against the base glyph it maps onto.
          if (!caretIsNearPoint(caret, x, y)) return null;
          const block = resolvedElement.closest(
            'p,li,blockquote,dd,dt,figcaption,h1,h2,h3,h4,h5,h6,td,th,pre'
          ) || resolvedElement;
          if (!pointIsInsideBlock(block, x, y)) return null;
          return {block: block, caret: caret};
        };
        const isVerticalBlock = (element) => {
          const mode = (getComputedStyle(element).writingMode || '').toString();
          return mode.indexOf('vertical') === 0 || mode === 'tb' || mode === 'tb-rl';
        };
        // A ruby reading is deliberately not selectable, but a highlighted kanji
        // and its furigana have to read as one shape, so the annotation paired
        // with a selected base node contributes its box to the tile list.
        const pairedRubyAnnotation = (node) => {
          const parent = node?.nodeType === Node.TEXT_NODE ? node.parentElement : node;
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
              if (sibling.matches('rp')) { sibling = sibling.nextSibling; continue; }
              break;
            }
            if (sibling.nodeType === Node.TEXT_NODE && sibling.data?.trim()) break;
            sibling = sibling.nextSibling;
          }
          return ruby.querySelector('rt');
        };
        const collectRects = (range, into) => {
          for (const rect of range.getClientRects()) {
            if (rect.width > 0.5 && rect.height > 0.5) {
              into.push({x: rect.left, y: rect.top, w: rect.width, h: rect.height});
            }
          }
        };
    """.trimIndent()

    /**
     * Paints merged tiles as a click-through document overlay.
     *
     * Neither platform's stock `::selection` can tint the `rt` line, because
     * ruby annotations are not part of the selectable range, so the stock paint
     * is made transparent and this overlay becomes the only visual layer. It
     * lives in document coordinates so it survives the column transforms
     * Readium applies while paginating.
     *
     * The scroll offsets are *passed in* rather than read here. Tiles are
     * measured in client coordinates during the selection call and painted in
     * document coordinates during a later one; `addRange` scrolls the selection
     * into view, so re-reading `scrollX`/`scrollY` at paint time can convert
     * them against a different origin than the one they were measured under and
     * lay the highlight down beside the text instead of on it.
     *
     * This is also why the two hosts' inline painters do *not* look like this
     * one: they measure and paint inside a single script, so they can take the
     * overlay's own `getBoundingClientRect()` as the origin — which is the only
     * correction that survives `vertical-rl`. Split across two bridge calls, as
     * here, that same trick would read an origin from after the scroll and
     * apply it to tiles measured before it.
     */
    private fun painter(overlayId: String, fillCss: String, strokeCss: String): String = """
        const paintTiles = (tiles, scrollX, scrollY) => {
          document.getElementById('$overlayId')?.remove();
          if (!tiles.length) return;
          const overlay = document.createElement('div');
          overlay.id = '$overlayId';
          overlay.setAttribute('aria-hidden', 'true');
          overlay.style.cssText =
            'position:absolute;left:0;top:0;pointer-events:none;z-index:2147483645;';
          overlay.style.width = Math.max(
            document.documentElement?.scrollWidth || 0, globalThis.innerWidth || 0) + 'px';
          overlay.style.height = Math.max(
            document.documentElement?.scrollHeight || 0, globalThis.innerHeight || 0) + 'px';
          // `!important` on the fill and the outline is load-bearing, not
          // decoration. ReadiumCSS ships these, and every theme except the
          // light one turns one of them on:
          //   :root[style*=readium-night-on] :not(a) {
          //     background-color: transparent !important;
          //     border-color: currentColor !important; }
          //   :root[style*=readium-sepia-on] :not(a) {
          //     background-color: transparent !important; }
          //   :root[style*="--USER__backgroundColor"] * {
          //     background-color: transparent !important; }
          // A tile is a <span> inside <body>, so all three match it and erase
          // the fill the palette just computed — the selection colour looked
          // broken on sepia, cool grey, dark and every custom background while
          // working perfectly on light. That is one bug, not four, and it was
          // never in the colour maths. An important declaration in a style
          // attribute outranks an important declaration in a stylesheet, so
          // this is what holds the tile's own colour.
          tiles.forEach((tile) => {
            const marker = document.createElement('span');
            marker.style.cssText =
              'position:absolute;pointer-events:none;box-sizing:border-box;' +
              'border-radius:' + ${ReaderSelectionHighlightMetrics.cssCornerRadiusExpression("tile.h")} + 'px;' +
              'border:${ReaderSelectionHighlightMetrics.STROKE_WIDTH}px solid $strokeCss !important;' +
              'background:$fillCss !important;' +
              'left:' + (tile.x + scrollX) + 'px;' +
              'top:' + (tile.y + scrollY) + 'px;' +
              'width:' + tile.w + 'px;height:' + tile.h + 'px;';
            overlay.appendChild(marker);
          });
          (document.body || document.documentElement)?.appendChild(overlay);
        };
        const suppressStockSelectionPaint = () => {
          document.getElementById('$SELECTION_STYLE_ID')?.remove();
          const style = document.createElement('style');
          style.id = '$SELECTION_STYLE_ID';
          style.textContent =
            '::selection { background: transparent !important; color: inherit !important; }' +
            '::-moz-selection { background: transparent !important; color: inherit !important; }';
          (document.head || document.documentElement).appendChild(style);
        };
    """.trimIndent()

    /**
     * Reads the tapped block's ruby-free text and the caret offset within it.
     *
     * Sentence segmentation deliberately happens in Kotlin, not here: the
     * segmenter is language-aware, shared, and tested, and pushing it into the
     * page would put it back out of reach of both.
     */
    fun blockTextScript(x: Double, y: Double): String = """
        (() => {
          $helpers
          const hit = blockAt($x, $y);
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
          return JSON.stringify({text: text, offset: offset, vertical: isVerticalBlock(hit.block)});
        })()
    """.trimIndent()

    /**
     * Selects `[start, end)` of the block at the given point, paints it, and
     * returns the tiles it painted.
     *
     * Measuring and painting have to happen in one evaluation. `addRange`
     * scrolls the selection into view, so client rects read before that scroll
     * no longer line up with the page by the time a second call could paint
     * them — the highlight lands next to the text instead of on it.
     *
     * The rectangles are collected one text node at a time rather than from the
     * sentence range as a whole: `getClientRects()` on the outer range also
     * reports the box of every `ruby` element it crosses, and that box sits on
     * top of the base characters inside it.
     */
    fun selectRangeScript(
        x: Double,
        y: Double,
        start: Int,
        end: Int,
        palette: ReaderSelectionPalette
    ): String = """
        (() => {
          $helpers
          ${painter(QUICK_OVERLAY_ID, palette.fillCss, palette.strokeCss)}
          const hit = blockAt($x, $y);
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
          // The quick-tap path owns the painting for this range; tell the
          // native-selection bridge to stand down so the base text is not
          // tinted twice.
          globalThis.__jerreaderQuickSelectionActive = true;
          selection.removeAllRanges();
          selection.addRange(range);

          const rects = [];
          const annotations = new Set();
          let consumed = 0;
          for (const node of nodes) {
            const nodeStart = consumed;
            consumed += node.data.length;
            const pieceStart = Math.max($start, nodeStart);
            const pieceEnd = Math.min($end, consumed);
            if (pieceEnd <= pieceStart) continue;
            const piece = document.createRange();
            piece.setStart(node, pieceStart - nodeStart);
            piece.setEnd(node, pieceEnd - nodeStart);
            collectRects(piece, rects);
            const annotation = pairedRubyAnnotation(node);
            if (annotation && !annotations.has(annotation)) {
              annotations.add(annotation);
              for (const rect of annotation.getClientRects()) {
                if (rect.width > 0.5 && rect.height > 0.5) {
                  rects.push({x: rect.left, y: rect.top, w: rect.width, h: rect.height});
                }
              }
            }
          }

          const vertical = isVerticalBlock(hit.block);
          // Raw boxes go back to Kotlin unmerged: `ReaderSelectionRectMerger`
          // owns that algorithm and its tests, and the reader knows the writing
          // mode it configured even when `getComputedStyle` misreports
          // `-epub-writing-mode` as horizontal.
          return JSON.stringify({ok: selection.toString().trim().length > 0,
                                 vertical: vertical, rects: rects,
                                 scrollX: globalThis.scrollX || 0,
                                 scrollY: globalThis.scrollY || 0});
        })()
    """.trimIndent()

    /**
     * Hands merged tiles back to the page for painting.
     *
     * Kotlin merges, JavaScript paints. The tiles arrive already de-duplicated
     * so the page never has to re-derive line structure it cannot test.
     */
    fun paintTilesScript(
        tiles: List<com.jerreader.unified.reader.geometry.ReaderRect>,
        palette: ReaderSelectionPalette,
        scrollX: Double,
        scrollY: Double
    ): String {
        val json = tiles.joinToString(prefix = "[", postfix = "]") { tile ->
            "{x:${tile.x},y:${tile.y},w:${tile.width},h:${tile.height}}"
        }
        return """
        (() => {
          ${painter(QUICK_OVERLAY_ID, palette.fillCss, palette.strokeCss)}
          suppressStockSelectionPaint();
          paintTiles($json, $scrollX, $scrollY);
          return JSON.stringify({ok: true, count: $json.length});
        })()
        """.trimIndent()
    }

    /** Removes every overlay and restores the stock selection paint. */
    fun clearHighlightScript(): String = """
        (() => {
          globalThis.__jerreaderQuickSelectionActive = false;
          document.getElementById('$QUICK_OVERLAY_ID')?.remove();
          document.getElementById('$NATIVE_OVERLAY_ID')?.remove();
          document.getElementById('$SELECTION_STYLE_ID')?.remove();
          return JSON.stringify({ok: true});
        })()
    """.trimIndent()

    /**
     * Reports the current native selection as a [ReaderSelectionSnapshot]
     * payload, with ruby readings stripped from the text and their boxes kept
     * for painting.
     */
    fun currentSelectionScript(): String = """
        (() => {
          $helpers
          const selection = window.getSelection();
          if (!selection || selection.rangeCount === 0) return null;
          const range = selection.getRangeAt(0);
          if (range.collapsed) return null;
          const container = range.commonAncestorContainer;
          const root = container.nodeType === Node.TEXT_NODE
            ? container.parentElement
            : container;
          if (!root) return null;

          const rects = [];
          const annotations = new Set();
          let baseText = '';
          let recoveredFromRuby = false;
          for (const node of textNodes(root)) {
            if (!range.intersectsNode(node)) continue;
            const piece = document.createRange();
            piece.selectNodeContents(node);
            if (piece.compareBoundaryPoints(Range.START_TO_START, range) < 0) {
              piece.setStart(range.startContainer, range.startOffset);
            }
            if (piece.compareBoundaryPoints(Range.END_TO_END, range) > 0) {
              piece.setEnd(range.endContainer, range.endOffset);
            }
            const text = piece.toString();
            if (!text) continue;
            baseText += text;
            collectRects(piece, rects);
            const annotation = pairedRubyAnnotation(node);
            if (annotation && !annotations.has(annotation)) {
              annotations.add(annotation);
              recoveredFromRuby = true;
              for (const rect of annotation.getClientRects()) {
                if (rect.width > 0.5 && rect.height > 0.5) {
                  rects.push({x: rect.left, y: rect.top, w: rect.width, h: rect.height});
                }
              }
            }
          }
          if (!baseText.trim() || !rects.length) return null;
          return JSON.stringify({
            rawText: selection.toString(),
            normalizedText: baseText,
            rects: rects,
            vertical: isVerticalBlock(root.nodeType === Node.ELEMENT_NODE ? root : document.body),
            recoveredFromRuby: recoveredFromRuby,
            scrollX: globalThis.scrollX || 0,
            scrollY: globalThis.scrollY || 0
          });
        })()
    """.trimIndent()
}
