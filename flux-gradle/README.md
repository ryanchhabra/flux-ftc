# flux-gradle

The Gradle plugin half of FTC Flux — a hot-code-reload pipeline for FIRST Tech Challenge robots.
This module owns everything on the laptop side: compile → dex → transfer → trigger, with stage
timing on every deploy. It never touches the on-robot classloader/reload engine — that's
`flux-runtime` (a separate module, owned separately).

See [`../docs/design/CONTRACT.md`](../docs/design/CONTRACT.md) for the frozen coordinates this
plugin implements, and [`../docs/design/architecture.md`](../docs/design/architecture.md) for the
full design rationale (§4 tiered deploy, §5 performance, §9 ease of use).

## Applying the plugin

Apply `dev.ryanchhab.flux` to your **TeamCode module** — the `com.android.library` module that holds
your OpModes (a `com.android.application` module also works, in case your project structures
TeamCode as the app module directly):

```kotlin
// TeamCode/build.gradle.kts
plugins {
    id("com.android.library")
    id("dev.ryanchhab.flux")
}
```

That's it. No further configuration is required — `./gradlew fluxDeploy` is the only command you
need. adb is auto-located (checks `flux.adbPath`, then `$ANDROID_HOME`, `$ANDROID_SDK_ROOT`,
`$PATH`, then the standard per-OS SDK install location), the robot address defaults to
`192.168.43.1`, and the deploy directory defaults to the CONTRACT.md path.

### Optional configuration

```kotlin
flux {
    // Only set these if the defaults don't fit your setup.
    deployLocation = "/sdcard/FIRST/flux"   // on-robot bundle directory
    adbPath = "/path/to/adb"                // override auto-location
    robotAddress = "192.168.43.1"           // Wi-Fi Direct address
    autoConnect = AutoConnect.MAINTAIN      // NEVER | MAINTAIN | ALWAYS
    safetyPolicy = SafetyPolicy.REJECT      // REJECT | DEFER | FORCE
}
```

## Tasks

| Task | What it does |
|---|---|
| `fluxAssemble` | Generates `__FluxVersion` (the BUILD_ID stamp, CONTRACT.md), wires it into AGP's normal debug compile via the Variant API, and classifies the deploy tier (architecture.md §4) — this is the one task that always runs, since it's what produces the classification everyone else reads. |
| `fluxDex` | Runs D8 directly (`com.android.tools.r8.D8`) against the compiled debug classes, always in a debug-equivalent invocation (no minification, line numbers kept, `--min-api 24`) regardless of the app's own build variant, and packages the result as `flux_bundle.jar`. Uses a persistent, content-hash-keyed per-class dex cache so unchanged classes are never re-dexed — this is the main measured win over full-rebuild competitors. |
| `fluxPush` | `adb push`es the bundle to the on-robot deploy directory, rotating the previous bundle to `flux_bundle.last.jar` first so the runtime has a rollback target. |
| `fluxReload` | `adb shell am broadcast -a dev.ryanchhab.flux.RELOAD`, parses `result=N` from the broadcast output, and reports one of three distinct outcomes — `1` success, `2` failed-clean (safe, still on old code), `3` failed-dirty (**restart the Robot Controller app**) — or, if no result code comes back at all, that the Flux runtime isn't installed. |
| `fluxTune` | **Tier L (live tuning) — CONTRACT.md Amendment 3.** Writes `live_values.json` for every eligible field whose *literal value* changed, `adb push`es it to `/sdcard/FIRST/flux/live_values.json`, then `adb shell am broadcast -a dev.ryanchhab.flux.LIVE_TUNE` and parses `result=N` — same three-outcome shape as `fluxReload` (`1` applied, `2` nothing applied/clean, `3` **partially applied**, i.e. the robot is now in a mixed state). Depends only on `fluxAssemble`, not `fluxDex`/`fluxPush`/`fluxReload` — that's the entire point (see below). |
| `fluxRead` | **Live-value read-back — CONTRACT.md Amendment 4.** The mirror image of `fluxTune`: collects the current `@FluxLive` class/field set straight from source (`LiveTuneDetector.currentFields`), pushes a small JSON request naming those classes, `adb shell am broadcast -a dev.ryanchhab.flux.LIVE_READ`, pulls the robot's answer, and prints a table of source value vs. robot value per field — flagging any that differ. That diff is the point: "the robot is running kP=0.042 but your source says 0.012" without hand-inspecting a JSON file over adb. Standalone — no dependency on `fluxAssemble`/tier classification, since read-back is a query, not a deploy. |
| `fluxDeploy` | The user-facing lifecycle task. Runs the whole pipeline in order and prints a stage-timing table. If tier classification says the change is unsafe to hot-deploy, it refuses immediately — before touching dex/push/reload — and names the exact file that forced it plus the fix (`./gradlew installDebug`). If nothing changed since the last deploy, it says so and skips the rest. If the *only* change was an eligible live-tunable literal, it routes into `fluxTune` instead of the compile/dex/push/reload pipeline and reports `tier L · live` with the fields it set. |
| `fluxDoctor` | One-command setup diagnosis: is adb located, is a device connected and authorized, is the Robot Controller app installed with the Flux runtime's reload receiver registered, and is the FTC SDK version on the classpath one Flux Phase 0 supports. Prints a pass/fail checklist with a fix for each failure. |

