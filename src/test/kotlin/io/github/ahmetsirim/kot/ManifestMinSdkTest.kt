package io.github.ahmetsirim.kot

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The unit spec of the manifest scan. The functional suite pins what the task does with the
 * value; here the scan itself is held to two things a build run cannot show cheaply: that a
 * manifest which is not well-formed yields no value instead of a failure, and that a large,
 * hostile one is still scanned in one pass.
 */
class ManifestMinSdkTest {

    @Test
    fun `reads the minSdkVersion of the uses-sdk element`() {
        val manifest = """<manifest><uses-sdk android:minSdkVersion="23" /></manifest>"""

        ManifestMinSdk.recordedIn(manifest = manifest) shouldBe "23"
    }

    @Test
    fun `yields nothing for text that is not a manifest`() {
        ManifestMinSdk.recordedIn(manifest = "") shouldBe null
        ManifestMinSdk.recordedIn(manifest = "not xml at all") shouldBe null
    }

    /** A start tag that is never closed has no attributes to trust. */
    @Test
    fun `yields nothing for a uses-sdk element that is never closed`() {
        ManifestMinSdk.recordedIn(manifest = """<manifest><uses-sdk android:minSdkVersion="23" """) shouldBe null
    }

    /** An unclosed comment swallows the rest of the document, as it does for an XML parser. */
    @Test
    fun `yields nothing behind a comment that is never closed`() {
        ManifestMinSdk.recordedIn(manifest = """<!-- <uses-sdk android:minSdkVersion="23" />""") shouldBe null
    }

    /**
     * Each opening that never closes sends a backtracking matcher to the end of the text, once
     * per opening. The scan visits the text once; the time limit turns a return to quadratic
     * behavior into a red test instead of a slow build.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `scans a large manifest of unclosed comments in one pass`() {
        ManifestMinSdk.recordedIn(manifest = "<!--".repeat(HOSTILE_REPETITIONS)) shouldBe null
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `scans a large manifest of unclosed uses-sdk elements in one pass`() {
        ManifestMinSdk.recordedIn(manifest = "<uses-sdk ".repeat(HOSTILE_REPETITIONS)) shouldBe null
    }

    private companion object {
        private const val HOSTILE_REPETITIONS = 200_000
    }
}
