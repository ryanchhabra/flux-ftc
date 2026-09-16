plugins {
    id("java")
    kotlin("jvm") version "2.0.21"
    // Version pinned in settings.gradle.kts via the org.jetbrains.intellij.platform.settings
    // plugin, which puts this plugin on the classpath -- redeclaring a version here conflicts.
    id("org.jetbrains.intellij.platform")
}

group = "dev.flux"
version = "0.1.0-alpha"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Target IntelliJ IDEA Community rather than Android Studio directly. Android Studio is built
// on the same IntelliJ Platform, so a plugin built against IC keeps the build self-contained and
// buildable without an Android Studio SDK dependency, while still installing into Android
// Studio at runtime (per architecture.md §8 / README below).
dependencies {
    intellijPlatform {
        create("IC", "2024.1.7")

        // Everything this plugin needs -- ToolWindow, actions, GeneralCommandLine/process
        // handling, Alarm-based polling -- lives in the base platform module. No extra bundled
        // plugin dependency (e.g. com.intellij.java) is needed for a tool window + a process
        // runner, and CONTRACT.md/the brief both push against pulling in more than necessary.
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.flux.idea"
        name = "FTC Flux"
        version = project.version.toString()
        description = """
            Deploy button, live robot connection status, and hot-reload timing for FTC Flux
            (dev.flux.load) — the Gradle-only fluxDeploy pipeline, wrapped in IDE UI so nobody
            has to hand-build a Run Configuration or touch a terminal. See ecosystem-positioning.md
            §2-3: no existing FTC plugin combines a deploy button, live status, and hot reload.
        """.trimIndent()

        ideaVersion {
            // Conservative: 2024.1 branch. Android Studio releases track IntelliJ Platform
            // versions roughly two releases behind at any given time, so pinning sinceBuild
            // near the low end keeps this installable on the Android Studio release a real FTC
            // team is likely running, not just the bleeding-edge IDE.
            sinceBuild = "241"
        }
    }

    // Keep the plugin verifier scope small for a first version — full IDE-matrix verification is
    // its own can of downloads and isn't needed to get an honest local `gradle build`.
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}
