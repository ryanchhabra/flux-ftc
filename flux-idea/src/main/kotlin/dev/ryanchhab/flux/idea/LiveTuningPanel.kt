package dev.ryanchhab.flux.idea

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * "Live Tuning" section of the Flux tool window -- live-tuning.md §6, the bidirectional feature
 * this whole task is about. A second, collapsible section under the existing Deploy UI (not a
 * separate tool window, not a gutter widget -- see the brief).
 *
 * Flow:
 *  1. **Refresh** scans the resolved Gradle project's sources for every eligible `@FluxLive`
 *     field ([FluxLiveSourceScanner]) and runs `./gradlew fluxRead` to get the robot's current
 *     values ([FluxReadOutputParser]), then renders one row per field: name on the left, a single
 *     control on the right -- a checkbox for booleans, a text box for everything else. No
 *     sliders: per the owner, they were useless for this.
 *  2. Committing a text box (Enter or focus loss) or toggling a checkbox pushes the new value to
 *     the robot directly ([LiveTunePusher] -- adb push + broadcast, no Gradle) AND writes it into
 *     the source file ([SourceFieldWriter]), so the robot and the file never disagree.
 *  3. **Reset** restores every field to the values as of the last successful `fluxDeploy` (the
 *     snapshot [FluxService] captures the moment a deploy succeeds -- see its doc), after a
 *     confirmation dialog, since it discards whatever tuning happened since then.
 *
 * Row layout: [rowsPanel] is a single [GridBagLayout] table, not a stack of independently-built
 * rows -- that's what makes the control column line up across every row regardless of how wide
 * each field's name or value happens to be (a `BoxLayout` per row cannot do this; each row would
 * size itself independently).
 */
class LiveTuningPanel(private val project: Project) : JPanel() {

    private val headerLabel = JBLabel("▾ Live Tuning").apply {
        font = font.deriveFont(Font.BOLD)
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    }
    private val refreshButton = JButton("Refresh").apply { icon = AllIcons.Actions.Refresh }
    private val resetButton = JButton("Reset").apply { icon = AllIcons.Actions.Rollback }
    private val statusLabel = JBLabel(" ").apply { foreground = JBColor.GRAY }

