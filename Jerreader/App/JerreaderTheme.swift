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

        init(red: CGFloat, green: CGFloat, blue: CGFloat) {
            self.red = red
            self.green = green
            self.blue = blue
        }

        /// `0xRRGGBB`, as written in docs/DESIGN_2.0.md.
        init(hex: UInt32) {
            red = CGFloat((hex >> 16) & 0xFF) / 255
            green = CGFloat((hex >> 8) & 0xFF) / 255
            blue = CGFloat(hex & 0xFF) / 255
        }

        func color(alpha: CGFloat = 1) -> UIColor {
            UIColor(red: red, green: green, blue: blue, alpha: alpha)
        }
    }

    private static func accentRGB(
        for choice: JerreaderThemeColorChoice,
        dark: Bool
    ) -> RGB {
        switch (choice, dark) {
        case (.ocean, false): return RGB(hex: 0x2C5D8F)
        case (.ocean, true): return RGB(hex: 0x8DBCE8)
        case (.forest, false): return RGB(hex: 0x2E6B53)
        case (.forest, true): return RGB(hex: 0x7FC8A6)
        case (.wisteria, false): return RGB(hex: 0x5B4C9C)
        case (.wisteria, true): return RGB(hex: 0xB7A9F0)
        case (.amber, false): return RGB(hex: 0x94601C)
        case (.amber, true): return RGB(hex: 0xEBB56A)
        case (.berry, false): return RGB(hex: 0x9A3A62)
        case (.berry, true): return RGB(hex: 0xEE91B6)
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

    /// The light-mode accent in both appearances: the deep end of a hero
    /// card's gradient, which stays dark enough for white text.
    static var accentDeep: Color { accentDeep(for: JerreaderThemePreferences.current()) }
    static func accentDeep(for choice: JerreaderThemeColorChoice) -> Color {
        Color(uiColor: accentRGB(for: choice, dark: false).color())
    }

    static var canvas: Color { canvas(for: JerreaderThemePreferences.current()) }
    static func canvas(for choice: JerreaderThemeColorChoice) -> Color {
        color(
            choice: choice,
            light: { _ in RGB(hex: 0xF4F1EA).color() },
            dark: { _ in RGB(hex: 0x101215).color() }
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
                    return RGB(hex: 0xFFFFFF).color()
                case let value where value < 0:
                    return RGB(hex: 0xEBE6DB).color()
                default:
                    return RGB(hex: 0xFFFDF8).color()
                }
            },
            dark: { _ in
                switch level {
                case let value where value > 0:
                    return RGB(hex: 0x20242A).color()
                case let value where value < 0:
                    return RGB(hex: 0x0B0D10).color()
                default:
                    return RGB(hex: 0x181B20).color()
                }
            }
        )
    }

    static var line: Color {
        color(
            choice: JerreaderThemePreferences.current(),
            light: { _ in RGB(hex: 0xE3DCCD).color() },
            dark: { _ in RGB(hex: 0x2B3038).color() }
        )
    }

    static var primaryAction: Color { accent }
    static var onPrimaryAction: Color {
        Color(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? RGB(hex: 0x0B0F14).color()
                : .white
        })
    }

    static let shadow = Color.black.opacity(0.07)
    static let deepShadow = Color.black.opacity(0.10)
    static let pagePadding: CGFloat = 20
    static let cardRadius: CGFloat = 20
}

enum JerreaderMotion {
    static let quick = Animation.easeOut(duration: 0.16)
    static let stateChange = Animation.spring(duration: 0.32, bounce: 0.12)
    static let reveal = Animation.spring(duration: 0.50, bounce: 0.14)
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

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                JerreaderTheme.canvas(for: themeColor)
                // A faint, static wash of the accent at the top left, so the
                // paper colour does not read as flat grey.
                RadialGradient(
                    colors: [
                        JerreaderTheme.accent(for: themeColor)
                            .opacity(colorScheme == .dark ? 0.10 : 0.06),
                        JerreaderTheme.accent(for: themeColor).opacity(0),
                    ],
                    center: UnitPoint(x: 0.2, y: 0),
                    startRadius: 0,
                    endRadius: max(proxy.size.width * 1.1, 1)
                )
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

private struct JerreaderPaperCardModifier: ViewModifier {
    let padding: CGFloat
    let radius: CGFloat
    let hasShadow: Bool

    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        let dark = colorScheme == .dark
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        content
            .padding(padding)
            .background(JerreaderTheme.paper, in: shape)
            .overlay {
                shape
                    .strokeBorder(JerreaderTheme.line.opacity(dark ? 1 : 0.9), lineWidth: 0.75)
                    .allowsHitTesting(false)
            }
            // e1 in light mode (e2 when raised); no shadow on dark paper.
            .shadow(
                color: dark ? .clear : JerreaderTheme.shadowTint.opacity(hasShadow ? 0.12 : 0.06),
                radius: dark ? 0 : (hasShadow ? 16 : 8),
                y: dark ? 0 : (hasShadow ? 8 : 4)
            )
    }
}

struct JerreaderPressButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var pressedScale: CGFloat = 0.97
    var pressedOpacity: Double = 0.94

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? pressedScale : 1)
            .opacity(configuration.isPressed ? pressedOpacity : 1)
            // Quick and flat going down, a little spring coming back up.
            .animation(
                reduceMotion ? nil : (configuration.isPressed ? JerreaderMotion.press : JerreaderMotion.release),
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
            .scaleEffect(isVisible || reduceMotion ? 1 : 0.98)
            .onAppear {
                guard !isVisible else { return }
                if reduceMotion {
                    isVisible = true
                } else {
                    withAnimation(
                        JerreaderMotion.reveal.delay(JerreaderMotion.revealDelay(order))
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
                JerreaderSymbol(name: systemImage)
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
        JerreaderLabel(title: title, icon: systemImage, size: 12)
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

            JerreaderSymbol(name: systemImage, size: size * 0.31)
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
            JerreaderSymbol(name: systemImage, size: 30)
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
