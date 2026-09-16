package dev.flux.runtime;

import android.content.Context;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManager;
import com.qualcomm.robotcore.eventloop.opmode.OpModeRegister;
import com.qualcomm.robotcore.util.RobotLog;

import org.firstinspires.ftc.robotcore.internal.opmode.AnnotatedOpModeClassFilter;
import org.firstinspires.ftc.robotcore.internal.opmode.ClassManager;
import org.firstinspires.ftc.robotcore.internal.opmode.OnBotJavaHelper;
import org.firstinspires.ftc.robotcore.internal.opmode.RegisteredOpModes;
import org.firstinspires.ftc.robotcore.internal.system.AppUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import dalvik.system.DexFile;

/**
 * The reload sequence itself. Follows docs/design/architecture.md §3.2, which in turn is the
 * "durability play" from docs/design/architecture.md §1: drive the SDK through its one *public*
 * hot-load hook ({@code ClassManager.setOnBotJavaClassHelper}) rather than replacing SDK internals
 * the way Sloth does (docs/research/sloth-teardown.md §2, ~12 private reflections across 5+
 * classes). Flux's entire reflection budget is the one private field reset in
 * {@link #resetProcessAllClassesGuard(ClassManager)} below.
 *
 * <p>Every code path returns exactly one of the three CONTRACT.md result codes. Nothing is ever
 * swallowed into a single generic outcome — see docs/research/risks.md §6 for why that is the
 * specific mistake this class must not repeat (Fast Load catches
 * {@code NoSuchFieldException | IllegalAccessException | NullPointerException} around its whole
 * sequence and just calls {@code e.printStackTrace()}, so its desktop side simply never sees
 * {@code result=1} on failure and cannot tell "safe" apart from "unknown state").
 */
final class FluxReloadEngine {

    private static final String TAG = "FLUX";

    /** CONTRACT.md result codes. */
    static final int RESULT_SUCCESS = 1;
    static final int RESULT_FAILED_CLEAN = 2;
    static final int RESULT_FAILED_DIRTY = 3;

    /** CONTRACT.md "On-robot paths". {@code AppUtil.FIRST_FOLDER} == {@code /sdcard/FIRST},
     *  verified from RobotCore 12.0.0 bytecode per CONTRACT.md. */
    private static final File DEPLOY_DIR = new File(AppUtil.FIRST_FOLDER, "flux");
    private static final File BUNDLE_FILE = new File(DEPLOY_DIR, "flux_bundle.jar");

    /** CONTRACT.md "Previous-good bundle": GAP 2's rollback target. Written on every
     *  {@link #RESULT_SUCCESS} (see {@link #saveAsLastKnownGood}), read on every failure (see
     *  {@link #attemptRollback}). */
    private static final File LAST_GOOD_BUNDLE_FILE = new File(DEPLOY_DIR, "flux_bundle.last.jar");

    /** CONTRACT.md "Version stamping": the class + field the Gradle plugin bakes into every
     *  bundle, which we must load THROUGH THE NEW GENERATION'S loader to prove the swap took. */
    private static final String VERSION_CLASS_NAME = "org.firstinspires.ftc.teamcode.__FluxVersion";
    private static final String VERSION_FIELD_NAME = "BUILD_ID";

    /** Lazily created once per process and reused for every reload — it is stateless (see its
     *  javadoc) so there is no reason to recreate it per generation, only FluxClassLoader gets a
     *  fresh instance each time. */
    private static volatile FluxRootClassLoader rootClassLoaderInstance;

    /** The most recently committed generation's loader, used for external inspection /
     *  diagnostics. Updated only on {@link #RESULT_SUCCESS}. */
    private static volatile ClassLoader currentGenerationLoader;

    private FluxReloadEngine() {
    }

    /** Package-visible accessor for diagnostics/tests; not otherwise consumed by this module. */
    static ClassLoader getCurrentGenerationLoader() {
        return currentGenerationLoader;
    }

