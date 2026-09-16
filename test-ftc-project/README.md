# Test FTC project

Two FTC Robot Controller projects used to verify Flux against the actual SDK, rather than mocks.
Everything in [`docs/design/PHASE-0-RESULTS.md`](../docs/design/PHASE-0-RESULTS.md) was measured
here.

## Two projects, two different wirings

| | `sdk/` | `consumer/` |
|---|---|---|
| **Wired like** | Flux's own working copy (composite build) | a real team's project (published coordinates) |
| **Settings.gradle uses** | `includeBuild('../../flux-gradle')` + `project(':flux-runtime').projectDir = file(...)` | `mavenLocal()` + `plugins { id 'dev.flux.load' version '...' }` + `implementation 'dev.flux:flux-runtime:...'` |
| **Requires** | Flux's source tree at a known relative path | only `gradle publishToMavenLocal` from `flux-runtime` and `flux-gradle` |
| **Purpose** | fast local iteration on Flux itself — no publish step needed to test a change | proves and documents the path an outside team actually uses |
| **Recreated by** | `./test-ftc-project/setup.sh` | manual clone + wiring (see below); no script yet, kept deliberately minimal |

`sdk/` is the long-standing, actively-used project — don't disturb its wiring. `consumer/` exists
specifically to catch friction that only shows up when you consume Flux "for real": plugin
resolution from `mavenLocal()`, missing repository declarations, etc. See
[`docs/GETTING-STARTED.md`](../docs/GETTING-STARTED.md) for the exact incantations, which were
worked out and verified against `consumer/`.

Both are gitignored (see root `.gitignore`) — they're clones of
[FIRST's own repository](https://github.com/FIRST-Tech-Challenge/FtcRobotController), ~300 MB with
history, and not ours to vendor.

## Why `sdk/` isn't committed

It's recreated on demand:

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

## Recreating `consumer/`

Not scripted — it's meant to mirror exactly what's in `docs/GETTING-STARTED.md`, so if it drifts
from the doc, the doc is wrong. Roughly:

```bash
git clone --depth 1 https://github.com/FIRST-Tech-Challenge/FtcRobotController.git test-ftc-project/consumer
```

then apply the changes described in `docs/GETTING-STARTED.md` §"Build file changes" (add
`mavenLocal()` to `pluginManagement.repositories` and `allprojects.repositories` in the root
`settings.gradle`/`build.gradle`, add the `dev.flux.load` plugin and `flux-runtime` dependency to
`TeamCode/build.gradle`), copy in `fixtures/teamcode/*`, and write `local.properties`.

## Gotchas

- **JDK 17.** Newer JDKs fail with `Unsupported class file major version`. Use
  `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- **`local.properties`** is machine-specific and gitignored; `setup.sh` writes it for you.
- The emulator that matches the real Control Hub is **API 25 / arm64**
  (`system-images;android-25;default;arm64-v8a`). Newer images can fail for reasons that say nothing
  about real hardware — see [`docs/design/PHASE-0-TEST-PLAN.md`](../docs/design/PHASE-0-TEST-PLAN.md).
