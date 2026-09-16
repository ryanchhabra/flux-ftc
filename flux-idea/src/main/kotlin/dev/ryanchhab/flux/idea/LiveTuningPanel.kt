package dev.ryanchhab.flux.idea

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Font
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSlider

/**
 * "Live Tuning" section of the Flux tool window -- live-tuning.md §6, the bidirectional feature
 * this whole task is about. A second, collapsible section under the existing Deploy UI (not a
 * separate tool window, not a gutter widget -- see the brief).
 *
 * Flow:
 *  1. **Refresh** scans the resolved Gradle project's sources for every eligible `@FluxLive`
 *     field ([FluxLiveSourceScanner]) and runs `./gradlew fluxRead` to get the robot's current
 *     values ([FluxReadOutputParser]), then renders one row per field.
 *  2. Dragging a numeric field's slider pushes to the robot live, throttled by
 *     [ThrottledFieldPusher] (adb push + broadcast directly -- no Gradle -- see that class and
 *     [LiveTunePusher] for the measured latency this achieves).
 *  3. Releasing the slider, or committing a text box / checkbox, writes the new value into the
 *     source file via [SourceFieldWriter] AND does one final direct push, so the robot and the
 *     file are never left disagreeing with each other because a throttled push got dropped.
 *
 * Row layout follows this file's sibling [FluxToolWindowPanel]'s established convention (see its
 * `row()` helper doc): X_AXIS BoxLayout, LEFT_ALIGNMENT, trailing glue, height-capped. Each field
 * gets two such rows stacked (label+status, then its control), inside a height-capped
 * [JBScrollPane] so a project with many `@FluxLive` fields doesn't take over the whole tool
 * window -- matches why `logArea` below it is scrollable rather than letting the panel grow
 * unbounded.
 */
class LiveTuningPanel(private val project: Project) : JPanel() {

    private val headerLabel = JBLabel("▾ Live Tuning").apply {
        font = font.deriveFont(Font.BOLD)
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    }
    private val refreshButton = JButton("Refresh").apply { icon = AllIcons.Actions.Refresh }
    private val statusLabel = JBLabel(" ").apply { foreground = JBColor.GRAY }

