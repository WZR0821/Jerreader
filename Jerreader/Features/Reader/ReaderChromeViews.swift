import SwiftUI

// 2.0 阅读器界面的视图组件，按 docs/DESIGN_2.0.md 实现。
//
// Reader chrome (the top and bottom bars), the pieces of the 阅读设置 sheet
// and the small controls of the translation card. Values come from
// docs/DESIGN_2.0.md §7 阅读器; Android mirrors them in the reader screen.

// MARK: - Metrics

enum ReaderChromeMetrics {
    /// The hit frame of every bar and card button.
    static let hitSize: CGFloat = 44
    /// The circle drawn behind a top-bar icon.
    static let iconCircle: CGFloat = 36
    /// Bar glyphs.
    static let iconSize: CGFloat = 22
    /// The bottom bar's filled 轻点翻译 circle; with the 44pt slots it keeps
    /// the bottom bar as compact as in 1.7.0 (about 78pt with progress).
    static let translateCircle: CGFloat = 40
    /// The progress slider.
    static let thumbSize: CGFloat = 22
    static let trackHeight: CGFloat = 4
    static let sliderHeight: CGFloat = 26
    /// How far a bar travels while it fades in or out.
    static let barTravel: CGFloat = 12
}

// MARK: - Bars

/// The background of a reader bar: the system bar material under the page
/// colour at 78%, so a bar reads as part of the page but still frosts text
/// passing under it in the immersive layout. A 0.5pt `line` hairline runs
/// along the edge that faces the page. `opacity` is the user's 顶部栏/底部栏
/// 透明度 turned into an opacity (1 at 0% transparency), and the surface
/// carries on into the safe area of its screen edge, as a system bar does.
/// It only draws: the layout frame of the bar is untouched.
struct ReaderBarSurface: View {
    let pageColor: Color
    let opacity: Double
    /// The screen edge the bar sits on.
    let edge: VerticalEdge

    var body: some View {
        ZStack {
            Rectangle().fill(Material.bar)
            Rectangle().fill(pageColor.opacity(0.78))
        }
        .overlay(alignment: edge == .top ? .bottom : .top) {
            Rectangle()
                .fill(JerreaderTheme.line)
                .frame(height: 0.5)
        }
        .opacity(opacity)
        .ignoresSafeArea(.container, edges: edge == .top ? .top : .bottom)
        .accessibilityHidden(true)
    }
}

/// A top-bar icon: the glyph sits in a 36pt circle inside a 44pt hit frame.
/// The circle is clear at rest, an 8% wash while pressed, and the accent
/// fill with an accent glyph while the button is on (a bookmarked page).
struct ReaderBarIconButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var isOn = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(isOn ? JerreaderTheme.accent : Color.primary)
            .frame(width: ReaderChromeMetrics.iconCircle, height: ReaderChromeMetrics.iconCircle)
            .background {
                Circle().fill(circleFill(pressed: configuration.isPressed))
            }
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.92 : 1)
            .frame(width: ReaderChromeMetrics.hitSize, height: ReaderChromeMetrics.hitSize)
            .contentShape(Rectangle())
            .animation(
                reduceMotion ? nil : (configuration.isPressed ? JerreaderMotion.press : JerreaderMotion.release),
                value: configuration.isPressed
            )
            .animation(reduceMotion ? nil : JerreaderMotion.stateChange, value: isOn)
    }

    private func circleFill(pressed: Bool) -> Color {
        if isOn {
            return pressed ? JerreaderTheme.accent.opacity(0.24) : JerreaderTheme.accentFill
        }
        return Color.primary.opacity(pressed ? 0.08 : 0)
    }
}

