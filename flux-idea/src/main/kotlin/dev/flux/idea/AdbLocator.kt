package dev.flux.idea

import java.io.File

/**
 * Locates the `adb` binary so the tool window can poll `adb devices` without asking the user to
 * configure anything.
 *
 * This mirrors `flux-gradle/src/main/kotlin/dev/flux/gradle/AdbLocator.kt` exactly (same search
 * order, same reference environment from CONTRACT.md: ANDROID_HOME unset, adb not on PATH, SDK
 * at the macOS default). Duplicated rather than shared because flux-idea does not depend on the
 * flux-gradle module -- the two projects are built and versioned independently (see the brief's
 * "do not touch flux-gradle" constraint) and this is ~30 lines, not worth a shared module for a
 * first version.
 */
object AdbLocator {

    private const val PLATFORM_TOOLS_ADB = "platform-tools/adb"

    fun locate(env: (String) -> String? = System::getenv): File? {
        env("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let { home ->
            val f = File(home, PLATFORM_TOOLS_ADB)
            if (f.isFile && f.canExecute()) return f
        }

        env("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let { root ->
            val f = File(root, PLATFORM_TOOLS_ADB)
            if (f.isFile && f.canExecute()) return f
        }

        env("PATH")?.split(File.pathSeparatorChar)?.forEach { dir ->
            val f = File(dir, "adb")
            if (f.isFile && f.canExecute()) return f
        }

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
            if (f.isFile && f.canExecute()) return f
        }

        return null
    }
}
