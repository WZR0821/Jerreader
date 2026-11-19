import CoreGraphics
import Foundation

// JavaScript that EPUBReaderViewController injects into Readium's web views:
// writing-mode and pagination CSS policy, selection and quick-sentence hit
// testing, sentence and speech highlights, and cross-page context capture.
// Every script only reads the document or adds temporary overlay styling;
// none edits the publication's text nodes or files. Kept apart from the
// controller so the controller file holds lifecycle and gesture code only.
extension EPUBReaderViewController {
    /// Readium's vertical CSS defaults to continuous columns. WebKit supports
    /// fragmenting those columns across horizontal pages through column-axis;
    /// opt into that CSS only for vertical page mode. Readium still owns the
    /// document, locators, typography, selection and previous/next navigation.
    /// No text nodes or imported publication files are changed by this policy.
    static func verticalPaginationPolicyScript(enabled: Bool, authoritative: Bool = false) -> String {
        let value = enabled ? "page" : "scroll"
        return #"""
        (() => {
          let mode = '\#(value)';
          try {
            if (\#(authoritative ? "true" : "false")) {
              localStorage.setItem('jerreaderReaderVerticalPagination', mode);
            } else {
              const stored = localStorage.getItem('jerreaderReaderVerticalPagination');
              if (stored === 'page' || stored === 'scroll') mode = stored;
            }
          } catch (_) {}
          const apply = () => {
            const root = document.documentElement;
            if (!root) return;
            root.setAttribute('data-jerreader-vertical-pagination', String(mode === 'page'));
            const id = 'jerreader-reader-vertical-pagination';
            if (document.getElementById(id)) return;
            const style = document.createElement('style');
            style.id = id;
            style.textContent = `
              @supports (-webkit-column-axis: horizontal) {
                html:root[data-jerreader-vertical-pagination="true"] {
                  -webkit-column-axis: auto !important;
                  columns: auto auto !important;
                  padding: 0 !important;
                  overflow-x: auto !important;
                  overflow-y: hidden !important;
                  width: 100vw !important;
                  min-width: 100vw !important;
                  max-width: 100vw !important;
                }
                html:root[data-jerreader-vertical-pagination="true"] body {
                  -webkit-column-axis: horizontal !important;
                  -webkit-column-gap: calc(2 * var(--RS__pageGutter)) !important;
                  column-fill: auto !important;
                  width: 100vw !important;
                  max-width: 100vw !important;
                  height: 100vh !important;
                  padding-left: var(--RS__pageGutter) !important;
                  padding-right: var(--RS__pageGutter) !important;
                }
              }
            `;
            (document.head || root).appendChild(style);
          };
          apply();
          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', apply, { once: true });
          }
          return mode;
        })()
        """#
    }

    static func quickSentenceHitScript(
        at point: CGPoint,
        prefersNativeRubySelection: Bool = false,
        selectedText: String? = nil
    ) -> String {
        let x = String(
            format: "%.3f",
            locale: Locale(identifier: "en_US_POSIX"),
            point.x
        )
        let y = String(
            format: "%.3f",
            locale: Locale(identifier: "en_US_POSIX"),
            point.y
        )
        let prefersNativeSelection = prefersNativeRubySelection ? "true" : "false"
        let selectedTextJSON: String
        if let selectedText {
            selectedTextJSON = javaScriptString(selectedText)
        } else {
            selectedTextJSON = "null"
        }
        return """
        (() => {
          \(quickSentenceDOMHelpers)
          const hit = jerreaderReaderTextHit(
            \(x),
            \(y),
            \(prefersNativeSelection),
            \(selectedTextJSON)
          );
          if (!hit) return null;
          return {
            paragraph: hit.paragraph,
            offset: hit.offset,
            rect: jerreaderReaderRectJSON(hit.probeRect),
            rubyBase: hit.rubyBase,
            rubyAnnotation: hit.rubyAnnotation,
            rubyText: hit.rubyText,
            rubyBaseStart: hit.rubyBaseStart,
            rubyBaseEnd: hit.rubyBaseEnd
          };
        })()
        """
    }

    /// Reads the browser's current native selection through the same base-text
    /// node model used by quick sentence hit-testing and highlighting. The raw
    /// WebKit selection can still contain visible ruby annotations, but the
    /// normalized text, context and geometry never use rt/rp nodes.
    static let nativeSelectionSnapshotScript = #"""
    (() => {
      \#(quickSentenceDOMHelpers)
      const selection = globalThis.getSelection?.();
      const snapshot = jerreaderReaderBaseSelectionSnapshot(selection);
      if (!snapshot) return null;
      return {
        rawText: snapshot.rawText,
        normalizedText: snapshot.normalizedText,
        before: snapshot.before,
        after: snapshot.after,
        rects: snapshot.rects.map(jerreaderReaderRectJSON),
        rect: jerreaderReaderUnionRectJSON(snapshot.rects),
        rectCount: snapshot.rects.length,
        recoveredFromRuby: snapshot.recoveredFromRuby,
        anchorRole: jerreaderReaderNodeRole(selection.anchorNode),
        focusRole: jerreaderReaderNodeRole(selection.focusNode),
        anchorOffset: selection.anchorOffset,
        focusOffset: selection.focusOffset,
        layout: {
          scrollX: window.scrollX,
          scrollY: window.scrollY,
          innerWidth: window.innerWidth,
          innerHeight: window.innerHeight
        }
      };
    })()
    """#

    static func crossPageDocumentContextScript(sourceText: String) -> String {
        let sourceJSON = javaScriptString(sourceText)
        return """
        (() => {
          \(quickSentenceDOMHelpers)
          const source = (\(sourceJSON) || '')
            .replace(/\\s+/gu, ' ')
            .trim()
            .normalize('NFC');
          if (!source) return null;

          const normalize = (value) => (value || '')
            .replace(/\\s+/gu, ' ')
            .trim()
            .normalize('NFC');
          const root = document.body || document.documentElement;
          const nodes = jerreaderReaderTextNodes(root);
          let rawDocumentText = '';
          let previousBlock = null;
          nodes.forEach((node) => {
            const block = jerreaderReaderBlock(node.parentElement) || root;
            if (rawDocumentText && previousBlock && block !== previousBlock) {
              rawDocumentText += ' ';
            }
            rawDocumentText += node.data;
            previousBlock = block;
          });
          const documentText = normalize(rawDocumentText);
          if (!documentText) return null;

          const matches = [];
          let cursor = 0;
          while (cursor <= documentText.length - source.length) {
            const index = documentText.indexOf(source, cursor);
            if (index < 0) break;
            matches.push(index);
            cursor = index + Math.max(source.length, 1);
          }
          if (!matches.length) return null;
          const middle = documentText.length / 2;
          matches.sort(
            (left, right) => Math.abs(left - middle) - Math.abs(right - middle)
          );

          const selectedIndex = matches[0];
          const maximum = Math.max(1200, Math.min(source.length + 240, 2000));
          const remaining = Math.max(maximum - source.length, 0);
          let start = Math.max(0, selectedIndex - Math.floor(remaining / 2));
          let end = Math.min(
            documentText.length,
            selectedIndex + source.length + (remaining - Math.floor(remaining / 2))
          );
          if (end - start < maximum) {
            start = Math.max(0, start - (maximum - (end - start)));
            end = Math.min(documentText.length, start + maximum);
          }
          return documentText.slice(start, end);
        })()
        """
    }

    /// Recovers the base glyph under a Readium selection frame when WebKit has
    /// already collapsed its native Range. The helper deliberately returns
    /// only a ruby base segment; ordinary non-ruby selections continue to use
    /// Readium's own selection text and geometry.
    static func selectionPointSnapshotScript(
        at point: CGPoint,
        selectedText: String?
    ) -> String {
        let x = String(
            format: "%.3f",
            locale: Locale(identifier: "en_US_POSIX"),
            point.x
        )
        let y = String(
            format: "%.3f",
            locale: Locale(identifier: "en_US_POSIX"),
            point.y
        )
        let selectedTextJSON: String
        if let selectedText {
            selectedTextJSON = javaScriptString(selectedText)
        } else {
            selectedTextJSON = "null"
        }
        return """
        (() => {
          \(quickSentenceDOMHelpers)
          const hit = jerreaderReaderTextHit(\(x), \(y), true, \(selectedTextJSON));
          if (!hit || hit.rubyBaseStart == null || hit.rubyBaseEnd == null) {
            return null;
          }
          const start = jerreaderReaderBoundary(hit.nodes, hit.rubyBaseStart);
          const end = jerreaderReaderBoundary(hit.nodes, hit.rubyBaseEnd);
          if (!start || !end) return null;

          const range = document.createRange();
          range.setStart(start.node, start.offset);
          range.setEnd(end.node, end.offset);
          const subRanges = jerreaderReaderBaseSubRanges(range, hit.block);
          const rects = jerreaderReaderSelectionRects(subRanges);
          if (!rects.length) return null;

          const normalizedText = subRanges
            .map((subRange) => subRange.toString())
            .join('')
            .normalize('NFC');
          if (!normalizedText.trim()) return null;
          return {
            rawText: \(selectedTextJSON) || '',
            normalizedText,
            before: hit.paragraph.slice(0, hit.rubyBaseStart).normalize('NFC'),
            after: hit.paragraph.slice(hit.rubyBaseEnd).normalize('NFC'),
            rects: rects.map(jerreaderReaderRectJSON),
            rect: jerreaderReaderUnionRectJSON(rects),
            rectCount: rects.length,
            recoveredFromRuby: true,
            anchorRole: 'pointFallback',
            focusRole: 'rubyBase'
          };
        })()
        """
    }

    private static func javaScriptString(_ value: String) -> String {
        guard let data = try? JSONSerialization.data(
            withJSONObject: value,
            options: [.fragmentsAllowed]
        ),
        let json = String(data: data, encoding: .utf8)
        else {
            return "null"
        }
        return json
    }

    /// The fill and outline of one highlight tile, as inline CSS that the
    /// page's own styles cannot override.
    ///
    /// ReadiumCSS declares `background-color: transparent !important` for every
    /// element under the sepia and night themes and under any explicit page
    /// colour, and `border-color: currentColor !important` at night. An inline
    /// `!important` is the only declaration that outranks those, so both
    /// properties carry it. `background-image: none` keeps a publisher rule
    /// from painting a pattern over the tint.
    static func markerPaintCSS(fill: String, stroke: String?) -> String {
        var css = "background-color:\(fill) !important;"
            + "background-image:none !important;"
        if let stroke {
            css += "border:0.75px solid \(stroke) !important;"
        } else {
            css += "border:0 none transparent !important;"
        }
        return css
    }

    static func quickSentenceHighlightScript(
        at point: CGPoint,
        utf16Range: Range<Int>,
        requestToken: String,
        palette: ReaderSelectionVisualPalette = .light
    ) -> String {
        let x = String(
            format: "%.3f",
            locale: Locale(identifier: "en_US_POSIX"),
            point.x
        )
        let y = String(
            format: "%.3f",
            locale: Locale(identifier: "en_US_POSIX"),
            point.y
        )
        return """
        (() => {
          \(quickSentenceDOMHelpers)
          const hit = jerreaderReaderTextHit(\(x), \(y));
          if (!hit) return null;
          const start = jerreaderReaderBoundary(hit.nodes, \(utf16Range.lowerBound));
          const end = jerreaderReaderBoundary(hit.nodes, \(utf16Range.upperBound));
          if (!start || !end) return null;

          const range = document.createRange();
          range.setStart(start.node, start.offset);
          range.setEnd(end.node, end.offset);
          const baseRanges = jerreaderReaderBaseSubRanges(range);
          const rects = jerreaderReaderSelectionRects(baseRanges);
          if (!rects.length) return null;

          globalThis.CSS?.highlights?.delete('jerreader-reader-quick-sentence');
          document.getElementById('jerreader-reader-quick-sentence-highlight')?.remove();
          document.getElementById(
            'jerreader-reader-quick-sentence-speech-highlight'
          )?.remove();
          document.getElementById('jerreader-reader-quick-sentence-style')?.remove();
          globalThis.__jerreaderReaderQuickSentenceSelection = {
            requestToken: '\(requestToken)',
            nodes: hit.nodes,
            lowerBound: \(utf16Range.lowerBound),
            upperBound: \(utf16Range.upperBound)
          };

          // CSS custom highlights are deliberately not used here: WebKit
          // skips painting them on text inside <ruby>, which is exactly the
          // text this reader must highlight. The overlay uses absolute
          // document coordinates because a fixed child can be displaced by
          // EPUB column paging. Keep the established ruby-aware geometry and
          // change painting only: the selection now matches other formats
          // with a light fill and a restrained outline.
          const overlay = document.createElement('div');
          overlay.id = 'jerreader-reader-quick-sentence-highlight';
          overlay.dataset.jerreaderReaderRequest = '\(requestToken)';
          overlay.setAttribute('aria-hidden', 'true');
          overlay.style.cssText =
            'position:absolute;left:0;top:0;width:0;height:0;' +
            'pointer-events:none;z-index:2147483646;';
          (document.body || document.documentElement).appendChild(overlay);

          // Position the markers against the overlay's *measured* origin
          // rather than `window.scrollX/scrollY`. Those two only describe the
          // containing block in an ordinary horizontal-tb, ltr document; in
          // `vertical-rl` — every Japanese book read in its original layout —
          // they do not, so every marker landed off-screen and tapping a
          // sentence produced no visible highlight at all while the
          // translation itself was correct. Measuring is writing-mode,
          // direction and pagination agnostic.
          //
          // The paint is `!important` on purpose. ReadiumCSS forces
          // `background-color: transparent !important` on every element in
          // the sepia and night themes and whenever a page colour is set
          // (cool grey, any custom background), and `border-color:
          // currentColor !important` at night. A plain inline declaration
          // loses to those rules, which left only the outline — in the text
          // colour — and no fill in every theme but plain white. Only an
          // inline `!important` outranks an `!important` stylesheet rule.
          const origin = overlay.getBoundingClientRect();
          rects.slice(0, 160).forEach((rect) => {
            const marker = document.createElement('span');
            marker.style.cssText =
              'position:absolute;pointer-events:none;border-radius:3px;' +
              'box-sizing:border-box;' +
              '\(markerPaintCSS(fill: palette.fillCSS, stroke: palette.strokeCSS))' +
              `left:${rect.left - origin.left}px;` +
              `top:${rect.top - origin.top}px;` +
              `width:${rect.width}px;height:${rect.height}px;`;
            overlay.appendChild(marker);
          });

          // The translation card must avoid the whole visible translation
          // target, not only the line nearest to the tap. This is especially
          // important in paragraph mode, where a nearest-line anchor would
          // cover the remaining selected lines.
          const left = Math.min(...rects.map((rect) => rect.left));
          const top = Math.min(...rects.map((rect) => rect.top));
          const right = Math.max(...rects.map((rect) => rect.right));
          const bottom = Math.max(...rects.map((rect) => rect.bottom));
          return jerreaderReaderRectJSON({
            left,
            top,
            width: right - left,
            height: bottom - top
          });
        })()
        """
    }

    static func quickSentenceSpeechHighlightScript(
        _ highlight: ReaderSpeechHighlight?,
        matching requestToken: String,
        sequence: Int,
        palette: ReaderSelectionVisualPalette = .light
    ) -> String {
        let lowerBound = highlight.map {
            String(
                format: "%.8f",
                locale: Locale(identifier: "en_US_POSIX"),
                $0.lowerBound
            )
        } ?? "null"
        let upperBound = highlight.map {
            String(
                format: "%.8f",
                locale: Locale(identifier: "en_US_POSIX"),
                $0.upperBound
            )
        } ?? "null"
        let requestTokenJSON = javaScriptString(requestToken)

        return """
        (() => {
          \(quickSentenceDOMHelpers)
          const incomingSequence = \(sequence);
          const previousSequence = Number(
            globalThis.__jerreaderReaderQuickSentenceSpeechSequence ?? -1
          );
          if (incomingSequence < previousSequence) return false;
          globalThis.__jerreaderReaderQuickSentenceSpeechSequence = incomingSequence;

          const state = globalThis.__jerreaderReaderQuickSentenceSelection;
          const selectionOverlay = document.getElementById(
            'jerreader-reader-quick-sentence-highlight'
          );
          const spokenOverlayID =
            'jerreader-reader-quick-sentence-speech-highlight';
          const requestToken = \(requestTokenJSON);
          if (
            !state ||
            state.requestToken !== requestToken ||
            selectionOverlay?.dataset.jerreaderReaderRequest !== requestToken
          ) {
            return false;
          }

          document.getElementById(spokenOverlayID)?.remove();
          const normalizedLower = \(lowerBound);
          const normalizedUpper = \(upperBound);
          if (normalizedLower === null || normalizedUpper === null) {
            return true;
          }

          const selectionLength = Math.max(
            0,
            state.upperBound - state.lowerBound
          );
          if (!selectionLength) return false;
          const spokenStart = Math.min(
            state.upperBound,
            Math.max(
              state.lowerBound,
              state.lowerBound +
                Math.floor(selectionLength * normalizedLower)
            )
          );
          let spokenEnd = Math.min(
            state.upperBound,
            Math.max(
              spokenStart,
              state.lowerBound +
                Math.ceil(selectionLength * normalizedUpper)
            )
          );
          if (spokenEnd <= spokenStart) {
            spokenEnd = Math.min(state.upperBound, spokenStart + 1);
          }
          if (spokenEnd <= spokenStart) return false;

          const start = jerreaderReaderBoundary(state.nodes, spokenStart);
          const end = jerreaderReaderBoundary(state.nodes, spokenEnd);
          if (!start || !end) return false;
          const range = document.createRange();
          range.setStart(start.node, start.offset);
          range.setEnd(end.node, end.offset);
          const rects = jerreaderReaderSelectionRects(
            jerreaderReaderBaseSubRanges(range)
          );
          if (!rects.length) return false;

          const overlay = document.createElement('div');
          overlay.id = spokenOverlayID;
          overlay.dataset.jerreaderReaderRequest = requestToken;
          overlay.setAttribute('aria-hidden', 'true');
          overlay.style.cssText =
            'position:absolute;left:0;top:0;width:0;height:0;' +
            'pointer-events:none;z-index:2147483647;opacity:0.52;';
          (document.body || document.documentElement).appendChild(overlay);

          // Same measured origin as the sentence overlay; see the comment
          // there for why `window.scrollX/scrollY` cannot be used.
          const origin = overlay.getBoundingClientRect();
          rects.slice(0, 80).forEach((rect) => {
            const marker = document.createElement('span');
            marker.style.cssText =
              'position:absolute;pointer-events:none;border-radius:3px;' +
              '\(markerPaintCSS(fill: palette.spokenFillCSS, stroke: nil))' +
              `left:${rect.left - origin.left}px;` +
              `top:${rect.top - origin.top}px;` +
              `width:${rect.width}px;height:${rect.height}px;`;
            overlay.appendChild(marker);
          });
          return true;
        })()
        """
    }

    static func clearQuickSentenceHighlightScript(
        matching requestToken: String?
    ) -> String {
        let predicate = requestToken.map {
            """
            overlay?.dataset.jerreaderReaderRequest === '\($0)' ||
              state?.requestToken === '\($0)'
            """
        } ?? "true"
        return """
        (() => {
          const style = document.getElementById('jerreader-reader-quick-sentence-style');
          const overlay = document.getElementById('jerreader-reader-quick-sentence-highlight');
          const spokenOverlay = document.getElementById(
            'jerreader-reader-quick-sentence-speech-highlight'
          );
          const state = globalThis.__jerreaderReaderQuickSentenceSelection;
          if (\(predicate)) {
            globalThis.CSS?.highlights?.delete('jerreader-reader-quick-sentence');
            overlay?.remove();
            spokenOverlay?.remove();
            style?.remove();
            delete globalThis.__jerreaderReaderQuickSentenceSelection;
          }
          return true;
        })()
        """
    }

    /// Removes ruby annotation text from the selectable DOM text flow
    /// entirely. Each `rt`'s reading is moved into a `data-jerreader-rt` attribute
    /// and rendered through a `::before` pseudo-element, which WebKit lays out
    /// identically but can never include in a Range: annotation text stops
    /// existing as text nodes, so a long press over furigana snaps to the
    /// nearest real text — the kanji base — and the native highlight, drag
    /// handles and copied text all operate on base text by construction.
    ///
    /// Scope: this script only ever runs inside the EPUB reader, and the
    /// annotation detachment additionally requires Japanese content — the
    /// book's metadata language, the document's `lang`/`xml:lang`, or kana
    /// inside the annotations themselves. Other publications keep the plain
    /// CSS policy and an untouched DOM.
    ///
    /// There are deliberately no touch or selectionchange listeners here:
    /// native ranges and handles remain fully owned by Readium/WebKit.
    /// Supplies the horizontal writing mode that Readium alone cannot deliver.
    ///
    /// Readium switches between the `cjk-vertical` and `cjk-horizontal` Readium
    /// CSS variants, but only the vertical variant ever declares `writing-mode`
    /// — the horizontal variant contains no such rule at all. Selecting it
    /// therefore only *stops enforcing* vertical text; it never enforces
    /// horizontal text. A publication that pins its own writing mode (the
    /// common EBPAJ pattern `.vrtl { -webkit-writing-mode: vertical-rl }` on
    /// `<html class="vrtl">`) consequently stays vertical no matter which
    /// preference is submitted or how often the resource is reloaded. This
    /// policy provides the missing declaration.
    ///
    /// In `publication` mode nothing is declared, so the book's own layout and
    /// Readium's vertical variant stay in charge.
    /// - Parameter authoritative: `true` when the host is applying the mode it
    ///   currently wants (and should record it for documents that load later);
    ///   `false` for the document-start injection, whose baked value goes stale
    ///   as soon as the user switches and must defer to the recorded one.
    static func writingModePolicyScript(
        forcesHorizontal: Bool,
        authoritative: Bool = false
    ) -> String {
        let mode = forcesHorizontal ? "horizontal" : "publication"
        let isAuthoritative = authoritative ? "true" : "false"
        return #"""
        (() => {
          let mode = '\#(mode)';
          const authoritative = \#(isAuthoritative);
          try {
            if (authoritative) {
              globalThis.localStorage?.setItem('jerreaderReaderWritingMode', mode);
            } else {
              const stored = globalThis.localStorage?.getItem(
                'jerreaderReaderWritingMode'
              );
              if (stored === 'horizontal' || stored === 'publication') {
                mode = stored;
              }
            }
          } catch (_) {}
          if (globalThis.__jerreaderReaderWritingModePolicy === mode) return mode;

          const styleID = 'jerreader-reader-writing-mode-policy';
          let style = document.getElementById(styleID);
          if (!style) {
            style = document.createElement('style');
            style.id = styleID;
          }

          // `-epub-` and `-ms-` aliases are emitted next to the standard
          // property because Japanese publications still ship them, and a
          // publisher rule that only sets an alias must lose to this one.
          const horizontal =
            '-ms-writing-mode:lr-tb !important;' +
            '-epub-writing-mode:horizontal-tb !important;' +
            '-webkit-writing-mode:horizontal-tb !important;' +
            'writing-mode:horizontal-tb !important;';
          style.textContent = mode === 'horizontal'
            ? ':root,body{' + horizontal +
              '-webkit-text-orientation:mixed !important;' +
              'text-orientation:mixed !important;}' +
              '*{-webkit-writing-mode:horizontal-tb !important;' +
              'writing-mode:horizontal-tb !important;}'
            : '';

          const attachStyle = () => {
            if (style.isConnected) return true;
            const root = document.head || document.documentElement;
            if (!root) return false;
            root.appendChild(style);
            return true;
          };

          // A declaration in the style attribute outranks every author
          // stylesheet rule, including a publisher's own `!important`. Applying
          // it to the elements that actually carry the publication's writing
          // mode is what makes the switch deterministic instead of a
          // specificity race against arbitrary publisher CSS.
          const pin = (element) => {
            if (!element?.style) return;
            if (!element.hasAttribute('data-jerreader-writing-mode')) {
              element.dataset.jerreaderOriginalWritingMode =
                element.style.getPropertyValue('writing-mode');
              element.dataset.jerreaderOriginalWritingModePriority =
                element.style.getPropertyPriority('writing-mode');
              element.dataset.jerreaderOriginalWebkitWritingMode =
                element.style.getPropertyValue('-webkit-writing-mode');
              element.dataset.jerreaderOriginalWebkitWritingModePriority =
                element.style.getPropertyPriority('-webkit-writing-mode');
            }
            element.style.setProperty(
              'writing-mode', 'horizontal-tb', 'important'
            );
            element.style.setProperty(
              '-webkit-writing-mode', 'horizontal-tb', 'important'
            );
            element.dataset.jerreaderWritingMode = 'horizontal';
          };

          const unpin = (element) => {
            if (!element?.style) return;
            if (!element.hasAttribute('data-jerreader-writing-mode')) return;
            const writingMode = element.dataset.jerreaderOriginalWritingMode || '';
            const writingPriority =
              element.dataset.jerreaderOriginalWritingModePriority || '';
            const webkitWritingMode =
              element.dataset.jerreaderOriginalWebkitWritingMode || '';
            const webkitPriority =
              element.dataset.jerreaderOriginalWebkitWritingModePriority || '';
            if (writingMode) {
              element.style.setProperty(
                'writing-mode', writingMode, writingPriority
              );
            } else {
              element.style.removeProperty('writing-mode');
            }
            if (webkitWritingMode) {
              element.style.setProperty(
                '-webkit-writing-mode', webkitWritingMode, webkitPriority
              );
            } else {
              element.style.removeProperty('-webkit-writing-mode');
            }
            delete element.dataset.jerreaderWritingMode;
            delete element.dataset.jerreaderOriginalWritingMode;
            delete element.dataset.jerreaderOriginalWritingModePriority;
            delete element.dataset.jerreaderOriginalWebkitWritingMode;
            delete element.dataset.jerreaderOriginalWebkitWritingModePriority;
          };

          // The `*` rule above already outranks every ordinary publisher
          // declaration. Only a publisher's own `!important` can still win,
          // and only on the elements its selector matches — which may be any
          // element, not just one carrying a class or inline style. Detecting
          // whether such a rule exists at all is far cheaper than resolving
          // the computed style of every element, so the expensive sweep runs
          // only for the rare books that need it.
          const verticalValue = /vertical|tb-/i;
          const hasImportantVerticalRule = () => {
            const scan = (rules) => {
              for (const rule of rules) {
                const declaration = rule.style;
                if (declaration) {
                  const isImportant =
                    declaration.getPropertyPriority('writing-mode') ===
                      'important' ||
                    declaration.getPropertyPriority('-webkit-writing-mode') ===
                      'important';
                  if (isImportant && verticalValue.test(
                    declaration.getPropertyValue('writing-mode') +
                      declaration.getPropertyValue('-webkit-writing-mode')
                  )) {
                    return true;
                  }
                }
                // @media and @supports wrap their own rule lists.
                if (rule.cssRules && scan(rule.cssRules)) return true;
              }
              return false;
            };

            for (const sheet of Array.from(document.styleSheets)) {
              if (sheet.ownerNode === style) continue;
              try {
                if (sheet.cssRules && scan(sheet.cssRules)) return true;
              } catch (_) {
                // An unreadable stylesheet cannot be cleared, so assume the
                // worst and let the sweep decide from the computed styles.
                return true;
              }
            }
            return document.querySelector('[style*="writing-mode"]') !== null;
          };

          // The marker attribute is what makes the reverse switch exact.
          const sweep = () => {
            if (mode !== 'horizontal') {
              document
                .querySelectorAll('[data-jerreader-writing-mode]')
                .forEach(unpin);
              return;
            }
            if (!hasImportantVerticalRule()) return;
            const candidates = document.querySelectorAll('*');
            const limit = Math.min(candidates.length, 20000);
            for (let index = 0; index < limit; index += 1) {
              const element = candidates[index];
              const writingMode =
                getComputedStyle(element).writingMode || '';
              if (verticalValue.test(writingMode)) {
                pin(element);
              }
            }
          };

          const apply = () => {
            attachStyle();
            if (mode === 'horizontal') {
              pin(document.documentElement);
              pin(document.body);
            } else {
              unpin(document.documentElement);
              unpin(document.body);
            }
            sweep();
          };

          apply();
          // At document-start there is no <body> yet. Re-running once the
          // document is parsed covers the body and any wrapper element.
          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', apply, { once: true });
          }
          globalThis.__jerreaderReaderWritingModePolicy = mode;
          return mode;
        })()
        """#
    }

    static func selectionPolicyScript(detachRubyAnnotations: Bool) -> String {
        let bookFlag = detachRubyAnnotations ? "true" : "false"
        return #"""
        (() => {
          const styleID = 'jerreader-reader-selection-policy-v3';
          let style = document.getElementById(styleID);
          if (!style) {
            style = document.createElement('style');
            style.id = styleID;
          }
          style.textContent =
            'rt, rp {' +
            '-webkit-user-select:none !important;' +
            'user-select:none !important;' +
            'pointer-events:none !important;' +
            '}' +
            'rt[data-jerreader-rt]::before {' +
            'content: attr(data-jerreader-rt);' +
            '}';

          const attachStyle = () => {
            if (style.isConnected) return true;
            const root = document.head || document.documentElement;
            if (!root) return false;
            root.appendChild(style);
            return true;
          };

          const bookPrefersDetachment = \#(bookFlag);
          const kanaPattern = /[ぁ-ゟ゠-ヿ]/;
          const documentPrefersDetachment = () => {
            const lang = (
              document.documentElement?.getAttribute('lang') ||
              document.documentElement?.getAttribute('xml:lang') ||
              ''
            ).toLowerCase();
            if (lang.startsWith('ja') || lang.startsWith('jpn')) return true;
            return Array.from(document.querySelectorAll('rt')).some(
              (annotation) =>
                kanaPattern.test(annotation.textContent || '') ||
                kanaPattern.test(annotation.dataset.jerreaderRt || '')
            );
          };

          // Moves an annotation's text into its data attribute. Appending to
          // any previously captured value keeps a reading intact even if its
          // text nodes arrive in separate mutation batches.
          const detachAnnotationText = (annotation) => {
            const key = annotation.localName === 'rp' ? 'jerreaderRp' : 'jerreaderRt';
            if (!annotation.firstChild) {
              if (annotation.dataset[key] == null) annotation.dataset[key] = '';
              return;
            }
            const incoming = (annotation.textContent || '')
              .replace(/\s+/gu, ' ')
              .trim();
            const existing = annotation.dataset[key] || '';
            annotation.dataset[key] = existing + incoming;
            while (annotation.firstChild) {
              annotation.removeChild(annotation.firstChild);
            }
          };

          const sweep = (root) => {
            if (!root || root.nodeType !== Node.ELEMENT_NODE) return;
            if (root.matches?.('rt,rp')) detachAnnotationText(root);
            root.querySelectorAll?.('rt,rp').forEach(detachAnnotationText);
          };

          // The sweep waits for the parsed document: processing a half-parsed
          // rt could capture only the first chunk of its reading, and the
          // Japanese-content check needs the annotations present. Until then
          // the rt still holds real text, which renders identically, and the
          // style above already keeps it unselectable.
          const ready = () => {
            attachStyle();
            if (!(bookPrefersDetachment || documentPrefersDetachment())) {
              return;
            }
            sweep(document.documentElement);
            if (!globalThis.__jerreaderReaderAnnotationObserver) {
              const observer = new MutationObserver((mutations) => {
                for (const mutation of mutations) {
                  const target = mutation.target;
                  const host = target.nodeType === Node.ELEMENT_NODE
                    ? target
                    : target.parentElement;
                  const annotation = host?.closest?.('rt,rp');
                  if (annotation) {
                    detachAnnotationText(annotation);
                    continue;
                  }
                  mutation.addedNodes.forEach((node) => sweep(node));
                }
              });
              observer.observe(document, { childList: true, subtree: true });
              globalThis.__jerreaderReaderAnnotationObserver = observer;
            }
          };

          attachStyle();
          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', ready, { once: true });
          } else {
            ready();
          }
          globalThis.__jerreaderReaderSelectionBridgeVersion = 3;
          return true;
        })()
        """#
    }

    private static let quickSentenceDOMHelpers = #"""
    const JERREADER_READER_EXCLUDED_SELECTOR = [
      'rt', 'rp', 'script', 'style', 'noscript', 'svg',
      '[aria-hidden="true"]',
      '.reader-highlight-layer', '.reader-ui',
      '#jerreader-reader-quick-sentence-highlight',
      '#jerreader-reader-quick-sentence-style',
      '#jerreader-reader-selection-policy-v3'
    ].join(',');

    function jerreaderReaderRectJSON(rect) {
      return { x: rect.left, y: rect.top, width: rect.width, height: rect.height };
    }

    function jerreaderReaderUnionRectJSON(rects) {
      if (!rects?.length) return null;
      const left = Math.min(...rects.map((rect) => rect.left));
      const top = Math.min(...rects.map((rect) => rect.top));
      const right = Math.max(...rects.map((rect) => rect.right));
      const bottom = Math.max(...rects.map((rect) => rect.bottom));
      if (![left, top, right, bottom].every(Number.isFinite) ||
          right <= left || bottom <= top) return null;
      return { x: left, y: top, width: right - left, height: bottom - top };
    }

    function jerreaderReaderIsBaseTextNode(node) {
      if (!node || node.nodeType !== Node.TEXT_NODE || !node.data) return false;
      const parent = node.parentElement;
      if (!parent || parent.closest(JERREADER_READER_EXCLUDED_SELECTOR)) return false;
      const style = getComputedStyle(parent);
      return style.display !== 'none' &&
        style.visibility !== 'hidden' &&
        style.opacity !== '0';
    }

    function jerreaderReaderNodeRole(node) {
      if (!node) return 'missing';
      const element = node.nodeType === Node.ELEMENT_NODE
        ? node
        : node.parentElement;
      if (element?.closest?.('rt,rp')) return 'rubyAnnotation';
      if (node.nodeType === Node.TEXT_NODE && jerreaderReaderIsBaseTextNode(node)) {
        return element?.closest?.('ruby') ? 'rubyBase' : 'baseText';
      }
      if (element?.closest?.(JERREADER_READER_EXCLUDED_SELECTOR)) return 'excluded';
      return 'container';
    }

    function jerreaderReaderTextNodes(block) {
      const nodes = [];
      const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT, {
        acceptNode(node) {
          return jerreaderReaderIsBaseTextNode(node)
            ? NodeFilter.FILTER_ACCEPT
            : NodeFilter.FILTER_REJECT;
        }
      });
      while (walker.nextNode()) nodes.push(walker.currentNode);
      return nodes;
    }

    function jerreaderReaderBaseSubRanges(range, root = document.body || document.documentElement) {
      if (!range || !root) return [];
      const subRanges = [];
      const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
        acceptNode(node) {
          if (!jerreaderReaderIsBaseTextNode(node)) return NodeFilter.FILTER_REJECT;
          try {
            return range.intersectsNode(node)
              ? NodeFilter.FILTER_ACCEPT
              : NodeFilter.FILTER_REJECT;
          } catch (_) {
            return NodeFilter.FILTER_REJECT;
          }
        }
      });

      while (walker.nextNode()) {
        const node = walker.currentNode;
        const subRange = document.createRange();
        subRange.selectNodeContents(node);

        if (range.startContainer === node) {
          subRange.setStart(node, Math.min(range.startOffset, node.data.length));
        }
        if (range.endContainer === node) {
          subRange.setEnd(node, Math.min(range.endOffset, node.data.length));
        }

        // `intersectsNode` above already applies DOM tree order, including
        // element-boundary selections such as selectNodeContents(ruby).
        // Comparing differently rooted boundary-point pairs here reverses the
        // operands in WebKit and can reject every node in a multi-ruby range.
        if (subRange.collapsed) {
          continue;
        }
        subRanges.push(subRange);
      }
      return subRanges;
    }

    function jerreaderReaderRubyRecoveryRanges(selection, range, existingRanges) {
      const annotations = new Set();
      const addAnnotationForNode = (node) => {
        const annotation = jerreaderReaderAnnotationForNode(node);
        if (annotation) annotations.add(annotation);
      };
      addAnnotationForNode(selection?.anchorNode);
      addAnnotationForNode(selection?.focusNode);

      // Some WebKit ruby ranges expose the <ruby> container as both endpoints
      // even though only its <rt> text is selected. Limit this wider scan to
      // the no-base case so an ordinary multi-word selection is never grown.
      if (!annotations.size && !existingRanges.length) {
        document.querySelectorAll('rt,rp').forEach((annotation) => {
          try {
            if (range.intersectsNode(annotation)) annotations.add(annotation);
          } catch (_) {}
        });
      }

      const recovered = [];
      const recoveredNodes = new Set();
      annotations.forEach((annotation) => {
        jerreaderReaderRubyBaseNodes(annotation).forEach((node) => {
          if (!node?.data?.length || recoveredNodes.has(node)) return;
          const alreadyCovered = existingRanges.some((candidate) => {
            try { return candidate.intersectsNode(node); } catch (_) { return false; }
          });
          if (alreadyCovered) return;
          recoveredNodes.add(node);
          const baseRange = document.createRange();
          baseRange.selectNodeContents(node);
          recovered.push(baseRange);
        });
      });
      return recovered;
    }

    function jerreaderReaderOrderedBaseRanges(ranges) {
      return ranges.slice().sort((left, right) => {
        try {
          return left.compareBoundaryPoints(Range.START_TO_START, right);
        } catch (_) {
          return 0;
        }
      });
    }

    function jerreaderReaderBaseSelectionSnapshot(selection) {
      if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return null;
      const range = selection.getRangeAt(0).cloneRange();
      const nativeBaseRanges = jerreaderReaderBaseSubRanges(range);
      const recoveredRanges = jerreaderReaderRubyRecoveryRanges(
        selection,
        range,
        nativeBaseRanges
      );
      const subRanges = jerreaderReaderOrderedBaseRanges([
        ...nativeBaseRanges,
        ...recoveredRanges
      ]);
      if (!subRanges.length) return null;

      const normalizedText = subRanges
        .map((subRange) => subRange.toString())
        .join('')
        .normalize('NFC');
      if (!normalizedText.trim()) return null;

      const rects = jerreaderReaderSelectionRects(subRanges);

      let before = null;
      let after = null;
      const startElement = range.startContainer.nodeType === Node.ELEMENT_NODE
        ? range.startContainer
        : range.startContainer.parentElement;
      const endElement = range.endContainer.nodeType === Node.ELEMENT_NODE
        ? range.endContainer
        : range.endContainer.parentElement;
      const block = jerreaderReaderBlock(startElement);
      if (block && (endElement === block || block.contains(endElement))) {
        const blockNodes = jerreaderReaderTextNodes(block);
        const segmentByNode = new Map();
        subRanges.forEach((subRange) => {
          if (subRange.startContainer === subRange.endContainer &&
              subRange.startContainer.nodeType === Node.TEXT_NODE) {
            segmentByNode.set(subRange.startContainer, {
              start: subRange.startOffset,
              end: subRange.endOffset
            });
          }
        });
        let paragraph = '';
        let selectionStart = null;
        let selectionEnd = null;
        blockNodes.forEach((node) => {
          const segment = segmentByNode.get(node);
          if (segment) {
            if (selectionStart === null) selectionStart = paragraph.length + segment.start;
            selectionEnd = paragraph.length + segment.end;
          }
          paragraph += node.data;
        });
        if (selectionStart !== null && selectionEnd !== null) {
          before = paragraph.slice(0, selectionStart).normalize('NFC');
          after = paragraph.slice(selectionEnd).normalize('NFC');
        }
      }

      return {
        // `Selection.toString()` intentionally hides text styled with
        // user-select:none. Reading the cloned Range instead keeps the
        // diagnostic value useful when WebKit left an annotation endpoint,
        // without mutating the browser-owned selection or its handles.
        rawText: range.toString(),
        normalizedText,
        before,
        after,
        rects,
        recoveredFromRuby: recoveredRanges.length > 0
      };
    }

    function jerreaderReaderBlock(element) {
      if (!element) return null;
      const explicit = element.closest(
        'p,li,blockquote,dd,dt,figcaption,td,th,h1,h2,h3,h4,h5,h6,pre'
      );
      if (explicit) return explicit;

      let candidate = element;
      while (candidate && candidate !== document.body && candidate !== document.documentElement) {
        const display = getComputedStyle(candidate).display;
        if (['block', 'list-item', 'table-cell', 'flex', 'grid'].includes(display) &&
            candidate.textContent && candidate.textContent.trim()) {
          return candidate;
        }
        candidate = candidate.parentElement;
      }
      return null;
    }

    function jerreaderReaderCaretRange(x, y) {
      if (document.caretRangeFromPoint) return document.caretRangeFromPoint(x, y);
      if (!document.caretPositionFromPoint) return null;
      const position = document.caretPositionFromPoint(x, y);
      if (!position) return null;
      const range = document.createRange();
      range.setStart(position.offsetNode, position.offset);
      range.collapse(true);
      return range;
    }

    function jerreaderReaderNearestRect(rects, x, y) {
      let nearest = null;
      let bestDistance = Number.POSITIVE_INFINITY;
      rects.forEach((rect) => {
        const dx = Math.max(rect.left - x, 0, x - rect.right);
        const dy = Math.max(rect.top - y, 0, y - rect.bottom);
        const distance = dx * dx + dy * dy;
        if (distance < bestDistance) {
          bestDistance = distance;
          nearest = rect;
        }
      });
      return bestDistance <= 784 ? nearest : null;
    }

    function jerreaderReaderAnnotationForNode(node) {
      const element = node?.nodeType === Node.ELEMENT_NODE
        ? node
        : node?.parentElement;
      return element?.closest?.('rt,rp') || null;
    }

    function jerreaderReaderNormalizedRubyText(text) {
      return (text || '').replace(/\s+/gu, '');
    }

    function jerreaderReaderRubyAnnotationAtPoint(x, y, selectedText) {
      const selected = jerreaderReaderNormalizedRubyText(selectedText);
      let best = null;
      let bestDistance = Number.POSITIVE_INFINITY;
      document.querySelectorAll('rt,rp').forEach((candidate) => {
        const annotation = jerreaderReaderNormalizedRubyText(candidate.textContent);
        if (selected &&
            !annotation.includes(selected) &&
            !selected.includes(annotation)) {
          return;
        }
        Array.from(candidate.getClientRects()).forEach((rect) => {
          if (!rect.width || !rect.height ||
              rect.right < 0 || rect.bottom < 0 ||
              rect.left > window.innerWidth || rect.top > window.innerHeight) {
            return;
          }
          const dx = Math.max(rect.left - x, 0, x - rect.right);
          const dy = Math.max(rect.top - y, 0, y - rect.bottom);
          const distance = dx * dx + dy * dy;
          const allowedDistance = selected ? 1600 : 16;
          if (distance <= allowedDistance && distance < bestDistance) {
            bestDistance = distance;
            best = candidate;
          }
        });
      });
      return best;
    }

    function jerreaderReaderRubyBaseNodes(annotation) {
      const ruby = annotation?.closest?.('ruby');
      if (!ruby) return [];
      const directChild = Array.from(ruby.childNodes).find(
        (child) => child === annotation || child.contains?.(annotation)
      );
      const children = Array.from(ruby.childNodes);
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
      roots.forEach((root) => {
        if (root.nodeType === Node.TEXT_NODE) {
          if (root.data?.trim()) nodes.push(root);
          return;
        }
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
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
      return nodes.length ? nodes : jerreaderReaderTextNodes(ruby);
    }

    function jerreaderReaderRubyBase(
      caretNode,
      x,
      y,
      preferNativeSelection,
      selectedText
    ) {
      let annotation = null;
      if (preferNativeSelection) {
        const selection = globalThis.getSelection?.();
        const selectedNodes = [selection?.anchorNode, selection?.focusNode];
        for (const selectedNode of selectedNodes) {
          annotation = jerreaderReaderAnnotationForNode(selectedNode);
          if (annotation) break;
        }
      }

      annotation = annotation || jerreaderReaderRubyAnnotationAtPoint(
        x,
        y,
        selectedText
      );

      const caretElement = caretNode.nodeType === Node.ELEMENT_NODE
        ? caretNode
        : caretNode.parentElement;
      annotation = annotation || caretElement?.closest('rt,rp');
      if (!annotation && typeof document.elementsFromPoint === 'function') {
        for (const element of document.elementsFromPoint(x, y)) {
          annotation = element?.closest?.('rt,rp');
          if (annotation) break;
        }
      }
      let ruby = annotation?.closest('ruby') || caretElement?.closest?.('ruby');
      if (!ruby) return null;

      // With annotation hit-testing disabled, caretRangeFromPoint correctly
      // lands on the visible base glyph. Re-associate that base node with the
      // nearest annotation so the same mapping works whether Readium reported
      // the reading or the kanji.
      annotation = annotation || ruby.querySelector('rt,rp');
      if (!annotation) return null;

      const baseNodes = jerreaderReaderRubyBaseNodes(annotation);
      if (!baseNodes.length) return null;
      const allBaseNodes = jerreaderReaderTextNodes(ruby);
      if (!allBaseNodes.length) return null;
      let bestNode = null;
      let bestDistance = Number.POSITIVE_INFINITY;
      baseNodes.forEach((node) => {
        const range = document.createRange();
        range.selectNodeContents(node);
        Array.from(range.getClientRects()).forEach((rect) => {
          const dx = Math.max(rect.left - x, 0, x - rect.right);
          const dy = Math.max(rect.top - y, 0, y - rect.bottom);
          const distance = dx * dx + dy * dy;
          if (distance < bestDistance) {
            bestDistance = distance;
            bestNode = node;
          }
        });
      });

      const node = bestNode || baseNodes[0];
      return {
        node,
        offset: Math.floor(node.data.length / 2),
        ruby,
        baseNodes,
        allBaseNodes,
        annotationText: annotation.textContent || '',
        rubyText: ruby.textContent || ''
      };
    }

    function jerreaderReaderTextHit(
      x,
      y,
      preferNativeRubySelection = false,
      selectedText = null
    ) {
      if (!Number.isFinite(x) || !Number.isFinite(y) ||
          x < 0 || y < 0 || x > window.innerWidth || y > window.innerHeight) {
        return null;
      }
      const caret = jerreaderReaderCaretRange(x, y);
      if (!caret) return null;
      const rubyResolution = jerreaderReaderRubyBase(
        caret.startContainer,
        x,
        y,
        preferNativeRubySelection,
        selectedText
      );
      const caretNode = rubyResolution?.node || caret.startContainer;
      const caretOffset = rubyResolution?.offset ?? caret.startOffset;
      const element = caretNode.nodeType === Node.ELEMENT_NODE
        ? caretNode
        : caretNode.parentElement;
      const block = jerreaderReaderBlock(element);
      if (!block) return null;

      const nodes = jerreaderReaderTextNodes(block);
      if (!nodes.length) return null;
      let paragraph = '';
      let offset = 0;
      let foundCaret = false;
      let rubyBaseStart = -1;
      let rubyBaseEnd = -1;
      nodes.forEach((node) => {
        if (rubyResolution?.allBaseNodes.includes(node)) {
          if (rubyBaseStart < 0) rubyBaseStart = paragraph.length;
          rubyBaseEnd = paragraph.length + node.data.length;
        }
        if (!foundCaret && node === caretNode) {
          offset = paragraph.length + Math.min(caretOffset, node.data.length);
          foundCaret = true;
        }
        paragraph += node.data;
      });
      if (!foundCaret || !paragraph.trim() || paragraph.length > 50000) return null;

      let probeNode = caretNode;
      if (probeNode.nodeType !== Node.TEXT_NODE || !probeNode.data) return null;
      let probeOffset = Math.min(caretOffset, probeNode.data.length);
      if (probeOffset >= probeNode.data.length) probeOffset = Math.max(0, probeOffset - 1);
      if (!probeNode.data.length) return null;
      const probe = document.createRange();
      probe.setStart(probeNode, probeOffset);
      probe.setEnd(probeNode, Math.min(probeOffset + 1, probeNode.data.length));
      const probeRect = jerreaderReaderNearestRect(Array.from(probe.getClientRects()), x, y);
      if (!probeRect) return null;

      const rubyBase = rubyBaseStart >= 0 && rubyBaseEnd > rubyBaseStart
        ? paragraph.slice(rubyBaseStart, rubyBaseEnd)
        : null;
      return {
        block,
        nodes,
        paragraph,
        offset,
        probeRect,
        rubyBase,
        rubyAnnotation: rubyResolution?.annotationText || null,
        rubyText: rubyResolution?.rubyText || null,
        rubyBaseStart: rubyBase ? rubyBaseStart : null,
        rubyBaseEnd: rubyBase ? rubyBaseEnd : null
      };
    }

    function jerreaderReaderBoundary(nodes, requestedOffset) {
      const offset = Math.max(0, requestedOffset);
      let consumed = 0;
      for (const node of nodes) {
        const next = consumed + node.data.length;
        if (offset <= next) {
          return { node, offset: Math.min(offset - consumed, node.data.length) };
        }
        consumed = next;
      }
      const last = nodes[nodes.length - 1];
      return last ? { node: last, offset: last.data.length } : null;
    }

    function jerreaderReaderViewportRectFilter(rect) {
      return rect.width > 0.5 && rect.height > 0.5 &&
        rect.right > 0 && rect.bottom > 0 &&
        rect.left < window.innerWidth && rect.top < window.innerHeight;
    }

    // Finds the rt annotation paired with a base text node inside interleaved
    // mono ruby (base, rt, base, rt, ...): the first rt sibling that follows
    // the node's top-level child within the ruby element.
    function jerreaderReaderPairedAnnotation(node) {
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
          if (!sibling.matches('rp')) break;
        } else if (sibling.nodeType === Node.TEXT_NODE && sibling.data?.trim()) {
          break;
        }
        sibling = sibling.nextSibling;
      }
      return ruby.querySelector('rt');
    }

    // Selection geometry = base-text rectangles plus the rectangles of each
    // paired ruby annotation. The reading is not selectable text, but the
    // visible highlight must cover the whole ruby unit so a selected kanji
    // never looks "skipped" under its furigana.
    function jerreaderReaderSelectionRects(subRanges) {
      const rects = [];
      const annotations = new Set();
      subRanges.forEach((subRange) => {
        rects.push(...Array.from(subRange.getClientRects()));
        const annotation = jerreaderReaderPairedAnnotation(subRange.startContainer);
        if (annotation && !annotations.has(annotation)) {
          annotations.add(annotation);
          rects.push(...Array.from(annotation.getClientRects()));
        }
      });
      return rects.filter(jerreaderReaderViewportRectFilter);
    }
    """#
}
