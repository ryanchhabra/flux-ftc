# Flux

Hot code reload for FIRST Tech Challenge robots. Change your TeamCode, run one command, and the new
code is running on the robot without reinstalling the Robot Controller app.

A normal Android Studio deploy rebuilds and reinstalls the whole APK, which takes roughly 40 to 90
seconds depending on the machine. Flux compiles only TeamCode, sends a small DEX file over ADB, and
swaps the classes in the running app.

Measured on an Android API 25 emulator, which matches the REV Control Hub's Android version:

```
Compile           56 ms
Generate           2 ms
Dex              143 ms (cached: 397/399)
Transfer          49 ms (145.0 KB)
Reload           198 ms
---------------------------
Total            448 ms
```

## What it does

- Reloads changed TeamCode without an APK install.
- Adds, renames and deletes OpModes. New classes that were never in the installed APK work.
- Supports Java and Kotlin TeamCode.
- Changes `@FluxLive` constants in a running OpMode without restarting it, in about 200 ms.
- Refuses changes that cannot be hot reloaded, and names the file responsible, instead of
  deploying something that will fail later on the robot.
- Keeps hot loaded code across a Robot Controller restart or power cycle.
- Rolls back to the last working build if a deploy fails, rather than leaving the robot in an
  unknown state.
- Refuses to reload while an OpMode is running, so code is never swapped out from under a
  robot that is driving.

## Requirements

