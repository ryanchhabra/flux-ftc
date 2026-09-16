package org.firstinspires.ftc.teamcode;

import dev.ryanchhab.flux.runtime.FluxLive;

/** Live-tunable constants. Edit a value, save, deploy — no reload, no OpMode restart. */
@FluxLive
public class DriveConstants {
    public static volatile double kP = 0.042;
    public static volatile double kI = 0.003;
    public static volatile int targetRpm = 5200;
    public static volatile boolean useVision = false;

    // Deliberately final: Flux must WARN that this can't be live-tuned (constant-folded).
    public static final double MAX_POWER = 1.0;
}
