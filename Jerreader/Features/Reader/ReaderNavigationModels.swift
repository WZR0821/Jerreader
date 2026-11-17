import Foundation
@preconcurrency import ReadiumShared

// Table of contents, chapter titles, in-book search results and the
// reader's loading state and timing.

struct ReaderOutlineItem: Identifiable {
    let id = UUID()
    /// Mutable so a junk label can be replaced with the document's own heading
    /// once the resource has been read.
    var title: String
    let link: Link
    let depth: Int
    /// Where this entry starts in the whole publication, once the positions
    /// service has been consulted. Drives both "which chapter am I in" and the
    /// chapter preview shown while the progress bar is being dragged.
    var startProgression: Double?
    /// True when the entry was synthesised from the reading order because the
    /// publication ships no usable table of contents.
    var isGenerated = false

    /// Resource identity used to match a reading position to an entry. Query
    /// and fragment are dropped: a table of contents routinely points at
    /// `chapter.xhtml#section-2` while the reading position reports the bare
    /// resource.
    var resourceKey: String {
        ReaderOutlineItem.resourceKey(for: link.href)
    }

    static func resourceKey(for href: String) -> String {
        var value = href
        if let hashIndex = value.firstIndex(of: "#") {
            value = String(value[value.startIndex ..< hashIndex])
        }
        if let queryIndex = value.firstIndex(of: "?") {
            value = String(value[value.startIndex ..< queryIndex])
        }
        while value.hasPrefix("/") {
            value.removeFirst()
        }
        return value.removingPercentEncoding ?? value
    }
}

/// Decides whether a chapter label is worth showing.
///
/// Publications routinely put junk in their navigation documents: a bare
/// running number (`１`), a generic `本文`, or the book's own title repeated on
/// every entry. Showing those verbatim gives the reader a header that reads
/// "1" for the entire book, which is worse than showing nothing.
enum ReaderChapterTitle {
    private static let genericTitles: Set<String> = [
        "本文", "正文", "目次", "目录", "扉", "表紙", "封面",
        "未命名章节", "untitled", "text", "body", "content", "contents"
    ]

    static func isMeaningful(_ title: String, bookTitle: String? = nil) -> Bool {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count > 1 else { return false }
        if genericTitles.contains(trimmed.lowercased()) { return false }
        if let bookTitle,
           trimmed.compare(
               bookTitle.trimmingCharacters(in: .whitespacesAndNewlines),
               options: [.caseInsensitive, .widthInsensitive]
           ) == .orderedSame {
            return false
        }
        // Bare numbering, half- or full-width, with or without punctuation.
        let stripped = trimmed.folding(
            options: [.widthInsensitive],
            locale: nil
        )
        .trimmingCharacters(
            in: CharacterSet(charactersIn: " .．、,，-–—_()（）[]【】")
        )
        if !stripped.isEmpty,
           stripped.allSatisfy({ $0.isNumber || $0.isPunctuation }) {
            return false
        }
        return true
    }

    /// Best label for the header, preferring the outline over the raw locator
    /// title because the outline can be repaired from document headings.
    static func display(
        outlineTitle: String?,
        locatorTitle: String?,
        bookTitle: String
    ) -> String {
        for candidate in [outlineTitle, locatorTitle] {
            guard let candidate = candidate?
                .trimmingCharacters(in: .whitespacesAndNewlines),
                !candidate.isEmpty
            else { continue }
            if isMeaningful(candidate, bookTitle: bookTitle) {
                return candidate
            }
        }
        return bookTitle
    }
}

/// Builds the outline shown in the navigation sheet.
///
/// Publications regularly ship an empty `<nav epub:type="toc">`, an NCX that
/// only lists the cover, or titles that are blank. Falling back to the reading
/// order keeps chapter navigation and the chapter scrubber usable for those
/// books instead of showing "this book has no contents".
enum ReaderOutlineBuilder {
    static func build(
        tableOfContents: [Link],
        readingOrder: [Link]
    ) -> [ReaderOutlineItem] {
        let declared = flatten(tableOfContents)
        let documents = readingOrder.filter { $0.mediaType?.isHTML != false }

        // A table of contents is only useful if it actually points into the
        // book. Publications exist whose navigation document lists nothing but
        // 表紙 / 目次 / 扉 / 本文 — technically several entries, but the whole
        // novel hides behind one of them, so it can navigate no better than an
        // empty one. Judge it by how many distinct documents it reaches.
        let reachedDocuments = Set(declared.map(\.resourceKey))
        let documentKeys = documents.map { ReaderOutlineItem.resourceKey(for: $0.href) }
        let reachedCount = Set(documentKeys).intersection(reachedDocuments).count
        let isUsable = declared.count > 1
            && (reachedCount > 1 || documents.count <= 2)
        if isUsable {
            return declared
        }

        guard documents.count > 1 else { return declared }

        var used = Set<String>()
        var generated: [ReaderOutlineItem] = []
        for (index, link) in documents.enumerated() {
            let key = ReaderOutlineItem.resourceKey(for: link.href)
            guard used.insert(key).inserted else { continue }
            let declaredTitle = link.title?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfBlank
            generated.append(
                ReaderOutlineItem(
                    title: declaredTitle ?? fallbackTitle(for: link, index: index),
                    link: link,
                    depth: 0,
                    isGenerated: declaredTitle == nil
                )
            )
        }
        return generated.count > declared.count ? generated : declared
    }

