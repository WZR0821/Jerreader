import SwiftUI
import UIKit

// Jerreader 2.0「纸墨」design system. The values come from
// docs/DESIGN_2.0.md, and Android keeps the same ones in
// shared/src/commonMain/kotlin/com/jerreader/shared/ui/JerreaderDesignSystem.kt.

// MARK: - Colours beyond the accent

extension UIColor {
    /// `0xRRGGBB`.
    convenience init(jerreaderHex hex: UInt32, alpha: CGFloat = 1) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: alpha
        )
    }
}

extension JerreaderTheme {
    /// A colour that follows light and dark mode.
    static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            UIColor(jerreaderHex: traits.userInterfaceStyle == .dark ? dark : light)
        })
    }

    /// 朱: due reviews, reminders.
    static let vermilion = dynamic(light: 0xC8513A, dark: 0xF08A6E)
    /// 金: streaks and achievements.
    static let gold = dynamic(light: 0xB07F28, dark: 0xE5BC62)
    /// 青: mastered, done.
    static let jade = dynamic(light: 0x3C8A66, dark: 0x72CC9E)
    /// 墨: the dark end of the hero gradient.
    static let ink = dynamic(light: 0x1B2433, dark: 0x0B0E13)
    /// The tint of light-mode shadows: ink rather than pure black.
    static let shadowTint = Color(uiColor: UIColor(jerreaderHex: 0x1B2433))
}

// MARK: - Spacing, radii, elevation

enum JerreaderSpacing {
    static let xxs: CGFloat = 4
    static let xs: CGFloat = 8
    static let sm: CGFloat = 12
    static let md: CGFloat = 16
    static let lg: CGFloat = 20
    static let xl: CGFloat = 24
    static let xxl: CGFloat = 32
}

enum JerreaderRadius {
    static let cover: CGFloat = 6
    static let small: CGFloat = 10
    static let control: CGFloat = 14
    static let card: CGFloat = 20
    static let hero: CGFloat = 26
    static let sheet: CGFloat = 28
}

enum JerreaderElevation: Sendable {
    /// e1: cards.
    case card
    /// e2: hero cards and book covers.
    case raised
    /// e3: floating controls.
    case floating

    var opacity: Double {
        switch self {
        case .card: return 0.06
        case .raised: return 0.12
        case .floating: return 0.18
        }
    }

    var radius: CGFloat {
        switch self {
        case .card: return 8
        case .raised: return 16
        case .floating: return 24
        }
    }

    var y: CGFloat {
        switch self {
        case .card: return 4
        case .raised: return 8
        case .floating: return 12
        }
    }
}

/// A soft ink shadow in light mode; in dark mode no shadow but a 1pt
/// white 6% edge, which reads better on dark surfaces.
private struct JerreaderElevationModifier: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme

    let level: JerreaderElevation
    let cornerRadius: CGFloat
    let tint: Color?

    func body(content: Content) -> some View {
        let dark = colorScheme == .dark
        content
            .shadow(
                color: dark ? .clear : (tint ?? JerreaderTheme.shadowTint).opacity(level.opacity),
                radius: dark ? 0 : level.radius,
                y: dark ? 0 : level.y
            )
            .overlay {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(Color.white.opacity(dark ? 0.06 : 0), lineWidth: 1)
                    .allowsHitTesting(false)
            }
    }
}

// MARK: - Motion

extension JerreaderMotion {
    static let press = Animation.spring(duration: 0.16, bounce: 0)
    static let release = Animation.spring(duration: 0.30, bounce: 0.25)
    static let selection = Animation.snappy(duration: 0.24, extraBounce: 0.08)
    static let hero = Animation.spring(duration: 0.42, bounce: 0.16)
    /// Reader bars: frequent, so short and without bounce.
    static let chrome = Animation.easeOut(duration: 0.20)
    /// What every animation becomes with Reduce Motion on.
    static let reduced = Animation.easeInOut(duration: 0.18)

    static func choose(_ animation: Animation, reduceMotion: Bool) -> Animation {
        reduceMotion ? reduced : animation
    }

    /// The delay of the `order`th item of a staggered first appearance.
    static func revealDelay(_ order: Int) -> Double {
        min(Double(max(order, 0)) * 0.04, 0.3)
    }
}

