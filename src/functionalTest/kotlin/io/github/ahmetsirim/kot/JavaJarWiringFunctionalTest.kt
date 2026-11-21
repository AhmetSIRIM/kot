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
 * The wiring for JVM libraries: with the Java plugin applied, the archive of the `jar` task is
 * verified without anyone naming it. A real Java source is compiled by the build under test, so
 * the bytecode version asserted here is the one a real toolchain emitted.
 */
class JavaJarWiringFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    /** Running the gate alone builds the jar first: the wired provider carries its producing task. */
    @Test
    fun `wires the jar automatically and builds it before verifying`() {
        writeJavaLibraryProject(kotBlock = "kot { jvmTargetFloor.set(11) }")

        val result: BuildResult = runner("verifyConsumerFloor").build()

        result.task(":jar")?.outcome shouldBe TaskOutcome.SUCCESS
        result.output shouldContain "bytecode major 55 <= JVM 11 floor"
    }

    @Test
    fun `fails when the wired jar exceeds the declared floor`() {
        writeJavaLibraryProject(kotBlock = "kot { jvmTargetFloor.set(8) }")

        val result: BuildResult = runner("verifyConsumerFloor").buildAndFail()

        result.output shouldContain "bytecode major version 55 exceeds the JVM 8 floor"
    }

    /** The Java plugin itself is the trigger, not only the Java library plugin built on it. */
    @Test
    fun `wires the jar of a project that applies the plain Java plugin`() {
        writeJavaLibraryProject(kotBlock = "kot { jvmTargetFloor.set(11) }", javaPlugin = "java")

        val result: BuildResult = runner("verifyConsumerFloor").build()

        result.output shouldContain "bytecode major 55 <= JVM 11 floor"
    }

    /** The wiring holds on the oldest supported Gradle, configuration cache included. */
    @Test
    fun `wires the jar on the oldest supported Gradle`() {
        writeJavaLibraryProject(kotBlock = "kot { jvmTargetFloor.set(11) }")

        val result: BuildResult = runner("check").withGradleVersion(OLDEST_SUPPORTED_GRADLE_VERSION).build()

        result.task(":jar")?.outcome shouldBe TaskOutcome.SUCCESS
        result.output shouldContain "bytecode major 55 <= JVM 11 floor"
    }

    /**
     * A disabled jar task builds nothing, but an archive from an earlier build can still lie at
     * its path. Reading that file would verify bytecode the project no longer produces, so the
     * wiring supplies nothing and the task stops.
     */
    @Test
    fun `does not read a stale archive when the jar task is disabled`() {
        writeJavaLibraryProject(kotBlock = "kot { jvmTargetFloor.set(11) }")
        runner("jar").build()
        File(projectDir, "build.gradle.kts").appendText("\ntasks.named<Jar>(\"jar\") { enabled = false }\n")

        val result: BuildResult = runner("verifyConsumerFloor").buildAndFail()

        result.output shouldContain "kot: no artifact to verify"
    }

    /** Asserted with --dry-run: the task graph is computed for real, nothing executes. */
    @Test
    fun `check includes the gate by default`() {
        writeJavaLibraryProject(kotBlock = "kot { jvmTargetFloor.set(11) }")

        val result: BuildResult = runner("check", "--dry-run").build()

        result.output shouldContain ":verifyConsumerFloor SKIPPED"
    }

    @Test
    fun `check excludes the gate when attachToCheck is false`() {
        writeJavaLibraryProject(kotBlock = "kot { jvmTargetFloor.set(11); attachToCheck.set(false) }")

        val result: BuildResult = runner("check", "--dry-run").build()

        result.output.contains(":verifyConsumerFloor") shouldBe false
    }

    /**
     * The wiring is a default. The Java plugin is applied after the task was given its artifact,
     * so the wiring runs last and would overwrite a plain value. The archive named on the task
     * carries a newer class than the project's own jar, so the verdict shows which one was read.
     */
    @Test
    fun `an artifact set on the task wins over a jar wired afterwards`() {
        PlainJarFixture.write(
            destination = File(projectDir, "other.jar"),
            classes = listOf(FixtureClass(classMajorVersion = 61, metadataVersion = null)),
        )
        writeSampleSource()
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "consumer"""")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.ahmetsirim.kot")
            }

            kot { jvmTargetFloor.set(11) }

            tasks.named<io.github.ahmetsirim.kot.VerifyConsumerFloorTask>("verifyConsumerFloor") {
                artifact.set(file("other.jar"))
            }

            apply(plugin = "java-library")
            tasks.withType<JavaCompile>().configureEach { options.release.set(11) }
            """.trimIndent()
        )

        val result: BuildResult = runner("verifyConsumerFloor").buildAndFail()

        result.output shouldContain "bytecode major version 61 exceeds the JVM 11 floor"
    }

    private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(*arguments, "--configuration-cache")
        .withPluginClasspath()

    private fun writeJavaLibraryProject(kotBlock: String, javaPlugin: String = "`java-library`") {
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "consumer"""")
        File(projectDir, "build.gradle.kts").writeText(
            """
            |plugins {
            |    $javaPlugin
            |    id("io.github.ahmetsirim.kot")
            |}
            |
            |// A fixed release keeps the emitted bytecode version independent of the JDK running the test.
            |tasks.withType<JavaCompile>().configureEach { options.release.set(11) }
            |
            |$kotBlock
            """.trimMargin()
        )
        writeSampleSource()
    }

    private fun writeSampleSource() {
        File(projectDir, "src/main/java/fixture/Sample.java").also { source: File ->
            source.parentFile.mkdirs()
            source.writeText("package fixture;\n\npublic class Sample {}\n")
        }
    }

    private companion object {
        private const val OLDEST_SUPPORTED_GRADLE_VERSION = "8.2"
    }
}
