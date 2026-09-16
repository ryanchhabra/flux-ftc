package dev.flux.idea

/**
 * Parses the combined stdout/stderr of `./gradlew fluxDeploy` into a [DeployResult].
 *
 * Three shapes come out of `FluxDeploy.kt`'s `report()` (`flux-gradle`), matched here in the same
 * order CONTRACT.md lists the result states in:
 *  1. Blocked: `GradleException("Flux cannot hot-deploy this change...")`, surfaced by Gradle as
 *     "* What went wrong:" followed by the message, with "Reason:", "File:", "Fix:" lines.
 *  2. No-op: the single lifecycle line "Flux: nothing changed since the last deploy...".
 *  3. Success (hot or live-tune): the "FTC Flux  ●  <addr>  ·  <tier>" header, a timing table,
 *     and a "Total ... ms" line.
 * Anything else (a real compile error, a crashed daemon, etc.) falls through to FAILED with the
 * raw output attached -- honest > a silently-wrong parse.
 */
object DeployOutputParser {

    private val stageLine = Regex("""^\s*(\S.*?\S|\S)\s{2,}(\d+) ms(?:\s*\(([^)]*)\))?\s*$""")
    private val totalLine = Regex("""^\s*Total\s+(\d+) ms(?:\s*·\s*BUILD_ID\s*(\S+))?\s*$""")
    private val headerLine = Regex("""^FTC Flux\s+●\s+(\S+)\s+·\s+(.+)$""")
    private val fieldsLine = Regex("""^\s*Fields set:\s*(.+)$""")
    private val reasonLine = Regex("""^\s*Reason:\s*(.+)$""")
    private val fileLine = Regex("""^\s*File:\s*(.+)$""")
    private val fixLine = Regex("""^\s*Fix:\s*(.+)$""")

    fun parse(output: String, exitCode: Int): DeployResult {
        if (output.contains("Flux cannot hot-deploy this change.")) {
            var reason: String? = null
            var file: String? = null
            var fix: String? = null
            for (line in output.lineSequence()) {
                reasonLine.find(line)?.let { reason = it.groupValues[1].trim() }
                fileLine.find(line)?.let { file = it.groupValues[1].trim() }
                fixLine.find(line)?.let { fix = it.groupValues[1].trim() }
            }
            return DeployResult(
                outcome = DeployOutcome.BLOCKED,
                blockedReason = reason,
                blockedFile = file,
                blockedFix = fix,
            )
        }

        if (output.contains("nothing changed since the last deploy")) {
            return DeployResult(outcome = DeployOutcome.NOOP)
        }

        val headerMatch = output.lineSequence().firstNotNullOfOrNull { headerLine.find(it) }
        if (headerMatch != null && exitCode == 0) {
            val tier = headerMatch.groupValues[2].trim()
            val stages = mutableListOf<DeployStage>()
            var total: Long? = null
            var buildId: String? = null
            var fields: String? = null
            for (line in output.lineSequence()) {
                fieldsLine.find(line)?.let { fields = it.groupValues[1].trim() }
                totalLine.find(line)?.let {
                    total = it.groupValues[1].toLong()
                    buildId = it.groupValues[2].ifBlank { null }
                }
                if (total == null) {
                    stageLine.find(line)?.let {
                        val name = it.groupValues[1].trim()
                        // Skip the header/total lines themselves and the "────" divider, which
                        // also happens to not match stageLine (no digit run), so no filter needed
                        // beyond not having already found Total.
                        stages += DeployStage(name, it.groupValues[2].toLong(), it.groupValues[3].ifBlank { null })
                    }
                }
            }
            return DeployResult(
                outcome = DeployOutcome.SUCCESS,
                tierLabel = tier,
                stages = stages,
                totalMs = total,
                buildId = buildId,
                fieldsSet = fields,
            )
        }

        return DeployResult(outcome = DeployOutcome.FAILED)
    }
}