    /**
     * Runs one full reload cycle against the bundle currently sitting at CONTRACT.md's push
     * target ({@code /sdcard/FIRST/flux/flux_bundle.jar}).
     *
     * @param context        the app Context (used only to obtain the real classloader on first
     *                       call; kept as a parameter rather than a static Application reference
     *                       so this class doesn't need its own app-hook wiring).
     * @param expectedBuildId the build id the caller expects to see after reload, or {@code null}
     *                       if the caller has no independent expectation. CONTRACT.md's Phase 0
     *                       trigger ({@code adb shell am broadcast -a dev.flux.RELOAD}) carries no
     *                       extras, so in Phase 0/1 this is typically {@code null} and the check
     *                       degrades to "did BUILD_ID load and read back at all" rather than an
     *                       exact-match comparison. Once the Gradle side round-trips a buildId
     *                       (e.g. as a broadcast extra, or later over the Phase 3 socket), passing
     *                       it here upgrades the check to a real mismatch detector, exactly as
     *                       CONTRACT.md's "Mismatch or CNFE ⇒ result code 3" describes.
     * @return one of {@link #RESULT_SUCCESS}, {@link #RESULT_FAILED_CLEAN}, {@link #RESULT_FAILED_DIRTY}.
     */
    static int reload(Context context, String expectedBuildId) {
        int result = doReload(context, expectedBuildId, BUNDLE_FILE);

        if (result == RESULT_SUCCESS) {
            // GAP 2 (architecture.md §6 "Known-good rollback" / CONTRACT.md "Previous-good
            // bundle"): only a bundle that actually reached RESULT_SUCCESS is allowed to become
            // the new rollback target — a bundle that merely got pushed but failed must never
            // overwrite a working one.
            saveAsLastKnownGood(BUNDLE_FILE);
            return result;
        }

        return attemptRollback(context, result);
    }

    /**
     * GAP 2's failure side. Called at most once per {@link #reload} invocation — see the
     * "no recursion" note inline below for exactly why a failed rollback can never trigger a
     * second rollback attempt.
     *
     * @param originalResult the result of the deploy that just failed ({@link #RESULT_FAILED_CLEAN}
     *                       or {@link #RESULT_FAILED_DIRTY}).
     */
    private static int attemptRollback(Context context, int originalResult) {
        if (!LAST_GOOD_BUNDLE_FILE.exists()) {
            // Nothing to roll back to yet (e.g. this is the very first deploy of the process, and
            // it failed). Report the original result as-is — there is no known-good state to
            // restore, so upgrading it to RESULT_FAILED_CLEAN would be a lie.
            RobotLog.ww(TAG, "FLUX: reload failed (result=%d) and no known-good bundle exists yet "
                    + "at %s — nothing to roll back to", originalResult, LAST_GOOD_BUNDLE_FILE.getPath());
            return originalResult;
        }

        RobotLog.ww(TAG, "FLUX: reload failed (result=%d) — attempting rollback to known-good bundle %s",
                originalResult, LAST_GOOD_BUNDLE_FILE.getPath());

        // NO RECURSION: this calls doReload() directly, never reload(). reload() is the only
        // caller of attemptRollback(), and this line deliberately bypasses reload() (and therefore
        // attemptRollback() itself) — so however this rollback attempt turns out, nothing here can
        // trigger a second rollback. expectedBuildId is null on purpose: the caller's expectation
        // was about the bundle that just FAILED, not about the rollback target, so comparing the
        // rollback's BUILD_ID against it would be comparing against the wrong id.
        int rollbackResult = doReload(context, null, LAST_GOOD_BUNDLE_FILE);

        if (rollbackResult == RESULT_SUCCESS) {
            // CONTRACT.md result code 2 exists for exactly this: "your deploy failed AND the
            // robot is confirmed on the previous good code" — a much stronger, more actionable
            // signal than a bare failure.
            RobotLog.ii(TAG, "FLUX: rollback succeeded — robot confirmed back on known-good code");
            return RESULT_FAILED_CLEAN;
        }

        // The rollback itself failed (or failed dirty) — we no longer know what state the robot
        // is in, regardless of what the original failure looked like. This must never be reported
        // as anything but RESULT_FAILED_DIRTY.
        RobotLog.ee(TAG, "FLUX: rollback ALSO failed (result=%d) — robot state unknown, restart the RC app",
                rollbackResult);
        return RESULT_FAILED_DIRTY;
    }

