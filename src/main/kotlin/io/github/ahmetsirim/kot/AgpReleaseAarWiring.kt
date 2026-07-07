package io.github.ahmetsirim.kot

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.LibraryVariant
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

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
     * the floor worth gating. Flavored libraries (several release variants) are not modeled yet;
     * with flavors, the last release variant AGP reports wins this wiring.
     */
    fun wire(project: Project, verifyTask: TaskProvider<VerifyConsumerFloorTask>) {
        val androidComponents: LibraryAndroidComponentsExtension = project
            .extensions.getByType(LibraryAndroidComponentsExtension::class.java)

        androidComponents.onVariants(
            /* selector = */ androidComponents.selector().withBuildType("release"),
        ) { variant: LibraryVariant ->
            verifyTask.configure { task: VerifyConsumerFloorTask ->
                task.artifact.set(variant.artifacts.get(/* type = */ SingleArtifact.AAR))
            }
        }
    }
}
