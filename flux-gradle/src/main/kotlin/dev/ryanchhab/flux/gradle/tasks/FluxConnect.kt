package dev.ryanchhab.flux.gradle.tasks

import dev.ryanchhab.flux.gradle.AdbConnection
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/**
 * `fluxConnect` -- connects adb to the robot over Wi-Fi Direct. Explicit, never automatic.
 *
 * This was briefly wired to run before every deploy, which was a mistake worth recording: a
 * doomed `adb connect` takes 75 seconds to time out, so any team working over USB would have paid
 * 75 seconds on every single deploy to save themselves typing one command once per session.
 *
 * Because the user asked for this one, it is allowed to fail loudly, unlike the old automatic
 * version which had to stay silent.
 */
@DisableCachingByDefault(because = "Manages a live adb connection; its effect is on the environment, not on any output file.")
abstract class FluxConnect : DefaultTask() {

    @get:Inject
    abstract val exec: ExecOperations

    @get:Input
    @get:Optional
    abstract val adbPath: Property<String>

    @get:Input
    abstract val robotAddress: Property<String>


    init {
        group = "flux"
        description = "Connect adb to the robot over Wi-Fi (run once per session)."
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun connect() {
        val adb = adbPath.orNull ?: throw org.gradle.api.GradleException(
            "Flux: adb not found. Install Android SDK platform-tools, or set flux { adbPath = \"...\" }.",
        )
        val address = robotAddress.get()

        // Probe with a plain socket first. `adb connect` to an address with nothing on it blocks
        // for 75 seconds (measured), which is an unacceptable way to tell someone their robot is
        // off. A 2 second TCP probe answers the same question and fails fast.
        if (!AdbConnection.isReachable(address, PROBE_TIMEOUT_MS)) {
            throw org.gradle.api.GradleException(
                "Flux: nothing is listening at $address.\n" +
                    "  Check the robot is powered on and you are joined to its Wi-Fi Direct network.\n" +
                    "  If your robot uses a different address, set `flux { robotAddress = \"...\" }`.\n" +
                    "  Connected over USB instead? You do not need this task at all.",
            )
        }

        AdbConnection.connect(exec, adb, address, force = true) { logger.lifecycle(it) }
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 2000
    }
}
