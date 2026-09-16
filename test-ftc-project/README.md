# Test FTC project

A real FTC Robot Controller project used to verify Flux against the actual SDK, rather than mocks.
Everything in [`docs/design/PHASE-0-RESULTS.md`](../docs/design/PHASE-0-RESULTS.md) was measured here.

## Why `sdk/` isn't committed

It's [FIRST's own repository](https://github.com/FIRST-Tech-Challenge/FtcRobotController) — roughly
300 MB with history, and not ours to vendor. It's gitignored and recreated on demand:

```bash
./test-ftc-project/setup.sh
```

What *is* committed is the small part that's actually ours:

| | |
|---|---|
| `fixtures/teamcode/` | The test OpModes (`FluxTest`, `FluxKotlinTest`, `DriveConstants`, `FluxBuildInfo`) |
| `fixtures/wiring.patch` | The build changes that apply Flux to the SDK project |
| `fixtures/sdk-commit.txt` | The exact SDK commit Flux was last verified against |

## What the fixtures are for

| File | Purpose |
|---|---|
| `FluxTest.java` | Experiment A — change a string, confirm it hot-deploys |
| `FluxBuildInfo.java` | Reads the generated `__FluxVersion.BUILD_ID` reflectively, so you can *see* on the Driver Station that the running code is the code you just deployed |
| `FluxKotlinTest.kt` | Experiment D — `object` singleton, `companion object`, and a data class, to exercise the `kotlin.*` classloader exclusion |
| `DriveConstants.java` | A `@FluxLive` class for live tuning, including a deliberately `final` field to check Flux warns that it can't be tuned |

## Gotchas

- **JDK 17.** Newer JDKs fail with `Unsupported class file major version`. Use
  `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- **`local.properties`** is machine-specific and gitignored; `setup.sh` writes it for you.
- The emulator that matches the real Control Hub is **API 25 / arm64**
  (`system-images;android-25;default;arm64-v8a`). Newer images can fail for reasons that say nothing
  about real hardware — see [`docs/design/PHASE-0-TEST-PLAN.md`](../docs/design/PHASE-0-TEST-PLAN.md).
