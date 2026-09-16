package dev.ryanchhab.flux.gradle.fixtures;

/**
 * An OpMode-shaped class that *references* a driver type without being annotated itself. Ordinary
 * TeamCode does this constantly, and it must stay hot-reloadable: only the annotated class is
 * baked into the robot's HardwareMap at startup.
 */
public class UsesDriverButIsNotOne {
    public Class<?> driver() {
        return PlainDriver.class;
    }
}