/// A one-off bounce of an icon, for a bookmark or star that was just set.
/// The app's icons are drawn images rather than SF Symbols, so this scales
/// the view instead of using a symbol effect.
private struct JerreaderBounceModifier<Trigger: Equatable>: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let trigger: Trigger

    func body(content: Content) -> some View {
        if reduceMotion {
            content
        } else {
            content.keyframeAnimator(initialValue: 1.0, trigger: trigger) { view, scale in
                view.scaleEffect(scale)
            } keyframes: { _ in
                KeyframeTrack(\.self) {
                    CubicKeyframe(1.22, duration: 0.12)
                    CubicKeyframe(0.96, duration: 0.12)
                    SpringKeyframe(1.0, duration: 0.18, spring: .snappy)
                }
            }
        }
    }
}

extension View {
    func jerreaderElevation(
        _ level: JerreaderElevation,
        cornerRadius: CGFloat = JerreaderRadius.card,
        tint: Color? = nil
    ) -> some View {
        modifier(JerreaderElevationModifier(level: level, cornerRadius: cornerRadius, tint: tint))
    }

    func jerreaderBounce<Trigger: Equatable>(trigger: Trigger) -> some View {
        modifier(JerreaderBounceModifier(trigger: trigger))
    }

    /// Sets an accessibility identifier only when there is one, so an
    /// identifier set further in is not replaced by an empty one.
    @ViewBuilder
    func jerreaderIdentifier(_ identifier: String?) -> some View {
        if let identifier, !identifier.isEmpty {
            accessibilityIdentifier(identifier)
        } else {
            self
        }
    }
}

// MARK: - Small pieces

/// A tinted rounded square with an icon: the leading mark of a tool row.
struct JerreaderIconTile: View {
    let systemImage: String
    var tint: Color = JerreaderTheme.accent
    var size: CGFloat = 36

    var body: some View {
        JerreaderSymbol(name: systemImage, size: size * 0.5)
            .foregroundStyle(tint)
            .frame(width: size, height: size)
            .background(
                tint.opacity(0.14),
                in: RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
            )
            .accessibilityHidden(true)
    }
}

/// The small accent label above a title, such as 继续阅读.
struct JerreaderEyebrow: View {
    let text: String
    var tint: Color = JerreaderTheme.accent

    var body: some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .tracking(0.6)
            .foregroundStyle(tint)
    }
}

/// A large rounded number that rolls when it changes, with an icon and a label.
struct JerreaderStatValue: View {
    let value: Int
    var unit: String?
    let label: String
    var systemImage: String?
    var tint: Color = JerreaderTheme.accent
    /// Text colour of the number; white on a hero card.
    var foreground: Color = .primary
    var secondary: Color = .secondary
    var size: CGFloat = 22

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                if let systemImage {
                    JerreaderSymbol(name: systemImage, size: size * 0.62)
                        .foregroundStyle(tint)
                        .alignmentGuide(.firstTextBaseline) { $0[VerticalAlignment.bottom] - 2 }
                }
                Text(value, format: .number)
                    .font(.system(size: size, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(foreground)
                    .contentTransition(.numericText(value: Double(value)))
                    .animation(JerreaderMotion.stateChange, value: value)
                if let unit {
                    Text(unit)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(secondary)
                }
            }
            Text(label)
                .font(.caption)
                .foregroundStyle(secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .accessibilityElement(children: .combine)
    }
}

/// A capsule progress bar that grows from zero to its value.
struct JerreaderProgressBar: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown: Double = 0

    let progress: Double
    var tint: Color = JerreaderTheme.accent
    var track: Color?
    var height: CGFloat = 4

    private var target: Double { min(max(progress.isFinite ? progress : 0, 0), 1) }

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(track ?? tint.opacity(0.14))
                Capsule()
                    .fill(tint)
                    .frame(width: max(proxy.size.width * shown, shown > 0 ? height : 0))
            }
        }
        .frame(height: height)
        .onAppear { grow(to: target) }
        .onChange(of: target) { _, value in grow(to: value) }
        .accessibilityHidden(true)
    }

    private func grow(to value: Double) {
        withAnimation(JerreaderMotion.choose(JerreaderMotion.stateChange, reduceMotion: reduceMotion)) {
            shown = value
        }
    }
}