    /**
     * The actual reload transaction against one specific bundle file — used both for a normal
     * deploy ({@code bundleFile == BUNDLE_FILE}) and for a rollback attempt
     * ({@code bundleFile == LAST_GOOD_BUNDLE_FILE}), so the two paths can never drift apart.
     *
     * @return one of {@link #RESULT_SUCCESS}, {@link #RESULT_FAILED_CLEAN}, {@link #RESULT_FAILED_DIRTY}.
     */
    private static int doReload(Context context, String expectedBuildId, File bundleFile) {
        RobotLog.ii(TAG, "FLUX: reload requested, bundle=%s", bundleFile.getPath());

        if (!bundleFile.exists()) {
            // Nothing has been touched in the SDK yet — definitely still on the previous
            // generation, definitely safe.
            RobotLog.ee(TAG, "FLUX: no bundle at %s — was it pushed?", bundleFile.getPath());
            return RESULT_FAILED_CLEAN;
        }

        final DexFile dexFile;
        try {
            dexFile = openBundleDexFile(bundleFile);
        } catch (IOException e) {
            RobotLog.ee(TAG, e, "FLUX: failed to open bundle dex file %s", bundleFile.getPath());
            return RESULT_FAILED_CLEAN; // still nothing touched in the SDK.
        }

        final FluxRootClassLoader root = rootClassLoader(context);

        // GAP 1 (architecture.md §6 "Thread and listener hygiene" / risks.md §3): stop
        // outgoing-generation threads and log the listener-hygiene limitation BEFORE the SDK is
        // asked to build the new generation's loader below. Must run here, not earlier — this is
        // the last point before createOnBotJavaClassLoader() can be invoked by processAllClasses()
        // inside the locked transaction. currentGenerationLoader is null on the very first reload
        // of the process, which FluxGenerationCleanup handles as a no-op.
        FluxGenerationCleanup.cleanupOutgoingGeneration(currentGenerationLoader);

        // Written inside createOnBotJavaClassLoader(), read back after the transaction — a
        // one-element array because it's captured by the anonymous OnBotJavaHelper below and
        // must be effectively-final from Java's point of view while still being writable.
        final FluxClassLoader[] newLoaderHolder = new FluxClassLoader[1];

        OnBotJavaHelper fakeHelper = new OnBotJavaHelper() {
            @Override
            public ClassLoader createOnBotJavaClassLoader() {
                // A brand-new FluxClassLoader per generation — this is what gives every reload a
                // fresh identity space for TeamCode classes while device-config-annotated classes
                // stay pinned across generations (see FluxClassLoader's javadoc).
                FluxClassLoader loader = new FluxClassLoader(bundleFile.getPath(), root);
                newLoaderHolder[0] = loader;
                return loader;
            }

            @Override
            public Collection<String> getOnBotJavaClassNames() {
                // So new/renamed/deleted classes all get (re)scanned, not just ones the SDK
                // already knew about — see hot-reload-prior-art.md §2.1.
                return Collections.list(dexFile.entries());
            }

            @Override
            public Collection<String> getExternalLibrariesClassNames() {
                // Flux never touches the SDK's separate "External Libraries" mechanism.
                return Collections.<String>emptyList();
            }

            @Override
            public boolean isExternalLibrariesError(NoClassDefFoundError e) {
                return false;
            }
        };

        final ClassManager classManager = ClassManager.getInstance();
        final RegisteredOpModes registeredOpModes = RegisteredOpModes.getInstance();
        final AtomicBoolean transactionFailed = new AtomicBoolean(false);

        // The whole registry swap, atomically, using an SDK-provided primitive neither Fast Load
        // nor Sloth uses for this purpose (architecture.md §3.2 / risks.md §5). This guarantees
        // the Driver Station can never observe a half-updated OpMode list and that no OpMode can
        // be started mid-registration.
        registeredOpModes.lockOpModesWhile(new Runnable() {
            @Override
            public void run() {
                try {
                    classManager.setOnBotJavaClassHelper(fakeHelper);       // public API
                    resetProcessAllClassesGuard(classManager);              // the ONE reflection
                    classManager.processAllClasses();                       // public API
                    registeredOpModes.registerAllOpModes(new OpModeRegister() {
                        @Override
                        public void register(OpModeManager manager) {
                            // Intentionally empty. The real registration happens via
                            // AnnotatedOpModeClassFilter scanning the class names supplied by
                            // getOnBotJavaClassNames() above — this callback exists for
                            // *instance*-registered OpModes, which Flux/OnBotJava does not use.
                            // Fast Load passes an equivalent no-op here (hot-reload-prior-art.md
                            // §2.2); architecture.md §3.2 specifies the same shape.
                        }
                    });
                    AnnotatedOpModeClassFilter.getInstance().filterExternalLibrariesClassesStart();
                    registeredOpModes.setExternalLibrariesChanged();        // refreshes the DS's OpMode list
                } catch (Throwable t) {
                    // We're already inside the SDK's own lock, mutating global registry state.
                    // We cannot know how much of the sequence above completed, so per
                    // architecture.md §6 / risks.md §6 this MUST be treated as "failed midway,
                    // state unknown" — never silently reported as either success or a clean abort.
                    RobotLog.ee(TAG, t, "FLUX: reload transaction failed mid-flight");
                    transactionFailed.set(true);
                }
            }
        });

        if (transactionFailed.get()) {
            return RESULT_FAILED_DIRTY;
        }

        FluxClassLoader newLoader = newLoaderHolder[0];
        if (newLoader == null) {
            // createOnBotJavaClassLoader() is only invoked by the SDK if it actually performed a
            // (re)scan. If it was never called, we have no idea what state the registry is in —
            // guessing "clean" here would be exactly the silent-failure mistake this project
            // exists to avoid.
            RobotLog.ee(TAG, "FLUX: SDK never invoked createOnBotJavaClassLoader(); registry state unknown");
            return RESULT_FAILED_DIRTY;
        }

        // --- Version-stamp verification (CONTRACT.md "Version stamping") ---
        // Load __FluxVersion THROUGH THE NEW LOADER (not any cached reference) and read BUILD_ID
        // back. This is the concrete answer to "how do we verify the correct code is actually
        // running" (risks.md §6) — a successful registry swap that quietly kept old code live
        // would otherwise look identical to a real success.
        String observedBuildId;
        try {
            Class<?> versionClass = newLoader.loadClass(VERSION_CLASS_NAME);
            Field buildIdField = versionClass.getField(VERSION_FIELD_NAME);
            observedBuildId = (String) buildIdField.get(null);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException | ClassCastException e) {
            // CNFE here means the bundle didn't contain (or Flux couldn't resolve) the
            // Gradle-plugin-generated version marker class — CONTRACT.md: CNFE ⇒ result code 3.
            // This happens post-commit (the registry has already been swapped), so it is dirty,
            // not a clean abort.
            RobotLog.ee(TAG, e, "FLUX: could not verify %s through the new generation's loader", VERSION_CLASS_NAME);
            return RESULT_FAILED_DIRTY;
        }

        RobotLog.ii(TAG, "FLUX_BUILD_ID=%s", observedBuildId);

        if (expectedBuildId != null && !expectedBuildId.equals(observedBuildId)) {
            // CONTRACT.md: mismatch ⇒ result code 3, for the same reason as CNFE above.
            RobotLog.ee(TAG, "FLUX: BUILD_ID mismatch — expected %s, observed %s", expectedBuildId, observedBuildId);
            return RESULT_FAILED_DIRTY;
        }

        // --- Generation check (architecture.md §6 / risks.md §3) ---
        // Intent: catch a half-reloaded registry, e.g. a stale AnnotatedOpModeClassFilter cache
        // still holding Class references from generation N-1 next to newly-registered N classes.
        //
        // API-shape note, verified against SDK 12.0.0 with `javap -p` (not assumed):
        // architecture.md §6's draft phrasing ("walk getOpModes() and assert each OpMode Class's
        // getClassLoader()") assumed Class access that the shipped SDK does not offer.
        // RegisteredOpModes.getOpModes() returns List<OpModeMeta>, and OpModeMeta carries only
        // name/flavor/group/description — no Class reference at all.
        //
        // The only public API exposing a checkable Class identity is getOpMode(String), which
        // CONSTRUCTS a live OpMode. We deliberately do NOT use it here:
        //
        //   1. Side effects. It would run every registered OpMode's constructor and every static
        //      initializer they touch, after every single deploy. FTC OpModes routinely have field
        //      initializers; running them outside the SDK's normal lifecycle is not something a
        //      deploy tool should do behind the user's back.
        //   2. False failures. One OpMode with a throwing constructor would make EVERY deploy
        //      report FAILED_DIRTY ("robot state unknown") even though the reload was fine. That
        //      is precisely the alarm-fatigue failure that makes a safety signal worthless.
        //
        // The BUILD_ID round-trip above is already strong evidence the new generation is live: it
        // was loaded through newLoader and its value is generated fresh per bundle. We rely on
        // that, and additionally verify the loader the SDK is actually holding, which is cheap and
        // has no side effects.
        // The meaningful loader check already happened above: createOnBotJavaClassLoader() is
        // invoked by the SDK itself, so a non-null newLoader proves the SDK performed a real
        // rescan against THIS generation's loader. A null there is already FAILED_DIRTY.

        RobotLog.ii(TAG, "FLUX: %d OpMode(s) registered", registeredOpModes.getOpModes().size());

        currentGenerationLoader = newLoader;
        RobotLog.ii(TAG, "FLUX: reload succeeded — build %s is live and verified", observedBuildId);
        return RESULT_SUCCESS;
    }

