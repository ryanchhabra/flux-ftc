package dev.ryanchhab.flux.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * User-facing configuration for the `flux { }` block.
 *
 * Every property has a zero-config default (see architecture.md §9 — "one line to adopt").
 * Nothing here needs to be set for `./gradlew fluxDeploy` to work on the reference toolchain
 * described in CONTRACT.md.
 */
abstract class FluxExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * On-robot directory Flux pushes the bundle into.
     *
     * Defaults to the frozen CONTRACT.md path (`/sdcard/FIRST/flux`). This is exposed as
     * configurable because a handful of test-bench setups need to redirect it (e.g. a second
     * Flux instance on the same robot for a two-hub setup), but changing it means the
     * flux-runtime side must agree — see CONTRACT.md before touching it.
     */
    val deployLocation: Property<String> = objects.property(String::class.java)
        .convention(Defaults.DEPLOY_LOCATION)

    /**
     * Path to `adb`. Left unset by default — [dev.ryanchhab.flux.gradle.AdbLocator] resolves it lazily
     * from `ANDROID_HOME` / `ANDROID_SDK_ROOT` / `PATH` / the standard SDK install location.
     * Set this only if auto-location fails on your machine.
     */
    val adbPath: Property<String> = objects.property(String::class.java)

    /** Wi-Fi Direct address FTC Control/Driver Hubs use by default. */
    /**
     * The robot's Wi-Fi Direct address, used by the explicit `fluxConnect` task and by
     * `fluxDoctor`'s advice. Flux never connects on its own.
     *
     * There used to be an `autoConnect` setting that did it automatically before every deploy,
     * defaulting to "connect if not already connected". Measured on a machine with the robot
     * absent, a doomed `adb connect` takes **75 seconds** to time out -- so that default would
     * have turned a 500 ms deploy into a 75 second one for every team working over USB, which is
     * most of them most of the time. Connecting is now something you ask for.
     */
    val robotAddress: Property<String> = objects.property(String::class.java)
        .convention(Defaults.ROBOT_ADDRESS)

    /**
     * What happens when a deploy is requested while an OpMode is running. Sent to the robot with
     * every reload; see [SafetyPolicy].
     */
    val safetyPolicy: Property<SafetyPolicy> = objects.property(SafetyPolicy::class.java)
        .convention(SafetyPolicy.REJECT)

    object Defaults {
        const val DEPLOY_LOCATION = "/sdcard/FIRST/flux"
        const val ROBOT_ADDRESS = "192.168.43.1"
    }
}


/**
 * What a reload does when an OpMode is running on the robot.
 *
 * Hot-swapping TeamCode classes out from under a running OpMode is unsound: the OpMode keeps the
 * classes it already loaded, while anything constructed afterwards comes from the new generation,
 * and those are different types sharing a name. On a robot that is driving, that is a physical
 * safety question.
 *
 * There used to be a third value, `DEFER` ("stage the bundle and apply it when the OpMode stops").
 * It is gone rather than kept as a placeholder. Nothing implemented it, so setting it behaved
 * exactly like every other value, and an inert safety setting is worse than an absent one. It also
 * cannot be added without changing the wire protocol, since "staged, not yet applied" is a fourth
 * outcome that the three result codes cannot express. If it comes back, it comes back with the
 * runtime support and a result code of its own.
 */
enum class SafetyPolicy {
    /**
     * Refuse the deploy if an OpMode is active. The default, and the only safe choice for a robot
     * that might be on the field. Reports FAILED_CLEAN: nothing was touched, the robot is still
     * running the code it was running.
     */
    REJECT,

    /**
     * Ask the running OpMode to stop, wait briefly, then reload. Stops the robot, so it is opt-in.
     * If the OpMode does not stop in time the reload is refused rather than forced.
     */
    FORCE,
}
