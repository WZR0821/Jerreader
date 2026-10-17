import CryptoKit
import Foundation
import SQLite3

struct OfflineDictionaryManifest: Sendable {
    var url: URL
    var sha256: String
    var bytes: Int64
    var entries: Int
    static let english = Self(
        url: URL(string: "https://raw.githubusercontent.com/skywind3000/ECDICT/bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b/ecdict.csv")!,
        sha256: "1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf",
        bytes: 65_933_428, entries: 770_611)
    static let licenseText = """
MIT License

Copyright (c) 2025 Linwei

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"""
    static let version = "bc015ed2e24a"
    static let source = URL(string: "https://github.com/skywind3000/ECDICT")!
    static let license = URL(string: "https://github.com/skywind3000/ECDICT/blob/bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b/LICENSE")!
}

enum OfflineDictionaryError: LocalizedError {
    case invalid, diskSpace, busy
    var errorDescription: String? {
        switch self {
        case .invalid: return "词典包未通过校验或索引失败，已有词典保留，请重试。"
        case .diskSpace: return "存储空间不足，请预留至少 300 MB 后重试。"
        case .busy: return "词典正在安装，请稍候。"
        }
    }
}

protocol DictionaryPackageDownloading: Sendable {
    /// Returns an owned temporary file. Tests supply a local copy, without network access.
    func download(_ url: URL) async throws -> URL
}

struct URLSessionDictionaryPackageDownloader: DictionaryPackageDownloading {
    func download(_ url: URL) async throws -> URL {
        let (file, response) = try await URLSession.shared.download(for: URLRequest(url: url, timeoutInterval: 180))
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            try? FileManager.default.removeItem(at: file)
            throw OfflineDictionaryError.invalid
        }
        return file
    }
}

/// Incremental CSV parser: supports quoted commas, doubled quotes and embedded newlines.
final class DictionaryCSVParser {
    private var field: [UInt8] = []
    private var row: [String] = []
    private var quoted = false
    private var closingQuote = false
    private let emit: ([String]) throws -> Void
    init(emit: @escaping ([String]) throws -> Void) { self.emit = emit }
    func feed(_ bytes: Data) throws {
        for byte in bytes {
            if quoted {
                if byte == 34 { if closingQuote { field.append(34); closingQuote = false } else { closingQuote = true }; continue }
                if !closingQuote { field.append(byte); continue }
                quoted = false; closingQuote = false
            }
            switch byte {
            case 34 where field.isEmpty: quoted = true
            case 44: endField()
            case 10: endField(); try emit(row); row.removeAll(keepingCapacity: true)
            case 13: break
            default: field.append(byte)
            }
            guard field.count < 1_000_000, row.count < 100 else { throw OfflineDictionaryError.invalid }
        }
    }
    func finish() throws {
        guard !quoted || closingQuote else { throw OfflineDictionaryError.invalid }
        if !field.isEmpty || !row.isEmpty { endField(); try emit(row) }
    }
    private func endField() {
        row.append(String(decoding: field, as: UTF8.self))
        field.removeAll(keepingCapacity: true)
    }
}

struct OfflineDictionaryEntry: Sendable {
    var term: String
    var reading: String
    var definition: String
    var partOfSpeech: String
}

enum OfflineDictionaryIndex {
    static func build(source: URL, target: URL, manifest: OfflineDictionaryManifest) throws {
        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        var hash = SHA256(); var bytes: Int64 = 0
        while let chunk = try input.read(upToCount: 1_048_576), !chunk.isEmpty {
            try Task.checkCancellation(); bytes += Int64(chunk.count); hash.update(data: chunk)
            guard bytes <= manifest.bytes else { throw OfflineDictionaryError.invalid }
        }
        guard bytes == manifest.bytes, hash.finalize().map({ String(format: "%02x", $0) }).joined() == manifest.sha256 else { throw OfflineDictionaryError.invalid }
        try input.seek(toOffset: 0)
        var db: OpaquePointer?
        guard sqlite3_open(target.path, &db) == SQLITE_OK else { sqlite3_close(db); throw OfflineDictionaryError.invalid }
        defer { sqlite3_close(db) }
        guard sqlite3_exec(db, "PRAGMA journal_mode=OFF; CREATE TABLE entries (term TEXT PRIMARY KEY, reading TEXT, definition TEXT, pos TEXT); BEGIN TRANSACTION;", nil, nil, nil) == SQLITE_OK else { throw OfflineDictionaryError.invalid }
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO entries VALUES (?,?,?,?)", -1, &statement, nil) == SQLITE_OK else { throw OfflineDictionaryError.invalid }
        defer { sqlite3_finalize(statement) }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        var first = true; var count = 0
        let parser = DictionaryCSVParser { fields in
            if first { first = false; guard fields.first == "word" else { throw OfflineDictionaryError.invalid }; return }
            guard fields.count >= 5, !fields[0].isEmpty, !fields[2].isEmpty || !fields[3].isEmpty else { return }
            let definition = (fields[3].isEmpty ? fields[2] : fields[3]).replacingOccurrences(of: "\\n", with: "\n")
            let values = [fields[0].lowercased(), fields[1], definition, fields[4]]
            for (index, value) in values.enumerated() {
                guard sqlite3_bind_text(statement, Int32(index + 1), value, -1, transient) == SQLITE_OK else { throw OfflineDictionaryError.invalid }
            }
            guard sqlite3_step(statement) == SQLITE_DONE else { throw OfflineDictionaryError.invalid }
            sqlite3_reset(statement); sqlite3_clear_bindings(statement); count += 1
        }
        while let chunk = try input.read(upToCount: 65_536), !chunk.isEmpty {
            try Task.checkCancellation(); try parser.feed(chunk)
        }
        try parser.finish()
        guard count == manifest.entries, sqlite3_exec(db, "COMMIT", nil, nil, nil) == SQLITE_OK else { throw OfflineDictionaryError.invalid }
    }

