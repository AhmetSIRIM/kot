package io.github.ahmetsirim.kot

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream
import java.util.Properties

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

    private fun writeAndroidLibraryProject() {
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

            buildscript {
                repositories {
                    google()
                    mavenCentral()
                }
                dependencies {
                    classpath("com.android.tools.build:gradle:$AGP_VERSION")
                    classpath(files(${pluginUnderTestClasspathAsKotlinArguments()}))
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
            }

            configure<KotExtension> {
                kotlinMetadataFloor.set("2.2")
                compileSdkFloor.set(29)
                agpFloor.set("8.1.0")
                jvmTargetFloor.set(17)
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

    /**
     * Reads the plugin's own classpath from the metadata file that the pluginUnderTestMetadata
     * task writes onto the functionalTest runtime classpath (this is the same file
     * withPluginClasspath reads), and renders it as quoted, comma-separated arguments for the
     * generated build script's files(...) call.
     */
    private fun pluginUnderTestClasspathAsKotlinArguments(): String {
        val metadata = Properties()
        checkNotNull(javaClass.classLoader.getResourceAsStream("plugin-under-test-metadata.properties")) {
            "plugin-under-test-metadata.properties not found on the test classpath"
        }.use { stream: InputStream -> metadata.load(stream) }

        return metadata.getProperty("implementation-classpath")
            .split(File.pathSeparator)
            .joinToString(separator = ", ") { path: String -> "\"${path.replace("\\", "\\\\")}\"" }
    }

    private companion object {
        // Pinned deliberately: AGP 9.0.x bundles Kotlin 2.2.x, which stamps metadata 2.2 (asserted above).
        private const val AGP_VERSION = "9.0.1"
    }
}
