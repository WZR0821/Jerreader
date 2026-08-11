import JerreaderCore
import SwiftUI

/// Where the saved colour sets live.
///
/// The encoded string, the name sanitising and the id derivation all come from
/// `core/library/ReaderColorPreset.kt`, which Android reads from too — the two
/// apps must agree on what a set *is*, not merely both have a feature called
/// 配色套系.
enum ReaderColorPresetDefaults {
    static let storageKey = "reader.colorPresets"

    static func presets(defaults: UserDefaults = .standard) -> [ReaderColorPreset] {
        ReaderColorPresetStore.shared.decode(raw: defaults.string(forKey: storageKey) ?? "")
    }
}

/// The swatch row plus the control that saves the current pair as a new set.
///
/// Both the per-book reader settings and the global defaults page show this, so
/// a set saved in one is immediately offered by the other.
struct ReaderColorPresetSection: View {
    let theme: ReaderThemeChoice
    let backgroundHex: String
    let selectionHex: String
    /// Applies both colours at once; a set is the pair.
    let onApply: (String, String) -> Void

    @AppStorage(ReaderColorPresetDefaults.storageKey) private var encodedPresets = ""
    @State private var isNaming = false

    private var presets: [ReaderColorPreset] {
        ReaderColorPresetStore.shared.decode(raw: encodedPresets)
    }

    /// What the page actually looks like right now — which is a complete pair
    /// of colours whether or not either custom toggle is on.
    private var appearance: ReaderAppearance {
        ReaderAppearance.from(
            theme: theme,
            customBackgroundHex: backgroundHex,
            customSelectionColorHex: selectionHex
        )
    }

    var body: some View {
        if !presets.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(presets, id: \.id) { preset in
                        swatch(for: preset)
                    }
                }
                .padding(.vertical, 4)
            }
        }

        // Naming happens in its own sheet. An inline row put the field at the
        // bottom of a long form where the keyboard covered its own save
        // button, and an alert's button action reads the presenting view's
        // `@State` as it was when the alert was built — so the name typed into
        // it arrived empty and the save quietly did nothing. The sheet owns
        // its text and hands it back, which neither of those can get wrong.
        Button {
            isNaming = true
        } label: {
            Label("保存当前配色为套系", systemImage: "square.on.square.badge.person.crop")
        }
        .sheet(isPresented: $isNaming) {
            ReaderColorPresetNamingSheet(
                suggestedName: ReaderColorPresetStore.shared.defaultName(existing: presets)
            ) { name in
                save(named: name)
            }
        }
    }

    private func swatch(for preset: ReaderColorPreset) -> some View {
        let isSelected = ReaderColorPresetStore.shared.matches(
            preset: preset,
            appearance: appearance
        )
        return VStack(spacing: 6) {
            ZStack {
                Circle()
                    .fill(Color(hex: preset.backgroundHex) ?? JerreaderTheme.paper)
                    .frame(width: 38, height: 38)
                    .overlay {
                        Circle().stroke(
                            isSelected ? JerreaderTheme.accent : Color.secondary.opacity(0.22),
                            lineWidth: isSelected ? 3 : 1
                        )
                    }
                // The inner dot is the selection colour. Without it two sets
                // built on the same paper are indistinguishable in the row.
                Circle()
                    .fill(Color(hex: preset.selectionHex) ?? JerreaderTheme.accent)
                    .frame(width: 15, height: 15)
            }
            Text(preset.name)
                .font(.caption2)
                .lineLimit(1)
        }
        .frame(width: 64)
        .contentShape(Rectangle())
        .onTapGesture { onApply(preset.backgroundHex, preset.selectionHex) }
        .contextMenu {
            Button("删除套系", role: .destructive) { remove(preset) }
        }
        .accessibilityLabel("\(preset.name)配色套系")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private func save(named name: String) {
        let store = ReaderColorPresetStore.shared
        encodedPresets = store.encode(
            presets: store.added(existing: presets, appearance: appearance, name: name)
        )
        isNaming = false
    }

    private func remove(_ preset: ReaderColorPreset) {
        let store = ReaderColorPresetStore.shared
        encodedPresets = store.encode(presets: store.removed(existing: presets, id: preset.id))
    }
}

/// The naming step, as its own screen.
///
/// It owns the text and reports it through [onSave], so nothing depends on a
/// parent's `@State` being read at the right moment.
private struct ReaderColorPresetNamingSheet: View {
    /// Used when the field is left empty, and shown as its placeholder.
    let suggestedName: String
    let onSave: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @FocusState private var isFieldFocused: Bool

    var body: some View {
        NavigationStack {
            // Plain stack, not a `Form`. The form lived in a fixed-height
            // detent that the keyboard covers whole — the sheet does not rise
            // for the keyboard the way a pushed screen does — so the reader was
            // typing into a field they could not see. This sits at the top of a
            // half sheet, well clear of it.
            VStack(alignment: .leading, spacing: 10) {
                TextField(suggestedName, text: $name)
                    .textFieldStyle(.plain)
                    .font(.title3)
                    .focused($isFieldFocused)
                    .submitLabel(.done)
                    .onSubmit(save)
                    // The length cap is the only rule that has to hold *while*
                    // typing, and it must not touch the binding otherwise:
                    // rewriting the text on every keystroke drops a 拼音
                    // candidate mid-composition. Trimming is left to
                    // `sanitizedName` at save time so a space between two words
                    // does not vanish as it is entered.
                    .onChange(of: name) {
                        let limit = Int(ReaderColorPresetStore.shared.MAXIMUM_NAME_LENGTH)
                        if name.count > limit {
                            name = String(name.prefix(limit))
                        }
                    }
                Divider()
                Text("给当前的背景色和选区色起个名字，之后在任意一本书里都能一键套用。不填就用「\(suggestedName)」。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Spacer(minLength: 0)
            }
            .padding(20)
            .navigationTitle("保存配色套系")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    // Never disabled: an empty field means 「用建议的名字」, not
                    // 「保存不了」. A disabled save with no explanation is what
                    // made this look broken.
                    Button("保存", action: save)
                }
            }
            // Focusing straight from `onAppear` races the sheet's own
            // presentation animation and the keyboard silently never comes up.
            .task {
                try? await Task.sleep(nanoseconds: 350_000_000)
                isFieldFocused = true
            }
        }
        .presentationDetents([.medium])
    }

    private func save() {
        // Sanitising is `added`'s job, and it falls back to the suggested name
        // on an empty one — so there is no path here that silently stores
        // nothing.
        onSave(name)
        dismiss()
    }
}

extension Color {
    /// `#RRGGBB` only — the same shape `ReaderColorPresetStore` stores.
    init?(hex: String) {
        let digits = ReaderColorPresetStore.shared.normalizedHex(value: hex).dropFirst()
        guard digits.count == 6, let raw = UInt32(digits, radix: 16) else { return nil }
        self.init(
            red: Double((raw >> 16) & 0xFF) / 255,
            green: Double((raw >> 8) & 0xFF) / 255,
            blue: Double(raw & 0xFF) / 255
        )
    }
}