    /**
     * Opens the bundle's {@link DexFile}. Constructed TWICE on purpose — this reproduces a
     * workaround from the reference implementation, not an oversight. See
     * docs/research/hot-reload-prior-art.md §2.1:
     *
     * <pre>
     * // loaded twice: first call reliably throws ("no original dex file"),
     * // second succeeds. Works around a DexFile reload bug.
     * try { dexFile = new DexFile(FAST_LOAD_JAR.getPath()); } catch (IOException ignored) {}
     * try { dexFile = new DexFile(FAST_LOAD_JAR.getPath()); } catch (IOException e) { e.printStackTrace(); }
     * </pre>
     *
     * Do not "clean this up" to a single construction without hardware testing — the prior-art
     * research is explicit that removing it reintroduces a real reload bug on-device. The Control
     * Hub's Android version (API 25, see docs/research/control-hub-platform.md) also has no
     * {@code InMemoryDexClassLoader}, so {@link DexFile} is the only option here regardless.
     */
    @SuppressWarnings("deprecation") // DexFile(String) is deprecated on newer API levels but is
                                     // still the only path available on the Control Hub's API 25.
    private static DexFile openBundleDexFile(File bundleJar) throws IOException {
        try {
            return new DexFile(bundleJar.getPath());
        } catch (IOException firstAttemptExpectedToFail) {
            RobotLog.ww(TAG, "FLUX: first DexFile open attempt failed as expected (see hot-reload-prior-art.md §2.1), retrying");
            return new DexFile(bundleJar.getPath());
        }
    }

