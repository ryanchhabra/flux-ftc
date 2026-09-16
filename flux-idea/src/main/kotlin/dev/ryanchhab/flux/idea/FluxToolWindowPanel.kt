package dev.ryanchhab.flux.idea

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import java.io.File
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator

/**
 * The "Flux" tool window content. Mirrors the mockup in architecture.md §8:
 *
 * ```
 * FTC FLUX
 * ────────────────────────
 * ● Control Hub  192.168.43.1
 *   SDK 12.0.0 · runtime OK
 *
 * [ ⚡ Deploy ]         ⌘⇧D
 *
 * Last: 457 ms · tier 1
 * Changed: 2 classes · 12.4 KB
 * Robot: STOPPED
 * Errors: none
 * ```
 *
 * Simplified for a first version: connection status is adb-only (device id, not SDK/runtime
 * version -- that needs a handshake protocol that doesn't exist yet, see flux-gradle's README
 * "Known limitations"), and there's no per-file change list -- `fluxDeploy`'s own output doesn't
 * report one either. Tier, total ms, per-stage timings, and the raw log are what's actually
 * available today, so that's what this shows.
 */
class FluxToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), FluxListener {

    private val statusLabel = JBLabel("Checking for device...")

    /**
     * Which Gradle build Flux will run in. Shown explicitly because the IDE project root and the
     * Gradle build root are not always the same folder -- assuming they were is what broke this
     * plugin's first real run. Surfacing it means a wrong guess is visible before you click Deploy,
     * rather than showing up as a confusing Gradle error afterwards.
     */
    private val projectDirLabel = JBLabel("")
    private val changeDirButton = JButton("Change...")
    private val deployButton = JButton("⚡ Deploy").apply {
        icon = AllIcons.Actions.Execute
    }
    /**
     * Wi-Fi Direct address of the Control Hub, for the Connect button. Editable because a team can
     * change it, and pre-filled with the address the SDK ships as the default so the common case
     * is one click.
     */
    private val robotAddressField = JBTextField("192.168.43.1", 12)
    private val connectButton = JButton("Connect")

    private val resultLabel = JBLabel(" ").apply {
        font = font.deriveFont(Font.BOLD)
    }
    private val stagesLabel = JBLabel(" ")

