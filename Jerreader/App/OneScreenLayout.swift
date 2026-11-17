import SwiftUI
import UIKit

/// A page that is exactly one screen tall whenever its content can be: the
/// content is offered the height between the bars, so nothing scrolls or
/// bounces. Content that cannot be that short (large text, a short screen, the
/// keyboard) scrolls as usual.
struct OneScreenPage<Content: View>: View {
    var identifier: String
    /// One view. It is handed the size of the space between the bars.
    @ViewBuilder var content: (CGSize) -> Content
    @Environment(\.dynamicTypeSize) private var typeSize
    /// The top inset with the large title at full height, for each width and
    /// text size the page has been shown at: the bars differ between them.
    @State private var fullTops: [Bars: CGFloat] = [:]
    @State private var fit = Fit(fits: true, collapsed: false)
    @State private var scroll = ScrollAnchor.Handle()
    @State private var settling: Task<Void, Never>?

    var body: some View {
        GeometryReader { proxy in
            let bars = Bars(width: proxy.size.width, type: typeSize)
            let top = proxy.safeAreaInsets.top
            // A large title hands its height to the page as it collapses. The
            // page is laid out for the title at full height all along: were it
            // to grow with the collapsing title, a page a little too tall
            // would come to fit halfway through a scroll, stop scrolling, and
            // leave the title collapsed.
            let fullTop = max(fullTops[bars] ?? 0, top)
            let room = proxy.size.height - (fullTop - top)
            ScrollView {
                AtLeast(height: room) { content(CGSize(width: proxy.size.width, height: room)) }
                    .background { ScrollAnchor(handle: scroll).allowsHitTesting(false).accessibilityHidden(true) }
            }
            // One screen under its large title does not move. Anything else
            // bounces like any other page: once the title has collapsed, a
            // page may be shorter than what shows, and a pull is the way to
            // get the title back.
            .scrollBounceBehavior(fit.fits && !fit.collapsed ? .basedOnSize : .always)
            .accessibilityIdentifier(identifier)
            .onChange(of: fullTop, initial: true) { fullTops[bars] = fullTop }
            .onChange(of: bars) { fullTops[bars] = fullTop }
            .onScrollGeometryChange(for: Fit.self) { geometry in
                let gone = fullTop - geometry.contentInsets.top
                return Fit(fits: geometry.contentSize.height <= geometry.containerSize.height - gone + 0.5,
                           collapsed: gone > 0.5)
            } action: { _, now in
                fit = now
            }
            .onChange(of: fit.fits) { _, fits in
                // A page that scrolled while the keyboard was up comes back to
                // one screen with its large title still collapsed. iOS 18 does
                // not always bring the title back by itself, so the page does
                // it. It looks again for a little while: the keyboard going
                // away moves the scroll view more than once.
                settling?.cancel()
                settling = nil
                guard fits else { return }
                settling = Task {
                    for _ in 0..<10 {
                        scroll.settle(under: fullTop)
                        try? await Task.sleep(for: .milliseconds(60))
                        if Task.isCancelled { return }
                    }
                }
            }
            #if DEBUG
            .modifier(OneScreenProbe(identifier: identifier))
            #endif
        }
        #if DEBUG
        .safeAreaInset(edge: .bottom, spacing: 0) { Color.clear.frame(height: OneScreenProbe.shorter) }
        #endif
    }
}

/// What decides how tall the bars above a page are.
private struct Bars: Hashable {
    var width: CGFloat, type: DynamicTypeSize
}

/// How a page stands in its scroll view.
private struct Fit: Equatable {
    /// The page is no taller than the room under the large title at full height.
    var fits: Bool
    /// The large title is collapsed, or partly so.
    var collapsed: Bool
}

/// A view inside a page's scroll view, through which the page can reach it.
private struct ScrollAnchor: UIViewRepresentable {
    final class Handle {
        fileprivate weak var view: UIView?

        /// One step towards the content at rest under bars `top` points deep.
        ///
        /// While the large title is collapsed the bars are less deep than
        /// that. The bar takes content a point lower than where it rests for
        /// a pull: the title grows back to full and the bar takes the content
        /// down with it. A try that changed nothing is undone by the next
        /// step, so that the one after it can try again.
        @MainActor func settle(under top: CGFloat) {
            var next = view?.superview
            while let current = next, !(current is UIScrollView) { next = current.superview }
            // A finger on the page brings the title back by itself, and so
            // does coming back to the page from another one.
            guard let scroll = next as? UIScrollView, let window = scroll.window,
                  !scroll.isTracking, !scroll.isDecelerating, isInFront(scroll) else { return }
            let x = scroll.contentOffset.x, y = scroll.contentOffset.y, inset = scroll.adjustedContentInset.top
            if inset < top - 0.5, y > -inset - 0.5 {
                UIView.animate(withDuration: 0.3) {
                    scroll.setContentOffset(CGPoint(x: x, y: -inset - 1), animated: false)
                    // The bar grows here, inside the animation.
                    window.layoutIfNeeded()
                    let grown = scroll.adjustedContentInset.top
                    if grown > inset + 0.5 { scroll.setContentOffset(CGPoint(x: x, y: -grown), animated: false) }
                }
            } else if abs(y + inset) > 0.5 {
                scroll.setContentOffset(CGPoint(x: x, y: -inset), animated: false)
            }
        }

