package dev.ryanchhab.flux.gradle

/**
 * Which FTC SDK versions Flux works against, and how sure we are.
 *
 * This used to be a single constant compared with `==`, which reported `[FAIL]` for every team not
 * on the exact version Flux was developed against. That was misleading in both directions: it
 * scared off teams whose SDK is fine, and it implied a precision nobody had actually measured.
 *
 * The floor below IS measured. flux-runtime touches a small, enumerable set of SDK members:
 *
 *   RobotCore  org.firstinspires.ftc.robotcore.internal.opmode.ClassManager
 *                  getInstance(), setOnBotJavaClassHelper(OnBotJavaHelper), processAllClasses(),
 *                  and the private field `processAllClassesCalled` (the one reflection Flux does)
 *              ...opmode.OnBotJavaHelper — implemented anonymously, so its whole abstract method
 *                  set is load-bearing: createOnBotJavaClassLoader, getOnBotJavaClassNames,
 *                  getExternalLibrariesClassNames, isExternalLibrariesError
 *              ...opmode.RegisteredOpModes — getInstance, lockOpModesWhile(Runnable),
 *                  registerAllOpModes(OpModeRegister), getOpModes()
 *              ...opmode.AnnotatedOpModeClassFilter — getInstance, filterExternalLibrariesClassesStart
 *              ...system.AppUtil — FIRST_FOLDER, showToast(UILocation, String)
 *              com.qualcomm.robotcore.hardware.configuration.annotations.{MotorType, ServoType,
 *                  I2cDeviceType, AnalogSensorType, DigitalIoDeviceType, DeviceProperties}
 *   FtcCommon  org.firstinspires.ftc.ftccommon.external.{OnCreate, OnCreateEventLoop, OnDestroy}
 *              com.qualcomm.ftccommon.FtcEventLoop
 *
 * Every one of those was `javap`-compared across all RobotCore/FtcCommon releases published to
 * Maven Central (7.0.0 through 12.0.0). Result: the signatures are byte-identical from 8.1.0
 * onward. 8.0.0 and 7.x are the only releases that differ, and they differ in the worst possible
 * place -- `ClassManager.processAllClassesCalled` does not exist, so the reload transaction cannot
 * reset the SDK's one-shot guard and a second reload would silently no-op.
 *
 * Hence three states rather than two:
 *  - VERIFIED  the SDK Flux is actually exercised against end to end. Reported as PASS.
 *  - >= MINIMUM but not verified: API-compatible by measurement, untested by us. Reported as WARN,
 *    which does NOT fail `fluxDoctor`. A team here should expect Flux to work and should say so if
 *    it doesn't.
 *  - < MINIMUM, or absent: reported as FAIL, because a specific member Flux needs is missing.
 */
object SdkSupport {

    /** Exercised end to end (hot reload, new classes, Kotlin, rollback, restart persistence). */
    val VERIFIED: Set<String> = setOf("12.0.0")

    /** The oldest release carrying every member listed above. Measured, not assumed. */
    const val MINIMUM = "8.1.0"

    /** The newest release this policy was measured against; newer ones warn rather than fail. */
    const val NEWEST_MEASURED = "12.0.0"

    sealed interface Verdict {
        /** A verified version. */
        object Verified : Verdict

        /** API-compatible by measurement, or newer than anything measured. Not a failure. */
        data class Untested(val reason: String) : Verdict

        /** A member Flux needs is missing, or no RobotCore was found at all. */
        data class Unsupported(val reason: String) : Verdict
    }

    fun classify(version: String?): Verdict {
        if (version.isNullOrBlank()) {
            return Verdict.Unsupported("RobotCore dependency not found on the module's classpath")
        }
        if (version in VERIFIED) return Verdict.Verified

        val parsed = parse(version)
            // An unparseable version is not evidence of breakage -- a team on a locally built or
            // -SNAPSHOT SDK gets a warning, not a hard stop, because Flux has no reason to believe
            // anything is wrong and refusing would be the more expensive mistake.
            ?: return Verdict.Untested("could not compare \"$version\" against the supported range")

        if (compare(parsed, parse(MINIMUM)!!) < 0) {
            return Verdict.Unsupported(
                "RobotCore $version predates $MINIMUM, which is the oldest release that has " +
                    "ClassManager.processAllClassesCalled -- without it Flux cannot reset the " +
                    "SDK's one-shot class-scan guard, so only the first reload would take effect",
            )
        }
        return if (compare(parsed, parse(NEWEST_MEASURED)!!) > 0) {
            Verdict.Untested("newer than $NEWEST_MEASURED, the newest release Flux has been checked against")
        } else {
            Verdict.Untested("API-compatible with $NEWEST_MEASURED, but Flux is only exercised against ${VERIFIED.joinToString(", ")}")
        }
    }

    /**
     * Splits a version into its numeric components, tolerating trailing qualifiers such as
     * `-SNAPSHOT` or `-RC1`. Returns null when there is no leading numeric component at all,
     * which the caller treats as "unknown", never as "too old".
     */
    internal fun parse(version: String): List<Int>? {
        val numeric = version.trim().takeWhile { it.isDigit() || it == '.' }.trimEnd('.')
        if (numeric.isEmpty()) return null
        val parts = numeric.split('.').map { it.toIntOrNull() ?: return null }
        return if (parts.isEmpty()) null else parts
    }

    /** Component-wise, treating a missing component as 0 so "9" and "9.0.0" compare equal. */
    internal fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
