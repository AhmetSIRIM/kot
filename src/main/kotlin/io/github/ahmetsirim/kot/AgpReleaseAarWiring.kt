package io.github.ahmetsirim.kot

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.LibraryVariant
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * The AGP-touching half of the plugin, kept in its own class so that AGP types are resolved
 * only when AGP is present.
 *
 * [KotPlugin] references this class only inside its `plugins.withId("com.android.library")`
 * callback. Folding this code into [KotPlugin] breaks every non-Android consumer at plugin
 * instantiation: Gradle decorates the plugin class reflectively, reflection loads every method
 * signature, and Kotlin compiles lambda bodies into synthetic methods of the enclosing class,
 * AGP parameter types included. The AGP dependency is compileOnly, so those types are absent at
 * runtime unless the consumer brings AGP.
 */
internal object AgpReleaseAarWiring {

    /**
     * Wires the release AAR into [verifyTask] through AGP's Variants API. The Provider that
     * `variant.artifacts.get(SingleArtifact.AAR)` returns carries the producing task, so the AAR
     * is built before it is verified.
     *
     * Release only: the artifact a library publishes is the release AAR, so that is the floor
     * worth gating. Flavored libraries (several release variants) are not modeled yet: with more
     * than one release variant the wiring supplies no AAR at all, and the consumer names the one
     * to verify on the task.
     */
    fun wire(project: Project, verifyTask: TaskProvider<VerifyConsumerFloorTask>, extension: KotExtension) {
        val androidComponents: LibraryAndroidComponentsExtension = project
            .extensions.getByType(LibraryAndroidComponentsExtension::class.java)

        androidComponents.onVariants(
            androidComponents.selector().withBuildType("release"),
        ) { variant: LibraryVariant ->
            verifyTask.configure { task: VerifyConsumerFloorTask ->
                // Names the variants in the task's refusal when the wiring cannot choose.
                task.wiredVariantNames.add(variant.name)

                // A single release variant leaves nothing to choose: its AAR is the default.
                // A further one makes the choice ambiguous, so the default is withdrawn (an
                // unset property is a provider with no value) rather than left on whichever
                // variant AGP reported last; verifying one flavor silently would read as
                // coverage of all of them.
                //
                // convention, not set: an artifact set on the task wins over the wiring whenever
                // it is set. With set() this callback would overwrite a hand-picked artifact,
                // because onVariants fires after the consumer's script body has run.
                val isOnlyReleaseVariantSoFar: Boolean = task.wiredVariantNames.get().size == 1
                task.artifact.convention(
                    if (isOnlyReleaseVariantSoFar) {
                        variant.artifacts.get(SingleArtifact.AAR)
                    } else {
                        project.objects.fileProperty()
                    }
                )
                // The stdlib version follows the artifact: known for a single release variant,
                // withdrawn when the choice of variant is ambiguous. It is reached through the
                // Kotlin floor, so with no floor declared the classpath is not resolved for it.
                val runtimeStdlibVersion: Provider<String> = if (isOnlyReleaseVariantSoFar) {
                    RuntimeKotlinStdlib.versionOn(runtimeClasspath = variant.runtimeConfiguration)
                } else {
                    project.objects.property(String::class.java)
                }
                task.kotlinStdlibVersion.convention(task.kotlinMetadataFloor.flatMap { runtimeStdlibVersion })
            }

            // Attached inside the variant callback on purpose: a library whose release variant is
            // disabled never fires this block and never wires an artifact, so its check must not
            // gain a gate with nothing to read. The dependency rides a Provider, so the
            // attachToCheck opt-out is honored without reading the property during configuration.
            project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure { checkTask: Task ->
                checkTask.dependsOn(
                    extension.attachToCheck.map { attached: Boolean ->
                        if (attached) listOf(verifyTask) else emptyList()
                    }
                )
            }
        }
    }
}
