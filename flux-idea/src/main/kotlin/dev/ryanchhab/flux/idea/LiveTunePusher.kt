package dev.ryanchhab.flux.idea

import com.intellij.openapi.diagnostic.logger
import java.io.File

/**
 * Pushes a single `@FluxLive` field value straight to the robot -- `adb push live_values.json` +
 * `adb shell am broadcast -a dev.ryanchhab.flux.LIVE_TUNE` (CONTRACT.md Amendment 3), with no
 * Gradle in the loop.
 *
 * Why not the `fluxTune` Gradle task: measured directly against the API 25 emulator used to
 * verify this feature, `./gradlew fluxTune`/`fluxDeploy` costs 600-800ms wall time per invocation
 * (JVM + Gradle daemon handshake dominate; the task's own reported time is ~150-200ms). The raw
 * two-adb-process path measured 125-160ms round-trip end to end (push + broadcast, confirmed via
 * `adb logcat` showing `FLUX: live set ...`), which is what this class does instead.
 *
 * One JSON object per call (this always pushes the single field that changed -- CONTRACT.md's
 * payload is a flat array, and a one-element array is exactly as valid as a multi-element one).
 * Blocking: callers must already be off the EDT.
 */
object LiveTunePusher {

    private val LOG = logger<LiveTunePusher>()

    /** Fixed by CONTRACT.md Amendment 3 -- not configurable, matches flux-gradle's FluxExtension default. */
    const val DEPLOY_LOCATION = "/sdcard/FIRST/flux"

    data class Field(val fqClassName: String, val fieldName: String, val type: String, val value: String)

    sealed class Result {
        object Applied : Result()
        data class Rejected(val code: Int?, val message: String) : Result()
        data class Failed(val message: String) : Result()
    }

    /** Synchronous -- call from a background thread only. */
    fun push(adb: File, field: Field): Result {
        val localFile = try {
            File.createTempFile("flux-live-values", ".json")
        } catch (e: Exception) {
            return Result.Failed("couldn't create temp payload file: ${e.message}")
        }
        try {
            localFile.writeText(buildJson(field))

            val pushExit = runBlocking(adb.absolutePath, "push", localFile.absolutePath, "$DEPLOY_LOCATION/live_values.json")
            if (pushExit.exitCode != 0) {
                return Result.Failed("adb push failed (exit ${pushExit.exitCode}): ${pushExit.output.trim()}")
            }

            val broadcast = runBlocking(adb.absolutePath, "shell", "am", "broadcast", "-a", "dev.ryanchhab.flux.LIVE_TUNE")
            val code = Regex("result=(-?\\d+)").find(broadcast.output)?.groupValues?.get(1)?.toIntOrNull()
            return when (code) {
                1 -> Result.Applied
                2 -> Result.Rejected(2, "robot rejected the value (still on the old one)")
                3 -> Result.Rejected(3, "PARTIAL -- robot state is now mixed")
                else -> Result.Failed("no result code from robot: ${broadcast.output.trim()}")
            }
        } finally {
            localFile.delete()
        }
    }

    private fun buildJson(f: Field): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "[{\"class\":\"${esc(f.fqClassName)}\",\"field\":\"${esc(f.fieldName)}\"," +
            "\"type\":\"${esc(f.type)}\",\"value\":\"${esc(f.value)}\"}]"
    }

    private data class ProcRes(val exitCode: Int, val output: String)

    private fun runBlocking(vararg command: String): ProcRes {
        return try {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            ProcRes(exit, output)
        } catch (e: Exception) {
            LOG.info("Flux live-tune push failed: ${command.joinToString(" ")}", e)
            ProcRes(-1, e.message ?: "unknown error")
        }
    }
}
