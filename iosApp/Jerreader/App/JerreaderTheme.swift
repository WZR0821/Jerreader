import JerreaderCore
import SwiftUI
import UIKit

enum JerreaderThemeColorChoice: String, CaseIterable, Identifiable, Sendable {
    case ocean
    case forest
    case wisteria
    case amber
    case berry

    var id: Self { self }

    /// The shared palette's counterpart. The raw values match its `id`s, so a
    /// theme saved by either app restores as the same theme in the other.
    var shared: JerreaderAccentPalette {
        JerreaderAccentPalette.companion.fromId(id: rawValue)
    }

    // The names the user reads come from the shared palette too, not from a
    // second copy here. Two copies is how one app ended up offering 海洋 while
    // the other offered 海蓝 for the same colour.
    var title: String { shared.title }

    var detail: String { shared.detail }
}

/// 白天 / 黑夜 / 跟随系统, backed by the shared `JerreaderAppearanceMode`.
enum JerreaderAppearanceModeChoice: String, CaseIterable, Identifiable, Sendable {
    case system
    case light
    case dark

    var id: Self { self }

    var shared: JerreaderAppearanceMode {
        JerreaderAppearanceMode.companion.fromId(id: rawValue)
    }

    var title: String { shared.title }

    var detail: String { shared.detail }

    var systemImage: String {
        switch self {
        case .system: "circle.lefthalf.filled"
        case .light: "sun.max"
        case .dark: "moon.stars"
        }
    }

    /// `nil` hands the decision back to the phone, which is exactly 跟随系统.
    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

enum JerreaderThemePreferences {
    static let storageKey = "appearance.theme-color"
    static let appearanceModeKey = "appearance.color-scheme"

    static func current(defaults: UserDefaults = .standard) -> JerreaderThemeColorChoice {
        JerreaderThemeColorChoice(
            rawValue: defaults.string(forKey: storageKey) ?? ""
        ) ?? .ocean
    }

    static func currentAppearanceMode(
        defaults: UserDefaults = .standard
    ) -> JerreaderAppearanceModeChoice {
        JerreaderAppearanceModeChoice(
            rawValue: defaults.string(forKey: appearanceModeKey) ?? ""
        ) ?? .system
    }
}

enum LearningModulePreferences {
    static let visibilityKey = "feature.learning-module-visible"
}

/// Applies the chosen mode to a whole view tree.
///
/// It has to be `preferredColorScheme` rather than anything the palette does
/// itself: every colour in `JerreaderTheme` is a dynamic `UIColor` that reads
/// the trait collection, and so are the system colours SwiftUI draws for lists,
/// keyboards and sheets. Overriding the scheme at the root moves all of them
/// together; tinting only our own colours would leave a light keyboard under a
/// dark page.
struct JerreaderAppearanceModifier: ViewModifier {
    @AppStorage(JerreaderThemePreferences.appearanceModeKey)
    private var appearanceRawValue = JerreaderAppearanceModeChoice.system.rawValue

    private var appearance: JerreaderAppearanceModeChoice {
        JerreaderAppearanceModeChoice(rawValue: appearanceRawValue) ?? .system
    }

    func body(content: Content) -> some View {
        content.preferredColorScheme(appearance.colorScheme)
    }
}

extension View {
    func jerreaderAppearance() -> some View {
        modifier(JerreaderAppearanceModifier())
    }
}

enum JerreaderTheme {
    /// Every surface tone comes from `core/design/JerreaderAppPalette.kt`, the
    /// same object Compose reads, so the two apps cannot drift into different
    /// shades of the same theme. From 1.3.3 the canvas and paper carry a wash
    /// of the accent rather than a fixed cool grey — the theme has to be
    /// visible on the page, not only on the buttons.
    private static func palette(
        for choice: JerreaderThemeColorChoice,
        dark: Bool
    ) -> JerreaderAppColors {
        JerreaderAppPalette.shared.colors(palette: choice.shared, dark: dark)
    }

    private static func color(
        choice: JerreaderThemeColorChoice,
        _ token: @escaping (JerreaderAppColors) -> JerreaderRgb
    ) -> Color {
        Color(uiColor: UIColor { traits in
            let isDark = traits.userInterfaceStyle == .dark
            return token(palette(for: choice, dark: isDark)).uiColor
        })
    }

