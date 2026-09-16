package dev.flux.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * `fluxDoctor` — CONTRACT.md / architecture.md §9: diagnose setup in one command. This is called
 * out as killing "the single biggest class of 'it doesn't work and I don't know why' support
 * burden", so every check prints pass/fail plus a fix, never just a fact.
 *
 * Phase 0 checks:
 *  1. Is adb on PATH or locatable at all.
 *  2. Is a device/emulator connected and authorized.
 *  3. Is a Robot Controller package installed, and does it have a receiver registered for
 *     `dev.flux.RELOAD` (best-effort presence check for the flux-runtime side; see the class doc
 *     TODO below for why this can't yet be a full version handshake).
 *  4. Is the FTC SDK version on the module's classpath one Flux Phase 0 supports (RobotCore
 *     12.0.0 per CONTRACT.md).
 *
 * TODO(Phase 1+): a real version handshake needs flux-protocol (architecture.md §7's versioned
 * handshake). Until that socket exists, "runtime present" is inferred by grepping
 * `dumpsys package` for a registered `dev.flux.RELOAD` receiver, which cannot report the
 * runtime's *version* — only that *something* is listening for the action.
 */
@DisableCachingByDefault(because = "Diagnostic task that probes the live adb connection and the device; its whole value is being current.")
abstract class FluxDoctor : DefaultTask() {

    @get:Inject
    abstract val exec: ExecOperations

    @get:Input
    @get:Optional
    abstract val adbPath: Property<String>

    @get:Input
    @get:Optional
    abstract val adbLocationSource: Property<String>

    @get:Input
    @get:Optional
    abstract val robotCoreVersion: Property<String>

    companion object {
        const val SUPPORTED_SDK_VERSION = "12.0.0"

        /**
         * This plugin's version, compared against what the on-robot runtime reports over
         * dev.flux.PING. Must track flux-gradle/build.gradle.kts's `version` and
         * dev.flux.runtime.FluxVersion.VERSION -- all three are one release.
         */
        const val FLUX_VERSION = "0.1.0-alpha"
        private val ROBOT_CONTROLLER_PACKAGE_HINTS = listOf("ftcrobotcontroller", "qualcomm")
    }

    @TaskAction
    fun diagnose() {
        val lines = mutableListOf<String>()
        var allPass = true

        fun check(label: String, pass: Boolean, detail: String, fix: String? = null) {
            allPass = allPass && pass
            lines += "  [${if (pass) "PASS" else "FAIL"}] $label — $detail"
            if (!pass && fix != null) lines += "         Fix: $fix"
        }

        val adb = adbPath.orNull
        check(
            "adb located",
            adb != null,
            if (adb != null) "$adb (via ${adbLocationSource.getOrElse("?")})" else "not found",
            "install Android SDK platform-tools, or set flux { adbPath = \"...\" }",
        )

        var deviceConnected = false
        var rcPackage: String? = null
        if (adb != null) {
            val devices = capture(adb, "devices")
            val deviceLines = devices.lines().drop(1).filter { it.isNotBlank() && !it.startsWith("*") }
            deviceConnected = deviceLines.any { it.trim().endsWith("device") }
            check(
                "device connected",
                deviceConnected,
                if (deviceConnected) "${deviceLines.size} device(s), at least one authorized" else "no authorized device (raw: ${devices.trim()})",
                "connect via USB or `adb connect ${'$'}{flux.robotAddress}`, and accept the RSA authorization prompt on the device",
            )

            if (deviceConnected) {
                val packages = capture(adb, "shell", "pm", "list", "packages")
                rcPackage = packages.lineSequence()
                    .map { it.removePrefix("package:").trim() }
                    .firstOrNull { pkg -> ROBOT_CONTROLLER_PACKAGE_HINTS.any { hint -> pkg.contains(hint, ignoreCase = true) } }

                check(
                    "Robot Controller app installed",
                    rcPackage != null,
                    rcPackage ?: "no package matching ftcrobotcontroller/qualcomm found",
                    "run ./gradlew installDebug from the FtcRobotController app module",
                )

                // Ask the runtime directly instead of inspecting the APK.
                //
                // This check used to grep `dumpsys package` for a dev.flux.RELOAD receiver, which
                // was a structural FALSE NEGATIVE: dumpsys lists only manifest-declared receivers,
                // and every Flux receiver is registered dynamically from an @OnCreate hook. It
                // therefore reported FAIL on every install, including verified-working ones. A
                // diagnostic tool that always fails is worse than no diagnostic at all.
                //
                // A ping is both simpler and strictly more informative: a reply proves the runtime
                // is running and listening (not merely bundled), and carries its version so skew
                // between the installed runtime and this plugin can be reported precisely.
                val ping = capture(adb, "shell", "am", "broadcast", "-a", "dev.flux.PING")
                val answered = Regex("""result=1""").containsMatchIn(ping)
                val runtimeVersion = Regex("""data="([^"]*)"""").find(ping)?.groupValues?.get(1)

                check(
                    "Flux runtime installed and running",
                    answered,
                    if (answered) "runtime answered dev.flux.PING (version ${runtimeVersion ?: "unknown"})"
                    else "no reply to dev.flux.PING -- runtime not installed, or the Robot Controller app isn't running",
                    "add the flux-runtime dependency to the RC app module, reinstall with " +
                        "./gradlew installDebug, and make sure the Robot Controller app is open",
                )

                // Version skew is the failure a preview team is most likely to hit and least likely
                // to diagnose: the runtime only changes on a full install, while the plugin changes
                // whenever they edit their build file. The two speak one wire protocol.
                if (answered && runtimeVersion != null) {
                    val pluginVersion = FLUX_VERSION
                    val matches = runtimeVersion == pluginVersion
                    check(
                        "Runtime and plugin versions match",
                        matches,
                        if (matches) "both $pluginVersion"
                        else "runtime $runtimeVersion, plugin $pluginVersion",
                        "run ./gradlew installDebug to bring the on-robot runtime up to $pluginVersion",
                    )
                }
            }
        }

        val sdkVersion = robotCoreVersion.orNull
        check(
            "FTC SDK version supported",
            sdkVersion == SUPPORTED_SDK_VERSION,
            sdkVersion?.let { "RobotCore $it" } ?: "RobotCore dependency not found on the module's classpath",
            "Flux Phase 0 targets FTC SDK $SUPPORTED_SDK_VERSION; other versions are untested and may not match the classloader exclusion set in CONTRACT.md",
        )

        logger.lifecycle("\nFlux Doctor\n${lines.joinToString("\n")}\n")
        if (!allPass) {
            logger.lifecycle("Some checks failed — see \"Fix:\" lines above.\n")
        } else {
            logger.lifecycle("All checks passed.\n")
        }
    }

    private fun capture(adb: String, vararg args: String): String {
        val out = ByteArrayOutputStream()
        exec.exec {
            commandLine = listOf(adb) + args
            standardOutput = out
            errorOutput = out
            isIgnoreExitValue = true
        }
        return out.toString()
    }
}
