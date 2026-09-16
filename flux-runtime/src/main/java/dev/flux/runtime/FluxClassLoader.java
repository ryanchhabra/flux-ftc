package dev.flux.runtime;

import com.qualcomm.robotcore.hardware.configuration.annotations.AnalogSensorType;
import com.qualcomm.robotcore.hardware.configuration.annotations.DeviceProperties;
import com.qualcomm.robotcore.hardware.configuration.annotations.DigitalIoDeviceType;
import com.qualcomm.robotcore.hardware.configuration.annotations.I2cDeviceType;
import com.qualcomm.robotcore.hardware.configuration.annotations.MotorType;
import com.qualcomm.robotcore.hardware.configuration.annotations.ServoType;
import com.qualcomm.robotcore.util.RobotLog;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.PathClassLoader;

/**
 * One of these is created fresh on every reload (see {@link FluxReloadEngine}'s fake
 * {@code OnBotJavaHelper.createOnBotJavaClassLoader()}), over the just-pushed bundle jar, with a
 * shared {@link FluxRootClassLoader} as parent.
 *
 * <p><b>Package-prefix exclusions</b> (CONTRACT.md "Classloader exclusion set"): {@code kotlin.},
 * {@code kotlinx.}, {@code java.}, {@code javax.}, {@code android.}, {@code androidx.},
 * {@code dalvik.}, {@code com.qualcomm.}, and {@code org.firstinspires.ftc.} (except
 * {@code org.firstinspires.ftc.teamcode.}) must always delegate to the parent and must never be
 * (re)defined from the bundle jar, even if the bundle happens to contain a copy of them (e.g. a
 * naively-assembled dex that pulled in transitive stdlib classes). Two independent reasons this
 * has to be enforced explicitly rather than left to luck:
 * <ul>
 *   <li>docs/research/risks.md §2: a duplicate-defined {@code kotlin.jvm.internal.Intrinsics} or
 *       {@code kotlin.Unit} creates two distinct {@code Class} objects with the same name across
 *       generations; objects built against one and checked against the other throw
 *       {@code ClassCastException} in code that looks nothing like a hot-reload bug. Fast Load
 *       has no such exclusion at all — CONTRACT.md and architecture.md call this out as the one
 *       bug we must not repeat.</li>
 *   <li>Ordinary parent-first delegation already tends to prefer the parent when the class
 *       exists there, but we make the exclusion explicit and unconditional rather than relying
 *       on incidental delegation order, per CONTRACT.</li>
 * </ul>
 *
 * <p><b>Hardware-configuration-annotated classes</b> — {@code @I2cDeviceType}, {@code @MotorType},
 * {@code @ServoType}, {@code @DigitalIoDeviceType}, {@code @AnalogSensorType},
 * {@code @DeviceProperties} — are the subtle case. Custom driver classes conventionally live
 * <i>inside</i> {@code org.firstinspires.ftc.teamcode}, which {@link FluxRootClassLoader} refuses
 * unconditionally, so "delegate to the parent" cannot serve them.
 *
 * <p>The correct target is the <b>RC app's real classloader</b>, reached through
 * {@link FluxRootClassLoader#realAppClassLoader()}. Reason (docs/research/risks.md §1):
 * {@code HardwareMap.get()} type-checks by {@code Class} identity against {@code HardwareDevice}
 * instances built <i>once</i>, at hardware-config parse time — at app startup, by the app
 * classloader, before Flux ever ran. Those instances are never rebuilt on a code-only reload. So the
 * only identity that keeps {@code hardwareMap.get(MyDriver.class, "name")} working is the app
 * loader's own; once resolved it is pinned for all later generations.
 *
 * <p>Note this is why pinning to the first <i>Flux</i> generation would be wrong — it would be
 * stable across reloads but still mismatch the live instances, which predate every Flux generation.
 * See docs/design/CONTRACT.md Amendment 1.
 *
 * <p>A hardware-config class that was never in the installed APK has no app-loader identity and no
 * HardwareMap instance built from it; it cannot work until a full install. Tier-3 build-time
 * detection should refuse such a deploy, and this loader logs loudly if one slips through.
 */
final class FluxClassLoader extends PathClassLoader {

    private static final String TAG = "FLUX";

    // Delegation decision lives in FluxDelegation -- pure logic, no Android deps, so it can be
    // tested on a plain JVM without a Control Hub. See test/jvm/FluxDelegationTest.java.

    /**
     * The six device-configuration annotation types from CONTRACT.md. All live in
     * {@code com.qualcomm.robotcore.hardware.configuration.annotations} in RobotCore 12.0.0
     * (verified via {@code javap -p} against the shipped AAR).
     */
    @SuppressWarnings("unchecked")
    private static final Class<? extends Annotation>[] HARDWARE_CONFIG_ANNOTATIONS = new Class[]{
            I2cDeviceType.class,
            MotorType.class,
            ServoType.class,
            DigitalIoDeviceType.class,
            AnalogSensorType.class,
            DeviceProperties.class,
    };

    /**
     * Cross-generation identity cache for hardware-configuration-annotated classes. This is a
     * {@code static} field on {@code FluxClassLoader} itself — the class object
     * {@code FluxClassLoader.class} is loaded exactly once, by the real app classloader, and
     * never reloaded (only *instances* of this loader are recreated per generation). That makes
     * a static field here the correct, simplest place to hold state that must outlive any single
     * generation. See the class javadoc for why this cache exists at all.
     */
    private static final Map<String, Class<?>> PINNED_HARDWARE_CLASSES = new ConcurrentHashMap<>();

