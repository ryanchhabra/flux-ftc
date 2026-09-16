package dev.ryanchhab.flux.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pure-logic tests for [FluxLiveSourceScanner] -- no IntelliJ platform involved, since
 * [FluxLiveSourceScanner.scanText] takes plain text and never touches disk or the platform. This
 * is deliberately the part of live tuning that's testable headlessly, per the brief: an actual
 * drag on the slider needs a human in the IDE, but "does the scanner find the right field, at the
 * right offset, with the right type" does not.
 */
class FluxLiveSourceScannerTest {

    private val driveConstantsJava = """
        package org.firstinspires.ftc.teamcode;

        import dev.ryanchhab.flux.runtime.FluxLive;

        /** Live-tunable constants. */
        @FluxLive
        public class DriveConstants {
            public static volatile double kP = 0.042;
            public static volatile double kI = 0.003;
            public static volatile int targetRpm = 5200;
            public static volatile boolean useVision = false;

            // Deliberately final: Flux must not treat this as live-tunable.
            public static final double MAX_POWER = 1.0;
        }
    """.trimIndent()

    @Test
    fun `finds every eligible field with correct type and value`() {
        val fields = FluxLiveSourceScanner.scanText(File("DriveConstants.java"), driveConstantsJava)
        val byName = fields.associateBy { it.fieldName }

        assertEquals(4, fields.size, "MAX_POWER (final) must be excluded")
        assertEquals("double", byName.getValue("kP").type)
        assertEquals("0.042", byName.getValue("kP").value)
        assertEquals("int", byName.getValue("targetRpm").type)
        assertEquals("5200", byName.getValue("targetRpm").value)
        assertEquals("boolean", byName.getValue("useVision").type)
        assertEquals("false", byName.getValue("useVision").value)
        assertTrue(fields.none { it.fieldName == "MAX_POWER" })
    }

    @Test
    fun `computes fully qualified class name from package`() {
        val fields = FluxLiveSourceScanner.scanText(File("DriveConstants.java"), driveConstantsJava)
        assertEquals("org.firstinspires.ftc.teamcode.DriveConstants", fields.first().fqClassName)
    }

    @Test
    fun `literal range points exactly at the literal text, not the whole declaration`() {
        val fields = FluxLiveSourceScanner.scanText(File("DriveConstants.java"), driveConstantsJava)
        val kP = fields.first { it.fieldName == "kP" }
        val slice = driveConstantsJava.substring(kP.literalRange.first, kP.literalRange.last + 1)
        assertEquals("0.042", slice)
    }

    @Test
    fun `a non-FluxLive class contributes nothing`() {
        val text = """
            package foo;
            public class Plain {
                public static double kP = 0.5;
            }
        """.trimIndent()
        assertTrue(FluxLiveSourceScanner.scanText(File("Plain.java"), text).isEmpty())
    }

    @Test
    fun `Kotlin companion object var fields are found, val fields are not`() {
        val text = """
            package org.firstinspires.ftc.teamcode

            import dev.ryanchhab.flux.runtime.FluxLive

            @FluxLive
            class KtConstants {
                companion object {
                    var kP: Double = 0.09
                    val locked: Double = 9.9
                }
            }
        """.trimIndent()
        val fields = FluxLiveSourceScanner.scanText(File("KtConstants.kt"), text)
        assertTrue(fields.any { it.fieldName == "kP" })
        assertTrue(fields.none { it.fieldName == "locked" })
    }

    @Test
    fun `an expression initializer is not eligible`() {
        val text = """
            package p;
            @FluxLive
            public class C {
                public static double derived = 1.0 + 2.0;
            }
        """.trimIndent()
        assertTrue(FluxLiveSourceScanner.scanText(File("C.java"), text).isEmpty())
    }

    @Test
    fun `scanFile on a missing file returns empty rather than throwing`() {
        val fields = FluxLiveSourceScanner.scanFile(File("/does/not/exist/DriveConstants.java"))
        assertTrue(fields.isEmpty())
    }
}
