import SwiftData
import SwiftUI

// The library (书架) screen's 2.0 building blocks, laid out to
// docs/DESIGN_2.0.md §7. LibraryView owns all state and every action; these
// views only draw and report taps back through closures.

// MARK: - Shared rules

/// Number and text rules shared by the hero, the grid, the statistics and the
/// book editor, so they always agree.
enum LibraryShelfFormat {
    static func clampedProgress(_ progress: Double) -> Double {
        guard progress.isFinite else { return 0 }
        return min(max(progress, 0), 1)
    }

    static func percent(_ progress: Double) -> Int {
        Int((clampedProgress(progress) * 100).rounded())
    }

    /// "37%".
    static func progressText(_ progress: Double) -> String {
        "\(percent(progress))%"
    }

    static func isStarted(_ progress: Double) -> Bool {
        progress.isFinite && progress > 0
    }

    /// Finished once the rounded progress reads 100%.
    static func isFinished(_ progress: Double) -> Bool {
        isStarted(progress) && percent(progress) >= 100
    }

    /// "<1 分钟", "25 分钟", "2 小时", "2小时5分".
    static func durationText(_ seconds: TimeInterval) -> String {
        let clamped = safeSeconds(seconds)
        let totalMinutes = Int(clamped / 60)
        if totalMinutes < 1 { return clamped > 0 ? "<1 分钟" : "0 分钟" }
        if totalMinutes < 60 { return "\(totalMinutes) 分钟" }
        let hours = totalMinutes / 60
        let minutes = totalMinutes % 60
        return minutes == 0 ? "\(hours) 小时" : "\(hours)小时\(minutes)分"
    }

    /// The 累计阅读 statistic: whole minutes under an hour, then whole hours
    /// (rounded down).
    static func readingTimeStat(_ seconds: TimeInterval) -> (value: Int, unit: String) {
        let totalMinutes = Int(safeSeconds(seconds) / 60)
        if totalMinutes < 60 { return (totalMinutes, "分钟") }
        return (totalMinutes / 60, "小时")
    }

    private static func safeSeconds(_ seconds: TimeInterval) -> TimeInterval {
        guard seconds.isFinite else { return 0 }
        return min(max(seconds, 0), 1_000_000_000)
    }
}

// MARK: - Date line

/// "11月19日 星期三", on the Gregorian calendar in the device's time zone.
enum LibraryShelfDate {
    private static let weekdays = ["星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"]

    static func string(from date: Date) -> String {
        let calendar = Calendar(identifier: .gregorian)
        let parts = calendar.dateComponents([.month, .day, .weekday], from: date)
        let weekday = ((parts.weekday ?? 1) - 1 + 7) % 7
        return "\(parts.month ?? 1)月\(parts.day ?? 1)日 \(weekdays[weekday])"
    }
}

/// The subtitle line under the 书架 large title: today's date, refreshed every
/// minute so it rolls over at midnight.
struct LibraryShelfDateLine: View {
    var body: some View {
        TimelineView(.everyMinute) { context in
            Text(LibraryShelfDate.string(from: context.date))
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - Zoom transition

/// Names the cover a reader zooms out of. A book can be on screen twice (the
/// hero and the grid), so the place is part of the id.
struct LibraryCoverTransitionID: Hashable, Sendable {
    enum Place: Hashable, Sendable {
        case continueReading
        case grid
    }

    let bookID: UUID
    let place: Place
}

private extension View {
    /// Makes a cover the source of the reader's zoom transition, keeping its
    /// rounded corners while it zooms.
    @MainActor
    func libraryCoverTransitionSource(
        _ id: LibraryCoverTransitionID,
        in namespace: Namespace.ID
    ) -> some View {
        matchedTransitionSource(id: id, in: namespace) { source in
            source.clipShape(
                RoundedRectangle(cornerRadius: JerreaderRadius.cover, style: .continuous)
            )
        }
    }
}

// MARK: - First-appearance reveal

/// The shelf's staggered rise-in. It runs only while `isEnabled` (the shelf's
/// first second on screen), so grid cells created later by scrolling, tab
/// switches and a closed reader simply show.
private struct LibraryShelfRevealModifier: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var hasAppeared = false

    let order: Int
    let isEnabled: Bool

    func body(content: Content) -> some View {
        let isShown = hasAppeared || !isEnabled
        content
            .opacity(isShown ? 1 : 0)
            .offset(y: isShown || reduceMotion ? 0 : 12)
            .scaleEffect(isShown || reduceMotion ? 1 : 0.98)
            .onAppear {
                guard isEnabled, !hasAppeared else { return }
                // Reduce Motion: a plain 0.18 s cross-fade, no rise.
                let animation = reduceMotion
                    ? JerreaderMotion.reduced
                    : JerreaderMotion.reveal.delay(JerreaderMotion.revealDelay(order))
                withAnimation(animation) {
                    hasAppeared = true
                }
            }
    }
}

extension View {
    @MainActor
    func libraryShelfReveal(order: Int, isEnabled: Bool) -> some View {
        modifier(LibraryShelfRevealModifier(order: order, isEnabled: isEnabled))
    }
}

// MARK: - Continue reading

/// 继续阅读: the most recently opened book on the ink hero gradient. The whole
/// card is one button.
struct LibraryContinueReadingCard: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    let book: BookRecord
    let transitionID: LibraryCoverTransitionID
    let transitionNamespace: Namespace.ID
    let onOpen: () -> Void

    private var isLargeType: Bool { dynamicTypeSize.isAccessibilitySize }

    var body: some View {
        // At accessibility text sizes the cover moves above the text, so the
        // title keeps the card's full width.
        let layout = isLargeType
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: JerreaderSpacing.md))
            : AnyLayout(HStackLayout(alignment: .top, spacing: JerreaderSpacing.md))

