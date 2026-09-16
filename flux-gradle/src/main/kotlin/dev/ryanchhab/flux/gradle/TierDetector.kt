package dev.ryanchhab.flux.gradle

import java.io.File
import java.util.Properties

/**
 * Implements the tier classifier from architecture.md §4 — the headline QoL feature. Runs at the
 * start of `fluxDeploy` and decides whether a hot deploy is even safe to attempt.
 *
 * Everything this classifier looks at is available before compilation: the manifest, res/, assets/,
 * native libs, the resolved dependency graph, and a BUILD_ID over the TeamCode sources. That
 * matters because fluxAssemble generates a source file into the compilation and therefore has to
 * run before javac.
 *
 * Device-driver classes are the one category that is deliberately NOT decided here. Identifying
 * them reliably needs bytecode, not source text, so that check lives in
 * [dev.ryanchhab.flux.gradle.tasks.FluxDeviceGuard], which runs after compilation and before
 * fluxDex. See [dev.ryanchhab.flux.gradle.DeviceDriverGuard] for why source text is not good
 * enough.
 */
object TierDetector {

    private const val STATE_FILE_NAME = "tier-state.properties"

    /**
     * Bumped whenever the MEANING of a recorded field changes, not merely its value.
     *
     * Without this, upgrading Flux silently produced a nonsense block. When dependency recording
     * moved from requested notations to resolved components, every existing baseline suddenly
     * "differed", and the deploy was refused with a diff claiming a pile of androidx artifacts had
     * been removed. Nothing had been removed: the two formats simply are not comparable, since the
     * requested set carries pre-conflict-resolution duplicates the resolved set does not.
     *
     * A stale baseline still has to block, because upgrading Flux changes flux-runtime inside the
     * APK and that genuinely needs a full install. What it must not do is invent a reason.
     */
    private const val STATE_FORMAT = 2

    /** What was on disk: nothing, something this Flux cannot interpret, or a usable baseline. */
    private sealed interface Loaded {
        object None : Loaded
        object StaleFormat : Loaded
        data class Ok(val state: State) : Loaded
    }

    sealed class Tier {
        /** Nothing changed since the last recorded deploy. Skip everything. */
        data class NoOp(val buildId: String) : Tier()

        /** Safe to hot-reload. */
        data class HotOrHardware(val hardwareChangeSuspected: Boolean) : Tier()

        /** Refuse the deploy. Names the exact file/category that forced it, and the fix. */
        data class Blocked(
            val reason: String,
            val culprit: String,
            val fix: String,
            /** Optional longer explanation of *why* this cannot be hot-reloaded. */
            val detail: String? = null,
        ) : Tier()
    }

