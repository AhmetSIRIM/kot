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
 * End-to-end proof of the AGP wiring layer: a real Android library project is built by a real
 * AGP inside TestKit, and kot picks the produced AAR up automatically through
 * SingleArtifact.AAR; nothing wires the artifact by hand. Running verifyConsumerFloor alone
 * must also trigger the AAR build itself, because the wired Provider carries the producing
 * task as a dependency.
 *
 * Classpath note: this test does NOT use withPluginClasspath. TestKit injects the plugin under
 * test into its own classloader, which cannot see classes of other plugins the test build
 * applies, so the wiring would fail to resolve AGP types. Real builds do not have this problem
 * (all plugins of a plugins { } block share one classloader scope); it is a documented TestKit
 * limitation. The workaround is to put BOTH plugins on the consumer's buildscript classpath,
 * reading our own classpath from the plugin-under-test-metadata.properties that
 * gradlePlugin.testSourceSets generates.
 *
 * This is the slowest test of the suite (a full Android library build; AGP and its dependencies
 * are fetched into the TestKit Gradle home on first run) and it deliberately pins one AGP
 * version: the AGP version dictates the bundled Kotlin compiler, which dictates the emitted
 * metadata stamp this test asserts against. The Gradle x AGP version matrix is a later stage.
 */
class AgpWiringFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    /**
     * The full consumer story in one test: apply the plugin next to com.android.library, declare
     * floors in kot { }, run verifyConsumerFloor. The release AAR gets built, opened and checked
     * across all four dimensions; the aar-metadata values declared in the android { } block come
     * back out of the artifact and must round-trip exactly.
     */
    @Test
    fun `wires the release AAR automatically and verifies all four floors against it`() {
        writeAndroidLibraryProject()

        val result: BuildResult = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("verifyConsumerFloor", "--configuration-cache")
            .build()

        result.task(":verifyConsumerFloor")?.outcome shouldBe TaskOutcome.SUCCESS
        result.output shouldContain "consumer floor check passed"
        result.output shouldContain "Kotlin metadata 2.2 <= floor 2.2" // Emitted by AGP's built-in Kotlin.
        result.output shouldContain "minAndroidGradlePluginVersion = 8.1.0" // Round-trips from aarMetadata below.
    }

    /**
     * The gate must run where consumers actually look: a plain `./gradlew check` (what nearly
     * every CI invokes through `build`) has to include verifyConsumerFloor without anyone
     * remembering to call it by name. Born red from the review finding that the task was not
     * attached to any lifecycle task, so a silent toolchain bump sailed through consumer CI.
     * Asserted with --dry-run: the task graph is computed for real, nothing executes.
     */
    @Test
    fun `check includes the gate by default`() {
        writeAndroidLibraryProject()

        val result: BuildResult = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("check", "--dry-run")
            .build()

        result.output shouldContain ":verifyConsumerFloor SKIPPED"
    }

    /**
     * The attachToCheck opt-out: declaring `attachToCheck.set(false)` in the kot { } block keeps
     * check free of the gate for consumers who schedule verification elsewhere (a release-only
     * pipeline, say). Pins that the Provider-backed dependency honors the property.
     */
    @Test
    fun `check excludes the gate when attachToCheck is false`() {
        writeAndroidLibraryProject(extraKotConfiguration = "attachToCheck.set(false)")

        val result: BuildResult = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("check", "--dry-run")
            .build()

        result.output.contains(":verifyConsumerFloor") shouldBe false
    }

    /**
     * A hand-picked artifact must beat the automatic wiring. Born red from the review finding
     * that the wiring used set() and silently overwrote a task-level artifact (onVariants fires
     * after the consumer's script body). The manual artifact carries a deliberate metadata
     * violation while the real AGP build would pass, so the failure PROVES which file was read.
     */
    @Test
    fun `a task-level artifact overrides the AGP wiring`() {
        AarFixture.write(destination = File(projectDir, "handpicked.aar"), metadataVersion = intArrayOf(9, 9, 0))
        writeAndroidLibraryProject(
            extraTaskConfiguration = """artifact.set(file("handpicked.aar"))""",
        )

        val result: BuildResult = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("verifyConsumerFloor")
            .buildAndFail()

        result.output shouldContain "emitted Kotlin metadata version 9.9 exceeds the declared floor 2.2"
    }

    /**
     * Flavored libraries produce several release variants, and the single-artifact wiring holds
     * only the last one's AAR; a green run would silently cover one flavor while the others ship
     * unverified. The gate must refuse loudly instead of reporting that partial truth. Proper
     * per-variant modeling is a roadmap item; this pins the honest failure until it lands.
     */
    @Test
    fun `refuses a flavored library instead of verifying a single flavor silently`() {
        writeAndroidLibraryProject(
            extraAndroidConfiguration = """
                flavorDimensions += "tier"
                productFlavors {
                    create("free") { dimension = "tier" }
                    create("paid") { dimension = "tier" }
                }
            """.trimIndent(),
        )

        val result: BuildResult = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("verifyConsumerFloor")
            .buildAndFail()

        result.output shouldContain "2 release variants found (freeRelease, paidRelease)"
        result.output shouldContain "Flavored libraries are not modeled yet."
    }

    private fun writeAndroidLibraryProject(
        extraKotConfiguration: String = "",
        extraAndroidConfiguration: String = "",
        extraTaskConfiguration: String = "",
    ) {
        // local.properties points AGP at the machine's SDK; ANDROID_HOME wins when set.
        val sdkDir: String = System.getenv("ANDROID_HOME")
            ?: "${System.getProperty("user.home")}/Library/Android/sdk"
        File(projectDir, "local.properties").writeText("sdk.dir=$sdkDir")

        File(projectDir, "settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement {
                repositories {
                    google() // AGP's runtime dependencies (and built-in Kotlin's stdlib) resolve from here.
                    mavenCentral()
                }
            }

            rootProject.name = "consumer"
            """.trimIndent()
        )

        File(projectDir, "build.gradle.kts").writeText(
            """
            import com.android.build.api.dsl.LibraryExtension
            import io.github.ahmetsirim.kot.KotExtension
            import io.github.ahmetsirim.kot.VerifyConsumerFloorTask

            buildscript {
                repositories {
                    google()
                    mavenCentral()
                }
                dependencies {
                    classpath("com.android.tools.build:gradle:$AGP_VERSION")
                    classpath(files(${PluginUnderTestClasspath.asKotlinArguments()}))
                }
            }

            apply(plugin = "com.android.library")
            apply(plugin = "io.github.ahmetsirim.kot")

            configure<LibraryExtension> {
                namespace = "fixture.library"
                compileSdk = 36
                defaultConfig {
                    minSdk = 24
                    aarMetadata {
                        minCompileSdk = 29
                        minAgpVersion = "8.1.0"
                    }
                }
                $extraAndroidConfiguration
            }

            configure<KotExtension> {
                kotlinMetadataFloor.set("2.2")
                compileSdkFloor.set(29)
                agpFloor.set("8.1.0")
                jvmTargetFloor.set(17)
                $extraKotConfiguration
            }

            tasks.named<VerifyConsumerFloorTask>("verifyConsumerFloor") {
                $extraTaskConfiguration
            }
            """.trimIndent()
        )

        File(projectDir, "src/main/AndroidManifest.xml").also { manifest: File ->
            manifest.parentFile.mkdirs()
            manifest.writeText("<manifest />")
        }

        // One Kotlin class, so the AAR carries a real @kotlin.Metadata stamp from AGP's built-in Kotlin.
        File(projectDir, "src/main/kotlin/fixture/library/Sample.kt").also { source: File ->
            source.parentFile.mkdirs()
            source.writeText("package fixture.library\n\nclass Sample\n")
        }
    }

    private companion object {
        // Pinned deliberately: AGP 9.0.x bundles Kotlin 2.2.x, which stamps metadata 2.2 (asserted above).
        private const val AGP_VERSION = "9.0.1"
    }
}
