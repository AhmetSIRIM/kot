package io.github.ahmetsirim.kot

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The kotlin-stdlib a library brings along is part of its Kotlin floor: a consumer's compiler
 * has to read the stdlib it receives, by the same rule as the library's own classes. The version
 * comes from the runtime classpath, so these tests resolve real dependency graphs against a
 * Maven repository written into the test project.
 *
 * The archive under verification is a fixture set on the task: its classes carry a low metadata
 * stamp, so a red verdict can only come from the stdlib.
 */
class KotlinStdlibFloorFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    @Test
    fun `fails when the kotlin-stdlib on the runtime classpath exceeds the Kotlin floor`() {
        publishModule(coordinate = "org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
        writeProject(dependency = "org.jetbrains.kotlin:kotlin-stdlib:2.4.21")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "kotlin-stdlib 2.4.21 exceeds the declared Kotlin floor 2.2"
    }

    @Test
    fun `passes when the kotlin-stdlib on the runtime classpath is within the Kotlin floor`() {
        publishModule(coordinate = "org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
        writeProject(dependency = "org.jetbrains.kotlin:kotlin-stdlib:2.2.21")

        val result: BuildResult = runner().build()

        result.output shouldContain "kotlin-stdlib 2.2.21 <= Kotlin floor 2.2"
    }

    /** A consumer receives the stdlib a dependency asks for as well, so the whole graph counts. */
    @Test
    fun `counts a kotlin-stdlib that only a dependency asks for`() {
        publishModule(coordinate = "org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
        publishModule(coordinate = "fixture:dependency:1.0", dependsOn = "org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
        writeProject(dependency = "fixture:dependency:1.0")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "kotlin-stdlib 2.4.21 exceeds the declared Kotlin floor 2.2"
    }

    @Test
    fun `says so when the runtime classpath carries no kotlin-stdlib`() {
        publishModule(coordinate = "fixture:dependency:1.0")
        writeProject(dependency = "fixture:dependency:1.0")

        val result: BuildResult = runner().build()

        result.output shouldContain "kot: no kotlin-stdlib version to check"
    }

    /** Without a declared Kotlin floor there is nothing to hold the stdlib against. */
    @Test
    fun `leaves the kotlin-stdlib alone when no Kotlin floor is declared`() {
        publishModule(coordinate = "org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
        writeProject(
            dependency = "org.jetbrains.kotlin:kotlin-stdlib:2.4.21",
            kotBlock = "kot { jvmTargetFloor.set(17) }",
        )

        val result: BuildResult = runner().build()

        result.output shouldContain "consumer floor check passed"
        result.output.contains("kotlin-stdlib") shouldBe false
    }

    /**
     * A dependency that does not resolve leaves the graph incomplete: the stdlib it would have
     * brought stays unknown. Calling that "no kotlin-stdlib" would pass a gate that could not
     * measure, so the run stops and names the dependency. Run without the configuration cache:
     * with it Gradle refuses the unresolved graph on its own, without it the graph is handed
     * over with the failure inside.
     */
    @Test
    fun `fails when a dependency on the runtime classpath does not resolve`() {
        writeProject(dependency = "fixture:missing:1.0", declareRepository = false)

        val result: BuildResult = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("verifyConsumerFloor")
            .withPluginClasspath()
            .buildAndFail()

        result.output shouldContain "kot: fixture:missing:1.0 on the runtime classpath did not resolve"
    }

    /** With no Kotlin floor declared the stdlib is not looked up, so the unresolved dependency goes unnoticed here. */
    @Test
    fun `does not resolve the runtime classpath when no Kotlin floor is declared`() {
        writeProject(
            dependency = "fixture:missing:1.0",
            kotBlock = "kot { jvmTargetFloor.set(17) }",
            declareRepository = false,
        )

        val result: BuildResult = runner().build()

        result.output shouldContain "consumer floor check passed"
    }

    /** The wired version is a default: the classpath resolves a stdlib within the floor, the task names a newer one. */
    @Test
    fun `a kotlin-stdlib version set on the task wins over the wired one`() {
        publishModule(coordinate = "org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
        writeProject(dependency = "org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
        File(projectDir, "build.gradle.kts").appendText(
            """

            tasks.named<io.github.ahmetsirim.kot.VerifyConsumerFloorTask>("verifyConsumerFloor") {
                kotlinStdlibVersion.set("2.4.21")
            }
            """.trimIndent()
        )

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "kotlin-stdlib 2.4.21 exceeds the declared Kotlin floor 2.2"
    }

    /** The measured version lands in the report beside the floors read from the archive. */
    @Test
    fun `writes the kotlin-stdlib version to the report`() {
        publishModule(coordinate = "org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
        writeProject(dependency = "org.jetbrains.kotlin:kotlin-stdlib:2.2.21")

        runner().build()

        File(projectDir, "build/reports/kot/emitted-floors.properties").readText() shouldContain
            "kotlinStdlibVersion=2.2.21"
    }

    /** A version with no minor part is a mistake, not version x.0. */
    @Test
    fun `refuses a kotlin-stdlib version without a minor part`() {
        writeUnwiredProject(kotlinStdlibVersion = "2")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "kot: kotlin-stdlib version \"2\" does not start with major.minor."
    }

    /** With neither plugin applied no classpath is wired; the stdlib version is given by hand. */
    @Test
    fun `checks a kotlin-stdlib version set on the task`() {
        writeUnwiredProject(kotlinStdlibVersion = "2.4.21")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "kotlin-stdlib 2.4.21 exceeds the declared Kotlin floor 2.2"
    }

    private fun runner(): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("verifyConsumerFloor", "--configuration-cache")
        .withPluginClasspath()

    private fun writeProject(
        dependency: String,
        kotBlock: String = """kot { kotlinMetadataFloor.set("2.2") }""",
        declareRepository: Boolean = true,
    ) {
        val repositories: String =
            if (declareRepository) """repositories { maven { url = uri("repository") } }""" else ""
        PlainJarFixture.write(
            destination = File(projectDir, "fixture.jar"),
            classes = listOf(FixtureClass(metadataVersion = intArrayOf(2, 1, 0))),
        )
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "consumer"""")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("io.github.ahmetsirim.kot")
            }

            $repositories

            dependencies {
                implementation("$dependency")
            }

            $kotBlock

            tasks.named<io.github.ahmetsirim.kot.VerifyConsumerFloorTask>("verifyConsumerFloor") {
                artifact.set(file("fixture.jar"))
            }
            """.trimIndent()
        )
    }

    private fun writeUnwiredProject(kotlinStdlibVersion: String) {
        PlainJarFixture.write(
            destination = File(projectDir, "fixture.jar"),
            classes = listOf(FixtureClass(metadataVersion = intArrayOf(2, 1, 0))),
        )
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "consumer"""")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.ahmetsirim.kot")
            }

            kot { kotlinMetadataFloor.set("2.2") }

            tasks.named<io.github.ahmetsirim.kot.VerifyConsumerFloorTask>("verifyConsumerFloor") {
                artifact.set(file("fixture.jar"))
                kotlinStdlibVersion.set("$kotlinStdlibVersion")
            }
            """.trimIndent()
        )
    }

    /** Writes a jar and a POM for [coordinate] into the test project's Maven repository. */
    private fun publishModule(coordinate: String, dependsOn: String? = null) {
        val (group: String, name: String, version: String) = coordinate.split(":")
        val directory = File(projectDir, "repository/${group.replace('.', '/')}/$name/$version").apply { mkdirs() }
        PlainJarFixture.write(destination = File(directory, "$name-$version.jar"), classes = emptyList())

        val dependencies: String = dependsOn?.split(":")?.let { (dependencyGroup, dependencyName, dependencyVersion) ->
            """
            <dependencies>
              <dependency>
                <groupId>$dependencyGroup</groupId>
                <artifactId>$dependencyName</artifactId>
                <version>$dependencyVersion</version>
                <scope>runtime</scope>
              </dependency>
            </dependencies>
            """.trimIndent()
        }.orEmpty()
        File(directory, "$name-$version.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$name</artifactId>
              <version>$version</version>
              $dependencies
            </project>
            """.trimIndent()
        )
    }
}
