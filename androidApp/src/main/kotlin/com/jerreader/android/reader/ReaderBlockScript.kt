package com.jerreader.android.reader

/**
 * The paragraph a tap or a selection belongs to, found as iOS finds it
 * (`jerreaderReaderBlock` in EPUBReaderScripts.swift): the closest p, li and
 * the like, else the nearest ancestor laid out as a block that has text.
 *
 * 2.1.0: a quick tap used to fall back to the element under the finger, so in
 * a book that sets its paragraphs as div a tap on a kanji under a reading (its
 * ruby or rb) or on emphasised text took only that element's text, often one
 * word, and opened a word lookup instead of translating the sentence.
 */
internal object ReaderBlockScript {
    /** Defines `readerBlockOf(element)`; null when no block lies below the body. */
    val FUNCTION = """
        const readerBlockOf = (element) => {
          if (!element) return null;
          const explicit = element.closest('p,li,blockquote,dd,dt,figcaption,td,th,h1,h2,h3,h4,h5,h6,pre');
          if (explicit) return explicit;
          for (let candidate = element;
               candidate && candidate !== document.body && candidate !== document.documentElement;
               candidate = candidate.parentElement) {
            const display = getComputedStyle(candidate).display;
            if (['block', 'list-item', 'table-cell', 'flex', 'grid'].includes(display) &&
                candidate.textContent && candidate.textContent.trim()) return candidate;
          }
          return null;
        };
    """.trimIndent()
}
