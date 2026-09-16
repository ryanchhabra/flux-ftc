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
 * `fluxDisconnect` -- drops the adb connection to the robot. The counterpart to `fluxConnect`,
 * for when you want to go back to USB without adb preferring the (now stale) network device.
 */
@DisableCachingByDefault(because = "Manages a live adb connection; its effect is on the environment, not on any output file.")
abstract class FluxDisconnect : DefaultTask() {

    @get:Inject
    abstract val exec: ExecOperations

    @get:Input
    @get:Optional
    abstract val adbPath: Property<String>

    @get:Input
    abstract val robotAddress: Property<String>


    init {
        group = "flux"
        description = "Disconnect adb from the robot's Wi-Fi address."
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun disconnect() {
        val adb = adbPath.orNull ?: return
        AdbConnection.disconnect(exec, adb, robotAddress.get()) { logger.lifecycle(it) }
    }
}
