package dev.ryanchhab.flux.runtime;

import android.app.Activity;

import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl;
import com.qualcomm.robotcore.util.RobotLog;

import org.firstinspires.ftc.robotcore.internal.system.AppUtil;

/**
 * Answers "is an OpMode running right now?", so a reload can refuse to swap code out from under a
 * robot that is actively driving.
 *
 * <p>This backs the {@code flux { safetyPolicy = ... }} setting. That setting existed, defaulted
 * to {@code REJECT}, and was even named in a reload failure message ("often an OpMode was running
 * under safetyPolicy=REJECT") -- but nothing ever checked, and the policy was never sent to the
 * robot. A team could set it to anything and get identical behaviour. A safety switch that is
 * wired to nothing is worse than no switch, because people rely on it.
 *
 * <p>No new reflection is needed, which matters: Flux's whole durability argument rests on a
 * one-reflection budget (see {@link FluxReloadEngine#resetProcessAllClassesGuard}). Everything
 * used here is public API on RobotCore 12.0.0, verified with {@code javap}:
 * {@code AppUtil.getActivity()}, {@code OpModeManagerImpl.getOpModeManagerOfActivity(Activity)},
 * {@code OpModeManagerImpl.getActiveOpModeName()} and the public constant
 * {@code OpModeManagerImpl.DEFAULT_OP_MODE_NAME}. All of them are present unchanged in every SDK
 * release from 8.1.0 onward, the same range {@code SdkSupport} already establishes.
 */
final class FluxOpModeGuard {

    private static final String TAG = "FLUX";

    private FluxOpModeGuard() {
    }

    /**
     * The name of the OpMode the Robot Controller currently has selected, or {@code null} when
     * that cannot be determined.
     *
     * <p>{@code null} means "no information", NOT "nothing is running". Callers must decide what
     * to do with ignorance explicitly rather than treating it as safety.
     */
    static String activeOpModeName() {
        try {
            Activity activity = AppUtil.getInstance().getActivity();
            if (activity == null) {
                return null;
            }
            OpModeManagerImpl manager = OpModeManagerImpl.getOpModeManagerOfActivity(activity);
            if (manager == null) {
                return null;
            }
            return manager.getActiveOpModeName();
        } catch (Throwable t) {
            // Never let a diagnostic question break a reload. Unknown is a valid answer.
            RobotLog.ww(TAG, "FLUX: could not determine the active OpMode: %s", t);
            return null;
        }
    }

    /**
     * True when an OpMode is selected and is not the SDK's built-in stop-robot placeholder.
     *
     * <p>The Robot Controller always has an "active" OpMode; when nothing is running it is
     * {@code OpModeManagerImpl.DEFAULT_OP_MODE_NAME} ("$Stop$Robot$"). Comparing against that
     * constant rather than a hardcoded string means this keeps working if the SDK renames it.
     *
     * <p>Note this is true for an OpMode that has been INIT-ed but not started, not only one that
     * is driving. That is deliberate: an init-ed OpMode already holds hardware references from the
     * current generation, and swapping its class out is exactly as unsound as doing it mid-drive.
     */
    static boolean isOpModeActive() {
        String name = activeOpModeName();
        return name != null && !OpModeManagerImpl.DEFAULT_OP_MODE_NAME.equals(name);
    }

    /**
     * Asks the Robot Controller to stop the running OpMode and waits, briefly, for it to actually
     * stop. Returns true if nothing is running by the time it gives up waiting.
     *
     * <p>The stop is performed by selecting the SDK's own stop-robot OpMode, which is the same
     * path the Driver Station's Stop button takes, rather than by reaching into OpMode internals.
     *
     * <p>The switch happens on the event loop's thread, not the caller's, so this polls. The
     * budget is deliberately small: this runs on the broadcast receiver's thread, and a reload
     * that hangs the app for seconds while "being helpful" is its own failure. If the OpMode does
     * not stop in time the caller is told so and refuses the reload, which is the safe outcome.
     */
    static boolean requestStopAndWait(long timeoutMs) {
        try {
            Activity activity = AppUtil.getInstance().getActivity();
            if (activity == null) {
                return false;
            }
            OpModeManagerImpl manager = OpModeManagerImpl.getOpModeManagerOfActivity(activity);
            if (manager == null) {
                return false;
            }

            RobotLog.ii(TAG, "FLUX: safetyPolicy=FORCE - asking the running OpMode to stop");
            manager.initOpMode(OpModeManagerImpl.DEFAULT_OP_MODE_NAME);

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (!isOpModeActive()) {
                    return true;
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return !isOpModeActive();
        } catch (Throwable t) {
            RobotLog.ww(TAG, "FLUX: could not stop the active OpMode: %s", t);
            return false;
        }
    }
}
