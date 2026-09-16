package dev.ryanchhab.flux.gradle.tasks

import dev.ryanchhab.flux.gradle.SdkSupport
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
 *  3. Is a Robot Controller package installed, and does its embedded flux-runtime answer a ping.
 *  4. Do the runtime and this plugin agree on a version (they speak one wire protocol).
 *  5. Is the FTC SDK on the module's classpath one Flux supports — see [SdkSupport] for how that
 *     range was measured.
 *
 * Checks report PASS, WARN or FAIL. WARN exists because "not the version we test against" is not
 * the same as "broken", and a diagnostic that cries wolf gets ignored. Only FAIL sets a non-clean
 * overall result.
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
        /**
         * This plugin's version, compared against what the on-robot runtime reports over
         * dev.ryanchhab.flux.PING. Must track flux-gradle/build.gradle.kts's `version` and
         * dev.ryanchhab.flux.runtime.FluxVersion.VERSION -- all three are one release.
         */
        const val FLUX_VERSION = "0.1.0-alpha"
        private val ROBOT_CONTROLLER_PACKAGE_HINTS = listOf("ftcrobotcontroller", "qualcomm")
    }

    @TaskAction
    fun diagnose() {
        val lines = mutableListOf<String>()
        var allPass = true
        var anyWarn = false

        fun report(status: String, label: String, detail: String, fix: String? = null) {
            lines += "  [$status] $label — $detail"
            if (fix != null && status != "PASS") lines += "         ${if (status == "WARN") "Note" else "Fix"}: $fix"
        }

        fun check(label: String, pass: Boolean, detail: String, fix: String? = null) {
            allPass = allPass && pass
            report(if (pass) "PASS" else "FAIL", label, detail, fix)
        }

        /** A WARN is informational: it prints, but it does not make fluxDoctor report failure. */
        fun warn(label: String, detail: String, note: String? = null) {
            anyWarn = true
            report("WARN", label, detail, note)
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
                "connect via USB, or `adb connect <robot address>` for a Control Hub over wifi (the address you set in the flux { } block), then accept the RSA authorization prompt on the device",
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
                // This check used to grep `dumpsys package` for a dev.ryanchhab.flux.RELOAD receiver, which
                // was a structural FALSE NEGATIVE: dumpsys lists only manifest-declared receivers,
                // and every Flux receiver is registered dynamically from an @OnCreate hook. It
                // therefore reported FAIL on every install, including verified-working ones. A
                // diagnostic tool that always fails is worse than no diagnostic at all.
                //
                // A ping is both simpler and strictly more informative: a reply proves the runtime
                // is running and listening (not merely bundled), and carries its version so skew
                // between the installed runtime and this plugin can be reported precisely.
                val ping = capture(adb, "shell", "am", "broadcast", "-a", "dev.ryanchhab.flux.PING")
                val answered = Regex("""result=1""").containsMatchIn(ping)
                val runtimeVersion = Regex("""data="([^"]*)"""").find(ping)?.groupValues?.get(1)

                check(
                    "Flux runtime installed and running",
                    answered,
                    if (answered) "runtime answered dev.ryanchhab.flux.PING (version ${runtimeVersion ?: "unknown"})"
                    else "no reply to dev.ryanchhab.flux.PING -- runtime not installed, or the Robot Controller app isn't running",
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
        val found = sdkVersion?.let { "RobotCore $it" } ?: "no RobotCore on the module's classpath"
        when (val verdict = SdkSupport.classify(sdkVersion)) {
            is SdkSupport.Verdict.Verified ->
                check("FTC SDK version supported", true, "$found (verified)")

            is SdkSupport.Verdict.Untested ->
                warn(
                    "FTC SDK version supported",
                    "$found — ${verdict.reason}",
                    "Flux should work here. If it doesn't, that is a bug worth reporting: include " +
                        "this fluxDoctor output and `adb logcat -s FLUX`.",
                )

            is SdkSupport.Verdict.Unsupported ->
                check(
                    "FTC SDK version supported",
                    false,
                    found,
                    verdict.reason + ". Update the FTC SDK to ${SdkSupport.MINIMUM} or newer.",
                )
        }

        logger.lifecycle("\nFlux Doctor\n${lines.joinToString("\n")}\n")
        if (!allPass) {
            logger.lifecycle("Some checks failed — see \"Fix:\" lines above.\n")
        } else if (anyWarn) {
            logger.lifecycle("No failures. See \"Note:\" lines above for things Flux can't fully vouch for.\n")
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
