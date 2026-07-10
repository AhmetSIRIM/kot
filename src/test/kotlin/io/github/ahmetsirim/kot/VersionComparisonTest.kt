package io.github.ahmetsirim.kot

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The unit spec of the version comparison pair, the smallest shared logic of the plugin:
 * [compareVersionParts] backs both the reader (picking the highest emitted version across
 * classes) and the task (emitted vs declared floor); [maxVersionParts] backs the reader and the
 * class visitor. Everything here runs as a plain JVM test: no Gradle build, no TestKit, no
 * fixture AAR, so a contract break surfaces in milliseconds instead of a functional-test run.
 *
 * The assertions pin the comparator CONTRACT (the sign of the result), never the magnitude:
 * the implementation happens to return per-component differences, and a test expecting -1
 * exactly would break on a legitimate rewrite that returns some other negative number.
 */
class VersionComparisonTest {

    /**
     * The zero case of the comparator contract: identical component lists are equal.
     */
    @Test
    fun `equal versions compare as zero`() {
        compareVersionParts(left = listOf(2, 2), right = listOf(2, 2)) shouldBe 0
    }

    /**
     * A missing component counts as zero, so 2.2 and 2.2.0 name the same version. Pinned in
     * both directions, because the padding must not depend on which side is the shorter one.
     */
    @Test
    fun `a missing component counts as zero`() {
        compareVersionParts(left = listOf(2, 2), right = listOf(2, 2, 0)) shouldBe 0
        compareVersionParts(left = listOf(2, 2, 0), right = listOf(2, 2)) shouldBe 0
    }

    /**
     * The reason versions travel as ints and not text: as strings "2.10" sorts below "2.9",
     * as components 10 beats 9. Both directions, so the sign flips with the arguments.
     */
    @Test
    fun `components compare numerically not lexicographically`() {
        compareVersionParts(left = listOf(2, 10), right = listOf(2, 9)) shouldBeGreaterThan 0
        compareVersionParts(left = listOf(2, 9), right = listOf(2, 10)) shouldBeLessThan 0
    }

    /**
     * The complement of the padding rule: a shorter list only EQUALS its zero-padded twin;
     * against a non-zero extra component it loses (2.2 is below 2.2.1).
     */
    @Test
    fun `a shorter version loses to a non-zero extra component`() {
        compareVersionParts(left = listOf(2, 2), right = listOf(2, 2, 1)) shouldBeLessThan 0
    }

    /**
     * The earlier component wins regardless of what follows: 3.0 beats 2.9 even though 9
     * beats 0, because comparison stops at the leftmost difference.
     */
    @Test
    fun `the leftmost differing component decides`() {
        compareVersionParts(left = listOf(3, 0), right = listOf(2, 9)) shouldBeGreaterThan 0
    }

    /**
     * All four null combinations of the null-tolerant max: two absent sides stay absent, an
     * absent side always loses to a present one, and two present sides defer to the comparator
     * (checked with both orders, so the maximum cannot secretly be "whichever came first").
     */
    @Test
    fun `maxVersionParts treats an absent side as the losing side`() {
        maxVersionParts(left = null, right = null) shouldBe null
        maxVersionParts(left = null, right = listOf(1, 9)) shouldBe listOf(1, 9)
        maxVersionParts(left = listOf(1, 9), right = null) shouldBe listOf(1, 9)
        maxVersionParts(left = listOf(2, 9), right = listOf(2, 10)) shouldBe listOf(2, 10)
        maxVersionParts(left = listOf(2, 10), right = listOf(2, 9)) shouldBe listOf(2, 10)
    }
}
