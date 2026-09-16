package dev.ryanchhab.flux.gradle

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.Properties
import java.util.zip.ZipFile

/**
 * Detects hardware device-driver classes in compiled TeamCode bytecode.
 *
 * Why this cannot be a source-text scan. Device-driver classes are the one category of TeamCode
 * change that must never be hot-reloaded: the Robot Controller builds its HardwareMap from them at
 * startup and never rebuilds it on a code-only reload, so a reloaded copy is a different type and
 * `hardwareMap.get()` fails on the robot (docs/research/risks.md §1). Flux therefore has to be
 * *right* about which classes carry one of the six configuration annotations.
 *
 * The previous implementation searched source files for the literal strings "@MotorType",
 * "@I2cDeviceType" and so on. That is wrong in both directions, and both were reproduced on the
 * emulator before this class was written:
 *
 *  - False negative, the dangerous one. A driver written with a fully-qualified annotation --
 *    `@com.qualcomm.robotcore.hardware.configuration.annotations.I2cDeviceType` -- contains no
 *    "@I2cDeviceType" substring. It hot-deployed as "tier 1 · hot" and would have failed on the
 *    robot at `hardwareMap.get()`. Kotlin `import ... as Alias` has the same hole.
 *  - False positive. Any mention in a comment or a string literal blocked the deploy. A comment
 *    *describing* the scan was enough to trigger it.
 *
 * Bytecode has neither hole. All six annotations are `RetentionPolicy.RUNTIME` (verified with
 * `javap -v` against RobotCore 12.0.0 -- they have to be, since the SDK itself reflects on them at
 * startup), so the annotation's type descriptor is recorded in the class file's constant pool no
 * matter how the source spelled it, and comments and imports are gone by then.
 *
 * The scan parses the constant pool properly rather than searching the file's raw bytes, so a
 * match means "this class file genuinely references this type", not "these bytes happen to appear
 * somewhere in the file".
 */
object DeviceDriverGuard {

    private const val STATE_FILE_NAME = "device-state.properties"

    private const val ANNOTATION_PACKAGE = "com/qualcomm/robotcore/hardware/configuration/annotations"

    /** CONTRACT.md's classloader exclusion set. Descriptor form, as it appears in a constant pool. */
    private val DEVICE_ANNOTATION_DESCRIPTORS: Set<String> = setOf(
        "I2cDeviceType", "MotorType", "ServoType",
        "DigitalIoDeviceType", "AnalogSensorType", "DeviceProperties",
    ).map { "L$ANNOTATION_PACKAGE/$it;" }.toSet()

    /** Human-readable form for error messages. */
    val DEVICE_ANNOTATION_NAMES: List<String> = listOf(
        "@I2cDeviceType", "@MotorType", "@ServoType",
        "@DigitalIoDeviceType", "@AnalogSensorType", "@DeviceProperties",
    )

