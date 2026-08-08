import SwiftUI
import UIKit

enum JerreaderThemeColorChoice: String, CaseIterable, Identifiable, Sendable {
    case ocean
    case forest
    case wisteria
    case amber
    case berry

    var id: Self { self }

    var title: String {
        switch self {
        case .ocean: return "海蓝"
        case .forest: return "森林"
        case .wisteria: return "紫藤"
        case .amber: return "琥珀"
        case .berry: return "莓红"
        }
    }

    var detail: String {
        switch self {
        case .ocean: return "沉静的蓝灰色系"
        case .forest: return "柔和的青绿色系"
        case .wisteria: return "克制的紫罗兰色系"
        case .amber: return "温暖的黄金与棕色系"
        case .berry: return "清晰的玫红色系"
        }
    }
}

enum JerreaderThemePreferences {
    static let storageKey = "appearance.theme-color"

    static func current(defaults: UserDefaults = .standard) -> JerreaderThemeColorChoice {
        JerreaderThemeColorChoice(
            rawValue: defaults.string(forKey: storageKey) ?? ""
        ) ?? .ocean
    }
}

enum JerreaderTheme {
    private struct RGB {
        let red: CGFloat
        let green: CGFloat
        let blue: CGFloat

        func color(alpha: CGFloat = 1) -> UIColor {
            UIColor(red: red, green: green, blue: blue, alpha: alpha)
        }
    }

    private static func accentRGB(
        for choice: JerreaderThemeColorChoice,
        dark: Bool
    ) -> RGB {
        switch (choice, dark) {
        case (.ocean, false): return RGB(red: 0.18, green: 0.41, blue: 0.60)
        case (.ocean, true): return RGB(red: 0.46, green: 0.72, blue: 0.88)
        case (.forest, false): return RGB(red: 0.16, green: 0.43, blue: 0.34)
        case (.forest, true): return RGB(red: 0.42, green: 0.76, blue: 0.62)
        case (.wisteria, false): return RGB(red: 0.39, green: 0.31, blue: 0.61)
        case (.wisteria, true): return RGB(red: 0.68, green: 0.61, blue: 0.91)
        case (.amber, false): return RGB(red: 0.55, green: 0.35, blue: 0.10)
        case (.amber, true): return RGB(red: 0.91, green: 0.68, blue: 0.34)
        case (.berry, false): return RGB(red: 0.58, green: 0.22, blue: 0.38)
        case (.berry, true): return RGB(red: 0.90, green: 0.52, blue: 0.68)
        }
    }

    private static func color(
        choice: JerreaderThemeColorChoice,
        light: @escaping (RGB) -> UIColor,
        dark: @escaping (RGB) -> UIColor
    ) -> Color {
        Color(uiColor: UIColor { traits in
            let isDark = traits.userInterfaceStyle == .dark
            let rgb = accentRGB(for: choice, dark: isDark)
            return isDark ? dark(rgb) : light(rgb)
        })
    }

    static var accent: Color { accent(for: JerreaderThemePreferences.current()) }
    static func accent(for choice: JerreaderThemeColorChoice) -> Color {
        color(choice: choice, light: { $0.color() }, dark: { $0.color() })
    }

    static var accentFill: Color { accentFill(for: JerreaderThemePreferences.current()) }
    static func accentFill(for choice: JerreaderThemeColorChoice) -> Color {
        color(
            choice: choice,
            light: { $0.color(alpha: 0.12) },
            dark: { $0.color(alpha: 0.22) }
        )
    }

    static var canvas: Color { canvas(for: JerreaderThemePreferences.current()) }
    static func canvas(for choice: JerreaderThemeColorChoice) -> Color {
        color(
            choice: choice,
            light: { _ in
                UIColor(red: 0.941, green: 0.961, blue: 0.975, alpha: 1)
            },
            dark: { _ in
                UIColor(red: 0.040, green: 0.065, blue: 0.090, alpha: 1)
            }
        )
    }

    static var paper: Color { surface(level: 0) }
    static var raisedPaper: Color { surface(level: 1) }
    static var mutedSurface: Color { surface(level: -1) }

    private static func surface(level: Int) -> Color {
        let choice = JerreaderThemePreferences.current()
        return color(
            choice: choice,
            light: { _ in
                switch level {
                case let value where value > 0:
                    return UIColor(red: 0.985, green: 0.993, blue: 0.998, alpha: 1)
                case let value where value < 0:
                    return UIColor(red: 0.885, green: 0.925, blue: 0.953, alpha: 1)
                default:
                    return UIColor(red: 0.965, green: 0.978, blue: 0.989, alpha: 1)
                }
            },
            dark: { _ in
                switch level {
                case let value where value > 0:
                    return UIColor(red: 0.095, green: 0.145, blue: 0.185, alpha: 1)
                case let value where value < 0:
                    return UIColor(red: 0.028, green: 0.055, blue: 0.080, alpha: 1)
                default:
                    return UIColor(red: 0.060, green: 0.100, blue: 0.135, alpha: 1)
                }
            }
        )
    }

    static var line: Color {
        color(
            choice: JerreaderThemePreferences.current(),
            light: { accent in accent.color(alpha: 0.18) },
            dark: { accent in accent.color(alpha: 0.28) }
        )
    }

    static var primaryAction: Color { accent }
    static var onPrimaryAction: Color {
        Color(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.025, green: 0.04, blue: 0.05, alpha: 1)
                : .white
        })
    }

    static let shadow = Color.black.opacity(0.07)
    static let deepShadow = Color.black.opacity(0.10)
    static let pagePadding: CGFloat = 20
    static let cardRadius: CGFloat = 16
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
enum JerreaderRelativeTime {
    static func string(from date: Date, now: Date = Date()) -> String {
        let elapsed = max(now.timeIntervalSince(date), 0)
        if elapsed < 60 {
            return "刚刚"
        }
        if elapsed < 3_600 {
            return "\(max(Int(elapsed / 60), 1)) 分钟前"
        }
        if elapsed < 86_400 {
            return "\(max(Int(elapsed / 3_600), 1)) 小时前"
        }
        return "\(max(Int(elapsed / 86_400), 1)) 天前"
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
