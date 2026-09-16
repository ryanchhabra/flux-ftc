package dev.flux.gradle

import java.io.File

/**
 * Plain-old-data mirror of [LiveTuneDetector.Result], persisted the same way and for the same
 * reason as [TierOutcome]: classification happens exactly once, inside `fluxAssemble`, and
 * `fluxDeploy`/`fluxTune`'s `onlyIf` gates + reporting read this back rather than re-parsing
 * source per task (see [TierOutcome]'s class doc for the full rationale).
 *
 * Fields are encoded flat rather than structured (no JSON/Properties library pulled in for this):
 * each entry in [changedFields] is `"classfieldtypevalue"`, matching
 * [LiveTuneDetector.LiveField]'s shape 1:1 so [FluxTune] can parse it back without re-deriving
 * anything. [warnings] entries are `"filefieldmessage"`.
 */
data class LiveTuneOutcome(
    val tunable: Boolean,
    val changedFields: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

object LiveTuneOutcomeIO {

    private const val RECORD_SEP = ""

    fun write(file: File, outcome: LiveTuneOutcome) {
        file.parentFile.mkdirs()
        val sb = StringBuilder()
        sb.append(outcome.tunable).append('\n')
        sb.append(outcome.changedFields.joinToString(RECORD_SEP)).append('\n')
        sb.append(outcome.warnings.joinToString(RECORD_SEP))
        file.writeText(sb.toString())
    }

    /**
     * Falls back to "not tunable" if the file is missing or unreadable -- same reasoning as
     * [TierOutcomeIO.read]: a missing result must never silently claim Tier L when it isn't sure.
     */
    fun read(file: File): LiveTuneOutcome {
        if (!file.isFile) return LiveTuneOutcome(tunable = false)
        return try {
            val lines = file.readText().split("\n", limit = 3)
            LiveTuneOutcome(
                tunable = lines.getOrElse(0) { "false" }.toBoolean(),
                changedFields = lines.getOrElse(1) { "" }.split(RECORD_SEP).filter { it.isNotEmpty() },
                warnings = lines.getOrElse(2) { "" }.split(RECORD_SEP).filter { it.isNotEmpty() },
            )
        } catch (e: Exception) {
            LiveTuneOutcome(tunable = false)
        }
    }
}
