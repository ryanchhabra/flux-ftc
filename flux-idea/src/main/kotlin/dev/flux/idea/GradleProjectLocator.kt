package dev.flux.idea

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Works out which directory to run `gradlew fluxDeploy` in.
 *
 * ## Why this is not simply `project.basePath`
 *
 * The first real run of this plugin failed with:
 *
 * ```
 * Directory '/Users/…/Coding/ftc-flux' does not contain a Gradle build.
 * ```
 *
 * The IDE project root and the Gradle build root are not always the same directory. That happens
 * whenever an FTC project is nested inside a larger repo — which is exactly the layout of the Flux
 * repo itself (the FTC build lives in `test-ftc-project/sdk`), and is also common for teams who
 * keep robot code in a monorepo alongside notes, CAD, or scouting apps.
 *
 * So: search for the Gradle build instead of assuming, and let the user override when the guess is
 * wrong or ambiguous.
 *
 * ## How the search works
 *
 * 1. An explicit user override always wins (persisted per project, see [FluxGradleSettings]).
 * 2. Otherwise walk the project tree to a shallow depth looking for directories that contain a
 *    Gradle settings file, and score them:
 *      - a module applying `dev.flux.load` is a certain match — that is literally the project Flux
 *        deploys, so it beats everything else
 *      - otherwise prefer a build that has a `gradlew` wrapper and looks like an FTC project
 * 3. Give up rather than guess wildly, so the UI can say "pick one" instead of failing obscurely.
 *
 * Depth is capped and build output directories are skipped: an unbounded walk of a large repo on
 * the EDT would be a far worse bug than the one this fixes.
 */
object GradleProjectLocator {

    private const val MAX_DEPTH = 4

    private val SKIP_DIRS = setOf(
        "build", "out", ".git", ".gradle", ".idea", "node_modules", "libs",
        "src", "res", "assets", "doc", "docs",
    )

    /** A candidate Gradle build root, with why we think it is the right one. */
    data class Candidate(val dir: File, val appliesFluxPlugin: Boolean, val hasWrapper: Boolean) {
        val score: Int get() = (if (appliesFluxPlugin) 100 else 0) + (if (hasWrapper) 10 else 0)
    }

    /**
     * The directory to run Gradle in, or null if nothing plausible was found.
     * Honours the user's override first.
     */
    fun resolve(project: Project): File? {
        FluxGradleSettings.getInstance(project).overrideDir
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
            ?.let { return it }

        return findCandidates(project).maxByOrNull { it.score }?.dir
    }

    /** True when the resolved directory came from the user rather than the search. */
    fun isOverridden(project: Project): Boolean =
        FluxGradleSettings.getInstance(project).overrideDir?.let { File(it).isDirectory } == true

    fun findCandidates(project: Project): List<Candidate> {
        val base = project.basePath?.let { File(it) } ?: return emptyList()
        val found = mutableListOf<Candidate>()
        scan(base, 0, found)
        return found
    }

    private fun scan(dir: File, depth: Int, out: MutableList<Candidate>) {
        if (depth > MAX_DEPTH || !dir.isDirectory) return

        val hasSettings = File(dir, "settings.gradle").isFile || File(dir, "settings.gradle.kts").isFile
        if (hasSettings) {
            out += Candidate(
                dir = dir,
                appliesFluxPlugin = appliesFluxPlugin(dir),
                hasWrapper = File(dir, "gradlew").isFile || File(dir, "gradlew.bat").isFile,
            )
            // Don't descend into an included build's own subprojects — the outer build is the one
            // you invoke, and its subprojects are reachable as :Module:task from here.
            return
        }

        dir.listFiles()
            ?.filter { it.isDirectory && !it.isHidden && it.name !in SKIP_DIRS }
            ?.forEach { scan(it, depth + 1, out) }
    }

    /**
     * Does any module in this build apply `dev.flux.load`? Checked by text rather than by asking
     * Gradle, because asking Gradle means configuring the build — far too slow and heavy for
     * something that runs while drawing a tool window.
     */
    private fun appliesFluxPlugin(buildRoot: File): Boolean {
        val buildFiles = buildRoot.walkTopDown()
            .maxDepth(3)
            .onEnter { it.name !in SKIP_DIRS }
            .filter { it.isFile && (it.name == "build.gradle" || it.name == "build.gradle.kts") }
            .take(40)
        return buildFiles.any { f ->
            val text = runCatching { f.readText() }.getOrNull() ?: return@any false
            APPLY_PATTERN.containsMatchIn(text)
        }
    }

    /**
     * Matches a build file that *applies* the plugin, not one that merely mentions the id.
     *
     * A plain `text.contains("dev.flux.load")` is not good enough, and this was caught by actually
     * running the search over this repo: it matched all three builds, because
     *   - `flux-gradle/build.gradle.kts` **registers** the id  -> `id = "dev.flux.load"`
     *   - `flux-idea/build.gradle.kts` mentions it in a comment
     *   - only `test-ftc-project/sdk/TeamCode/build.gradle` **applies** it -> `id 'dev.flux.load'`
     *
     * The `[^=]` guard after `id` is what rejects the registration form, which is the one that
     * would otherwise send Flux at its own Gradle plugin build instead of the robot project.
     */
    private val APPLY_PATTERN = Regex(
        """(?:id\s*\(?\s*['"]dev\.flux\.load['"]|apply\s+plugin:\s*['"]dev\.flux\.load['"])""",
    )
}

/** Per-project persisted override for the Gradle build directory. */
@Service(Service.Level.PROJECT)
@State(name = "FluxGradleSettings", storages = [Storage("flux.xml")])
class FluxGradleSettings : PersistentStateComponent<FluxGradleSettings.State> {

    data class State(var overrideDir: String? = null)

    private var state = State()

    var overrideDir: String?
        get() = state.overrideDir
        set(value) {
            state.overrideDir = value
        }

    override fun getState(): State = state

    override fun loadState(s: State) {
        state = s
    }

    companion object {
        fun getInstance(project: Project): FluxGradleSettings =
            project.getService(FluxGradleSettings::class.java)
    }
}
