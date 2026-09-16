package dev.flux.runtime;

import android.content.Context;

import com.qualcomm.robotcore.util.RobotLog;

import org.firstinspires.ftc.robotcore.internal.system.AppUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Live-value read-back (CONTRACT.md Amendment 4). The mirror image of {@link FluxLiveTuning}: instead
 * of writing static fields, this reads their CURRENT values and reports them back to the desktop, so
 * the editor knows what's actually running on the robot after a restart or a tune from another
 * source (docs/design/live-tuning.md §4.5).
 *
 * <p><b>Why the desktop sends a class list instead of this scanning the whole dex:</b> the write path
 * ({@link FluxLiveTuning}) is always told exactly which classes to touch because the desktop already
 * knows — it just parsed the source tree. Read-back has the same information available on the
 * desktop side (the same {@code @FluxLive}-class scan {@code LiveTuneDetector} already does for
 * `fluxTune`), so the simplest correct design reuses that: {@code fluxRead} pushes a small JSON
 * request naming the classes, and this class resolves exactly those names. The alternative — walking
 * every loaded/dexed class on-device looking for the {@code @FluxLive} annotation — needs either a
 * full dex scan (slow, and there is no single API to enumerate "every class in the current
 * generation") or a persisted device-side class registry this runtime doesn't otherwise keep. Given
 * the desktop already has the list for free, reusing it is both less code and more correct.
 *
 * <p>Request file (CONTRACT.md Amendment 4's "Read-request payload"), deliberately as trivial as the
 * write path's payload — a flat JSON array of class-name strings:
 * <pre>
 * ["org.firstinspires.ftc.teamcode.DriveConstants"]
 * </pre>
 *
 * <p>Result file, in the exact same shape {@link FluxLiveTuning}'s payload uses (so the desktop side
 * can reuse the same parser it already has for {@code live_values.json}):
 * <pre>
 * [{"class":"org.firstinspires.ftc.teamcode.DriveConstants","field":"kP","type":"double","value":"0.042"}]
 * </pre>
 *
 * <p><b>Eligibility mirrors {@link FluxLiveTuning} exactly</b> (declared directly on a class
 * annotated {@link FluxLive}, {@code static}, not {@code final}, primitive/{@code String}/enum type)
 * — deliberately, not out of laziness: a {@code final} field is constant-folded at every call site,
 * so it can never diverge from source and is not interesting to read back; restricting to the same
 * eligible set keeps the read-back table directly comparable field-for-field against what
 * {@code fluxTune} would ever write.
 *
 * <p><b>Resolution rule (same as Amendment 3, critical for the same reason):</b> every class is
 * resolved through the CURRENT Flux generation's classloader
 * ({@link FluxReloadEngine#getCurrentGenerationLoader()}), never a stale one — reading through a
 * stale generation's {@code Class} object would report a value that isn't what the running OpMode
 * actually has.
 *
 * <p>Result codes reuse the same triad as the rest of the system: {@link FluxReloadEngine#RESULT_SUCCESS}
 * (every requested class resolved and was read), {@link FluxReloadEngine#RESULT_FAILED_CLEAN}
 * (nothing read — missing/malformed request, empty class list, or every class failed to resolve),
 * {@link FluxReloadEngine#RESULT_FAILED_DIRTY} (some classes resolved and some didn't — a "mixed"
 * outcome exactly like {@link FluxLiveTuning}'s partial-apply case, not an unknown-state one).
 */
final class FluxLiveReadback {

    private static final String TAG = "FLUX";

    /** CONTRACT.md Amendment 4 "Read-request payload file". */
    private static final File READ_REQUEST_FILE =
            new File(new File(AppUtil.FIRST_FOLDER, "flux"), "live_read_request.json");

    /** CONTRACT.md Amendment 4 "Read-result payload file". */
    private static final File READ_RESULT_FILE =
            new File(new File(AppUtil.FIRST_FOLDER, "flux"), "live_values_current.json");

    private FluxLiveReadback() {
    }

    /**
     * Reads CONTRACT.md Amendment 4's read-request payload, resolves each named class through the
     * current generation's classloader, reads its eligible static fields, and writes the result to
     * {@link #READ_RESULT_FILE}.
     *
     * @param context used only to obtain the app classloader fallback, same reasoning as
     *                {@link FluxLiveTuning#apply}.
     * @return one of {@link FluxReloadEngine}'s three result codes.
     */
    static int apply(Context context) {
        String json;
        try {
            json = readFile(READ_REQUEST_FILE);
        } catch (IOException e) {
            RobotLog.ee(TAG, e, "FLUX: could not read live-read request at %s", READ_REQUEST_FILE.getPath());
            return FluxReloadEngine.RESULT_FAILED_CLEAN;
        }

        List<String> classNames;
        try {
            classNames = parseClassNames(json);
        } catch (LiveReadParseException e) {
            RobotLog.ee(TAG, e, "FLUX: malformed live-read request at %s", READ_REQUEST_FILE.getPath());
            return FluxReloadEngine.RESULT_FAILED_CLEAN;
        }

        if (classNames.isEmpty()) {
            RobotLog.ww(TAG, "FLUX: live-read request at %s named no classes", READ_REQUEST_FILE.getPath());
            return FluxReloadEngine.RESULT_FAILED_CLEAN;
        }

        ClassLoader loader = resolveClassLoader(context);

        List<FieldValue> allValues = new ArrayList<FieldValue>();
        int resolvedClasses = 0;
        for (String className : classNames) {
            List<FieldValue> values = readOne(loader, className);
            if (values != null) {
                resolvedClasses++;
                allValues.addAll(values);
            }
        }

        try {
            writeResult(allValues);
        } catch (IOException e) {
            // The read itself may have gone fine, but if we can't report it, the desktop will see
            // no file / a stale one -- that must not be reported as clean success.
            RobotLog.ee(TAG, e, "FLUX: could not write live-read result to %s", READ_RESULT_FILE.getPath());
            return FluxReloadEngine.RESULT_FAILED_DIRTY;
        }

        if (resolvedClasses == 0) {
            return FluxReloadEngine.RESULT_FAILED_CLEAN;
        }
        if (resolvedClasses == classNames.size()) {
            return FluxReloadEngine.RESULT_SUCCESS;
        }
        RobotLog.ww(TAG, "FLUX: live read-back partially resolved -- %d/%d class(es), see log above for which failed",
                resolvedClasses, classNames.size());
        return FluxReloadEngine.RESULT_FAILED_DIRTY;
    }

    /** Same rule and same fallback reasoning as {@link FluxLiveTuning#resolveClassLoader}. */
    private static ClassLoader resolveClassLoader(Context context) {
        ClassLoader generationLoader = FluxReloadEngine.getCurrentGenerationLoader();
        if (generationLoader != null) {
            return generationLoader;
        }
        RobotLog.ii(TAG, "FLUX: no Flux generation yet this session -- resolving live-read classes "
                + "through the app classloader");
        return context.getApplicationContext().getClassLoader();
    }

    /**
     * Resolves one class and reads its eligible fields. Returns {@code null} (not an empty list) if
     * the class itself could not be resolved/qualified, so the caller can tell "class failed" apart
     * from "class resolved but has zero eligible fields."
     */
    private static List<FieldValue> readOne(ClassLoader loader, String className) {
        Class<?> clazz;
        try {
            clazz = loader.loadClass(className);
        } catch (ClassNotFoundException e) {
            RobotLog.ee(TAG, "FLUX: live-read %s skipped -- class not found through the current "
                    + "generation's classloader", className);
            return null;
        }

        if (!clazz.isAnnotationPresent(FluxLive.class)) {
            RobotLog.ee(TAG, "FLUX: live-read %s skipped -- not annotated @FluxLive", className);
            return null;
        }

        List<FieldValue> values = new ArrayList<FieldValue>();
        for (Field field : clazz.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                continue; // Same eligibility as FluxLiveTuning -- see class doc.
            }
            if (!isEligibleType(field.getType())) {
                continue;
            }
            String value;
            try {
                field.setAccessible(true);
                value = stringify(field.get(null));
            } catch (IllegalAccessException e) {
                RobotLog.ee(TAG, e, "FLUX: live-read %s.%s skipped -- could not read field",
                        className, field.getName());
                continue;
            }
            values.add(new FieldValue(className, field.getName(), typeName(field.getType()), value));
            RobotLog.ii(TAG, "FLUX: live read %s.%s = %s", className, field.getName(), value);
        }
        return values;
    }

    private static boolean isEligibleType(Class<?> type) {
        return type.isPrimitive() || type == String.class || type.isEnum();
    }

    /** Mirrors {@link FluxLiveTuning}'s supported type names, used verbatim by the desktop parser. */
    private static String typeName(Class<?> type) {
        if (type == String.class) {
            return "String";
        }
        if (type.isEnum()) {
            return "enum";
        }
        return type.getName(); // primitives: "boolean", "int", "double", ...
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        return String.valueOf(value);
    }

    // --- Result serialization -----------------------------------------------------------------

    private static final class FieldValue {
        final String className;
        final String fieldName;
        final String typeName;
        final String value;

        FieldValue(String className, String fieldName, String typeName, String value) {
            this.className = className;
            this.fieldName = fieldName;
            this.typeName = typeName;
            this.value = value;
        }
    }

    /** Same flat-array-of-flat-objects shape as {@code live_values.json} -- see class doc. */
    private static void writeResult(List<FieldValue> values) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            FieldValue v = values.get(i);
            sb.append('{');
            sb.append("\"class\":\"").append(jsonEscape(v.className)).append("\",");
            sb.append("\"field\":\"").append(jsonEscape(v.fieldName)).append("\",");
            sb.append("\"type\":\"").append(jsonEscape(v.typeName)).append("\",");
            sb.append("\"value\":\"").append(jsonEscape(v.value)).append('"');
            sb.append('}');
        }
        sb.append(']');

        File parent = READ_RESULT_FILE.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent.getPath());
        }
        OutputStream out = new FileOutputStream(READ_RESULT_FILE);
        try {
            out.write(sb.toString().getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    // --- Request reading / hand-rolled JSON parsing --------------------------------------------
    //
    // Same rationale as FluxLiveTuning: the payload shape is fixed and trivial (a flat array of
    // strings here, rather than a flat array of objects), so a hand-rolled scanner beats pulling in
    // a JSON library.

    private static final int EMPTY_READ_RETRIES = 10;
    private static final long EMPTY_READ_RETRY_MS = 20L;

    private static String readFile(File file) throws IOException {
        // Same FUSE race as FluxLiveTuning.readFile -- see that method's javadoc for the full
        // explanation. A push immediately followed by a broadcast can observe the just-pushed file
        // as present-but-zero-length.
        for (int attempt = 0; ; attempt++) {
            String content = readFileOnce(file);
            if (!content.trim().isEmpty() || attempt >= EMPTY_READ_RETRIES) {
                if (content.trim().isEmpty()) {
                    RobotLog.ww(TAG, "FLUX: live-read request still empty after %d retries", EMPTY_READ_RETRIES);
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

    private static final class LiveReadParseException extends Exception {
        LiveReadParseException(String message) {
            super(message);
        }
    }

    private static List<String> parseClassNames(String json) throws LiveReadParseException {
        Scanner s = new Scanner(json);
        s.skipWhitespace();
        s.expect('[');
        List<String> names = new ArrayList<String>();
        s.skipWhitespace();
        if (s.peek() == ']') {
            s.next();
            return names;
        }
        while (true) {
            s.skipWhitespace();
            names.add(s.parseJsonString());
            s.skipWhitespace();
            char c = s.next();
            if (c == ']') {
                break;
            }
            if (c != ',') {
                throw new LiveReadParseException("expected ',' or ']' at offset " + s.pos + ", got '" + c + "'");
            }
        }
        return names;
    }

    /** Minimal hand-rolled cursor, same shape as {@link FluxLiveTuning}'s private scanner. */
    private static final class Scanner {
        private final String src;
        private int pos;

        Scanner(String src) {
            this.src = src;
        }

        char peek() throws LiveReadParseException {
            if (pos >= src.length()) {
                throw new LiveReadParseException("unexpected end of input at offset " + pos);
            }
            return src.charAt(pos);
        }

        char next() throws LiveReadParseException {
            char c = peek();
            pos++;
            return c;
        }

        void expect(char c) throws LiveReadParseException {
            char actual = next();
            if (actual != c) {
                throw new LiveReadParseException("expected '" + c + "' at offset " + (pos - 1) + ", got '" + actual + "'");
            }
        }

        void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        String parseJsonString() throws LiveReadParseException {
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
                                throw new LiveReadParseException("truncated \\u escape at offset " + pos);
                            }
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw new LiveReadParseException("invalid \\u escape \"" + hex + "\" at offset " + pos);
                            }
                            break;
                        default:
                            throw new LiveReadParseException("invalid escape '\\" + esc + "' at offset " + (pos - 1));
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }
}
