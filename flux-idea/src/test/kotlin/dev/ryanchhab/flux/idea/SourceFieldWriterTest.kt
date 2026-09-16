package dev.ryanchhab.flux.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests [SourceFieldWriter.computeUpdatedText] -- the pure "compute the new file text" half of
 * write-back, which is exactly what the brief asks to verify headlessly: "exercise it directly on
 * a copy of the file." [SourceFieldWriter.write] itself (the IntelliJ Document/VFS half) is not
 * exercised here -- that needs a running IDE, which per the brief this task cannot launch. What's
 * tested here is the part that actually decides what bytes end up on disk, which is the part that
 * can corrupt a file if it's wrong.
 */
class SourceFieldWriterTest {

    private fun fixtureCopy(): File {
        val src = File(
            "../test-ftc-project/sdk/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/DriveConstants.java",
        )
        assertTrue(src.isFile, "fixture not found at ${src.absolutePath} -- run tests from flux-idea/")
        val tmp = Files.createTempFile("DriveConstants", ".java").toFile()
        tmp.writeText(src.readText())
        return tmp
    }

    @Test
    fun `replaces only the target field's literal, leaving the rest of the file untouched`() {
        val file = fixtureCopy()
        val original = file.readText()

        val updated = SourceFieldWriter.computeUpdatedText(file.name, original, "DriveConstants", "kP", "0.5")
        assertNotNullAndGet(updated)!!.let { text ->
            assertTrue(text.contains("public static volatile double kP = 0.5;"))
            // Every other field's literal must be byte-for-byte unchanged.
            assertTrue(text.contains("public static volatile double kI = 0.003;"))
            assertTrue(text.contains("public static volatile int targetRpm = 5200;"))
            assertTrue(text.contains("public static volatile boolean useVision = false;"))
            assertTrue(text.contains("public static final double MAX_POWER = 1.0;"))
            // Whole-file length delta should be exactly the literal length delta ("0.042" -> "0.5":
            // 5 chars -> 3 chars, delta -2), i.e. nothing else moved.
            assertEquals(original.length - 2, text.length)
        }
    }

    @Test
    fun `never touches a final field even if asked to`() {
        val file = fixtureCopy()
        val original = file.readText()
        // MAX_POWER is final -- FluxLiveSourceScanner must not report it as a field at all, so
        // there is nothing to locate and the whole-file text comes back null (safe no-op), never
        // a corrupting guess.
        val updated = SourceFieldWriter.computeUpdatedText(file.name, original, "DriveConstants", "MAX_POWER", "2.0")
        assertNull(updated)
    }

    @Test
    fun `returns null instead of guessing when the field can't be found`() {
        val file = fixtureCopy()
        val original = file.readText()
        val updated = SourceFieldWriter.computeUpdatedText(file.name, original, "DriveConstants", "doesNotExist", "1")
        assertNull(updated)
    }

    @Test
    fun `boolean field round-trips`() {
        val file = fixtureCopy()
        val original = file.readText()
        val updated = SourceFieldWriter.computeUpdatedText(file.name, original, "DriveConstants", "useVision", "true")
        assertTrue(assertNotNullAndGet(updated)!!.contains("public static volatile boolean useVision = true;"))
    }

    @Test
    fun `a stale field name after a rename is not found rather than mis-replacing a similarly named field`() {
        val file = fixtureCopy()
        val original = file.readText()
        // Simulate the file having been hand-edited since the last Refresh: kP renamed to kProp.
        val edited = original.replace("double kP = 0.042;", "double kProp = 0.042;")
        val updated = SourceFieldWriter.computeUpdatedText(file.name, edited, "DriveConstants", "kP", "9.9")
        assertNull(updated, "the old field name no longer exists in the edited file -- must not fall back to a guess")
    }

    private fun assertNotNullAndGet(updated: String?): String? {
        org.junit.jupiter.api.Assertions.assertNotNull(updated)
        return updated
    }
}
