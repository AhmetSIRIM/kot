package io.github.ahmetsirim.kot

import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.provider.Provider

/**
 * Finds the kotlin-stdlib a library brings to its consumers, taking the version its own runtime
 * classpath resolves to as the measure: a consumer's resolution takes the newest version any
 * dependency in the graph asks for.
 *
 * The resolved version is a proxy, and it errs in two known directions. A version the producer
 * forces, excludes or substitutes is not published, so consumers can still receive the newer
 * one. And a stdlib raised only by a runtime-scoped dependency reaches a plain JVM consumer's
 * runtime classpath but not its compile classpath, where the compiler would read it.
 */
internal object RuntimeKotlinStdlib {

    private const val KOTLIN_GROUP = "org.jetbrains.kotlin"
    private const val STDLIB_NAME = "kotlin-stdlib"

    /**
     * The kotlin-stdlib version [runtimeClasspath] resolves to; a provider with no value when
     * the graph holds no kotlin-stdlib. The configuration is resolved when the provider is
     * queried, not when it is created. A dependency that does not resolve fails the query: with
     * part of the graph missing, "no kotlin-stdlib" would be a guess.
     */
    fun versionOn(runtimeClasspath: Configuration): Provider<String> {
        // A null out of map leaves the provider without a value, which is how "no kotlin-stdlib
        // in the graph" travels to the task.
        return runtimeClasspath.incoming.resolutionResult.rootComponent
            .map { root: ResolvedComponentResult -> findVersion(root = root) }
    }

    /** Walks the resolved graph breadth-first; a module is selected once, so the first match is the version. */
    private fun findVersion(root: ResolvedComponentResult): String? {
        val visited: MutableSet<ResolvedComponentResult> = mutableSetOf()
        val pending: ArrayDeque<ResolvedComponentResult> = ArrayDeque(listOf(root))

        while (pending.isNotEmpty()) {
            val component: ResolvedComponentResult = pending.removeFirst()
            if (!visited.add(component)) continue

            val module: ModuleVersionIdentifier? = component.moduleVersion
            if (module != null && module.group == KOTLIN_GROUP && module.name == STDLIB_NAME) return module.version

            component.dependencies.forEach { dependency: DependencyResult ->
                when (dependency) {
                    is ResolvedDependencyResult -> pending.addLast(dependency.selected)

                    // Gradle's reason travels inside the message, not as a cause: the failure
                    // surfaces while a task input is evaluated, where Gradle's summary prints
                    // only the innermost exception, and a wrapped cause would hide this line.
                    is UnresolvedDependencyResult -> throw GradleException(
                        "kot: ${dependency.attempted.displayName} on the runtime classpath did not resolve, so " +
                            "the kotlin-stdlib consumers receive is unknown: ${dependency.failure.message}"
                    )
                }
            }
        }
        return null
    }
}
