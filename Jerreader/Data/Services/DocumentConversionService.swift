import Foundation
import NaturalLanguage
@preconcurrency import ReadiumShared
import ReadiumZIPFoundation

struct ReflowableDocumentContent: Equatable, Sendable {
    let title: String
    let author: String
    let language: String?
    let paragraphs: [String]

    var plainText: String {
        paragraphs.joined(separator: "\n\n")
    }
}

enum DocumentConversionError: Error {
    case invalidDOCX
    case unreadableText
    case emptyDocument
    case archiveCreationFailed
    case documentTooLarge
}

enum PlainTextDocumentParser {
    static let maximumBytes = 20 * 1_024 * 1_024

    static func parse(url: URL, fallbackTitle: String) throws -> ReflowableDocumentContent {
        let values = try? url.resourceValues(forKeys: [.fileSizeKey])
        guard (values?.fileSize ?? 0) <= maximumBytes else {
            throw DocumentConversionError.documentTooLarge
        }
        let data = try Data(contentsOf: url, options: [.mappedIfSafe])
        guard let text = decodedString(from: data) else {
            throw DocumentConversionError.unreadableText
        }

        let normalized = text
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        let paragraphs = normalized
            .components(separatedBy: "\n")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        guard !paragraphs.isEmpty else {
            throw DocumentConversionError.emptyDocument
        }

        return ReflowableDocumentContent(
            title: fallbackTitle.nilIfEmpty ?? "未命名文档",
            author: "未知作者",
            language: DocumentLanguageDetector.detect(in: normalized),
            paragraphs: paragraphs
        )
    }

    static func decodedString(from data: Data) -> String? {
        if data.starts(with: [0xEF, 0xBB, 0xBF]) {
            return String(data: data.dropFirst(3), encoding: .utf8)
        }
        if data.starts(with: [0xFF, 0xFE]) {
            return String(data: data, encoding: .utf16LittleEndian)
        }
        if data.starts(with: [0xFE, 0xFF]) {
            return String(data: data, encoding: .utf16BigEndian)
        }

        let encodings: [String.Encoding] = [
            .utf8,
            .utf16,
            .utf16LittleEndian,
            .utf16BigEndian,
            .shiftJIS,
            .isoLatin1,
        ]
        return encodings.lazy.compactMap { String(data: data, encoding: $0) }.first
    }
}

enum DOCXDocumentParser {
    static let maximumDocumentXMLBytes: UInt64 = 32 * 1_024 * 1_024
    static let maximumCorePropertiesBytes: UInt64 = 2 * 1_024 * 1_024

    static func parse(url: URL, fallbackTitle: String) async throws -> ReflowableDocumentContent {
        guard let fileURL = FileURL(url: url) else {
            throw DocumentConversionError.invalidDOCX
        }

        let archive: ContainerAsset
        do {
            archive = try await DefaultArchiveOpener()
                .sniffOpen(resource: FileResource(file: fileURL))
                .get()
        } catch {
            throw DocumentConversionError.invalidDOCX
        }

        guard let documentPath = RelativeURL(path: "word/document.xml"),
              let documentResource = archive.container[documentPath]
        else {
            throw DocumentConversionError.invalidDOCX
        }

        let documentXML: Data
        do {
            if let estimatedLength = try await documentResource.estimatedLength().get(),
               estimatedLength > maximumDocumentXMLBytes
            {
                throw DocumentConversionError.documentTooLarge
            }
            documentXML = try await documentResource.read(
                range: 0 ..< (maximumDocumentXMLBytes + 1)
            ).get()
            guard UInt64(documentXML.count) <= maximumDocumentXMLBytes else {
                throw DocumentConversionError.documentTooLarge
            }
        } catch let error as DocumentConversionError {
            throw error
        } catch {
            throw DocumentConversionError.invalidDOCX
        }

        let coreXML: Data?
        if let corePropertiesPath = RelativeURL(path: "docProps/core.xml"),
           let resource = archive.container[corePropertiesPath]
        {
            coreXML = try? await resource.read(
                range: 0 ..< (maximumCorePropertiesBytes + 1)
            ).get()
        } else {
            coreXML = nil
        }

        return try parse(
            documentXML: documentXML,
            corePropertiesXML: coreXML,
            fallbackTitle: fallbackTitle
        )
    }