    /**
     * @param bundleJarPath absolute path to the pushed bundle jar (CONTRACT.md: the on-robot
     *                      full push target).
     * @param parent        the shared {@link FluxRootClassLoader} for this process.
     */
    FluxClassLoader(String bundleJarPath, ClassLoader parent) {
        super(bundleJarPath, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Already resolved by this exact generation? Reuse it — standard classloader hygiene,
        // avoids re-triggering static initializers.
        Class<?> alreadyLoaded = findLoadedClass(name);
        if (alreadyLoaded != null) {
            if (resolve) {
                resolveClass(alreadyLoaded);
            }
            return alreadyLoaded;
        }

        // A previously-pinned hardware-config class: MUST keep returning the exact Class object
        // pinned on first sight, never a fresh redefinition from this (or any later) generation's
        // dex. See class javadoc.
        Class<?> pinned = PINNED_HARDWARE_CLASSES.get(name);
        if (pinned != null) {
            if (resolve) {
                resolveClass(pinned);
            }
            return pinned;
        }

        // Package-prefix exclusions: always the parent, never redefined here. For anything
        // outside org.firstinspires.ftc.teamcode this is also just what plain parent-first
        // delegation would do anyway (the parent already has these classes); we make it explicit
        // per CONTRACT rather than leaning on incidental delegation order, and it protects us if
        // a badly-assembled bundle jar ever contains a stray copy of one of these classes.
        if (FluxDelegation.isPackagePrefixExcluded(name)) {
            Class<?> fromParent = getParent().loadClass(name);
            if (resolve) {
                resolveClass(fromParent);
            }
            return fromParent;
        }

        // --- TeamCode ---------------------------------------------------------------------
        // The parent (FluxRootClassLoader) refuses this package unconditionally, so ordinary
        // delegation cannot serve it. Before defining from the bundle we must check one thing:
        // is this a hardware-configuration driver class that the running HardwareMap already
        // depends on?
        //
        // WHY THIS ORDER MATTERS (docs/research/risks.md §1):
        // HardwareDevice instances in the live HardwareMap were constructed at hardware-config
        // parse time — at app startup, by the APP classloader, before Flux ever ran. They are NOT
        // rebuilt on a code-only reload. So the Class identity that hardwareMap.get() checks
        // against is the APP loader's, not Flux generation 1's. Pinning to the first *Flux*
        // generation would therefore still mismatch the live instances. The only identity that
        // keeps hardwareMap.get(MyDriver.class, "x") working is the app loader's own.
        if (FluxDelegation.isTeamCode(name)) {
            Class<?> fromApp = tryAppLoader(name);
            if (fromApp != null && carriesHardwareConfigAnnotation(fromApp)) {
                pin(name, fromApp);
                if (resolve) {
                    resolveClass(fromApp);
                }
                return fromApp;
            }

            // Ordinary TeamCode: define from the pushed bundle. This is the one and only place a
            // class is actually defined from the jar, and it is what lets brand-new classes work.
            Class<?> fromDex = findClass(name);

            if (carriesHardwareConfigAnnotation(fromDex) && fromApp == null) {
                // A hardware driver class that did NOT exist in the installed APK. There is no
                // app-loader identity to pin to, and no HardwareMap instance built from it, so it
                // cannot function until a full install rebuilds the hardware configuration.
                // Tier-3 build-time detection should have refused this deploy before it reached
                // the robot; log loudly in case it slipped through.
                RobotLog.ww(TAG, "FLUX: %s is a new hardware-config class that was not in the "
                        + "installed APK — it will not bind to the running HardwareMap. "
                        + "Run a full install (./gradlew installDebug).", name);
            }

            pinIfHardwareConfig(name, fromDex);
            if (resolve) {
                resolveClass(fromDex);
            }
            return fromDex;
        }

        // --- Everything else: ordinary parent-first ---------------------------------------
        Class<?> fromParent = tryParent(name);
        if (fromParent != null) {
            if (resolve) {
                resolveClass(fromParent);
            }
            return fromParent;
        }
        Class<?> fromDex = findClass(name);
        if (resolve) {
            resolveClass(fromDex);
        }
        return fromDex;
    }


    /**
     * Resolves via the RC app's real classloader, bypassing {@link FluxRootClassLoader}'s TeamCode
     * refusal. Returns {@code null} if the class was not in the installed APK.
     */
    private Class<?> tryAppLoader(String name) {
        ClassLoader parent = getParent();
        if (!(parent instanceof FluxRootClassLoader)) {
            return null;
        }
        try {
            return ((FluxRootClassLoader) parent).realAppClassLoader().loadClass(name);
        } catch (ClassNotFoundException notInApk) {
            return null;
        }
    }

    private static void pin(String name, Class<?> resolved) {
        if (PINNED_HARDWARE_CLASSES.putIfAbsent(name, resolved) == null) {
            RobotLog.ii(TAG, "FLUX: pinned hardware-config class %s to the app classloader's "
                    + "identity (HardwareMap compatibility)", name);
        }
    }

    private Class<?> tryParent(String name) {
        try {
            return getParent().loadClass(name);
        } catch (ClassNotFoundException expectedForTeamCode) {
            return null;
        }
    }

    private static void pinIfHardwareConfig(String name, Class<?> resolved) {
        if (!carriesHardwareConfigAnnotation(resolved)) {
            return;
        }
        Class<?> raced = PINNED_HARDWARE_CLASSES.putIfAbsent(name, resolved);
        if (raced == null) {
            RobotLog.ii(TAG, "FLUX: pinned hardware-config class %s to generation-independent identity", name);
        }
    }

    private static boolean carriesHardwareConfigAnnotation(Class<?> clazz) {
        for (Class<? extends Annotation> annotation : HARDWARE_CONFIG_ANNOTATIONS) {
            if (clazz.isAnnotationPresent(annotation)) {
                return true;
            }
        }
        return false;
    }

}
