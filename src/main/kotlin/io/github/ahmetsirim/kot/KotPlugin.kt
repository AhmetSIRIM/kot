package io.github.ahmetsirim.kot

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.util.GradleVersion

/**
 * Registers the `kot { }` block and the `verifyConsumerFloor` task.
 *
 * With the Android library plugin applied, the task joins `check`, and a library with a single
 * release variant gets that variant's AAR wired into the task. With the Java plugin applied
 * (the Java library and Kotlin JVM plugins apply it), the task joins `check` and reads the
 * archive of the `jar` task. In every other case the task's artifact is set by hand.
 */
class KotPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        ensureSupportedGradle()

        val extension: KotExtension = project.extensions.create(EXTENSION_NAME, KotExtension::class.java)
        extension.attachToCheck.convention(true)

        val verifyTask: TaskProvider<VerifyConsumerFloorTask> = project.tasks.register(
            VERIFY_CONSUMER_FLOOR_TASK_NAME,
            VerifyConsumerFloorTask::class.java
        ) { task: VerifyConsumerFloorTask ->
            task.group = "verification"
            task.description = "Verifies the artifact's emitted consumer floor against the declared floors."

            // Conventions, not values: a floor set directly on the task wins over the kot { } block.
            task.kotlinMetadataFloor.convention(extension.kotlinMetadataFloor)
            task.compileSdkFloor.convention(extension.compileSdkFloor)
            task.agpFloor.convention(extension.agpFloor)
            task.jvmTargetFloor.convention(extension.jvmTargetFloor)
            task.emittedFloorsReport.convention(
                project.layout.buildDirectory.file("reports/kot/emitted-floors.properties")
            )
        }

        // No AGP type may be named in this class, not even inside the lambda: Kotlin compiles
        // lambda bodies into synthetic methods of the enclosing class, and Gradle's reflective
        // decoration loads every method signature. Everything that touches AGP lives in
        // AgpReleaseAarWiring, loaded only when this block runs.
        project.plugins.withId("com.android.library") {
            AgpReleaseAarWiring.wire(project = project, verifyTask = verifyTask, extension = extension)
        }

        project.plugins.withId("java") {
            JavaJarWiring.wire(project = project, verifyTask = verifyTask, extension = extension)
        }
    }

    /**
     * Refuses a Gradle older than the oldest one the test suite runs against.
     *
     * A Kotlin build script cannot use this plugin on such a Gradle: the Kotlin compiler it embeds
     * fails on the plugin's metadata with a protocol buffer error that names neither the plugin
     * nor the cause. The plugins block is applied before the rest of the script is compiled, so
     * for a plugin applied there this check speaks first. A script that only has the plugin on its
     * classpath (a buildscript block, `apply false`, a convention plugin) is compiled before any
     * plugin code runs and still fails the old way.
     */
    private fun ensureSupportedGradle() {
        val current: GradleVersion = GradleVersion.current()
        if (current.baseVersion < GradleVersion.version(MINIMUM_GRADLE_VERSION)) {
            throw GradleException(
                "kot: Gradle $MINIMUM_GRADLE_VERSION or newer is required; this build runs Gradle ${current.version}."
            )
        }
    }

    private companion object {
        private const val MINIMUM_GRADLE_VERSION = "8.2"
        private const val EXTENSION_NAME = "kot"
        private const val VERIFY_CONSUMER_FLOOR_TASK_NAME = "verifyConsumerFloor"
    }

}