    /**
     * @param moduleDir the TeamCode module's project directory (where AndroidManifest.xml, res/,
     *   assets/ etc. live relative to).
     * @param manifestFile resolved AndroidManifest.xml for the variant.
     * @param resDirs resolved res/ directories for the variant (usually just `src/main/res` plus
     *   any flavor dirs; AGP merges these but we hash the pre-merge source dirs so we can name
     *   the exact file that changed).
     * @param assetDirs resolved assets/ directories for the variant.
     * @param nativeLibDirs jniLibs / native .so source directories for the variant.
     * @param dependencyNotations stable string form (`group:name:version`) of every resolved
     *   dependency on the module's compile+runtime classpath.
     * @param currentBuildId the BUILD_ID fluxAssemble computed for this invocation (from the
     *   TeamCode source tree only — see FluxAssemble). Used for the Tier 0 no-op check.
     */
    fun classify(
        stateDir: File,
        manifestFile: File,
        resDirs: List<File>,
        assetDirs: List<File>,
        nativeLibDirs: List<File>,
        dependencyNotations: Set<String>,
        currentBuildId: String,
    ): Tier {
        stateDir.mkdirs()
        val stateFile = File(stateDir, STATE_FILE_NAME)
        val loaded = loadState(stateFile)
        val previous = (loaded as? Loaded.Ok)?.state

        val manifestHash = if (manifestFile.isFile) Hashing.sha256Hex(manifestFile) else "absent"
        val resHash = resDirs.joinToString("|") { Hashing.sha256OfTree(it) }
        val assetsHash = assetDirs.joinToString("|") { Hashing.sha256OfTree(it) }
        val nativeHash = nativeLibDirs.joinToString("|") { Hashing.sha256OfTree(it) }
        val depsHash = Hashing.sha256Hex(dependencyNotations.sorted().joinToString("\n").toByteArray())

        val result = when {
            loaded is Loaded.StaleFormat -> Tier.Blocked(
                reason = "Flux was upgraded since the last install",
                culprit = "this project's recorded baseline was written by a different version of Flux",
                fix = "./gradlew installDebug",
                detail = "Flux records a fingerprint of the module at every install and compares "
                    + "the next deploy against it. This version of Flux records that fingerprint "
                    + "differently, so the stored one cannot be compared and Flux will not guess. "
                    + "A full install is needed after a Flux upgrade anyway, because flux-runtime "
                    + "itself ships inside the APK. This happens once per upgrade.",
            )

            previous == null -> {
                // First deploy Flux has ever seen for this module: nothing to compare against.
                // Treat as hot rather than blocked so first-run ease-of-use isn't punished, but
                // this is exactly the "requires full install first" case in practice — the user
                // must have already run installDebug once for the RC app + runtime to exist at
                // all, so by the time fluxDeploy runs there *is* a known-good install.
                Tier.HotOrHardware(hardwareChangeSuspected = false)
            }

            previous.buildId == currentBuildId &&
                previous.manifestHash == manifestHash &&
                previous.resHash == resHash &&
                previous.assetsHash == assetsHash &&
                previous.nativeHash == nativeHash &&
                previous.depsHash == depsHash -> Tier.NoOp(currentBuildId)

            previous.manifestHash != manifestHash -> Tier.Blocked(
                reason = "AndroidManifest.xml changed",
                culprit = manifestFile.path,
                fix = "./gradlew installDebug",
            )

            previous.resHash != resHash -> Tier.Blocked(
                reason = "Android resources changed",
                culprit = firstChangedFile(resDirs, previous.resFileHashes)
                    ?: resDirs.joinToString(", ") { it.path },
                fix = "./gradlew installDebug",
            )

            previous.assetsHash != assetsHash -> Tier.Blocked(
                reason = "assets/ changed",
                culprit = firstChangedFile(assetDirs, previous.assetFileHashes)
                    ?: assetDirs.joinToString(", ") { it.path },
                fix = "./gradlew installDebug",
            )

            previous.nativeHash != nativeHash -> Tier.Blocked(
                reason = "native library (.so/JNI) changed",
                culprit = nativeLibDirs.joinToString(", ") { it.path },
                fix = "./gradlew installDebug",
            )

            previous.depsHash != depsHash -> Tier.Blocked(
                reason = "dependency set changed (new/removed/upgraded Gradle dependency)",
                culprit = diffDependencies(previous.dependencyNotations, dependencyNotations),
                fix = "./gradlew installDebug",
            )

            else -> Tier.HotOrHardware(hardwareChangeSuspected = false)
        }

        // Only persist state on a non-blocked outcome — a blocked deploy didn't actually reach
        // the robot, so the "last known good" fingerprint must not move.
        if (result !is Tier.Blocked) {
            saveState(
                stateFile,
                State(
                    buildId = currentBuildId,
                    manifestHash = manifestHash,
                    resHash = resHash,
                    assetsHash = assetsHash,
                    nativeHash = nativeHash,
                    depsHash = depsHash,
                    resFileHashes = fileHashes(resDirs),
                    assetFileHashes = fileHashes(assetDirs),
                    dependencyNotations = dependencyNotations,
                ),
            )
        }

        return result
    }

    /**
     * Writes the current fingerprint as the new known-good baseline, with no classification.
     *
     * Called after a full install (see [dev.ryanchhab.flux.gradle.tasks.FluxSyncBaseline]), which is the one
     * moment we know the robot and the source tree agree. Without this, a blocked deploy is
     * permanent: [classify] never advances the baseline on a block, so the condition that caused
     * the block would still be there on every subsequent run, even after the user did the full
     * install we told them to do.
     */
    fun recordBaseline(
        stateDir: File,
        manifestFile: File,
        resDirs: List<File>,
        assetDirs: List<File>,
        nativeLibDirs: List<File>,
        dependencyNotations: Set<String>,
    ) {
        stateDir.mkdirs()
        val previousBuildId = (loadState(File(stateDir, STATE_FILE_NAME)) as? Loaded.Ok)?.state?.buildId ?: ""
        saveState(
            File(stateDir, STATE_FILE_NAME),
            State(
                // Keep whatever BUILD_ID we last knew. A full install did not produce a Flux
                // bundle, so there is no new one to record; the next fluxDeploy recomputes it
                // from source anyway and will simply not match, correctly yielding tier 1.
                buildId = previousBuildId,
                manifestHash = if (manifestFile.isFile) Hashing.sha256Hex(manifestFile) else "absent",
                resHash = resDirs.joinToString("|") { Hashing.sha256OfTree(it) },
                assetsHash = assetDirs.joinToString("|") { Hashing.sha256OfTree(it) },
                nativeHash = nativeLibDirs.joinToString("|") { Hashing.sha256OfTree(it) },
                depsHash = Hashing.sha256Hex(dependencyNotations.sorted().joinToString("\n").toByteArray()),
                resFileHashes = fileHashes(resDirs),
                assetFileHashes = fileHashes(assetDirs),
                dependencyNotations = dependencyNotations,
            ),
        )
    }

