package io.github.ahmetsirim.kot

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.LibraryVariant
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * The AGP-touching half of the plugin, isolated in its own class ON PURPOSE.
 *
 * KotPlugin references this class only inside its plugins.withId("com.android.library")
 * callback, so the JVM resolves it (and the AGP types it needs) only when AGP is actually
 * present in the consumer's build. Folding this code into KotPlugin breaks every non-Android
 * consumer at plugin instantiation: Gradle decorates the plugin class reflectively, reflection
 * loads every method signature, and Kotlin compiles lambda bodies into synthetic methods OF THE
 * ENCLOSING CLASS, AGP parameter types included. The compileOnly AGP dependency makes those
 * types absent at runtime unless the consumer brings AGP.
 */
internal object AgpReleaseAarWiring {

    /**
     * Wires the release variant's AAR into the verify task through AGP's Variants API, so a
     * library author only declares floors; the artifact link, and the "build the AAR first"
     * task ordering, come for free.
     *
     * variant.artifacts.get(SingleArtifact.AAR) returns a Provider that both points at the final
     * AAR location and carries the producing task as a dependency; assigning it to the task's
     * RegularFileProperty is the whole integration. The promise is wired at configuration time,
     * the file exists only after the AAR task runs at execution time.
     *
     * Release only, deliberately: the artifact a library publishes is the release AAR, so that is
     * the floor worth gating. Flavored libraries (several release variants) are not modeled yet:
     * with more than one release variant the wiring supplies no AAR at all, and the consumer
     * names the one to verify on the task.
     */
    fun wire(project: Project, verifyTask: TaskProvider<VerifyConsumerFloorTask>, extension: KotExtension) {
        val androidComponents: LibraryAndroidComponentsExtension = project
            .extensions.getByType(LibraryAndroidComponentsExtension::class.java)

        androidComponents.onVariants(
            /* selector = */ androidComponents.selector().withBuildType("release"),
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
                // convention, not set: a consumer's explicit task-level artifact wins over the
                // wiring whenever it is set, mirroring the floor properties' kot{}-vs-task
                // precedence. With set() this callback would silently overwrite a hand-picked
                // artifact, because onVariants fires after the consumer's script body has run.
                val isOnlyReleaseVariantSoFar: Boolean = task.wiredVariantNames.get().size == 1
                task.artifact.convention(
                    if (isOnlyReleaseVariantSoFar) {
                        variant.artifacts.get(/* type = */ SingleArtifact.AAR)
                    } else {
                        project.objects.fileProperty()
                    }
                )
            }

            // The gate only earns its keep if it runs where consumers actually look: ./gradlew
            // check. A gate that waits to be invoked by name misses exactly the silent toolchain
            // bumps it exists to catch. Attached INSIDE the variant callback on purpose: a
            // library whose release variant is disabled (beforeVariants { enable = false })
            // never fires this block, never wires an artifact, and must not have its check
            // broken by a gate with nothing to read. The dependency rides a Provider, so the
            // attachToCheck opt-out is honored at task-graph time without reading the property
            // during configuration.
            project.tasks.named(/* name = */ LifecycleBasePlugin.CHECK_TASK_NAME).configure { checkTask: Task ->
                checkTask.dependsOn(
                    extension.attachToCheck.map { attached: Boolean ->
                        if (attached) listOf(verifyTask) else emptyList()
                    }
                )
            }
        }
    }
}
