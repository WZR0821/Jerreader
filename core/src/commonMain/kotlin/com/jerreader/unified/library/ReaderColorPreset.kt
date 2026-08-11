package com.jerreader.unified.library

import com.jerreader.unified.reader.selection.ReaderSelectionVisualStyle

/**
 * A user-saved pair of page background and selection colour, presented in the
 * reader as one more background choice alongside 浅色 / 护眼 / 冷灰 / 深色.
 *
 * The pair is the point: a background that needs a hand-picked selection colour
 * to stay readable is useless if the two are saved separately and can drift
 * apart. Applying a preset always writes both.
 */
data class ReaderColorPreset(
    val id: String,
    val name: String,
    val backgroundHex: String,
    val selectionHex: String
)

/**
 * Encoding, validation and application of [ReaderColorPreset] lists.
 *
 * Both platforms persist the encoded string under the same preferences key, so
 * a backup taken on one restores on the other and neither can invent its own
 * on-disk shape. The format is deliberately not JSON: `:core` carries no
 * serialization dependency, and a record/field split over two characters that
 * [sanitizedName] refuses to store is simpler to keep identical across
 * Kotlin/JVM and Kotlin/Native than a hand-written JSON parser would be.
 */
object ReaderColorPresetStore {
    /**
     * Enough for a reader who keeps one set per book series, few enough that
     * the swatch row stays a row. Saving past the limit drops the oldest.
     */
    const val MAXIMUM_PRESETS = 12
    const val MAXIMUM_NAME_LENGTH = 16

    private const val RECORD_SEPARATOR = '\n'
    private const val FIELD_SEPARATOR = '\t'

    fun encode(presets: List<ReaderColorPreset>): String =
        presets.joinToString(RECORD_SEPARATOR.toString()) { preset ->
            listOf(preset.id, preset.name, preset.backgroundHex, preset.selectionHex)
                .joinToString(FIELD_SEPARATOR.toString())
        }

    fun decode(raw: String): List<ReaderColorPreset> {
        if (raw.isBlank()) return emptyList()
        val seen = mutableSetOf<String>()
        return raw.split(RECORD_SEPARATOR)
            .mapNotNull { line ->
                val fields = line.split(FIELD_SEPARATOR)
                if (fields.size != 4) return@mapNotNull null
                val id = fields[0].trim()
                val name = sanitizedName(fields[1])
                val background = normalizedHex(fields[2])
                val selection = normalizedHex(fields[3])
                // A record missing either colour is not a colour set. Dropping
                // it beats restoring a swatch that paints nothing.
                if (id.isEmpty() || name.isEmpty()) return@mapNotNull null
                if (background.isEmpty() || selection.isEmpty()) return@mapNotNull null
                if (!seen.add(id)) return@mapNotNull null
                ReaderColorPreset(id, name, background, selection)
            }
            .take(MAXIMUM_PRESETS)
    }

    /**
     * Returns the list with the preset added, or unchanged when the colours are
     * incomplete. An existing entry with the same id is replaced in place so a
     * rename or recolour does not reorder the row.
     */
    fun added(
        existing: List<ReaderColorPreset>,
        id: String,
        name: String,
        backgroundHex: String,
        selectionHex: String
    ): List<ReaderColorPreset> {
        val preset = ReaderColorPreset(
            id = id.trim(),
            name = sanitizedName(name),
            backgroundHex = normalizedHex(backgroundHex),
            selectionHex = normalizedHex(selectionHex)
        )
        if (preset.id.isEmpty() || preset.name.isEmpty()) return existing
        if (preset.backgroundHex.isEmpty() || preset.selectionHex.isEmpty()) return existing

        val index = existing.indexOfFirst { it.id == preset.id }
        if (index >= 0) {
            return existing.toMutableList().apply { this[index] = preset }
        }
        return (existing + preset).takeLast(MAXIMUM_PRESETS)
    }

    /**
     * The list with the colours [appearance] is currently showing saved as a
     * set, under [name] or a generated one when [name] is empty.
     *
     * The three-way "derive the id, resolve both colours, fall back on the
     * name" dance is the part a host gets wrong, and getting it wrong is
     * silent — [added] simply returns the list it was handed. Both apps go
     * through here so neither can.
     */
    fun added(
        existing: List<ReaderColorPreset>,
        appearance: ReaderAppearance,
        name: String
    ): List<ReaderColorPreset> = added(
        existing = existing,
        id = idFor(appearance),
        name = sanitizedName(name).ifEmpty { defaultName(existing) },
        backgroundHex = backgroundHexFor(appearance),
        selectionHex = selectionHexFor(appearance)
    )

