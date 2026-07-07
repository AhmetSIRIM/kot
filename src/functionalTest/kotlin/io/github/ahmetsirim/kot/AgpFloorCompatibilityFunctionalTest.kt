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
 * The floor guard: proves the plugin actually RUNS against the oldest AGP it claims to support,
 * on a Gradle of that era (TestKit's withGradleVersion downloads and drives a different Gradle
 * than the one running the build).
 *
 * Why this exists: the plugin compiles against the FLOOR AGP API (the compileOnly pin), which
 * catches "we referenced API newer than the floor" at compile time. What compile time cannot
 * catch is the floor pin itself being raised (a dependency bump looks green, because the other
 * end-to-end test runs a NEWER AGP at runtime). This cell runs the wiring inside a build whose
 * AGP IS the floor, so code that needs anything newer fails here with the very error a consumer
 * on the floor would see (NoClassDefFoundError/NoSuchMethodError at apply time).
 *
 * Era differences handled on purpose: AGP 8.x has no built-in Kotlin, so the fixture applies
 * the standalone kotlin-android plugin (whose Kotlin dictates the emitted metadata floor: 1.9),
 * and the run skips --configuration-cache, which the main wiring test already guards on the
 * current toolchain.
 */
class AgpFloorCompatibilityFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    @Test
    fun `the wiring works against the declared floor AGP on its era Gradle`() {
        writeFloorEraAndroidLibraryProject()

        val result: BuildResult = GradleRunner.create()
            .withProjectDir(projectDir)
            .withGradleVersion(FLOOR_GRADLE_VERSION)
            .withArguments("verifyConsumerFloor")
            .build()

        result.task(":verifyConsumerFloor")?.outcome shouldBe TaskOutcome.SUCCESS
        result.output shouldContain "consumer floor check passed"
        result.output shouldContain "Kotlin metadata 1.9 <= floor 1.9" // Stamped by the standalone KGP below.
        result.output shouldContain "minAndroidGradlePluginVersion = $FLOOR_AGP_VERSION"
    }

    private fun writeFloorEraAndroidLibraryProject() {
        // local.properties points AGP at the machine's SDK; ANDROID_HOME wins when set.
        val sdkDir: String = System.getenv("ANDROID_HOME")
            ?: "${System.getProperty("user.home")}/Library/Android/sdk"
        File(projectDir, "local.properties").writeText("sdk.dir=$sdkDir")

        File(projectDir, "settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }

            rootProject.name = "floor-consumer"
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
                    classpath("com.android.tools.build:gradle:$FLOOR_AGP_VERSION")
                    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$FLOOR_ERA_KOTLIN_VERSION")
                    classpath(files(${PluginUnderTestClasspath.asKotlinArguments()}))
                }
            }

            apply(plugin = "com.android.library")
            apply(plugin = "org.jetbrains.kotlin.android")
            apply(plugin = "io.github.ahmetsirim.kot")

            configure<LibraryExtension> {
                namespace = "fixture.library"
                compileSdk = 34
                defaultConfig {
                    minSdk = 24
                    aarMetadata {
                        minCompileSdk = 29
                        minAgpVersion = "$FLOOR_AGP_VERSION"
                    }
                }
                // AGP 8.x defaults javac to 1.8 while the standalone KGP follows the JDK 17
                // daemon; without an explicit alignment the Kotlin compile task refuses the mix.
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }

            configure<KotExtension> {
                kotlinMetadataFloor.set("1.9")
                compileSdkFloor.set(34)
                agpFloor.set("$FLOOR_AGP_VERSION")
                jvmTargetFloor.set(17)
            }
            """.trimIndent()
        )

        File(projectDir, "src/main/AndroidManifest.xml").also { manifest: File ->
            manifest.parentFile.mkdirs()
            manifest.writeText("<manifest />")
        }

        File(projectDir, "src/main/kotlin/fixture/library/Sample.kt").also { source: File ->
            source.parentFile.mkdirs()
            source.writeText("package fixture.library\n\nclass Sample\n")
        }
    }

    private companion object {
        // The declared floor: the same version the compileOnly pin compiles against.
        private const val FLOOR_AGP_VERSION = "8.1.0"
        // A Gradle of the floor's era; AGP 8.1 requires Gradle 8.0+.
        private const val FLOOR_GRADLE_VERSION = "8.5"
        // AGP 8.x brings no built-in Kotlin; this standalone KGP stamps metadata 1.9.
        private const val FLOOR_ERA_KOTLIN_VERSION = "1.9.24"
    }
}