/// A page-turn glyph in the bottom bar: primary at 62%, with a 40pt wash
/// while pressed. The whole slot is the hit area.
struct ReaderPageButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(Color.primary.opacity(isEnabled ? 0.62 : 0.26))
            .frame(width: 40, height: 40)
            .background {
                Circle().fill(Color.primary.opacity(configuration.isPressed ? 0.08 : 0))
            }
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.92 : 1)
            .frame(maxWidth: .infinity, minHeight: ReaderChromeMetrics.hitSize)
            .contentShape(Rectangle())
            .animation(
                reduceMotion ? nil : (configuration.isPressed ? JerreaderMotion.press : JerreaderMotion.release),
                value: configuration.isPressed
            )
    }
}

/// The bottom bar's one solid accent control, 轻点翻译, as a 40pt circle.
/// On: accent fill, `onPrimaryAction` glyph and an accent-tinted e2 shadow.
/// Off: the accent tint with a 1pt accent ring and no shadow.
struct ReaderTranslateToggleButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let isOn: Bool

    func makeBody(configuration: Configuration) -> some View {
        let size = ReaderChromeMetrics.translateCircle
        configuration.label
            .foregroundStyle(isOn ? JerreaderTheme.onPrimaryAction : JerreaderTheme.accent)
            .frame(width: size, height: size)
            .background {
                Circle().fill(isOn ? JerreaderTheme.accent : JerreaderTheme.accentFill)
            }
            .overlay {
                Circle()
                    .strokeBorder(JerreaderTheme.accent.opacity(isOn ? 0 : 0.28), lineWidth: 1)
            }
            .jerreaderElevation(
                .raised,
                cornerRadius: size / 2,
                tint: isOn ? JerreaderTheme.accent : Color.clear
            )
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.92 : 1)
            .frame(maxWidth: .infinity, minHeight: ReaderChromeMetrics.hitSize)
            .contentShape(Rectangle())
            .animation(
                reduceMotion ? nil : (configuration.isPressed ? JerreaderMotion.press : JerreaderMotion.release),
                value: configuration.isPressed
            )
            .animation(reduceMotion ? nil : JerreaderMotion.stateChange, value: isOn)
    }
}

