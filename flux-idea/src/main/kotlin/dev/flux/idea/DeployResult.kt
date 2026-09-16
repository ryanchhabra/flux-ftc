package dev.flux.idea

/** One stage row from `fluxDeploy`'s timing table (see `FluxDeploy.kt`'s `report()`). */
data class DeployStage(val name: String, val ms: Long, val detail: String?)

enum class DeployOutcome { SUCCESS, NOOP, BLOCKED, FAILED, RUNNING }

/**
 * The parsed result of one `fluxDeploy` run, built from the plain-text task output --
 * CONTRACT.md defines no machine-readable result format for Phase 0/4, so this reads the exact
 * strings `FluxDeploy.kt` (`flux-gradle`) prints. If that task's report format ever changes,
 * this parser needs to change with it; there's no contract pinning the two together yet.
 */
data class DeployResult(
    val outcome: DeployOutcome,
    val tierLabel: String? = null,
    val stages: List<DeployStage> = emptyList(),
    val totalMs: Long? = null,
    val buildId: String? = null,
    val blockedReason: String? = null,
    val blockedFile: String? = null,
    val blockedFix: String? = null,
    val fieldsSet: String? = null,
) {
    companion object {
        val RUNNING = DeployResult(DeployOutcome.RUNNING)
    }
}
