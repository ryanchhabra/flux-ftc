# flux-idea

The IntelliJ Platform (Android Studio / IntelliJ IDEA) plugin half of FTC Flux — a tool window
and a keyboard-shortcut action that wrap `./gradlew fluxDeploy` in IDE UI, per
[`../docs/design/architecture.md`](../docs/design/architecture.md) §8 and the positioning finding
in [`../docs/research/ecosystem-positioning.md`](../docs/research/ecosystem-positioning.md) §2-3:
no existing FTC IDE plugin combines a deploy button, live robot status, and hot reload — Sloth is
Gradle-only and makes users hand-build Run Configurations from README screenshots.

This module never touches `flux-runtime`, `flux-gradle`, or `test-ftc-project` — it only shells
out to the target project's own `./gradlew fluxDeploy` and polls `adb devices`.

## What it does (first version)

- **Flux tool window** (right-hand dock, ⚡ icon):
  - Robot connection status, polled from `adb devices` every 3s — connected (with device id) or
    disconnected/no-adb. Located the same way as `flux-gradle`'s `AdbLocator.kt`
    (`flux.adbPath` → `ANDROID_HOME` → `ANDROID_SDK_ROOT` → `PATH` → standard per-OS SDK
    location); duplicated in `AdbLocator.kt` here since this module doesn't depend on
    `flux-gradle`.
  - A **⚡ Deploy** button that runs `fluxDeploy` on the project.
  - Last deploy result: tier (`0`/`L`/`1`/`2`/`3`), total ms, and per-stage timings — parsed from
    `fluxDeploy`'s own `logger.lifecycle` report (see `FluxDeploy.kt` in `flux-gradle`) — or the
    blocked reason/file/fix if the deploy was refused.
  - A scrolling log area with the full task output.
