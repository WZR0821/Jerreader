import CryptoKit
import Foundation

struct FixturePackageDownloader: DictionaryPackageDownloading {
    let data: Data
    func download(_ url: URL) async throws -> URL {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".csv")
        try data.write(to: file)
        return file
    }
}

@main
struct OfflineDictionaryTests {
    static func main() async throws {
        let csv = "word,phonetic,definition,translation,pos\napple,æpl,fruit,苹果,n\nquote,,\"a, b\",\"第一行\n第二行与\"\"引号\"\"\",n\n"
        let data = Data(csv.utf8)
        var rows: [[String]] = []
        let parser = DictionaryCSVParser { rows.append($0) }
        for byte in data { try parser.feed(Data([byte])) }
        try parser.finish()
        precondition(rows.count == 3 && rows[2][3] == "第一行\n第二行与\"引号\"")
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = OfflineDictionaryPackageStore(directory: directory)
        let manifest = OfflineDictionaryManifest(url: URL(string: "https://example.invalid/fixture")!,
            sha256: SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(), bytes: Int64(data.count), entries: 2)
        try await store.install(manifest: manifest, downloader: FixturePackageDownloader(data: data))
        let entry = await store.lookup(" APPLE ")
        precondition(entry?.definition == "苹果")
        let second = await store.lookup("quote")
        let absent = await store.lookup("missing")
        let ordered = await store.lookup(candidates: ["missing", " QUOTE ", "quote", "apple"])
        let reversed = await store.lookup(candidates: ["apple", "quote"])
        precondition(second?.definition == "第一行\n第二行与\"引号\"" && absent == nil)
        precondition(ordered?.term == "quote" && reversed?.term == "apple", "Candidate order must remain stable")
        let cancelled = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return await store.lookup(candidates: ["apple"])
        }
        let cancelledResult = await cancelled.value
        precondition(cancelledResult == nil, "Cancelled queries must not access the dictionary")
        do {
            try await store.install(manifest: manifest, downloader: FixturePackageDownloader(data: Data("corrupt".utf8)))
            fatalError("Corrupted package was accepted")
        } catch is OfflineDictionaryError { }
        let retained = await store.lookup("apple")
        precondition(retained?.definition == "苹果")
        let updatedData = Data(csv.replacingOccurrences(of: "苹果", with: "苹果（新版）").utf8)
        let updatedManifest = OfflineDictionaryManifest(url: manifest.url,
            sha256: SHA256.hash(data: updatedData).map { String(format: "%02x", $0) }.joined(),
            bytes: Int64(updatedData.count), entries: 2)
        try await store.install(manifest: updatedManifest, downloader: FixturePackageDownloader(data: updatedData))
        let replaced = await store.lookup("apple")
        precondition(replaced?.definition == "苹果（新版）", "Replacement must invalidate an open reader of the old inode")
        try await store.remove()
        let missing = await store.lookup("apple")
        precondition(missing == nil)
        try await store.install(manifest: manifest, downloader: FixturePackageDownloader(data: data))
        let reinstalled = await store.lookup("apple")
        precondition(reinstalled?.definition == "苹果", "Reinstall must reopen the new index")
        print("PASS: CSV boundaries, cached hit/miss/hit, candidate order, cancellation, failed/successful replacement, delete/reinstall")
        try benchmark(directory: directory)
        if CommandLine.arguments.count > 1 {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let target = directory.appendingPathComponent("full.sqlite")
            try OfflineDictionaryIndex.build(source: URL(fileURLWithPath: CommandLine.arguments[1]), target: target, manifest: .english)
            precondition(OfflineDictionaryIndex.lookup("apple", in: target) != nil)
            precondition(OfflineDictionaryIndex.lookup("translation", in: target) != nil)
            print("PASS: actual ECDICT package SHA-256, 770611 input entries and indexed lookups")
        }
    }

    /// Bounded host comparison of opening per query vs retaining the read-only
    /// reader. No speed threshold: this is not a device responsiveness test.
    static func benchmark(directory: URL) throws {
        let url = directory.appendingPathComponent("ecdict-bc015ed.sqlite")
        let clock = ContinuousClock()
        let oldStart = clock.now
        for _ in 0..<500 {
            precondition(OfflineDictionaryIndex.lookup("apple", in: url)?.definition == "苹果")
        }
        let oldDuration = oldStart.duration(to: clock.now)
        let reader = OfflineDictionaryIndex.Reader(url: url)!
        let newStart = clock.now
        for _ in 0..<500 {
            precondition(reader.lookup(candidates: ["apple"])?.definition == "苹果")
        }
        print("BENCHMARK: 500 host queries, reopen=\(oldDuration), reuse=\(newStart.duration(to: clock.now))")
    }
}
