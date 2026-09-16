package dev.ryanchhab.flux.idea

/**
 * Parses the combined stdout of `./gradlew fluxRead` (CONTRACT.md Amendment 4, `FluxRead.kt`'s
 * `printTable`) into a robot-value/differs map, keyed by "SimpleClassName.fieldName" -- the same
 * label `fluxRead` prints, since that task's table has no machine-readable format either (same
 * situation [DeployOutputParser] already deals with for `fluxDeploy`).
 *
 * `fluxRead`'s table has no TYPE column, only FIELD/SOURCE/ROBOT, so this alone is not enough to
 * build a Live Tuning row -- [FluxLiveSourceScanner] supplies the type and the source's authoritative
 * value/location; this parser supplies only what the robot reported and whether it differs.
 *
 * Table shape (see `FluxRead.printTable`):
 * ```
 * Flux live-value read-back:
 *   FIELD                     SOURCE  ROBOT
 *   DriveConstants.kP         0.099   0.15  <- DIFFERS
 *   DriveConstants.kI         0.003   0.003
 * ```
 * Columns are separated by `padEnd(width)` followed by a literal two spaces, so at least two
 * spaces always separate columns; field labels and values never contain whitespace themselves
 * (Amendment 3/4 eligibility: primitive/String/enum, and String values are also printed without
 * surrounding quotes) except pathological String literals containing a space, which is a known,
 * accepted gap -- not worth extra machinery for a table this simple.
 */
object FluxReadOutputParser {

    data class RobotReading(val robotValue: String, val differs: Boolean)

    private val ROW = Regex("""^\s*(\S+)\s{2,}(\S+)\s{2,}(\S+)(\s*<-\s*DIFFERS)?\s*$""")

    /** True when fluxRead reported no eligible @FluxLive fields at all (nothing to tune). */
    fun noFieldsFound(output: String): Boolean =
        output.contains("no eligible @FluxLive fields found")

    /** Keyed by "SimpleClassName.fieldName" (fluxRead's own row label). */
    fun parse(output: String): Map<String, RobotReading> {
        val lines = output.lineSequence().toList()
        val headerIdx = lines.indexOfFirst { it.contains("Flux live-value read-back:") }
        if (headerIdx < 0) return emptyMap()

        val result = LinkedHashMap<String, RobotReading>()
        for (line in lines.drop(headerIdx + 1)) {
            if (line.isBlank()) break // table ends at the first blank line after the header
            val m = ROW.find(line) ?: continue
            val label = m.groupValues[1]
            if (label == "FIELD") continue
            val robotValue = m.groupValues[3]
            val differs = m.groupValues[4].isNotBlank()
            result[label] = RobotReading(robotValue, differs)
        }
        return result
    }
}