    static var accent: Color { accent(for: JerreaderThemePreferences.current()) }
    static func accent(for choice: JerreaderThemeColorChoice) -> Color {
        color(choice: choice) { $0.accent }
    }

    static var accentFill: Color { accentFill(for: JerreaderThemePreferences.current()) }
    static func accentFill(for choice: JerreaderThemeColorChoice) -> Color {
        color(choice: choice) { $0.accentFill }
    }

    static var canvas: Color { canvas(for: JerreaderThemePreferences.current()) }
    static func canvas(for choice: JerreaderThemeColorChoice) -> Color {
        color(choice: choice) { $0.canvas }
    }

    static var paper: Color { color(choice: JerreaderThemePreferences.current()) { $0.paper } }
    static var raisedPaper: Color {
        color(choice: JerreaderThemePreferences.current()) { $0.raisedPaper }
    }
    static var mutedSurface: Color {
        color(choice: JerreaderThemePreferences.current()) { $0.mutedSurface }
    }

    static var line: Color {
        color(choice: JerreaderThemePreferences.current()) { $0.line }
    }

    static var secondaryText: Color {
        color(choice: JerreaderThemePreferences.current()) { $0.secondaryText }
    }

    static var primaryAction: Color { accent }
    static var onPrimaryAction: Color {
        color(choice: JerreaderThemePreferences.current()) { $0.onAccent }
    }

    static let shadow = Color.black.opacity(0.07)
    static let deepShadow = Color.black.opacity(0.10)
    static let pagePadding: CGFloat = 20
    static let cardRadius: CGFloat = 16
}

private extension JerreaderRgb {
    var uiColor: UIColor {
        UIColor(
            red: CGFloat(red),
            green: CGFloat(green),
            blue: CGFloat(blue),
            alpha: 1
        )
    }
}

enum JerreaderMotion {
    static let quick = Animation.easeOut(duration: 0.16)
    static let stateChange = Animation.spring(response: 0.34, dampingFraction: 0.88)
    static let reveal = Animation.spring(response: 0.46, dampingFraction: 0.86)
}

/// A quiet ambient background gives the app depth without competing with
/// book covers or reader content. It is intentionally static, so the primary
/// app surfaces do not spend energy on decorative continuous animation.
struct JerreaderCanvasBackground: View {
    @AppStorage(JerreaderThemePreferences.storageKey)
    private var themeColorRawValue = JerreaderThemeColorChoice.ocean.rawValue

    private var themeColor: JerreaderThemeColorChoice {
        JerreaderThemeColorChoice(rawValue: themeColorRawValue) ?? .ocean
    }

    var body: some View {
        JerreaderTheme.canvas(for: themeColor)
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}

private struct JerreaderPaperCardModifier: ViewModifier {
    let padding: CGFloat
    let radius: CGFloat
    let hasShadow: Bool

    func body(content: Content) -> some View {
        content
            .padding(padding)
            .background(JerreaderTheme.paper, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .stroke(JerreaderTheme.line, lineWidth: 0.75)
            }
            .shadow(
                color: hasShadow ? JerreaderTheme.shadow : .clear,
                radius: hasShadow ? 10 : 0,
                y: hasShadow ? 3 : 0
            )
    }
}

struct JerreaderPressButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var pressedScale: CGFloat = 0.975
    var pressedOpacity: Double = 0.92

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? pressedScale : 1)
            .opacity(configuration.isPressed ? pressedOpacity : 1)
            .animation(
                reduceMotion ? nil : .spring(response: 0.22, dampingFraction: 0.82),
                value: configuration.isPressed
            )
    }
}

private struct JerreaderRevealModifier: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isVisible = false

    let order: Int
    let offset: CGFloat

    func body(content: Content) -> some View {
        content
            .opacity(isVisible || reduceMotion ? 1 : 0)
            .offset(y: isVisible || reduceMotion ? 0 : offset)
            .scaleEffect(isVisible || reduceMotion ? 1 : 0.985)
            .onAppear {
                guard !isVisible else { return }
                if reduceMotion {
                    isVisible = true
                } else {
                    withAnimation(
                        JerreaderMotion.reveal.delay(min(Double(order) * 0.045, 0.32))
                    ) {
                        isVisible = true
                    }
                }
            }
    }
}

