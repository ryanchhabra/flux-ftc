package dev.flux.gradle

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared, parallel-safe home for per-stage timing (architecture.md §5 — "measure everything").
 *
 * `fluxAssemble`, `fluxDex`, `fluxPush` and `fluxReload` each record their own wall-clock time
 * here; `fluxDeploy` reads it back to print the timing table. A [BuildService] is the
 * configuration-cache-correct way to share mutable state between tasks in Gradle 9 — plain
 * companion-object statics are not safe across configuration-cache-loaded builds.
 */
abstract class FluxTimingService : BuildService<BuildServiceParameters.None> {

    private val stages = ConcurrentHashMap<String, Stage>()

    data class Stage(val elapsedMs: Long, val detail: String?)

    fun record(stage: String, elapsedMs: Long, detail: String? = null) {
        stages[stage] = Stage(elapsedMs, detail)
    }

    fun snapshot(): Map<String, Stage> = LinkedHashMap(stages)

    fun clear() = stages.clear()

    companion object {
        const val STAGE_COMPILE = "Compile"
        const val STAGE_DEX = "Dex"
        const val STAGE_TRANSFER = "Transfer"
        const val STAGE_RELOAD = "Reload"

        /** Tier L (live-tuning.md §3) — push + broadcast, no compile/dex/reload involved. */
        const val STAGE_LIVE_TUNE = "Live tune"
    }
}

/** Small helper so tasks don't repeat the same `System.nanoTime()` bookkeeping. */
inline fun <T> timed(block: () -> T): Pair<T, Long> {
    val start = System.nanoTime()
    val result = block()
    val elapsedMs = (System.nanoTime() - start) / 1_000_000
    return result to elapsedMs
}
