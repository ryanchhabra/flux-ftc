package dev.flux.gradle.tasks

import dev.flux.gradle.TierDetector
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * `fluxSyncBaseline` — records the current state of the module as the new "known good" baseline,
 * without classifying or deploying anything.
 *
 * ### Why this task has to exist
 *
 * Tier detection compares the current module against the last state Flux *successfully deployed*,
 * and deliberately does **not** advance that baseline on a blocked outcome — a blocked deploy never
 * reached the robot, so moving the baseline would lose the block on the next run.
 *
 * That is correct, but on its own it creates a dead end. Consider:
 *
 * ```
 * 1. add a class annotated @I2cDeviceType
 * 2. ./gradlew fluxDeploy   -> BLOCKED, "run ./gradlew installDebug"   (baseline NOT advanced)
 * 3. ./gradlew installDebug -> robot now has the new class
 * 4. ./gradlew fluxDeploy   -> STILL BLOCKED, because nothing told Flux about step 3
 * ```
 *
 * The user follows the instructions exactly and never escapes. (Verified: this really happened on
 * the test project before this task existed.)
 *
 * A full install is by definition the moment the robot and the source tree are back in sync, so
 * that is precisely when the baseline should advance. [dev.flux.gradle.FluxPlugin] wires this task
 * as a finalizer of `installDebug`/`installRelease`, so it happens automatically and the user never
 * has to know this mechanism exists.
 */
abstract class FluxSyncBaseline : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val teamCodeSourceDirs: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val resDirs: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val assetDirs: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val nativeLibDirs: ConfigurableFileCollection

    @get:Input
    abstract val dependencyNotations: SetProperty<String>

    @get:OutputDirectory
    abstract val tierStateDir: DirectoryProperty

    init {
        group = "flux"
        description = "Record the current source state as Flux's known-good baseline (runs after a full install)."
        // The whole point is to reflect whatever is on disk right now; never skip as up-to-date.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun sync() {
        TierDetector.recordBaseline(
            stateDir = tierStateDir.get().asFile,
            manifestFile = manifestFile.get().asFile,
            resDirs = resDirs.files.toList(),
            assetDirs = assetDirs.files.toList(),
            nativeLibDirs = nativeLibDirs.files.toList(),
            dependencyNotations = dependencyNotations.get(),
            sourceDirs = teamCodeSourceDirs.files.toList(),
        )
        logger.lifecycle("Flux: baseline synced — the next fluxDeploy compares against this install.")
    }
}