/// The reading-position slider: a 4pt `line` track, an accent fill and a
/// 22pt white thumb with e1. As with UISlider, only a touch that lands on
/// the thumb (with 14pt of slop either side) moves it, and the thumb keeps
/// its offset from the finger, so a tap on the track never moves the book.
/// `onEditingChanged` fires `true` when a drag picks the thumb up and
/// `false` once when it ends or is cancelled. VoiceOver sees a standard
/// adjustable Slider with the same binding, label and value.
struct ReaderProgressSlider: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Binding var value: Double
    let accessibilityLabelText: String
    let accessibilityValueText: String
    let onEditingChanged: (Bool) -> Void

    /// Finger position minus thumb centre when the drag began; nil while idle.
    @State private var grabOffset: CGFloat? = nil
    /// The current touch began off the thumb, so it is ignored until it ends.
    @State private var ignoresCurrentTouch = false
    @GestureState private var isTouching = false

    private var thumb: CGFloat { ReaderChromeMetrics.thumbSize }
    /// How far beside the thumb a touch still picks it up.
    private static let slop: CGFloat = 14

    var body: some View {
        GeometryReader { proxy in
            let usable = max(proxy.size.width - thumb, 1)
            let fraction = CGFloat(Self.clamped(value))
            let thumbCenter = thumb / 2 + usable * fraction
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(JerreaderTheme.line)
                    .frame(width: usable, height: ReaderChromeMetrics.trackHeight)
                    .offset(x: thumb / 2)
                Capsule()
                    .fill(JerreaderTheme.accent)
                    .frame(width: usable * fraction, height: ReaderChromeMetrics.trackHeight)
                    .offset(x: thumb / 2)
                Circle()
                    .fill(Color.white)
                    .frame(width: thumb, height: thumb)
                    .overlay {
                        Circle().strokeBorder(Color.black.opacity(0.06), lineWidth: 0.5)
                    }
                    .jerreaderElevation(.card, cornerRadius: thumb / 2)
                    .scaleEffect(grabOffset != nil && !reduceMotion ? 1.12 : 1)
                    .animation(
                        reduceMotion ? nil : (grabOffset != nil ? JerreaderMotion.press : JerreaderMotion.release),
                        value: grabOffset != nil
                    )
                    .offset(x: thumbCenter - thumb / 2)
            }
            .frame(width: proxy.size.width, height: proxy.size.height, alignment: .leading)
            .contentShape(Rectangle())
            .gesture(dragGesture(thumbCenter: thumbCenter, usable: usable))
        }
        .frame(height: ReaderChromeMetrics.sliderHeight)
        .onChange(of: isTouching) { _, touching in
            if !touching { endDrag() }
        }
        .accessibilityRepresentation {
            Slider(value: $value, in: 0 ... 1, onEditingChanged: onEditingChanged)
                .accessibilityLabel(accessibilityLabelText)
                .accessibilityValue(accessibilityValueText)
        }
    }

    private func dragGesture(thumbCenter: CGFloat, usable: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .local)
            .updating($isTouching) { _, touching, _ in
                touching = true
            }
            .onChanged { drag in
                var anchor = grabOffset
                if anchor == nil {
                    guard !ignoresCurrentTouch else { return }
                    guard abs(drag.startLocation.x - thumbCenter) <= thumb / 2 + Self.slop else {
                        ignoresCurrentTouch = true
                        return
                    }
                    let offset = drag.startLocation.x - thumbCenter
                    anchor = offset
                    grabOffset = offset
                    onEditingChanged(true)
                }
                guard let grab = anchor else { return }
                let center = drag.location.x - grab
                let next = Double(min(max((center - thumb / 2) / usable, 0), 1))
                if next != value {
                    value = next
                }
            }
            .onEnded { _ in
                endDrag()
            }
    }

    /// Ends a drag exactly once, whether it finished or was cancelled.
    private func endDrag() {
        ignoresCurrentTouch = false
        guard grabOffset != nil else { return }
        grabOffset = nil
        onEditingChanged(false)
    }

    private static func clamped(_ value: Double) -> Double {
        value.isFinite ? min(max(value, 0), 1) : 0
    }
}

/// Bars come and go by fading while they travel 12pt towards their own
/// edge, on `JerreaderMotion.chrome` (0.20s ease-out, no bounce). With
/// Reduce Motion they only cross-fade (`JerreaderMotion.reduced`). Offset and
/// opacity leave the layout frame alone, so measured bar heights and the
/// page between the bars never change.
private struct ReaderChromeBarTransition: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let isVisible: Bool
    let edge: VerticalEdge

    func body(content: Content) -> some View {
        let travel = reduceMotion ? 0 : ReaderChromeMetrics.barTravel
        content
            .offset(y: isVisible ? 0 : (edge == .top ? -travel : travel))
            .opacity(isVisible ? 1 : 0)
            .animation(
                JerreaderMotion.choose(JerreaderMotion.chrome, reduceMotion: reduceMotion),
                value: isVisible
            )
    }
}

extension View {
    func readerChromeBar(isVisible: Bool, edge: VerticalEdge) -> some View {
        modifier(ReaderChromeBarTransition(isVisible: isVisible, edge: edge))
    }
}

// MARK: - Translation card

/// A round icon action on the translation card: a 32pt wash in a 44pt hit
/// frame, secondary glyph; the accent tint and glyph while on (a favourite).
struct ReaderCardIconButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.isEnabled) private var isEnabled
    var isOn = false
    var circle: CGFloat = 32

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(isOn ? JerreaderTheme.accent : Color.secondary)
            .frame(width: circle, height: circle)
            .background {
                Circle().fill(
                    isOn
                        ? JerreaderTheme.accentFill
                        : Color.primary.opacity(configuration.isPressed ? 0.12 : 0.06)
                )
            }
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.9 : 1)
            .opacity(isEnabled ? 1 : 0.45)
            .frame(width: ReaderChromeMetrics.hitSize, height: ReaderChromeMetrics.hitSize)
            .contentShape(Rectangle())
            .animation(
                reduceMotion ? nil : (configuration.isPressed ? JerreaderMotion.press : JerreaderMotion.release),
                value: configuration.isPressed
            )
            .animation(reduceMotion ? nil : JerreaderMotion.stateChange, value: isOn)
    }
}