/// A ring that grows from zero to its value.
struct JerreaderProgressRing: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown: Double = 0

    let progress: Double
    var tint: Color = JerreaderTheme.accent
    var track: Color?
    var lineWidth: CGFloat = 6

    private var target: Double { min(max(progress.isFinite ? progress : 0, 0), 1) }

    var body: some View {
        ZStack {
            Circle()
                .stroke(track ?? tint.opacity(0.16), lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: shown)
                .stroke(tint, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .padding(lineWidth / 2)
        .onAppear { grow(to: target) }
        .onChange(of: target) { _, value in grow(to: value) }
        .accessibilityHidden(true)
    }

    private func grow(to value: Double) {
        withAnimation(JerreaderMotion.choose(JerreaderMotion.hero, reduceMotion: reduceMotion)) {
            shown = value
        }
    }
}

// MARK: - Segmented picker

/// The 2.0 segmented control: the selected option sits on an accent capsule
/// that slides between options, with a selection tick.
struct JerreaderPillPicker<Value: Hashable>: View {
    struct Option: Identifiable {
        let value: Value
        let title: String
        var systemImage: String?
        var identifier: String?

        var id: Value { value }
    }

    enum Layout {
        /// Icon beside the title, one row.
        case inline
        /// Icon above the title, as on the learning tabs.
        case stacked
    }

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var namespace

    let options: [Option]
    @Binding var selection: Value
    var layout: Layout = .inline
    var minHeight: CGFloat = 36

    var body: some View {
        HStack(spacing: 4) {
            ForEach(options) { option in
                optionButton(option)
            }
        }
        .padding(4)
        .background(JerreaderTheme.mutedSurface, in: Capsule())
        .sensoryFeedback(.selection, trigger: selection)
    }

    private func optionButton(_ option: Option) -> some View {
        let selected = option.value == selection
        return Button {
            guard selection != option.value else { return }
            withAnimation(JerreaderMotion.choose(JerreaderMotion.selection, reduceMotion: reduceMotion)) {
                selection = option.value
            }
        } label: {
            label(for: option, selected: selected)
                .foregroundStyle(selected ? JerreaderTheme.onPrimaryAction : Color.primary.opacity(0.72))
                .frame(maxWidth: .infinity, minHeight: minHeight)
                .padding(.horizontal, 6)
                .background {
                    if selected {
                        Capsule()
                            .fill(JerreaderTheme.accent)
                            .matchedGeometryEffect(id: "selection", in: namespace)
                            .jerreaderElevation(.card, cornerRadius: minHeight / 2, tint: JerreaderTheme.accent)
                    }
                }
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .jerreaderIdentifier(option.identifier)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder
    private func label(for option: Option, selected: Bool) -> some View {
        switch layout {
        case .inline:
            HStack(spacing: 6) {
                if let icon = option.systemImage {
                    JerreaderSymbol(name: icon, size: 15)
                        .jerreaderBounce(trigger: selected)
                }
                Text(option.title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
        case .stacked:
            VStack(spacing: 3) {
                if let icon = option.systemImage {
                    JerreaderSymbol(name: icon, size: 17)
                        .jerreaderBounce(trigger: selected)
                }
                Text(option.title)
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }
            .padding(.vertical, 4)
        }
    }
}

// MARK: - Buttons

/// The one filled accent action of a screen.
struct JerreaderPrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var height: CGFloat = 50
    var fullWidth = true

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(JerreaderTheme.onPrimaryAction)
            .lineLimit(1)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: height)
            .padding(.horizontal, 20)
            .background {
                Capsule()
                    .fill(JerreaderTheme.accent)
                    .overlay {
                        Capsule().fill(
                            LinearGradient(
                                colors: [Color.white.opacity(0.10), Color.white.opacity(0)],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                    }
            }
            .shadow(
                color: colorScheme == .dark || !isEnabled ? .clear : JerreaderTheme.accent.opacity(0.28),
                radius: 12,
                y: 6
            )
            .opacity(isEnabled ? 1 : 0.45)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(
                reduceMotion ? nil : (configuration.isPressed ? JerreaderMotion.press : JerreaderMotion.release),
                value: configuration.isPressed
            )
    }
}

/// A quieter action: accent text on an accent tint.
struct JerreaderTintedButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var height: CGFloat = 44
    var fullWidth = false
    var tint: Color = JerreaderTheme.accent

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(tint)
            .lineLimit(1)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: height)
            .padding(.horizontal, 18)
            .background(tint.opacity(0.12), in: Capsule())
            .opacity(isEnabled ? 1 : 0.45)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(
                reduceMotion ? nil : (configuration.isPressed ? JerreaderMotion.press : JerreaderMotion.release),
                value: configuration.isPressed
            )
    }
}

// MARK: - Hero card

/// The ink-and-accent background of a hero card: 继续阅读, 今日练习.
struct JerreaderHeroBackground: View {
    var cornerRadius: CGFloat = JerreaderRadius.hero

    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            .fill(
                LinearGradient(
                    colors: [JerreaderTheme.accentDeep, JerreaderTheme.ink],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            )
            .overlay {
                GeometryReader { proxy in
                    RadialGradient(
                        colors: [Color.white.opacity(0.16), Color.white.opacity(0)],
                        center: .topLeading,
                        startRadius: 0,
                        endRadius: max(proxy.size.width * 0.9, 1)
                    )
                }
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                .allowsHitTesting(false)
            }
            .jerreaderElevation(.raised, cornerRadius: cornerRadius)
            .accessibilityHidden(true)
    }
}

// MARK: - Book covers

/// Spine shade, gloss, a rounded edge and a two-layer shadow on a cover.
private struct JerreaderBookCoverModifier: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme

    let cornerRadius: CGFloat
    let elevated: Bool

    func body(content: Content) -> some View {
        let dark = colorScheme == .dark
        content
            .overlay(alignment: .leading) {
                GeometryReader { proxy in
                    HStack(spacing: 0) {
                        LinearGradient(
                            colors: [Color.black.opacity(0.22), Color.black.opacity(0)],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                        .frame(width: max(proxy.size.width * 0.08, 2))
                        Rectangle()
                            .fill(Color.white.opacity(0.12))
                            .frame(width: 1)
                        Spacer(minLength: 0)
                    }
                }
                .allowsHitTesting(false)
            }
            .overlay {
                LinearGradient(
                    colors: [Color.white.opacity(0.10), Color.white.opacity(0)],
                    startPoint: .topLeading,
                    endPoint: .center
                )
                .allowsHitTesting(false)
            }
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(Color.white.opacity(dark ? 0.06 : 0), lineWidth: 1)
                    .allowsHitTesting(false)
            }
            .shadow(color: dark || !elevated ? .clear : Color.black.opacity(0.18), radius: 2, y: 1)
            .shadow(
                color: dark || !elevated ? .clear : JerreaderTheme.shadowTint.opacity(JerreaderElevation.raised.opacity),
                radius: JerreaderElevation.raised.radius,
                y: JerreaderElevation.raised.y
            )
    }
}

