package io.github.ahmetsirim.kot

import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Plain JARs: the classes an archive carries itself are read, which is the whole of a JVM
 * library. The Kotlin metadata and bytecode dimensions apply to it; the two Android-side
 * dimensions have nothing to read.
 */
class PlainJarFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    @Test
    fun `passes a plain JAR within its declared floors`() {
        PlainJarFixture.write(
            destination = File(projectDir, "fixture.jar"),
            classes = listOf(FixtureClass(classMajorVersion = 52, metadataVersion = intArrayOf(2, 1, 0))),
        )
        writeConsumerBuild("""kot { kotlinMetadataFloor.set("2.1"); jvmTargetFloor.set(8) }""")

        val result: BuildResult = runner().build()

        result.output shouldContain "Kotlin metadata 2.1 <= floor 2.1"
        result.output shouldContain "bytecode major 52 <= JVM 8 floor"
    }

    @Test
    fun `reads the bytecode version of a plain JAR's classes`() {
        PlainJarFixture.write(
            destination = File(projectDir, "fixture.jar"),
            classes = listOf(FixtureClass(classMajorVersion = 55, metadataVersion = null)),
        )
        writeConsumerBuild("""kot { jvmTargetFloor.set(8) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "bytecode major version 55 exceeds the JVM 8 floor"
    }

    @Test
    fun `reads the Kotlin metadata version of a plain JAR's classes`() {
        PlainJarFixture.write(
            destination = File(projectDir, "fixture.jar"),
            classes = listOf(FixtureClass(metadataVersion = intArrayOf(2, 3, 0))),
        )
        writeConsumerBuild("""kot { kotlinMetadataFloor.set("2.2") }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "emitted Kotlin metadata version 2.3 exceeds the declared floor 2.2"
    }

    /**
     * A module descriptor is compiled for Java 9 or newer whatever the library targets, and a
     * JVM older than that never loads it. Counting it would report a floor no consumer meets.
     */
    @Test
    fun `leaves the module descriptor of a plain JAR out of the bytecode floor`() {
        PlainJarFixture.write(
            destination = File(projectDir, "fixture.jar"),
            classes = listOf(
                FixtureClass(name = "fixture/Sample", classMajorVersion = 52, metadataVersion = null),
                FixtureClass(name = "module-info", classMajorVersion = 53, metadataVersion = null),
            ),
        )
        writeConsumerBuild("""kot { jvmTargetFloor.set(8) }""")

        val result: BuildResult = runner().build()

        result.output shouldContain "bytecode major 52 <= JVM 8 floor"
    }

    /**
     * A multi-release JAR keeps classes for newer runtimes under META-INF/versions/N; a runtime
     * older than N loads the classes outside that directory instead, so the versioned ones do
     * not raise the bytecode floor.
     */
    @Test
    fun `leaves the versioned classes of a multi-release JAR out of the bytecode floor`() {
        PlainJarFixture.write(
            destination = File(projectDir, "fixture.jar"),
            classes = listOf(
                FixtureClass(name = "fixture/Sample", classMajorVersion = 52, metadataVersion = null),
                FixtureClass(
                    name = "META-INF/versions/11/fixture/Sample",
                    classMajorVersion = 55,
                    metadataVersion = null,
                ),
                FixtureClass(name = "META-INF/versions/9/module-info", classMajorVersion = 53, metadataVersion = null),
            ),
        )
        writeConsumerBuild("""kot { jvmTargetFloor.set(8) }""")

        val result: BuildResult = runner().build()

        result.output shouldContain "bytecode major 52 <= JVM 8 floor"
    }

    /**
     * The Kotlin metadata stamp of a versioned class still counts. Which classes a JVM loads
     * says nothing about which ones a Kotlin compiler reads, so the higher stamp is kept.
     */
    @Test
    fun `counts the Kotlin metadata of a multi-release JAR's versioned classes`() {
        PlainJarFixture.write(
            destination = File(projectDir, "fixture.jar"),
            classes = listOf(
                FixtureClass(name = "fixture/Sample", classMajorVersion = 52, metadataVersion = intArrayOf(2, 1, 0)),
                FixtureClass(
                    name = "META-INF/versions/11/fixture/Sample",
                    classMajorVersion = 55,
                    metadataVersion = intArrayOf(2, 3, 0),
                ),
            ),
        )
        writeConsumerBuild("""kot { kotlinMetadataFloor.set("2.1"); jvmTargetFloor.set(8) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "emitted Kotlin metadata version 2.3 exceeds the declared floor 2.1"
    }

    /** Only a module descriptor: no class is left that sets the bytecode floor. */
    @Test
    fun `fails hard when a plain JAR carries only a module descriptor`() {
        PlainJarFixture.write(
            destination = File(projectDir, "fixture.jar"),
            classes = listOf(FixtureClass(name = "module-info", classMajorVersion = 53, metadataVersion = null)),
        )
        writeConsumerBuild("""kot { jvmTargetFloor.set(8) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "kot: no classes found in fixture.jar"
    }

    /** Only versioned classes: none of them sets the bytecode floor, so there is nothing to measure. */
    @Test
    fun `fails hard when a plain JAR carries only versioned classes`() {
        PlainJarFixture.write(
            destination = File(projectDir, "fixture.jar"),
            classes = listOf(
                FixtureClass(
                    name = "META-INF/versions/11/fixture/Sample",
                    classMajorVersion = 55,
                    metadataVersion = null,
                ),
            ),
        )
        writeConsumerBuild("""kot { jvmTargetFloor.set(8) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "kot: no classes found in fixture.jar"
    }

    /**
     * Only the descriptor at the archive's root is a module descriptor; a class of the same file
     * name inside a package is an ordinary class and counts.
     */
    @Test
    fun `counts a class named like a module descriptor that sits inside a package`() {
        PlainJarFixture.write(
            destination = File(projectDir, "fixture.jar"),
            classes = listOf(
                FixtureClass(name = "fixture/Sample", classMajorVersion = 52, metadataVersion = null),
                FixtureClass(name = "fixture/module-info", classMajorVersion = 55, metadataVersion = null),
            ),
        )
        writeConsumerBuild("""kot { jvmTargetFloor.set(8) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "bytecode major version 55 exceeds the JVM 8 floor"
    }

    /**
     * A jar inside a plain JAR is a resource, not part of the library's classes: with no
     * classes.jar beside it, a jar under libs/ stays closed.
     */
    @Test
    fun `leaves the jars nested in a plain JAR closed`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.jar"),
            classes = emptyList(),
            aarMetadataLines = null,
            includeClassesJar = false,
            bundledJars = mapOf("dep.jar" to listOf(FixtureClass(name = "dep/Newer", classMajorVersion = 61))),
            directClasses = listOf(
                FixtureClass(name = "fixture/Sample", classMajorVersion = 52, metadataVersion = null),
            ),
        )
        writeConsumerBuild("""kot { jvmTargetFloor.set(8) }""")

        val result: BuildResult = runner().build()

        result.output shouldContain "bytecode major 52 <= JVM 8 floor"
    }

    /** The Android-side floors live in a file only an AAR carries; declared over a JAR they cannot be measured. */
    @Test
    fun `fails hard when an Android floor is declared over a plain JAR`() {
        PlainJarFixture.write(destination = File(projectDir, "fixture.jar"), classes = listOf(FixtureClass()))
        writeConsumerBuild("""kot { compileSdkFloor.set(36) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "minCompileSdk missing from aar-metadata.properties in fixture.jar"
    }

    private fun runner(): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("verifyConsumerFloor", "--configuration-cache")
        .withPluginClasspath()

    private fun writeConsumerBuild(kotBlock: String) {
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "consumer"""")
        File(projectDir, "build.gradle.kts").writeText(
            """
            import io.github.ahmetsirim.kot.VerifyConsumerFloorTask

            plugins {
                id("io.github.ahmetsirim.kot")
            }

            $kotBlock

            tasks.named<VerifyConsumerFloorTask>("verifyConsumerFloor") {
                artifact.set(file("fixture.jar"))
            }
            """.trimIndent()
        )
    }
}
