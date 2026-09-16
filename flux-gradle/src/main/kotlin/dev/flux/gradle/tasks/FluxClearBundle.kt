package dev.flux.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * `fluxClearBundle` — requirement D of on-robot persistence (docs/design/CONTRACT.md Amendment 5):
 * `installDebug`/`installRelease` must clear whatever bundle is sitting at the deploy dir, or a
 * stale hot-loaded bundle would silently load right back over a fresh install the moment the RC
 * app restarts (`FluxStartupLoader`'s whole job). A stale bundle winning over a fresh install is
 * worse than the "lost a tuning session to a power cycle" problem persistence exists to fix, since
 * it would look like the install itself did nothing.
 *
 * Deliberately the mirror image of Sloth's `removeSlothRemote` (docs/research/sloth-teardown.md
 * §3: "wired as a dependency of installDebug/installRelease so a full reinstall clears stray
 * hot-reload jars") — same problem, same fix, same place in the task graph, adapted to
 * `finalizedBy` (matching how [dev.flux.gradle.FluxPlugin] already wires [FluxSyncBaseline]) rather
 * than `dependsOn`: the bundle should only be cleared once the install has actually succeeded, not
 * before an install that might fail partway through.
 *
 * `adb shell rm -f` on both CONTRACT.md paths (the live bundle AND the rollback bundle) — leaving
 * `flux_bundle.last.jar` behind would let `FluxStartupLoader`'s rollback path resurrect stale code
 * on the very next restart even though the primary bundle is gone, defeating the point.
 *
 * Best-effort: a missing/unreachable device must never fail `installDebug` itself (the install
 * already succeeded by the time this finalizer runs) — this only logs a warning telling the user
 * to run it manually later.
 */
@DisableCachingByDefault(because = "Side effect is on the robot, not in the build directory. Gradle cannot know whether the device still holds a stale bundle.")
abstract class FluxClearBundle : DefaultTask() {

    @get:Inject
    abstract val exec: ExecOperations

    /** Resolved lazily inside the task action (not an [org.gradle.api.tasks.Input] provider that
     *  might throw at configuration time) — see [dev.flux.gradle.FluxPlugin]'s wiring: adb
     *  resolution failing here must degrade to a warning, never fail the finalized `installDebug`. */
    @get:Internal
    abstract val adbPathOrNull: org.gradle.api.provider.Property<String>

    @get:Input
    abstract val deployLocation: org.gradle.api.provider.Property<String>

    init {
        group = "flux"
        description = "adb shell rm -f the deploy-dir bundle files, so a fresh install is never silently overridden by a stale hot-loaded bundle on the next restart (CONTRACT.md Amendment 5)."
        // Always re-run: the whole point is "did we actually clear the device right now",
        // Gradle's up-to-date tracking has no visibility into remote device state.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun clear() {
        val adb = adbPathOrNull.orNull
        if (adb == null) {
            logger.warn(
                "Flux: could not locate adb to clear the on-robot bundle after install — " +
                    "a stale flux_bundle.jar may still be on the robot and could auto-load on " +
                    "the next restart. Fix: run `adb shell rm -f <deployLocation>/flux_bundle.jar " +
                    "<deployLocation>/flux_bundle.last.jar` manually, or set flux.adbPath.",
            )
            return
        }

        val dir = deployLocation.get()
        val out = ByteArrayOutputStream()
        val result = exec.exec {
            commandLine = listOf(adb, "shell", "rm", "-f", "$dir/flux_bundle.jar", "$dir/flux_bundle.last.jar")
            standardOutput = out
            errorOutput = out
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            // Most commonly: no device attached at all. Non-fatal (see class doc) -- the install
            // itself already succeeded.
            logger.warn(
                "Flux: failed to clear the on-robot bundle after install (adb exit " +
                    "${result.exitValue}): ${out.toString().trim()}\n" +
                    "  A stale hot-loaded bundle may still be on the robot. Fix: reconnect and " +
                    "run this again, or `adb shell rm -f $dir/flux_bundle.jar $dir/flux_bundle.last.jar` manually.",
            )
            return
        }
        logger.lifecycle("Flux: cleared any stale on-robot bundle at $dir — the fresh install will not be shadowed by a hot-loaded generation.")
    }
}