        Button(action: onOpen) {
            layout {
                cover
                details
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
            // Lets the text column stretch to the row's height, so the
            // progress row sits on the card's bottom edge.
            .fixedSize(horizontal: false, vertical: true)
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background {
                JerreaderHeroBackground(cornerRadius: JerreaderRadius.hero)
            }
            .contentShape(RoundedRectangle(cornerRadius: JerreaderRadius.hero, style: .continuous))
        }
        .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.98))
        .accessibilityLabel("继续阅读，\(book.title)，作者 \(book.author)")
        .accessibilityValue(accessibilityValue)
        .accessibilityHint("轻点继续阅读")
    }

    private var cover: some View {
        LibraryBookCoverView(book: book)
            .frame(width: 84, height: 120)
            .jerreaderBookCover(cornerRadius: JerreaderRadius.cover)
            .libraryCoverTransitionSource(transitionID, in: transitionNamespace)
    }

    private var details: some View {
        VStack(alignment: .leading, spacing: 0) {
            JerreaderEyebrow(text: "继续阅读", tint: Color.white.opacity(0.72))

            Text(book.title)
                .font(.title2.weight(.bold))
                .foregroundStyle(Color.white)
                .lineLimit(isLargeType ? 4 : 2)
                .multilineTextAlignment(.leading)
                .padding(.top, 6)

            if !book.author.isEmpty {
                Text(book.author)
                    .font(.subheadline)
                    .foregroundStyle(Color.white.opacity(0.75))
                    .lineLimit(isLargeType ? 2 : 1)
                    .padding(.top, JerreaderSpacing.xxs)
            }

            Spacer(minLength: JerreaderSpacing.sm)

            JerreaderProgressBar(
                progress: book.lastReadProgress,
                tint: Color.white,
                track: Color.white.opacity(0.25)
            )

            HStack(alignment: .center, spacing: JerreaderSpacing.xs) {
                progressCaption
                Spacer(minLength: JerreaderSpacing.xs)
                continueCapsule
            }
            .padding(.top, 10)

            if let chapterEstimate {
                Text(chapterEstimate)
                    .font(.footnote)
                    .foregroundStyle(Color.white.opacity(0.75))
                    .lineLimit(1)
                    .padding(.top, 6)
                    .accessibilityIdentifier("shelf-chapter-time-estimate")
            }
        }
    }

    /// 本章还剩约 N 分钟 (2.1.0 §9), from the chapter end the reader last saw
    /// for this book and the pace on its record; nothing for a PDF or before
    /// the pace is known.
    private var chapterEstimate: String? {
        guard book.format != .pdf,
              let chapterEnd = UserDefaults.standard.object(
                  forKey: ChapterTimeEstimatePolicy.chapterEndKey(bookID: book.id)
              ) as? Double
        else { return nil }
        return ChapterTimeEstimatePolicy.text(
            readingSeconds: book.totalReadingSeconds,
            progression: book.lastReadProgress,
            chapterEnd: chapterEnd
        )
    }

    private var progressCaption: some View {
        HStack(spacing: 6) {
            Text(LibraryShelfFormat.progressText(book.lastReadProgress))
                .monospacedDigit()
            if let lastOpenedAt = book.lastOpenedAt {
                Text("·")
                JerreaderRelativeTimeText(date: lastOpenedAt)
            }
        }
        .font(.footnote)
        .foregroundStyle(Color.white.opacity(0.75))
        .lineLimit(1)
    }

    private var continueCapsule: some View {
        Text("继续")
            .font(.subheadline.weight(.semibold))
            // accentDeep is the light-mode accent in both appearances, so the
            // label stays readable on the white capsule in dark mode too.
            .foregroundStyle(JerreaderTheme.accentDeep)
            .padding(.horizontal, JerreaderSpacing.md)
            .frame(minHeight: 34)
            .background(Color.white, in: Capsule())
            .fixedSize()
    }

    private var accessibilityValue: String {
        var parts = ["已读 \(LibraryShelfFormat.progressText(book.lastReadProgress))"]
        if let lastOpenedAt = book.lastOpenedAt {
            parts.append(JerreaderRelativeTime.string(from: lastOpenedAt))
        }
        if let chapterEstimate {
            parts.append(chapterEstimate)
        }
        return parts.joined(separator: "，")
    }
}