extension View {
    func jerreaderPaperCard(
        padding: CGFloat = 20,
        radius: CGFloat = JerreaderTheme.cardRadius,
        hasShadow: Bool = false
    ) -> some View {
        modifier(JerreaderPaperCardModifier(padding: padding, radius: radius, hasShadow: hasShadow))
    }

    func jerreaderReveal(order: Int = 0, offset: CGFloat = 12) -> some View {
        modifier(JerreaderRevealModifier(order: order, offset: offset))
    }
}

struct JerreaderSectionTitle: View {
    let title: String
    var detail: String?
    var systemImage: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(JerreaderTheme.accent)
                    .frame(width: 22, height: 22)
                    .alignmentGuide(.firstTextBaseline) { dimensions in
                        dimensions[VerticalAlignment.center]
                    }
            }

            Text(title)
                .font(.title3.weight(.semibold))

            Spacer(minLength: 8)

            if let detail {
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

struct JerreaderStatusPill: View {
    let title: String
    let systemImage: String
    var tint = JerreaderTheme.accent

    var body: some View {
        Label(title, systemImage: systemImage)
            .font(.caption.weight(.semibold))
            .foregroundStyle(tint)
            .padding(.horizontal, 10)
            .frame(minHeight: 30)
            .background(tint.opacity(0.10), in: Capsule())
            .overlay {
                Capsule()
                    .stroke(tint.opacity(0.14), lineWidth: 0.75)
            }
    }
}

struct JerreaderLoadingGlyph: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isPulsing = false

    var systemImage = "sparkles"
    var size: CGFloat = 54

    var body: some View {
        ZStack {
            Circle()
                .fill(JerreaderTheme.accent.opacity(0.10))
                .frame(width: size, height: size)
                .scaleEffect(isPulsing && !reduceMotion ? 1.14 : 0.94)
                .opacity(isPulsing && !reduceMotion ? 0.46 : 1)

            Circle()
                .fill(JerreaderTheme.accentFill)
                .frame(width: size * 0.76, height: size * 0.76)

            Image(systemName: systemImage)
                .font(.system(size: size * 0.31, weight: .semibold))
                .foregroundStyle(JerreaderTheme.accent)
                .rotationEffect(
                    .degrees(isPulsing && !reduceMotion ? 4 : -4)
                )
        }
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 1.05).repeatForever(autoreverses: true)) {
                isPulsing = true
            }
        }
        .onDisappear {
            isPulsing = false
        }
        .accessibilityHidden(true)
    }
}

struct JerreaderEmptyState: View {
    let title: String
    let message: String
    let systemImage: String

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: systemImage)
                .font(.system(size: 30, weight: .medium))
                .foregroundStyle(JerreaderTheme.accent)
                .frame(width: 68, height: 68)
                .background(JerreaderTheme.accentFill, in: RoundedRectangle(cornerRadius: 20, style: .continuous))

            VStack(spacing: 7) {
                Text(title)
                    .font(.title3.weight(.semibold))
                    .multilineTextAlignment(.center)

                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: 340)
        .padding(28)
        .jerreaderReveal()
        .accessibilityElement(children: .combine)
    }
}

/// A single user-facing relative-time policy for the whole app. SwiftUI's
/// `.relative` date style updates every second for recent dates, which makes
/// static library and learning metadata look like a countdown. Reader-facing
/// timestamps deliberately use only minute, hour and day buckets.
///
/// The buckets themselves live in `core/design/JerreaderCopy.kt`; this is only
/// the `Date` arithmetic Swift needs to reach them.
enum JerreaderRelativeTime {
    static func string(from date: Date, now: Date = Date()) -> String {
        JerreaderCopy.shared.relativeTime(
            elapsedSeconds: Int64(max(now.timeIntervalSince(date), 0))
        )
    }
}

struct JerreaderRelativeTimeText: View {
    let date: Date

    var body: some View {
        TimelineView(.periodic(from: .now, by: 60)) { context in
            Text(JerreaderRelativeTime.string(from: date, now: context.date))
        }
    }
}
