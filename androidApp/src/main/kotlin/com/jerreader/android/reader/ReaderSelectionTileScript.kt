package com.jerreader.android.reader

/**
 * Ruby-aware selection tiles for the page itself: the quick-tap sentence
 * overlay and the native-selection bridge both paint what these scripts
 * return.
 *
 * [SOURCE] (`jerreaderSelectionTiles`) is the pure geometry, the page-side
 * twin of `ReaderSelectionRectMerger`: one tile per line run, and each ruby
 * annotation joins the tile of the kanji it sits over, so kanji and furigana
 * are one region with no gap and no second outline between them. It is the
 * same source as iOS's `ReaderSelectionTileScript.swift`, character for
 * character. iOS runs it in JavaScriptCore against
 * `shared/parity/selection_tiles.json`, and `ReaderSelectionTileScriptTest`
 * keeps the two copies identical, so change both together.
 *
 * [DOM_SOURCE] measures a selection in the page: base-text boxes, the paired
 * annotation of each selected ruby base, and whether that base is selected
 * in full.
 *
 * No `$` anywhere: both are spliced into Kotlin string templates.
 */
internal object ReaderSelectionTileScript {
    val SOURCE: String = """
        // jerreaderSelectionTiles(base, annotations, vertical)
        //   base:        selected base-text rects, in document order.
        //   annotations: [{ base: [indices into base], rects: [annotation rects],
        //                   fullySelected: false when only part of the text the
        //                   annotation belongs to is selected }].
        //   vertical:    true for vertical writing (lines run top to bottom).
        function jerreaderSelectionTiles(base, annotations, vertical) {
          const MIN_EXTENT = 0.5;
          const SAME_LINE = 0.55;
          const RUN_GAP = 1.5;
          const TIE = 0.01;
          // cross = across the line (y in horizontal text, x in vertical text);
          // run = along the line.
          const crossStart = (r) => vertical ? r.left : r.top;
          const crossEnd = (r) => vertical ? r.right : r.bottom;
          const runStart = (r) => vertical ? r.top : r.left;
          const runEnd = (r) => vertical ? r.bottom : r.right;
          const box = (r) => ({ left: r.left, top: r.top, right: r.right, bottom: r.bottom });
          const usable = (r) => !!r &&
            [r.left, r.top, r.right, r.bottom].every(Number.isFinite) &&
            r.right - r.left > MIN_EXTENT && r.bottom - r.top > MIN_EXTENT;
          const union = (a, b) => ({
            left: Math.min(a.left, b.left), top: Math.min(a.top, b.top),
            right: Math.max(a.right, b.right), bottom: Math.max(a.bottom, b.bottom)
          });
          const tile = (left, top, right, bottom) => ({
            left, top, right, bottom, width: right - left, height: bottom - top, x: left, y: top
          });

          // `line` decides which line an item belongs to; `paint` is what gets
          // drawn. A joined annotation only ever grows `paint`.
          const items = Array.from(base || []).map(
            (r) => usable(r) ? { line: box(r), paint: box(r) } : null
          );
          const extra = [];
          Array.from(annotations || []).forEach((annotation) => {
            const candidates = Array.from(annotation?.base || [])
              .filter((index) => Number.isInteger(index) && index >= 0 &&
                index < items.length && items[index])
              .map((index) => items[index]);
            Array.from(annotation?.rects || []).forEach((raw) => {
              if (!usable(raw)) return;
              const r = box(raw);
              if (annotation.fullySelected === false && candidates.length) {
                // Only the kana over the selected kanji belong to the selection.
                const start = Math.max(runStart(r), Math.min(...candidates.map((c) => runStart(c.line))));
                const end = Math.min(runEnd(r), Math.max(...candidates.map((c) => runEnd(c.line))));
                if (end - start <= MIN_EXTENT) return;
                if (vertical) {
                  r.top = start;
                  r.bottom = end;
                } else {
                  r.left = start;
                  r.right = end;
                }
              }
              let best = null;
              let bestOverlap = -Infinity;
              let bestGap = Infinity;
              candidates.forEach((candidate) => {
                const overlap = Math.min(runEnd(candidate.line), runEnd(r)) -
                  Math.max(runStart(candidate.line), runStart(r));
                const gap = Math.max(0, crossStart(candidate.line) - crossEnd(r),
                  crossStart(r) - crossEnd(candidate.line));
                if (overlap > bestOverlap + TIE ||
                    (Math.abs(overlap - bestOverlap) <= TIE && gap < bestGap)) {
                  best = candidate;
                  bestOverlap = overlap;
                  bestGap = gap;
                }
              });
              const thickness = best ? crossEnd(best.line) - crossStart(best.line) : 0;
              if (best && bestOverlap > 0 && bestGap <= thickness) {
                best.paint = union(best.paint, r);
              } else {
                extra.push({ line: r, paint: box(r) });
              }
            });
          });

          const live = items.filter(Boolean).concat(extra);
          if (live.length <= 1) {
            return live.map((item) => tile(item.paint.left, item.paint.top,
              item.paint.right, item.paint.bottom));
          }

          const sharesLine = (a, b) => {
            const overlap = Math.min(crossEnd(a), crossEnd(b)) - Math.max(crossStart(a), crossStart(b));
            if (overlap <= 0) return false;
            const shorter = Math.min(crossEnd(a) - crossStart(a), crossEnd(b) - crossStart(b));
            return shorter > 0 && overlap / shorter >= SAME_LINE;
          };
          const buckets = [];
          live.slice()
            .sort((a, b) => crossStart(a.line) - crossStart(b.line))
            .forEach((item) => {
              const bucket = buckets.find(
                (candidate) => candidate.some((other) => sharesLine(other.line, item.line))
              );
              if (bucket) bucket.push(item);
              else buckets.push([item]);
            });

          const lines = buckets.map((bucket) => {
            const runs = [];
            bucket
              .map((item) => [runStart(item.paint), runEnd(item.paint)])
              .sort((a, b) => a[0] - b[0])
              .forEach(([start, end]) => {
                const last = runs[runs.length - 1];
                if (last && start <= last[1] + RUN_GAP) last[1] = Math.max(last[1], end);
                else runs.push([start, end]);
              });
            return {
              start: Math.min(...bucket.map((item) => crossStart(item.paint))),
              end: Math.max(...bucket.map((item) => crossEnd(item.paint))),
              runs
            };
          }).sort((a, b) => a.start - b.start);

          // Neighbouring lines whose paint overlaps (tight leading, or ruby
          // reaching into the line above) meet at the midpoint instead.
          for (let index = 1; index < lines.length; index += 1) {
            const previous = lines[index - 1];
            const current = lines[index];
            if (current.start >= previous.end) continue;
            const boundary = (current.start + previous.end) / 2;
            if (boundary <= previous.start || boundary >= current.end) continue;
            previous.end = boundary;
            current.start = boundary;
          }

          const tiles = [];
          lines.forEach((line) => {
            line.runs.forEach(([start, end]) => {
              tiles.push(vertical
                ? tile(line.start, start, line.end, end)
                : tile(start, line.start, end, line.end));
            });
          });
          return tiles;
        }
    """.trimIndent()