    /**
     * Every device-driver class found, mapped to a hash of its bytecode.
     *
     * Keyed by class name rather than file path: the same class compiled into a directory on one
     * run and packaged into a jar on another must compare equal, otherwise Flux would report a
     * phantom hardware change every time AGP changed its output shape.
     */
    fun scan(classesDirs: List<File>, classesJars: List<File>): Map<String, String> {
        val found = sortedMapOf<String, String>()

        for (dir in classesDirs) {
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .forEach { f ->
                    val bytes = try { f.readBytes() } catch (e: Exception) { return@forEach }
                    recordIfDeviceDriver(bytes, found)
                }
        }

        for (jar in classesJars) {
            if (!jar.isFile) continue
            try {
                ZipFile(jar).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .forEach { entry ->
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            recordIfDeviceDriver(bytes, found)
                        }
                }
            } catch (e: Exception) {
                // A jar Flux cannot open is not evidence of a device driver. Skipping it is the
                // same outcome as it containing none, and the dex step will fail loudly anyway.
            }
        }
        return found
    }

    private fun recordIfDeviceDriver(bytes: ByteArray, into: MutableMap<String, String>) {
        val parsed = try { parseConstantPool(bytes) } catch (e: Exception) { return }
        if (parsed == null) return
        if (parsed.utf8Entries.none { it in DEVICE_ANNOTATION_DESCRIPTORS }) return
        val name = parsed.thisClassName ?: return
        into[name] = Hashing.sha256Hex(bytes)
    }

    private class ParsedClass(
        val utf8Entries: Set<String>,
        val thisClassName: String?,
    )

    /**
     * Reads a class file's constant pool and resolves `this_class` to a name.
     *
     * Only the constant pool and the three u2 fields that follow it are read; fields, methods and
     * attributes are not walked, because everything needed is already in the pool.
     */
    private fun parseConstantPool(bytes: ByteArray): ParsedClass? {
        val input = DataInputStream(bytes.inputStream())
        if (input.readInt() != -0x35014542) return null // 0xCAFEBABE
        input.readUnsignedShort() // minor
        input.readUnsignedShort() // major

        val count = input.readUnsignedShort()
        val utf8 = HashMap<Int, String>()
        val classNameIndex = HashMap<Int, Int>()

        var i = 1
        while (i < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[i] = input.readUTF()                                  // Utf8
                7 -> classNameIndex[i] = input.readUnsignedShort()              // Class
                8, 16, 19, 20 -> input.skipFully(2)                             // String/MethodType/Module/Package
                15 -> input.skipFully(3)                                        // MethodHandle
                3, 4, 9, 10, 11, 12, 17, 18 -> input.skipFully(4)               // Integer/Float/refs/NameAndType/Dynamic
                // Long and Double occupy two constant pool slots. Failing to skip the extra slot
                // desynchronizes every later index, which is the classic way a hand-written class
                // parser silently reads garbage.
                5, 6 -> { input.skipFully(8); i++ }
                else -> return null // Unknown tag: cannot keep parsing safely.
            }
            i++
        }

        input.readUnsignedShort() // access_flags
        val thisClass = input.readUnsignedShort()
        val nameIdx = classNameIndex[thisClass]
        val thisName = nameIdx?.let { utf8[it] }?.replace('/', '.')

        return ParsedClass(utf8.values.toSet(), thisName)
    }

    private fun InputStream.skipFully(n: Int) {
        var remaining = n.toLong()
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) throw IllegalStateException("truncated class file")
            remaining -= skipped
        }
    }

    /**
     * The class whose device-driver status changed since [previous], or null if none did.
     * Returns a description suitable for showing the user.
     */
    fun firstChange(current: Map<String, String>, previous: Map<String, String>): String? {
        for ((name, hash) in current) {
            if (!previous.containsKey(name)) return "$name (new device driver)"
            if (previous[name] != hash) return name
        }
        for (name in previous.keys) {
            if (!current.containsKey(name)) return "$name (removed)"
        }
        return null
    }

    fun loadState(stateDir: File): Map<String, String> {
        val file = File(stateDir, STATE_FILE_NAME)
        if (!file.isFile) return emptyMap()
        val props = Properties()
        return try {
            file.inputStream().use { props.load(it) }
            props.stringPropertyNames().associateWith { props.getProperty(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveState(stateDir: File, classes: Map<String, String>) {
        stateDir.mkdirs()
        val props = Properties()
        classes.forEach { (k, v) -> props.setProperty(k, v) }
        File(stateDir, STATE_FILE_NAME).outputStream().use {
            props.store(it, "Flux device-driver classes as of the last known-good install/deploy")
        }
    }

    /**
     * True when Flux has never recorded a device-driver baseline for this module.
     *
     * Distinguishing "no baseline" from "baseline is empty" matters: a team with no device drivers
     * at all has a legitimately empty map, and must not be told to reinstall on every deploy.
     */
    fun hasState(stateDir: File): Boolean = File(stateDir, STATE_FILE_NAME).isFile
}
