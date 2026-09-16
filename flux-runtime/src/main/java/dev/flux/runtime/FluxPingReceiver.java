package dev.flux.runtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.qualcomm.robotcore.util.RobotLog;

import org.firstinspires.ftc.ftccommon.external.OnCreate;
import org.firstinspires.ftc.ftccommon.external.OnDestroy;

/**
 * Answers {@code dev.flux.PING} with the runtime's version, so the desktop can tell whether
 * flux-runtime is installed and whether it matches the Gradle plugin.
 *
 * <h2>Why this exists at all</h2>
 *
 * {@code fluxDoctor} used to infer "is the runtime installed?" by grepping
 * {@code adb shell dumpsys package} for a {@code dev.flux.RELOAD} receiver. That check is a
 * <b>structural false negative</b>: {@code dumpsys package} lists only receivers declared in
 * AndroidManifest.xml, and every Flux receiver is registered <i>dynamically</i> from an
 * {@code @OnCreate} hook (see this module's near-empty manifest for why). So the check reported
 * FAIL on every install, including verified-working ones — confirmed by running {@code fluxDoctor}
 * immediately after a successful reload.
 *
 * <p>That matters more than a cosmetic bug: {@code fluxDoctor} is the tool a team reaches for when
 * something doesn't work, and a check that always fails trains people to ignore all of its output.
 *
 * <h2>Why a ping rather than a better static inspection</h2>
 *
 * Asking the runtime directly is both simpler and strictly more informative than inspecting the
 * APK: a reply proves the runtime is not merely present but actually <i>running and listening</i>,
 * and it can carry the version, which no amount of manifest inspection could. The reply rides
 * {@code setResultData}, which {@code am broadcast} prints as {@code data="..."} — the same
 * round-trip trick the reload path already uses for its result codes.
 */
public final class FluxPingReceiver {

    private static final String TAG = "FLUX";

    /** CONTRACT.md: the desktop sends this to ask "are you there, and what version?". */
    public static final String ACTION_PING = "dev.flux.PING";

    private static BroadcastReceiver registeredInstance;

    private FluxPingReceiver() {}

    @OnCreate
    @SuppressWarnings("unused") // invoked reflectively by the SDK's app-hook scan
    public static void onCreate(Context context) {
        if (registeredInstance != null) {
            RobotLog.ww(TAG, "FLUX: ping receiver already registered — ignoring");
            return;
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                // Result code 1 mirrors the rest of the protocol ("succeeded"); the version rides
                // in the result DATA so one round trip answers both "installed?" and "which one?".
                setResultCode(1);
                setResultData(FluxVersion.VERSION);
                RobotLog.ii(TAG, "FLUX: ping answered, runtime version %s", FluxVersion.VERSION);
            }
        };
        context.getApplicationContext().registerReceiver(receiver, new IntentFilter(ACTION_PING));
        registeredInstance = receiver;
        RobotLog.ii(TAG, "FLUX: ping receiver registered for action %s", ACTION_PING);
    }

    @OnDestroy
    @SuppressWarnings("unused")
    public static void onDestroy(Context context) {
        if (registeredInstance == null) {
            return;
        }
        try {
            context.getApplicationContext().unregisterReceiver(registeredInstance);
        } catch (IllegalArgumentException alreadyGone) {
            // Already unregistered; nothing to do and nothing worth alarming anyone about.
        }
        registeredInstance = null;
    }
}
