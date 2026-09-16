package dev.ryanchhab.flux.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as containing live-tunable constants (CONTRACT.md Amendment 3, "Tier L").
 *
 * <p>This is the eligibility gate {@link FluxLiveTuning} checks before writing any field: a field
 * is only ever live-set if its <em>declaring</em> class carries this annotation. Everything else
 * about eligibility ({@code static}, non-{@code final}, primitive/{@code String}/enum type) is
 * checked per-field at apply time — see {@link FluxLiveTuning}'s javadoc for why those live there
 * instead of here.
 *
 * <pre>
 * {@literal @}FluxLive
 * public class DriveConstants {
 *     public static double kP = 0.012;
 *     public static int targetRpm = 4700;
 * }
 * </pre>
 *
 * <p>{@code RUNTIME} retention is required: this annotation is read by reflection against the
 * live, already-running OpMode's classes, not at compile/build time (contrast with the Gradle
 * plugin's own source-level differ, which is a separate, build-time check on the desktop side and
 * out of scope for this module).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FluxLive {
}
