package dev.ryanchhab.flux.runtime;

import android.content.Context;

import com.qualcomm.robotcore.util.RobotLog;

import org.firstinspires.ftc.robotcore.internal.system.AppUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Tier L — "live tuning" (CONTRACT.md Amendment 3). Applies a batch of static field writes to the
 * already-running OpMode's classes, with no compile, no dex, and no classloader swap. See
 * docs/design/live-tuning.md §2 for why this is cheaper than a reload, not a stripped-down version
 * of one.
 *
 * <p>The payload is a flat JSON array at CONTRACT.md's fixed path, deliberately trivial:
 * <pre>
 * [{"class":"org.firstinspires.ftc.teamcode.DriveConstants","field":"kP","type":"double","value":"0.015"}]
 * </pre>
 * We deliberately do NOT pull in a JSON library for this (see {@link #parse(String)}) — the format
 * is one flat array of string-keyed, string-valued objects, small enough that a hand-rolled scanner
 * is both simpler and has zero dependency footprint added to the RC app.
 *
 * <p><b>Eligibility (CONTRACT.md Amendment 3) — ALL of the following or the field is refused:</b>
 * <ul>
 *   <li>declared directly on a class annotated {@link FluxLive}</li>
 *   <li>{@code static}</li>
 *   <li><b>not</b> {@code final} — see {@link #REJECT_FINAL_MESSAGE} for why this one is a real
 *       trap rather than pure caution</li>
 *   <li>type is a primitive, {@code String}, or an enum</li>
 * </ul>
 *
 * <p><b>Result codes reuse {@link FluxReloadEngine}'s triad</b> (CONTRACT.md: "Result codes reuse
 * the reload set"): {@link FluxReloadEngine#RESULT_SUCCESS} (all applied),
 * {@link FluxReloadEngine#RESULT_FAILED_CLEAN} (nothing applied — missing/malformed file, or every
 * entry was ineligible; the robot is untouched, which is why this is "clean" even though it's a
 * failure), {@link FluxReloadEngine#RESULT_FAILED_DIRTY} (some entries applied and some did not —
 * unlike a reload, this is not a transaction, so "dirty" here means "mixed", not "unknown state";
 * the caller must be told exactly which fields did and didn't take, which is why every outcome is
 * logged per-field regardless of the final code).
 */
final class FluxLiveTuning {

    private static final String TAG = "FLUX";

    /** CONTRACT.md "Payload file". Same deploy dir as the bundle (CONTRACT.md "On-robot paths"). */
    private static final File LIVE_VALUES_FILE =
            new File(new File(AppUtil.FIRST_FOLDER, "flux"), "live_values.json");

    private static final String REJECT_FINAL_MESSAGE =
            "FLUX: refusing to live-set %s.%s — it is `final`. A final primitive/String is "
                    + "constant-folded into every call site AT COMPILE TIME (JLS 13.4.9), so writing "
                    + "the field's value at runtime changes nothing observable — every place that "
                    + "reads it already baked in the old literal. Remove `final` and rebuild once to "
                    + "make this field live-tunable.";

    private FluxLiveTuning() {
    }

    /**
     * Reads and applies CONTRACT.md's live-values payload.
     *
     * @param context used only to obtain the app classloader fallback (see
     *                {@link #resolveClassLoader(Context)}) — kept as a parameter rather than a
     *                static reference for the same reason {@link FluxReloadEngine#reload} does.
     * @return one of {@link FluxReloadEngine}'s three result codes.
     */
    static int apply(Context context) {
        String json;
        try {
            json = readFile(LIVE_VALUES_FILE);
        } catch (IOException e) {
            // Nothing read, nothing touched — clean, not dirty.
            RobotLog.ee(TAG, e, "FLUX: could not read live-values payload at %s", LIVE_VALUES_FILE.getPath());
            return FluxReloadEngine.RESULT_FAILED_CLEAN;
        }

        List<LiveValueEntry> entries;
        try {
            entries = parse(json);
        } catch (LiveTuningParseException e) {
            // A malformed payload must produce result 2, not a crash — CONTRACT.md is explicit
            // about this. Nothing has been applied yet, so this is clean.
            RobotLog.ee(TAG, e, "FLUX: malformed live-values payload at %s", LIVE_VALUES_FILE.getPath());
            return FluxReloadEngine.RESULT_FAILED_CLEAN;
        }

        if (entries.isEmpty()) {
            RobotLog.ww(TAG, "FLUX: live-values payload at %s contained no entries", LIVE_VALUES_FILE.getPath());
            return FluxReloadEngine.RESULT_FAILED_CLEAN;
        }

        ClassLoader loader = resolveClassLoader(context);

        int applied = 0;
        for (LiveValueEntry entry : entries) {
            if (applyOne(loader, entry)) {
                applied++;
            }
        }

        if (applied == 0) {
            return FluxReloadEngine.RESULT_FAILED_CLEAN;
        }
        if (applied == entries.size()) {
            return FluxReloadEngine.RESULT_SUCCESS;
        }
        // Some succeeded, some didn't — the robot is now running a mix of old and new constant
        // values. Unlike a reload this can't be "unknown state" (every entry's outcome was
        // individually observed and logged), but the caller must still be told it's not a clean
        // all-or-nothing result. CONTRACT.md's "3" is the agreed code for that.
        RobotLog.ww(TAG, "FLUX: live tuning partially applied — %d/%d field(s) set, see log above for which failed",
                applied, entries.size());
        return FluxReloadEngine.RESULT_FAILED_DIRTY;
    }

    /**
     * CONTRACT.md Amendment 3 / this class's javadoc, "Resolution rule (critical)": a live-tuned
     * field must be written on the class as resolved by the CURRENT Flux generation's classloader
     * — the one the running OpMode actually used — never a stale generation's {@code Class}
     * object, which would silently do nothing observable.
     *
     * <p>If no Flux reload has happened yet this session, {@link FluxReloadEngine} has never set
     * {@code currentGenerationLoader} (it starts {@code null} and is only assigned on
     * {@code RESULT_SUCCESS}). In that case there IS no Flux generation, and the running OpMode is
     * still whatever was compiled into the installed APK — i.e. it's being run by the app's own
     * classloader, not by any {@link FluxClassLoader}. Falling back to the app classloader here is
     * therefore not a degraded case, it's simply resolving through whichever loader is actually in
     * play, exactly the same rule applied to a different starting state.
     */
    private static ClassLoader resolveClassLoader(Context context) {
        ClassLoader generationLoader = FluxReloadEngine.getCurrentGenerationLoader();
        if (generationLoader != null) {
            return generationLoader;
        }
        RobotLog.ii(TAG, "FLUX: no Flux generation yet this session — resolving live-tune classes "
                + "through the app classloader");
        return context.getApplicationContext().getClassLoader();
    }

    /**
     * Applies a single entry. Never throws — every failure path is logged and reflected in the
     * boolean return so the caller can compute the applied/total ratio for the result code.
     */
    private static boolean applyOne(ClassLoader loader, LiveValueEntry entry) {
        Class<?> clazz;
        try {
            clazz = loader.loadClass(entry.className);
        } catch (ClassNotFoundException e) {
            RobotLog.ee(TAG, "FLUX: live-set %s.%s skipped — class not found through the current "
                    + "generation's classloader", entry.className, entry.fieldName);
            return false;
        }

        if (!clazz.isAnnotationPresent(FluxLive.class)) {
            RobotLog.ee(TAG, "FLUX: live-set %s.%s skipped — %s is not annotated @FluxLive",
                    entry.className, entry.fieldName, entry.className);
            return false;
        }

        Field field;
        try {
            // Declared directly, not inherited — CONTRACT.md says "declared in a class annotated
            // @FluxLive", which we read literally: no superclass walk.
            field = clazz.getDeclaredField(entry.fieldName);
        } catch (NoSuchFieldException e) {
            RobotLog.ee(TAG, "FLUX: live-set %s.%s skipped — no such declared field",
                    entry.className, entry.fieldName);
            return false;
        }

        int modifiers = field.getModifiers();
        if (!Modifier.isStatic(modifiers)) {
            RobotLog.ee(TAG, "FLUX: live-set %s.%s skipped — field is not static",
                    entry.className, entry.fieldName);
            return false;
        }
        if (Modifier.isFinal(modifiers)) {
            RobotLog.ee(TAG, REJECT_FINAL_MESSAGE, entry.className, entry.fieldName);
            return false;
        }

        Object coerced;
        try {
            coerced = coerce(field.getType(), entry.value);
        } catch (RuntimeException e) {
            RobotLog.ee(TAG, e, "FLUX: live-set %s.%s skipped — could not coerce value %s to %s",
                    entry.className, entry.fieldName, entry.value, field.getType().getName());
            return false;
        }

        try {
            field.setAccessible(true);
            field.set(null, coerced);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            RobotLog.ee(TAG, e, "FLUX: live-set %s.%s skipped — could not write field",
                    entry.className, entry.fieldName);
            return false;
        }

        RobotLog.ii(TAG, "FLUX: live set %s.%s = %s", entry.className, entry.fieldName, entry.value);
        return true;
    }

    /**
     * Coerces a string value to {@code type}, which must be a primitive, {@code String}, or enum
     * (CONTRACT.md Amendment 3's supported-type list). Anything else — arrays, arbitrary objects,
     * boxed wrapper fields (real live-tuned fields declare the primitive, per
     * docs/design/live-tuning.md §4.1's example) — is rejected with a clear message rather than
     * guessed at.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object coerce(Class<?> type, String value) {
        if (type == boolean.class) {
            return Boolean.valueOf(value);
        } else if (type == byte.class) {
            return Byte.valueOf(value);
        } else if (type == short.class) {
            return Short.valueOf(value);
        } else if (type == int.class) {
            return Integer.valueOf(value);
        } else if (type == long.class) {
            return Long.valueOf(value);
        } else if (type == float.class) {
            return Float.valueOf(value);
        } else if (type == double.class) {
            return Double.valueOf(value);
        } else if (type == char.class) {
            if (value.length() != 1) {
                throw new IllegalArgumentException("char value must be exactly one character, got \"" + value + "\"");
            }
            return Character.valueOf(value.charAt(0));
        } else if (type == String.class) {
            return value;
        } else if (type.isEnum()) {
            return Enum.valueOf((Class<Enum>) type, value);
        }
        throw new IllegalArgumentException(
                "unsupported live-tunable field type " + type.getName()
                        + " — only primitives, String, and enums are supported (CONTRACT.md Amendment 3)");
    }

    // --- Hand-rolled JSON parsing -------------------------------------------------------------
    //
    // Deliberately not a general JSON parser: the payload shape is fixed by CONTRACT.md to a flat
    // array of flat objects, all string keys, all string values. This scanner supports exactly
    // that shape (plus standard backslash-quote, backslash-backslash, and the usual \n \t \r and
    // backslash-u-XXXX string escapes, since editors may emit them) and rejects anything else as
    // malformed rather than trying to be permissive.

    private static final class LiveValueEntry {
        final String className;
        final String fieldName;
        final String typeName;
        final String value;

        LiveValueEntry(String className, String fieldName, String typeName, String value) {
            this.className = className;
            this.fieldName = fieldName;
            this.typeName = typeName;
            this.value = value;
        }
    }

    private static final class LiveTuningParseException extends Exception {
        LiveTuningParseException(String message) {
            super(message);
        }
    }

    /**
     * Number of times to re-read an empty payload before giving up, and the pause between tries.
     *
     * <p>Why this retry exists — it is not defensive padding, it fixes an observed, reproducible
     * failure. The desktop side does {@code adb push} immediately followed by the
     * {@code dev.ryanchhab.flux.LIVE_TUNE} broadcast. Those are sequential on the host, but the pushed bytes
     * are not necessarily visible to the app that fast: {@code /sdcard} is FUSE-backed, and adb
     * writes through a different path than the app reads from, so the app can briefly observe the
     * file as present-but-zero-length.
     *
     * <p>Measured on an API 25 emulator: every live-tune deploy failed with "unexpected end of
     * input at offset 0" while {@code adb shell cat} of the same path showed correct content, and
     * a manual broadcast against the settled file succeeded immediately. This is the same class of
     * platform quirk as the double-{@code DexFile}-open workaround in {@link FluxReloadEngine}.
     *
     * <p>Only an <em>empty</em> read is retried. A non-empty but malformed payload is a real error
     * and fails immediately — retrying that would just delay an honest failure.
     */
    private static final int EMPTY_READ_RETRIES = 10;
    private static final long EMPTY_READ_RETRY_MS = 20L;

    private static String readFile(File file) throws IOException {
        for (int attempt = 0; ; attempt++) {
            String content = readFileOnce(file);
            if (!content.trim().isEmpty() || attempt >= EMPTY_READ_RETRIES) {
                if (content.trim().isEmpty()) {
                    RobotLog.ww(TAG, "FLUX: live-values payload still empty after %d retries",
                            EMPTY_READ_RETRIES);
                }
                return content;
            }
            try {
                Thread.sleep(EMPTY_READ_RETRY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return content;
            }
        }
    }

    private static String readFileOnce(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            return sb.toString();
        } finally {
            in.close();
        }
    }

    private static List<LiveValueEntry> parse(String json) throws LiveTuningParseException {
        Scanner s = new Scanner(json);
        s.skipWhitespace();
        s.expect('[');
        List<LiveValueEntry> entries = new ArrayList<LiveValueEntry>();
        s.skipWhitespace();
        if (s.peek() == ']') {
            s.next();
            return entries;
        }
        while (true) {
            entries.add(parseEntry(s));
            s.skipWhitespace();
            char c = s.next();
            if (c == ']') {
                break;
            }
            if (c != ',') {
                throw new LiveTuningParseException("expected ',' or ']' at offset " + s.pos + ", got '" + c + "'");
            }
            s.skipWhitespace();
        }
        return entries;
    }

    private static LiveValueEntry parseEntry(Scanner s) throws LiveTuningParseException {
        s.skipWhitespace();
        s.expect('{');
        String className = null;
        String fieldName = null;
        String typeName = null;
        String value = null;

        s.skipWhitespace();
        if (s.peek() == '}') {
            s.next();
            throw new LiveTuningParseException("empty entry object at offset " + s.pos);
        }
        while (true) {
            s.skipWhitespace();
            String key = s.parseJsonString();
            s.skipWhitespace();
            s.expect(':');
            s.skipWhitespace();
            String val = s.parseJsonString();

            if ("class".equals(key)) {
                className = val;
            } else if ("field".equals(key)) {
                fieldName = val;
            } else if ("type".equals(key)) {
                typeName = val;
            } else if ("value".equals(key)) {
                value = val;
            } else {
                throw new LiveTuningParseException("unknown key \"" + key + "\" at offset " + s.pos);
            }

            s.skipWhitespace();
            char c = s.next();
            if (c == '}') {
                break;
            }
            if (c != ',') {
                throw new LiveTuningParseException("expected ',' or '}' at offset " + s.pos + ", got '" + c + "'");
            }
        }

        if (className == null || fieldName == null || typeName == null || value == null) {
            throw new LiveTuningParseException(
                    "entry missing one of class/field/type/value near offset " + s.pos);
        }
        return new LiveValueEntry(className, fieldName, typeName, value);
    }

    /** Minimal hand-rolled cursor over the payload string. Package-private-visible only via
     *  nesting; not reused outside this class. */
    private static final class Scanner {
        private final String src;
        private int pos;

        Scanner(String src) {
            this.src = src;
        }

        char peek() throws LiveTuningParseException {
            if (pos >= src.length()) {
                throw new LiveTuningParseException("unexpected end of input at offset " + pos);
            }
            return src.charAt(pos);
        }

        char next() throws LiveTuningParseException {
            char c = peek();
            pos++;
            return c;
        }

        void expect(char c) throws LiveTuningParseException {
            char actual = next();
            if (actual != c) {
                throw new LiveTuningParseException("expected '" + c + "' at offset " + (pos - 1) + ", got '" + actual + "'");
            }
        }

        void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        String parseJsonString() throws LiveTuningParseException {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    char esc = next();
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (pos + 4 > src.length()) {
                                throw new LiveTuningParseException("truncated \\u escape at offset " + pos);
                            }
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw new LiveTuningParseException("invalid \\u escape \"" + hex + "\" at offset " + pos);
                            }
                            break;
                        default:
                            throw new LiveTuningParseException("invalid escape '\\" + esc + "' at offset " + (pos - 1));
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }
}
