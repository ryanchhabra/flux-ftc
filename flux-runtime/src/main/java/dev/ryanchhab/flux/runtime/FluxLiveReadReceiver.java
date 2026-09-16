package dev.ryanchhab.flux.runtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.qualcomm.robotcore.util.RobotLog;

import org.firstinspires.ftc.ftccommon.external.OnCreate;
import org.firstinspires.ftc.ftccommon.external.OnDestroy;

/**
 * The on-robot trigger for live-value read-back (CONTRACT.md Amendment 4). Registers for
 * {@link #ACTION_LIVE_READ} and delegates to {@link FluxLiveReadback}, returning the result via
 * {@link #setResultCode(int)} — same mechanism, same {@code result=N} stdout contract on the Gradle
 * side as every other Flux receiver.
 *
 * <p>Registered dynamically via the FTC SDK's {@code @OnCreate}/{@code @OnDestroy} app hooks
 * ({@code org.firstinspires.ftc.ftccommon.external}) — copied from {@link FluxLiveTuneReceiver}, which
 * copied it from {@link FluxReloadReceiver}; see that class's javadoc for why this needs no
 * {@code AndroidManifest.xml} entry. Kept as a fully separate receiver/instance/action (rather than
 * teaching an existing receiver a second action) for the same reason {@link FluxLiveTuneReceiver} is
 * separate from {@link FluxReloadReceiver}: one action, one receiver, one result path, matching
 * CONTRACT.md's "Intent action" row per tier.
 */
public final class FluxLiveReadReceiver extends BroadcastReceiver {

    private static final String TAG = "FLUX";

    /** CONTRACT.md Amendment 4 "Intent action". */
    public static final String ACTION_LIVE_READ = "dev.ryanchhab.flux.LIVE_READ";

    private static volatile FluxLiveReadReceiver registeredInstance;

    @OnCreate
    public static void onCreate(Context context) {
        if (registeredInstance != null) {
            RobotLog.ww(TAG, "FLUX: live-read onCreate called again with a receiver already registered — ignoring");
            return;
        }
        FluxLiveReadReceiver receiver = new FluxLiveReadReceiver();
        context.registerReceiver(receiver, new IntentFilter(ACTION_LIVE_READ));
        registeredInstance = receiver;
        RobotLog.ii(TAG, "FLUX: live-read receiver registered for action %s", ACTION_LIVE_READ);
    }

    @OnDestroy
    public static void onDestroy(Context context) {
        FluxLiveReadReceiver receiver = registeredInstance;
        if (receiver == null) {
            return;
        }
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException alreadyUnregistered) {
            RobotLog.ww(TAG, "FLUX: live-read receiver was already unregistered");
        }
        registeredInstance = null;
        RobotLog.ii(TAG, "FLUX: live-read receiver unregistered");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        RobotLog.ii(TAG, "FLUX: %s received, reading live values", ACTION_LIVE_READ);

        int resultCode;
        try {
            resultCode = FluxLiveReadback.apply(context.getApplicationContext());
        } catch (Throwable t) {
            // Same last-line-of-defense reasoning as the other receivers: FluxLiveReadback.apply()
            // should never let an exception escape, but if one ever does, we must still answer with
            // one of CONTRACT.md's three defined codes rather than an undefined broadcast result.
            RobotLog.ee(TAG, t, "FLUX: uncaught exception escaped FluxLiveReadback.apply()");
            resultCode = FluxReloadEngine.RESULT_FAILED_DIRTY;
        }

        setResultCode(resultCode);
        RobotLog.ii(TAG, "FLUX: live read finished, result=%d", resultCode);
    }
}
