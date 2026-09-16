package dev.flux.runtime;

/**
 * The classloader delegation decision, extracted as pure logic with <b>no Android dependencies</b>.
 *
 * <p>This exists so the most bug-prone part of Flux can be tested on a plain JVM, with no Control
 * Hub, no emulator, and no Android SDK. {@link FluxClassLoader} is untestable off-device because it
 * extends {@code dalvik.system.PathClassLoader}, but the interesting part — <i>which loader should
 * serve this class name</i> — is ordinary string logic. Keeping it here means the tests exercise the
 * same code the robot runs, rather than a copy that can drift.
 *
 * <p>See {@code test/jvm/FluxDelegationTest.java}.
 */
final class FluxDelegation {

    private FluxDelegation() {}

    /** Where a given class name must be resolved from. */
    enum Route {
        /** Always the parent chain; never define from the bundle (stdlib, SDK, framework). */
        PARENT,
        /**
         * TeamCode. Normally defined from the pushed bundle — but if it carries a hardware-config
         * annotation AND exists in the installed APK, it must resolve to the app loader's identity
         * instead (docs/design/CONTRACT.md Amendment 1).
         */
        TEAMCODE,
        /** Ordinary parent-first, falling back to the bundle. */
        PARENT_THEN_BUNDLE,
    }

    static final String[] EXCLUDED_PACKAGE_PREFIXES = {
            "kotlin.",
            "kotlinx.",
            "java.",
            "javax.",
            "android.",
            "androidx.",
            "dalvik.",
            "com.qualcomm.",
    };

    static final String FTC_PREFIX = "org.firstinspires.ftc.";
    static final String TEAMCODE_PREFIX = "org.firstinspires.ftc.teamcode.";
    static final String TEAMCODE_PACKAGE = "org.firstinspires.ftc.teamcode";

    static Route routeFor(String name) {
        if (isPackagePrefixExcluded(name)) {
            return Route.PARENT;
        }
        if (isTeamCode(name)) {
            return Route.TEAMCODE;
        }
        return Route.PARENT_THEN_BUNDLE;
    }

    static boolean isTeamCode(String name) {
        return name.equals(TEAMCODE_PACKAGE) || name.startsWith(TEAMCODE_PREFIX);
    }

    static boolean isPackagePrefixExcluded(String name) {
        for (String prefix : EXCLUDED_PACKAGE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        // org.firstinspires.ftc.* is excluded EXCEPT the teamcode subpackage, which is the whole
        // point of this loader's existence.
        if (name.startsWith(FTC_PREFIX)) {
            return !name.equals(TEAMCODE_PACKAGE) && !name.startsWith(TEAMCODE_PREFIX);
        }
        return false;
    }
}
