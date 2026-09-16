package dev.ryanchhab.flux.gradle.fixtures;

/**
 * The false-positive case. This class talks about device annotations but carries none, so it is
 * not a device driver and must not block a deploy. The old source-text scan blocked on exactly
 * this: a comment mentioning the name was enough.
 */
public class MentionsAnnotationInTextOnly {
    /** A string literal is not an annotation either. */
    public static final String DOC = "Annotate drivers with MotorType or I2cDeviceType.";
}
