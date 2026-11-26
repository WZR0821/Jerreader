import Foundation
import JavaScriptCore

// Host test for the reader's ruby-aware selection tiles. Runs
// ReaderSelectionTileScript.source in JavaScriptCore against
// shared/parity/selection_tiles.json, the same cases Android checks.
// Run from the repository root (safe_ios_validation.py host-test does).

@main
struct SelectionTileTests {
    static var failures = 0

    static func fail(_ message: String) {
        failures += 1
        print("FAIL " + message)
    }

    static func expect(_ condition: Bool, _ message: String) {
        if !condition { fail(message) }
    }

    static func load(_ name: String) -> [String: Any] {
        let url = URL(fileURLWithPath: "shared/parity/" + name)
        guard let data = try? Data(contentsOf: url),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            print("FAIL cannot read shared/parity/" + name + " (run from the repository root)")
            exit(1)
        }
        return object
    }

    static func makeContext() -> JSContext {
        guard let context = JSContext() else {
            print("FAIL cannot create a JavaScriptCore context")
            exit(1)
        }
        context.exceptionHandler = { _, value in
            fail("JavaScript exception: " + (value?.toString() ?? "unknown"))
        }
        context.evaluateScript(ReaderSelectionTileScript.source)
        // Fixture rects are [x, y, width, height]; the script takes and returns
        // DOMRect-like objects.
        context.evaluateScript(#"""
        function jerreaderSelectionTilesFixture(json) {
          const input = JSON.parse(json);
          const rect = (r) => ({ left: r[0], top: r[1], right: r[0] + r[2], bottom: r[1] + r[3] });
          const annotations = (input.annotations || []).map((a) => {
            const out = { base: a.base, rects: a.rects.map(rect) };
            if (Object.prototype.hasOwnProperty.call(a, 'fullySelected')) out.fullySelected = a.fullySelected;
            return out;
          });
          const tiles = jerreaderSelectionTiles(input.base.map(rect), annotations, !!input.vertical);
          return JSON.stringify(tiles.map((t) => ({
            box: [t.left, t.top, t.width, t.height],
            consistent: t.x === t.left && t.y === t.top &&
              Math.abs(t.right - t.left - t.width) < 1e-9 &&
              Math.abs(t.bottom - t.top - t.height) < 1e-9
          })));
        }
        """#)
        return context
    }

    static func tiles(_ context: JSContext, _ input: [String: Any]) -> [[Double]]? {
        guard let data = try? JSONSerialization.data(withJSONObject: input),
              let json = String(data: data, encoding: .utf8),
              let function = context.objectForKeyedSubscript("jerreaderSelectionTilesFixture"),
              let output = function.call(withArguments: [json])?.toString(),
              let outputData = output.data(using: .utf8),
              let decoded = try? JSONSerialization.jsonObject(with: outputData) as? [[String: Any]] else {
            return nil
        }
        var result: [[Double]] = []
        for tile in decoded {
            guard let box = tile["box"] as? [Double], box.count == 4 else { return nil }
            if tile["consistent"] as? Bool != true {
                fail("tile \(box) has inconsistent DOMRect fields")
            }
            result.append(box)
        }
        return result
    }

    static func numbers(_ value: Any?) -> [[Double]] {
        (value as? [[Any]] ?? []).map { row in row.map { ($0 as? NSNumber)?.doubleValue ?? .nan } }
    }

    static func matches(_ actual: [[Double]], _ expected: [[Double]]) -> Bool {
        actual.count == expected.count && zip(actual, expected).allSatisfy { got, want in
            got.count == want.count && zip(got, want).allSatisfy { abs($0 - $1) <= 0.001 }
        }
    }

    static func main() {
        let context = makeContext()
        let fixture = load("selection_tiles.json")
        let cases = fixture["cases"] as? [[String: Any]] ?? []
        expect(cases.count >= 10, "selection_tiles.json should carry at least 10 cases, found \(cases.count)")

        for testCase in cases {
            let name = testCase["name"] as? String ?? "?"
            let expected = numbers(testCase["tiles"])
            guard let actual = tiles(context, testCase) else {
                fail(name + ": script returned nothing")
                continue
            }
            expect(matches(actual, expected), name + ": expected \(expected), got \(actual)")

            // The UIKit overlay and Android's anchor merge run the tiles through
            // a plain line merge again; tiles must survive that unchanged.
            let again = tiles(context, [
                "vertical": testCase["vertical"] as? Bool ?? false,
                "base": actual,
                "annotations": [[String: Any]]()
            ])
            expect(again.map { matches($0, actual) } == true,
                   name + ": re-merging the tiles changed them to \(again ?? [])")
        }

        // An annotation without `fullySelected` counts as fully selected,
        // matching ReaderRubyAnnotationRects' default on Android.
        let partial = cases.first { ($0["name"] as? String)?.contains("partly selected") == true }
        if var testCase = partial,
           var annotations = testCase["annotations"] as? [[String: Any]],
           !annotations.isEmpty {
            annotations[0].removeValue(forKey: "fullySelected")
            testCase["annotations"] = annotations
            let actual = tiles(context, testCase) ?? []
            expect(matches(actual, [[52, 88, 96, 36]]),
                   "missing fullySelected should keep the whole reading, got \(actual)")
        } else {
            fail("the partly selected group ruby case is missing")
        }

        // Unusable boxes never become tiles, and unknown base indices are ignored.
        let degenerate = tiles(context, [
            "vertical": false,
            "base": [[10, 10, 0.2, 20], [20, 100, 40, 24]],
            "annotations": [["base": [0, 7], "rects": [[20, 88, 40, 0.3]], "fullySelected": true]]
        ]) ?? [[-1]]
        expect(matches(degenerate, [[20, 100, 40, 24]]), "degenerate boxes leaked into \(degenerate)")
        let empty = tiles(context, ["vertical": true, "base": [[Double]](), "annotations": [[String: Any]]()])
        expect(empty.map(\.isEmpty) == true, "an empty selection should give no tiles")

        if failures > 0 {
            print("selection tiles: \(failures) failure(s)")
            exit(1)
        }
        print("selection tiles: \(cases.count) fixture cases passed")
    }
}
