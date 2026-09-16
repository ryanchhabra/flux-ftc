package dev.flux.gradle.tasks

import dev.flux.gradle.FluxTimingService
import dev.flux.gradle.Hashing
import dev.flux.gradle.LiveTuneDetector
import dev.flux.gradle.LiveTuneOutcome
import dev.flux.gradle.LiveTuneOutcomeIO
import dev.flux.gradle.TierDetector
import dev.flux.gradle.TierOutcome
import dev.flux.gradle.TierOutcomeIO
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * `fluxAssemble` — CONTRACT.md: "Build the TeamCode class output + generate `__FluxVersion`".
 *
 * This task does *not* invoke javac/kotlinc itself. It only:
 *  1. Hashes the TeamCode module's own source tree to derive `BUILD_ID` (CONTRACT.md §"Version
 *     stamping" — sha256 over the bundle inputs, first 12 hex chars).
 *  2. Generates `__FluxVersion.java` into a directory registered as a generated Java source
 *     directory on the debug variant (see FluxPlugin), so AGP's own compileDebugJavaWithJavac
 *     task compiles it into the normal class output alongside everything else.
 *  3. Runs tier classification (architecture.md §4) and persists the result for the other tasks
 *     to read.
 *
 * Deliberately *not* hashing the compiled .class output here: the debug compile task's output
 * includes the very file this task generates, so hashing post-compile classes would be circular
 * (this task would need its own output as an input). Hashing the pre-existing source tree avoids
 * that and is still a faithful "did anything change" signal for BUILD_ID / Tier 0 purposes.
 *
 * ## Why tier classification lives here
 * `fluxAssemble` is the one task in the pipeline that always runs, regardless of tier (it's what
 * *produces* the classification everyone else reads). Doing the classification here — using this
 * task's own `@InputFile`/`@InputFiles` properties for the manifest/res/assets/native-lib
 * directories — lets Gradle infer the correct task ordering against AGP's own manifest-merge task
 * automatically (because the merged-manifest artifact `Provider` is wired into a real task input,
 * not read early). Doing it lazily in the plugin's `apply()` instead would either force premature
 * evaluation of an AGP artifact `Provider` before its producing task has run, or need extra
 * plumbing to guarantee ordering — this is simpler and correct by construction.
 */
abstract class FluxAssemble : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val teamCodeSourceDirs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val versionSourceDir: DirectoryProperty

    @get:OutputFile
    abstract val buildIdFile: RegularFileProperty

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

    @get:Internal
    abstract val tierStateDir: DirectoryProperty

    @get:OutputFile
    abstract val tierResultFile: RegularFileProperty

    /** [dev.flux.gradle.LiveTuneDetector]'s own snapshot dir -- separate from [tierStateDir]. */
    @get:Internal
    abstract val liveTuneStateDir: DirectoryProperty

    @get:OutputFile
    abstract val liveTuneResultFile: RegularFileProperty

    @get:Internal
    abstract val timingService: Property<FluxTimingService>

    init {
        // Tier classification depends on the previous successful deploy, not only on declared
        // file inputs. Recompute it for every invocation so a completed hot deploy is followed
        // by a genuine tier-0 no-op when nothing subsequently changes.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun generate() {
        val start = System.nanoTime()

        val treeHash = teamCodeSourceDirs.files
            .filter { it.exists() }
            .sortedBy { it.path }
            .joinToString("|") { Hashing.sha256OfTree(it) }
        val buildId = Hashing.shorten(Hashing.sha256Hex(treeHash.toByteArray()))

        val outDir = versionSourceDir.get().asFile
        val pkgDir = outDir.resolve("org/firstinspires/ftc/teamcode")
        pkgDir.mkdirs()
        pkgDir.resolve("__FluxVersion.java").writeText(
            """
            package org.firstinspires.ftc.teamcode;

            // Generated by dev.flux.load — do not edit, do not commit.
            // Read back through the *new* classloader after a reload to confirm it actually
            // took (CONTRACT.md "Version stamping"); mismatch or CNFE => result code 3.
            public final class __FluxVersion {
                public static final String BUILD_ID = "$buildId";
                private __FluxVersion() {}
            }

            """.trimIndent(),
        )

        buildIdFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(buildId)
        }

        val tier = TierDetector.classify(
            stateDir = tierStateDir.get().asFile,
            manifestFile = manifestFile.get().asFile,
            resDirs = resDirs.files.toList(),
            assetDirs = assetDirs.files.toList(),
            nativeLibDirs = nativeLibDirs.files.toList(),
            dependencyNotations = dependencyNotations.get(),
            currentBuildId = buildId,
            // Scanned for the six hardware device-driver annotations. A change to one of those
            // classes cannot be hot-reloaded (docs/research/risks.md §1) and is blocked at build
            // time rather than failing on the robot with a ClassCastException later.
            sourceDirs = teamCodeSourceDirs.files.toList(),
        )

        val outcome = when (tier) {
            is TierDetector.Tier.Blocked -> TierOutcome(
                blocked = true,
                blockedReason = tier.reason,
                blockedCulprit = tier.culprit,
                blockedFix = tier.fix,
                blockedDetail = tier.detail,
                buildId = buildId,
            )
            is TierDetector.Tier.NoOp -> TierOutcome(blocked = false, noOp = true, buildId = buildId)
            is TierDetector.Tier.HotOrHardware -> TierOutcome(
                blocked = false,
                hardwareChangeSuspected = tier.hardwareChangeSuspected,
                buildId = buildId,
            )
        }
        TierOutcomeIO.write(tierResultFile.get().asFile, outcome)

        // Live-tune classification (live-tuning.md §4.2, CONTRACT.md Amendment 3) — runs
        // alongside tier classification, not instead of it; fluxDeploy decides which result wins
        // (blocked > no-op > Tier L > Tier 1/2). Only advance LiveTuneDetector's own baseline when
        // this deploy isn't blocked -- a blocked deploy never reached the robot, so "consuming"
        // the literal change here would silently drop it (see LiveTuneDetector.detect's doc).
        val liveTune = LiveTuneDetector.detect(
            stateDir = liveTuneStateDir.get().asFile,
            sourceDirs = teamCodeSourceDirs.files.toList(),
            advanceBaseline = tier !is TierDetector.Tier.Blocked,
        )
        val liveTuneOutcome = when (liveTune) {
            is LiveTuneDetector.Result.Tunable -> LiveTuneOutcome(
                tunable = true,
                changedFields = liveTune.changed.map { "${it.className}${it.fieldName}${it.type}${it.value}" },
                warnings = liveTune.warnings.map { "${it.file}${it.fieldName}${it.message}" },
            )
            is LiveTuneDetector.Result.NotTunable -> LiveTuneOutcome(
                tunable = false,
                warnings = liveTune.warnings.map { "${it.file}${it.fieldName}${it.message}" },
            )
        }
        LiveTuneOutcomeIO.write(liveTuneResultFile.get().asFile, liveTuneOutcome)
        val liveTuneWarnings = when (liveTune) {
            is LiveTuneDetector.Result.Tunable -> liveTune.warnings
            is LiveTuneDetector.Result.NotTunable -> liveTune.warnings
        }
        for (w in liveTuneWarnings) {
            logger.warn("Flux: ${w.file} field `${w.fieldName}`: ${w.message}")
        }

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        timingService.orNull?.record("Generate", elapsedMs)
        logger.lifecycle("Flux: BUILD_ID=$buildId" + if (outcome.blocked) " (BLOCKED: ${outcome.blockedReason})" else "")
    }
}
