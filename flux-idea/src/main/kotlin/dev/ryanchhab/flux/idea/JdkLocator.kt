package dev.ryanchhab.flux.idea

import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Finds a JDK the FTC build will actually accept, and reports clearly when it can't.
 *
 * ## Why this exists
 *
 * The FTC SDK's Gradle build targets Java 8 and runs on Gradle 9 + AGP 8.x. Launched on a
 * too-new JDK it dies during script compilation with:
 *
 * ```
 * BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_'
 * Unsupported class file major version 70
 * ```
 *
 * (major 70 = Java 26). This is not hypothetical — it happened twice while building Flux: once from
 * a shell whose `JAVA_HOME` was JDK 26, and then again from inside the IDE, because a subprocess
 * inherits the IDE's environment and that environment is frequently not the JDK Gradle should use.
 *
 * The message names neither the offending JDK nor the fix, so it is worth spending a little code to
 * avoid rather than making every user decode it.
 *
 * ## What it picks
 *
 * A JDK in [MIN_MAJOR]..[MAX_MAJOR], preferring the *oldest* supported one -- see [MIN_MAJOR]. On macOS the
 * authoritative source is `/usr/libexec/java_home`, which knows about every installed JDK; other
 * platforms fall back to `JAVA_HOME` and common install roots.
 *
 * If the inherited `JAVA_HOME` is already in range it wins, so a deliberately-configured
 * environment is never second-guessed.
 */
object JdkLocator {

    private val LOG = logger<JdkLocator>()

    /**
     * AGP 8.x needs 17+, and 17 is also what we deliberately prefer.
     *
     * The FTC SDK compiles at `source`/`target` 8. Running the build on a newer JDK works, but AGP
     * warns on every single compile task:
     *
     * ```
     * Java compiler version 21 has deprecated support for compiling with source/target version 8.
     *   3. Use a lower version of the JDK running the build
     * ```
     *
     * Observed on JDK 21 against the real FTC project. The build succeeded, but it printed that
     * block twice per deploy. AGP is telling us plainly which JDK it wants, so prefer the oldest
     * supported rather than the newest: quieter output, and it matches what the FTC docs assume.
     */
    private const val MIN_MAJOR = 17

    /**
     * Upper bound, not because newer JDKs are bad, but because Gradle/AGP must have shipped support
     * for them. Raise this as the FTC SDK's Gradle and AGP versions move.
     */
    private const val MAX_MAJOR = 21

    /** Substring of the Gradle failure this whole class exists to prevent. */
    const val VERSION_ERROR_MARKER = "Unsupported class file major version"

    /**
     * A JDK home suitable for running the FTC build, or null if none was found (in which case the
     * caller should just let Gradle inherit the environment and surface whatever happens).
     */
    fun findSuitableJdk(): File? {
        System.getenv("JAVA_HOME")?.let { home ->
            val dir = File(home)
            val major = majorVersionOf(dir)
            if (major != null && major in MIN_MAJOR..MAX_MAJOR) {
                LOG.info("Inherited JAVA_HOME is Java $major; using it")
                return dir
            }
            LOG.info("Inherited JAVA_HOME is Java $major, outside $MIN_MAJOR..$MAX_MAJOR; searching")
        }

        // macOS: java_home enumerates every installed JDK. Ascend so the OLDEST supported wins.
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            for (major in MIN_MAJOR..MAX_MAJOR) {
                javaHomeFor(major)?.let { return it }
            }
        }

        val roots = listOf(
            File("/Library/Java/JavaVirtualMachines"),
            File("/usr/lib/jvm"),
            File(System.getProperty("user.home"), ".sdkman/candidates/java"),
        )
        return roots.filter { it.isDirectory }
            .flatMap { it.listFiles()?.toList() ?: emptyList() }
            .mapNotNull { candidate ->
                val home = sequenceOf(File(candidate, "Contents/Home"), candidate).firstOrNull {
                    File(it, "bin/java").canExecute()
                } ?: return@mapNotNull null
                majorVersionOf(home)?.let { major -> home to major }
            }
            .filter { it.second in MIN_MAJOR..MAX_MAJOR }
            .minByOrNull { it.second }
            ?.first
    }

    /** `/usr/libexec/java_home -v <major>`, the supported way to resolve a JDK on macOS. */
    private fun javaHomeFor(major: Int): File? = runCatching {
        val p = ProcessBuilder("/usr/libexec/java_home", "-v", major.toString())
            .redirectErrorStream(false)
            .start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        if (p.exitValue() == 0 && out.isNotEmpty()) File(out).takeIf { it.isDirectory } else null
    }.getOrNull()

    /** Reads `release`'s JAVA_VERSION, falling back to running `java -version`. */
    private fun majorVersionOf(javaHome: File): Int? {
        val release = File(javaHome, "release")
        if (release.isFile) {
            runCatching { release.readLines() }.getOrNull()
                ?.firstOrNull { it.startsWith("JAVA_VERSION=") }
                ?.substringAfter('=')?.trim('"', ' ')
                ?.let { return parseMajor(it) }
        }
        val javaBin = File(javaHome, "bin/java")
        if (!javaBin.canExecute()) return null
        return runCatching {
            val p = ProcessBuilder(javaBin.absolutePath, "-version").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
            Regex("""version "([^"]+)"""").find(out)?.groupValues?.get(1)?.let { parseMajor(it) }
        }.getOrNull()
    }

    /** "17.0.12" -> 17, "1.8.0_402" -> 8. */
    private fun parseMajor(version: String): Int? {
        val parts = version.split('.', '_', '-')
        val first = parts.firstOrNull()?.toIntOrNull() ?: return null
        return if (first == 1) parts.getOrNull(1)?.toIntOrNull() else first
    }
}
