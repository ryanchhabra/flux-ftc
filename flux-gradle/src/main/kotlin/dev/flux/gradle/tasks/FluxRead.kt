package dev.flux.gradle.tasks

import dev.flux.gradle.LiveTuneDetector
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * `fluxRead` — CONTRACT.md Amendment 4: live-value read-back, the mirror image of `fluxTune`.
 *
 * Collects the current `@FluxLive` class/field set straight from the TeamCode source tree (reusing
 * [LiveTuneDetector.currentFields] — the same per-file scan `fluxTune`'s tier detection already
 * does, just without the diffing-baseline comparison, since this task always wants the *whole*
 * current set, not just what changed), pushes a small JSON request naming those classes, broadcasts
 * `dev.flux.LIVE_READ`, pulls back the robot's answer, and prints a table of source value vs. robot
 * value for every field — flagging any that differ.
 *
 * That diff is the actual point of this task: telling the user "the robot is running kP=0.042 but
 * your source says 0.012" without them having to `adb shell cat` a JSON file by hand. Deliberately
 * standalone (no dependency on `fluxAssemble`/tier classification) — read-back is a query, not a
 * deploy, and the source scan it needs is cheap enough to just do inline.
 */
abstract class FluxRead : DefaultTask() {

    @get:Inject
    abstract val exec: ExecOperations

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    abstract val teamCodeSourceDirs: ConfigurableFileCollection

    @get:Input
    abstract val adbPath: Property<String>

    @get:Input
    abstract val deployLocation: Property<String>

    private data class Field(val className: String, val fieldName: String, val type: String, val value: String)

    @TaskAction
    fun read() {
        val sourceFields = LiveTuneDetector.currentFields(teamCodeSourceDirs.files.toList())
        if (sourceFields.isEmpty()) {
            logger.lifecycle(
                "Flux: no eligible @FluxLive fields found in TeamCode sources -- nothing to read back.",
            )
            return
        }
        val classNames = sourceFields.map { it.className }.distinct().sorted()

        val adb = adbPath.get()
        val dir = deployLocation.get()
        val requestRemote = "$dir/live_read_request.json"
        val resultRemote = "$dir/live_values_current.json"

        val localRequest = File.createTempFile("flux-live-read-request", ".json")
        val localResult = File.createTempFile("flux-live-values-current", ".json")
        try {
            localRequest.writeText(buildRequestJson(classNames))

            run(adb, "shell", "mkdir", "-p", dir)

            val pushResult = runCapturing(adb, "push", localRequest.absolutePath, requestRemote)
            if (pushResult.exitCode != 0) {
                throw GradleException(
                    "Flux: adb push of live_read_request.json failed (exit ${pushResult.exitCode}).\n" +
                        "  adb output: ${pushResult.output.trim()}\n" +
                        "  Fix: confirm the robot is connected (`./gradlew fluxDoctor`) and that " +
                        "\"$dir\" is writable.",
                )
            }

            // Clear any stale result first so a failed read can't be mistaken for a leftover from
            // a previous run.
            run(adb, "shell", "rm", "-f", resultRemote)

            val broadcastOut = ByteArrayOutputStream()
            val broadcastResult = exec.exec {
                commandLine = listOf(adb, "shell", "am", "broadcast", "-a", "dev.flux.LIVE_READ")
                standardOutput = broadcastOut
                errorOutput = broadcastOut
                isIgnoreExitValue = true
            }
            val stdout = broadcastOut.toString()
            val code = Regex("result=(-?\\d+)").find(stdout)?.groupValues?.get(1)?.toIntOrNull()

            if (broadcastResult.exitValue != 0) {
                throw GradleException(
                    "Flux: adb shell am broadcast (LIVE_READ) failed (exit ${broadcastResult.exitValue}).\n" +
                        "  adb output: ${stdout.trim()}\n" +
                        "  Fix: confirm a device is connected (`./gradlew fluxDoctor`).",
                )
            }

            if (code == null) {
                throw GradleException(
                    "Flux: no result code from the robot (raw adb output: \"${stdout.trim()}\").\n" +
                        "  This almost always means either the Flux runtime isn't installed in the " +
                        "running Robot Controller app, or it doesn't yet register a receiver for " +
                        "dev.flux.LIVE_READ (CONTRACT.md Amendment 4).\n" +
                        "  Fix: run `./gradlew fluxDoctor` to confirm the runtime is present; if it " +
                        "is and this still happens, the LIVE_READ receiver isn't wired up on the " +
                        "robot side yet.",
                )
            }

            val pullResult = runCapturing(adb, "pull", resultRemote, localResult.absolutePath)
            val robotFields = if (pullResult.exitCode == 0 && localResult.isFile) {
                parseFields(localResult.readText())
            } else {
                emptyList()
            }

            printTable(sourceFields, robotFields)

            when (code) {
                1 -> logger.lifecycle("Flux: read-back SUCCESS -- robot reported current values for every requested class.")

                2 -> logger.lifecycle(
                    "Flux: read-back reported NOTHING READ (result=2) -- none of the requested " +
                        "@FluxLive classes could be resolved on the robot (the app may not have " +
                        "loaded them yet, or was restarted since the last install/reload). The " +
                        "table above shows source values only.",
                )

                3 -> logger.lifecycle(
                    "Flux: read-back PARTIAL (result=3) -- some @FluxLive classes could not be " +
                        "resolved on the robot; see the blank robot values above.",
                )

                else -> throw GradleException("Flux: unexpected result code $code from dev.flux.LIVE_READ.")
            }
        } finally {
            localRequest.delete()
            localResult.delete()
        }
    }