## Tier detection (architecture.md §4, live-tuning.md §3)

Implemented tiers:

- **Tier 0 (no-op):** nothing changed since the last deploy (compared by content hash) → skip
  everything.
- **Tier L (live tuning, CONTRACT.md Amendment 3):** the *only* change anywhere in the TeamCode
  source tree was the literal value of one or more eligible `static` fields (or Kotlin
  `object`/`companion object` properties) on a class annotated `@FluxLive` → skip
  compile/dex/push/reload entirely and just `fluxTune` the changed fields (~10-50ms target vs. the
  full pipeline). See [`LiveTuneDetector.kt`](src/main/kotlin/dev/flux/gradle/LiveTuneDetector.kt)
  for exactly what counts as "eligible" and how conservative the differ is — any change it isn't
  certain about (a new method, a changed expression, reordered code, a new import, a `final`/`val`
  field) falls straight through to Tier 1. A `final` (Java) or `val` (Kotlin) field that is
  otherwise eligible gets a build warning explaining it's constant-folded and can never be
  live-tuned, detected for free while the same line is already being parsed.
- **Tier 3 (blocked):** the `AndroidManifest.xml`, `res/`, `assets/`, native libs (`jniLibs/`), or
  the resolved dependency set changed → refuse the deploy, name the exact file/dependency that
  forced it, and tell the user to run `./gradlew installDebug`.
- **Tier 1 (hot):** everything else — hot-reload it.
- **Tier 2 (hardware/device-driver annotation changes):** **not implemented in Phase 0.** It needs
  bytecode scanning for the six device-driver annotations in CONTRACT.md's classloader exclusion
  set, which wasn't finished cleanly in this pass — see the TODO in `TierDetector.kt`
  (`Tier.HotOrHardware`). Today, a Tier-2-worthy change is treated as Tier 1 (allowed to
  hot-reload) rather than silently mishandled, which is the conservative direction to be wrong in.

## Known limitations / TODOs

- **Tier 2 bytecode scanning** — see above.
- **`LiveTuneDetector` is a source-text differ, not a compiler.** It deliberately has no AST, no
  symbol resolution, and no classpath (matching `TierDetector`'s own device-driver scan, the
  established bar in this codebase for "good enough, stays fast, stays simple"). Concretely: an
  enum type is only recognized if the *same file* textually declares `enum ... TypeName`; a
  Kotlin field's type is inferred from its literal shape only when there's no explicit type
  annotation, so an enum-typed Kotlin field without `: Type` is never treated as eligible; and
  multi-declarator statements (`int a = 1, b = 2;`) and multi-line literals are never recognized
  as eligible. All of these fail safe — the field is just never masked, so any future change to it
  correctly forces Tier 1 instead of silently being ignored. See `LiveTuneDetector.kt`'s class doc
  for the full list.
- **`fluxTune`'s LIVE_TUNE result parsing was verified up to and including the push** against the
  real API 25 emulator in `test-ftc-project/`, with correctly-shaped `live_values.json` payloads
  for both Java and Kotlin multi-file, multi-field changes. The `dev.ryanchhab.flux.LIVE_TUNE` broadcast
  itself returned `result=0` (no receiver responded) in that verification run, because the
  robot-side receiver was being built in parallel and wasn't wired up yet at the time — this is
  the same "no result code" outcome `fluxReload` already reports for a missing runtime, not a bug
  in this module.
- **BUILD_ID verification is not yet a true round-trip.** `fluxReload` confirms the *result code*
  from the broadcast, but there's no wire protocol yet (that's `flux-protocol`, Phase 3) to read
  the robot's own echoed `FLUX_BUILD_ID` back. The timing table reports the BUILD_ID it *sent*, not
  a confirmed-matching one — architecture.md §5's "honest caveat" applies here too: no claim is
  made that isn't backed by what was actually measured.
- **`fluxDoctor`'s "runtime present" check is a presence check, not a version check.** It greps
  `adb shell dumpsys package` for a receiver registered against `dev.ryanchhab.flux.RELOAD`, which confirms
  *something* is listening but can't read that runtime's version without a real handshake
  (architecture.md §7, Phase 1+).
- **Dependency-set change detection resolves the module's `debugRuntimeClasspath` (falling back to
  `debugCompileClasspath`) during task configuration.** This is a deliberate simplification for
  Phase 0; it means dependency resolution happens somewhat eagerly rather than being fully deferred
  to execution time.
