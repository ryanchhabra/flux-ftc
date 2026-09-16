import java.lang.reflect.Method;

/**
 * Plain-JVM test of the safetyPolicy decision -- no Control Hub, no emulator, no Android.
 *
 * This is the one Flux decision that cannot be verified end-to-end on an emulator. Without a
 * Driver Station and a configured robot the Robot Controller never reaches RUNNING, initOpMode is
 * a no-op, and the active OpMode stays "$Stop$Robot$" whatever you do -- so "an OpMode really is
 * running" is only reachable on hardware. Testing the real decision class here (via reflection,
 * since it is package-private) means the table is still verified, and the untested link is
 * narrowed to the single SDK read in FluxOpModeGuard.
 *
 * Run: see flux-runtime/src/test/jvm/run-tests.sh
 */
public class FluxSafetyDecisionTest {

    private static int passed = 0;
    private static int failed = 0;
    private static Method decide;

    public static void main(String[] args) throws Exception {
        Class<?> d = Class.forName("dev.ryanchhab.flux.runtime.FluxSafetyDecision");
        decide = d.getDeclaredMethod("decide", String.class, boolean.class);
        decide.setAccessible(true);

        System.out.println("FluxSafetyDecision -- reload safety policy\n");

        // --- Nothing running: every policy proceeds. This is the ordinary case ----------------
        check("REJECT", false, "PROCEED", "nothing running, REJECT still deploys");
        check("FORCE", false, "PROCEED", "nothing running, FORCE has nothing to stop");
        check(null, false, "PROCEED", "nothing running, no policy sent");

        // --- OpMode running: this is what the setting exists for ------------------------------
        check("REJECT", true, "REFUSE", "REJECT refuses rather than swapping code under a running OpMode");
        check("FORCE", true, "STOP_THEN_PROCEED", "FORCE stops the OpMode first");

        // --- Failing safe --------------------------------------------------------------------
        // An older Gradle plugin sends no extra at all, and someone firing the broadcast by hand
        // from a shell sends nothing either. Both must get the safe behaviour, not the permissive
        // one: opting out of a safety check has to be deliberate.
        check(null, true, "REFUSE", "absent policy is REJECT, not permissive");
        check("", true, "REFUSE", "empty policy is REJECT");
        check("   ", true, "REFUSE", "whitespace policy is REJECT");
        check("DEFER", true, "REFUSE", "a policy that no longer exists must not silently allow");
        check("force ", true, "STOP_THEN_PROCEED", "surrounding whitespace is tolerated");
        check("Force", true, "STOP_THEN_PROCEED", "policy match is case-insensitive");
        check("banana", true, "REFUSE", "an unrecognised policy fails safe");

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String policy, boolean opModeActive, String expected, String why)
            throws Exception {
        Object actual = decide.invoke(null, policy, opModeActive);
        String actualName = String.valueOf(actual);
        boolean ok = expected.equals(actualName);
        if (ok) {
            passed++;
            System.out.printf("  ok    policy=%-8s active=%-5s -> %-17s %s%n",
                    String.valueOf(policy), opModeActive, actualName, why);
        } else {
            failed++;
            System.out.printf("  FAIL  policy=%-8s active=%-5s -> %-17s expected %s (%s)%n",
                    String.valueOf(policy), opModeActive, actualName, expected, why);
        }
    }
}
