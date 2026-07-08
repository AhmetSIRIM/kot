package io.github.ahmetsirim.kot

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * TestKit functional tests for the verifyConsumerFloor gate. Every test applies the plugin to a
 * scratch consumer build, points the task at a synthesized AAR ([AarFixture]) and runs a real
 * Gradle invocation, so what is asserted here is exactly what a consumer of the plugin sees.
 *
 * The suite doubles as the gate's behavioral spec: beyond the plain pass and fail paths, each
 * test pins one deliberate design decision (equality on the AGP dimension, ignoring the metadata
 * patch component, reporting every violation at once, the empty-gate guard, task-level override,
 * configuration-cache compatibility), so reading the tests top to bottom introduces the product.
 */
class VerifyConsumerFloorFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    /**
     * The happy path: all four emitted values sit exactly on their declared floors and the gate
     * passes, printing one line per asserted dimension. Sitting exactly ON the floor passing is
     * itself a rule worth pinning: three of the four dimensions compare with <=, not <.
     */
    @Test
    fun `passes when all emitted floors are within the declared floors`() {
        AarFixture.write(destination = File(projectDir, "fixture.aar"))
        writeConsumerBuild(
            """
            kot {
                kotlinMetadataFloor.set("2.2")
                compileSdkFloor.set(36)
                agpFloor.set("8.1.0")
                jvmTargetFloor.set(17)
            }
            """.trimIndent()
        )

        val result: BuildResult = runner().build()

        result.task(":verifyConsumerFloor")?.outcome shouldBe TaskOutcome.SUCCESS
        result.output shouldContain "consumer floor check passed"
        result.output shouldContain "Kotlin metadata 2.2 <= floor 2.2"
    }

    /**
     * The silent-creep headline case, the reason this plugin exists: the artifact carries
     * metadata 2.3 while the declared consumer floor is 2.2, which is what an unnoticed AGP
     * built-in Kotlin bump produces. The build must fail and the message must name the
     * dimension, the emitted value and the floor. Undeclared floors are also asserted here:
     * they are skipped, and the skip is said out loud rather than silently reading as coverage.
     */
    @Test
    fun `fails when the emitted Kotlin metadata version exceeds the declared floor`() {
        AarFixture.write(destination = File(projectDir, "fixture.aar"), metadataVersion = intArrayOf(2, 3, 0))
        writeConsumerBuild(
            """
            kot {
                kotlinMetadataFloor.set("2.2")
            }
            """.trimIndent()
        )

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "consumer floor regression"
        result.output shouldContain "emitted Kotlin metadata version 2.3 exceeds the declared floor 2.2"
        result.output shouldContain "skipped undeclared floors: compileSdkFloor, agpFloor, jvmTargetFloor"
    }

    /**
     * The AGP dimension is an equality check, and this test pins the direction a <= check would
     * miss: the artifact records a LOWER minAgpVersion than declared. 1.0.0 is not a made-up
     * value; it is exactly what AGP stamps when the module's aarMetadata.minAgpVersion
     * declaration is removed, so "lower than declared" is the fingerprint of a lost declaration,
     * not of a generous one.
     */
    @Test
    fun `fails when the artifact records a lower minAgpVersion than the declared floor`() {
        AarFixture.write(destination = File(projectDir, "fixture.aar"), minAgpVersion = "1.0.0")
        writeConsumerBuild(
            """
            kot {
                agpFloor.set("8.1.0")
            }
            """.trimIndent()
        )

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "minAndroidGradlePluginVersion 1.0.0 does not equal the declared floor 8.1.0"
        result.output shouldContain "declaration changed or was removed"
    }

    /**
     * Violations are collected and reported together, not one per run: a toolchain bump that
     * raises several floors at once should show its full damage in a single red build instead
     * of forcing a fix-one-run-again loop. Three dimensions are violated here (metadata,
     * compileSdk, bytecode) and all three must appear in the same failure message.
     */
    @Test
    fun `reports every violated dimension in a single failure`() {
        AarFixture.write(
            destination = File(projectDir, "fixture.aar"),
            metadataVersion = intArrayOf(2, 3, 0),
            classMajorVersion = 62, // Java 18 bytecode against a Java 17 floor.
            minCompileSdk = 37,
        )
        writeConsumerBuild(
            """
            kot {
                kotlinMetadataFloor.set("2.2")
                compileSdkFloor.set(36)
                jvmTargetFloor.set(17)
            }
            """.trimIndent()
        )

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "emitted Kotlin metadata version 2.3 exceeds the declared floor 2.2"
        result.output shouldContain "minCompileSdk 37 exceeds the declared floor 36"
        result.output shouldContain "bytecode major version 62 exceeds the JVM 17 floor (class-file major 61)"
    }

    /**
     * Only major.minor of the metadata stamp participates in the comparison; the patch
     * component carries no compatibility meaning for consumers. An artifact stamped 2.2.255
     * still satisfies a 2.2 floor, so routine compiler patch updates never turn the gate red.
     */
    @Test
    fun `ignores the patch component of the emitted metadata version`() {
        AarFixture.write(destination = File(projectDir, "fixture.aar"), metadataVersion = intArrayOf(2, 2, 255))
        writeConsumerBuild(
            """
            kot {
                kotlinMetadataFloor.set("2.2")
            }
            """.trimIndent()
        )

        val result: BuildResult = runner().build()

        result.task(":verifyConsumerFloor")?.outcome shouldBe TaskOutcome.SUCCESS
        result.output shouldContain "Kotlin metadata 2.2 <= floor 2.2"
    }

    /**
     * The empty-gate guard: a kot { } block that declares nothing fails the build instead of
     * passing vacuously. A gate that runs green while guarding nothing is worse than no gate,
     * because it reads as coverage.
     */
    @Test
    fun `fails when the kot block declares no floor`() {
        AarFixture.write(destination = File(projectDir, "fixture.aar"))
        writeConsumerBuild(kotBlock = "")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "no consumer floor declared"
    }

    /**
     * The DSL's resolution order: a floor set directly on the task wins over the kot { } block,
     * because the plugin wires the extension through convention() (a default), not set() (an
     * assignment). The extension alone declares 2.0 and would fail against the emitted 2.2; the
     * task-level 2.2 overrides it and the build passes.
     */
    @Test
    fun `a floor set directly on the task overrides the kot block`() {
        AarFixture.write(destination = File(projectDir, "fixture.aar"))
        writeConsumerBuild(
            kotBlock = """
            kot {
                kotlinMetadataFloor.set("2.0")
            }
            """.trimIndent(),
            taskConfiguration = """kotlinMetadataFloor.set("2.2")""",
        )

        val result: BuildResult = runner().build()

        result.task(":verifyConsumerFloor")?.outcome shouldBe TaskOutcome.SUCCESS
        result.output shouldContain "Kotlin metadata 2.2 <= floor 2.2"
    }

    /**
     * The configuration-cache promise: because the task reads only its declared inputs and never
     * touches Project at execution time, a second identical run reuses the serialized task graph
     * without running any build script. This test would break the moment someone sneaks
     * undeclared state into the task action.
     */
    @Test
    fun `reuses the configuration cache on the second identical run`() {
        AarFixture.write(destination = File(projectDir, "fixture.aar"))
        writeConsumerBuild(
            """
            kot {
                kotlinMetadataFloor.set("2.2")
            }
            """.trimIndent()
        )

        runner().build() // First run calculates and stores the configuration-cache entry.
        val secondRun: BuildResult = runner().build()

        secondRun.output shouldContain "Reusing configuration cache."
    }

    /**
     * A crafted class carrying TWO @kotlin.Metadata annotations (the JVM does not reject the
     * duplicate) must not let the second, lower stamp mask the real floor: the highest stamp
     * wins within a class, mirroring the highest-wins rule across classes. Born red from the
     * review's adversarial finding that the visitor overwrote instead of max-accumulating,
     * producing a false PASS, the worst outcome a gate can have.
     */
    @Test
    fun `a duplicate lower metadata stamp cannot mask the real floor`() {
        AarFixture.write(
            destination = File(projectDir, "fixture.aar"),
            metadataVersion = intArrayOf(2, 3, 0),
            decoyMetadataVersion = intArrayOf(2, 0, 0),
        )
        writeConsumerBuild(
            """
            kot {
                kotlinMetadataFloor.set("2.2")
            }
            """.trimIndent()
        )

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "emitted Kotlin metadata version 2.3 exceeds the declared floor 2.2"
    }

    /**
     * A malformed floor string is a configuration mistake and must fail as one, loudly and
     * accurately. Born red from the review finding that unparsable parts silently became 0,
     * turning a typo into a guaranteed-red gate with a misleading violation message.
     */
    @Test
    fun `fails a malformed kotlinMetadataFloor as a configuration error`() {
        AarFixture.write(destination = File(projectDir, "fixture.aar"))
        writeConsumerBuild(
            """
            kot {
                kotlinMetadataFloor.set("2,2")
            }
            """.trimIndent()
        )

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain """kotlinMetadataFloor "2,2" is not a dotted numeric version"""
    }

    private fun runner(): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("verifyConsumerFloor", "--configuration-cache")
        .withPluginClasspath()

    /**
     * Writes the scratch consumer project: a settings file and a build script that applies the
     * plugin by id, declares floors through [kotBlock], and wires the task to the fixture AAR
     * (the wiring the AGP integration layer will eventually do per variant). [taskConfiguration]
     * lands inside the task's configuration block, after the artifact wiring.
     */
    private fun writeConsumerBuild(kotBlock: String, taskConfiguration: String = "") {
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "consumer"""")
        File(projectDir, "build.gradle.kts").writeText(
            """
            import io.github.ahmetsirim.kot.VerifyConsumerFloorTask

            plugins {
                id("io.github.ahmetsirim.kot")
            }

            $kotBlock

            tasks.named<VerifyConsumerFloorTask>("verifyConsumerFloor") {
                artifact.set(file("fixture.aar"))
                $taskConfiguration
            }
            """.trimIndent()
        )
    }
}
