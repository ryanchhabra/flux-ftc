package dev.ryanchhab.flux.gradle.tasks

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import com.android.tools.r8.origin.PathOrigin
import dev.ryanchhab.flux.gradle.FluxTimingService
import dev.ryanchhab.flux.gradle.Hashing
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipFile

/**
 * `fluxDex` — CONTRACT.md: "D8 → `flux_bundle.jar`".
 *
 * risks.md §7 requirements enforced here:
 *  - **Debug-equivalent invocation always**, regardless of the app's own build variant:
 *    `CompilationMode.DEBUG`, no minification, line numbers kept.
 *  - **`--min-api` matches the app** (24, per CONTRACT.md) so hot-reloaded classes desugar
 *    identically to the classes already loaded by the parent classloader.
 *
 * Input classes come from AGP's `ScopedArtifact.CLASSES` (project scope) via the variant API's
 * `toGet` — that artifact can materialize as either directories or jars depending on AGP/Kotlin
 * plugin configuration, so both [classesDirs] and [classesJars] are wired (see FluxPlugin).
 *
 * ## Persistent dex cache
 * This is "the main measured win over the competitor" per the task brief — Sloth's `AssembleSloth`
 * runs a full `RELEASE` D8 merge of the entire TeamCode module on every single deploy
 * (sloth-teardown.md §3). Flux instead dexes each `.class` independently, keyed by the sha256 of
 * its bytes (plus a classpath fingerprint, so a dependency bump invalidates entries that could
 * desugar differently), and only re-dexes cache misses:
 *
 *  1. For every input class (from a directory or a jar entry): cache key = sha256(class bytes) +
 *     classpath fingerprint. Cache hit -> reuse the stored per-class `.dex`. Cache miss -> run D8
 *     in `setIntermediate(true)` mode on just those bytes (`addClassProgramData`, no temp file
 *     needed) with the full classpath available for desugaring context, and store the result.
 *  2. A second, cheap D8 merge pass (non-intermediate) combines every per-class dex — cached and
 *     freshly produced — into the final `classes.dex`, which is then jarred up as
 *     `flux_bundle.jar`.
 *
 * This mirrors AGP's own per-class dexing + merge strategy for incremental dexing, so it's a
 * well-trodden design, not a novel risk. **Known limitation, left as-is rather than half-solved
 * silently:** per-class intermediate dexing means desugaring decisions for a given class are made
 * without seeing its sibling classes change together in the same build; the classpath fingerprint
 * bump on dependency changes covers the common case (a library upgrade) but a class whose
 * desugaring depends on a *sibling TeamCode class's* signature changing without its own bytes
 * changing is a corner case D8's own incremental dexing has the same property for, so this is
 * considered acceptable for Phase 0.
 */