    private val rowsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
    }
    private val rowsScroll = JBScrollPane(rowsPanel).apply {
        border = JBUI.Borders.empty()
        preferredSize = Dimension(10, 220)
        maximumSize = Dimension(Int.MAX_VALUE, 220)
    }
    private var expanded = true

    private var isRefreshing = false

    private fun row(vararg comps: JComponent) = JPanel().apply {
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

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.empty(4, 0, 0, 0)

        add(row(headerLabel, refreshButton))
        add(Box.createVerticalStrut(4))
        add(row(statusLabel))
        add(Box.createVerticalStrut(4))
        add(rowsScroll)

        headerLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = toggleExpanded()
        })
        refreshButton.addActionListener { refresh() }

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

    // --------------------------------------------------------------------------------------
    // Refresh
    // --------------------------------------------------------------------------------------

    private fun refresh() {
        if (isRefreshing) return
        val gradleDir = GradleProjectLocator.resolve(project)
        if (gradleDir == null) {
            statusLabel.text = "No Gradle project found -- set it in the Deploy section above first."
            statusLabel.foreground = JBColor.RED
            return
        }

        isRefreshing = true
        refreshButton.isEnabled = false
        statusLabel.text = "Scanning sources..."
        statusLabel.foreground = JBColor.GRAY

        ApplicationManager.getApplication().executeOnPooledThread {
            val scanned = FluxLiveSourceScanner.scan(gradleDir)
            FluxDeployRunner.onEdt {
                if (scanned.isEmpty()) {
                    isRefreshing = false
                    refreshButton.isEnabled = true
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

    private fun runFluxRead(gradleDir: java.io.File, scanned: List<FluxLiveSourceScanner.ScannedField>) {
        FluxDeployRunner.run(
            project = project,
            taskPath = "fluxRead",
            onOutput = { /* fluxRead's log isn't shown here -- the tool window's main log area is
                           for fluxDeploy; a full table re-render below is more useful anyway. */ },
            onFinished = { fullOutput, exitCode ->
                FluxDeployRunner.onEdt {
                    isRefreshing = false
                    refreshButton.isEnabled = true
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

        val diffCount = entries.count { it.differs }
        val readCount = entries.count { it.robotValue != null }
        statusLabel.text = when {
            readCount == 0 && exitCode != 0 -> "fluxRead failed -- robot not connected? (see Tools → Flux Deploy log for gradlew output)"
            readCount == 0 -> "${entries.size} field(s) found, but none read back from the robot."
            diffCount > 0 -> "${entries.size} field(s) · $diffCount differ from the robot"
            else -> "${entries.size} field(s) · in sync with the robot"
        }
        statusLabel.foreground = if (diffCount > 0) JBColor(0xC62828, 0xE57373) else JBColor.GRAY

        rowsPanel.removeAll()
        for (entry in entries) {
            rowsPanel.add(FieldRow(entry).component)
            rowsPanel.add(Box.createVerticalStrut(6))
        }
        rowsPanel.revalidate()
        rowsPanel.repaint()
    }

    // --------------------------------------------------------------------------------------
    // Per-field row
    // --------------------------------------------------------------------------------------

    /** Owns one field's UI and pushes/writes for it. A fresh instance is built on every Refresh. */
    private inner class FieldRow(private val entry: LiveTuneEntry) {

        private val infoLabel = JBLabel().apply { font = font.deriveFont(Font.PLAIN, 11f) }
        private var pusher: ThrottledFieldPusher? = null

        /** Guards against the programmatic slider/textfield updates each other's listeners fire. */
        private var syncing = false

        val component: JPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            infoLabel.alignmentX = LEFT_ALIGNMENT
            add(infoLabel)
            add(Box.createVerticalStrut(2))
            add(buildControlRow())
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

        init {
            updateInfoLabel(entry.sourceValue, entry.robotValue, entry.differs, pending = false)
        }

        private fun updateInfoLabel(sourceValue: String, robotValue: String?, differs: Boolean, pending: Boolean) {
            val robotText = robotValue ?: "(not read)"
            val marker = if (differs) "  DIFFERS" else ""
            val pendingText = if (pending) "  (pushing...)" else ""
            infoLabel.text = "${entry.label} — source=$sourceValue  robot=$robotText$marker$pendingText"
            infoLabel.foreground = if (differs) JBColor(0xC62828, 0xE57373) else JBColor.GRAY
        }

        private fun buildControlRow(): JPanel {
            val adb = AdbLocator.locate()

            return when {
                entry.isBoolean -> buildBooleanControl(adb)
                entry.isNumeric -> buildNumericControl(adb)
                else -> buildTextControl(adb)
            }
        }

        private fun buildBooleanControl(adb: java.io.File?): JPanel {
            val checkBox = JBCheckBox(entry.fieldName, entry.sourceValue.toBoolean())
            checkBox.addItemListener {
                if (syncing) return@addItemListener
                val newValue = checkBox.isSelected.toString()
                commit(adb, newValue, newValue)
            }
            return row(checkBox)
        }

        private fun buildTextControl(adb: java.io.File?): JPanel {
            val field = JBTextField(entry.sourceValue, 14)
            fun commitText() {
                val text = field.text
                val literal = toSourceLiteral(entry.type, text)
                commit(adb, text, literal)
            }
            field.addActionListener { commitText() }
            field.addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) = commitText()
            })
            return row(field)
        }

        private fun buildNumericControl(adb: java.io.File?): JPanel {
            val current = entry.sourceValue.toDoubleOrNull() ?: 0.0
            val isIntegral = entry.type != "float" && entry.type != "double"

            // Range derived from the current value (brief: "0..2x for a positive number, with a
            // sane floor/ceiling" -- no range-configuration UI). Zero/negative get a fixed sane
            // window rather than degenerating to a zero-width range.
            val (rangeMin, rangeMax) = when {
                current > 0 -> 0.0 to current * 2
                current < 0 -> current * 2 to 0.0
                else -> -10.0 to 10.0
            }

            val slider = JSlider(0, SLIDER_RESOLUTION, valueToSlider(current, rangeMin, rangeMax))
            val textField = JBTextField(formatNumber(entry.type, current), 8)

            if (adb == null) {
                textField.toolTipText = "adb not found -- live push while dragging is disabled, but committing still writes the source file."
            }

            slider.addChangeListener {
                if (syncing) return@addChangeListener
                val value = sliderToValue(slider.value, rangeMin, rangeMax)
                val formatted = formatNumber(entry.type, value)
                syncing = true
                textField.text = formatted
                syncing = false

                if (slider.valueIsAdjusting) {
                    if (adb != null) {
                        if (pusher == null) pusher = ThrottledFieldPusher(adb) { result -> onPushResult(result, formatted) }
                        pusher?.request(LiveTunePusher.Field(entry.fqClassName, entry.fieldName, entry.type, formatted))
                    }
                    updateInfoLabel(formatted, entry.robotValue, differs = false, pending = true)
                } else {
                    // Drag released (or a programmatic non-adjusting set, which only happens from
                    // our own syncing=true-guarded code above, so this is always a real release).
                    commit(adb, formatted, formatted)
                }
            }

            textField.addActionListener { commitTypedNumber(textField, slider, rangeMin, rangeMax, isIntegral, adb) }
            textField.addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) = commitTypedNumber(textField, slider, rangeMin, rangeMax, isIntegral, adb)
            })

            return row(slider, textField)
        }

        private fun commitTypedNumber(
            textField: JBTextField,
            slider: JSlider,
            rangeMin: Double,
            rangeMax: Double,
            isIntegral: Boolean,
            adb: java.io.File?,
        ) {
            val parsed = textField.text.toDoubleOrNull()
            if (parsed == null) {
                textField.text = formatNumber(entry.type, sliderToValue(slider.value, rangeMin, rangeMax))
                return
            }
            // Text box can override beyond the slider's derived range (brief) -- clamp only the
            // slider's own display, never the value actually sent/written.
            syncing = true
            slider.value = valueToSlider(parsed.coerceIn(rangeMin, rangeMax), rangeMin, rangeMax)
            syncing = false
            val formatted = formatNumber(entry.type, parsed)
            textField.text = formatted
            commit(adb, formatted, formatted)
        }

        private fun onPushResult(result: LiveTunePusher.Result, formatted: String) {
            when (result) {
                is LiveTunePusher.Result.Applied -> updateInfoLabel(formatted, formatted, differs = false, pending = false)
                is LiveTunePusher.Result.Rejected -> updateInfoLabel(formatted, entry.robotValue, differs = true, pending = false)
                is LiveTunePusher.Result.Failed -> updateInfoLabel(formatted, entry.robotValue, differs = true, pending = false)
            }
        }

        /** Final commit: one direct (non-throttled) push, then write the source file. Runs off the EDT. */
        private fun commit(adb: java.io.File?, valueForWire: String, literalForSource: String) {
            updateInfoLabel(literalForSource, entry.robotValue, differs = false, pending = true)
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
                    val robotShown = if (pushed) valueForWire else entry.robotValue
                    val differs = !pushed
                    val suffix = buildString {
                        if (!pushed) append(" (push failed: ${pushError ?: "unknown"})")
                        if (!wrote) append(" (source write failed -- hit Refresh and retry)")
                    }
                    infoLabel.text = "${entry.label} — source=$literalForSource  robot=${robotShown ?: "(not read)"}$suffix"
                    infoLabel.foreground = if (differs || !wrote) JBColor(0xC62828, 0xE57373) else JBColor(0x1E8E3E, 0x5FBB6C)
                }
            }
        }

        private fun toSourceLiteral(type: String, value: String): String = when (type) {
            "String" -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            "char" -> "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
            else -> value
        }
    }

    companion object {
        private const val SLIDER_RESOLUTION = 1000

        private fun valueToSlider(value: Double, min: Double, max: Double): Int {
            if (max <= min) return 0
            val frac = ((value - min) / (max - min)).coerceIn(0.0, 1.0)
            return (frac * SLIDER_RESOLUTION).toInt()
        }

        private fun sliderToValue(sliderPos: Int, min: Double, max: Double): Double =
            min + (sliderPos.toDouble() / SLIDER_RESOLUTION) * (max - min)

        private fun formatNumber(type: String, value: Double): String = when (type) {
            "int", "long", "short", "byte" -> value.toLong().toString()
            else -> {
                // Trim to a readable precision without losing typical tuning granularity, then
                // strip trailing zeros/dot (JSlider's own resolution is 1/1000th of the range, so
                // more than ~6 significant digits would be false precision anyway).
                val s = "%.6f".format(value).trimEnd('0').trimEnd('.')
                if (s.isEmpty() || s == "-") "0" else s
            }
        }
    }
}
