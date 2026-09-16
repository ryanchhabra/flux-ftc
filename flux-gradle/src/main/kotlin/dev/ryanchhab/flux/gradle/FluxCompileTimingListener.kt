package dev.ryanchhab.flux.gradle

import org.gradle.api.provider.Provider
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFinishEvent
import java.util.concurrent.atomic.AtomicLong

/**
 * Times the "Compile" stage of the timing table (architecture.md §5) by observing the debug
 * variant's own javac/kotlinc task(s) finish — `fluxAssemble` never invokes javac itself (see its
 * class doc), so this is the only way to measure that stage without re-implementing compilation.
 *
 * Registered via [org.gradle.build.event.BuildEventsListenerRegistry], which is the
 * configuration-cache-safe way (Gradle 9) to observe other tasks' execution from a plugin without
 * holding a live `Task`/`Project` reference into task-execution time.
 */
class FluxCompileTimingListener(
    private val relevantTaskPaths: Set<String>,
    private val timingService: Provider<FluxTimingService>,
) : OperationCompletionListener {

    private val accumulatedMs = AtomicLong(0)

    override fun onFinish(event: FinishEvent) {
        if (event !is TaskFinishEvent) return
        val path = event.descriptor.taskPath
        if (path !in relevantTaskPaths) return

        val result = event.result
        val elapsed = result.endTime - result.startTime
        val total = accumulatedMs.addAndGet(elapsed)
        timingService.orNull?.record(FluxTimingService.STAGE_COMPILE, total)
    }
}