/// The look of a card icon that is not a Button (the 更多翻译操作 menu's
/// label), matching `ReaderCardIconButtonStyle` at rest.
struct ReaderCardIconLabel: View {
    let systemImage: String
    var size: CGFloat = 18
    var circle: CGFloat = 32

    var body: some View {
        JerreaderSymbol(name: systemImage, size: size)
            .foregroundStyle(Color.secondary)
            .frame(width: circle, height: circle)
            .background(Color.primary.opacity(0.06), in: Circle())
            .frame(width: ReaderChromeMetrics.hitSize, height: ReaderChromeMetrics.hitSize)
            .contentShape(Rectangle())
    }
}

// MARK: - 阅读设置 sheet

/// A titled group of settings on paper, the 2.0 replacement for a Form
/// section: a 13pt semibold secondary title, the rows on a paper card
/// (radius 20, `line` stroke, e1 in light mode) and an optional 13pt
/// secondary footer. A group with no rows shows only its title and footer.
struct ReaderSettingsCard<Content: View>: View {
    let title: String
    let footer: String?
    let hasRows: Bool
    let content: Content

    init(
        _ title: String,
        footer: String? = nil,
        hasRows: Bool = true,
        @ViewBuilder content: () -> Content
    ) {
        self.title = title
        self.footer = footer
        self.hasRows = hasRows
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: JerreaderSpacing.xs) {
            Text(title)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.horizontal, JerreaderSpacing.xxs)
                .accessibilityAddTraits(.isHeader)

