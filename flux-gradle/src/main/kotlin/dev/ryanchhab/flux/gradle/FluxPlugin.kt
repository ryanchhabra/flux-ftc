package dev.ryanchhab.flux.gradle

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.variant.Variant
import dev.ryanchhab.flux.gradle.tasks.FluxAssemble
import dev.ryanchhab.flux.gradle.tasks.FluxConnect
import dev.ryanchhab.flux.gradle.tasks.FluxDeviceGuard
import dev.ryanchhab.flux.gradle.tasks.FluxDisconnect
import dev.ryanchhab.flux.gradle.tasks.FluxClearBundle
import dev.ryanchhab.flux.gradle.tasks.FluxDeploy
import dev.ryanchhab.flux.gradle.tasks.FluxDex
import dev.ryanchhab.flux.gradle.tasks.FluxDoctor
import dev.ryanchhab.flux.gradle.tasks.FluxPush
import dev.ryanchhab.flux.gradle.tasks.FluxRead
import dev.ryanchhab.flux.gradle.tasks.FluxSyncBaseline
import dev.ryanchhab.flux.gradle.tasks.FluxReload
import dev.ryanchhab.flux.gradle.tasks.FluxTune
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.kotlin.dsl.register
import javax.inject.Inject

/**
 * `dev.ryanchhab.flux` — see CONTRACT.md for the frozen coordinates/task names this plugin must match
 * exactly, and architecture.md §9 for the zero-config goal this whole plugin is built around:
 * `plugins { id("dev.ryanchhab.flux") }` plus `./gradlew fluxDeploy` should be the entire user-facing
 * surface.
 *
 * Apply this to the TeamCode module (a `com.android.library` in the normal FTC project layout;
 * `com.android.application` is also supported in case a project structures TeamCode as the app
 * module directly).
 */
