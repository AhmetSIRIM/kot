package io.github.ahmetsirim.kot

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * The wiring for JVM libraries: a project with the Java plugin applied publishes the archive of
 * its `jar` task, so that archive is the one worth gating.
 *
 * Two kinds of project are outside this wiring and name their archive on the task: one that
 * publishes another archive than the `jar` task's (a shadow or fat jar), and a Kotlin
 * Multiplatform project, which builds one jar per JVM target under other task names.
 */
internal object JavaJarWiring {

    /**
     * Makes the `jar` task's archive the default artifact of [verifyTask] and attaches the gate
     * to `check`. The provider carries the `jar` task, so the archive is built before it is
     * verified.
     */
    fun wire(project: Project, verifyTask: TaskProvider<VerifyConsumerFloorTask>, extension: KotExtension) {
        val noArchive: RegularFileProperty = project.objects.fileProperty()
        verifyTask.configure { task: VerifyConsumerFloorTask ->
            // convention, not set: an artifact set on the task wins over the wiring.
            task.artifact.convention(
                project.tasks.named(JavaPlugin.JAR_TASK_NAME, AbstractArchiveTask::class.java)
                    .flatMap { jarTask: AbstractArchiveTask ->
                        // A disabled jar task builds nothing, yet its archive path can still hold
                        // a file from an earlier build. Supplying that path would verify bytecode
                        // the project no longer produces, so a disabled task supplies no archive.
                        if (jarTask.enabled) jarTask.archiveFile else noArchive
                    }
            )
        }

        // The dependency rides a Provider, so the attachToCheck opt-out is honored without
        // reading the property during configuration.
        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure { checkTask: Task ->
            checkTask.dependsOn(
                extension.attachToCheck.map { attached: Boolean ->
                    if (attached) listOf(verifyTask) else emptyList()
                }
            )
        }
    }
}
