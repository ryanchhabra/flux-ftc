package dev.ryanchhab.flux.idea

import java.net.InetSocketAddress
import java.net.Socket

/**
 * `adb connect` to a Control Hub over Wi-Fi Direct, for the tool window's Connect button.
 *
 * ## Why this probes first
 *
 * `adb connect` to an address with nothing listening blocks for **75 seconds** before giving up.
 * That was measured, and it is the reason Flux does not connect automatically before a deploy: at
 * that price, a convenience that saves one command per session would cost 75 seconds on every
 * deploy for anyone working over USB.
 *
 * A two second TCP probe answers the same question, so a robot that is switched off is reported
 * in two seconds rather than seventy five.
 */
object AdbConnector {

    private const val DEFAULT_PORT = 5555
    private const val PROBE_TIMEOUT_MS = 2000

    sealed class Result {
        data class Connected(val target: String) : Result()
        data class Unreachable(val target: String) : Result()
        data class Failed(val target: String, val detail: String) : Result()
        object NoAdb : Result()
    }

    /** Blocking. Callers must run this off the EDT. */
    fun connect(address: String): Result {
        val adb = AdbLocator.locate() ?: return Result.NoAdb
        val host = address.substringBefore(':').trim()
        val port = address.substringAfter(':', "").trim().toIntOrNull() ?: DEFAULT_PORT
        val target = "$host:$port"

        if (!isReachable(host, port)) {
            return Result.Unreachable(target)
        }

        return try {
            val process = ProcessBuilder(adb.absolutePath, "connect", target)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            // adb exits 0 even when the connection failed, so its text is the only real signal.
            val ok = output.contains("connected to", ignoreCase = true) &&
                !output.contains("cannot connect", ignoreCase = true) &&
                !output.contains("failed to connect", ignoreCase = true)
            if (ok) Result.Connected(target) else Result.Failed(target, output)
        } catch (e: Exception) {
            Result.Failed(target, e.message ?: e.toString())
        }
    }

    /** Blocking. Callers must run this off the EDT. */
    fun disconnect(address: String) {
        val adb = AdbLocator.locate() ?: return
        val host = address.substringBefore(':').trim()
        val port = address.substringAfter(':', "").trim().toIntOrNull() ?: DEFAULT_PORT
        try {
            ProcessBuilder(adb.absolutePath, "disconnect", "$host:$port")
                .redirectErrorStream(true)
                .start()
                .waitFor()
        } catch (e: Exception) {
            // Disconnecting is best-effort; nothing useful to do if it fails.
        }
    }

    private fun isReachable(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS); true }
    } catch (e: Exception) {
        false
    }
}
