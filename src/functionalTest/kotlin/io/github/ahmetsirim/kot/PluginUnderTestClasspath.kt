package io.github.ahmetsirim.kot

import java.io.File
import java.io.InputStream
import java.util.Properties

/**
 * Reads the plugin's own classpath from the metadata file the pluginUnderTestMetadata task puts
 * onto the functionalTest runtime classpath (the same file withPluginClasspath reads internally).
 *
 * Needed by the tests that CANNOT use withPluginClasspath: TestKit injects the plugin under test
 * into its own classloader, which cannot see classes of other plugins the test build applies
 * (a documented TestKit limitation real builds do not share, since all plugins of a plugins { }
 * block land in one classloader scope). Those tests put every plugin, ours included, onto the
 * consumer's buildscript classpath instead.
 */
internal object PluginUnderTestClasspath {

    /** Renders the classpath as quoted, comma-separated arguments for a files(...) call. */
    fun asKotlinArguments(): String {
        val metadata = Properties()
        checkNotNull(PluginUnderTestClasspath::class.java.classLoader.getResourceAsStream("plugin-under-test-metadata.properties")) {
            "plugin-under-test-metadata.properties not found on the test classpath"
        }.use { stream: InputStream -> metadata.load(stream) }

        return metadata.getProperty("implementation-classpath")
            .split(File.pathSeparator)
            .joinToString(separator = ", ") { path: String -> "\"${path.replace("\\", "\\\\")}\"" }
    }
}
