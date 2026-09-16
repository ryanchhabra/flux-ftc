package dev.ryanchhab.flux.idea

sealed class AdbStatus {
    /** adb itself couldn't be found (see AdbLocator) -- distinct from "found but no device". */
    object NoAdb : AdbStatus()

    object Disconnected : AdbStatus()

    data class Connected(val deviceId: String) : AdbStatus()
}

/**
 * Runs `adb devices` and reports the first connected+authorized device, if any.
 *
 * Blocking -- callers must run this off the EDT (the tool window polls it from a pooled Alarm
 * thread; see `FluxService`).
 */
object AdbDevices {

    fun poll(): AdbStatus {
        val adb = AdbLocator.locate() ?: return AdbStatus.NoAdb
        return try {
            val process = ProcessBuilder(adb.absolutePath, "devices")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            // Output looks like:
            //   List of devices attached
            //   192.168.43.1:5555\tdevice
            // A second column of "unauthorized" or "offline" means don't report it as connected
            // -- those states can't actually take a deploy.
            val deviceLinePattern = Regex("""^(\S+)\s+device(\s.*)?$""")
            val match = output.lineSequence()
                .drop(1)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .firstNotNullOfOrNull { deviceLinePattern.find(it) }
            if (match != null) {
                AdbStatus.Connected(match.groupValues[1])
            } else {
                AdbStatus.Disconnected
            }
        } catch (e: Exception) {
            AdbStatus.Disconnected
        }
    }
}
