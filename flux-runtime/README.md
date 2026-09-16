# flux-runtime

The half of Flux that ships **inside** the Robot Controller APK. Java 8 only, no Kotlin — see
`build.gradle`'s comment for why (this module is compiled permanently into every RC app that
adopts Flux; the Gradle plugin (`flux-gradle`, a separate module) is the half that runs on the
laptop and does not ship here).

Grounded in `../docs/design/CONTRACT.md` (frozen names/paths/result codes),
`../docs/design/architecture.md` §3 and §6, and the research in `../docs/research/`. Every class
below cites the specific section it implements.

## Classes

### `FluxRootClassLoader`
Wraps the RC app's real classloader and refuses to resolve `org.firstinspires.ftc.teamcode.*`,
always. Adopted from Sloth's `RootClassLoader` (`sloth-teardown.md` §1). This one deliberate hole
is what forces the pushed bundle jar to be the sole source of truth for TeamCode on every
generation — including brand-new classes, which is the capability Fast Load's child-first-override
design appears to lack (`hot-reload-prior-art.md` §2.5).

### `FluxClassLoader`
`extends dalvik.system.PathClassLoader`, built fresh over the pushed bundle jar on every reload,
with `FluxRootClassLoader` as parent. Enforces CONTRACT.md's classloader exclusion set:

- Package-prefix exclusions (`kotlin.`, `kotlinx.`, `java.`, `javax.`, `android.`, `androidx.`,
  `dalvik.`, `com.qualcomm.`, `org.firstinspires.ftc.` except `...teamcode.`) always delegate to
  the parent — critical for `kotlin.*`/`kotlinx.*` in particular (`risks.md` §2: a duplicate-defined
  `Intrinsics`/`Unit` causes cross-generation `ClassCastException`s that look like anything but a
  reload bug; Fast Load has no such exclusion at all).
- The six device-configuration annotations (`@I2cDeviceType`, `@MotorType`, `@ServoType`,
  `@DigitalIoDeviceType`, `@AnalogSensorType`, `@DeviceProperties`) are pinned: the first `Class`
  resolved for such a name is cached in a loader-spanning static map and reused on every later
  generation, rather than being redefined by a fresh dex each reload. This is necessary (not just
  cautious) because custom device drivers conventionally live *inside*
  `org.firstinspires.ftc.teamcode`, where `FluxRootClassLoader` categorically refuses parent
  resolution — so "always resolve from the parent" has to mean "always resolve to the same
  identity across generations," which this pin cache provides. Root cause: `HardwareMap.get()`
  type-checks by `Class` identity against `HardwareDevice` instances built once at config-parse
  time; a fresh redefinition is a different, incompatible identity (`risks.md` §1).

### `FluxReloadEngine`
The reload sequence (`architecture.md` §3.2):

1. Build a fake `OnBotJavaHelper` whose `createOnBotJavaClassLoader()` returns a fresh
   `FluxClassLoader` and whose `getOnBotJavaClassNames()` returns the bundle's dex entries. Its
   `DexFile` is opened twice on purpose — a reference-implementation workaround for a reload bug
   (`hot-reload-prior-art.md` §2.1); do not "simplify" this without hardware testing.
2. Wrap the whole registry mutation in `RegisteredOpModes.getInstance().lockOpModesWhile(Runnable)`
   — an SDK-provided transaction primitive neither Fast Load nor Sloth uses here, making the swap
   atomic (`risks.md` §5).
3. Inside the lock: `setOnBotJavaClassHelper` (public API), reset the private
   `processAllClassesCalled` guard via reflection — **the only private reflection in this
   codebase**, isolated in `resetProcessAllClassesGuard()` — then `processAllClasses()`,
   `registerAllOpModes(no-op)`, `filterExternalLibrariesClassesStart()`,
   `setExternalLibrariesChanged()`.
4. Verify: load `org.firstinspires.ftc.teamcode.__FluxVersion` **through the new loader**, read
   `BUILD_ID`, log `FLUX_BUILD_ID=<id>`. CNFE or a mismatch against an (optional) expected id ⇒
   result code 3.
5. Generation assertion: every currently-registered OpMode must have been loaded by the new
   generation's loader. See the in-code note on why this is implemented via
   `RegisteredOpModes.getOpMode(name)` rather than a direct `Class` walk — `getOpModes()` returns
   `OpModeMeta`, which (verified via `javap`) carries no `Class` reference at all, unlike what an
   early draft of the architecture doc assumed.
6. Returns exactly one of CONTRACT.md's three result codes — never swallows an exception into a
   single outcome (`risks.md` §6). Any failure *before* the SDK transaction starts is
   `RESULT_FAILED_CLEAN` (nothing touched, safe); any failure *during or after* the transaction is
   `RESULT_FAILED_DIRTY` (state unknown, tell the user to restart the RC app).

### `FluxReloadReceiver`
A `BroadcastReceiver` for `dev.ryanchhab.flux.RELOAD`, registered dynamically via
`Context.registerReceiver` from `@OnCreate`/`@OnDestroy` (`org.firstinspires.ftc.ftccommon.external`)
rather than a manifest `<receiver>` entry — matches the reference implementation
(`hot-reload-prior-art.md` §2.4). Calls `FluxReloadEngine.reload(...)` and reports the result via
`setResultCode(...)` so the Gradle side can parse `result=N` from `am broadcast` stdout.

