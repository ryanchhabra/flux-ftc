package dev.ryanchhab.flux.gradle

import dev.ryanchhab.flux.gradle.fixtures.PlainDriver
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceDriverGuardTest {

    /**
     * The directory javac put the fixture classes in. Located from the fixture class itself rather
     * than hardcoded, so this survives a change of build layout.
     */
    private val fixtureClassesDir: File =
        File(PlainDriver::class.java.protectionDomain.codeSource.location.toURI())

    private val fqn = "dev.ryanchhab.flux.gradle.fixtures."

    @Test
    fun `finds drivers regardless of how the annotation was spelled`() {
        val found = DeviceDriverGuard.scan(listOf(fixtureClassesDir), emptyList())

        assertTrue(found.containsKey(fqn + "PlainDriver"), "a normally-imported annotation")
        assertTrue(
            found.containsKey(fqn + "FullyQualifiedDriver"),
            "a fully-qualified annotation -- the case that silently hot-deployed before this scan existed",
        )
    }

    @Test
    fun `does not flag classes that only mention the annotation in comments or strings`() {
        val found = DeviceDriverGuard.scan(listOf(fixtureClassesDir), emptyList())
        assertFalse(found.containsKey(fqn + "MentionsAnnotationInTextOnly"))
    }

    @Test
    fun `does not flag ordinary code that merely references a driver type`() {
        val found = DeviceDriverGuard.scan(listOf(fixtureClassesDir), emptyList())
        assertFalse(
            found.containsKey(fqn + "UsesDriverButIsNotOne"),
            "referencing a driver must stay hot-reloadable; only the annotated class is pinned",
        )
    }

    @Test
    fun `finds exactly the annotated fixtures and nothing else`() {
        val found = DeviceDriverGuard.scan(listOf(fixtureClassesDir), emptyList())
            .keys.filter { it.startsWith(fqn) }
        assertEquals(
            listOf(fqn + "DriverWithWideConstants", fqn + "FullyQualifiedDriver", fqn + "PlainDriver"),
            found.sorted(),
        )
    }

    @Test
    fun `a class packaged in a jar is found the same way as one in a directory`() {
        val tmp = createTempDirectory("flux-guard").toFile()
        val jar = File(tmp, "classes.jar")
        val classFile = File(fixtureClassesDir, fqn.replace('.', '/') + "PlainDriver.class")
        assertTrue(classFile.isFile, "fixture class file should exist at $classFile")

        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(fqn.replace('.', '/') + "PlainDriver.class"))
            zip.write(classFile.readBytes())
            zip.closeEntry()
        }

        val fromJar = DeviceDriverGuard.scan(emptyList(), listOf(jar))
        val fromDir = DeviceDriverGuard.scan(listOf(fixtureClassesDir), emptyList())

        // Same key AND same hash: AGP emits classes as directories or jars depending on
        // configuration, and a hash that changed with packaging would report a phantom hardware
        // change on every deploy.
        assertEquals(fromDir[fqn + "PlainDriver"], fromJar[fqn + "PlainDriver"])
    }

    @Test
    fun `change detection reports added, modified and removed drivers`() {
        val a = mapOf("A" to "h1")
        val b = mapOf("A" to "h1", "B" to "h2")

        assertNull(DeviceDriverGuard.firstChange(a, a))
        assertEquals("B (new device driver)", DeviceDriverGuard.firstChange(b, a))
        assertEquals("B (removed)", DeviceDriverGuard.firstChange(a, b))
        assertEquals("A", DeviceDriverGuard.firstChange(mapOf("A" to "h9"), a))
    }

    @Test
    fun `state round-trips, and absent state is distinguishable from empty state`() {
        val dir = createTempDirectory("flux-guard-state").toFile()

        // A team with no device drivers at all has a legitimately empty map. If that were
        // indistinguishable from "never recorded", every deploy would demand a reinstall.
        assertFalse(DeviceDriverGuard.hasState(dir))
        DeviceDriverGuard.saveState(dir, emptyMap())
        assertTrue(DeviceDriverGuard.hasState(dir))
        assertEquals(emptyMap(), DeviceDriverGuard.loadState(dir))

        val classes = mapOf("com.example.Driver" to "abc123")
        DeviceDriverGuard.saveState(dir, classes)
        assertEquals(classes, DeviceDriverGuard.loadState(dir))
    }

    @Test
    fun `malformed and non-class files are skipped rather than failing the build`() {
        val dir = createTempDirectory("flux-guard-junk").toFile()
        File(dir, "truncated.class").writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte()))
        File(dir, "notaclass.class").writeText("this is not bytecode at all")
        File(dir, "README.txt").writeText("ignored")

        assertEquals(emptyMap(), DeviceDriverGuard.scan(listOf(dir), emptyList()))
    }

    /**
     * Long and Double take two constant pool slots. Getting that wrong desynchronizes every later
     * index, so a driver whose pool contains one must still be found.
     */
    @Test
    fun `drivers whose constant pool contains long or double constants are still found`() {
        val found = DeviceDriverGuard.scan(listOf(fixtureClassesDir), emptyList())
        assertNotNull(found[fqn + "DriverWithWideConstants"])
    }
}