// MARK: - Reading statistics

/// The three numbers on the 阅读统计 card, from this device's books only.
struct LibraryReadingStatsSummary {
    let totalSeconds: TimeInterval
    let startedCount: Int
    let averagePercent: Int

    init(books: [BookRecord]) {
        totalSeconds = books.reduce(0) { $0 + max($1.totalReadingSeconds, 0) }
        let started = books.filter { $0.lastReadProgress > 0 }
        startedCount = started.count
        if started.isEmpty {
            averagePercent = 0
        } else {
            let average = started.reduce(0) { $0 + $1.lastReadProgress } / Double(started.count)
            averagePercent = LibraryShelfFormat.percent(average)
        }
    }
}

/// 阅读统计: a paper card with three statistics in gold, accent and jade.
/// During the shelf's first appearance the numbers roll up from zero.
struct LibraryReadingStatsCard: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var hasRolledIn = false

    let summary: LibraryReadingStatsSummary
    let rollsIn: Bool

    init(summary: LibraryReadingStatsSummary, rollsIn: Bool) {
        self.summary = summary
        self.rollsIn = rollsIn
    }

    private var showsValues: Bool { hasRolledIn || !rollsIn || reduceMotion }

    var body: some View {
        let readingTime = LibraryShelfFormat.readingTimeStat(summary.totalSeconds)
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: JerreaderSpacing.md))
            : AnyLayout(HStackLayout(alignment: .top, spacing: JerreaderSpacing.sm))

        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline, spacing: JerreaderSpacing.xs) {
                Text("阅读统计")
                    .font(.headline)
                Spacer(minLength: JerreaderSpacing.xs)
                Text(summary.startedCount == 0 ? "从一本喜欢的书开始" : "只记录本机阅读进度")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            layout {
                stat(
                    value: readingTime.value,
                    unit: readingTime.unit,
                    label: "累计阅读",
                    systemImage: "clock",
                    tint: JerreaderTheme.gold
                )
                stat(
                    value: summary.startedCount,
                    unit: "本",
                    label: "已开始",
                    systemImage: "books.vertical",
                    tint: JerreaderTheme.accent
                )
                stat(
                    value: summary.averagePercent,
                    unit: "%",
                    label: "平均进度",
                    systemImage: "chart.line.uptrend.xyaxis",
                    tint: JerreaderTheme.jade
                )
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jerreaderPaperCard(padding: JerreaderSpacing.md, radius: JerreaderRadius.card)
        .accessibilityElement(children: .combine)
        .task {
            guard rollsIn, !hasRolledIn, !reduceMotion else { return }
            // Starts just after the card's own reveal begins; the stat view
            // rolls each number with its numeric-text transition.
            try? await Task.sleep(for: .milliseconds(200))
            hasRolledIn = true
        }
    }

    private func stat(
        value: Int,
        unit: String,
        label: String,
        systemImage: String,
        tint: Color
    ) -> some View {
        JerreaderStatValue(
            value: showsValues ? value : 0,
            unit: unit,
            label: label,
            systemImage: systemImage,
            tint: tint,
            size: 28
        )
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Section header

/// 所有书籍 with its count, and an accessory (the sort menu) on the right.
struct LibraryShelfSectionHeader<Accessory: View>: View {
    let title: String
    let detail: String
    let accessory: Accessory

    init(title: String, detail: String, @ViewBuilder accessory: () -> Accessory) {
        self.title = title
        self.detail = detail
        self.accessory = accessory()
    }

    var body: some View {
        HStack(alignment: .center, spacing: JerreaderSpacing.xs) {
            HStack(alignment: .firstTextBaseline, spacing: JerreaderSpacing.xs) {
                Text(title)
                    .font(.title3.weight(.bold))
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)

            Spacer(minLength: JerreaderSpacing.xs)

            accessory
        }
    }
}

// MARK: - Folder chips

/// The folder filter: 全部书籍 plus one chip per folder, scrolling sideways
/// edge to edge. The selected chip's fill slides to the new chip.
struct LibraryFolderChips: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var selectionNamespace

    let categories: [String]
    let books: [BookRecord]
    @Binding var selectedCategory: String?

    init(categories: [String], books: [BookRecord], selectedCategory: Binding<String?>) {
        self.categories = categories
        self.books = books
        _selectedCategory = selectedCategory
    }

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: JerreaderSpacing.xs) {
                chip(
                    title: "全部书籍",
                    count: books.count,
                    systemImage: "square.grid.2x2.fill",
                    isSelected: selectedCategory == nil
                ) {
                    selectedCategory = nil
                }

                ForEach(categories, id: \.self) { category in
                    chip(
                        title: category,
                        count: books.filter { $0.category == category }.count,
                        systemImage: "folder.fill",
                        isSelected: selectedCategory == category
                    ) {
                        selectedCategory = category
                    }
                }
            }
            .padding(.horizontal, JerreaderTheme.pagePadding)
            .padding(.vertical, 2)
        }
        .scrollIndicators(.hidden)
        // Bleeds to the screen edges; the row's own padding keeps the first
        // chip on the page margin.
        .padding(.horizontal, -JerreaderTheme.pagePadding)
        .sensoryFeedback(.selection, trigger: selectedCategory)
    }

    private func chip(
        title: String,
        count: Int,
        systemImage: String,
        isSelected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button {
            withAnimation(JerreaderMotion.choose(JerreaderMotion.selection, reduceMotion: reduceMotion)) {
                action()
            }
        } label: {
            HStack(spacing: 6) {
                JerreaderSymbol(name: systemImage, size: 14)
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                Text("\(count)")
                    .font(.caption.weight(.semibold))
                    .monospacedDigit()
                    .opacity(0.72)
            }
            .foregroundStyle(isSelected ? JerreaderTheme.onPrimaryAction : Color.primary)
            .padding(.horizontal, 14)
            .frame(minHeight: 36)
            .background {
                if isSelected {
                    if reduceMotion {
                        Capsule().fill(JerreaderTheme.primaryAction)
                    } else {
                        Capsule()
                            .fill(JerreaderTheme.primaryAction)
                            .matchedGeometryEffect(id: "selected-folder", in: selectionNamespace)
                    }
                } else {
                    Capsule()
                        .fill(JerreaderTheme.paper)
                        .overlay {
                            Capsule().strokeBorder(JerreaderTheme.line, lineWidth: 0.75)
                        }
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.96))
        .accessibilityLabel("\(title)，\(count) 本")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

// MARK: - Book tile

/// One book on the shelf: cover, title, author and format, with the 更多操作
/// menu beside the title and the same actions on long press.
struct LibraryBookTile: View {
    enum Style {
        /// Cover above the text, for the three-column grid.
        case grid
        /// Cover beside the text, for accessibility text sizes.
        case row
    }

    let book: BookRecord
    let style: Style
    let transitionNamespace: Namespace.ID
    let onOpen: () -> Void
    let onManage: () -> Void
    let onDelete: () -> Void

    var body: some View {
        Group {
            switch style {
            case .grid:
                gridBody
            case .row:
                rowBody
            }
        }
        .contextMenu { menuItems }
        // Flexible grid cells do not constrain a cover image's ideal size on
        // their own; binding the tile to its cell keeps oversized source
        // covers out of the neighbouring column.
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var gridBody: some View {
        VStack(alignment: .leading, spacing: 0) {
            coverButton

            HStack(alignment: .top, spacing: JerreaderSpacing.xxs) {
                titleText
                    .lineLimit(2, reservesSpace: true)
                    .frame(maxWidth: .infinity, alignment: .leading)

                // A 44 pt target that only takes 16 × 20 pt of the row, so
                // the title keeps most of a narrow column.
                moreMenu
                    .padding(.horizontal, -14)
                    .padding(.vertical, -12)
            }
            .padding(.top, 10)

            authorText
                .lineLimit(1)
                .padding(.top, 2)

            formatChip
                .padding(.top, 6)
        }
    }

    private var rowBody: some View {
        HStack(alignment: .top, spacing: 14) {
            coverButton
                .frame(width: 72)

            VStack(alignment: .leading, spacing: JerreaderSpacing.xxs) {
                titleText
                    .lineLimit(3)
                authorText
                    .lineLimit(2)
                formatChip
                    .padding(.top, 2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            moreMenu
        }
    }

    private var coverButton: some View {
        Button(action: onOpen) {
            cover
        }
        .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.97))
        .accessibilityLabel("\(book.title)，作者 \(book.author)")
        .accessibilityValue(progressAccessibilityValue)
        .accessibilityHint("轻点打开阅读器")
    }

    private var cover: some View {
        Color.clear
            .aspectRatio(2.0 / 3.0, contentMode: .fit)
            .frame(maxWidth: .infinity)
            .overlay {
                LibraryBookCoverView(book: book)
            }
            .overlay(alignment: .bottom) {
                if LibraryShelfFormat.isStarted(book.lastReadProgress) {
                    LibraryCoverProgressEdge(progress: book.lastReadProgress)
                }
            }
            .jerreaderBookCover(cornerRadius: JerreaderRadius.cover)
            .overlay(alignment: .topTrailing) {
                if LibraryShelfFormat.isFinished(book.lastReadProgress) {
                    LibraryFinishedBadge()
                        .padding(6)
                }
            }
            .contentShape(RoundedRectangle(cornerRadius: JerreaderRadius.cover, style: .continuous))
            .libraryCoverTransitionSource(
                LibraryCoverTransitionID(bookID: book.id, place: .grid),
                in: transitionNamespace
            )
    }

    private var titleText: some View {
        Text(book.title)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.primary)
            .multilineTextAlignment(.leading)
    }

    private var authorText: some View {
        Text(book.author)
            .font(.footnote)
            .foregroundStyle(.secondary)
    }

    private var formatChip: some View {
        Text(book.format.displayName)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(JerreaderTheme.accent)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(JerreaderTheme.accentFill, in: Capsule())
    }

    private var moreMenu: some View {
        Menu {
            menuItems
        } label: {
            JerreaderSymbol(name: "ellipsis", size: 16)
                .foregroundStyle(.secondary)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .accessibilityLabel("\(book.title)的更多操作")
    }

    @ViewBuilder
    private var menuItems: some View {
        Button(action: onOpen) {
            Label { Text("开始阅读") } icon: { Image(uiImage: JerreaderIconImage.image("BOOKS")) }
        }

        Button(action: onManage) {
            Label { Text("编辑书籍信息") } icon: { Image(uiImage: JerreaderIconImage.image("NOTE")) }
        }

        Button(role: .destructive, action: onDelete) {
            Label { Text("删除") } icon: { Image(uiImage: JerreaderIconImage.image("TRASH")) }
        }
    }

    private var progressAccessibilityValue: String {
        let progress = book.lastReadProgress
        if LibraryShelfFormat.isFinished(progress) { return "已读完" }
        if LibraryShelfFormat.isStarted(progress) {
            return "已读 \(LibraryShelfFormat.progressText(progress))"
        }
        return ""
    }
}

/// The 3 pt reading-progress line along a grid cover's bottom edge.
private struct LibraryCoverProgressEdge: View {
    let progress: Double

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Rectangle()
                    .fill(Color.black.opacity(0.32))
                Rectangle()
                    .fill(JerreaderTheme.accent)
                    .frame(width: proxy.size.width * LibraryShelfFormat.clampedProgress(progress))
            }
        }
        .frame(height: 3)
        .accessibilityHidden(true)
    }
}