- **"Flux Deploy" action** — Tools menu, and `Ctrl+Shift+D` / `Cmd+Shift+D` (mirroring the
  `⌘⇧D` in architecture.md §8's mockup). Same code path as the button (`FluxService.deploy()`),
  so there's exactly one way this plugin runs a deploy, and it also opens the tool window so the
  result is visible even if it was closed when the shortcut fired.

## How a deploy is run

Two options were on the table: IntelliJ's own Gradle integration
(`ExternalSystemUtil`/`GradleExecutionHelper`, talking to the Tooling API) or shelling out to the
project's `gradlew` wrapper as a plain external process.

**This plugin shells out to `gradlew`** (`FluxDeployRunner.kt`, via
`com.intellij.execution.configurations.GeneralCommandLine` +
`com.intellij.execution.process.OSProcessHandler`). Why:

- `fluxDeploy`'s entire report — tier, timings, blocked reason — is designed as human-readable
  `logger.lifecycle` text, not structured Gradle build events, so the Tooling API's progress
  listeners don't save any parsing work here.
- The Gradle-integration route requires depending on the `org.jetbrains.plugins.gradle` bundled
  plugin and keeping it version-matched to the target IDE build — more surface area than a first
  version needs, and the brief is explicit about not over-engineering this.
- `./gradlew :TeamCode:fluxDeploy` is the already-verified entry point (works on a real API 25
  emulator, ~400ms hot / ~200ms live-tune per the brief). Running the same wrapper as a subprocess
  reproduces that known-good path exactly.

`OSProcessHandler` owns its own output-reading threads; `FluxDeployRunner` never calls a blocking
`waitFor()`, so the EDT is never blocked. Every UI update from a process callback hops back to the
EDT via `ApplicationManager.invokeLater` (`FluxDeployRunner.onEdt`).

`FluxService` (a project-level `@Service`) owns adb polling (via a pooled-thread `Alarm`) and the
current/last deploy result, so the tool window and the Tools-menu action always agree on state.

## Output parsing

`DeployOutputParser.kt` pattern-matches the exact strings `FluxDeploy.kt`'s `report()` prints:

- `"Flux cannot hot-deploy this change."` → **BLOCKED**, with `Reason:`/`File:`/`Fix:` extracted.
- `"nothing changed since the last deploy"` → **NOOP** (tier 0).
- The `FTC Flux  ●  <addr>  ·  <tier>` header + stage rows + `Total ... ms` line → **SUCCESS**,
  with tier label, per-stage `DeployStage`s, total ms, and `BUILD_ID` if present.
- Anything else (a real compile error, a crashed daemon) → **FAILED**, with the raw log shown —
  no attempt to guess a reason that isn't there.

There's no machine-readable contract for this between `flux-gradle` and `flux-idea` yet (Phase 0's
CONTRACT.md doesn't define one) — if `FluxDeploy.kt`'s report format changes, this parser needs to
change with it.

## Running it in a sandbox IDE

```
cd flux-idea
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home ./gradlew runIde
```

This launches a throwaway IntelliJ IDEA Community sandbox with the plugin installed. **Not
attempted in this environment** — too heavy for a sandboxed build agent (downloads/launches a full
IDE instance); `./gradlew build` is what was actually run and verified here.

## Build

```
cd flux-idea
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home gradle build
# or: JAVA_HOME=... ./gradlew build
```

Verified: `gradle build` and `./gradlew build` both pass (compile, `patchPluginXml`, `jar`,
`composedJar`, `assemble`). A newer JDK than 17 fails the same way it does for `flux-gradle`
(`Unsupported class file major version`).

Built with the **IntelliJ Platform Gradle Plugin 2.19.0**, targeting **IntelliJ IDEA Community
2024.1.7** (`sinceBuild = "241"`) rather than an Android Studio SDK directly — Android Studio is
built on the same platform, so this keeps the build self-contained while still installing into
Android Studio at runtime. Kotlin 2.0.21, JVM toolchain 17.

## Current limitations

- **Connection status is adb-only.** No SDK/runtime-version display (architecture.md §8's mockup
  shows "SDK 12.0.0 · runtime OK") — that needs a handshake protocol that doesn't exist yet (see
  `flux-gradle/README.md`'s "Known limitations": `fluxDoctor`'s runtime check is presence-only,
  not version-aware).
  Only bumps IC 2024.1's `sinceBuild`; no upper bound (`untilBuild`) is set, so it isn't
  explicitly tested against future IDE majors — `pluginVerification { ides { recommended() } }`
  is configured but was not run as part of this build (it downloads and boots real IDE
  distributions, out of scope for what was verified here).
- **No inline editor annotations for compile errors, no embedded Dashboard/telemetry panel.**
  Both are explicitly later work per architecture.md §8 ("Beyond the button: ... later, an
  embedded Dashboard/telemetry panel").
- **No auto-registered Run Configurations.** architecture.md §8 phases that in *before* the tool
  window; this plugin only builds the tool window + action, per the brief's tighter first-version
  scope.
- **No settings panel.** Robot address, adb path overrides, etc. are not surfaced in the plugin —
  intentionally, per the brief's "no settings panels" constraint. `flux { adbPath = ... }` in the
  target project's own `build.gradle.kts` still works for `fluxDeploy` itself; this plugin's own
  adb-polling just doesn't read that override yet (it can't see the Gradle build's `flux {}`
  block without evaluating it, which is exactly the parsing/Gradle-integration cost this module
  avoided — see "How a deploy is run" above).
- **Task name is hardcoded to the bare `fluxDeploy` task**, not a module-qualified path like
  `:TeamCode:fluxDeploy`. Gradle resolves a bare task name against whichever module applies
  `dev.ryanchhab.flux`, which works for the common single-TeamCode-module layout but would need
  disambiguation in a multi-module project with more than one module applying the plugin.
- **`runIde` (launching a real sandbox IDE) was not attempted** in this environment, per the
  task's own instruction — only `gradle build` was verified.
