package dev.ryanchhab.flux.runtime;

/**
 * The {@code flux { safetyPolicy = ... }} decision, as pure logic.
 *
 * <p>Extracted from {@link FluxReloadEngine} for the same reason {@link FluxDelegation} was: the
 * surrounding code cannot run off-device, but the decision it makes is ordinary branching that
 * can, and this is a safety decision, so it should be tested rather than reasoned about.
 *
 * <p>That matters more than usual here. The one state this cannot be exercised in on an emulator
 * is the one that counts: without a Driver Station and a configured robot, the Robot Controller
 * never reaches RUNNING, {@code initOpMode} is a no-op, and the active OpMode stays
 * {@code $Stop$Robot$} no matter what. So "an OpMode really is running" is reachable only on
 * hardware. Keeping the decision pure means the table below is still verified today, and the only
 * untested link is the SDK read in {@link FluxOpModeGuard#isOpModeActive()}.
 */
final class FluxSafetyDecision {

    private FluxSafetyDecision() {
    }

    /** What the reload engine should do next. */
    enum Decision {
        /** Nothing is running, or the policy allows it. Reload. */
        PROCEED,

        /** An OpMode is running and the policy forbids interrupting it. Refuse, touch nothing. */
        REFUSE,

        /** An OpMode is running and the policy says stop it first, then reload if it stops. */
        STOP_THEN_PROCEED,
    }

    static final String POLICY_REJECT = "REJECT";
    static final String POLICY_FORCE = "FORCE";

    /**
     * @param policy       the policy name as sent by the Gradle plugin. Null, empty, or anything
     *                     unrecognised is treated as {@link #POLICY_REJECT}: failing safe beats
     *                     being permissive about a typo or an older plugin that sends nothing.
     * @param opModeActive whether an OpMode is currently selected and running. When this cannot be
     *                     determined, callers pass false -- refusing every reload because the SDK
     *                     could not be queried would make Flux unusable, and an undeterminable
     *                     state has never been observed on a robot that is actually running one.
     */
    static Decision decide(String policy, boolean opModeActive) {
        if (!opModeActive) {
            return Decision.PROCEED;
        }
        if (POLICY_FORCE.equalsIgnoreCase(trim(policy))) {
            return Decision.STOP_THEN_PROCEED;
        }
        return Decision.REFUSE;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
