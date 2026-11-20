package io.github.ahmetsirim.kot

import org.gradle.api.GradleException
import org.objectweb.asm.ClassReader
import java.io.File
import java.io.InputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Reads [EmittedFloors] out of a built AAR.
 *
 * Three kinds of entry matter: aar-metadata.properties (the Android-side floors, written by AGP),
 * classes.jar (the library's own compiled bytecode) and every jar under libs/, where AGP packs
 * the library's local jar dependencies; consumers get those classes like any other. Classes are
 * inspected with ASM rather than a classloader: loading a class links its supertypes, and an
 * AAR's supertypes (Android framework types, dependencies) are not on the build's classpath.
 */
internal object EmittedFloorReader {

    private const val AAR_METADATA_ENTRY_NAME = "META-INF/com/android/build/gradle/aar-metadata.properties"

    // TODO (Ahmet SIRIM): Plain-JAR support. The Kotlin metadata and bytecode dimensions apply
    //  to any JVM artifact; only minCompileSdk and minAgpVersion are AAR-specific, and both are
    //  already optional. The reader would branch on the archive type: a JAR's classes sit at the
    //  top level instead of inside a nested classes.jar.
    private const val CLASSES_JAR_ENTRY_NAME = "classes.jar"
    private const val BUNDLED_JARS_DIRECTORY = "libs/"

    /**
     * Reads the properties entry, then every .class inside classes.jar and the bundled jars.
     * The nested jars are streamed straight out of the outer archive; nothing is extracted to
     * disk. Across classes the highest values win, because a single newer class raises the whole
     * artifact's floor.
     */
    fun read(aarFile: File): EmittedFloors {
        var minCompileSdk: Int? = null
        var minAgpVersion: String? = null
        var maxClassMajorVersion: Int? = null
        var maxMetadataVersion: List<Int>? = null

        ZipFile(aarFile).use { aar: ZipFile ->

            // The entry can be legitimately absent (a non-AGP archive); absence stays null here
            // and the task judges it per declared dimension.
            aar.getEntry(AAR_METADATA_ENTRY_NAME)?.let { metadataEntry: ZipEntry ->
                val properties = Properties()
                aar.getInputStream(metadataEntry).use { stream: InputStream -> properties.load(stream) }

                minCompileSdk = properties.getProperty("minCompileSdk")?.toIntOrNull() // Malformed value stays null.
                minAgpVersion = properties.getProperty("minAndroidGradlePluginVersion")
            }

            val classesJarEntry: ZipEntry = aar.getEntry(CLASSES_JAR_ENTRY_NAME)
                ?: throw GradleException("kot: classes.jar not found in ${aarFile.name}")

            val bundledJarEntries: List<ZipEntry> = aar.entries().asSequence()
                .filter { entry: ZipEntry ->
                    entry.name.startsWith(BUNDLED_JARS_DIRECTORY) && entry.name.endsWith(".jar", ignoreCase = true)
                }
                .toList()

            (listOf(classesJarEntry) + bundledJarEntries).forEach { jarEntry: ZipEntry ->
                ZipInputStream(aar.getInputStream(jarEntry)).use { jar: ZipInputStream ->
                    val bytecodeScan: BytecodeScan = scanJar(jar = jar)
                    maxClassMajorVersion = listOfNotNull(maxClassMajorVersion, bytecodeScan.maxClassMajorVersion)
                        .maxOrNull()
                    maxMetadataVersion = maxVersionParts(
                        left = maxMetadataVersion,
                        right = bytecodeScan.maxMetadataVersion,
                    )
                }
            }
        }

        return EmittedFloors(
            kotlinMetadataVersion = maxMetadataVersion,
            minCompileSdk = minCompileSdk,
            minAgpVersion = minAgpVersion,
            maxClassMajorVersion = maxClassMajorVersion,
        )
    }

    /** The two facts the bytecode contributes, aggregated across every class of the archive. */
    private data class BytecodeScan(
        val maxClassMajorVersion: Int?,
        val maxMetadataVersion: List<Int>?,
    )

    /** Walks every .class entry of the opened jar stream and keeps the highest values seen. */
    private fun scanJar(jar: ZipInputStream): BytecodeScan {
        var maxClassMajorVersion: Int? = null
        var maxMetadataVersion: List<Int>? = null

        generateSequence { jar.nextEntry }
            .filter { entry: ZipEntry -> entry.name.endsWith(".class") }
            .forEach { _: ZipEntry ->
                // readBytes() reads only the current entry: the stream reports end-of-entry as end-of-stream.
                val scanned: ScanningClassVisitor = scanClass(classBytes = jar.readBytes())

                val currentMaxClassMajorVersion: Int? = maxClassMajorVersion
                if (currentMaxClassMajorVersion == null || scanned.majorVersion > currentMaxClassMajorVersion) {
                    maxClassMajorVersion = scanned.majorVersion
                }
                maxMetadataVersion = maxVersionParts(left = maxMetadataVersion, right = scanned.metadataVersion)
            }

        return BytecodeScan(
            maxClassMajorVersion = maxClassMajorVersion,
            maxMetadataVersion = maxMetadataVersion,
        )
    }

    /**
     * Runs one class's bytes through ASM and returns the visitor with its findings. The SKIP_*
     * flags leave out method bodies, debug info and stack frames, none of which this scan reads.
     */
    private fun scanClass(classBytes: ByteArray): ScanningClassVisitor {
        val visitor = ScanningClassVisitor()
        ClassReader(classBytes).accept(
            visitor,
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return visitor
    }

}
