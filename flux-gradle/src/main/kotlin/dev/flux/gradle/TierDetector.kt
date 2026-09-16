package dev.flux.gradle

import java.io.File
import java.util.Properties

/**
 * Implements the tier classifier from architecture.md §4 — the headline QoL feature. Runs at the
 * start of `fluxDeploy` and decides whether a hot deploy is even safe to attempt.
 *
 * Phase 0 scope, per the task brief:
 *  - Tier 0 (no-op) and Tier 3 (blocked) are fully implemented — Tier 3 is "the feature users
 *    will actually feel" and is the one that must not be skipped.
 *  - Tier 2 (device-driver annotation changes) needs bytecode scanning for the six annotations
 *    in CONTRACT.md's classloader exclusion set (risks.md §1) to distinguish it from Tier 1. That
 *    scan isn't implemented yet — see the TODO on [Tier.HotOrHardware] below — so Flux currently
 *    treats "TeamCode-only change, nothing Tier-3-worthy" as Tier 1 across the board. This is
 *    conservative in the *wrong* direction (a Tier-2 change is still allowed to hot-reload as if
 *    it were Tier 1, rather than getting the HardwareMap rebuild it actually needs), so it is
 *    called out loudly in fluxDeploy's output rather than silently passing as "hot".
 */
object TierDetector {

    private const val STATE_FILE_NAME = "tier-state.properties"

    sealed class Tier {
        /** Nothing changed since the last recorded deploy. Skip everything. */
        data class NoOp(val buildId: String) : Tier()

        /**
         * Safe to hot-reload. `hardwareChangeSuspected` is always false today — see the class
         * doc TODO. Once bytecode scanning lands, a true Tier 2 result should carry
         * `hardwareChangeSuspected = true` so fluxDeploy can trigger the HardwareMap rebuild path.
         */
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
    private val DEVICE_ANNOTATIONS = listOf(
        "@I2cDeviceType",
        "@MotorType",
        "@ServoType",
        "@DigitalIoDeviceType",
        "@AnalogSensorType",
        "@DeviceProperties",
    )

    fun classify(
        stateDir: File,
        manifestFile: File,
        resDirs: List<File>,
        assetDirs: List<File>,
        nativeLibDirs: List<File>,
        dependencyNotations: Set<String>,
        currentBuildId: String,
        sourceDirs: List<File> = emptyList(),
    ): Tier {
        stateDir.mkdirs()
        val stateFile = File(stateDir, STATE_FILE_NAME)
        val previous = loadState(stateFile)

        val manifestHash = if (manifestFile.isFile) Hashing.sha256Hex(manifestFile) else "absent"
        val resHash = resDirs.joinToString("|") { Hashing.sha256OfTree(it) }
        val assetsHash = assetDirs.joinToString("|") { Hashing.sha256OfTree(it) }
        val nativeHash = nativeLibDirs.joinToString("|") { Hashing.sha256OfTree(it) }
        val depsHash = Hashing.sha256Hex(dependencyNotations.sorted().joinToString("\n").toByteArray())
        val deviceFileHashes = deviceDriverSourceHashes(sourceDirs)

        val result = when {
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

            changedDeviceDriverSource(deviceFileHashes, previous.deviceFileHashes) != null -> Tier.Blocked(
                reason = "a hardware device-driver class changed",
                culprit = changedDeviceDriverSource(deviceFileHashes, previous.deviceFileHashes)!!,
                fix = "./gradlew installDebug",
                detail = "Classes annotated @I2cDeviceType / @MotorType / @ServoType / "
                    + "@DigitalIoDeviceType / @AnalogSensorType / @DeviceProperties cannot be "
                    + "hot-reloaded. The robot built its HardwareMap from these classes at "
                    + "startup and never rebuilds it on a code-only reload, so a reloaded copy "
                    + "is a different type and hardwareMap.get() would fail. "
                    + "See docs/research/risks.md \u00a71.",
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
                    deviceFileHashes = deviceFileHashes,
                ),
            )
        }

        return result
    }

    /**
     * Writes the current fingerprint as the new known-good baseline, with no classification.
     *
     * Called after a full install (see [dev.flux.gradle.tasks.FluxSyncBaseline]), which is the one
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
        sourceDirs: List<File>,
    ) {
        stateDir.mkdirs()
        val previousBuildId = loadState(File(stateDir, STATE_FILE_NAME))?.buildId ?: ""
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
                deviceFileHashes = deviceDriverSourceHashes(sourceDirs),
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
    private fun deviceDriverSourceHashes(sourceDirs: List<File>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (dir in sourceDirs) {
            if (!dir.exists()) continue
            dir.walkTopDown()
                .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
                .forEach { f ->
                    val text = try { f.readText() } catch (e: Exception) { return@forEach }
                    if (DEVICE_ANNOTATIONS.any { text.contains(it) }) {
                        map[f.path] = Hashing.sha256Hex(f)
                    }
                }
        }
        return map
    }

    private fun changedDeviceDriverSource(
        current: Map<String, String>,
        previous: Map<String, String>,
    ): String? {
        for ((path, hash) in current) {
            if (previous[path] != hash) {
                return if (previous.containsKey(path)) path else "$path (new device driver)"
            }
        }
        for (path in previous.keys) {
            if (!current.containsKey(path)) return "$path (removed)"
        }
        return null
    }

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
        val deviceFileHashes: Map<String, String> = emptyMap(),
    )

    private fun loadState(file: File): State? {
        if (!file.isFile) return null
        return try {
            val props = Properties().apply { file.inputStream().use { load(it) } }
            State(
                buildId = props.getProperty("buildId") ?: return null,
                manifestHash = props.getProperty("manifestHash") ?: "",
                resHash = props.getProperty("resHash") ?: "",
                assetsHash = props.getProperty("assetsHash") ?: "",
                nativeHash = props.getProperty("nativeHash") ?: "",
                depsHash = props.getProperty("depsHash") ?: "",
                resFileHashes = decodeMap(props.getProperty("resFileHashes")),
                assetFileHashes = decodeMap(props.getProperty("assetFileHashes")),
                deviceFileHashes = decodeMap(props.getProperty("deviceFileHashes")),
                dependencyNotations = props.getProperty("dependencyNotations")
                    ?.split("")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
            )
        } catch (e: Exception) {
            null // Corrupt/foreign state file: treat as "no history" rather than crash the build.
        }
    }

    private fun saveState(file: File, state: State) {
        val props = Properties()
        props.setProperty("buildId", state.buildId)
        props.setProperty("manifestHash", state.manifestHash)
        props.setProperty("resHash", state.resHash)
        props.setProperty("assetsHash", state.assetsHash)
        props.setProperty("nativeHash", state.nativeHash)
        props.setProperty("depsHash", state.depsHash)
        props.setProperty("resFileHashes", encodeMap(state.resFileHashes))
        props.setProperty("assetFileHashes", encodeMap(state.assetFileHashes))
        props.setProperty("deviceFileHashes", encodeMap(state.deviceFileHashes))
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
