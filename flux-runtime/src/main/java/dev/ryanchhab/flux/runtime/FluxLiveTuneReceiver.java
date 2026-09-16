package dev.ryanchhab.flux.runtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.qualcomm.robotcore.util.RobotLog;

import org.firstinspires.ftc.ftccommon.external.OnCreate;
import org.firstinspires.ftc.ftccommon.external.OnDestroy;

/**
 * The on-robot trigger for Tier L live tuning (CONTRACT.md Amendment 3). Registers for
 * {@link #ACTION_LIVE_TUNE} and delegates to {@link FluxLiveTuning}, returning the result via
 * {@link #setResultCode(int)} exactly as {@link FluxReloadReceiver} does for {@code dev.ryanchhab.flux.RELOAD}
 * — same mechanism, same {@code result=N} stdout contract on the Gradle side, deliberately copied
 * rather than reinvented.
 *
 * <p>Registered dynamically via the FTC SDK's {@code @OnCreate}/{@code @OnDestroy} app hooks
 * ({@code org.firstinspires.ftc.ftccommon.external}) — see {@link FluxReloadReceiver}'s javadoc for
 * why this needs no {@code AndroidManifest.xml} entry. Kept as a fully separate receiver/instance
 * (rather than teaching {@link FluxReloadReceiver} a second action) so a live-tune broadcast can
 * never be misrouted into the much more expensive reload path or vice versa — one action, one
 * receiver, one result path, matching CONTRACT.md's "Intent action" row for each tier.
 */
public final class FluxLiveTuneReceiver extends BroadcastReceiver {

    private static final String TAG = "FLUX";

    /** CONTRACT.md Amendment 3 "Intent action". */
    public static final String ACTION_LIVE_TUNE = "dev.ryanchhab.flux.LIVE_TUNE";

    private static volatile FluxLiveTuneReceiver registeredInstance;

    @OnCreate
    public static void onCreate(Context context) {
        if (registeredInstance != null) {
            // Defensive, mirrors FluxReloadReceiver: @OnCreate should only fire once per process.
            RobotLog.ww(TAG, "FLUX: live-tune onCreate called again with a receiver already registered — ignoring");
            return;
        }
        FluxLiveTuneReceiver receiver = new FluxLiveTuneReceiver();
        context.registerReceiver(receiver, new IntentFilter(ACTION_LIVE_TUNE));
        registeredInstance = receiver;
        RobotLog.ii(TAG, "FLUX: live-tune receiver registered for action %s", ACTION_LIVE_TUNE);
    }

    @OnDestroy
    public static void onDestroy(Context context) {
        FluxLiveTuneReceiver receiver = registeredInstance;
        if (receiver == null) {
            return;
        }
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException alreadyUnregistered) {
            RobotLog.ww(TAG, "FLUX: live-tune receiver was already unregistered");
        }
        registeredInstance = null;
        RobotLog.ii(TAG, "FLUX: live-tune receiver unregistered");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        RobotLog.ii(TAG, "FLUX: %s received, applying live values", ACTION_LIVE_TUNE);

        int resultCode;
        try {
            resultCode = FluxLiveTuning.apply(context.getApplicationContext());
        } catch (Throwable t) {
            // Same last-line-of-defense reasoning as FluxReloadReceiver: FluxLiveTuning.apply()
            // should never let an exception escape (every per-field failure is caught and logged
            // individually), but if one ever does, we must still answer with one of CONTRACT.md's
            // three defined codes rather than let the broadcast default to an undefined result.
            RobotLog.ee(TAG, t, "FLUX: uncaught exception escaped FluxLiveTuning.apply()");
            resultCode = FluxReloadEngine.RESULT_FAILED_DIRTY;
        }

        setResultCode(resultCode);
        RobotLog.ii(TAG, "FLUX: live tune finished, result=%d", resultCode);
    }
}
