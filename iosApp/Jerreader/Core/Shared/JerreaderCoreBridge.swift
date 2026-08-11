import CoreGraphics
import JerreaderCore

/// Translation between this app's UIKit/SwiftUI types and the shared Kotlin
/// module's own geometry and model types.
///
/// Nothing here makes a decision. Every rule the reader applies to a selection —
/// which rectangles to paint, what colour they are, which side of the sentence
/// the translation card goes on — now lives in `JerreaderCore` and is shared
/// with the Android app. This file only carries values across the boundary, so
/// the Swift views above it never had to change and the UI stays exactly what
/// it was.

// MARK: - Geometry

extension ReaderRect {
    /// A `CGRect` as the shared module sees it, with negative extents
    /// normalised the way `standardized` does.
    static func from(_ rect: CGRect) -> ReaderRect {
        let standard = rect.standardized
        return ReaderRect(
            x: Double(standard.origin.x),
            y: Double(standard.origin.y),
            width: Double(standard.width),
            height: Double(standard.height)
        )
    }

    var cgRect: CGRect {
        CGRect(x: x, y: y, width: width, height: height)
    }
}

extension ReaderSize {
    static func from(_ size: CGSize) -> ReaderSize {
        ReaderSize(width: Double(size.width), height: Double(size.height))
    }
}

extension ReaderPoint {
    static func from(_ point: CGPoint) -> ReaderPoint {
        ReaderPoint(x: Double(point.x), y: Double(point.y))
    }

    var cgPoint: CGPoint {
        CGPoint(x: x, y: y)
    }
}

// MARK: - Appearance

extension ReaderThemeOption {
    static func from(_ theme: ReaderThemeChoice) -> ReaderThemeOption {
        switch theme {
        case .light: return .light
        case .sepia: return .sepia
        case .coolGray: return .coolGray
        case .dark: return .dark
        }
    }
}

extension ReaderAppearance {
    static func from(
        theme: ReaderThemeChoice,
        orientation: ReaderTextOrientation = .publication,
        customBackgroundHex: String,
        customSelectionColorHex: String
    ) -> ReaderAppearance {
        // `forSelection` rather than the full initialiser: Kotlin's default
        // arguments do not survive the Objective-C export, and spelling out the
        // other seven fields here would mean this file quietly deciding what
        // the reader's font scale and margins are.
        ReaderAppearance.companion.forSelection(
            theme: ReaderThemeOption.from(theme),
            orientation: orientation,
            customBackgroundHex: customBackgroundHex,
            customSelectionColorHex: customSelectionColorHex
        )
    }
}

extension ReaderWritingMode {
    static func of(vertical: Bool) -> ReaderWritingMode {
        vertical ? .vertical : .horizontal
    }
}

// MARK: - Speech

/// The app's own `ReaderSpeechHighlight` carried across the boundary.
///
/// Taking the two bounds rather than the struct sidesteps the name collision
/// between the app's type and the shared one — both are called
/// `ReaderSpeechHighlight`, and neither name is worth changing just to satisfy
/// this one call. Returns nil for a degenerate range, matching the shared
/// factory's rule that an empty span paints nothing rather than everything.
func sharedSpeechHighlight(
    lowerBound: Double?,
    upperBound: Double?
) -> JerreaderCore.ReaderSpeechHighlight? {
    guard let lowerBound, let upperBound else { return nil }
    return JerreaderCore.ReaderSpeechHighlight.companion.of(
        lower: lowerBound,
        upper: upperBound
    )
}