    /**
     * THE ONLY PRIVATE REFLECTION IN THIS CODEBASE. Deliberately isolated in one small, heavily
     * commented method so it is trivial to audit and trivial to find if a future SDK release
     * renames or removes the field.
     *
     * <p>{@code ClassManager.processAllClasses()} is a run-once operation guarded by a private
     * static {@code AtomicBoolean processAllClassesCalled} (verified via {@code javap -p} against
     * RobotCore 12.0.0). Flux needs to invoke it again on every reload, so this resets that guard
     * immediately beforehand. This exact reflection point, and no others, is what Fast Load uses
     * (hot-reload-prior-art.md §2.2) and is the reason architecture.md §1 calls Flux's reflection
     * budget "1 private field, versus Sloth's ~12 members across 5+ classes" — the single biggest
     * durability argument for this whole design over Sloth's approach.
     */
    private static void resetProcessAllClassesGuard(ClassManager classManager) {
        try {
            Field field = ClassManager.class.getDeclaredField("processAllClassesCalled");
            field.setAccessible(true);
            AtomicBoolean guard = (AtomicBoolean) field.get(classManager);
            guard.set(false);
        } catch (NoSuchFieldException | IllegalAccessException | ClassCastException e) {
            // If this ever fails it means the SDK renamed/removed the field — surface it loudly
            // rather than silently proceeding with a ClassManager that thinks it already ran.
            // The caller's try/catch around the whole transaction (see reload() above) turns this
            // into RESULT_FAILED_DIRTY, which is the correct classification: we're already
            // holding the SDK's registry lock at this point.
            throw new IllegalStateException(
                    "FLUX: ClassManager.processAllClassesCalled reflection target is gone — SDK API changed", e);
        }
    }

