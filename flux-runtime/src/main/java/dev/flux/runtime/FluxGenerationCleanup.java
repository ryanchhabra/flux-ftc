package dev.flux.runtime;

import com.qualcomm.robotcore.util.RobotLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GAP 1 from the pre-hardware safety pass: thread and listener hygiene, run immediately BEFORE a
 * new generation's {@link FluxClassLoader} is installed. See docs/research/risks.md §3
 * ("Leak vectors, worst first") and docs/design/architecture.md §6 ("Thread and listener
 * hygiene") — this class is the concrete implementation of both bullets there.
 *
 * <p><b>Why this matters more once real hardware is attached</b> (risks.md §3 item 1): ART does
 * NOT stop threads when a classloader is discarded — it keeps them running with the outgoing
 * loader as their context. A TeamCode control loop spawned on a background thread survives a
 * reload and can keep issuing motor commands built against old-generation code, right alongside
 * a brand-new generation that thinks it's the only thing driving the robot.
 *
 * <h2>Threads — implemented</h2>
 * Every live thread in the VM is enumerated ({@link Thread#getAllStackTraces()}), but only a
 * thread whose identity is provably tied to the SPECIFIC outgoing {@link FluxClassLoader}
 * instance is touched — see {@link #belongsToGeneration} for the exact, deliberately narrow,
 * check. This is a reference-identity check against one object, never a name or package
 * heuristic, specifically so an SDK thread, a system thread (Binder, GC, the app's main thread,
 * etc.), or a thread from a DIFFERENT generation can never match by accident. Per the task brief:
 * killing an SDK or system thread would be far worse than the stale-thread problem this fixes, so
 * a missed stale thread (logged loudly) is an acceptable failure mode here and a false positive
 * is not.
 *
 * <h2>Listeners — investigated, NOT implemented, and here is exactly why</h2>
 * {@code OpModeManagerNotifier} (verified via {@code javap -p} against RobotCore 12.0.0) exposes
 * exactly two public methods:
 * <pre>
 * public abstract OpMode registerListener(OpModeManagerNotifier.Notifications);
 * public abstract void unregisterListener(OpModeManagerNotifier.Notifications);
 * </pre>
 * There is no public method anywhere in the SDK to enumerate currently-registered listeners, and
 * to call {@code unregisterListener} at all Flux would need a REFERENCE to the specific listener
 * instance a TeamCode class registered — a reference TeamCode never gives Flux; it hands it
 * directly to the SDK's {@code OpModeManagerImpl}. There is no public getter for that
 * {@code OpModeManagerImpl} instance either.
 *
 * <p>The only place the listener set physically lives is a {@code protected}, non-public field on
 * the concrete implementation, {@code OpModeManagerImpl.listeners}
 * ({@code WeakReferenceSet<Notifications>}, backed by a plain {@code WeakHashMap}) — also verified
 * via {@code javap -p}. Reaching it would require a SECOND private/non-public reflection target,
 * which architecture.md §3.2 explicitly budgets at exactly one (already spent on
 * {@code ClassManager.processAllClassesCalled} in {@link FluxReloadEngine}). Per the task's hard
 * constraint on that budget, this class does not spend it — reflecting into SDK internals to claw
 * back a QoL feature is exactly the brittleness (~12 reflections across 5+ classes) that
 * architecture.md §1 calls out as Sloth's mistake and Flux's reason to exist.
 *
 * <p>One mitigating fact worth recording here, found during this same investigation: the backing
 * store IS a {@code WeakHashMap} — the SDK itself only holds listeners weakly. An outgoing
 * generation's listener instance is NOT pinned forever by this registry; once nothing else in the
 * process holds a strong reference to it (typically once its owning OpMode instance itself is
 * unreachable), the garbage collector is free to drop it and the entry disappears on its own. That
 * bounds the leak — it does not eliminate the window during which a stale listener can still fire,
 * which is the real risk risks.md §3 item 2 describes (an old-generation listener observes an
 * INIT/START/STOP meant for the new generation). This is logged every reload so the gap stays
 * visible rather than silently assumed-fixed.
 */
final class FluxGenerationCleanup {

    private static final String TAG = "FLUX";

    /** See the listener note in {@link #cleanupOutgoingGeneration}: logged once per process, not per reload. */
    private static boolean listenerLimitationLogged = false;

    /**
     * TOTAL join budget across every outgoing-generation thread found, not a per-thread timeout.
     * architecture.md §4's Tier 1 target is "&lt;1s end to end" for a hot reload; a cleanup pass
     * that could itself block for seconds per thread would defeat that, and per the task brief a
     * reload must never hang over this.
     */
    private static final long JOIN_BUDGET_MILLIS = 300;

    private FluxGenerationCleanup() {
    }

    /**
     * Called by {@link FluxReloadEngine} once per reload attempt, immediately before it asks the
     * SDK to build the new generation's {@link FluxClassLoader}.
     *
     * @param outgoingLoader the previous generation's loader (i.e. {@code FluxReloadEngine}'s
     *                       {@code currentGenerationLoader}), or {@code null} on the very first
     *                       reload of the process, when there is no outgoing generation yet.
     */
    static void cleanupOutgoingGeneration(ClassLoader outgoingLoader) {
        if (outgoingLoader == null) {
            // Nothing has ever been reloaded yet in this process — there is no outgoing
            // generation, and therefore nothing that could be leaking. Skip silently; logging
            // "cleaned up 0 threads" on the very first deploy of a session is just noise.
            return;
        }

        List<Thread> owned = findThreadsOwnedBy(outgoingLoader);
        if (owned.isEmpty()) {
            RobotLog.ii(TAG, "FLUX: no live threads from the outgoing generation");
        } else {
            interruptAndJoin(owned);
        }

        // See class javadoc "Listeners — investigated, NOT implemented, and here is exactly why".
        //
        // Logged ONCE per app process, not per reload. The gap is real and worth surfacing, but a
        // multi-line paragraph on every single deploy is the kind of noise that trains people to
        // stop reading the FLUX log entirely — and that log is exactly where they need to be
        // looking when a reload misbehaves. Once per session keeps the information and keeps the
        // signal-to-noise ratio of everything around it intact.
        if (listenerLimitationLogged) {
            return;
        }
        listenerLimitationLogged = true;
        RobotLog.ii(TAG, "FLUX: listener hygiene: no public SDK API exists to enumerate or "
                + "unregister OpModeManagerNotifier listeners registered by TeamCode; not "
                + "attempting it rather than spending a second private-reflection budget slot "
                + "(architecture.md §3.2) — see FluxGenerationCleanup's class javadoc");
    }

    private static List<Thread> findThreadsOwnedBy(ClassLoader outgoingLoader) {
        List<Thread> owned = new ArrayList<>();
        Thread self = Thread.currentThread();
        // getAllStackTraces().keySet() is a snapshot of literally every live thread in the VM —
        // SDK threads, system threads (Binder, GC, FinalizerDaemon, the app main thread), and any
        // TeamCode threads from any generation. That is exactly why belongsToGeneration() below
        // must be conservative: everything walked here is a CANDIDATE, not an assumption.
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        for (Thread t : all.keySet()) {
            if (t == self) {
                // Never consider the thread that is running this very reload. (In practice this
                // is an SDK/broadcast-dispatch thread, not a TeamCode one, so belongsToGeneration
                // would almost certainly return false anyway — this is belt-and-suspenders.)
                continue;
            }
            if (belongsToGeneration(t, outgoingLoader)) {
                owned.add(t);
            }
        }
        return owned;
    }

    /**
     * A thread counts as belonging to {@code outgoingLoader} if EITHER:
     * <ul>
     *   <li>its context classloader IS that exact loader instance (the common case — a thread
     *       spawned from TeamCode inherits the context classloader of its creator, which for
     *       TeamCode is always the generation's {@link FluxClassLoader}), OR</li>
     *   <li>the concrete {@code Class} of the {@code Thread}/{@code Runnable} it is running was
     *       ITSELF defined by that loader (covers a TeamCode class that extends {@code Thread}
     *       and never bothers to set its context classloader explicitly).</li>
     * </ul>
     * Both are reference-identity ({@code ==}) checks against one specific object — the exact
     * {@link FluxClassLoader} instance created for the outgoing generation, never a class-name
     * prefix or package heuristic. {@code FluxClassLoader} instances are never reused across
     * generations (see {@link FluxReloadEngine}, a fresh one is created every reload), so this
     * cannot accidentally match a thread from the CURRENT generation, an SDK thread, or a system
     * thread — every one of those has a different (or no) classloader identity.
     */
    private static boolean belongsToGeneration(Thread t, ClassLoader outgoingLoader) {
        try {
            if (t.getContextClassLoader() == outgoingLoader) {
                return true;
            }
            return t.getClass().getClassLoader() == outgoingLoader;
        } catch (SecurityException noAccess) {
            // Defensive only — the RC app runs with no SecurityManager, so this should not be
            // reachable, but "can't tell" must resolve to "leave it alone", never to "assume ours".
            return false;
        }
    }

    private static void interruptAndJoin(List<Thread> threads) {
        for (Thread t : threads) {
            RobotLog.ww(TAG, "FLUX: interrupting outgoing-generation thread \"%s\" (id=%d)", t.getName(), t.getId());
            t.interrupt();
        }

        // One shared deadline for the whole batch — see JOIN_BUDGET_MILLIS javadoc. A thread that
        // is already dead by the time we get to it costs ~0ms, so this only actually blocks on
        // threads that are slow to notice the interrupt.
        long deadline = System.currentTimeMillis() + JOIN_BUDGET_MILLIS;
        for (Thread t : threads) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break; // Budget exhausted. Every thread was already interrupted above; we just
                       // stop waiting for join() to observe it.
            }
            try {
                t.join(remaining);
            } catch (InterruptedException selfInterrupted) {
                // The reload thread itself got interrupted while waiting — restore the flag and
                // stop waiting. Every outgoing thread was already asked to stop; further joins are
                // best-effort politeness, not correctness, so bailing out here is safe.
                Thread.currentThread().interrupt();
                break;
            }
        }

        List<String> stillAlive = new ArrayList<>();
        for (Thread t : threads) {
            if (t.isAlive()) {
                stillAlive.add(t.getName());
            }
        }
        if (stillAlive.isEmpty()) {
            RobotLog.ii(TAG, "FLUX: %d outgoing-generation thread(s) stopped cleanly", threads.size());
        } else {
            // Per the task brief: do NOT fail the reload over this — but make it unmissable. This
            // is risks.md §3 item 1 actually happening: a thread from the outgoing generation is
            // still alive, running old-generation code, possibly still issuing hardware commands.
            RobotLog.ee(TAG, "FLUX: %d outgoing-generation thread(s) did NOT stop within %dms and "
                            + "are still running with old code: %s — continuing the reload anyway "
                            + "(per architecture.md §6, a reload must not hang), but this generation "
                            + "may still be doing work; if it drives hardware, restart the RC app",
                    stillAlive.size(), JOIN_BUDGET_MILLIS, stillAlive);
        }
    }
}
