package dev.ryanchhab.flux.gradle

import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream

/**
 * Works out WHY the robot did not answer a Flux broadcast, by asking the device instead of
 * guessing.
 *
 * `am broadcast` reports `result=0` whenever nothing set a result code, and that single symptom
 * covers several very different causes. Flux used to print one fixed paragraph for all of them,
 * leading with "the Flux runtime isn't installed" and telling the user to reinstall. That is the
 * wrong first guess: the common case, by a wide margin, is simply that the Robot Controller app is
 * not running. It was hit three times in one afternoon of testing, and reinstalling does not fix
 * it -- `installDebug` stops the app, so following the old advice could leave someone reinstalling
 * in a loop while the real fix was to open the app.
 *
 * Two of the three messages also cited internal design documents ("CONTRACT.md Amendment 3") and
 * said the robot-side receiver "may not be built yet". That was true early in development and is
 * not true now. A team reading it would reasonably conclude the feature was unfinished rather than
 * that their app was closed.
 *
 * So: check. adb is already in hand, and the three questions that separate the causes are each one
 * cheap shell command.
 */
object RobotReachability {

    private val ROBOT_CONTROLLER_PACKAGE_HINTS = listOf("ftcrobotcontroller", "qualcomm")

    /**
     * A ready-to-print explanation of a missing result code, ordered most-likely-cause first.
     *
     * [action] is the broadcast that went unanswered, e.g. `dev.ryanchhab.flux.RELOAD`.
     * [rawOutput] is adb's own output, kept in the message so a surprising case is still debuggable.
     */
    fun explain(
        exec: ExecOperations,
        adb: String,
        action: String,
        rawOutput: String,
    ): String {
        val head = "Flux: no result code from the robot (raw adb output: \"${rawOutput.trim()}\").\n"

        val rcPackage = findRobotControllerPackage(exec, adb)
            ?: return head +
                "  The Robot Controller app does not appear to be installed on this device.\n" +
                "  Fix: run `./gradlew installDebug`, then open the Robot Controller app."

        if (!isRunning(exec, adb, rcPackage)) {
            return head +
                "  The Robot Controller app ($rcPackage) is installed but NOT RUNNING, so nothing " +
                "was listening for $action.\n" +
                "  Fix: open the Robot Controller app on the device, then retry. Note that " +
                "`installDebug` stops the app, so it needs opening again after every full install."
        }

        // The process can exist while the app has not actually finished starting -- most commonly
        // because Android is showing a runtime-permission dialog on top of it, which happens on
        // the first launch after a fresh install. Flux's hooks have not run at that point, so
        // blaming a missing flux-runtime would be confidently wrong. Observed on the emulator:
        // the process was alive, PING was silent, and the resumed activity was
        // com.android.packageinstaller/.permission.ui.GrantPermissionsActivity.
        val foreground = resumedActivity(exec, adb)
        if (foreground != null && !foreground.contains(rcPackage)) {
            val permissionDialog = foreground.contains("permission", ignoreCase = true) ||
                foreground.contains("packageinstaller", ignoreCase = true)
            return head +
                "  The Robot Controller app ($rcPackage) has a running process, but it is not the " +
                "app in the foreground" +
                (if (permissionDialog) " -- Android is showing a permission dialog over it" else "") +
                ", so it has not finished starting and Flux's hooks have not run yet.\n" +
                "  Foreground: $foreground\n" +
                "  Fix: " + (if (permissionDialog) {
                    "accept the permission prompt on the device, then retry."
                } else {
                    "bring the Robot Controller app to the foreground and let it finish starting, then retry."
                })
        }

        return head +
            "  The Robot Controller app ($rcPackage) is running, but no Flux receiver answered " +
            "$action. That points at the installed APK not containing flux-runtime, or " +
            "containing a version that predates this action.\n" +
            "  Fix: check `implementation 'dev.ryanchhab:flux-runtime:...'` is in your " +
            "TeamCode dependencies, run `./gradlew installDebug`, reopen the app, and confirm " +
            "with `./gradlew fluxDoctor`."
    }

    /**
     * The package/activity currently resumed, or null when it cannot be determined. Null means
     * "no information", and the caller must not read it as "nothing is in the foreground".
     */
    private fun resumedActivity(exec: ExecOperations, adb: String): String? =
        capture(exec, adb, "shell", "dumpsys", "activity")
            .lineSequence()
            .firstOrNull { it.contains("mResumedActivity") }
            ?.substringAfter("mResumedActivity:", "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun findRobotControllerPackage(exec: ExecOperations, adb: String): String? =
        capture(exec, adb, "shell", "pm", "list", "packages")
            .lineSequence()
            .map { it.removePrefix("package:").trim() }
            .firstOrNull { pkg -> ROBOT_CONTROLLER_PACKAGE_HINTS.any { pkg.contains(it, ignoreCase = true) } }

    /**
     * `pidof` is not present on every Android build (it is missing on some older images, which is
     * exactly the vintage a Control Hub runs), so a blank answer falls back to scanning `ps`.
     * Treating "pidof missing" as "app not running" would produce a confidently wrong diagnosis.
     */
    private fun isRunning(exec: ExecOperations, adb: String, pkg: String): Boolean {
        val pid = capture(exec, adb, "shell", "pidof", pkg).trim()
        if (pid.isNotEmpty() && pid.any { it.isDigit() }) return true
        return capture(exec, adb, "shell", "ps").lineSequence().any { it.contains(pkg) }
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
            // This runs while building an error message. Failing here would replace a useful
            // diagnosis with a confusing secondary failure, so degrade to "no information".
            ""
        }
    }
}
