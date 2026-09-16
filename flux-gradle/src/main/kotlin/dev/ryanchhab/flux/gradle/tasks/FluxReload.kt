package dev.ryanchhab.flux.gradle.tasks

import dev.ryanchhab.flux.gradle.FluxTimingService
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import dev.ryanchhab.flux.gradle.TierDetector
import dev.ryanchhab.flux.gradle.LiveTuneDetector
import dev.ryanchhab.flux.gradle.RobotReachability
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

    /**
     * Tier state, so a failed reload can clear the recorded BUILD_ID. Tier classification writes
     * that state before anything reaches the robot, so without this a failed deploy leaves the
     * baseline claiming success and the retry skips as Tier 0.
     */
    @get:Internal
    abstract val tierStateDir: DirectoryProperty

    @get:Internal
    abstract val liveTuneStateDir: DirectoryProperty

    /**
     * The team's `flux { safetyPolicy = ... }`, sent with the broadcast. The robot decides; the
     * plugin only reports what came back. Sending it on every reload rather than configuring the
     * robot once means the setting takes effect the moment it is edited, with no install.
     */
    @get:Input
    abstract val safetyPolicy: Property<String>

    @TaskAction
    fun reload() {
        val start = System.nanoTime()
        val adb = adbPath.get()

        val out = ByteArrayOutputStream()
        val result = exec.exec {
            // --es buildId: the runtime already knew how to verify this (FluxReloadEngine compares
            // it against the BUILD_ID it reads back out of the freshly loaded bundle), but the
            // Gradle side never sent it, so the check silently degraded to "some BUILD_ID was
            // readable" rather than "the one I just pushed is live". A push that failed or landed
            // stale would have reported success.
            commandLine = listOf(
                adb, "shell", "am", "broadcast",
                "-a", "dev.ryanchhab.flux.RELOAD",
                "--es", "buildId", buildId.get(),
                "--es", "safetyPolicy", safetyPolicy.getOrElse("REJECT"),
            )
            standardOutput = out
            errorOutput = out
            isIgnoreExitValue = true
        }
        val stdout = out.toString()
        val code = Regex("result=(-?\\d+)").find(stdout)?.groupValues?.get(1)?.toIntOrNull()

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        timingService.orNull?.record(FluxTimingService.STAGE_RELOAD, elapsedMs)

        // The baseline advances here and nowhere earlier. Classification runs before anything is
        // pushed, so only a confirmed result=1 makes "the robot is running this build" true. Any
        // other outcome simply leaves the pending fingerprint uncommitted, and the next deploy
        // correctly sees the change as still outstanding.
        if (code == 1) {
            tierStateDir.orNull?.asFile?.let { TierDetector.commitPending(it) }
            // A hot reload ships the new constant values too, so the live-tune snapshot is now
            // accurate as well; leaving it behind would make the next deploy re-send them.
            liveTuneStateDir.orNull?.asFile?.let { LiveTuneDetector.commitPending(it) }
        }

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
                    "  The robot refused the reload and is still running the previous code — " +
                    "safe to keep driving.\n" +
                    (
                        if (safetyPolicy.getOrElse("REJECT").equals("FORCE", ignoreCase = true)) {
                            "  Most likely: an OpMode is running and did not stop in time " +
                                "(safetyPolicy=FORCE asked it to). An OpMode that will not stop " +
                                "is usually stuck in its own loop.\n" +
                                "  Fix: stop it on the Driver Station, then deploy again.\n"
                        } else {
                            "  Most likely: an OpMode is running and safetyPolicy is REJECT.\n" +
                                "  Fix: stop the OpMode on the Driver Station, then deploy again. " +
                                "To stop it automatically instead, set " +
                                "`flux { safetyPolicy = dev.ryanchhab.flux.gradle.SafetyPolicy.FORCE }`.\n"
                        }
                        ) +
                    "  Flux will not swap TeamCode classes out from under a running OpMode: it " +
                    "keeps the classes it already loaded, so the two generations would be " +
                    "different types sharing a name.\n" +
                    "  Other causes are logged on the robot: `adb logcat -s FLUX`.",
            )

            3 -> throw GradleException(
                "Flux: reload FAILED_DIRTY (result=3).\n" +
                    "  The reload failed midway and the robot's code state is UNKNOWN — do not " +
                    "trust it to drive safely.\n" +
                    "  Fix: restart the Robot Controller app now, then retry `./gradlew fluxDeploy`.",
            )

            else -> throw GradleException(
                RobotReachability.explain(exec, adb, "dev.ryanchhab.flux.RELOAD", stdout),
            )
        }
    }
}
