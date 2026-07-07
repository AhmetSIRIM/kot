package io.github.ahmetsirim.kot

import org.gradle.api.Plugin
import org.gradle.api.Project

class KotPlugin : Plugin<Project> {

    // Runs during the consumer build's configuration phase, once per project that applies the id.
    override fun apply(project: Project) {
        // Materializes the kot { } block in the consumer's script; EXTENSION_NAME is the block name they write.
        val extension: KotExtension = project.extensions.create(
            /* name = */ EXTENSION_NAME,
            /* type = */ KotExtension::class.java
        )

        // register (not create) keeps the task lazy: this lambda runs only when a build actually needs the task.
        project.tasks.register(
            /* name = */ VERIFY_CONSUMER_FLOOR_TASK_NAME,
            /* type = */ VerifyConsumerFloorTask::class.java
        ) { task: VerifyConsumerFloorTask ->
            task.group = "verification" // Where the task appears in ./gradlew tasks.
            task.description = "Verifies the artifact's emitted consumer floor against the declared floors."

            // convention() sets a DEFAULT, not a value. At execution time each task property resolves in order:
            //   1. a value set directly on the task wins:
            //        tasks.named("verifyConsumerFloor") { kotlinMetadataFloor.set("2.0") }
            //   2. otherwise the value from the consumer's kot { } block flows in through this link
            //   3. otherwise the property stays absent and the task reports that dimension as skipped
            // The link carries the Property itself, not a copied value: the consumer may fill kot { }
            // before or after this wiring runs; the task still observes the final value at execution time.
            task.kotlinMetadataFloor.convention(/* provider = */ extension.kotlinMetadataFloor)
            task.compileSdkFloor.convention(/* provider = */ extension.compileSdkFloor)
            task.agpFloor.convention(/* provider = */ extension.agpFloor)
            task.jvmTargetFloor.convention(/* provider = */ extension.jvmTargetFloor)
        }
    }

    private companion object {
        private const val EXTENSION_NAME = "kot"
        private const val VERIFY_CONSUMER_FLOOR_TASK_NAME = "verifyConsumerFloor"
    }

}