    static func parse(
        documentXML: Data,
        corePropertiesXML: Data?,
        fallbackTitle: String
    ) throws -> ReflowableDocumentContent {
        guard UInt64(documentXML.count) <= maximumDocumentXMLBytes,
              UInt64(corePropertiesXML?.count ?? 0) <= maximumCorePropertiesBytes
        else {
            throw DocumentConversionError.documentTooLarge
        }
        let bodyDelegate = DOCXBodyXMLDelegate()
        let bodyParser = XMLParser(data: documentXML)
        bodyParser.shouldProcessNamespaces = true
        bodyParser.delegate = bodyDelegate
        guard bodyParser.parse(), !bodyDelegate.paragraphs.isEmpty else {
            throw DocumentConversionError.emptyDocument
        }

        let metadata: DOCXCoreMetadata
        if let corePropertiesXML {
            let metadataDelegate = DOCXCoreXMLDelegate()
            let metadataParser = XMLParser(data: corePropertiesXML)
            metadataParser.shouldProcessNamespaces = true
            metadataParser.delegate = metadataDelegate
            _ = metadataParser.parse()
            metadata = metadataDelegate.metadata
        } else {
            metadata = DOCXCoreMetadata()
        }

        let fullText = bodyDelegate.paragraphs.joined(separator: "\n\n")
        return ReflowableDocumentContent(
            title: metadata.title?.nilIfEmpty ?? fallbackTitle.nilIfEmpty ?? "未命名文档",
            author: metadata.creator?.nilIfEmpty ?? "未知作者",
            language: metadata.language?.nilIfEmpty ?? DocumentLanguageDetector.detect(in: fullText),
            paragraphs: bodyDelegate.paragraphs
        )
    }
}

enum ReflowableEPUBBuilder {
    static func build(
        content: ReflowableDocumentContent,
        destinationURL: URL
    ) async throws {
        let fileManager = FileManager.default
        if fileManager.fileExists(atPath: destinationURL.path) {
            try fileManager.removeItem(at: destinationURL)
        }

        let language = content.language?.nilIfEmpty ?? "und"
        let identifier = "urn:uuid:\(UUID().uuidString.lowercased())"
        let modified = ISO8601DateFormatter().string(from: Date())
        let title = xmlEscaped(content.title)
        let author = xmlEscaped(content.author)
        let chapterBody = content.paragraphs
            .map { paragraph in
                let body = xmlEscaped(paragraph)
                    .replacingOccurrences(of: "\n", with: "<br/>")
                return "<p>\(body)</p>"
            }
            .joined(separator: "\n")

        let files: [(path: String, data: Data, compressed: Bool)] = [
            ("mimetype", Data("application/epub+zip".utf8), false),
            ("META-INF/container.xml", Data(containerXML.utf8), true),
            ("OEBPS/content.opf", Data("""
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id" xml:lang="\(xmlEscaped(language))">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="book-id">\(identifier)</dc:identifier>
                <dc:title>\(title)</dc:title>
                <dc:creator>\(author)</dc:creator>
                <dc:language>\(xmlEscaped(language))</dc:language>
                <meta property="dcterms:modified">\(modified)</meta>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                <item id="style" href="style.css" media-type="text/css"/>
              </manifest>
              <spine page-progression-direction="ltr">
                <itemref idref="chapter"/>
              </spine>
            </package>
            """.utf8), true),
            ("OEBPS/nav.xhtml", Data("""
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml" lang="\(xmlEscaped(language))" xml:lang="\(xmlEscaped(language))">
              <head><title>目录</title></head>
              <body><nav epub:type="toc" xmlns:epub="http://www.idpf.org/2007/ops"><ol><li><a href="chapter.xhtml">\(title)</a></li></ol></nav></body>
            </html>
            """.utf8), true),
            ("OEBPS/chapter.xhtml", Data("""
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml" lang="\(xmlEscaped(language))" xml:lang="\(xmlEscaped(language))">
              <head>
                <meta charset="utf-8"/>
                <title>\(title)</title>
                <link rel="stylesheet" type="text/css" href="style.css"/>
              </head>
              <body>
                <h1>\(title)</h1>
                \(chapterBody)
              </body>
            </html>
            """.utf8), true),
            ("OEBPS/style.css", Data(styleCSS.utf8), true),
        ]

        do {
            let archive = try await Archive(url: destinationURL, accessMode: .create)
            for file in files {
                let data = file.data
                try await archive.addEntry(
                    with: file.path,
                    type: .file,
                    uncompressedSize: Int64(data.count),
                    compressionMethod: file.compressed ? .deflate : .none
                ) { position, size in
                    let start = min(max(Int(position), 0), data.count)
                    let end = min(start + size, data.count)
                    return data.subdata(in: start ..< end)
                }
            }
        } catch {
            try? fileManager.removeItem(at: destinationURL)
            throw DocumentConversionError.archiveCreationFailed
        }
    }

