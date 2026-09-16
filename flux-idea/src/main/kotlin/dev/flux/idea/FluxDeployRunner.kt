package dev.flux.idea

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Runs `./gradlew fluxDeploy` and streams its output.
 *
 * Two ways to drive a Gradle task from inside the IDE were on the table (per the brief):
 *  1. IntelliJ's own Gradle integration (`ExternalSystemUtil.runTask` / `GradleExecutionHelper`),
 *     which talks to the Tooling API and gives structured build events.
 *  2. Shell out to the project's own `gradlew` wrapper as a plain external process and parse its
 *     text output.
 *
 * This picks (2). Reasons:
 *  - `fluxDeploy`'s entire report -- tier, stage timings, blocked reason -- is designed as
 *    human-readable `logger.lifecycle` text (see `FluxDeploy.kt`), not Gradle build events, so
 *    the Tooling API's structured progress listeners buy nothing here; the text still has to be
 *    parsed either way.
 *  - `ExternalSystemUtil`/`GradleExecutionHelper` pull in the Gradle plugin module
 *    (`org.jetbrains.plugins.gradle`) as a hard dependency, which this plugin would then have to
 *    declare and version-match against the target IDE build -- exactly the kind of extra
 *    surface area the brief says to avoid for a first version.
 *  - The project already has a working, verified entry point for this exact task:
 *    `./gradlew :TeamCode:fluxDeploy` from a terminal. Running the same wrapper as a subprocess
 *    reproduces that known-good path instead of a new one.
 *
 * Runs entirely off the EDT: `OSProcessHandler` owns its own reader threads, and this object
 * never calls a blocking `waitFor()` -- callbacks arrive via `ProcessAdapter`, and every UI
 * update the caller does in response must itself hop back to the EDT (the tool window panel and
 * action both do this via `invokeLater`).
 */
object FluxDeployRunner {

    private val LOG = logger<FluxDeployRunner>()

    /**
     * @param taskPath Gradle task to run, e.g. "fluxDeploy" or ":TeamCode:fluxDeploy". Defaults
     *   to the bare task name so Gradle resolves it against whichever module applies
     *   `dev.flux.load` -- CONTRACT.md doesn't fix a module name, and requiring the user to know
     *   theirs would violate architecture.md §9's "one line to adopt" goal.
     * @param onOutput called on every line of combined stdout/stderr, on the process's own
     *   thread -- callers must hop to the EDT themselves before touching Swing.
     * @param onFinished called once with the full output and exit code when the process ends.
     */
    fun run(
        project: Project,
        taskPath: String = "fluxDeploy",
        onOutput: (String) -> Unit,
        onFinished: (String, Int) -> Unit,
    ) {
        // NOT project.basePath -- the IDE project root and the Gradle build root differ whenever
        // an FTC project is nested in a larger repo. Assuming they were the same is what produced
        // "Directory '…/ftc-flux' does not contain a Gradle build" on this plugin's first real run.
        // See GradleProjectLocator.
        val gradleDir = GradleProjectLocator.resolve(project)
        if (gradleDir == null) {
            onOutput(
                "Flux: couldn't find a Gradle build to run.\n" +
                    "  Looked under: ${project.basePath}\n" +
                    "  Fix: click \"Change…\" next to the project path in the Flux tool window and\n" +
                    "       pick the folder containing your FTC project's settings.gradle.\n",
            )
            onFinished("", -1)
            return
        }
        val basePath = gradleDir.absolutePath

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val wrapperName = if (isWindows) "gradlew.bat" else "gradlew"
        val wrapper = File(basePath, wrapperName)

        val commandLine = if (wrapper.isFile) {
            if (!isWindows && !wrapper.canExecute()) wrapper.setExecutable(true)
            GeneralCommandLine(wrapper.absolutePath, taskPath, "--console=plain")
        } else {
            // Fall back to a `gradle` on PATH if the project has no wrapper checked in. Every
            // module under this repo does ship one (see flux-gradle/gradlew), so this is a
            // safety net for other projects applying dev.flux.load, not the common case.
            LOG.info("No $wrapperName at $basePath; falling back to `gradle` on PATH")
            GeneralCommandLine("gradle", taskPath, "--console=plain")
        }
        commandLine.workDirectory = File(basePath)
        commandLine.charset = Charsets.UTF_8

        // Pin the JDK for the Gradle subprocess. A subprocess inherits the IDE's environment, and
        // that is regularly a JDK the FTC build cannot use -- which fails with the thoroughly
        // unhelpful "Unsupported class file major version". See JdkLocator.
        val jdk = JdkLocator.findSuitableJdk()
        if (jdk != null) {
            commandLine.withEnvironment("JAVA_HOME", jdk.absolutePath)
            LOG.info("Running Gradle with JAVA_HOME=${jdk.absolutePath}")
        } else {
            LOG.info("No JDK 17..21 found; letting Gradle inherit the environment")
        }

        val handler = try {
            OSProcessHandler(commandLine)
        } catch (e: Exception) {
            onOutput("Flux: failed to launch Gradle: ${e.message}\n")
            onFinished("", -1)
            return
        }

        onOutput(
            if (jdk != null) "Flux: ${gradleDir.absolutePath} (JDK ${jdk.name})\n"
            else "Flux: ${gradleDir.absolutePath}\n",
        )

        val fullOutput = StringBuilder()
        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                fullOutput.append(event.text)
                onOutput(event.text)
            }

            override fun processTerminated(event: ProcessEvent) {
                val text = fullOutput.toString()
                if (text.contains(JdkLocator.VERSION_ERROR_MARKER)) {
                    // Gradle names neither the offending JDK nor the fix, so say both.
                    onOutput(
                        "\nFlux: that failure means Gradle ran on a JDK the FTC build can't use.\n" +
                            "  Flux looked for JDK 17-21 and " +
                            (if (jdk != null) "used ${jdk.absolutePath}, which was still rejected.\n"
                            else "couldn't find one.\n") +
                            "  Fix: install a JDK 17 and/or set Settings -> Build, Execution, Deployment\n" +
                            "       -> Build Tools -> Gradle -> Gradle JDK to it.\n",
                    )
                }
                onFinished(text, event.exitCode)
            }
        })
        handler.startNotify()
    }

    /** Runs the given block on the EDT, for UI callbacks fired from a process thread. */
    fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block)
    }
}
