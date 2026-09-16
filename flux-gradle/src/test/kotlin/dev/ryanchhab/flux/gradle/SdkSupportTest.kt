package dev.ryanchhab.flux.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SdkSupportTest {

    @Test
    fun `the version Flux is exercised against is verified`() {
        assertTrue(SdkSupport.classify("12.0.0") is SdkSupport.Verdict.Verified)
    }

    /**
     * The whole point of the three-state change: a team on a current-but-not-latest SDK must not
     * be told their setup is broken. These are the real Maven Central releases whose API surface
     * was measured identical to 12.0.0.
     */
    @Test
    fun `measured-compatible releases warn rather than fail`() {
        for (v in listOf("8.1.0", "8.1.1", "8.2.0", "9.0.0", "9.2.0", "10.0.0", "10.3.0", "11.0.0", "11.2.1")) {
            assertTrue(SdkSupport.classify(v) is SdkSupport.Verdict.Untested, "expected $v to warn, not fail")
        }
    }

    /**
     * 8.0.0 and 7.x lack ClassManager.processAllClassesCalled. Flux would appear to work once and
     * then silently stop reloading, which is far worse than refusing up front.
     */
    @Test
    fun `releases without the one-shot guard field are unsupported`() {
        for (v in listOf("7.0.0", "7.1.0", "7.2.0", "8.0.0")) {
            val verdict = SdkSupport.classify(v)
            assertTrue(verdict is SdkSupport.Verdict.Unsupported, "expected $v to be unsupported")
            assertTrue(
                verdict.reason.contains("processAllClassesCalled"),
                "the reason should name the missing member, so a team can tell this apart from a version snob check",
            )
        }
    }

    @Test
    fun `a missing RobotCore is unsupported, not merely untested`() {
        assertTrue(SdkSupport.classify(null) is SdkSupport.Verdict.Unsupported)
        assertTrue(SdkSupport.classify("  ") is SdkSupport.Verdict.Unsupported)
    }

    @Test
    fun `an SDK newer than anything measured warns instead of failing`() {
        val verdict = SdkSupport.classify("13.0.0")
        assertTrue(verdict is SdkSupport.Verdict.Untested)
        assertTrue(verdict.reason.contains("newer"))
    }

    /**
     * A team on a locally built SDK gets a warning. Refusing would be the more expensive mistake:
     * an unparseable version is absence of evidence, not evidence of breakage.
     */
    @Test
    fun `unusual version strings warn rather than fail`() {
        assertTrue(SdkSupport.classify("12.0.0-SNAPSHOT") is SdkSupport.Verdict.Untested)
        assertTrue(SdkSupport.classify("master-SNAPSHOT") is SdkSupport.Verdict.Untested)
    }

    @Test
    fun `qualified versions still compare numerically`() {
        assertEquals(listOf(9, 0, 1), SdkSupport.parse("9.0.1-RC2"))
        assertNull(SdkSupport.parse("nightly"))
    }

    @Test
    fun `missing components compare as zero`() {
        assertEquals(0, SdkSupport.compare(listOf(9), listOf(9, 0, 0)))
        assertTrue(SdkSupport.compare(listOf(10, 0, 0), listOf(9, 9, 9)) > 0)
        // Not lexicographic: "10" must outrank "9", which string comparison gets backwards.
        assertTrue(SdkSupport.compare(SdkSupport.parse("10.0.0")!!, SdkSupport.parse("9.0.0")!!) > 0)
    }
}
