import java.lang.reflect.Method;

/**
 * Plain-JVM test of the classloader delegation decision -- no Control Hub, no emulator, no Android.
 *
 * FluxClassLoader itself extends dalvik.system.PathClassLoader and cannot run off-device, but the
 * decision it makes is ordinary string logic living in FluxDelegation. This tests that real class
 * (via reflection, since it is package-private), not a copy, so the two cannot drift apart.
 *
 * Run: see flux-runtime/src/test/jvm/run-tests.sh
 */
public class FluxDelegationTest {

    private static int passed = 0;
    private static int failed = 0;
    private static Method routeFor;
    private static Method isTeamCode;

    public static void main(String[] args) throws Exception {
        Class<?> d = Class.forName("dev.flux.runtime.FluxDelegation");
        routeFor = d.getDeclaredMethod("routeFor", String.class);
        routeFor.setAccessible(true);
        isTeamCode = d.getDeclaredMethod("isTeamCode", String.class);
        isTeamCode.setAccessible(true);

        System.out.println("FluxDelegation -- classloader routing\n");

        // --- The whole point of the design: TeamCode comes from the bundle -------------------
        route("org.firstinspires.ftc.teamcode.FluxTest",            "TEAMCODE");
        route("org.firstinspires.ftc.teamcode.subsystems.Drive",    "TEAMCODE");
        route("org.firstinspires.ftc.teamcode",                     "TEAMCODE");

        // --- risks.md S2: the Kotlin stdlib must NEVER be redefined -------------------------
        // Duplicate-defining these produces ClassCastExceptions that look like anything but a
        // hot-reload bug. Fast Load has no such exclusion; this is the bug we must not repeat.
        route("kotlin.Unit",                                        "PARENT");
        route("kotlin.jvm.internal.Intrinsics",                     "PARENT");
        route("kotlinx.coroutines.BuildersKt",                      "PARENT");

        // --- The SDK is the host environment running the reload ------------------------------
        route("com.qualcomm.robotcore.hardware.DcMotor",            "PARENT");
        route("com.qualcomm.robotcore.eventloop.opmode.LinearOpMode","PARENT");
        route("org.firstinspires.ftc.robotcore.internal.opmode.ClassManager", "PARENT");
        route("org.firstinspires.ftc.vision.VisionPortal",           "PARENT");

        // --- Framework -----------------------------------------------------------------------
        route("java.lang.String",                                   "PARENT");
        route("android.content.Context",                            "PARENT");
        route("androidx.core.app.ActivityCompat",                   "PARENT");
        route("dalvik.system.PathClassLoader",                      "PARENT");

        // --- Third-party TeamCode dependencies are not excluded ------------------------------
        route("com.acmerobotics.dashboard.FtcDashboard",            "PARENT_THEN_BUNDLE");
        route("org.openftc.easyopencv.OpenCvCamera",                "PARENT_THEN_BUNDLE");

        // --- Boundary cases: the near-misses that would silently break everything -------------
        // A sibling package must NOT be mistaken for teamcode...
        bool("org.firstinspires.ftc.teamcodeextra.Thing", false, "sibling pkg is not teamcode");
        route("org.firstinspires.ftc.teamcodeextra.Thing", "PARENT");
        // ...and a class named *like* the package must not be either.
        bool("org.firstinspires.ftc.teamcodeX", false, "teamcodeX is not teamcode");
        // A class whose name merely contains the string (Fast Load used a `contains` check here,
        // which would wrongly match this).
        bool("com.example.org.firstinspires.ftc.teamcode.Fake", false, "contains-match must not fire");
        route("com.example.org.firstinspires.ftc.teamcode.Fake", "PARENT_THEN_BUNDLE");
        // Prefix-lookalikes for excluded packages.
        route("kotlinx.Thing",                                      "PARENT");
        route("kotlinfoo.Thing",                                    "PARENT_THEN_BUNDLE");
        route("javaxfoo.Thing",                                     "PARENT_THEN_BUNDLE");

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void route(String name, String expected) throws Exception {
        String actual = routeFor.invoke(null, name).toString();
        check(expected.equals(actual), name + " -> " + actual
                + (expected.equals(actual) ? "" : "  (expected " + expected + ")"));
    }

    private static void bool(String name, boolean expected, String label) throws Exception {
        boolean actual = (Boolean) isTeamCode.invoke(null, name);
        check(actual == expected, label + ": isTeamCode(" + name + ") = " + actual);
    }

    private static void check(boolean ok, String msg) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + msg);
        if (ok) passed++; else failed++;
    }
}
