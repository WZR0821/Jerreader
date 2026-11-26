import Foundation

/// Turns the boxes of a text selection into highlight tiles, one per line run.
///
/// A ruby annotation (`rt`) joins the tile of the selected kanji it sits over,
/// so 東京 with とうきょう above it (or beside it in vertical text) reads as
/// one region from the top of the kana to the bottom of the kanji, instead of
/// a strip for the text and a separate strip for the reading with a gap and
/// two outlines between them. Lines are still told apart by the base text
/// alone, so a line with ruby never swallows the line next to it.
///
/// The rules and their cases live in `shared/parity/selection_tiles.json`.
/// `scripts/test_selection_tiles.swift` runs this exact source in
/// JavaScriptCore against them (host-test suite `selection-tiles`); Android
/// runs the same cases against its copy of this script and against
/// `ReaderSelectionRectMerger`.
///
/// Pure geometry: no DOM access, so it can run anywhere. Input rects need
/// `left`, `top`, `right` and `bottom`; output tiles are DOMRect-like
/// (`left`, `top`, `right`, `bottom`, `width`, `height`, `x`, `y`).
enum ReaderSelectionTileScript {
    static let source = #"""
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
    """#
}