    val DOM_SOURCE: String = """
        // jerreaderSelectionGeometry(pieces, vertical, extraAnnotations)
        //   pieces:           one Range per selected base-text node, each inside a
        //                     single text node (the reading itself is never selected).
        //   vertical:         true for vertical writing.
        //   extraAnnotations: rt elements the native range touched directly; each one
        //                     joins the pieces of its own ruby base.
        // Returns the highlight tiles as {x, y, w, h} in client coordinates: every
        // selected kanji's paired reading joins the kanji's tile, so kanji and kana
        // are painted as one region (jerreaderSelectionTiles).
        function jerreaderSelectionGeometry(pieces, vertical, extraAnnotations) {
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
          // The base text an annotation belongs to: the ruby children between the
          // previous annotation and this one (mono ruby), or the whole ruby.
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
              const element = children[index].nodeType === Node.ELEMENT_NODE ? children[index] : null;
              if (element?.matches?.('rt,rp') || element?.querySelector?.('rt,rp')) {
                segmentStart = index + 1;
                break;
              }
            }
            const roots = annotationIndex > 0 ? children.slice(segmentStart, annotationIndex) : children;
            const nodes = [];
            roots.forEach((rootNode) => {
              if (rootNode.nodeType === Node.TEXT_NODE) {
                if (rootNode.data?.trim()) nodes.push(rootNode);
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
                if (walker.currentNode.data?.trim()) nodes.push(walker.currentNode);
              }
            });
            return nodes;
          };
          const visible = (rect) => rect.width > 0.5 && rect.height > 0.5;

          const base = [];
          const groups = new Map();
          const indicesByNode = new Map();
          Array.from(pieces || []).forEach((piece) => {
            const indices = [];
            Array.from(piece.getClientRects()).forEach((rect) => {
              if (!visible(rect)) return;
              indices.push(base.length);
              base.push(rect);
            });
            const node = piece.startContainer;
            const text = node?.nodeType === Node.TEXT_NODE ? (node.data || '') : '';
            if (!text.trim()) return;
            indicesByNode.set(node, (indicesByNode.get(node) || []).concat(indices));
            const annotation = pairedAnnotation(node);
            if (!annotation) return;
            let group = groups.get(annotation);
            if (!group) {
              group = { base: [], nodes: new Set(), partial: false };
              groups.set(annotation, group);
            }
            group.base.push(...indices);
            group.nodes.add(node);
            const leading = text.length - text.trimStart().length;
            const trailing = text.length - text.trimEnd().length;
            if (piece.startOffset > leading || piece.endOffset < text.length - trailing) {
              group.partial = true;
            }
          });

          const annotations = [];
          groups.forEach((group, annotation) => {
            const pairedBase = rubyBaseNodes(annotation).filter(
              (node) => pairedAnnotation(node) === annotation
            );
            annotations.push({
              base: group.base,
              rects: Array.from(annotation.getClientRects()).filter(visible),
              fullySelected: !group.partial && pairedBase.every((node) => group.nodes.has(node))
            });
          });
          Array.from(extraAnnotations || []).forEach((annotation) => {
            if (!annotation?.matches?.('rt') || groups.has(annotation)) return;
            const indices = [];
            rubyBaseNodes(annotation).forEach((node) => {
              indices.push(...(indicesByNode.get(node) || []));
            });
            annotations.push({
              base: indices,
              rects: Array.from(annotation.getClientRects()).filter(visible),
              fullySelected: true
            });
          });

          return jerreaderSelectionTiles(base, annotations, vertical).map(
            (tile) => ({ x: tile.left, y: tile.top, w: tile.width, h: tile.height })
          );
        }

        // Vertical writing lays lines out top to bottom. `configured` is what the
        // reader itself set: some WebView builds report a book's own
        // `-epub-writing-mode` as horizontal through getComputedStyle.
        function jerreaderSelectionIsVertical(node, configured) {
          if (configured) return true;
          const element = node?.nodeType === Node.ELEMENT_NODE ? node : node?.parentElement;
          if (!element) return false;
          const style = getComputedStyle(element);
          const mode = (style.writingMode || style.webkitWritingMode ||
            style.getPropertyValue('-epub-writing-mode') || '').toString();
          return /^(vertical|sideways|tb)/i.test(mode);
        }
    """.trimIndent()

    /** Both scripts, ready to splice into a page script. */
    val ALL: String = SOURCE + "\n" + DOM_SOURCE
}
