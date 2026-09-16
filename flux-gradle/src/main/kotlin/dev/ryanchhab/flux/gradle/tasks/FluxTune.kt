package dev.ryanchhab.flux.gradle.tasks

import dev.ryanchhab.flux.gradle.FluxTimingService
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import dev.ryanchhab.flux.gradle.RobotReachability
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * `fluxTune` — CONTRACT.md Amendment 3: writes the `live_values.json` payload, `adb push`es it to
 * `/sdcard/FIRST/flux/live_values.json`, then `adb shell am broadcast -a dev.ryanchhab.flux.LIVE_TUNE` and
 * parses `result=N`.
 *
 * This is Tier L (live-tuning.md §3) — the whole point is to skip compile/dex/reload entirely, so
 * this task does not depend on `fluxAssemble`'s class output, `fluxDex`, or `fluxPush`. It only
 * needs the changed-field list [dev.ryanchhab.flux.gradle.LiveTuneDetector] already produced and persisted
 * during `fluxAssemble` (see [dev.ryanchhab.flux.gradle.LiveTuneOutcome]'s class doc — classify once,
 * re-read many times, same pattern as [dev.ryanchhab.flux.gradle.TierOutcome]).
 *
 * Reuses [dev.ryanchhab.flux.gradle.AdbLocator] (via the `adbPath` input, resolved once in FluxPlugin) and
 * mirrors FluxPush/FluxReload's `ExecOperations` + `result=N` parsing shape — see those classes'
 * docs for why `ExecOperations` (injected) is used instead of `project.exec` (unavailable to task
 * actions under Gradle 9's configuration cache).
 *
 * Result codes reuse the reload set (CONTRACT.md Amendment 3):
 *  - `1` applied — every field was set.
 *  - `2` nothing applied (clean) — robot rejected the whole batch, still on old values, safe.
 *  - `3` **partially applied** — some fields landed, some didn't, and the robot is now in a
 *    genuinely mixed state that this tool cannot see into. Said loudly, not glossed over.
 */
@DisableCachingByDefault(because = "Writes field values to a running OpMode on the device; the effect is entirely outside the build.")
abstract class FluxTune : DefaultTask() {

    @get:Inject
    abstract val exec: ExecOperations

    @get:Input
    abstract val adbPath: Property<String>

    @get:Input
    abstract val deployLocation: Property<String>

    /** One entry per changed field, each "classfieldtypevalue" — see FluxPlugin. */
    @get:Input
    abstract val changedFieldsEncoded: ListProperty<String>

    @get:Internal
    abstract val timingService: Property<FluxTimingService>

    @TaskAction
    fun tune() {
        val start = System.nanoTime()

        val fields = changedFieldsEncoded.get().mapNotNull {
            val parts = it.split("")
            if (parts.size == 4) Field(parts[0], parts[1], parts[2], parts[3]) else null
        }
        if (fields.isEmpty()) {
            throw GradleException(
                "Flux: fluxTune ran with no changed live-tunable fields to send.\n" +
                    "  `fluxDeploy` only routes into fluxTune when the live-tune detector found " +
                    "eligible changes -- if you ran `fluxTune` directly, run `fluxDeploy` instead " +
                    "so the right tier is picked automatically.",
            )
        }

        val adb = adbPath.get()
        val dir = deployLocation.get()
        val remotePath = "$dir/live_values.json"

        val localFile = File.createTempFile("flux-live-values", ".json")
        try {
            localFile.writeText(buildJson(fields))

            run(adb, "shell", "mkdir", "-p", dir)

            val pushResult = runCapturing(adb, "push", localFile.absolutePath, remotePath)
            if (pushResult.exitCode != 0) {
                throw GradleException(
                    "Flux: adb push of live_values.json failed (exit ${pushResult.exitCode}).\n" +
                        "  adb output: ${pushResult.output.trim()}\n" +
                        "  Fix: confirm the robot is connected (`./gradlew fluxDoctor`) and that " +
                        "\"$dir\" is writable.",
                )
            }

            val broadcastOut = ByteArrayOutputStream()
            val broadcastResult = exec.exec {
                commandLine = listOf(adb, "shell", "am", "broadcast", "-a", "dev.ryanchhab.flux.LIVE_TUNE")
                standardOutput = broadcastOut
                errorOutput = broadcastOut
                isIgnoreExitValue = true
            }
            val stdout = broadcastOut.toString()
            val code = Regex("result=(-?\\d+)").find(stdout)?.groupValues?.get(1)?.toIntOrNull()

            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            timingService.orNull?.record(
                FluxTimingService.STAGE_LIVE_TUNE,
                elapsedMs,
                "${fields.size} field${if (fields.size == 1) "" else "s"}",
            )

            if (broadcastResult.exitValue != 0) {
                throw GradleException(
                    "Flux: adb shell am broadcast (LIVE_TUNE) failed (exit ${broadcastResult.exitValue}).\n" +
                        "  adb output: ${stdout.trim()}\n" +
                        "  Fix: confirm a device is connected (`./gradlew fluxDoctor`).",
                )
            }

            val fieldList = fields.joinToString(", ") { "${simpleName(it.className)}.${it.fieldName}=${it.value}" }

            when (code) {
                1 -> logger.lifecycle("Flux: live tune SUCCESS -- $fieldList")

                2 -> throw GradleException(
                    "Flux: live tune FAILED_CLEAN (result=2).\n" +
                        "  Nothing was applied -- the robot rejected the whole batch and is still " +
                        "running the previous values. Safe to keep driving.\n" +
                        "  Fields attempted: $fieldList\n" +
                        "  Fix: check the Driver Station / Robot Controller log for the rejection " +
                        "reason, then retry `./gradlew fluxDeploy`.",
                )

                3 -> throw GradleException(
                    "Flux: live tune FAILED_DIRTY (result=3) -- PARTIALLY APPLIED.\n" +
                        "  Some of these fields were set on the robot and some weren't, and this " +
                        "tool cannot tell you which from here -- the robot is in a MIXED state.\n" +
                        "  Fields attempted: $fieldList\n" +
                        "  Fix: treat every one of these values as unknown on the robot right now. " +
                        "Re-run `./gradlew fluxDeploy` to reconcile (it will re-send all of them), " +
                        "or restart the Robot Controller app if you need a clean baseline.",
                )

                else -> throw GradleException(
                    RobotReachability.explain(exec, adb, "dev.ryanchhab.flux.LIVE_TUNE", stdout),
                )
            }
        } finally {
            localFile.delete()
        }
    }

    private fun simpleName(className: String): String = className.substringAfterLast('.')

    private data class Field(val className: String, val fieldName: String, val type: String, val value: String)

    private fun buildJson(fields: List<Field>): String {
        val sb = StringBuilder()
        sb.append('[')
        fields.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"class\":\"").append(jsonEscape(f.className)).append("\",")
            sb.append("\"field\":\"").append(jsonEscape(f.fieldName)).append("\",")
            sb.append("\"type\":\"").append(jsonEscape(f.type)).append("\",")
            sb.append("\"value\":\"").append(jsonEscape(f.value)).append('"')
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /** No JSON library dependency for a payload this trivial (CONTRACT.md calls this out explicitly). */
    private fun jsonEscape(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun run(adb: String, vararg args: String) {
        val result = runCapturing(adb, *args)
        if (result.exitCode != 0) {
            logger.debug("Flux: adb ${args.joinToString(" ")} exited ${result.exitCode}: ${result.output}")
        }
    }

    private fun runCapturing(adb: String, vararg args: String): ProcessResult {
        val out = ByteArrayOutputStream()
        val execResult = exec.exec {
            commandLine = listOf(adb) + args
            standardOutput = out
            errorOutput = out
            isIgnoreExitValue = true
        }
        return ProcessResult(execResult.exitValue, out.toString())
    }

    private data class ProcessResult(val exitCode: Int, val output: String)
}
