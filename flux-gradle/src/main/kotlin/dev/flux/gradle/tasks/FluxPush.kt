package dev.flux.gradle.tasks

import dev.flux.gradle.FluxTimingService
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * `fluxPush` — CONTRACT.md: "adb push to the deploy dir".
 *
 * Before pushing the new bundle, best-effort renames the existing remote bundle to
 * `flux_bundle.last.jar` (CONTRACT.md "Previous-good bundle") so flux-runtime has a known-good
 * fallback to roll back to on a FAILED_DIRTY reload. A missing remote file (first-ever deploy) is
 * not an error.
 *
 * Uses the injected [ExecOperations] rather than `project.exec` — the latter is unavailable to
 * task actions under Gradle 9's configuration cache.
 */
abstract class FluxPush : DefaultTask() {

    @get:Inject
    abstract val exec: ExecOperations

    @get:InputFile
    abstract val bundleJar: RegularFileProperty

    @get:Input
    abstract val adbPath: Property<String>

    @get:Input
    abstract val deployLocation: Property<String>

    @get:Internal
    abstract val timingService: Property<FluxTimingService>

    @TaskAction
    fun push() {
        val start = System.nanoTime()
        val adb = adbPath.get()
        val dir = deployLocation.get()
        val bundleName = bundleJar.get().asFile.name
        val remoteBundle = "$dir/$bundleName"
        val remoteLast = "$dir/flux_bundle.last.jar"

        run(adb, "shell", "mkdir", "-p", dir)

        // Best-effort rollback snapshot; `test -e` + `mv` rather than failing the whole deploy if
        // there is nothing to rotate yet.
        run(
            adb, "shell",
            "if [ -f $remoteBundle ]; then mv $remoteBundle $remoteLast; fi",
        )

        val pushResult = runCapturing(adb, "push", bundleJar.get().asFile.absolutePath, remoteBundle)
        if (pushResult.exitCode != 0) {
            throw org.gradle.api.GradleException(
                "Flux: adb push failed (exit ${pushResult.exitCode}).\n" +
                    "  adb output: ${pushResult.output.trim()}\n" +
                    "  Fix: confirm the robot is connected (`./gradlew fluxDoctor`) and that " +
                    "\"$dir\" is writable.",
            )
        }

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val sizeKb = bundleJar.get().asFile.length() / 1024.0
        timingService.orNull?.record(
            FluxTimingService.STAGE_TRANSFER,
            elapsedMs,
            "%.1f KB".format(sizeKb),
        )
        logger.lifecycle("Flux: pushed $bundleName to $remoteBundle")
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
