package dev.flux.gradle

import java.io.File

/**
 * Resolves the `adb` binary with zero configuration.
 *
 * Verified reference environment (see CONTRACT.md): `ANDROID_HOME` unset, `adb` not on `PATH`,
 * SDK installed at `~/Library/Android/sdk`. The search order below is designed to succeed on
 * that exact machine while still doing the right thing on a normal setup where `ANDROID_HOME`
 * is exported or `platform-tools` is on `PATH`.
 */
object AdbLocator {

    private const val PLATFORM_TOOLS_ADB = "platform-tools/adb"

    /**
     * @param configuredPath value of `flux.adbPath`, if the user set one explicitly.
     * @param env environment variable lookup, injectable for testing.
     */
    fun locate(configuredPath: String?, env: (String) -> String? = System::getenv): AdbLocation {
        if (!configuredPath.isNullOrBlank()) {
            val f = File(configuredPath)
            return if (f.isFile && f.canExecute()) {
                AdbLocation.Found(f, "flux.adbPath")
            } else {
                AdbLocation.NotFound(
                    "flux.adbPath is set to \"$configuredPath\" but that file does not exist " +
                        "or is not executable.\n" +
                        "  Fix: correct the `adbPath` value in the `flux { }` block, or remove it " +
                        "to let Flux auto-locate adb."
                )
            }
        }

        // 1. ANDROID_HOME
        env("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let { home ->
            val f = File(home, PLATFORM_TOOLS_ADB)
            if (f.isFile && f.canExecute()) return AdbLocation.Found(f, "ANDROID_HOME")
        }

        // 2. ANDROID_SDK_ROOT
        env("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let { root ->
            val f = File(root, PLATFORM_TOOLS_ADB)
            if (f.isFile && f.canExecute()) return AdbLocation.Found(f, "ANDROID_SDK_ROOT")
        }

        // 3. PATH
        env("PATH")?.split(File.pathSeparatorChar)?.forEach { dir ->
            val f = File(dir, "adb")
            if (f.isFile && f.canExecute()) return AdbLocation.Found(f, "PATH")
        }

        // 4. Standard per-OS SDK install locations (covers the verified reference machine:
        //    ANDROID_HOME unset, adb not on PATH, SDK at the macOS default location).
        val userHome = env("HOME") ?: System.getProperty("user.home")
        val standardLocations = buildList {
            if (userHome != null) {
                add(File(userHome, "Library/Android/sdk")) // macOS
                add(File(userHome, "Android/Sdk"))          // Linux
                add(File(userHome, "AppData/Local/Android/Sdk")) // Windows (best-effort under HOME)
            }
            env("LOCALAPPDATA")?.let { add(File(it, "Android/Sdk")) } // Windows
        }
        for (sdkDir in standardLocations) {
            val f = File(sdkDir, PLATFORM_TOOLS_ADB)
            if (f.isFile && f.canExecute()) return AdbLocation.Found(f, "standard SDK location ($sdkDir)")
        }

        return AdbLocation.NotFound(
            "Flux could not find `adb`.\n" +
                "  Checked: flux.adbPath (unset), \$ANDROID_HOME (unset), \$ANDROID_SDK_ROOT " +
                "(unset), \$PATH, and the standard per-OS SDK install locations.\n" +
                "  Fix: install the Android SDK platform-tools, or set `flux { adbPath = \"...\" }` " +
                "in the module applying dev.flux.load to point at it directly."
        )
    }
}

sealed class AdbLocation {
    data class Found(val file: File, val source: String) : AdbLocation()
    data class NotFound(val message: String) : AdbLocation()
}
