package dev.ryanchhab.flux.gradle.fixtures;

import com.qualcomm.robotcore.hardware.configuration.annotations.ServoType;

/**
 * Long and double constants occupy two constant pool slots each. A parser that advances by one
 * reads every later entry at the wrong index, which is the classic silent failure in hand-written
 * class file parsing, so a driver carrying them belongs in the test set.
 */
@ServoType(name = "Wide")
public class DriverWithWideConstants {
    public static final long TICKS = 1234567890123L;
    public static final double RATIO = 3.14159265358979;
    public static final long OTHER = 987654321098L;
    public static final double SECOND = 2.718281828459045;
}
