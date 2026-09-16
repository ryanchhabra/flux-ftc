package dev.ryanchhab.flux.runtime;

/**
 * Wraps the RC app's real classloader and punches one deliberate hole in it: it categorically
 * refuses to resolve {@code org.firstinspires.ftc.teamcode.*}, no matter whether the real app
 * classloader underneath it actually has those classes (e.g. from the last full APK install).
 *
 * This is the whole trick, adopted directly from Sloth (see
 * docs/research/sloth-teardown.md §1, which quotes the reference implementation this class
 * mirrors):
 *
 * <pre>
 * class RootClassLoader(delegate: ClassLoader) : ClassLoader(delegate) {
 *     init { inclusion.exclude("org.firstinspires.ftc.teamcode") }
 *     override fun loadClass(name: String, resolve: Boolean): Class&lt;*&gt;? =
 *         if (!inclusion.determineInclusion(name))
 *             throw ClassNotFoundException("attempted to excluded class $name")
 *         else super.loadClass(name, false)
 * }
 * </pre>
 *
 * Why this matters (docs/design/architecture.md §3.1): {@link FluxClassLoader} is a plain
 * parent-first {@code PathClassLoader} over the freshly-pushed bundle jar, with *this* loader as
 * its parent. Ordinary parent-first delegation means the parent always wins when it has the
 * class — which would mean the *original, installed* TeamCode classes win forever, and the
 * pushed dex would only ever supply classes that never existed in the APK. That is exactly the
 * capability gap flagged (inferred) for FTC Fast Load in
 * docs/research/hot-reload-prior-art.md §2.5 ("probably cannot add new classes"), because Fast
 * Load's loader does child-first override instead of fixing the hole at the parent.
 *
 * By making the parent unconditionally refuse the TeamCode package, {@link FluxClassLoader}'s
 * ordinary, unmodified parent-first algorithm falls through to its own dex for every TeamCode
 * class on every generation — new classes, renamed classes, and edited classes all resolve the
 * same way, with no special-casing needed in the child. The pushed dex becomes the sole source of
 * truth for {@code org.firstinspires.ftc.teamcode.*}, structurally, not by convention.
 *
 * Everything that is NOT under {@code org.firstinspires.ftc.teamcode} is delegated to the real
 * app classloader completely normally — this loader defines no classes of its own and never calls
 * {@code findClass}, so for every other package the behavior is indistinguishable from talking to
 * the real app classloader directly.
 */
final class FluxRootClassLoader extends ClassLoader {

    /** Package prefix, with trailing dot, so we don't accidentally match a sibling package like
     *  {@code org.firstinspires.ftc.teamcodesomethingelse}. */
    private static final String TEAMCODE_PREFIX = "org.firstinspires.ftc.teamcode.";

    /** The bare package name itself (no trailing dot) — matched separately so a lookup of the
     *  package/marker class name (rare, but package-info-style lookups exist) is also refused. */
    private static final String TEAMCODE_PACKAGE = "org.firstinspires.ftc.teamcode";

    /**
     * @param realAppClassLoader the RC app's actual classloader (i.e. the one that loaded
     *                           flux-runtime itself) — the thing being wrapped, not replaced.
     */
    private final ClassLoader realAppClassLoader;

    FluxRootClassLoader(ClassLoader realAppClassLoader) {
        super(realAppClassLoader);
        this.realAppClassLoader = realAppClassLoader;
    }

    /**
     * The RC app's actual classloader, reachable <i>without</i> going through this loader's
     * TeamCode refusal.
     *
     * <p>Needed for exactly one purpose: hardware-configuration-annotated driver classes. Those
     * conventionally live inside {@code org.firstinspires.ftc.teamcode}, which {@link #loadClass}
     * refuses unconditionally — but the {@code HardwareDevice} instances sitting in the live
     * {@code HardwareMap} were constructed at hardware-config parse time by <b>this</b> loader,
     * before Flux ever ran. To keep {@code hardwareMap.get(MyDriver.class, ...)} working, those
     * driver classes must keep resolving to the app loader's {@code Class} object, not to any
     * Flux generation's redefinition. See {@link FluxClassLoader} and docs/research/risks.md §1.
     *
     * <p>This is a deliberate, narrow bypass. Do not use it for anything else — routing ordinary
     * TeamCode through here would resurrect stale code and defeat the whole design.
     */
    ClassLoader realAppClassLoader() {
        return realAppClassLoader;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (isTeamCodeClass(name)) {
            // Deliberate hole. See class javadoc: this is what forces the pushed bundle jar to be
            // the sole source of truth for TeamCode, on every generation, including brand-new
            // classes. Do NOT "fix" this to delegate normally — that would silently reintroduce
            // the very limitation this design exists to avoid.
            throw new ClassNotFoundException(
                    "FluxRootClassLoader refuses to resolve " + name
                            + " — TeamCode may only be resolved from the pushed Flux bundle");
        }
        // Everything else: completely normal parent-first delegation. We never call findClass
        // ourselves, so this always terminates at the real app classloader (or its ancestors) or
        // throws ClassNotFoundException exactly as it would without Flux involved at all.
        return super.loadClass(name, resolve);
    }

    private static boolean isTeamCodeClass(String name) {
        return name.equals(TEAMCODE_PACKAGE) || name.startsWith(TEAMCODE_PREFIX);
    }
}
