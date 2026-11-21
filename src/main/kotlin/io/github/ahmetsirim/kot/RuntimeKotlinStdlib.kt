package io.github.ahmetsirim.kot

import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.provider.Provider

/**
 * Finds the kotlin-stdlib a library brings to its consumers, taking its own runtime classpath
 * as the measure: a consumer's resolution takes the newest version any dependency in the graph
 * asks for.
 *
 * Both the version the classpath selects and the versions its dependencies ask for count, and
 * the highest wins. A version the producer forces or substitutes in its own build is not
 * published, so a consumer still resolves the one a dependency asked for.
 *
 * The result is a proxy, and it errs in known directions. An excluded dependency leaves no
 * trace in the graph, so what it would have brought is not seen. A published strict constraint
 * that lowers the stdlib holds for consumers too, yet the higher request still counts here. And
 * a stdlib raised only by a runtime-scoped dependency reaches a plain JVM consumer's runtime
 * classpath but not its compile classpath, where the compiler would read it.
 */
internal object RuntimeKotlinStdlib {

    private const val KOTLIN_GROUP = "org.jetbrains.kotlin"
    private const val STDLIB_NAME = "kotlin-stdlib"

    // Dotted numbers with an optional qualifier ("2.4.21", "2.4.0-RC2"); not a range or "2.+".
    private val plainVersion = Regex("""\d+(\.\d+)+([-.][A-Za-z0-9]+)*""")

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

    /**
     * Walks the whole resolved graph and returns the highest kotlin-stdlib version met, selected
     * or asked for; null when the graph holds none.
     */
    private fun findVersion(root: ResolvedComponentResult): String? {
        val visited: MutableSet<ResolvedComponentResult> = mutableSetOf()
        val pending: ArrayDeque<ResolvedComponentResult> = ArrayDeque(listOf(root))
        val stdlibVersions: MutableList<String> = mutableListOf()

        while (pending.isNotEmpty()) {
            val component: ResolvedComponentResult = pending.removeFirst()
            if (!visited.add(component)) continue

            component.moduleVersion
                ?.takeIf { module: ModuleVersionIdentifier ->
                    module.group == KOTLIN_GROUP && module.name == STDLIB_NAME
                }
                ?.let { module: ModuleVersionIdentifier -> stdlibVersions.add(module.version) }

            component.dependencies.forEach { dependency: DependencyResult ->
                when (dependency) {
                    is ResolvedDependencyResult -> {
                        requestedStdlibVersion(dependency = dependency)?.let(stdlibVersions::add)
                        pending.addLast(dependency.selected)
                    }

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

        return stdlibVersions.maxWithOrNull { left: String, right: String ->
            compareVersionParts(left = leadingNumbers(version = left), right = leadingNumbers(version = right))
        }
    }

    /**
     * The version [dependency] asks for when it points at kotlin-stdlib and names one plain
     * version. A range, a dynamic version or a request with no version (one a platform fills in)
     * names no single version and is left to the selected one.
     */
    private fun requestedStdlibVersion(dependency: ResolvedDependencyResult): String? {
        val requested: ModuleComponentSelector? = dependency.requested as? ModuleComponentSelector
        return requested
            ?.takeIf { selector: ModuleComponentSelector ->
                selector.group == KOTLIN_GROUP && selector.module == STDLIB_NAME
            }
            ?.versionConstraint?.requiredVersion
            ?.takeIf { version: String -> plainVersion.matches(version) }
    }

    /** The dotted numbers [version] starts with; "2.4.0-RC2" gives [2, 4, 0]. */
    private fun leadingNumbers(version: String): List<Int> {
        return version.takeWhile { character: Char -> character.isDigit() || character == '.' }
            .split(".")
            .mapNotNull { part: String -> part.toIntOrNull() }
    }
}