extension View {
    func jerreaderBookCover(cornerRadius: CGFloat = JerreaderRadius.cover, elevated: Bool = true) -> some View {
        modifier(JerreaderBookCoverModifier(cornerRadius: cornerRadius, elevated: elevated))
    }
}

/// The cover of a book that has none: one of eight ink-wash colour pairs,
/// chosen by the title, with the title and author set on it.
struct JerreaderGeneratedCover: View {
    static let palettes: [(top: UInt32, bottom: UInt32)] = [
        (0x2C4A73, 0x172A45), // 藍
        (0x8C3B4A, 0x4E1F2B), // 臙脂
        (0x2F5D4E, 0x18352C), // 松葉
        (0xB45E3A, 0x6E3420), // 柿
        (0x4B5563, 0x262C35), // 鈍色
        (0x6A5A9E, 0x3A2F5E), // 藤
        (0xA5793A, 0x5E4320), // 黄土
        (0x4E8A86, 0x284B49), // 青磁
    ]

    /// Stable across launches and platforms: `h = h * 31 + byte` over the
    /// UTF-8 bytes, wrapping at 32 bits.
    static func paletteIndex(for title: String) -> Int {
        var hash: UInt32 = 0
        for byte in title.utf8 {
            hash = hash &* 31 &+ UInt32(byte)
        }
        return Int(hash % UInt32(palettes.count))
    }

    let title: String
    var author: String?
    /// Smaller type for grid-sized covers.
    var compact = false

    var body: some View {
        let pair = Self.palettes[Self.paletteIndex(for: title)]
        ZStack {
            LinearGradient(
                colors: [Color(uiColor: UIColor(jerreaderHex: pair.top)), Color(uiColor: UIColor(jerreaderHex: pair.bottom))],
                startPoint: .top,
                endPoint: .bottom
            )
            VStack(spacing: compact ? 6 : 10) {
                Spacer(minLength: 0)
                Text(title)
                    .font(.system(size: compact ? 14 : 19, weight: .bold))
                    .foregroundStyle(Color.white)
                    .multilineTextAlignment(.center)
                    .lineLimit(4)
                    .minimumScaleFactor(0.7)
                Rectangle()
                    .fill(Color.white.opacity(0.5))
                    .frame(width: compact ? 22 : 30, height: 1)
                if let author, !author.isEmpty {
                    Text(author)
                        .font(.system(size: compact ? 10 : 12, weight: .medium))
                        .foregroundStyle(Color.white.opacity(0.75))
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(compact ? 10 : 16)
        }
        .accessibilityHidden(true)
    }
}
