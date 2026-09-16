package org.firstinspires.ftc.teamcode;

/**
 * Reads the generated __FluxVersion.BUILD_ID reflectively so TeamCode still compiles
 * before Flux has ever generated it (e.g. a plain Android Studio build).
 *
 * Showing BUILD_ID on the Driver Station is how a human confirms, with their own eyes,
 * that the code running is the code just deployed -- not a cached or stale generation.
 */
public final class FluxBuildInfo {

    private FluxBuildInfo() {}

    public static String buildId() {
        try {
            Class<?> c = Class.forName("org.firstinspires.ftc.teamcode.__FluxVersion");
            Object v = c.getField("BUILD_ID").get(null);
            return v == null ? "<null>" : v.toString();
        } catch (Throwable t) {
            return "<not deployed by flux>";
        }
    }
}
