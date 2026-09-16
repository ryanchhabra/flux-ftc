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
    val robotAddress: Property<String> = objects.property(String::class.java)
        .convention(Defaults.ROBOT_ADDRESS)

    /** How Flux manages `adb connect`/`disconnect` around a deploy. */
    val autoConnect: Property<AutoConnect> = objects.property(AutoConnect::class.java)
        .convention(AutoConnect.MAINTAIN)

    /** What happens when a deploy is requested while an OpMode is running. */
    val safetyPolicy: Property<SafetyPolicy> = objects.property(SafetyPolicy::class.java)
        .convention(SafetyPolicy.REJECT)

    object Defaults {
        const val DEPLOY_LOCATION = "/sdcard/FIRST/flux"
        const val ROBOT_ADDRESS = "192.168.43.1"
    }
}

/**
 * Mirrors Sloth's `AutoConnect` semantics (sloth-teardown.md §3) since it is a proven, well
 * understood model for FTC's usual USB-then-Wi-Fi-Direct workflow.
 */
enum class AutoConnect {
    /** Never touch the adb connection; the user manages USB/Wi-Fi themselves. */
    NEVER,

    /** Connect before a deploy if not already connected; never proactively disconnect. */
    MAINTAIN,

    /** Actively (re)connect before every deploy and disconnect afterward. */
    ALWAYS,
}

/**
 * architecture.md §6 — reload is refused or deferred while an OpMode is live. `REJECT` is called
 * out there as "safest; PoC default", which is the default here. `DEFER` and `FORCE` need the
 * runtime-side staging support that `flux-runtime` provides.
 */
enum class SafetyPolicy {
    /** Refuse the deploy outright if an OpMode is running. */
    REJECT,

    /** Stage the bundle and apply it once the running OpMode stops. */
    DEFER,

    /** Request the OpMode stop, wait for it to stop, then reload. */
    FORCE,
}
