package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/** A minimal OpMode for verifying Flux hot reloads. */
@TeleOp(name = "Flux Test", group = "flux")
public class FluxTest extends LinearOpMode {

    @Override
    public void runOpMode() {
        telemetry.addData("VERSION", "VERSION 1");
        telemetry.addData("BUILD_ID", FluxBuildInfo.buildId());
        telemetry.update();

        waitForStart();

        while (opModeIsActive()) {
            telemetry.addData("VERSION", "VERSION 1");
            telemetry.addData("BUILD_ID", FluxBuildInfo.buildId());
            telemetry.update();
            idle();
        }
    }
}