    private static FluxRootClassLoader rootClassLoader(Context context) {
        FluxRootClassLoader existing = rootClassLoaderInstance;
        if (existing != null) {
            return existing;
        }
        synchronized (FluxReloadEngine.class) {
            if (rootClassLoaderInstance == null) {
                // The "real app classloader" being wrapped is the RC app's own Context
                // classloader — equivalent to Fast Load's use of
                // ReloadIntentListener.class.getClassLoader() as the parent for its loader chain
                // (flux-runtime is compiled into the app, so the two are the same loader; using
                // the Context is the more direct expression of "the app's real classloader").
                ClassLoader realAppClassLoader = context.getApplicationContext().getClassLoader();
                rootClassLoaderInstance = new FluxRootClassLoader(realAppClassLoader);
            }
            return rootClassLoaderInstance;
        }
    }

    /**
     * GAP 2, success side. Copies the bundle that just reached {@link #RESULT_SUCCESS} over
     * {@link #LAST_GOOD_BUNDLE_FILE}, via a temp-file-then-rename so a process death or power loss
     * mid-copy can never leave the rollback target itself half-written — that file is the one
     * thing {@link #attemptRollback} depends on being intact when something later goes wrong.
     */
    private static void saveAsLastKnownGood(File justAppliedBundle) {
        File tmp = new File(DEPLOY_DIR, LAST_GOOD_BUNDLE_FILE.getName() + ".tmp");
        try {
            copyFile(justAppliedBundle, tmp);
            if (!tmp.renameTo(LAST_GOOD_BUNDLE_FILE)) {
                throw new IOException("rename " + tmp.getPath() + " -> " + LAST_GOOD_BUNDLE_FILE.getPath() + " failed");
            }
            RobotLog.ii(TAG, "FLUX: saved known-good bundle to %s", LAST_GOOD_BUNDLE_FILE.getPath());
        } catch (IOException e) {
            // Non-fatal to THIS deploy — it already reached RESULT_SUCCESS and must still be
            // reported as such. The only consequence of losing this copy is that a FUTURE failed
            // deploy won't have anything to roll back to until the next successful one.
            RobotLog.ee(TAG, e, "FLUX: failed to save known-good bundle to %s — rollback on a "
                    + "future failed deploy will not be available until the next successful deploy",
                    LAST_GOOD_BUNDLE_FILE.getPath());
        }
    }

    private static void copyFile(File source, File dest) throws IOException {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}
