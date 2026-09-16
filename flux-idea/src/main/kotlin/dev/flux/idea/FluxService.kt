package dev.flux.idea

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import java.util.concurrent.CopyOnWriteArrayList

interface FluxListener {
    fun onAdbStatus(status: AdbStatus) {}
    fun onDeployOutput(text: String) {}
    fun onDeployResult(result: DeployResult) {}
    fun onDeployStarted() {}
}

/**
 * Project-level hub for the tool window and the "Flux Deploy" action: owns adb-status polling
 * and the currently-running (if any) deploy, so both UI entry points see the same state whether
 * or not the tool window is open when a deploy starts.
 *
 * Deliberately not more than this -- no persistence, no settings, no multi-project robot
 * tracking. The brief is explicit about keeping this tight.
 */
@Service(Service.Level.PROJECT)
class FluxService(private val project: Project) {

    private val listeners = CopyOnWriteArrayList<FluxListener>()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)

    @Volatile
    var lastAdbStatus: AdbStatus = AdbStatus.Disconnected
        private set

    @Volatile
    var lastResult: DeployResult? = null
        private set

    @Volatile
    var isDeploying: Boolean = false
        private set

    fun addListener(listener: FluxListener) {
        listeners += listener
    }

    fun removeListener(listener: FluxListener) {
        listeners -= listener
    }

    /** Starts polling `adb devices` every 3s. Safe to call more than once (re-arms, no stacking). */
    fun startPolling() {
        alarm.cancelAllRequests()
        scheduleNextPoll(delayMs = 0)
    }

    private fun scheduleNextPoll(delayMs: Int) {
        if (alarm.isDisposed) return
        alarm.addRequest({
            val status = AdbDevices.poll()
            lastAdbStatus = status
            FluxDeployRunner.onEdt { listeners.forEach { it.onAdbStatus(status) } }
            scheduleNextPoll(POLL_INTERVAL_MS)
        }, delayMs)
    }

    fun deploy() {
        if (isDeploying) return
        isDeploying = true
        FluxDeployRunner.onEdt { listeners.forEach { it.onDeployStarted() } }

        FluxDeployRunner.run(
            project = project,
            onOutput = { text ->
                FluxDeployRunner.onEdt { listeners.forEach { it.onDeployOutput(text) } }
            },
            onFinished = { fullOutput, exitCode ->
                val result = DeployOutputParser.parse(fullOutput, exitCode)
                lastResult = result
                isDeploying = false
                FluxDeployRunner.onEdt { listeners.forEach { it.onDeployResult(result) } }
            },
        )
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3000
    }
}
