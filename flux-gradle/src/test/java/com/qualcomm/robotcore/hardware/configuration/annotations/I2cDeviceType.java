package com.qualcomm.robotcore.hardware.configuration.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Stand-in for the real SDK annotation. Only the package, name and RUNTIME retention matter:
 * those are what determine the type descriptor recorded in a compiled class's constant pool,
 * which is what DeviceDriverGuard looks for. Declaring it here keeps the test free of an
 * Android dependency while exercising the exact bytes the real annotation produces.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface I2cDeviceType {
    String name() default "";
}