    fun removed(existing: List<ReaderColorPreset>, id: String): List<ReaderColorPreset> =
        existing.filterNot { it.id == id }

    /**
     * True when [appearance] is currently showing exactly this colour set.
     *
     * Compared on the colours in force, not on the two custom fields: a set
     * saved off 护眼 stores the theme's own paper, and reading only
     * `customBackgroundHex` left its swatch unmarked while the reader was
     * looking at precisely that colour.
     */
    fun matches(preset: ReaderColorPreset, appearance: ReaderAppearance): Boolean =
        backgroundHexFor(appearance) == preset.backgroundHex &&
            selectionHexFor(appearance) == preset.selectionHex

    /**
     * Both colours at once. [ReaderAppearance.theme] is left alone: it still
     * decides the text colour the page falls back to, and a preset only claims
     * the two custom fields it was built from.
     */
    fun applied(appearance: ReaderAppearance, preset: ReaderColorPreset): ReaderAppearance =
        appearance.copy(
            customBackgroundHex = preset.backgroundHex,
            customSelectionColorHex = preset.selectionHex
        )

    /** A name safe to round-trip through [encode]: no separators, length capped. */
    fun sanitizedName(value: String): String =
        value.map { if (it == RECORD_SEPARATOR || it == FIELD_SEPARATOR) ' ' else it }
            .joinToString("")
            .filterNot { it.isISOControl() }
            .trim()
            .take(MAXIMUM_NAME_LENGTH)

    /** `#RRGGBB` upper case, or empty when [value] is not a complete colour. */
    fun normalizedHex(value: String): String {
        val digits = value.trim().removePrefix("#").uppercase()
        if (digits.length != 6 || digits.any { it !in "0123456789ABCDEF" }) return ""
        return "#$digits"
    }

    /**
     * A stable id for a new preset.
     *
     * Derived from the colours rather than from a clock so that saving the same
     * pair twice updates the one entry instead of filling the row with
     * duplicates, and so both platforms generate the same id for the same set.
     */
    fun idFor(backgroundHex: String, selectionHex: String): String {
        val background = normalizedHex(backgroundHex).removePrefix("#")
        val selection = normalizedHex(selectionHex).removePrefix("#")
        if (background.isEmpty() || selection.isEmpty()) return ""
        return "$background-$selection"
    }

    /**
     * The page colour a set saved from [appearance] carries.
     *
     * A set is saved from what the reader is *looking at*, which is a colour
     * whether or not it came from the colour picker. Requiring
     * `customBackgroundHex` to be filled in meant a reader on 护眼 with a
     * hand-picked selection colour had nothing to save.
     */
    fun backgroundHexFor(appearance: ReaderAppearance): String =
        ReaderPageBackground.toRgbHex(ReaderPageBackground.argb(appearance))

    /**
     * The selection colour a set saved from [appearance] carries.
     *
     * Saving used to need 自定义选区颜色 switched on as well, and both toggles
     * being on at once is rare — which is what made the feature look like it
     * could only ever hold one set: after the first save the reader changed the
     * background, the pair was no longer complete, and the button went dead
     * with no way to tell that from "the limit is one". When no colour was
     * picked by hand, the one the reader has been seeing all along is the
     * honest answer: [ReaderSelectionVisualStyle] already derives it from the
     * page, so take that. Its fill carries an alpha the swatch has no use for,
     * so only the hue is kept.
     */
    fun selectionHexFor(appearance: ReaderAppearance): String {
        val chosen = normalizedHex(appearance.customSelectionColorHex)
        if (chosen.isNotEmpty()) return chosen
        return ReaderPageBackground.toRgbHex(
            ReaderSelectionVisualStyle.palette(appearance).fillArgb
        )
    }

    /** The id a set saved from [appearance] gets. Never empty. */
    fun idFor(appearance: ReaderAppearance): String =
        idFor(backgroundHexFor(appearance), selectionHexFor(appearance))

    /**
     * A name for a set the reader did not name.
     *
     * The naming sheet can be dismissed with an empty field, and [added] drops
     * a nameless set — a save that reports success and stores nothing. Naming
     * it 配色 N is worse than a good name and far better than silence.
     */
    fun defaultName(existing: List<ReaderColorPreset>): String {
        val used = existing.map { it.name }.toSet()
        var index = existing.size + 1
        while ("配色 $index" in used) index += 1
        return "配色 $index"
    }
}