    private fun fileHashes(dirs: List<File>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (dir in dirs) {
            if (!dir.exists()) continue
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                map[f.path] = Hashing.sha256Hex(f)
            }
        }
        return map
    }

    private fun firstChangedFile(dirs: List<File>, previousHashes: Map<String, String>): String? {
        val current = fileHashes(dirs)
        // Added or modified files first.
        for ((path, hash) in current) {
            if (previousHashes[path] != hash) return path
        }
        // Otherwise something was removed.
        for (path in previousHashes.keys) {
            if (!current.containsKey(path)) return "$path (removed)"
        }
        return null
    }

    /**
     * Source files declaring a custom hardware device driver, mapped to their content hash.
     *
     * A *source* scan rather than bytecode scanning: it needs no extra dependency, runs in
     * milliseconds, and errs toward over-inclusion (a comment mentioning @MotorType would match).
     * Over-inclusion here costs a user one unnecessary full install and a clear explanation;
     * under-inclusion costs them a silent ClassCastException on the robot. Easy trade.
     */


    private fun diffDependencies(previous: Set<String>, current: Set<String>): String {
        val added = current - previous
        val removed = previous - current
        return buildString {
            if (added.isNotEmpty()) append("added: ${added.sorted().joinToString(", ")}")
            if (added.isNotEmpty() && removed.isNotEmpty()) append("; ")
            if (removed.isNotEmpty()) append("removed: ${removed.sorted().joinToString(", ")}")
            if (isEmpty()) append("dependency version changed")
        }
    }

    private data class State(
        val buildId: String,
        val manifestHash: String,
        val resHash: String,
        val assetsHash: String,
        val nativeHash: String,
        val depsHash: String,
        val resFileHashes: Map<String, String>,
        val assetFileHashes: Map<String, String>,
        val dependencyNotations: Set<String>,
    )

    private fun loadState(file: File): Loaded {
        if (!file.isFile) return Loaded.None
        return try {
            val props = Properties().apply { file.inputStream().use { load(it) } }
            // Written by a different Flux: the values are not comparable with today's, so report
            // that plainly instead of diffing two incompatible encodings against each other.
            if ((props.getProperty("stateFormat")?.toIntOrNull() ?: 1) != STATE_FORMAT) {
                return Loaded.StaleFormat
            }
            Loaded.Ok(State(
                buildId = props.getProperty("buildId") ?: return Loaded.None,
                manifestHash = props.getProperty("manifestHash") ?: "",
                resHash = props.getProperty("resHash") ?: "",
                assetsHash = props.getProperty("assetsHash") ?: "",
                nativeHash = props.getProperty("nativeHash") ?: "",
                depsHash = props.getProperty("depsHash") ?: "",
                resFileHashes = decodeMap(props.getProperty("resFileHashes")),
                assetFileHashes = decodeMap(props.getProperty("assetFileHashes")),
                dependencyNotations = props.getProperty("dependencyNotations")
                    ?.split("")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
            ))
        } catch (e: Exception) {
            // Corrupt/foreign state file: treat as "no history" rather than crash the build.
            Loaded.None
        }
    }

    private fun saveState(file: File, state: State) {
        val props = Properties()
        props.setProperty("stateFormat", STATE_FORMAT.toString())
        props.setProperty("buildId", state.buildId)
        props.setProperty("manifestHash", state.manifestHash)
        props.setProperty("resHash", state.resHash)
        props.setProperty("assetsHash", state.assetsHash)
        props.setProperty("nativeHash", state.nativeHash)
        props.setProperty("depsHash", state.depsHash)
        props.setProperty("resFileHashes", encodeMap(state.resFileHashes))
        props.setProperty("assetFileHashes", encodeMap(state.assetFileHashes))
        props.setProperty("dependencyNotations", state.dependencyNotations.joinToString(""))
        file.outputStream().use { props.store(it, "Flux tier-detection state — do not edit by hand") }
    }

    private fun encodeMap(map: Map<String, String>): String =
        map.entries.joinToString("") { "${it.key}${it.value}" }

    private fun decodeMap(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split("").filter { it.isNotEmpty() }.associate {
            val parts = it.split("", limit = 2)
            parts[0] to (parts.getOrElse(1) { "" })
        }
    }
}