        /// The page is the one showing in its navigation stack, and no push
        /// or pop is under way.
        @MainActor private func isInFront(_ scroll: UIScrollView) -> Bool {
            var responder: UIResponder? = scroll
            while let current = responder, !(current is UIViewController) { responder = current.next }
            guard var page = responder as? UIViewController, let stack = page.navigationController else { return true }
            guard stack.transitionCoordinator == nil else { return false }
            while let parent = page.parent, parent !== stack { page = parent }
            return stack.topViewController === page
        }
    }

    var handle: Handle

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.isUserInteractionEnabled = false
        handle.view = view
        return view
    }

    func updateUIView(_ view: UIView, context: Context) { handle.view = view }
}

#if DEBUG
/// For UI tests only (`--jerreader-one-screen-probe`). It reports the scroll
/// view's content height, the room it has, how far it has ever been moved, and
/// the inset under the top bar, as the label of an element named
/// `<identifier>Probe`. `--jerreader-fixture-shorter=<points>` takes that much
/// off the bottom of the page to stand in for a shorter phone.
private struct OneScreenProbe: ViewModifier {
    var identifier: String
    @State private var report = ""
    @State private var moved: CGFloat = 0

    private struct Reading: Equatable { var content: CGFloat, room: CGFloat, offset: CGFloat, top: CGFloat }

    static var shorter: CGFloat {
        let prefix = "--jerreader-fixture-shorter="
        guard let argument = ProcessInfo.processInfo.arguments.first(where: { $0.hasPrefix(prefix) }),
              let points = Double(argument.dropFirst(prefix.count)) else { return 0 }
        return CGFloat(points)
    }

    func body(content: Content) -> some View {
        if ProcessInfo.processInfo.arguments.contains("--jerreader-one-screen-probe") {
            content
                .onScrollGeometryChange(for: Reading.self) { geometry in
                    // The container size already leaves the bars out.
                    Reading(content: geometry.contentSize.height, room: geometry.containerSize.height,
                            offset: geometry.contentOffset.y + geometry.contentInsets.top, top: geometry.contentInsets.top)
                } action: { _, reading in
                    moved = max(moved, abs(reading.offset))
                    report = String(format: "content=%.1f room=%.1f moved=%.1f top=%.1f",
                                    reading.content, reading.room, moved, reading.top)
                }
                .overlay(alignment: .topLeading) {
                    Color.clear.frame(width: 1, height: 1).accessibilityElement()
                        .accessibilityLabel(report).accessibilityIdentifier(identifier + "Probe")
                }
        } else {
            content
        }
    }
}
#endif

/// Offers its one child the given height, and takes more only when the child
/// cannot be that short. `frame(minHeight:)` would not do: inside a scroll view
/// it measures the child at its ideal height first, so a child that could have
/// given way never gets asked.
private struct AtLeast: Layout {
    var height: CGFloat

    /// What to offer the child: the height, or nothing in particular when the
    /// child cannot be that short however it gives way. It then keeps its
    /// natural size, and the page scrolls.
    private func offer(_ child: LayoutSubview, width: CGFloat?) -> CGFloat? {
        child.sizeThatFits(ProposedViewSize(width: width, height: height)).height > height + 0.01 ? nil : height
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let child = subviews.first else { return .zero }
        let size = child.sizeThatFits(ProposedViewSize(width: proposal.width, height: offer(child, width: proposal.width)))
        return CGSize(width: proposal.width ?? size.width, height: max(size.height, height))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let child = subviews.first else { return }
        child.place(at: bounds.origin, anchor: .topLeading,
                    proposal: ProposedViewSize(width: bounds.width, height: offer(child, width: bounds.width)))
    }
}

/// A vertical stack that hands spare height to the children that ask for it.
/// `VStack` splits spare height one nesting level at a time; here every child
/// marked `stretch(_:)` grows by the same amount, up to its limit. When the
/// height is too little, the children marked `shrinks()` give way first, and
/// those marked `squeezes()` make up what is still missing.
struct FillStack<Content: View>: View {
    @Environment(\.displayScale) private var scale
    @ViewBuilder var content: Content

    var body: some View { FillLayout(pixel: 1 / max(scale, 1)) { content } }
}

private struct StretchLimit: LayoutValueKey { static let defaultValue: CGFloat = 0 }
private enum Yield { case no, first, last }
private struct Yielding: LayoutValueKey { static let defaultValue = Yield.no }

extension View {
    /// In a `FillStack`: this child may grow by up to `limit` points when there is spare height.
    func stretch(_ limit: CGFloat) -> some View { layoutValue(key: StretchLimit.self, value: limit) }