### `FluxLive`
A `@Retention(RUNTIME) @Target(TYPE)` marker annotation (CONTRACT.md Amendment 3). A class carries
it to declare "the static fields on me are live-tunable." `FluxLiveTuning` refuses to write any
field whose declaring class isn't annotated with it — this is the eligibility gate, checked by
reflection at apply time, not at build time.

### `FluxLiveTuning`
Tier L — "live tuning" (`live-tuning.md`, CONTRACT.md Amendment 3). Applies a batch of `static`
field writes directly to the already-running OpMode's classes: no compile, no dex, no classloader
swap, target ~10-50 ms instead of a reload's ~0.5-1 s.

1. Reads CONTRACT.md's payload file (`/sdcard/FIRST/flux/live_values.json`) — a flat JSON array of
   `{class, field, type, value}`, all string-valued. Parsed with a small hand-rolled scanner rather
   than a JSON library dependency, since the shape is fixed and trivial by design. A missing or
   malformed file produces result code 2 (clean — nothing touched), never a crash.
2. For each entry: resolves the class **through the current Flux generation's classloader**
   (`FluxReloadEngine.getCurrentGenerationLoader()`) — the same class-identity rule as Amendment 1,
   because writing to a stale generation's `Class` object silently does nothing while the running
   OpMode uses the current generation's class. If no Flux reload has happened yet this session,
   there is no Flux generation at all, so it falls back to the app classloader, which is what's
   actually running the OpMode in that case.
3. Per field, refuses (with a distinct logged reason, never silently skipped): non-`static` fields,
   `final` fields (with an explicit message that a `final` primitive/`String` is constant-folded
   into call sites at compile time, so writing it changes nothing observable — a real trap, not
   just caution), fields whose declaring class isn't `@FluxLive`, and unsupported types (anything
   other than a primitive, `String`, or enum).
4. Result codes reuse `FluxReloadEngine`'s triad: `1` every entry applied, `2` nothing applied
   (clean — missing/malformed file, empty array, or every entry was ineligible), `3` some entries
   applied and some didn't (the robot is now running a mix of old and new constant values — unlike
   a reload this isn't "unknown state," every outcome was individually observed and logged, but the
   caller must still be told it wasn't all-or-nothing).

### `FluxLiveTuneReceiver`
A `BroadcastReceiver` for `dev.ryanchhab.flux.LIVE_TUNE`, registered the same `@OnCreate`/`@OnDestroy` way as
`FluxReloadReceiver` — a separate receiver/action rather than teaching `FluxReloadReceiver` a second
action, so a live-tune broadcast can never be misrouted into the much more expensive reload path.
Calls `FluxLiveTuning.apply(...)` and reports the result via `setResultCode(...)`.

### `FluxLiveReadback`
Live-value read-back (CONTRACT.md Amendment 4) — the mirror image of `FluxLiveTuning`. The desktop
already knows the full `@FluxLive` class set (the same scan `LiveTuneDetector` does for `fluxTune`),
so instead of scanning the whole dex on-device it pushes a small JSON request naming the classes it
wants (`live_read_request.json`, a flat array of class-name strings). This class resolves each name
**through the current Flux generation's classloader** — the same rule as Amendment 3, for the same
reason: reading a stale generation's `Class` object would report a value the running OpMode isn't
actually using — reads every eligible static field (same eligibility as `FluxLiveTuning`: declared
directly on an `@FluxLive` class, `static`, not `final`, primitive/`String`/enum), and writes the
current values to `live_values_current.json` in the exact same flat-array shape
`FluxLiveTuning`'s payload uses, so the desktop reuses one parser for both directions. Same
three-code result triad.

### `FluxLiveReadReceiver`
A `BroadcastReceiver` for `dev.ryanchhab.flux.LIVE_READ`, registered the same `@OnCreate`/`@OnDestroy` way as
the other two receivers, and kept separate from them for the same "one action, one receiver, one
result path" reasoning. Calls `FluxLiveReadback.apply(...)` and reports the result via
`setResultCode(...)`.

## What's verified vs. assumed

Every SDK method/field signature this module calls was checked with `javap -p` against the real
SDK 12.0.0 AARs (RobotCore, FtcCommon) rather than assumed from the research docs. One real
discrepancy turned up during that verification: `RegisteredOpModes.getOpModes()` returns
`List<OpModeMeta>` (metadata only — name/flavor/group/description, no `Class` field), not a
`Class`-bearing type as an early architecture-doc draft implied. `FluxReloadEngine`'s generation
assertion works around this by using `RegisteredOpModes.getOpMode(name)` to obtain a live instance
and checking its `Class`'s loader instead — see the code comment there for the exact trade-off
(it briefly instantiates every registered OpMode; never calls `init()`/`loop()` on it).

## What's out of scope here

- Known-good bundle rollback (`flux_bundle.last.jar`, CONTRACT.md paths) — this module does not
  automatically re-reload from the last-good bundle on `RESULT_FAILED_DIRTY`. That's file/process
  management that can layer on top of `FluxReloadEngine.reload(...)`; adding it silently would have
  gone beyond what this module's remit describes.
- Thread/listener hygiene (interrupting TeamCode-spawned threads, unregistering
  `OpModeManagerNotifier` listeners before swapping — `architecture.md` §6) is not implemented in
  this pass; flagged as a known gap versus the full safety model, not an oversight.
- Tiered deploy classification (`architecture.md` §4) is a build-time concern that lives in
  `flux-gradle`, not here.
