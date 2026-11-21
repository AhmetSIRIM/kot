package io.github.ahmetsirim.kot

import java.io.File

/**
 * Synthesizes a plain JAR: the classes sit at the top level of the archive, the layout any JVM
 * library ships. A [FixtureClass.name] is the entry path without its extension, so
 * `META-INF/versions/9/fixture/Sample` lands where a multi-release JAR keeps its versioned
 * classes, and `module-info` becomes the module descriptor.
 *
 * Matches a real JAR in: entry paths and class-file headers. Diverges in: no manifest unless
 * the class list is empty, and a class's internal name equals its entry path even for
 * versioned entries; the reader looks at neither.
 */
internal object PlainJarFixture {

    /** Writes [classes] and [rawEntries] (entry name to content) into [destination] and returns it. */
    fun write(
        destination: File,
        classes: List<FixtureClass>,
        rawEntries: Map<String, ByteArray> = emptyMap(),
    ): File {
        destination.writeBytes(AarFixture.classesJarBytes(classes = classes, rawEntries = rawEntries))
        return destination
    }
}