    /** Held so the panel can be revalidated after [stagesLabel] changes height. */
    private var resultPanelRef: JPanel? = null
    private val logArea = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        // The tool window is usually docked narrow. Without wrapping, Gradle's long lines push a
        // horizontal scrollbar and the useful part of an error scrolls off to the right.
        lineWrap = true
        wrapStyleWord = false
    }

    init {
        border = JBUI.Borders.empty(8)

        // Every row: X_AXIS BoxLayout, LEFT_ALIGNMENT, trailing glue, and a capped height.
        //
        // Two Swing footguns this avoids, both of which showed up in the first build:
        //  - a bare JPanel() uses a CENTERING FlowLayout, so rows appeared indented rather than
        //    flush left;
        //  - inside a Y_AXIS BoxLayout a row will stretch to its maximumSize, so without the
        //    height cap the buttons grow vertically and the trailing control ("Change...") gets
        //    pushed off the right edge of a narrow docked tool window.
        fun row(vararg comps: JComponent) = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = LEFT_ALIGNMENT
            comps.forEachIndexed { i, c ->
                if (i > 0) add(Box.createHorizontalStrut(8))
                c.alignmentY = CENTER_ALIGNMENT
                add(c)
            }
            add(Box.createHorizontalGlue())
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

        val header = row(statusLabel)

        val deployRow = row(
            deployButton,
            JBLabel("Tools → Flux Deploy").apply { foreground = JBColor.GRAY },
        )

        val projectRow = row(projectDirLabel, changeDirButton)

        // Connecting is a deliberate action, not something Flux does before every deploy: a
        // doomed `adb connect` blocks for 75 seconds, so automating it would cost every team on
        // USB 75 seconds per deploy to save one click per session.
        val connectRow = row(
            JBLabel("Robot over Wi-Fi:"),
            robotAddressField,
            connectButton,
        )

        val resultPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            resultLabel.alignmentX = LEFT_ALIGNMENT
            stagesLabel.alignmentX = LEFT_ALIGNMENT
            add(resultLabel)
            add(stagesLabel)
            // Deliberately NOT capped to preferredSize here, unlike the single-line rows above.
            // stagesLabel is one blank line at construction and grows to ~5 lines once a deploy
            // reports its stage table; freezing the height now would permanently clip it to the
            // first row (observed: only "Compile: 56 ms" ever appeared).
        }
        resultPanelRef = resultPanel

        val liveTuningPanel = LiveTuningPanel(project)

        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            add(header)
            add(Box.createVerticalStrut(8))
            add(deployRow)
            add(Box.createVerticalStrut(4))
            add(projectRow)
            add(Box.createVerticalStrut(4))
            add(connectRow)
            add(Box.createVerticalStrut(8))
            add(resultPanel)
            add(Box.createVerticalStrut(8))
            add(JSeparator())
            liveTuningPanel.alignmentX = LEFT_ALIGNMENT
            add(liveTuningPanel)
        }

        add(top, BorderLayout.NORTH)
        add(JBScrollPane(logArea), BorderLayout.CENTER)

        deployButton.addActionListener { project.service<FluxService>().deploy() }

        connectButton.addActionListener {
            val address = robotAddressField.text.trim()
            if (address.isEmpty()) {
                resultLabel.text = "Enter the robot's address first"
                return@addActionListener
            }
            // Off the EDT: even with the probe this can take a couple of seconds, and freezing the
            // IDE for that is exactly the kind of thing that makes a tool feel broken.
            connectButton.isEnabled = false
            resultLabel.text = "Connecting to $address..."
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = AdbConnector.connect(address)
                ApplicationManager.getApplication().invokeLater {
                    connectButton.isEnabled = true
                    resultLabel.text = when (result) {
                        is AdbConnector.Result.Connected -> "Connected to ${result.target}"
                        is AdbConnector.Result.Unreachable ->
                            "Nothing at ${result.target} — is the robot on, and are you on its Wi-Fi?"
                        is AdbConnector.Result.Failed -> "Could not connect: ${result.detail}"
                        AdbConnector.Result.NoAdb -> "adb not found — install Android SDK platform-tools"
                    }
                }
            }
        }

        changeDirButton.addActionListener {
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select Gradle Project Folder")
                .withDescription("Pick the folder containing your FTC project's settings.gradle")
            FileChooser.chooseFile(descriptor, project, null)?.let { chosen ->
                FluxGradleSettings.getInstance(project).overrideDir = chosen.path
                refreshProjectDir()
            }
        }

        refreshProjectDir()

        val service = project.service<FluxService>()
        onAdbStatus(service.lastAdbStatus)
        service.lastResult?.let { onDeployResult(it) }
        service.addListener(this)
        service.startPolling()
    }

    fun dispose() {
        project.service<FluxService>().removeListener(this)
    }

    override fun onAdbStatus(status: AdbStatus) {
        statusLabel.text = when (status) {
            is AdbStatus.Connected -> "● Connected  ${status.deviceId}"
            AdbStatus.Disconnected -> "○ Disconnected  (no authorized device)"
            AdbStatus.NoAdb -> "○ adb not found -- see flux-idea README"
        }
        statusLabel.foreground = when (status) {
            is AdbStatus.Connected -> JBColor(0x1E8E3E, 0x5FBB6C)
            else -> JBColor.GRAY
        }
    }

    override fun onDeployStarted() {
        deployButton.isEnabled = false
        deployButton.text = "Deploying..."
        resultLabel.text = " "
        stagesLabel.text = " "
        logArea.text = ""
    }

    override fun onDeployOutput(text: String) {
        logArea.append(text)
        logArea.caretPosition = logArea.document.length
    }

    override fun onDeployResult(result: DeployResult) {
        deployButton.isEnabled = true
        deployButton.text = "⚡ Deploy"

        val stagesText = StringBuilder()
        when (result.outcome) {
            DeployOutcome.SUCCESS -> {
                // The parsed tier label already reads "tier 1 · hot" -- prefixing another
                // "tier " produced "tier tier 1 · hot".
                resultLabel.text = "Last: ${result.totalMs ?: "?"} ms · ${result.tierLabel ?: "?"}"
                resultLabel.foreground = JBColor(0x1E8E3E, 0x5FBB6C)
                if (!result.fieldsSet.isNullOrBlank()) {
                    stagesText.append("Fields set: ${result.fieldsSet}\n")
                }
                for (stage in result.stages) {
                    stagesText.append("  ${stage.name}: ${stage.ms} ms\n")
                }
                if (!result.buildId.isNullOrBlank()) {
                    stagesText.append("BUILD_ID ${result.buildId}")
                }
            }
            DeployOutcome.NOOP -> {
                resultLabel.text = "Last: no-op (nothing changed)"
                resultLabel.foreground = JBColor.GRAY
            }
            DeployOutcome.BLOCKED -> {
                resultLabel.text = "Blocked: ${result.blockedReason ?: "unsafe to hot-deploy"}"
                resultLabel.foreground = JBColor(0xC62828, 0xE57373)
                stagesText.append("File: ${result.blockedFile ?: "?"}\n")
                stagesText.append("Fix:  ${result.blockedFix ?: "./gradlew installDebug"}")
            }
            DeployOutcome.FAILED -> {
                resultLabel.text = "Deploy failed -- see log below"
                resultLabel.foreground = JBColor(0xC62828, 0xE57373)
            }
            DeployOutcome.RUNNING -> Unit
        }
        stagesLabel.text = "<html>${stagesText.toString().replace("\n", "<br>")}</html>"
        // The label just changed line count; without this the container keeps its old bounds and
        // the extra rows are laid out but never drawn.
        resultPanelRef?.revalidate()
        resultPanelRef?.repaint()
        revalidate()
        repaint()
    }

    /**
     * Shows the Gradle build directory Flux resolved, relative to the IDE project root when it sits
     * inside it (shorter and easier to sanity-check at a glance), and flags when the value came
     * from an explicit override rather than auto-detection.
     */
    private fun refreshProjectDir() {
        val resolved = GradleProjectLocator.resolve(project)
        if (resolved == null) {
            projectDirLabel.text = "No Gradle build found - click Change..."
            projectDirLabel.foreground = com.intellij.ui.JBColor.RED
            return
        }
        val base = project.basePath?.let { File(it) }
        val shown = if (base != null && resolved.absolutePath.startsWith(base.absolutePath)) {
            resolved.absolutePath.removePrefix(base.absolutePath).trimStart(File.separatorChar)
                .ifEmpty { "." }
        } else {
            resolved.absolutePath
        }
        val suffix = if (GradleProjectLocator.isOverridden(project)) " (set manually)" else ""
        projectDirLabel.text = "Gradle project: $shown$suffix"
        projectDirLabel.foreground = com.intellij.ui.JBColor.GRAY
        projectDirLabel.toolTipText = resolved.absolutePath
    }

}
