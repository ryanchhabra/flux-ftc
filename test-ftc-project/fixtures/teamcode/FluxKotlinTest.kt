package org.firstinspires.ftc.teamcode

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode
import com.qualcomm.robotcore.eventloop.opmode.TeleOp

/** EXPERIMENT D: exercises the Kotlin constructs most likely to break a classloader swap. */

// object singleton -- per-classloader static state, reset on every reload
object KotlinConstants {
    var tuningValue: Double = 1.0
    const val LABEL = "KOTLIN 2"
}

// data class -- generates equals/hashCode/toString, touches kotlin.jvm.internal.Intrinsics
data class DriveSample(val left: Double, val right: Double)

@TeleOp(name = "Flux Kotlin Test", group = "flux")
class FluxKotlinTest : LinearOpMode() {

    // companion object -- another per-classloader static holder
    companion object {
        const val VERSION = "KOTLIN 2"
        fun describe(s: DriveSample) = "L=${s.left} R=${s.right}"
    }

    override fun runOpMode() {
        val sample = DriveSample(0.5, 0.75)
        telemetry.addData("VERSION", VERSION)
        telemetry.addData("LABEL", KotlinConstants.LABEL)
        telemetry.addData("SAMPLE", describe(sample))
        telemetry.update()
        waitForStart()
        while (opModeIsActive()) { idle() }
    }
}