    /// Turns `text/part0007_split_002.html` into `第 8 节`-style labels. The
    /// file name is kept when it carries a human-readable name.
    private static func fallbackTitle(for link: Link, index: Int) -> String {
        let file = ReaderOutlineItem.resourceKey(for: link.href)
            .split(separator: "/")
            .last
            .map(String.init) ?? ""
        let stem = file.split(separator: ".").first.map(String.init) ?? file
        let isOpaque = stem.isEmpty
            || stem.range(
                of: #"^(?:[a-z_\-]*\d+[a-z0-9_\-]*)$"#,
                options: [.regularExpression, .caseInsensitive]
            ) != nil
        if isOpaque {
            return "第 \(index + 1) 节"
        }
        return stem.replacingOccurrences(of: "_", with: " ")
    }

    private static func flatten(
        _ links: [Link],
        depth: Int = 0
    ) -> [ReaderOutlineItem] {
        links.flatMap { link -> [ReaderOutlineItem] in
            let title = link.title?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfBlank ?? "未命名章节"
            return [ReaderOutlineItem(title: title, link: link, depth: depth)]
                + flatten(link.children, depth: depth + 1)
        }
    }

    /// Reads the given resources and returns a chapter name per resource key.
    ///
    /// Kept off the main actor — like the layout detector — because Readium's
    /// `Resource` is not `Sendable` and must not cross an actor boundary.
    static func repairedTitles(
        in publication: Publication,
        links: [Link],
        bookTitle: String,
        maximumBytesPerResource: UInt64 = 96 * 1_024
    ) async -> [String: String] {
        var titles: [String: String] = [:]
        for link in links {
            let key = ReaderOutlineItem.resourceKey(for: link.href)
            guard titles[key] == nil else { continue }
            guard let resource = publication.get(link) else { continue }
            let data = await resource.read(
                range: 0 ..< maximumBytesPerResource
            ).getOrNil()
            guard let data, !data.isEmpty else { continue }
            guard let title = extractedTitle(
                fromHTML: String(decoding: data, as: UTF8.self),
                bookTitle: bookTitle
            ) else { continue }
            titles[key] = title
        }
        return titles
    }

    /// Recovers a chapter name from the document itself.
    ///
    /// Used for entries the publication left unlabelled or labelled with a
    /// bare number. Real headings are checked first; Japanese ebooks that use
    /// styled paragraphs instead of `<h*>` are covered by the common
    /// 見出し/midashi class names, and `<title>` is the last resort.
    static func extractedTitle(fromHTML html: String, bookTitle: String?) -> String? {
        let patterns = [
            #"<h[1-6][^>]*>([\s\S]{1,200}?)</h[1-6]>"#,
            #"<[a-z]+[^>]*class="[^"]*(?:midashi|mihdashi|chapter|caption|title)[^"]*"[^>]*>([\s\S]{1,200}?)</[a-z]+>"#,
            #"<title[^>]*>([\s\S]{1,200}?)</title>"#
        ]
        for pattern in patterns {
            guard let range = html.range(
                of: pattern,
                options: [.regularExpression, .caseInsensitive]
            ) else { continue }
            let candidate = plainText(String(html[range]))
            if ReaderChapterTitle.isMeaningful(candidate, bookTitle: bookTitle) {
                return candidate
            }
        }
        return nil
    }

    private static func plainText(_ markup: String) -> String {
        let withoutTags = markup.replacingOccurrences(
            of: "<[^>]+>",
            with: " ",
            options: .regularExpression
        )
        let decoded = withoutTags
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&#160;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
        return decoded
            .replacingOccurrences(
                of: "\\s+",
                with: " ",
                options: .regularExpression
            )
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

private extension String {
    var nilIfBlank: String? {
        isEmpty ? nil : self
    }
}

struct ReaderSearchResult: Identifiable {
    let id = UUID()
    let locator: Locator

    var chapterTitle: String {
        let title = locator.title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return title.isEmpty ? "正文" : title
    }

    var progress: Double? {
        locator.locations.totalProgression
    }

    var text: Locator.Text {
        locator.text.sanitized()
    }
}

enum ReaderSearchError: LocalizedError {
    case emptyQuery
    case unavailable
    case failed

    var errorDescription: String? {
        switch self {
        case .emptyQuery:
            return "请输入要搜索的文字。"
        case .unavailable:
            return "这份文档暂时不支持全文搜索。扫描 PDF 需要先有可搜索文字层。"
        case .failed:
            return "搜索没有完成，请稍后重试。"
        }
    }
}

enum ReaderLoadState {
    case loading
    case ready
    case failed(String)
}

struct ReaderLoadTiming: Sendable {
    let openTimeout: Duration

    static let standard = ReaderLoadTiming(openTimeout: .seconds(25))
}

struct ReaderQuickTranslationIndicatorTiming: Sendable {
    let autoExitDelay: Duration

    static let standard = ReaderQuickTranslationIndicatorTiming(
        autoExitDelay: .seconds(5)
    )
}