    private fun printTable(sourceFields: List<LiveTuneDetector.LiveField>, robotFields: List<Field>) {
        val robotByKey = robotFields.associateBy { "${it.className}#${it.fieldName}" }

        val rows = sourceFields.map { f ->
            val key = "${f.className}#${f.fieldName}"
            val robot = robotByKey[key]
            val label = "${simpleName(f.className)}.${f.fieldName}"
            val robotValue = robot?.value ?: "(not read)"
            val differs = robot != null && robot.value != f.value
            Triple(label, f.value, robotValue) to differs
        }

        val labelWidth = maxOf(rows.maxOf { it.first.first.length }, "FIELD".length)
        val sourceWidth = maxOf(rows.maxOf { it.first.second.length }, "SOURCE".length)

        logger.lifecycle("")
        logger.lifecycle("Flux live-value read-back:")
        logger.lifecycle("  " + "FIELD".padEnd(labelWidth) + "  " + "SOURCE".padEnd(sourceWidth) + "  ROBOT")
        for ((row, differs) in rows) {
            val (label, sourceValue, robotValue) = row
            val marker = if (differs) "  <- DIFFERS" else ""
            logger.lifecycle(
                "  " + label.padEnd(labelWidth) + "  " + sourceValue.padEnd(sourceWidth) + "  " + robotValue + marker,
            )
        }
        logger.lifecycle("")

        val diffCount = rows.count { it.second }
        if (diffCount > 0) {
            logger.lifecycle(
                "Flux: $diffCount field(s) differ between source and the running robot -- the robot " +
                    "is not running what your source file currently says.",
            )
        }
    }

    private fun simpleName(className: String): String = className.substringAfterLast('.')

    private fun buildRequestJson(classNames: List<String>): String {
        val sb = StringBuilder()
        sb.append('[')
        classNames.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append('"').append(jsonEscape(c)).append('"')
        }
        sb.append(']')
        return sb.toString()
    }

    /** Hand-rolled, same rationale as [FluxTune]: the payload shape is fixed and trivial. */
    private fun parseFields(json: String): List<Field> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyList()

        val fields = mutableListOf<Field>()
        var i = 0

        fun skipWs() {
            while (i < trimmed.length && trimmed[i].isWhitespace()) i++
        }

        fun expect(c: Char) {
            if (i >= trimmed.length || trimmed[i] != c) {
                throw GradleException("Flux: malformed live_values_current.json near offset $i (expected '$c')")
            }
            i++
        }

        fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < trimmed.length && trimmed[i] != '"') {
                val c = trimmed[i]
                if (c == '\\' && i + 1 < trimmed.length) {
                    i++
                    when (val esc = trimmed[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'u' -> {
                            val hex = trimmed.substring(i + 1, i + 5)
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
                i++
            }
            expect('"')
            return sb.toString()
        }

        skipWs()
        expect('[')
        skipWs()
        if (i < trimmed.length && trimmed[i] == ']') return emptyList()

        while (true) {
            skipWs()
            expect('{')
            var className = ""
            var fieldName = ""
            var type = ""
            var value = ""
            skipWs()
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                skipWs()
                val v = parseString()
                when (key) {
                    "class" -> className = v
                    "field" -> fieldName = v
                    "type" -> type = v
                    "value" -> value = v
                }
                skipWs()
                if (i < trimmed.length && trimmed[i] == ',') {
                    i++
                    continue
                }
                break
            }
            skipWs()
            expect('}')
            fields += Field(className, fieldName, type, value)
            skipWs()
            if (i < trimmed.length && trimmed[i] == ',') {
                i++
                continue
            }
            break
        }
        skipWs()
        expect(']')
        return fields
    }

    /** No JSON library dependency for a payload this trivial (matches FluxTune). */
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
