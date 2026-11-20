package io.github.ahmetsirim.kot

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * Registers the `kot { }` block and the `verifyConsumerFloor` task.
 *
 * With the Android library plugin applied, the task joins `check`, and a library with a single
 * release variant gets that variant's AAR wired into the task. In every other case the task's
 * artifact is set by hand.
 */
class KotPlugin : Plugin<Project> {

    override fun apply(project: Project) {
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
    }

    private companion object {
        private const val EXTENSION_NAME = "kot"
        private const val VERIFY_CONSUMER_FLOOR_TASK_NAME = "verifyConsumerFloor"
    }

}
