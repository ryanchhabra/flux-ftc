package dev.flux.runtime;

/**
 * The version of flux-runtime compiled into this Robot Controller app.
 *
 * <p>Reported over {@code dev.flux.PING} so the desktop side can detect version skew: the runtime
 * ships inside the APK and only changes on a full install, while the Gradle plugin changes whenever
 * the developer updates their build file. Those two halves speak one wire protocol
 * (docs/design/CONTRACT.md), so a mismatch produces confusing robot-side failures that look like
 * bugs rather than like "you forgot to reinstall".
 *
 * <p><b>Keep {@link #VERSION} in step with {@code flux-runtime/build.gradle}'s {@code version}.</b>
 * It is a plain constant rather than a generated {@code BuildConfig} field purely to keep this
 * module's build file untouched; if this ever drifts in practice, generating it is the better fix.
 */
public final class FluxVersion {

    /** Must match flux-runtime/build.gradle's `version`. */
    public static final String VERSION = "0.1.0-alpha";

    private FluxVersion() {}
}