    static func lookup(_ term: String, in url: URL) -> OfflineDictionaryEntry? {
        Reader(url: url)?.lookup(candidates: [term])
    }

    /// Confined to the package-store actor. Reuse one read-only connection and
    /// prepared query; never send SQLite handles across tasks.
    final class Reader {
        private var db: OpaquePointer?
        private var statement: OpaquePointer?

        init?(url: URL) {
            guard sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READONLY, nil) == SQLITE_OK,
                  sqlite3_prepare_v2(db, "SELECT term,reading,definition,pos FROM entries WHERE term=?", -1, &statement, nil) == SQLITE_OK
            else {
                sqlite3_finalize(statement)
                sqlite3_close(db)
                statement = nil
                db = nil
                return nil
            }
        }

        deinit {
            sqlite3_finalize(statement)
            sqlite3_close(db)
        }

        func lookup(candidates: [String]) -> OfflineDictionaryEntry? {
            var seen = Set<String>()
            for candidate in candidates {
                guard !Task.isCancelled else { return nil }
                let term = candidate.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                guard !term.isEmpty, seen.insert(term).inserted else { continue }
                if let entry = lookupNormalized(term) { return entry }
            }
            return nil
        }

        private func lookupNormalized(_ term: String) -> OfflineDictionaryEntry? {
            defer {
                sqlite3_reset(statement)
                sqlite3_clear_bindings(statement)
            }
            guard sqlite3_bind_text(statement, 1, term, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self)) == SQLITE_OK,
                  sqlite3_step(statement) == SQLITE_ROW else { return nil }
            func text(_ index: Int32) -> String { sqlite3_column_text(statement, index).map { String(cString: $0) } ?? "" }
            return OfflineDictionaryEntry(term: text(0), reading: text(1), definition: text(2), partOfSpeech: text(3))
        }
    }
}

actor OfflineDictionaryPackageStore {
    static let shared = OfflineDictionaryPackageStore()
    private let directory: URL
    private var installing = false
    private var reader: OfflineDictionaryIndex.Reader?
    private var indexURL: URL { directory.appendingPathComponent("ecdict-bc015ed.sqlite") }
    init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Jerreader/OfflineDictionaries", isDirectory: true)
    }
    func installed() -> Bool { FileManager.default.fileExists(atPath: indexURL.path) }
    func remove() throws {
        guard !installing else { throw OfflineDictionaryError.busy }
        if installed() { try FileManager.default.removeItem(at: indexURL) }
        reader = nil
    }
    func install(manifest: OfflineDictionaryManifest = .english,
                 downloader: any DictionaryPackageDownloading = URLSessionDictionaryPackageDownloader()) async throws {
        guard !installing else { throw OfflineDictionaryError.busy }
        try Task.checkCancellation()
        installing = true
        defer { installing = false }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let capacity = try directory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
        guard capacity > max(manifest.bytes * 4, 300_000_000) else { throw OfflineDictionaryError.diskSpace }
        let source = try await downloader.download(manifest.url)
        let temporary = directory.appendingPathComponent(UUID().uuidString + ".sqlite")
        defer { try? FileManager.default.removeItem(at: source); try? FileManager.default.removeItem(at: temporary) }
        let build = Task.detached(priority: .utility) { try OfflineDictionaryIndex.build(source: source, target: temporary, manifest: manifest) }
        try await withTaskCancellationHandler { try await build.value } onCancel: { build.cancel() }
        try Task.checkCancellation()
        guard rename(temporary.path, indexURL.path) == 0 else { throw OfflineDictionaryError.invalid }
        // A reader opened before the atomic replacement still points at the
        // old inode. Invalidate only after success so failed installs keep working.
        reader = nil
        var url = indexURL
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
    }
    func lookup(_ term: String) -> OfflineDictionaryEntry? {
        lookup(candidates: [term])
    }

    func lookup(candidates: [String]) -> OfflineDictionaryEntry? {
        guard !Task.isCancelled, !candidates.isEmpty else { return nil }
        if reader == nil { reader = OfflineDictionaryIndex.Reader(url: indexURL) }
        return reader?.lookup(candidates: candidates)
    }
}