abstract class FluxPlugin @Inject constructor(
    private val buildEventsListenerRegistry: BuildEventsListenerRegistry,
) : Plugin<Project> {

    companion object {
        const val MIN_API = 24 // CONTRACT.md — must match the app's minSdk exactly (risks.md §7).
    }

    override fun apply(project: Project) {
        val extension = project.extensions.create("flux", FluxExtension::class.java)

        val timingService = project.gradle.sharedServices.registerIfAbsent(
            "fluxTimingService-${project.path}",
            FluxTimingService::class.java,
        ) {}

        var wired = false

        project.pluginManager.withPlugin("com.android.library") {
            wireAndroidComponents(project, extension, timingService) { wired = true }
        }
        project.pluginManager.withPlugin("com.android.application") {
            wireAndroidComponents(project, extension, timingService) { wired = true }
        }

        project.afterEvaluate {
            if (!wired) {
                throw GradleException(
                    "dev.ryanchhab.flux must be applied to an Android module.\n" +
                        "  Reason: no com.android.library or com.android.application plugin was " +
                        "found on ${project.path}.\n" +
                        "  Fix: apply dev.ryanchhab.flux to your TeamCode module (the one with " +
                        "`id(\"com.android.library\")`), not the root project.",
                )
            }
        }
    }

    private fun wireAndroidComponents(
        project: Project,
        extension: FluxExtension,
        timingService: Provider<FluxTimingService>,
        markWired: () -> Unit,
    ) {
        val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)
        wireDebugVariant(androidComponents, project, extension, timingService, markWired)
    }

    private fun <VariantT : Variant> wireDebugVariant(
        androidComponents: AndroidComponentsExtension<*, *, VariantT>,
        project: Project,
        extension: FluxExtension,
        timingService: Provider<FluxTimingService>,
        markWired: () -> Unit,
    ) {
        androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
            markWired()
            wireTasks(project, extension, variant, timingService)
        }
    }

    /**
     * A tier-gate [org.gradle.api.specs.Spec] shared by fluxDex/fluxPush/fluxReload: skip
     * running the task when the deploy is blocked (tier 3) or a no-op (tier 0). Built as a plain
     * `Spec<Task>` (rather than passed as a per-task generic helper function) to sidestep a
     * Kotlin/Gradle SAM-conversion resolution quirk seen when `TaskProvider<T>.configure` is
     * wrapped in a small generic function in this codebase/toolchain combination.
     */
    private fun tierGateSpec(
        tierResultFileLoc: Provider<org.gradle.api.file.RegularFile>,
        liveTuneResultFileLoc: Provider<org.gradle.api.file.RegularFile>,
    ): org.gradle.api.specs.Spec<Task> =
        org.gradle.api.specs.Spec<Task> {
            val outcome = TierOutcomeIO.read(tierResultFileLoc.get().asFile)
            val liveTune = LiveTuneOutcomeIO.read(liveTuneResultFileLoc.get().asFile)
            // Tier L takes a completely different path (fluxTune, below) -- compile/dex/push/
            // reload are skipped, not just made cheap, when only eligible literals changed.
            !outcome.blocked && !outcome.noOp && !liveTune.tunable
        }

    /** The mirror image of [tierGateSpec]: fluxTune runs only on the Tier L path. */
    private fun liveTuneGateSpec(
        tierResultFileLoc: Provider<org.gradle.api.file.RegularFile>,
        liveTuneResultFileLoc: Provider<org.gradle.api.file.RegularFile>,
    ): org.gradle.api.specs.Spec<Task> =
        org.gradle.api.specs.Spec<Task> {
            val outcome = TierOutcomeIO.read(tierResultFileLoc.get().asFile)
            val liveTune = LiveTuneOutcomeIO.read(liveTuneResultFileLoc.get().asFile)
            !outcome.blocked && !outcome.noOp && liveTune.tunable
        }

    private fun wireTasks(
        project: Project,
        extension: FluxExtension,
        variant: Variant,
        timingService: Provider<FluxTimingService>,
    ) {
        val buildDir = project.layout.buildDirectory

        // --- adb resolution (zero-config: FluxExtension.adbPath is unset by default) ---
        val adbPathProvider = project.provider {
            when (val located = AdbLocator.locate(extension.adbPath.orNull)) {
                is AdbLocation.Found -> located.file.absolutePath
                is AdbLocation.NotFound -> throw GradleException(located.message)
            }
        }

        // --- fluxAssemble ---
        // Named with a `Loc` suffix throughout this method to avoid shadowing the identically
        // named task properties below: inside `register<T> { ... }`, `this` is the task instance,
        // so `versionSourceDir.set(versionSourceDir)` would otherwise resolve its RHS to the
        // task's own (still-empty) property instead of this outer path.
        val versionSourceDirLoc = buildDir.dir("generated/flux/version")
        val buildIdFileLoc = buildDir.file("flux/build-id.txt")
        val tierResultFileLoc = buildDir.file("flux/tier-result.properties")
        val tierStateDirLoc = buildDir.dir("flux/tierState")
        val deviceStateDirLoc = buildDir.dir("flux/deviceState")
        val liveTuneResultFileLoc = buildDir.file("flux/live-tune-result.txt")
        val liveTuneStateDirLoc = buildDir.dir("flux/liveTuneState")

        val moduleDir = project.projectDir
        val compileClasspath: Configuration? = project.configurations.findByName("debugCompileClasspath")
        val runtimeClasspath: Configuration? = project.configurations.findByName("debugRuntimeClasspath")
        val compileClasspathFiles = compileClasspath?.incoming?.artifactView {
            attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
        }?.files
        // Resolved components, not requested dependencies. Tier detection uses this set to decide
        // whether the dependency graph changed since the last installDebug -- and if it did, to
        // refuse a hot reload, because the pushed dex would be running against jars that are not
        // the ones inside the installed APK. The *requested* set can stay byte-identical while the
        // *resolved* set moves underneath it: a resolutionStrategy.force, a version catalog bump,
        // a platform/BOM, or any transitive that pulls a shared library up. Comparing requested
        // notations would miss every one of those and hot-reload into a mismatched APK, which is
        // the silent-wrong-behaviour case Flux is supposed to convert into "run installDebug".
        val dependencyNotationsProvider = project.provider {
            (runtimeClasspath ?: compileClasspath)
                ?.incoming?.resolutionResult?.allComponents
                ?.map { it.id.displayName }
                ?.toSet() ?: emptySet()
        }

        val fluxAssemble = project.tasks.register<FluxAssemble>("fluxAssemble") {
            group = "flux"
            description = "Generates __FluxVersion, stamps the TeamCode BUILD_ID, and classifies the deploy tier (CONTRACT.md, architecture.md §4)."
            // `.static` (not `.all`) deliberately: `.all` includes generated source directories,
            // and this task itself contributes one (versionSourceDir, wired below) — using `.all`
            // here would make fluxAssemble depend on its own output, a real circular dependency
            // Gradle catches immediately ("Circular dependency ... fluxAssemble --- fluxAssemble").
            variant.sources.java?.static?.let { teamCodeSourceDirs.from(it) }
            versionSourceDir.set(versionSourceDirLoc)
            buildIdFile.set(buildIdFileLoc)
            // Wiring the merged-manifest artifact Provider straight into a real task @InputFile
            // (rather than calling .get() on it eagerly) is what lets Gradle infer "run AGP's
            // manifest-merge task before fluxAssemble" automatically.
            manifestFile.set(variant.artifacts.get<RegularFile>(SingleArtifact.MERGED_MANIFEST))
            resDirs.from(moduleDir.resolve("src/main/res"))
            assetDirs.from(moduleDir.resolve("src/main/assets"))
            nativeLibDirs.from(moduleDir.resolve("src/main/jniLibs"))
            dependencyNotations.set(dependencyNotationsProvider)
            tierStateDir.set(tierStateDirLoc)
            tierResultFile.set(tierResultFileLoc)
            liveTuneStateDir.set(liveTuneStateDirLoc)
            liveTuneResultFile.set(liveTuneResultFileLoc)
            this.timingService.set(timingService)
        }

        // --- fluxSyncBaseline ---
        // A full install is the one moment the robot and the source tree are known to agree, so
        // it is when Flux's "last known good" baseline must advance. Without this, a blocked
        // deploy is a dead end: classify() never advances the baseline on a block, so the very
        // condition that blocked the deploy is still there after the user runs the installDebug
        // we told them to run. See FluxSyncBaseline's class doc.
        val fluxSyncBaseline = project.tasks.register<FluxSyncBaseline>("fluxSyncBaseline") {
            variant.sources.java?.static?.let { teamCodeSourceDirs.from(it) }
            manifestFile.set(variant.artifacts.get<RegularFile>(SingleArtifact.MERGED_MANIFEST))
            resDirs.from(moduleDir.resolve("src/main/res"))
            assetDirs.from(moduleDir.resolve("src/main/assets"))
            nativeLibDirs.from(moduleDir.resolve("src/main/jniLibs"))
            dependencyNotations.set(dependencyNotationsProvider)
            tierStateDir.set(tierStateDirLoc)
            deviceStateDir.set(deviceStateDirLoc)
        }

        // --- fluxClearBundle (CONTRACT.md Amendment 5) ---
        // A stale on-robot bundle must never survive a fresh install -- see FluxClearBundle's
        // class doc, and docs/research/sloth-teardown.md §3 for Sloth's identical fix
        // (removeSlothRemote) to the identical problem.
        val fluxClearBundle = project.tasks.register<FluxClearBundle>("fluxClearBundle") {
            adbPathOrNull.set(
                project.provider {
                    (AdbLocator.locate(extension.adbPath.orNull) as? AdbLocation.Found)?.file?.absolutePath
                },
            )
            deployLocation.set(extension.deployLocation)
        }

        // finalizedBy, not dependsOn: the baseline should only advance -- and the bundle should
        // only be cleared -- once the install has actually succeeded. Matched by name because the
        // install tasks are created by AGP.
        project.tasks.matching { it.name == "installDebug" || it.name == "installRelease" }
            .configureEach { finalizedBy(fluxSyncBaseline, fluxClearBundle) }

        // Register the generated source dir so AGP's own compileDebugJavaWithJavac compiles
        // __FluxVersion.java into the normal debug class output — see FluxAssemble's class doc
        // for why this task doesn't invoke javac itself.
        variant.sources.java?.addGeneratedSourceDirectory(fluxAssemble, FluxAssemble::versionSourceDir)

        // --- fluxDex ---
        // onlyIf is wired inline in each of fluxDex/fluxPush/fluxReload's own registration block
        // (rather than looped over afterwards) — see tierGateSpec's doc for why.
        val fluxDex = project.tasks.register<FluxDex>("fluxDex") {
            group = "flux"
            description = "D8 -> flux_bundle.jar, using a persistent per-class dex cache (CONTRACT.md)."
            compileClasspathFiles?.let { classpathFiles.from(it) }
            minApi.set(MIN_API)
            dexCacheDir.set(buildDir.dir("flux/dexCache"))
            bundleJar.set(buildDir.file("flux/flux_bundle.jar"))
            this.timingService.set(timingService)
            onlyIf("Flux tier gate — skipped when blocked, unchanged, or live-tunable (tier 0/3/L)", tierGateSpec(tierResultFileLoc, liveTuneResultFileLoc))
        }
        // ScopedArtifact.CLASSES (project scope) can materialize as directories and/or jars
        // depending on AGP/Kotlin plugin configuration; toGet wires both automatically and
        // establishes the correct task dependency on whichever task(s) actually produce them.
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
            .use(fluxDex)
            .toGet(ScopedArtifact.CLASSES, FluxDex::classesJars, FluxDex::classesDirs)

        // --- fluxDeviceGuard ---
        // Runs between compilation and dexing: it needs real bytecode, which fluxAssemble cannot
        // have (it generates a source file into that same compilation). See FluxDeviceGuard.
        val fluxDeviceGuard = project.tasks.register<FluxDeviceGuard>("fluxDeviceGuard") {
            deviceStateDir.set(deviceStateDirLoc)
            onlyIf("Flux tier gate — skipped when blocked, unchanged, or live-tunable (tier 0/3/L)", tierGateSpec(tierResultFileLoc, liveTuneResultFileLoc))
        }
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
            .use(fluxDeviceGuard)
            .toGet(ScopedArtifact.CLASSES, FluxDeviceGuard::classesJars, FluxDeviceGuard::classesDirs)
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
            .use(fluxSyncBaseline)
            .toGet(ScopedArtifact.CLASSES, FluxSyncBaseline::classesJars, FluxSyncBaseline::classesDirs)
        fluxDex.configure { dependsOn(fluxDeviceGuard) }

        // --- fluxPush ---
        // --- fluxConnect / fluxDisconnect ---
        // Explicit, never automatic: a doomed `adb connect` blocks for 75 seconds (measured), so
        // running it before every deploy would cost every USB user 75 seconds to save one command
        // per session. Run `./gradlew fluxConnect`, or press Connect in the IDE tool window.
        val fluxConnect = project.tasks.register<FluxConnect>("fluxConnect") {
            adbPath.set(adbPathProvider)
            robotAddress.set(extension.robotAddress)
        }
        val fluxDisconnect = project.tasks.register<FluxDisconnect>("fluxDisconnect") {
            adbPath.set(adbPathProvider)
            robotAddress.set(extension.robotAddress)
        }

        val fluxPush = project.tasks.register<FluxPush>("fluxPush") {
            group = "flux"
            description = "adb push flux_bundle.jar to the deploy dir (CONTRACT.md)."
            bundleJar.set(fluxDex.flatMap { it.bundleJar })
            adbPath.set(adbPathProvider)
            deployLocation.set(extension.deployLocation)
            this.timingService.set(timingService)
            onlyIf("Flux tier gate — skipped when blocked, unchanged, or live-tunable (tier 0/3/L)", tierGateSpec(tierResultFileLoc, liveTuneResultFileLoc))
        }

        // --- fluxReload ---
        val fluxReload = project.tasks.register<FluxReload>("fluxReload") {
            tierStateDir.set(tierStateDirLoc)
            safetyPolicy.set(extension.safetyPolicy.map { it.name })
            group = "flux"
            description = "adb shell am broadcast -a dev.ryanchhab.flux.RELOAD, parse result code (CONTRACT.md)."
            dependsOn(fluxPush)
            adbPath.set(adbPathProvider)
            buildId.set(project.provider { buildIdFileLoc.get().asFile.takeIf { it.isFile }?.readText()?.trim() ?: "" })
            buildIdFile.set(buildIdFileLoc)
            this.timingService.set(timingService)
            onlyIf("Flux tier gate — skipped when blocked, unchanged, or live-tunable (tier 0/3/L)", tierGateSpec(tierResultFileLoc, liveTuneResultFileLoc))
        }

        // --- fluxTune (Tier L — CONTRACT.md Amendment 3, live-tuning.md §3) ---
        // Deliberately depends on fluxAssemble only, not fluxDex/fluxPush/fluxReload: the whole
        // point of Tier L is to skip compile/dex/reload entirely (live-tuning.md §2).
        val fluxTune = project.tasks.register<FluxTune>("fluxTune") {
            group = "flux"
            description = "Push live_values.json + adb shell am broadcast -a dev.ryanchhab.flux.LIVE_TUNE (CONTRACT.md Amendment 3)."
            dependsOn(fluxAssemble)
            adbPath.set(adbPathProvider)
            deployLocation.set(extension.deployLocation)
            changedFieldsEncoded.set(
                project.provider { LiveTuneOutcomeIO.read(liveTuneResultFileLoc.get().asFile).changedFields },
            )
            this.timingService.set(timingService)
            onlyIf("Flux live-tune gate — runs only when the only change was an eligible literal value (tier L)", liveTuneGateSpec(tierResultFileLoc, liveTuneResultFileLoc))
        }

        // --- fluxDeploy (the user-facing lifecycle task) ---
        project.tasks.register<FluxDeploy>("fluxDeploy") {
            group = "flux"
            description = "Deploy TeamCode to the robot via hot reload (the only command you need)."
            dependsOn(fluxAssemble, fluxDex, fluxPush, fluxReload, fluxTune)
            this.timingService.set(timingService)
            adbPath.set(adbPathProvider)
            robotAddress.set(extension.robotAddress)
            buildId.set(project.provider { buildIdFileLoc.get().asFile.takeIf { it.isFile }?.readText()?.trim() })

            val outcomeProvider = project.provider { TierOutcomeIO.read(tierResultFileLoc.get().asFile) }
            tierBlocked.set(outcomeProvider.map { it.blocked })
            tierBlockedReason.set(outcomeProvider.map { it.blockedReason ?: "" })
            tierBlockedCulprit.set(outcomeProvider.map { it.blockedCulprit ?: "" })
            tierBlockedFix.set(outcomeProvider.map { it.blockedFix ?: "" })
            tierBlockedDetail.set(outcomeProvider.map { it.blockedDetail ?: "" })
            tierNoOp.set(outcomeProvider.map { it.noOp })

            val liveTuneOutcomeProvider = project.provider { LiveTuneOutcomeIO.read(liveTuneResultFileLoc.get().asFile) }
            liveTuneEligible.set(liveTuneOutcomeProvider.map { it.tunable })
            liveTuneFieldsSummary.set(
                liveTuneOutcomeProvider.map { lt ->
                    lt.changedFields.joinToString(", ") { encoded ->
                        val parts = encoded.split("")
                        if (parts.size == 4) {
                            "${parts[0].substringAfterLast('.')}.${parts[1]}=${parts[3]}"
                        } else {
                            encoded
                        }
                    }
                },
            )
        }

        // --- fluxRead (CONTRACT.md Amendment 4) ---
        // Standalone: no dependency on fluxAssemble/tier classification. It's a query ("what is the
        // robot actually running right now"), not a deploy, and the source scan it needs
        // (LiveTuneDetector.currentFields) is cheap enough to just do inline in the task action.
        project.tasks.register<FluxRead>("fluxRead") {
            group = "flux"
            description = "Read back current on-robot @FluxLive values and diff against source (CONTRACT.md Amendment 4)."
            variant.sources.java?.static?.let { teamCodeSourceDirs.from(it) }
            adbPath.set(adbPathProvider)
            deployLocation.set(extension.deployLocation)
        }

        // --- fluxDoctor ---
        project.tasks.register<FluxDoctor>("fluxDoctor") {
            group = "flux"
            description = "Diagnose Flux setup: adb, device, runtime, SDK version (architecture.md §9)."
            adbPath.set(project.provider {
                when (val located = AdbLocator.locate(extension.adbPath.orNull)) {
                    is AdbLocation.Found -> located.file.absolutePath
                    is AdbLocation.NotFound -> null
                }
            })
            adbLocationSource.set(project.provider {
                (AdbLocator.locate(extension.adbPath.orNull) as? AdbLocation.Found)?.source
            })
            // allComponents, not allDependencies: the latter reports what was *requested*, which
            // is not what ends up on the robot the moment anything forces or constrains RobotCore
            // (a resolutionStrategy.force, a platform, or another dependency pulling it up). Flux
            // would then diagnose a version the team is not actually running -- the exact class of
            // confidently-wrong answer a doctor tool exists to prevent.
            robotCoreVersion.set(project.provider {
                (runtimeClasspath ?: compileClasspath)
                    ?.incoming?.resolutionResult?.allComponents
                    ?.mapNotNull { it.moduleVersion }
                    ?.firstOrNull { it.name == "RobotCore" }
                    ?.version
            })
        }

        // Compile-stage timing (architecture.md §5) — observe AGP's own compile task(s) for this
        // variant rather than re-timing compilation ourselves.
        val capitalizedVariant = variant.name.replaceFirstChar { it.uppercase() }
        val compileTaskPaths = setOf(
            "${project.path}:compile${capitalizedVariant}JavaWithJavac",
            "${project.path}:compile${capitalizedVariant}Kotlin",
        )
        buildEventsListenerRegistry.onTaskCompletion(
            project.provider { FluxCompileTimingListener(compileTaskPaths, timingService) },
        )
    }
}