    private val rowsPanel = JPanel(GridBagLayout()).apply {
        alignmentX = LEFT_ALIGNMENT
    }
    private val rowsScroll = JBScrollPane(rowsPanel).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        // No fixed height: a hard 220px cap starved the field list while the deploy log below it
        // took the remaining space. Let it ask for what its rows need and shrink gracefully.
        preferredSize = Dimension(10, 260)
    }
    private var expanded = true

    /** Guards Refresh and Reset against overlapping runs (they share [rowsPanel]). */
    private var isBusy = false

    private var currentEntries: List<LiveTuneEntry> = emptyList()

    /**
     * A left-aligned row whose height tracks its content.
     *
     * getMaximumSize() is OVERRIDDEN rather than assigned. Assigning
     * `maximumSize = Dimension(MAX, preferredSize.height)` in an `apply {}` snapshots the height at
     * construction, before the row has been laid out and before a JTextField reports its real
     * height. The cap then comes out too small, BoxLayout hands the row fewer pixels than it needs,
     * and the contents draw past the bottom edge and overlap the next row -- which is exactly what
     * happened on the first build of this panel (and again, separately, on this one -- see the
     * `⚠️ CRITICAL` note in the task brief). Overriding recomputes on every layout pass.
     */
    private fun row(vararg comps: JComponent) = object : JPanel() {
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        alignmentX = LEFT_ALIGNMENT
        comps.forEachIndexed { i, c ->
            if (i > 0) add(Box.createHorizontalStrut(8))
            c.alignmentY = CENTER_ALIGNMENT
            add(c)
        }
        add(Box.createHorizontalGlue())
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.empty(4, 0, 0, 0)

        add(row(headerLabel, refreshButton, resetButton))
        add(Box.createVerticalStrut(4))
        add(row(statusLabel))
        add(Box.createVerticalStrut(4))
        add(rowsScroll)

        headerLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = toggleExpanded()
        })
        refreshButton.addActionListener { refresh() }
        resetButton.addActionListener { resetToLastDeploy() }

        statusLabel.text = "Click Refresh to load @FluxLive fields."
    }

    private fun toggleExpanded() {
        expanded = !expanded
        headerLabel.text = if (expanded) "▾ Live Tuning" else "▸ Live Tuning"
        rowsScroll.isVisible = expanded
        statusLabel.isVisible = expanded
        revalidate()
        repaint()
    }

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        refreshButton.isEnabled = !busy
        resetButton.isEnabled = !busy
    }

    // --------------------------------------------------------------------------------------
    // Refresh
    // --------------------------------------------------------------------------------------

    private fun refresh() {
        if (isBusy) return
        val gradleDir = GradleProjectLocator.resolve(project)
        if (gradleDir == null) {
            statusLabel.text = "No Gradle project found -- set it in the Deploy section above first."
            statusLabel.foreground = JBColor.RED
            return
        }

        setBusy(true)
        statusLabel.text = "Scanning sources..."
        statusLabel.foreground = JBColor.GRAY

        ApplicationManager.getApplication().executeOnPooledThread {
            val scanned = FluxLiveSourceScanner.scan(gradleDir)
            FluxDeployRunner.onEdt {
                if (scanned.isEmpty()) {
                    setBusy(false)
                    currentEntries = emptyList()
                    rowsPanel.removeAll()
                    statusLabel.text = "No @FluxLive fields found under $gradleDir."
                    statusLabel.foreground = JBColor.GRAY
                    rowsPanel.revalidate()
                    rowsPanel.repaint()
                    return@onEdt
                }
                statusLabel.text = "Reading robot values (fluxRead)..."
                runFluxRead(gradleDir, scanned)
            }
        }
    }

    private fun runFluxRead(gradleDir: File, scanned: List<FluxLiveSourceScanner.ScannedField>) {
        FluxDeployRunner.run(
            project = project,
            taskPath = "fluxRead",
            onOutput = { /* fluxRead's log isn't shown here -- the tool window's main log area is
                           for fluxDeploy; a full table re-render below is more useful anyway. */ },
            onFinished = { fullOutput, exitCode ->
                FluxDeployRunner.onEdt {
                    setBusy(false)
                    applyReadResult(scanned, fullOutput, exitCode)
                }
            },
        )
    }

    private fun applyReadResult(scanned: List<FluxLiveSourceScanner.ScannedField>, output: String, exitCode: Int) {
        val readings = FluxReadOutputParser.parse(output)
        val entries = scanned.map { f ->
            val reading = readings[f.label]
            LiveTuneEntry(
                file = f.file,
                fqClassName = f.fqClassName,
                simpleClassName = f.simpleClassName,
                fieldName = f.fieldName,
                type = f.type,
                sourceValue = f.value,
                robotValue = reading?.robotValue,
                differs = reading?.differs ?: false,
            )
        }
        currentEntries = entries

        val diffCount = entries.count { it.differs }
        val readCount = entries.count { it.robotValue != null }
        // Per-row source=/robot= text is gone (owner's spec), so the status line is now the only
        // place the source/robot comparison is surfaced -- summarized as a count rather than a
        // per-field breakdown.
        statusLabel.text = when {
            readCount == 0 && exitCode != 0 -> "fluxRead failed -- robot not connected? (see Tools → Flux Deploy log for gradlew output)"
            readCount == 0 -> "${entries.size} field(s) found, but none read back from the robot."
            diffCount > 0 -> "${entries.size} field(s) · $diffCount differ from the robot"
            else -> "${entries.size} field(s) · in sync with the robot"
        }
        statusLabel.foreground = if (diffCount > 0) JBColor(0xC62828, 0xE57373) else JBColor.GRAY

        renderRows(entries)
    }

    /** Rebuilds [rowsPanel] as a name-left / control-right table. */
    private fun renderRows(entries: List<LiveTuneEntry>) {
        rowsPanel.removeAll()
        entries.forEachIndexed { i, entry ->
            val fieldRow = FieldRow(entry)

            val nameGbc = GridBagConstraints().apply {
                gridx = 0
                gridy = i
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.NONE
                weightx = 0.0
                insets = JBUI.insets(3, 0, 3, 16)
            }
            rowsPanel.add(fieldRow.nameLabel, nameGbc)

            val controlGbc = GridBagConstraints().apply {
                gridx = 1
                gridy = i
                // EAST + weightx=1 is what makes every row's control flush against the same right
                // edge -- "controls right-aligned in a consistent column" from the owner's spec --
                // rather than each control sizing/positioning itself independently.
                anchor = GridBagConstraints.EAST
                fill = GridBagConstraints.NONE
                weightx = 1.0
                insets = JBUI.insets(3, 0, 3, 0)
            }
            rowsPanel.add(fieldRow.control, controlGbc)
        }
        // A trailing weighty=1 filler keeps the real rows pinned to the top of the scroll area
        // instead of GridBagLayout centering the whole block vertically once there's slack space.
        rowsPanel.add(
            Box.createGlue(),
            GridBagConstraints().apply {
                gridx = 0
                gridy = entries.size
                gridwidth = 2
                weighty = 1.0
                fill = GridBagConstraints.VERTICAL
            },
        )
        rowsPanel.revalidate()
        rowsPanel.repaint()
    }

    // --------------------------------------------------------------------------------------
    // Reset -- snapshot-based undo of the current tuning session
    // --------------------------------------------------------------------------------------

    private fun resetToLastDeploy() {
        if (isBusy) return
        val snapshot = project.service<FluxService>().lastDeploySnapshot
        if (snapshot == null) {
            statusLabel.text = "No deploy seen yet this session -- run Deploy once, then Reset can restore to it."
            statusLabel.foreground = JBColor.GRAY
            return
        }

        val confirmed = Messages.showYesNoDialog(
            project,
            "Reset all ${snapshot.size} Live Tuning field(s) to their values as of the last successful " +
                "Deploy? This discards any tuning done since then, on both the robot and the source files.",
            "Reset Live Tuning",
            "Reset",
            "Cancel",
            Messages.getWarningIcon(),
        )
        if (confirmed != Messages.YES) return

        setBusy(true)
        statusLabel.text = "Resetting ${snapshot.size} field(s) to the last deploy's values..."
        statusLabel.foreground = JBColor.GRAY

        ApplicationManager.getApplication().executeOnPooledThread {
            val adb = AdbLocator.locate()
            var pushFailures = 0
            var writeFailures = 0
            for (field in snapshot) {
                val literalForSource = toSourceLiteral(field.type, field.value)
                if (adb != null) {
                    when (LiveTunePusher.push(adb, LiveTunePusher.Field(field.fqClassName, field.fieldName, field.type, field.value))) {
                        is LiveTunePusher.Result.Applied -> Unit
                        else -> pushFailures++
                    }
                } else {
                    pushFailures++
                }
                val wrote = SourceFieldWriter.write(project, field.file, field.simpleClassName, field.fieldName, literalForSource)
                if (!wrote) writeFailures++
            }

            FluxDeployRunner.onEdt {
                setBusy(false)
                if (pushFailures == 0 && writeFailures == 0) {
                    statusLabel.text = "Reset ${snapshot.size} field(s) to the last deploy's values."
                    statusLabel.foreground = JBColor.GRAY
                } else {
                    statusLabel.text = "Reset finished with $pushFailures robot push failure(s) and " +
                        "$writeFailures source write failure(s) -- adb/robot may be disconnected, or a file " +
                        "changed since the snapshot was taken."
                    statusLabel.foreground = JBColor(0xC62828, 0xE57373)
                }
                // Reload from disk/robot so the rows reflect what Reset actually achieved.
                refresh()
            }
        }
    }

    // --------------------------------------------------------------------------------------
    // Per-field row
    // --------------------------------------------------------------------------------------

    /** Owns one field's UI and pushes/writes for it. A fresh instance is built on every Refresh. */
    private inner class FieldRow(private val entry: LiveTuneEntry) {

        val nameLabel = JBLabel(entry.label)
        val control: JComponent = if (entry.isBoolean) buildBooleanControl() else buildTextControl()

        private fun markOk() {
            nameLabel.foreground = null
            nameLabel.toolTipText = null
        }

        private fun markFailed(message: String) {
            nameLabel.foreground = JBColor(0xC62828, 0xE57373)
            nameLabel.toolTipText = message
        }

        private fun buildBooleanControl(): JComponent {
            val adb = AdbLocator.locate()
            val checkBox = JBCheckBox(null, entry.sourceValue.toBoolean())
            if (adb == null) {
                checkBox.toolTipText = "adb not found -- live push is disabled, but toggling still writes the source file."
            }
            checkBox.addItemListener {
                val newValue = checkBox.isSelected.toString()
                commit(adb, newValue, newValue)
            }
            return checkBox
        }

        private fun buildTextControl(): JComponent {
            val adb = AdbLocator.locate()
            val field = JBTextField(entry.sourceValue, 14)
            if (adb == null) {
                field.toolTipText = "adb not found -- live push is disabled, but committing still writes the source file."
            }

            fun commitText() {
                val text = field.text
                if (entry.isNumeric && text.toDoubleOrNull() == null) {
                    // Invalid number typed -- revert rather than push/write garbage.
                    field.text = entry.sourceValue
                    markFailed("\"$text\" is not a valid ${entry.type}")
                    return
                }
                val literal = toSourceLiteral(entry.type, text)
                commit(adb, text, literal)
            }

            field.addActionListener { commitText() }
            field.addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) = commitText()
            })
            return field
        }

        /** Push to the robot, then write the source file. Runs off the EDT. */
        private fun commit(adb: File?, valueForWire: String, literalForSource: String) {
            ApplicationManager.getApplication().executeOnPooledThread {
                var pushed = false
                var pushError: String? = null
                if (adb != null) {
                    when (val result = LiveTunePusher.push(adb, LiveTunePusher.Field(entry.fqClassName, entry.fieldName, entry.type, valueForWire))) {
                        is LiveTunePusher.Result.Applied -> pushed = true
                        is LiveTunePusher.Result.Rejected -> pushError = "robot: ${result.message}"
                        is LiveTunePusher.Result.Failed -> pushError = result.message
                    }
                } else {
                    pushError = "adb not found"
                }

                val wrote = SourceFieldWriter.write(project, entry.file, entry.simpleClassName, entry.fieldName, literalForSource)

                FluxDeployRunner.onEdt {
                    when {
                        !wrote -> markFailed("source write failed -- hit Refresh and retry")
                        !pushed -> markFailed("push failed: ${pushError ?: "unknown"}")
                        else -> markOk()
                    }
                }
            }
        }
    }

    companion object {
        private fun toSourceLiteral(type: String, value: String): String = when (type) {
            "String" -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            "char" -> "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
            else -> value
        }
    }
}