    /// In a `FillStack`: this child can be shorter than it would like, in a
    /// step, for example text that drops a line. All such children step down
    /// together, before anything is squeezed.
    func shrinks() -> some View { layoutValue(key: Yielding.self, value: .first) }

    /// In a `FillStack`: this child can be any height down to its least. It
    /// gives up only what is still missing once the others have stepped down.
    func squeezes() -> some View { layoutValue(key: Yielding.self, value: .last) }
}

private struct FillLayout: Layout {
    var pixel: CGFloat

    static var layoutProperties: LayoutProperties {
        var properties = LayoutProperties()
        properties.stackOrientation = .vertical
        return properties
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let height = heights(width: proposal.width, height: proposal.height, subviews).reduce(0, +)
        let width = proposal.width ?? subviews.map { $0.sizeThatFits(.unspecified).width }.max() ?? 0
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for (child, height) in zip(subviews, heights(width: bounds.width, height: bounds.height, subviews)) {
            child.place(at: CGPoint(x: bounds.minX, y: y), anchor: .topLeading,
                        proposal: ProposedViewSize(width: bounds.width, height: height))
            y += height
        }
    }

    private func heights(width: CGFloat?, height: CGFloat?, _ subviews: Subviews) -> [CGFloat] {
        func measure(_ index: Int, _ height: CGFloat?) -> CGFloat {
            subviews[index].sizeThatFits(ProposedViewSize(width: width, height: height)).height
        }
        var heights = subviews.indices.map { measure($0, nil) }
        if let height, height.isFinite {
            var spare = height - heights.reduce(0, +)
            if spare < -0.01 {
                // Too tall. The children that step down all do so together,
                // so rows of one group keep looking alike.
                for index in subviews.indices where subviews[index][Yielding.self] == .first {
                    let least = min(measure(index, 0), heights[index])
                    spare += heights[index] - least
                    heights[index] = least
                }
            }
            if spare < -0.01 {
                // Still too tall. The children that squeeze share what is missing.
                let give = subviews.indices.map {
                    subviews[$0][Yielding.self] == .last ? max(heights[$0] - measure($0, 0), 0) : 0
                }
                let total = give.reduce(0, +)
                if total > 0.01 {
                    let part = min(-spare / total, 1)
                    for index in subviews.indices where give[index] > 0 {
                        heights[index] -= give[index] * part
                        spare += give[index] * part
                    }
                }
            }
            var room = subviews.map { $0[StretchLimit.self] }
            var rounds = 0
            while spare > 0.01, rounds < 2 * subviews.count + 2 {
                rounds += 1
                let open = room.indices.filter { room[$0] > 0.01 }
                guard let least = open.map({ room[$0] }).min() else { break }
                let share = min(spare / CGFloat(open.count), least)
                for index in open {
                    // A child may take less than it is offered; it then gets no more.
                    let taken = min(max(measure(index, heights[index] + share) - heights[index], 0), share)
                    room[index] = taken < share - 0.01 ? 0 : room[index] - share
                    heights[index] += taken
                    spare -= taken
                }
            }
        }
        // Children meet on whole pixels, so no hairline shows between two
        // backgrounds. The last one ends where the stack does: a stack that
        // came out a fraction taller than its room would make the page scroll.
        let total = heights.reduce(0, +)
        var edge: CGFloat = 0, sum: CGFloat = 0
        return heights.indices.map { index in
            sum += heights[index]
            let next = index == heights.count - 1 ? total : min((sum / pixel).rounded() * pixel, total)
            defer { edge = next }
            return max(next - edge, 0)
        }
    }
}

/// Where a row sits in its group, for a group that is drawn row by row.
enum GroupPosition {
    case only, first, middle, last

    var roundsTop: Bool { self == .only || self == .first }
    var roundsBottom: Bool { self == .only || self == .last }
}

/// One row of a group that looks like a system inset-grouped list, for a page
/// that lays its rows out itself.
struct GroupedRowStyle: ButtonStyle {
    var position: GroupPosition
    /// Where the separator above the row starts.
    var separatorInset: CGFloat

    func makeBody(configuration: Configuration) -> some View {
        let top: CGFloat = position.roundsTop ? 10 : 0, bottom: CGFloat = position.roundsBottom ? 10 : 0
        configuration.label
            .background(configuration.isPressed ? Color(uiColor: .systemGray4) : JerreaderTheme.groupedRow,
                        in: UnevenRoundedRectangle(topLeadingRadius: top, bottomLeadingRadius: bottom,
                                                   bottomTrailingRadius: bottom, topTrailingRadius: top,
                                                   style: .continuous))
            .overlay(alignment: .top) {
                if !position.roundsTop { Divider().padding(.leading, separatorInset) }
            }
    }
}

extension JerreaderTheme {
    /// The row colour of a grouped list, for groups drawn by hand to match one.
    static var groupedRow: Color { Color(uiColor: .secondarySystemGroupedBackground) }
}
