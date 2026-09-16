package dev.flux.gradle

import java.io.File
import java.util.Properties

/**
 * Plain-old-data mirror of [TierDetector.Tier], persisted to disk so it can cross a task
 * boundary safely.
 *
 * Classification happens exactly once, inside `fluxAssemble` (the one task that always runs
 * regardless of tier — see FluxAssemble's doc), because [TierDetector.classify] has a side
 * effect: it persists the new "last known good" fingerprint on a non-blocked outcome. Reading
 * this file back from `fluxDex`/`fluxPush`/`fluxReload`'s `onlyIf` checks and from `fluxDeploy`'s
 * reporting is a pure, repeatable read with no re-classification risk.
 */
data class TierOutcome(
    val blocked: Boolean,
    val blockedReason: String? = null,
    val blockedCulprit: String? = null,
    val blockedFix: String? = null,
    val blockedDetail: String? = null,
    val noOp: Boolean = false,
    val hardwareChangeSuspected: Boolean = false,
    val buildId: String = "",
)

object TierOutcomeIO {

    fun write(file: File, outcome: TierOutcome) {
        file.parentFile.mkdirs()
        val props = Properties()
        props.setProperty("blocked", outcome.blocked.toString())
        outcome.blockedReason?.let { props.setProperty("blockedReason", it) }
        outcome.blockedCulprit?.let { props.setProperty("blockedCulprit", it) }
        outcome.blockedFix?.let { props.setProperty("blockedFix", it) }
        outcome.blockedDetail?.let { props.setProperty("blockedDetail", it) }
        props.setProperty("noOp", outcome.noOp.toString())
        props.setProperty("hardwareChangeSuspected", outcome.hardwareChangeSuspected.toString())
        props.setProperty("buildId", outcome.buildId)
        file.outputStream().use { props.store(it, "Flux tier-classification result for this build — do not edit by hand") }
    }

    /**
     * Falls back to "not blocked, not a no-op" (i.e. run the full pipeline) if the file is
     * missing or unreadable — a missing tier result should never silently block a deploy or
     * silently skip it as a false no-op; the safe failure mode is "just do the work".
     */
    fun read(file: File): TierOutcome {
        if (!file.isFile) return TierOutcome(blocked = false)
        return try {
            val props = Properties().apply { file.inputStream().use { load(it) } }
            TierOutcome(
                blocked = props.getProperty("blocked")?.toBoolean() ?: false,
                blockedReason = props.getProperty("blockedReason"),
                blockedCulprit = props.getProperty("blockedCulprit"),
                blockedFix = props.getProperty("blockedFix"),
                blockedDetail = props.getProperty("blockedDetail"),
                noOp = props.getProperty("noOp")?.toBoolean() ?: false,
                hardwareChangeSuspected = props.getProperty("hardwareChangeSuspected")?.toBoolean() ?: false,
                buildId = props.getProperty("buildId") ?: "",
            )
        } catch (e: Exception) {
            TierOutcome(blocked = false)
        }
    }
}