            if hasRows {
                VStack(alignment: .leading, spacing: 0) {
                    content
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .jerreaderPaperCard(padding: 0, radius: JerreaderRadius.card)
            }

            if let footer {
                Text(footer)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, JerreaderSpacing.xxs)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The hairline between two rows of a `ReaderSettingsCard`, inset 16pt.
struct ReaderSettingsDivider: View {
    var body: some View {
        Rectangle()
            .fill(JerreaderTheme.line)
            .frame(height: 0.5)
            .padding(.leading, JerreaderSpacing.md)
            .accessibilityHidden(true)
    }
}

extension View {
    /// One row of a `ReaderSettingsCard`: 16pt sides, 11pt top and bottom,
    /// at least 44pt tall.
    func readerSettingsRow() -> some View {
        frame(maxWidth: .infinity, minHeight: 22, alignment: .leading)
            .padding(.horizontal, JerreaderSpacing.md)
            .padding(.vertical, 11)
    }
}

/// A round background swatch: 44pt with a 1pt hairline; the chosen one
/// gets a 2pt accent ring 2pt outside it and a check. The ring grows from
/// 90% and the check from 60% on `JerreaderMotion.stateChange`; with Reduce
/// Motion they only fade.
struct ReaderThemeSwatch: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let color: Color
    let title: String
    let isSelected: Bool
    /// White on a dark swatch, the accent on a light one.
    let checkColor: Color

    var body: some View {
        VStack(spacing: JerreaderSpacing.xs) {
            ZStack {
                Circle()
                    .strokeBorder(JerreaderTheme.accent, lineWidth: 2)
                    .frame(width: 52, height: 52)
                    .scaleEffect(isSelected || reduceMotion ? 1 : 0.9)
                    .opacity(isSelected ? 1 : 0)
                Circle()
                    .fill(color)
                    .frame(width: 44, height: 44)
                    .overlay {
                        Circle().strokeBorder(Color.primary.opacity(0.12), lineWidth: 1)
                    }
                JerreaderSymbol(name: "CHECK", size: 18)
                    .foregroundStyle(checkColor)
                    .scaleEffect(isSelected || reduceMotion ? 1 : 0.6)
                    .opacity(isSelected ? 1 : 0)
            }
            .frame(width: 52, height: 52)

            Text(title)
                .font(.caption.weight(isSelected ? .semibold : .regular))
                .foregroundStyle(isSelected ? JerreaderTheme.accent : Color.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .animation(
            JerreaderMotion.choose(JerreaderMotion.stateChange, reduceMotion: reduceMotion),
            value: isSelected
        )
    }
}

/// A minus or a plus on the icon grid (24 units, 1.65 stroke, round caps,
/// arms from 3 to 21 like the drawn PLUS icon). The drawn icon set has no
/// minus, so both are built from capsules here to match each other.
struct ReaderStepGlyph: View {
    let isPlus: Bool
    var size: CGFloat = 12

    var body: some View {
        let stroke = max(size * 1.65 / 24, 1)
        let length = size * 18 / 24 + stroke
        ZStack {
            Capsule().frame(width: length, height: stroke)
            if isPlus {
                Capsule().frame(width: stroke, height: length)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// 字号 as "A − value + A": a small A with a minus, the size rolling as it
/// changes, a plus with a large A, on a muted capsule. Each tap moves one
/// `step` (the slider's old step, so every size stays reachable) and
/// holding a button repeats it. Each step ticks a selection haptic.
struct ReaderFontSizeStepper: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let value: Double
    let range: ClosedRange<Double>
    let step: Double
    let valueText: String
    let onStep: (Double) -> Void

    var body: some View {
        HStack(spacing: 2) {
            Button {
                move(by: -step)
            } label: {
                HStack(spacing: 3) {
                    Text("A")
                        .font(.system(size: 13, weight: .semibold))
                    ReaderStepGlyph(isPlus: false, size: 11)
                }
            }
            .buttonStyle(ReaderStepperButtonStyle())
            .disabled(value <= range.lowerBound + 0.001)
            .accessibilityLabel("减小字号")

            Text(valueText)
                .font(.subheadline.weight(.semibold))
                .monospacedDigit()
                .foregroundStyle(.primary)
                .contentTransition(reduceMotion ? .opacity : .numericText(value: value))
                .animation(
                    JerreaderMotion.choose(JerreaderMotion.stateChange, reduceMotion: reduceMotion),
                    value: value
                )
                .lineLimit(1)
                .frame(minWidth: 62)

            Button {
                move(by: step)
            } label: {
                HStack(spacing: 3) {
                    ReaderStepGlyph(isPlus: true, size: 11)
                    Text("A")
                        .font(.system(size: 19, weight: .semibold))
                }
            }
            .buttonStyle(ReaderStepperButtonStyle())
            .disabled(value >= range.upperBound - 0.001)
            .accessibilityLabel("增大字号")
        }
        .padding(3)
        .background(JerreaderTheme.mutedSurface, in: Capsule())
        .buttonRepeatBehavior(.enabled)
        .sensoryFeedback(.selection, trigger: value)
    }

    private func move(by delta: Double) {
        let next = min(max(value + delta, range.lowerBound), range.upperBound)
        guard abs(next - value) > 0.0001 else { return }
        onStep(next)
    }
}

/// A key of the font-size stepper: a paper capsule at least 52×38 on the
/// stepper's muted track.
struct ReaderStepperButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(Color.primary.opacity(isEnabled ? 0.86 : 0.28))
            .frame(minWidth: 52, minHeight: 38)
            .background {
                Capsule()
                    .fill(JerreaderTheme.paper)
                    .opacity(configuration.isPressed ? 0.55 : 1)
            }
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.94 : 1)
            .contentShape(Capsule())
            .animation(
                reduceMotion ? nil : (configuration.isPressed ? JerreaderMotion.press : JerreaderMotion.release),
                value: configuration.isPressed
            )
    }
}