- JDK 17. Newer JDKs fail the FTC build with `Unsupported class file major version`.
- FTC SDK 8.1.0 or newer. Developed against 12.0.0. See [Supported FTC SDK versions](#supported-ftc-sdk-versions).
- Android Studio, and a device reachable over ADB.

## Installation

Add the Flux repository in two places. Plugin resolution and dependency resolution read different
blocks, so both are required.

`settings.gradle`:

```groovy
pluginManagement {
    repositories {
        maven { url 'https://raw.githubusercontent.com/ryanchhabra/flux-ftc/main/maven' }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

`build.gradle` in the project root:

```groovy
allprojects {
    repositories {
        maven { url 'https://raw.githubusercontent.com/ryanchhabra/flux-ftc/main/maven' }
        mavenCentral()
        google()
    }
}
```

`TeamCode/build.gradle`:

```groovy
plugins {
    id 'com.android.application'
    id 'dev.ryanchhab.flux' version '0.1.0-alpha'
}

dependencies {
    implementation 'dev.ryanchhab:flux-runtime:0.1.0-alpha'
}
```

## Usage

Install the app normally once. This is what puts the Flux runtime on the robot.

```bash
./gradlew installDebug
```

After that, use Flux:

```bash
./gradlew fluxDeploy
```

Re-initialize the OpMode on the Driver Station to pick up the new code.

## Connecting over Wi-Fi

Over USB there is nothing to do. To use a Control Hub over Wi-Fi Direct, join its network and
connect once per session:

```bash
./gradlew fluxConnect
```

The Android Studio plugin has a Connect button that does the same thing.

Flux never connects on its own. `adb connect` to an address with nothing listening blocks for 75
seconds before giving up, so doing it automatically before every deploy would cost every team
working over USB 75 seconds per deploy to save one command per session. `fluxConnect` checks the
address is actually reachable first and tells you in about two seconds if it is not.

If your robot is not at the default address:

```groovy
flux {
    robotAddress = '192.168.43.1'
}
```

`./gradlew fluxDisconnect` drops the connection again.

If something is wrong, run:

```bash
./gradlew fluxDoctor
```

It checks that ADB is reachable, a device is connected, the Robot Controller app is installed, the
Flux runtime is running, the runtime and plugin versions match, and the FTC SDK version is supported.
Each check reports `[PASS]`, `[WARN]` or `[FAIL]`. A `[WARN]` marks something Flux cannot fully
vouch for and does not mean anything is broken. Only `[FAIL]` needs action, and each one names a fix.

## Supported FTC SDK versions

Flux is developed and tested against FTC SDK 12.0.0, and works on 8.1.0 and newer.

That floor is measured, not assumed. The Flux runtime uses a fixed set of SDK members, and those
members have identical signatures in every RobotCore and FtcCommon release from 8.1.0 through
12.0.0. Releases older than 8.1.0 are missing `ClassManager.processAllClassesCalled`. Without that
field Flux cannot reset the SDK's one shot class scan guard, so only the first reload of a session
would take effect and every later one would silently do nothing. `fluxDoctor` fails those versions
outright rather than letting a team find out on the field.

On 8.1.0 through 11.2.1, `fluxDoctor` reports `[WARN]`: Flux should work, but those versions are not
part of its routine testing. If something misbehaves there, please report it, with the `fluxDoctor`
output and `adb logcat -s FLUX`.

## Reloading while an OpMode is running

By default Flux refuses. A running OpMode keeps the classes it already loaded, while anything
constructed after a reload comes from the new generation, and those are different types sharing a
name. On a robot that is driving, that is a safety question, not just a correctness one.

The deploy fails with `FAILED_CLEAN`, which means nothing was touched and the robot is still
running the code it was running. Stop the OpMode and deploy again.

Live tuning is not affected. Changing an `@FluxLive` constant sets a field on the running OpMode
and swaps no classes, so it works while the robot is moving. That is the point of it.

To have Flux stop the OpMode for you instead of refusing:

```groovy
flux {
    safetyPolicy = dev.ryanchhab.flux.gradle.SafetyPolicy.FORCE
}
```

`FORCE` asks the OpMode to stop, waits up to two seconds, and reloads only if it actually stopped.
If it does not stop in time the reload is still refused rather than forced through.

## Live tuning

Mark a class of constants:

```java
import dev.ryanchhab.flux.runtime.FluxLive;

@FluxLive
public class DriveConstants {
    public static volatile double kP = 0.012;
    public static volatile int targetRpm = 4700;
}
```

Changing one of these values and deploying sets the field in the running OpMode without restarting
it. `final` fields cannot be live tuned, because the compiler inlines their values at every call
site. Flux warns when it sees one.

`./gradlew fluxRead` reports the values the robot currently holds, next to the values in your source,
and marks any that differ.

## Android Studio plugin

The plugin adds a Flux tool window with device status, a deploy button with per stage timings, and a
live tuning panel that edits `@FluxLive` constants. Editing a value sends it to the robot and writes
it back into your source file, so the file stays the source of truth.

It is not on the JetBrains Marketplace yet. Build and install it from disk:

```bash
cd flux-idea
gradle buildPlugin
```

Then in Android Studio: Settings, Plugins, the gear icon, Install Plugin from Disk, and select
`flux-idea/build/distributions/flux-idea-0.1.0-alpha.zip`.

## What cannot be hot reloaded

These require a normal `installDebug`. Flux detects them and refuses the deploy rather than failing
on the robot:

| Change | Reason |
|---|---|
| Hardware device drivers (`@I2cDeviceType`, `@MotorType`, `@ServoType`, `@DigitalIoDeviceType`, `@AnalogSensorType`, `@DeviceProperties`) | The robot built its HardwareMap from these classes at startup and does not rebuild it on a code only reload |
| AndroidManifest changes | The manifest is read at install time |
| Android resources and assets | Resource IDs are compiled into the installed APK |
| Native libraries | A `.so` cannot be unloaded and reloaded in a running process |
| Gradle dependency changes | New code has to be in the APK |
| FTC SDK version changes | The SDK is the environment the reload mechanism runs inside |

## Status

Version 0.1.0-alpha. Verified on an Android API 25 emulator, which matches the Control Hub's Android
7.1.1, same API level and CPU architecture. Not yet verified on physical hardware. Two behaviours
cannot be tested without a robot: whether the Driver Station refreshes its OpMode list without a
restart, and what happens if a reload is attempted while motors are running.

Treat it as usable for development, not for competition, until it has run on a real Control Hub.