    private static let containerXML = """
    <?xml version="1.0" encoding="UTF-8"?>
    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
      <rootfiles>
        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
      </rootfiles>
    </container>
    """

    private static let styleCSS = """
    html { -webkit-text-size-adjust: 100%; }
    body {
      font-family: serif;
      line-height: 1.55;
      overflow-wrap: anywhere;
    }
    h1 {
      font-size: 1.55em;
      line-height: 1.25;
      margin: 0 0 1.4em;
    }
    p {
      margin: 0 0 0.85em;
      text-indent: 0;
    }
    """

    private static func xmlEscaped(_ value: String) -> String {
        value
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&apos;")
    }
}

private struct DOCXCoreMetadata: Equatable {
    var title: String?
    var creator: String?
    var language: String?
}

private final class DOCXCoreXMLDelegate: NSObject, XMLParserDelegate {
    private(set) var metadata = DOCXCoreMetadata()
    private var activeElement: String?
    private var buffer = ""

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        let name = localName(elementName, qName)
        guard ["title", "creator", "language"].contains(name) else { return }
        activeElement = name
        buffer = ""
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        guard activeElement != nil else { return }
        buffer += string
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?
    ) {
        let name = localName(elementName, qName)
        guard name == activeElement else { return }
        let value = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
        switch name {
        case "title": metadata.title = value
        case "creator": metadata.creator = value
        case "language": metadata.language = value
        default: break
        }
        activeElement = nil
        buffer = ""
    }
}

private final class DOCXBodyXMLDelegate: NSObject, XMLParserDelegate {
    private(set) var paragraphs: [String] = []
    private var paragraph = ""
    private var isInsideText = false
    private var rubyAnnotationDepth = 0

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        switch localName(elementName, qName) {
        case "p":
            paragraph = ""
        case "t":
            isInsideText = true
        case "tab":
            paragraph += "\t"
        case "br", "cr":
            paragraph += "\n"
        case "rt":
            rubyAnnotationDepth += 1
        default:
            break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        guard isInsideText, rubyAnnotationDepth == 0 else { return }
        paragraph += string
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?
    ) {
        switch localName(elementName, qName) {
        case "t":
            isInsideText = false
        case "rt":
            rubyAnnotationDepth = max(0, rubyAnnotationDepth - 1)
        case "p":
            let value = paragraph.trimmingCharacters(in: .whitespacesAndNewlines)
            if !value.isEmpty {
                paragraphs.append(value)
            }
            paragraph = ""
        default:
            break
        }
    }
}

private enum DocumentLanguageDetector {
    static func detect(in text: String) -> String? {
        let sample = String(text.prefix(8_000))
        guard !sample.isEmpty,
              let language = NLLanguageRecognizer.dominantLanguage(for: sample)
        else {
            return nil
        }
        return language.rawValue
    }
}

private func localName(_ elementName: String, _ qualifiedName: String?) -> String {
    let value = qualifiedName ?? elementName
    return value.split(separator: ":").last.map(String.init) ?? value
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
