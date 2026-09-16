package dev.ryanchhab.flux.idea

import java.io.File

/**
 * One row of the Live Tuning section: an `@FluxLive` field as found in source
 * ([FluxLiveSourceScanner]) merged with what the robot last reported for it (`fluxRead`,
 * [FluxReadOutputParser]). `robotValue == null` means the robot side wasn't read back (read
 * failed, or this field wasn't in `fluxRead`'s table) -- the row still shows and is still
 * tunable from the source value, it just can't show a robot/source diff yet.
 */
data class LiveTuneEntry(
    val file: File,
    val fqClassName: String,
    val simpleClassName: String,
    val fieldName: String,
    val type: String,
    val sourceValue: String,
    val robotValue: String?,
    val differs: Boolean,
) {
    val label: String get() = "$simpleClassName.$fieldName"

    val isBoolean: Boolean get() = type == "boolean"
    val isNumeric: Boolean get() = type in NUMERIC_TYPES
    val isEnumOrString: Boolean get() = !isBoolean && !isNumeric

    companion object {
        private val NUMERIC_TYPES = setOf("byte", "short", "int", "long", "float", "double")
    }
}
