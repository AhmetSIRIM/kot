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
 * passing): each of its paths is pinned here, plus the two wiring edges in front of them (a
 * non-archive artifact and no artifact at all).
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

    /**
     * An AAR also carries the library's local jar dependencies, under libs/, and consumers get
     * those classes like any other. A newer class there raises the artifact's bytecode floor
     * even when classes.jar itself sits on the declared one.
     */
    @Test
    fun `takes the class-file major version of a bundled jar into account`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(FixtureClass(name = "fixture/Own", classMajorVersion = 61)),
            aarMetadataLines = null,
            bundledJars = mapOf(
                "bundled.jar" to listOf(FixtureClass(name = "bundled/Newer", classMajorVersion = 62)),
            ),
        )
        writeConsumerBuild("""kot { jvmTargetFloor.set(17) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "bytecode major version 62 exceeds the JVM 17 floor"
    }

    /** The extension is matched without regard to case; a jar named in capitals is still a jar. */
    @Test
    fun `scans a bundled jar whose extension is upper case`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(FixtureClass(name = "fixture/Own", classMajorVersion = 61)),
            aarMetadataLines = null,
            bundledJars = mapOf(
                "BUNDLED.JAR" to listOf(FixtureClass(name = "bundled/Newer", classMajorVersion = 62)),
            ),
        )
        writeConsumerBuild("""kot { jvmTargetFloor.set(17) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "bytecode major version 62 exceeds the JVM 17 floor"
    }

    /** The same rule for the Kotlin dimension: a newer stamp inside a bundled jar counts. */
    @Test
    fun `takes the Kotlin metadata version of a bundled jar into account`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(FixtureClass(name = "fixture/Own", metadataVersion = intArrayOf(2, 2, 0))),
            aarMetadataLines = null,
            bundledJars = mapOf(
                "bundled.jar" to listOf(FixtureClass(name = "bundled/Newer", metadataVersion = intArrayOf(2, 3, 0))),
            ),
        )
        writeConsumerBuild("""kot { kotlinMetadataFloor.set("2.2") }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "emitted Kotlin metadata version 2.3 exceeds the declared floor 2.2"
    }

    /**
     * Only jars are opened: any other file under libs/ is left alone. The stray entry here is
     * a real jar under another extension and carries a class above the floor, so reading it
     * would turn this run red.
     */
    @Test
    fun `ignores entries under libs that are not jars`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(FixtureClass(name = "fixture/Own", classMajorVersion = 61)),
            aarMetadataLines = null,
            bundledJars = mapOf(
                "notes.txt" to listOf(FixtureClass(name = "stray/Newer", classMajorVersion = 62)),
            ),
        )
        writeConsumerBuild("""kot { jvmTargetFloor.set(17) }""")

        val result: BuildResult = runner().build()

        result.output shouldContain "bytecode major 61 <= JVM 17 floor"
    }

    /**
     * No classes.jar, and the archive's own entries hold no class either (a placeholder AAR):
     * a declared class dimension has nothing to measure and fails.
     */
    @Test
    fun `fails hard when a class floor is declared but the archive carries no classes jar`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = emptyList(),
            aarMetadataLines = listOf("minCompileSdk=36", "minAndroidGradlePluginVersion=8.1.0"),
            includeClassesJar = false,
        )
        writeConsumerBuild("""kot { kotlinMetadataFloor.set("2.2") }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "no class carrying kotlin.Metadata found in fixture.aar"
    }

    /** The same placeholder under the bytecode floor: no class to measure, so the gate fails. */
    @Test
    fun `fails hard when the JVM floor is declared but the archive carries no classes jar`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = emptyList(),
            aarMetadataLines = listOf("minCompileSdk=36", "minAndroidGradlePluginVersion=8.1.0"),
            includeClassesJar = false,
        )
        writeConsumerBuild("""kot { jvmTargetFloor.set(17) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "kot: no classes found in fixture.aar"
    }

    /**
     * An archive that carries classes both in a classes.jar and beside it: the nested jar must
     * not hide the others, or the newer class would pass unread.
     */
    @Test
    fun `reads the classes beside a classes jar as well as the ones inside it`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(FixtureClass(name = "fixture/Nested", classMajorVersion = 52)),
            aarMetadataLines = null,
            directClasses = listOf(FixtureClass(name = "fixture/Direct", classMajorVersion = 61)),
        )
        writeConsumerBuild("""kot { jvmTargetFloor.set(8) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "bytecode major version 61 exceeds the JVM 8 floor"
    }

    /** The same placeholder still carries its Android-side floors, and those stay verifiable. */
    @Test
    fun `verifies the Android floors of an archive that carries no classes jar`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = emptyList(),
            aarMetadataLines = listOf("minCompileSdk=36", "minAndroidGradlePluginVersion=8.1.0"),
            includeClassesJar = false,
        )
        writeConsumerBuild("""kot { compileSdkFloor.set(36) }""")

        val result: BuildResult = runner().build()

        result.output shouldContain "minCompileSdk 36 <= floor 36"
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

    /**
     * A value that is present but not a number is a different fault from a missing key, and the
     * message has to say which one it is: "missing" would send the author looking for a
     * declaration that is there.
     */
    @Test
    fun `names a minCompileSdk that is not a number instead of calling it missing`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(FixtureClass()),
            aarMetadataLines = listOf("minCompileSdk=thirty-six", "minAndroidGradlePluginVersion=8.1.0"),
        )
        writeConsumerBuild("""kot { compileSdkFloor.set(36) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain
            "minCompileSdk in aar-metadata.properties of fixture.aar is not a number: \"thirty-six\""
    }

    /**
     * The same value in a build that never declared the dimension: nothing is asked of it, so
     * the run passes, and the report carries the value as it was recorded instead of dropping it.
     */
    @Test
    fun `leaves a minCompileSdk that is not a number alone when the dimension is not declared`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(FixtureClass()),
            aarMetadataLines = listOf("minCompileSdk=thirty-six", "minAndroidGradlePluginVersion=8.1.0"),
        )
        writeConsumerBuild("""kot { kotlinMetadataFloor.set("2.2") }""")

        runner().build()

        File(projectDir, "build/reports/kot/emitted-floors.properties").readText() shouldContain
            "minCompileSdk=thirty-six"
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

        result.output shouldContain "kot: no classes found in fixture.aar"
    }

    /**
     * An entry named like a class that is not one: the failure names the entry and the archive.
     * The class reader's own wording ("Index 6 out of bounds for length 5") names neither.
     */
    @Test
    fun `names an entry that is not a class file`() {
        PlainJarFixture.write(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(FixtureClass()),
            rawEntries = mapOf("notes/readme.class" to "hello".toByteArray()),
        )
        writeConsumerBuild("""kot { jvmTargetFloor.set(17) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "kot: notes/readme.class in fixture.aar cannot be read as a class file"
    }

    /** The same failure inside a nested jar also names the jar the entry sits in. */
    @Test
    fun `names the nested jar of an entry that is not a class file`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(FixtureClass()),
            aarMetadataLines = null,
            bundledJars = mapOf("dep.jar" to listOf(FixtureClass(name = "dep/TooNew", classMajorVersion = 999))),
        )
        writeConsumerBuild("""kot { jvmTargetFloor.set(17) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain
            "kot: dep/TooNew.class in libs/dep.jar of fixture.aar cannot be read as a class file"
    }

    /**
     * A file that is not a zip at all errs closed: the build fails instead of passing, and the
     * failure names the file and says what is wrong with it. The JDK's own wording follows in
     * the message and varies across versions, so only the plugin's part is pinned.
     */
    @Test
    fun `errs closed on an artifact that is not an archive`() {
        File(projectDir, "fixture.aar").writeText("this is not a zip")
        writeConsumerBuild("""kot { kotlinMetadataFloor.set("2.2") }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "Execution failed for task ':verifyConsumerFloor'"
        result.output shouldContain "kot: fixture.aar is not a readable zip archive"
    }

    /**
     * No Android or Java plugin, no manual wiring: the task has nothing to verify and says so
     * itself, naming the places an artifact can come from. The failure is the task's own rather
     * than Gradle's generic input validation; the artifact is an optional input exactly so the
     * action can explain a missing one.
     */
    @Test
    fun `fails with its own message when no artifact is wired`() {
        writeConsumerBuild("""kot { kotlinMetadataFloor.set("2.2") }""", wireArtifact = false)

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "no artifact to verify: neither the Android library wiring nor the " +
            "Java plugin wiring supplied one (a disabled jar task supplies none), and none is set on the task."
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
