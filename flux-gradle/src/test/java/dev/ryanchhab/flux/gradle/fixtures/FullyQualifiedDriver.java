package dev.ryanchhab.flux.gradle.fixtures;

/**
 * The case that defeated the old source-text scan, reproduced on the emulator: the annotation is
 * written out in full, so the source contains no short annotation name to match.
 */
@com.qualcomm.robotcore.hardware.configuration.annotations.I2cDeviceType(name = "Fqn")
public class FullyQualifiedDriver {
}
