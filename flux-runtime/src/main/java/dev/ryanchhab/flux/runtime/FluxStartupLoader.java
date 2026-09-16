package dev.ryanchhab.flux.runtime;

import android.content.Context;

import com.qualcomm.ftccommon.FtcEventLoop;
import com.qualcomm.robotcore.util.RobotLog;

import org.firstinspires.ftc.ftccommon.external.OnCreateEventLoop;
import org.firstinspires.ftc.robotcore.internal.system.AppUtil;
import org.firstinspires.ftc.robotcore.internal.ui.UILocation;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loads whatever bundle is already sitting at CONTRACT.md's push target the moment the RC app
 * (re)starts, so a hot-loaded generation survives a Driver/Control Hub restart or power cycle
 * instead of silently reverting to whatever the last {@code installDebug}/{@code installRelease}
 * put on the robot. Before this class existed, {@link FluxReloadReceiver}'s {@code @OnCreate} only
 * registered a broadcast listener — nothing ever re-applied a bundle that was already on disk.
 *
 * <h2>Why {@code @OnCreateEventLoop}, not {@code @OnCreate}</h2>
 *
 * This was determined empirically (adb-deploy + restart + logcat on the API 25 Control Hub
 * emulator target), not assumed, because the two hooks fire at very different points relative to
 * the SDK's OWN class scan, and getting this wrong produces a silent no-op that looks identical to
 * success in the logs unless you specifically check which generation's classloader ends up live.
 *
 * Verified call order, from {@code FtcRobotControllerActivity.onCreate()} (decompiled/read from
 * the FtcCommon 12.0.0 sources bundled with the SDK — this file is NOT part of Flux, we only read
 * it) and confirmed live on-device:
 *
 * <pre>
 * onCreate() {
 *     ...
 *     ClassManager.getInstance().setOnBotJavaClassHelper(onBotJavaHelper);  // the APK's own helper
 *     ClassManagerFactory.processAllClasses();                              // SDK's OWN scan, APK classes
 *     ...
 *     AnnotatedHooksClassFilter.getInstance().callOnCreateMethods(this);    // <- our @OnCreate fires HERE
 * }
 * // ... later, once the background FtcRobotControllerService binds (async) ...
 * requestRobotSetup() {
 *     eventLoop = new FtcEventLoop(...);
 *     controllerService.setupRobot(eventLoop, idleLoop, ...);  // queues an ExecutorService task
 *                                                               // (RobotSetupRunnable) that will
 *                                                               // eventually call eventLoop.init(...)
 *                                                               // and register OpModes from
 *                                                               // whatever ClassManager/
 *                                                               // AnnotatedOpModeClassFilter
 *                                                               // currently hold -- NOT a rescan.
 *     AnnotatedHooksClassFilter.getInstance()
 *         .callOnCreateEventLoopMethods(this, eventLoop);       // <- our @OnCreateEventLoop fires HERE,
 *                                                                //    SYNCHRONOUSLY on the calling
 *                                                                //    thread, before the queued
 *                                                                //    RobotSetupRunnable has
 *                                                                //    necessarily even started.
 * }
 * </pre>
 *
 * {@code @OnCreate} fires <b>before</b> {@code ClassManagerFactory.processAllClasses()} has even
 * finished settling as "the" registry the rest of startup builds on top of, and well before
 * {@code FtcEventLoop} exists — reloading here means the SDK's own subsequent bookkeeping around
 * event-loop construction still has every opportunity to observe (and rebuild from) the APK's
 * original scan. Confirmed on-device: a reload issued from {@code @OnCreate} is NOT what ships to
 * the Driver Station's OpMode list after startup completes — {@code FluxTest} does not appear.
 *
 * {@code @OnCreateEventLoop} fires after {@code FtcEventLoop} has been constructed for this run and
 * immediately before the SDK hands OpMode registration duty to it — reloading here means our
 * generation is THE one {@code eventLoop.init()} (invoked moments later, off-thread, by
 * {@code RobotSetupRunnable}) registers OpModes from. Confirmed on-device: {@code FluxTest} (and,
 * see test #2, a brand-new class added after the last {@code installDebug}) is present in the
 * Driver Station's OpMode list after a restart when the reload happens here.
 *
 * <h2>Never blocks startup, never crashes it</h2>
 *
 * Every path through {@link #onCreateEventLoop} is wrapped so nothing can escape uncaught (same
 * discipline as {@link FluxReloadReceiver#onReceive}), and {@link FluxReloadEngine#reload} already
 * guarantees one of its three result codes with the app's original SDK-scanned registry left
 * completely untouched on {@code RESULT_FAILED_CLEAN} (see that method's doc) — the exact "fall
 * through to plain APK behaviour" outcome CONTRACT.md's safety model requires. This class adds
 * nothing to Flux's one-reflection budget (architecture.md §3.2): it only calls the existing
 * {@link FluxReloadEngine#reload} entry point, the same one {@link FluxReloadReceiver} already
 * uses for a live-triggered reload.
 */
public final class FluxStartupLoader {

    private static final String TAG = "FLUX";

    /** CONTRACT.md "On-robot paths" / "Full push target". Mirrors the private constant in
     *  {@link FluxReloadEngine} — kept duplicated rather than exposed from that (package-private,
     *  intentionally narrow) class, since "does a bundle exist" is a cheap, side-effect-free
     *  File#exists() check that doesn't need to go through the reload engine at all. */
    private static final File BUNDLE_FILE =
            new File(new File(AppUtil.FIRST_FOLDER, "flux"), "flux_bundle.jar");

    /** Guards against this ever running twice in one process. {@code @OnCreateEventLoop} fires
     *  once per {@code requestRobotSetup()} call, which in principle can happen more than once in
     *  the activity's lifetime (e.g. the user hits "restart robot" in the app) — persistence is a
     *  startup concern only. Re-running it on every manual robot restart would silently clobber
     *  whatever generation a live {@code fluxDeploy}/{@code fluxReload} had put in place since
     *  process start, which is exactly the kind of surprising behind-the-back overwrite
     *  architecture.md §9 ("ease of use") warns against. */
    private static final AtomicBoolean startupLoadAttempted = new AtomicBoolean(false);

    private FluxStartupLoader() {
    }

    @OnCreateEventLoop
    public static void onCreateEventLoop(Context context, FtcEventLoop eventLoop) {
        if (!startupLoadAttempted.compareAndSet(false, true)) {
            // Not the first event loop of this process — see field javadoc. Leave whatever
            // generation is currently live (installed APK, or a prior in-process reload) alone.
            return;
        }

        try {
            attemptStartupLoad(context);
        } catch (Throwable t) {
            // Absolute last line of defense, mirroring FluxReloadReceiver#onReceive: nothing below
            // should let an exception escape (FluxReloadEngine#reload already catches everything
            // internal to itself), but startup must NEVER crash or hang because of Flux, full stop.
            RobotLog.ee(TAG, t, "FLUX: uncaught exception escaped startup auto-load — "
                    + "continuing with whatever code is currently registered");
        }
    }

    private static void attemptStartupLoad(Context context) {
        if (!BUNDLE_FILE.exists()) {
            // The common case: no bundle has ever been pushed, or installDebug/installRelease
            // cleared it (see FluxSyncBaseline / Amendment 5). Nothing to do — the SDK's own scan
            // from onCreate() is already the live registry, i.e. plain APK behaviour.
            RobotLog.ii(TAG, "FLUX: no bundle at %s — starting on installed APK code",
                    BUNDLE_FILE.getPath());
            return;
        }

        RobotLog.ii(TAG, "FLUX: bundle found at %s — attempting startup auto-load so the RC app "
                + "resumes on the last hot-loaded generation instead of reverting to the "
                + "installed APK", BUNDLE_FILE.getPath());

        // Null policy means REJECT, the safe default. That is correct here rather than a
        // special case: this runs at @OnCreateEventLoop, before any OpMode can be selected, so
        // the check passes through. Deliberately NOT given a bypass -- if an OpMode somehow were
        // active at startup, refusing would still be the right answer.
        int result = FluxReloadEngine.reload(context.getApplicationContext(), null, null);

        switch (result) {
            case FluxReloadEngine.RESULT_SUCCESS:
                announceHotLoadedStartup(context, FluxReloadEngine.getCurrentGenerationLoader());
                break;
            case FluxReloadEngine.RESULT_FAILED_CLEAN:
                // RESULT_FAILED_CLEAN is ambiguous on its own -- FluxReloadEngine.reload() returns
                // it both when NOTHING was ever touched (bundle missing/corrupt, no rollback
                // available or attempted) AND when the primary bundle failed but the rollback to
                // flux_bundle.last.jar (requirement B: "reuse the existing rollback path where
                // sensible") SUCCEEDED -- in the latter case the robot is very much NOT running
                // plain APK code, it's running the last known-good HOT-LOADED generation. The two
                // are told apart the same way FluxReloadEngine itself verifies success: whether
                // currentGenerationLoader got set. (Reads a package-visible accessor, not a new
                // reflection -- see that method's doc.)
                ClassLoader rolledBackLoader = FluxReloadEngine.getCurrentGenerationLoader();
                if (rolledBackLoader != null) {
                    String rollbackBuildId = readBuildId(rolledBackLoader);
                    RobotLog.ww(TAG, "FLUX: startup auto-load of the latest bundle failed — rolled "
                            + "back to the last known-good hot-loaded build=%s instead", rollbackBuildId);
                    AppUtil.getInstance().showToast(UILocation.BOTH,
                            "Flux: latest bundle bad, rolled back to build " + rollbackBuildId);
                } else {
                    // True "nothing to roll back to" case (requirement B's actual "fall through to
                    // plain APK behaviour" outcome): the registry was never touched, so the SDK's
                    // own onCreate() scan of the installed APK is exactly what's live.
                    RobotLog.ww(TAG, "FLUX: startup auto-load did not apply (result=%d, clean, "
                            + "nothing to roll back to) — robot is running the INSTALLED APK's "
                            + "code, not a hot-loaded bundle", result);
                }
                break;
            case FluxReloadEngine.RESULT_FAILED_DIRTY:
            default:
                // Reached only if the pushed bundle's dex opened but something failed mid- or
                // post-registry-swap AND the last-known-good rollback FluxReloadEngine.reload()
                // already attempted also failed. This is the one startup outcome this class cannot
                // fully paper over without a second private-field reflection (there is no public
                // API to force ClassManager back to the app's original OnBotJavaHelper once a
                // helper swap has been committed) -- architecture.md §3.2's one-reflection budget
                // says stop and report rather than spend a second one, so this is logged as loudly
                // as possible instead. In practice this requires the pushed bundle AND the saved
                // rollback bundle to both be dex-valid-but-broken, which fluxDeploy's own
                // version-stamp verification makes very unlikely to ever reach the device in the
                // first place.
                RobotLog.ee(TAG, "FLUX: startup auto-load FAILED DIRTY (result=%d) — OpMode "
                        + "registry state is unknown. RESTART THE RC APP. If this persists, run "
                        + "`adb shell rm -f %s %s` and restart again to force plain APK behaviour.",
                        result, BUNDLE_FILE.getPath(),
                        new File(BUNDLE_FILE.getParentFile(), "flux_bundle.last.jar").getPath());
                AppUtil.getInstance().showToast(UILocation.BOTH,
                        "Flux: hot-load FAILED at startup, robot state unknown - RESTART THE APP");
                break;
        }
    }

    /**
     * Requirement C ("make it visible"): a robot that boots running hot-loaded code must be
     * obviously, not inferentially, different from one running the installed APK. Loud log line
     * (searched for by `adb logcat -d | grep FLUX` in CONTRACT.md's own verification recipe) plus
     * a Driver-Station-visible toast, both carrying the BUILD_ID, exactly mirroring how
     * {@link FluxReloadEngine} already reports a live-triggered reload's BUILD_ID.
     */
    private static void announceHotLoadedStartup(Context context, ClassLoader newGenerationLoader) {
        String buildId = readBuildId(newGenerationLoader);
        RobotLog.ii(TAG, "FLUX: ***** RC app started on HOT-LOADED code, build=%s ***** "
                + "(NOT the code from the last installDebug/installRelease)", buildId);
        AppUtil.getInstance().showToast(UILocation.BOTH,
                "Flux: running HOT-LOADED code (build " + buildId + ")");
    }

    private static String readBuildId(ClassLoader newGenerationLoader) {
        if (newGenerationLoader == null) {
            return "unknown";
        }
        try {
            Class<?> versionClass = newGenerationLoader.loadClass(
                    "org.firstinspires.ftc.teamcode.__FluxVersion");
            return String.valueOf(versionClass.getField("BUILD_ID").get(null));
        } catch (ReflectiveOperationException e) {
            // Purely cosmetic (the toast/log text) -- FluxReloadEngine already independently
            // verified this same class/field through this same loader to reach RESULT_SUCCESS in
            // the first place, so failing here would be surprising, but it must not be fatal to
            // an already-successful startup load.
            RobotLog.ww(TAG, e, "FLUX: reload succeeded but re-reading BUILD_ID for the "
                    + "startup banner failed");
            return "unknown";
        }
    }
}
