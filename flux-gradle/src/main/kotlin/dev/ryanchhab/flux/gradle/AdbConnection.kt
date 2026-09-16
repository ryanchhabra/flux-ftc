package dev.ryanchhab.flux.gradle

import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream

/**
 * The `adb connect` / `adb disconnect` primitives behind `fluxConnect` / `fluxDisconnect`.
 *
 * A Control Hub is reached over Wi-Fi Direct at [FluxExtension.robotAddress], and a team that
 * unplugs USB has to run `adb connect 192.168.43.1` by hand before every session. Automating that
 * is the whole point of the setting.
 *
 * ## Probe before connecting
 *
 * [isReachable] exists because `adb connect` to an address with nothing on it blocks for **75
 * seconds** before giving up -- measured, not estimated. That is why connecting is an explicit
 * action rather than something Flux does before every deploy: at that cost, automating it would
 * have turned a 500 ms deploy into a 75 second one for anyone working over USB.
 */
object AdbConnection {

    /** adb's default port for `connect`, and what a Control Hub listens on. */
    private const val DEFAULT_ADB_PORT = 5555

    /**
     * True when adb already has an authorized connection to [address].
     *
     * Matches on the host part rather than the full `host:port` string: a team can legitimately be
     * connected as `192.168.43.1:5555` while having configured plain `192.168.43.1`, and treating
     * those as different would reconnect on every single deploy.
     */
    fun isConnected(exec: ExecOperations, adb: String, address: String): Boolean {
        val host = address.substringBefore(':')
        return capture(exec, adb, "devices")
            .lineSequence()
            .drop(1)
            .filter { it.isNotBlank() && !it.startsWith("*") }
            .any { line ->
                val serial = line.trim().substringBefore('\t').substringBefore(' ')
                serial.substringBefore(':') == host && line.trim().endsWith("device")
            }
    }

    /**
     * Connects if needed. Returns true when adb reports a connection afterwards.
     *
     * `adb connect` is idempotent -- it answers "already connected" rather than erroring -- so
     * [force] only controls whether the check is skipped, not whether a reconnect is safe.
     */
    fun connect(
        exec: ExecOperations,
        adb: String,
        address: String,
        force: Boolean,
        log: (String) -> Unit,
    ): Boolean {
        if (!force && isConnected(exec, adb, address)) {
            return true
        }
        val target = if (address.contains(':')) address else "$address:$DEFAULT_ADB_PORT"
        val output = capture(exec, adb, "connect", target).trim()

        // adb exits 0 even when it failed to reach the host, so the text is the only signal.
        val ok = output.contains("connected to", ignoreCase = true) &&
            !output.contains("cannot connect", ignoreCase = true) &&
            !output.contains("failed to connect", ignoreCase = true)

        if (ok) {
            log("Flux: connected to $target")
        } else if (output.isNotEmpty()) {
            // Info, not a warning: this is the expected outcome for every team on USB, and a
            // warning on every deploy for a thing that is working fine is how warnings get ignored.
            log("Flux: could not reach $target over the network ($output) — continuing, a USB device will still work")
        }
        return ok
    }

    /** Drops the connection. Never fails the build. */
    fun disconnect(exec: ExecOperations, adb: String, address: String, log: (String) -> Unit) {
        val target = if (address.contains(':')) address else "$address:$DEFAULT_ADB_PORT"
        capture(exec, adb, "disconnect", target)
        log("Flux: disconnected from $target")
    }

    /**
     * What adb is actually talking to, e.g. `emulator-5554` or `192.168.43.1:5555`, or null when
     * that cannot be determined.
     *
     * This exists because the deploy header used to print the *configured* `robotAddress`
     * regardless of what was really being used, so it cheerfully reported a Control Hub Wi-Fi
     * address while deploying to an emulator over USB. A header that states a fact should state a
     * real one.
     */
    fun currentDeviceSerial(exec: ExecOperations, adb: String): String? =
        capture(exec, adb, "get-serialno").trim()
            .takeIf { it.isNotEmpty() && !it.contains("unknown", ignoreCase = true) && !it.contains("error", ignoreCase = true) }

    /**
     * A plain TCP probe, so "your robot is not there" can be answered in two seconds instead of
     * the 75 `adb connect` takes to work it out.
     */
    fun isReachable(address: String, timeoutMs: Int): Boolean {
        val host = address.substringBefore(':')
        val port = address.substringAfter(':', "").toIntOrNull() ?: DEFAULT_ADB_PORT
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun capture(exec: ExecOperations, adb: String, vararg args: String): String {
        val out = ByteArrayOutputStream()
        return try {
            exec.exec {
                commandLine = listOf(adb) + args
                standardOutput = out
                errorOutput = out
                isIgnoreExitValue = true
            }
            out.toString()
        } catch (e: Exception) {
            ""
        }
    }
}