@CacheableTask
abstract class FluxDex : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesDirs: ListProperty<Directory>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesJars: ListProperty<RegularFile>

    /** Full compile+runtime classpath, for desugaring context only — never dexed themselves. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classpathFiles: ConfigurableFileCollection

    @get:Input
    abstract val minApi: Property<Int>

    @get:Internal
    abstract val dexCacheDir: DirectoryProperty

    @get:OutputFile
    abstract val bundleJar: RegularFileProperty

    @get:Internal
    abstract val timingService: Property<FluxTimingService>

    private data class ClassInput(val relativeName: String, val bytes: ByteArray, val origin: File)

    @TaskAction
    fun dex() {
        val start = System.nanoTime()

        val cacheDir = dexCacheDir.get().asFile.apply { mkdirs() }
        val classpath = classpathFiles.files.filter { it.exists() }
        // Deliberately path + size, NOT lastModified.
        //
        // The classpath belongs in the key because D8's desugaring decisions depend on it. But
        // timestamps are the wrong signal: AGP rebuilds the FtcRobotController library jar on
        // essentially every deploy, so its mtime changes even when its contents do not. Including
        // mtime therefore invalidated the entire cache on every single run — measured on the real
        // test project as "cached: 0/394" every time, with dexing at ~1850 ms of a ~2150 ms
        // deploy (86%). Size is a coarser but stable proxy, and a classpath change that alters
        // desugaring without altering any jar's size is not a realistic case for this tool.
        //
        // Content-hashing the jars was considered and rejected: jar entries embed their own
        // timestamps, so a rebuilt-but-identical jar is not guaranteed byte-identical either.
        val classpathFingerprint = Hashing.sha256Hex(
            classpath.sortedBy { it.path }
                .joinToString("\n") { "${it.path}:${it.length()}" }
                .toByteArray(),
        ).take(16)

        val classInputs = collectClassInputs()

        var hits = 0
        var misses = 0
        val perClassDexFiles = mutableListOf<File>()

        for (input in classInputs) {
            val key = Hashing.sha256Hex(
                (classpathFingerprint + minApi.get() + input.relativeName + Hashing.sha256Hex(input.bytes)).toByteArray(),
            )
            val cached = File(cacheDir, "$key.dex")
            if (cached.isFile) {
                hits++
            } else {
                misses++
                dexOne(input, classpath, cached)
            }
            perClassDexFiles += cached
        }

        val bundle = bundleJar.get().asFile
        bundle.parentFile.mkdirs()
        mergeAndPackage(perClassDexFiles, bundle)

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val detail = "cached: $hits/${hits + misses}"
        timingService.orNull?.record(FluxTimingService.STAGE_DEX, elapsedMs, detail)
        logger.lifecycle("Flux: dexed ${hits + misses} classes ($detail) -> ${bundle.name}")
    }

    private fun collectClassInputs(): List<ClassInput> {
        val result = mutableListOf<ClassInput>()

        for (dir in classesDirs.getOrElse(emptyList())) {
            val root = dir.asFile
            if (!root.exists()) continue
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { classFile ->
                result += ClassInput(classFile.relativeTo(root).path, classFile.readBytes(), classFile)
            }
        }

        for (jarFile in classesJars.getOrElse(emptyList())) {
            val file = jarFile.asFile
            if (!file.exists()) continue
            ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .forEach { entry ->
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        result += ClassInput("${file.name}!${entry.name}", bytes, file)
                    }
            }
        }

        return result
    }

    /** Dex a single class's bytes in intermediate mode, writing its per-class `.dex` into [outFile]. */
    private fun dexOne(input: ClassInput, classpath: List<File>, outFile: File) {
        val tmpOut = createTempDir(prefix = "flux-d8-one")
        try {
            val command = D8Command.builder()
                .setMode(CompilationMode.DEBUG)
                .setIntermediate(true)
                .setMinApiLevel(minApi.get())
                .addClassProgramData(input.bytes, PathOrigin(input.origin.toPath()))
                .apply { classpath.forEach { addClasspathFiles(it.toPath()) } }
                .setOutput(tmpOut.toPath(), OutputMode.DexIndexed)
                .build()
            D8.run(command)
            val produced = tmpOut.listFiles { f -> f.extension == "dex" }?.firstOrNull()
                ?: error("D8 produced no output for ${input.relativeName}")
            produced.copyTo(outFile, overwrite = true)
        } finally {
            tmpOut.deleteRecursively()
        }
    }

    /** Merge every per-class dex into one `classes.dex` and jar it as `flux_bundle.jar`. */
    private fun mergeAndPackage(perClassDex: List<File>, bundle: File) {
        val mergeOut = createTempDir(prefix = "flux-d8-merge")
        try {
            val programPaths: List<Path> = perClassDex.filter { it.isFile }.map(File::toPath)
            val command = D8Command.builder()
                .setMode(CompilationMode.DEBUG)
                .setMinApiLevel(minApi.get())
                .addProgramFiles(programPaths)
                .setOutput(mergeOut.toPath(), OutputMode.DexIndexed)
                .build()
            D8.run(command)

            val dexOutputs = mergeOut.listFiles { f -> f.extension == "dex" }
                ?.sortedBy { it.name } ?: emptyList()

            JarOutputStream(bundle.outputStream()).use { jar ->
                jar.setLevel(Deflater.BEST_SPEED)
                for (dex in dexOutputs) {
                    jar.putNextEntry(JarEntry(dex.name))
                    dex.inputStream().use { it.copyTo(jar) }
                    jar.closeEntry()
                }
            }
        } finally {
            mergeOut.deleteRecursively()
        }
    }

    private fun createTempDir(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()
}
