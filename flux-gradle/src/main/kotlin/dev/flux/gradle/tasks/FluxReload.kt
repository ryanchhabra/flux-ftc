package dev.flux.gradle.tasks

import dev.flux.gradle.FluxTimingService
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * `fluxReload` — CONTRACT.md: "`adb shell am broadcast`, parse result code".
 *
 * Three distinct, actionable outcomes (risks.md §6 — Fast Load conflates all of these):
 *  - `1` SUCCESS         — new code live and verified.
 *  - `2` FAILED_CLEAN    — reload aborted, robot still safely on the previous code.
 *  - `3` FAILED_DIRTY    — failed mid-swap, robot state unknown. **Must tell the user to restart
 *                           the Robot Controller app** (CONTRACT.md).
 *  - anything else / unparseable — the flux-runtime receiver never responded, which means the
 *    runtime isn't installed in the running RC app at all (as opposed to running and rejecting).
 *    Said explicitly rather than treated as a generic failure.
 */
@DisableCachingByDefault(because = "Triggers a reload on a live device; the result depends on robot state Gradle cannot observe.")
abstract class FluxReload : DefaultTask() {

    @get:Inject
    abstract val exec: ExecOperations

    @get:Input
    abstract val adbPath: Property<String>

    @get:Input
    abstract val buildId: Property<String>

    @get:Internal
    abstract val buildIdFile: RegularFileProperty

    @get:Internal
    abstract val timingService: Property<FluxTimingService>

    @TaskAction
    fun reload() {
        val start = System.nanoTime()
        val adb = adbPath.get()

        val out = ByteArrayOutputStream()
        val result = exec.exec {
            commandLine = listOf(adb, "shell", "am", "broadcast", "-a", "dev.flux.RELOAD")
            standardOutput = out
            errorOutput = out
            isIgnoreExitValue = true
        }
        val stdout = out.toString()
        val code = Regex("result=(-?\\d+)").find(stdout)?.groupValues?.get(1)?.toIntOrNull()

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        timingService.orNull?.record(FluxTimingService.STAGE_RELOAD, elapsedMs)

        if (result.exitValue != 0) {
            throw GradleException(
                "Flux: adb shell am broadcast failed (exit ${result.exitValue}).\n" +
                    "  adb output: ${stdout.trim()}\n" +
                    "  Fix: confirm a device is connected (`./gradlew fluxDoctor`).",
            )
        }

        when (code) {
            1 -> logger.lifecycle(
                "Flux: reload SUCCESS — new code live (BUILD_ID=${buildId.get()}).",
            )

            2 -> throw GradleException(
                "Flux: reload FAILED_CLEAN (result=2).\n" +
                    "  The robot rejected the reload and is still running the previous code — " +
                    "safe to keep driving.\n" +
                    "  Fix: check the Driver Station / Robot Controller log for the rejection " +
                    "reason (often an OpMode was running under safetyPolicy=REJECT), then retry " +
                    "`./gradlew fluxDeploy`.",
            )

            3 -> throw GradleException(
                "Flux: reload FAILED_DIRTY (result=3).\n" +
                    "  The reload failed midway and the robot's code state is UNKNOWN — do not " +
                    "trust it to drive safely.\n" +
                    "  Fix: restart the Robot Controller app now, then retry `./gradlew fluxDeploy`.",
            )

            else -> throw GradleException(
                "Flux: no result code from the robot (raw adb output: \"${stdout.trim()}\").\n" +
                    "  This almost always means the Flux runtime isn't installed in the running " +
                    "Robot Controller app (as opposed to being installed and rejecting the reload).\n" +
                    "  Fix: run `./gradlew installDebug` once to install an APK containing " +
                    "flux-runtime, then retry `./gradlew fluxDeploy`. Run `./gradlew fluxDoctor` " +
                    "to confirm.",
            )
        }
    }
}
