package dev.ryanchhab.flux.runtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.qualcomm.robotcore.util.RobotLog;

import org.firstinspires.ftc.ftccommon.external.OnCreate;
import org.firstinspires.ftc.ftccommon.external.OnDestroy;

/**
 * The on-robot trigger for a reload. Registers for {@link #ACTION_RELOAD} and delegates to
 * {@link FluxReloadEngine}, returning the result via {@link #setResultCode(int)} so the Gradle
 * side's {@code adb shell am broadcast} can parse {@code result=N} out of stdout (CONTRACT.md
 * "Reload trigger").
 *
 * <p>Registered via the FTC SDK's {@code @OnCreate}/{@code @OnDestroy} app hooks
 * ({@code org.firstinspires.ftc.ftccommon.external}), the same mechanism
 * docs/research/hot-reload-prior-art.md §2.4 documents Fast Load using. These are plain method
 * annotations (verified via {@code javap} against FtcCommon 12.0.0 — {@code @Target(METHOD)}),
 * discovered by the SDK's own classpath scan over classes compiled into the app. Because
 * flux-runtime is a normal Gradle dependency of the RC app (not something pushed at runtime), this
 * requires no AndroidManifest.xml entry at all — see this module's (deliberately near-empty)
 * manifest for the fuller explanation.
 *
 * <p>Important asymmetry, called out in docs/research/risks.md §9's supported/blocked table:
 * this receiver's own registration is install-time (it's how the RC app itself starts listening
 * for {@link #ACTION_RELOAD} in the first place). A hot reload can never install *this* listener
 * for the first time — that always requires a normal APK install. Once installed, though, the
 * listener itself never needs to change across ordinary TeamCode reloads.
 */
public final class FluxReloadReceiver extends BroadcastReceiver {

    private static final String TAG = "FLUX";

    /** CONTRACT.md "Reload trigger". */
    public static final String ACTION_RELOAD = "dev.ryanchhab.flux.RELOAD";

    /**
     * Optional broadcast extra carrying the build id the caller expects to observe after reload.
     * CONTRACT.md's minimal Phase 0 command ({@code adb shell am broadcast -a dev.ryanchhab.flux.RELOAD})
     * doesn't pass this, so it is read defensively and treated as absent (no mismatch check, only
     * a load-and-read-back check) unless a future Gradle-side change starts supplying it — see
     * {@link FluxReloadEngine#reload(Context, String)}'s javadoc for what changes if it's present.
     */
    public static final String EXTRA_EXPECTED_BUILD_ID = "buildId";

    private static volatile FluxReloadReceiver registeredInstance;

    @OnCreate
    public static void onCreate(Context context) {
        if (registeredInstance != null) {
            // Defensive: @OnCreate should only fire once per process, but guard against a
            // double-registration leaking a second live receiver if that ever changes.
            RobotLog.ww(TAG, "FLUX: onCreate called again with a receiver already registered — ignoring");
            return;
        }
        FluxReloadReceiver receiver = new FluxReloadReceiver();
        context.registerReceiver(receiver, new IntentFilter(ACTION_RELOAD));
        registeredInstance = receiver;
        RobotLog.ii(TAG, "FLUX: reload receiver registered for action %s", ACTION_RELOAD);
    }

    @OnDestroy
    public static void onDestroy(Context context) {
        FluxReloadReceiver receiver = registeredInstance;
        if (receiver == null) {
            return;
        }
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException alreadyUnregistered) {
            // Best-effort: the receiver may already be gone if the Context is on its way down.
            RobotLog.ww(TAG, "FLUX: reload receiver was already unregistered");
        }
        registeredInstance = null;
        RobotLog.ii(TAG, "FLUX: reload receiver unregistered");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String expectedBuildId = intent.getStringExtra(EXTRA_EXPECTED_BUILD_ID);
        RobotLog.ii(TAG, "FLUX: %s received, starting reload", ACTION_RELOAD);

        int resultCode;
        try {
            resultCode = FluxReloadEngine.reload(context.getApplicationContext(), expectedBuildId);
        } catch (Throwable t) {
            // Absolute last line of defense: nothing in FluxReloadEngine should let an exception
            // escape uncaught (every internal path returns one of the three result codes), but if
            // one ever does, we must still answer with a code rather than let the broadcast
            // result default to whatever Android's BroadcastReceiver default is (0, which is not
            // one of CONTRACT.md's three defined codes and would be silently misinterpreted).
            RobotLog.ee(TAG, t, "FLUX: uncaught exception escaped FluxReloadEngine.reload()");
            resultCode = FluxReloadEngine.RESULT_FAILED_DIRTY;
        }

        setResultCode(resultCode);
        RobotLog.ii(TAG, "FLUX: reload finished, result=%d", resultCode);
    }
}
