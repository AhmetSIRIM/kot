package io.github.ahmetsirim.kot

import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The Gradle floor as a consumer meets it: a build on a Gradle older than the oldest one the
 * suite runs against is refused when the plugin is applied, in words that name both versions.
 * Without the refusal such a build fails wherever it first touches something that Gradle lacks,
 * or appears to work on a version nothing here has ever exercised.
 */
class GradleFloorFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    @Test
    fun `refuses a Gradle below the floor and names both versions`() {
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "consumer"""")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.ahmetsirim.kot")
            }

            kot {
                kotlinMetadataFloor.set("2.2")
            }
            """.trimIndent()
        )

        val result: BuildResult = GradleRunner.create()
            .withProjectDir(projectDir)
            .withGradleVersion(GRADLE_BELOW_THE_FLOOR)
            .withPluginClasspath()
            .withArguments("help")
            .buildAndFail()

        result.output shouldContain
            "kot: Gradle 8.2 or newer is required; this build runs Gradle $GRADLE_BELOW_THE_FLOOR."
    }

    private companion object {
        // The last release before the floor.
        private const val GRADLE_BELOW_THE_FLOOR = "8.1"
    }
}
