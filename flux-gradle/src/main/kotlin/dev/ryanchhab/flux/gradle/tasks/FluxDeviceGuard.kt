package dev.ryanchhab.flux.gradle.tasks

import dev.ryanchhab.flux.gradle.DeviceDriverGuard
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Blocks a hot deploy when a hardware device-driver class was added, changed or removed.
 *
 * This is the bytecode half of tier detection. `fluxAssemble` classifies almost everything, but it
 * has to run *before* javac (it generates `__FluxVersion.java` into the compilation), so it has no
 * bytecode to look at. Device drivers are the one category where source text is not good enough:
 * see [DeviceDriverGuard] for the two ways the old source-text scan was wrong, both reproduced on
 * the emulator.
 *
 * So the check lives here instead, in its own task that runs after compilation and before
 * `fluxDex`. Nothing has reached the robot at that point, so blocking here is exactly as safe as
 * blocking in `fluxAssemble`, and the message a team sees is the same.
 *
 * Deliberately a separate task rather than a few lines inside `fluxDex`: `fluxDex` is
 * `@CacheableTask`, and a correctness gate that stops running on a cache hit is not a gate.
 */
@DisableCachingByDefault(because = "A safety gate whose whole job is to run every time; a cache hit would silently skip the check it exists to perform.")
abstract class FluxDeviceGuard : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesDirs: ListProperty<Directory>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesJars: ListProperty<RegularFile>

    @get:OutputDirectory
    abstract val deviceStateDir: DirectoryProperty

    init {
        group = "flux"
        description = "Refuse a hot deploy when a hardware device-driver class changed (CONTRACT.md tier 3)."
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val stateDir = deviceStateDir.get().asFile
        val current = DeviceDriverGuard.scan(
            classesDirs = classesDirs.getOrElse(emptyList()).map { it.asFile },
            classesJars = classesJars.getOrElse(emptyList()).map { it.asFile },
        )

        if (!DeviceDriverGuard.hasState(stateDir)) {
            // Nothing recorded yet, so there is nothing to compare against. Record and allow,
            // matching how TierDetector treats a first-ever deploy: by the time fluxDeploy runs,
            // the team has necessarily already done a full install, so the robot and this build
            // do agree.
            DeviceDriverGuard.saveState(stateDir, current)
            return
        }

        val changed = DeviceDriverGuard.firstChange(current, DeviceDriverGuard.loadState(stateDir))
        if (changed != null) {
            throw GradleException(
                buildString {
                    appendLine("Flux cannot hot-deploy this change.")
                    appendLine("    Reason: a hardware device-driver class changed")
                    appendLine("    Class:  $changed")
                    appendLine("    Fix:    ./gradlew installDebug")
                    appendLine()
                    appendLine("    Classes annotated ${DeviceDriverGuard.DEVICE_ANNOTATION_NAMES.joinToString(" / ")}")
                    appendLine("    cannot be hot-reloaded. The Robot Controller built its HardwareMap from these")
                    appendLine("    classes at startup and never rebuilds it on a code-only reload, so a reloaded")
                    appendLine("    copy is a different type and hardwareMap.get() would fail on the robot.")
                },
            )
        }

        // Unchanged, so this is a no-op in practice. Written anyway so the state file self-heals
        // if it was ever lost or written by an older Flux.
        DeviceDriverGuard.saveState(stateDir, current)
    }
}
