package io.github.ahmetsirim.kot

import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Edge cases of reading the artifact itself, in two families.
 *
 * Cross-class invariants: the reader's HIGHEST-wins rule only shows its worth on archives with
 * several classes, which the dimension-focused suite never builds.
 *
 * Unmeasurable artifacts: the check functions' second exit (a declared floor whose emitted
 * counterpart cannot be read fails HARD, because a gate that cannot measure is broken, not
 * passing) previously lived only in KDoc; every one of its five paths is pinned here, plus the
 * two wiring edges in front of them (a non-archive artifact and no artifact at all).
 */
class ArtifactEdgeCaseFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    /** One newer class raises the whole artifact's bytecode floor, wherever it sits in the jar. */
    @Test
    fun `takes the highest class-file major version across classes`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(
                FixtureClass(name = "fixture/Old", classMajorVersion = 61),
                FixtureClass(name = "fixture/New", classMajorVersion = 62),
            ),
            aarMetadataLines = null,
        )
        writeConsumerBuild("""kot { jvmTargetFloor.set(17) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "bytecode major version 62 exceeds the JVM 17 floor"
    }

    /** One newer stamp raises the whole artifact's metadata floor, wherever it sits in the jar. */
    @Test
    fun `takes the highest Kotlin metadata version across classes`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(
                FixtureClass(name = "fixture/Old", metadataVersion = intArrayOf(2, 2, 0)),
                FixtureClass(name = "fixture/New", metadataVersion = intArrayOf(2, 3, 0)),
            ),
            aarMetadataLines = null,
        )
        writeConsumerBuild("""kot { kotlinMetadataFloor.set("2.2") }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "emitted Kotlin metadata version 2.3 exceeds the declared floor 2.2"
    }

    /**
     * Mixed Kotlin/Java archives are the norm; a plain class scanned AFTER a Kotlin class must
     * not reset the accumulated stamp (the reader's max is null-tolerant: an absent side loses).
     */
    @Test
    fun `a plain class does not erase a Kotlin class's metadata version`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(
                FixtureClass(name = "fixture/KotlinOne", metadataVersion = intArrayOf(2, 3, 0)),
                FixtureClass(name = "fixture/PlainJava", metadataVersion = null),
            ),
            aarMetadataLines = null,
        )
        writeConsumerBuild("""kot { kotlinMetadataFloor.set("2.2") }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "emitted Kotlin metadata version 2.3 exceeds the declared floor 2.2"
    }

    /** No classes.jar at all: nothing bytecode-related is measurable, so the reader refuses. */
    @Test
    fun `fails hard when the archive carries no classes jar`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = emptyList(),
            aarMetadataLines = listOf("minCompileSdk=36", "minAndroidGradlePluginVersion=8.1.0"),
            includeClassesJar = false,
        )
        writeConsumerBuild("""kot { kotlinMetadataFloor.set("2.2") }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "classes.jar not found in fixture.aar"
    }

    /** A declared metadata floor over an archive with no Kotlin class cannot be waved through. */
    @Test
    fun `fails hard when the metadata floor is declared but no class carries the stamp`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(FixtureClass(name = "fixture/PlainJava", metadataVersion = null)),
            aarMetadataLines = null,
        )
        writeConsumerBuild("""kot { kotlinMetadataFloor.set("2.2") }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "no class carrying kotlin.Metadata found in fixture.aar"
    }

    /** The properties entry exists but lacks the key this floor needs: broken gate, not a pass. */
    @Test
    fun `fails hard when the compileSdk floor is declared but minCompileSdk is missing`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(FixtureClass()),
            aarMetadataLines = listOf("minAndroidGradlePluginVersion=8.1.0"),
        )
        writeConsumerBuild("""kot { compileSdkFloor.set(36) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "minCompileSdk missing from aar-metadata.properties in fixture.aar"
    }

    /** Same rule for the AGP dimension's key. */
    @Test
    fun `fails hard when the agp floor is declared but minAndroidGradlePluginVersion is missing`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(FixtureClass()),
            aarMetadataLines = listOf("minCompileSdk=36"),
        )
        writeConsumerBuild("""kot { agpFloor.set("8.1.0") }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "minAndroidGradlePluginVersion missing from aar-metadata.properties in fixture.aar"
    }

    /** A classes.jar with no .class entries leaves the JVM floor unmeasurable. */
    @Test
    fun `fails hard when the JVM floor is declared but the archive carries no classes`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = emptyList(),
            aarMetadataLines = null,
        )
        writeConsumerBuild("""kot { jvmTargetFloor.set(17) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "no .class entries found in classes.jar of fixture.aar"
    }

    /**
     * A file that is not a zip at all errs CLOSED: the build fails instead of passing. The exact
     * message belongs to the JDK's zip machinery and varies across versions, so only the failing
     * task is pinned; wrapping it in a friendlier message is a possible future refinement.
     */
    @Test
    fun `errs closed on an artifact that is not an archive`() {
        File(projectDir, "fixture.aar").writeText("this is not a zip")
        writeConsumerBuild("""kot { kotlinMetadataFloor.set("2.2") }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "Execution failed for task ':verifyConsumerFloor'"
    }

    /**
     * No AGP, no manual wiring: Gradle's own input validation refuses the unset @InputFile
     * before the action ever runs, naming the property. Pins the bare-JVM consumer experience.
     */
    @Test
    fun `fails input validation when no artifact is wired`() {
        writeConsumerBuild("""kot { kotlinMetadataFloor.set("2.2") }""", wireArtifact = false)

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "property 'artifact' doesn't have a configured value"
    }

    private fun runner(): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("verifyConsumerFloor", "--configuration-cache")
        .withPluginClasspath()

    private fun writeConsumerBuild(kotBlock: String, wireArtifact: Boolean = true) {
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "consumer"""")
        val wiring: String = if (wireArtifact) {
            """
            tasks.named<VerifyConsumerFloorTask>("verifyConsumerFloor") {
                artifact.set(file("fixture.aar"))
            }
            """.trimIndent()
        } else {
            ""
        }
        File(projectDir, "build.gradle.kts").writeText(
            """
            import io.github.ahmetsirim.kot.VerifyConsumerFloorTask

            plugins {
                id("io.github.ahmetsirim.kot")
            }

            $kotBlock

            $wiring
            """.trimIndent()
        )
    }
}
