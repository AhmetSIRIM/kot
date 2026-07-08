package io.github.ahmetsirim.kot

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

class KotPlugin : Plugin<Project> {

    // Runs during the consumer build's configuration phase, once per project that applies the id.
    override fun apply(project: Project) {
        // Materializes the kot { } block in the consumer's script; EXTENSION_NAME is the block name they write.
        val extension: KotExtension = project.extensions.create(
            /* name = */ EXTENSION_NAME,
            /* type = */ KotExtension::class.java
        )
        extension.attachToCheck.convention(/* value = */ true)

        // register (not create) keeps the task lazy: this lambda runs only when a build actually needs the task.
        val verifyTask: TaskProvider<VerifyConsumerFloorTask> = project.tasks.register(
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
            // Where the measured emitted floors land after every run; relocatable via the same
            // convention-vs-set precedence as everything else on this task.
            task.emittedFloorsReport.convention(
                /* provider = */ project.layout.buildDirectory.file("reports/kot/emitted-floors.properties")
            )
        }

        // The optional AGP layer. withId fires the block only when (and if) the consumer's project
        // also applies the Android library plugin. No AGP type may be named in THIS class, not
        // even inside the lambda (Kotlin compiles lambda bodies into synthetic methods of the
        // enclosing class, and Gradle's reflective decoration loads every method signature);
        // everything AGP-flavored lives in AgpReleaseAarWiring, loaded only when this block runs.
        project.plugins.withId(/* pluginId = */ "com.android.library") {
            AgpReleaseAarWiring.wire(project = project, verifyTask = verifyTask, extension = extension)
        }
    }

    private companion object {
        private const val EXTENSION_NAME = "kot"
        private const val VERIFY_CONSUMER_FLOOR_TASK_NAME = "verifyConsumerFloor"
    }

}
