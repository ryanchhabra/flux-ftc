package dev.ryanchhab.flux.gradle.tasks

import dev.ryanchhab.flux.gradle.FluxTimingService
import dev.ryanchhab.flux.gradle.AdbConnection
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject
import org.gradle.work.DisableCachingByDefault

/**
 * `fluxDeploy` — CONTRACT.md's user-facing lifecycle task: "all of the above, with stage
 * timings". The only command a zero-config user ever needs to type (architecture.md §9).
 *
 * The tier decision (architecture.md §4) is made once, at configuration time, in FluxPlugin —
 * before fluxAssemble/fluxDex/fluxPush/fluxReload are even given a chance to run, via `onlyIf`
 * guards wired there. This task's own action just reports the outcome:
 *  - **Blocked**: throw with the exact culprit file and the fix, before anything was touched.
 *  - **No-op**: nothing changed since the last deploy, say so, skip the timing table.
 *  - **Hot**: print the stage timing table (architecture.md §5) — instrumentation is a core
 *    feature here, not decoration.
 */
@DisableCachingByDefault(because = "Pure reporting task: it prints the timing table for work its dependencies did, and must run every invocation.")
abstract class FluxDeploy : DefaultTask() {

    @get:Internal
    abstract val timingService: Property<FluxTimingService>

    @get:Input
    abstract val robotAddress: Property<String>

    @get:Internal
    abstract val adbPath: Property<String>

    @get:Inject
    abstract val exec: ExecOperations

    @get:Input
    abstract val tierBlocked: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val tierBlockedReason: Property<String>

    @get:Input
    @get:Optional
    abstract val tierBlockedDetail: Property<String>

    @get:Input
    @get:Optional
    abstract val tierBlockedCulprit: Property<String>

    @get:Input
    @get:Optional
    abstract val tierBlockedFix: Property<String>

    @get:Input
    abstract val tierNoOp: Property<Boolean>


    @get:Input
    @get:Optional
    abstract val buildId: Property<String>

    /** Tier L (live-tuning.md §3) — true when the only change was eligible literal values. */
    @get:Input
    abstract val liveTuneEligible: Property<Boolean>

    /** Precomputed "Class.field=value, ..." for the report -- see FluxPlugin's wiring. */
    @get:Input
    @get:Optional
    abstract val liveTuneFieldsSummary: Property<String>

    init {
        // fluxDeploy is a lifecycle task: it never has file inputs/outputs of its own, only
        // reports on the tasks it depends on, so re-running it every invocation is correct.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun report() {
        if (tierBlocked.getOrElse(false)) {
            throw GradleException(
                buildString {
                    appendLine("Flux cannot hot-deploy this change.")
                    appendLine("  Reason: ${tierBlockedReason.getOrElse("unknown")}")
                    appendLine("  File:   ${tierBlockedCulprit.getOrElse("unknown")}")
                    appendLine("  Fix:    ${tierBlockedFix.getOrElse("./gradlew installDebug")}")
                    val detail = tierBlockedDetail.getOrElse("")
                    if (detail.isNotBlank()) {
                        appendLine()
                        // Wrapped so the explanation is readable in a terminal rather than one
                        // long line -- a blocked deploy is exactly when the user needs to
                        // understand *why*, not just that it failed.
                        detail.chunkedWords(76).forEach { appendLine("  $it") }
                    }
                    append("")
                },
            )
        }

        if (tierNoOp.getOrElse(false)) {
            logger.lifecycle("Flux: nothing changed since the last deploy — skipping (tier 0).")
            return
        }

        val isLiveTune = liveTuneEligible.getOrElse(false)
        val tierLabel = if (isLiveTune) "tier L · live" else "tier 1 · hot"

        val snapshot = timingService.orNull?.snapshot() ?: emptyMap()

        // The device adb is actually talking to, not the configured robotAddress. Those are
        // different things far more often than not: this line used to print the Control Hub
        // Wi-Fi default while the deploy went to an emulator over USB, stating a fabricated fact
        // on every single deploy. Falls back to the configured address only when adb cannot say.
        val device = adbPath.orNull?.let { AdbConnection.currentDeviceSerial(exec, it) }
            ?: robotAddress.getOrElse("?")
        val header = "FTC Flux  ●  $device  ·  $tierLabel"
        val stageNames = if (isLiveTune) {
            listOf(FluxTimingService.STAGE_LIVE_TUNE)
        } else {
            listOf(
                FluxTimingService.STAGE_COMPILE,
                "Generate",
                FluxTimingService.STAGE_DEX,
                FluxTimingService.STAGE_TRANSFER,
                FluxTimingService.STAGE_RELOAD,
            )
        }
        val rows = stageNames.mapNotNull { stage -> snapshot[stage]?.let { stage to it } }

        val total = rows.sumOf { it.second.elapsedMs }

        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine(header)
        sb.appendLine()
        // Tier L skips compile/dex/push/reload entirely (live-tuning.md §2) — showing exactly
        // which fields were set is the point of this report; a bare "Live tune  12 ms" tells the
        // user nothing they didn't already know when they hit save.
        if (isLiveTune) {
            val fieldsLine = liveTuneFieldsSummary.getOrElse("")
            if (fieldsLine.isNotBlank()) {
                sb.appendLine("  Fields set: $fieldsLine")
                sb.appendLine()
            }
        }
        for ((stage, s) in rows) {
            val detail = s.detail?.let { " ($it)" } ?: ""
            sb.appendLine("  %-14s %5d ms%s".format(stage, s.elapsedMs, detail))
        }
        sb.appendLine("  " + "─".repeat(28))
        val buildIdSuffix = buildId.orNull?.let { " · BUILD_ID $it" } ?: ""
        sb.appendLine("  Total          %5d ms$buildIdSuffix".format(total))

        logger.lifecycle(sb.toString())
    }
}

/** Greedy word-wrap, so a blocked-deploy explanation reads properly in a terminal. */
private fun String.chunkedWords(width: Int): List<String> {
    val out = mutableListOf<String>()
    val line = StringBuilder()
    for (word in split(Regex("\\s+")).filter { it.isNotEmpty() }) {
        if (line.isNotEmpty() && line.length + 1 + word.length > width) {
            out += line.toString()
            line.setLength(0)
        }
        if (line.isNotEmpty()) line.append(' ')
        line.append(word)
    }
    if (line.isNotEmpty()) out += line.toString()
    return out
}