/// The jade check on a finished book's cover.
private struct LibraryFinishedBadge: View {
    var body: some View {
        JerreaderSymbol(name: "checkmark", size: 11)
            .foregroundStyle(JerreaderTheme.onPrimaryAction)
            .frame(width: 22, height: 22)
            .background(JerreaderTheme.jade, in: Circle())
            .overlay {
                Circle().strokeBorder(Color.white.opacity(0.9), lineWidth: 1.5)
            }
            .shadow(color: Color.black.opacity(0.2), radius: 2, y: 1)
            .accessibilityHidden(true)
    }
}

// MARK: - No match

/// Shown in place of the grid when the search or folder matches no book.
struct LibraryNoMatchView: View {
    var body: some View {
        VStack(spacing: JerreaderSpacing.sm) {
            JerreaderIconTile(systemImage: "magnifyingglass", tint: JerreaderTheme.accent, size: 52)

            VStack(spacing: JerreaderSpacing.xxs) {
                Text("没有匹配的书籍")
                    .font(.headline)
                Text("请清除搜索文字或切换文件夹。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, JerreaderSpacing.xxl)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Empty shelf

/// The empty shelf: an icon block, a title, import and restore. It sits a
/// third of the way down the screen, and scrolls instead when large text
/// makes it taller than the screen.
struct LibraryEmptyShelfView: View {
    let isImporting: Bool
    let revealsOnAppear: Bool
    let onImport: () -> Void
    let onRestore: () -> Void

    var body: some View {
        ViewThatFits(in: .vertical) {
            VStack(alignment: .leading, spacing: 0) {
                LibraryShelfDateLine()
                Spacer(minLength: JerreaderSpacing.xl)
                content
                Spacer(minLength: JerreaderSpacing.xl)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .padding(.horizontal, JerreaderTheme.pagePadding)
            .padding(.top, JerreaderSpacing.xxs)
            .padding(.bottom, JerreaderSpacing.xl)

            ScrollView {
                VStack(alignment: .leading, spacing: JerreaderSpacing.xl) {
                    LibraryShelfDateLine()
                    content
                }
                .padding(.horizontal, JerreaderTheme.pagePadding)
                .padding(.top, JerreaderSpacing.xxs)
                .padding(.bottom, JerreaderSpacing.xxl)
            }
        }
    }

    private var content: some View {
        VStack(spacing: 0) {
            LibraryEmptyShelfIcon()
                .libraryShelfReveal(order: 0, isEnabled: revealsOnAppear)

            VStack(spacing: JerreaderSpacing.xs) {
                Text("把下一本书放进来")
                    .font(.title2.weight(.bold))
                Text("导入 EPUB、PDF、DOCX 或 TXT。书籍只保存在这台设备上。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .multilineTextAlignment(.center)
            .padding(.top, JerreaderSpacing.lg)
            .libraryShelfReveal(order: 1, isEnabled: revealsOnAppear)

            VStack(spacing: JerreaderSpacing.sm) {
                Button(action: onImport) {
                    JerreaderLabel(title: "导入书籍", icon: "square.and.arrow.down")
                }
                .buttonStyle(JerreaderPrimaryButtonStyle())
                .disabled(isImporting)

                // An empty shelf is what a reinstall looks like, so the restore
                // path belongs here rather than only three taps deep in settings.
                Button(action: onRestore) {
                    JerreaderLabel(title: "从备份恢复", icon: "RESTORE", size: 16)
                }
                .buttonStyle(JerreaderTintedButtonStyle(fullWidth: true))
                .disabled(isImporting)
                .accessibilityIdentifier("library-restore-backup")
            }
            .padding(.top, 28)
            .libraryShelfReveal(order: 2, isEnabled: revealsOnAppear)

            VStack(spacing: JerreaderSpacing.sm) {
                Text("装过 Jerreader 的话，选择以前的备份文件夹即可恢复书架、进度和备份计划。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                JerreaderLabel(title: "本机保存，不会上传", icon: "lock.fill", size: 13)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .padding(.top, JerreaderSpacing.md)
            .libraryShelfReveal(order: 3, isEnabled: revealsOnAppear)
        }
        .frame(maxWidth: 360)
        .frame(maxWidth: .infinity)
    }
}

/// The 72 pt accent-gradient block with the bookshelf symbol.
private struct LibraryEmptyShelfIcon: View {
    private let size: CGFloat = 72

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)

        JerreaderSymbol(name: "books.vertical.fill", size: 34)
            .foregroundStyle(Color.white)
            .frame(width: size, height: size)
            .background {
                shape
                    .fill(
                        LinearGradient(
                            colors: [JerreaderTheme.accent, JerreaderTheme.accentDeep],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .overlay {
                        // accent and accentDeep match in light mode; the gloss
                        // keeps the block a gradient there too.
                        shape.fill(
                            LinearGradient(
                                colors: [Color.white.opacity(0.22), Color.white.opacity(0)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                    }
            }
            .jerreaderElevation(.raised, cornerRadius: size * 0.28, tint: JerreaderTheme.accent)
            .accessibilityHidden(true)
    }
}
