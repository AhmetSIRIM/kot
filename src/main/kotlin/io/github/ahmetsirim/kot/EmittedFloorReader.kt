package io.github.ahmetsirim.kot

import org.gradle.api.GradleException
import org.objectweb.asm.ClassReader
import java.io.File
import java.io.InputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Reads [EmittedFloors] out of a built archive: an AAR or a plain JAR.
 *
 * The two differ in where the classes sit. A plain JAR carries them itself. An AAR nests them:
 * classes.jar holds the library's own bytecode, and every jar under libs/ holds a local jar
 * dependency AGP packed in; consumers get those classes like any other. The classes the archive
 * carries itself are always read. The nested jars are read only when a classes.jar entry is
 * present, so jars under libs/ with no classes.jar beside them stay closed; the file name plays
 * no part. aar-metadata.properties (the Android-side floors, written by AGP) is read wherever it
 * is present.
 *
 * Classes are inspected with ASM rather than a classloader: loading a class links its
 * supertypes, and an archive's supertypes (Android framework types, dependencies) are not on the
 * build's classpath.
 */
internal object EmittedFloorReader {

    private const val AAR_METADATA_ENTRY_NAME = "META-INF/com/android/build/gradle/aar-metadata.properties"
    private const val CLASSES_JAR_ENTRY_NAME = "classes.jar"
    private const val BUNDLED_JARS_DIRECTORY = "libs/"
    private const val VERSIONED_CLASSES_DIRECTORY = "META-INF/versions/"
    private const val MODULE_DESCRIPTOR_ENTRY_NAME = "module-info.class"

    /**
     * Reads the properties entry, then every class of the archive. Nested jars are streamed
     * straight out of the outer archive; nothing is extracted to disk. Across classes the
     * highest values win, because a single newer class raises the whole artifact's floor.
     */
    fun read(archiveFile: File): EmittedFloors {
        openArchive(archiveFile = archiveFile).use { archive: ZipFile ->
            val properties = Properties()
            // The entry can be legitimately absent (a plain JAR, a non-AGP archive); the two
            // values stay null then and the task judges that per declared dimension.
            archive.getEntry(AAR_METADATA_ENTRY_NAME)?.let { metadataEntry: ZipEntry ->
                archive.getInputStream(metadataEntry).use { stream: InputStream -> properties.load(stream) }
            }

            val nestedJarsScan: BytecodeScan = archive.getEntry(CLASSES_JAR_ENTRY_NAME)
                ?.let { classesJarEntry: ZipEntry -> scanNestedJars(aar = archive, classesJarEntry = classesJarEntry) }
                ?: noClassScanned
            val bytecodeScan: BytecodeScan = scanOwnClasses(archive = archive).mergedWith(other = nestedJarsScan)

            return EmittedFloors(
                kotlinMetadataVersion = bytecodeScan.maxMetadataVersion,
                minCompileSdk = properties.getProperty("minCompileSdk"),
                minAgpVersion = properties.getProperty("minAndroidGradlePluginVersion"),
                maxClassMajorVersion = bytecodeScan.maxClassMajorVersion,
            )
        }
    }

    /**
     * Opens [archiveFile] as a zip. A file the JDK cannot read as one fails here with the file's
     * name: the JDK's own message alone ("zip END header not found") says neither which file nor
     * that the artifact is the problem.
     */
    private fun openArchive(archiveFile: File): ZipFile {
        return try {
            ZipFile(archiveFile)
        } catch (failure: ZipException) {
            throw GradleException(
                "kot: ${archiveFile.name} is not a readable zip archive (${failure.message})",
                failure,
            )
        }
    }

    /** The two facts the bytecode contributes, aggregated across the classes scanned so far. */
    private data class BytecodeScan(
        val maxClassMajorVersion: Int?,
        val maxMetadataVersion: List<Int>?,
    ) {
        /** The scan joined with [other]; each fact keeps the higher of its two values. */
        fun mergedWith(other: BytecodeScan): BytecodeScan {
            return BytecodeScan(
                maxClassMajorVersion = listOfNotNull(maxClassMajorVersion, other.maxClassMajorVersion).maxOrNull(),
                maxMetadataVersion = maxVersionParts(left = maxMetadataVersion, right = other.maxMetadataVersion),
            )
        }
    }

    /** The scan before any class is taken in. */
    private val noClassScanned = BytecodeScan(maxClassMajorVersion = null, maxMetadataVersion = null)

    /** Scans classes.jar and every jar under libs/, each one a jar nested inside the AAR. */
    private fun scanNestedJars(aar: ZipFile, classesJarEntry: ZipEntry): BytecodeScan {
        val bundledJarEntries: List<ZipEntry> = aar.entries().asSequence()
            .filter { entry: ZipEntry ->
                entry.name.startsWith(BUNDLED_JARS_DIRECTORY) && entry.name.endsWith(".jar", ignoreCase = true)
            }
            .toList()

        return (listOf(classesJarEntry) + bundledJarEntries)
            .map { jarEntry: ZipEntry ->
                ZipInputStream(aar.getInputStream(jarEntry)).use { nestedJar: ZipInputStream ->
                    scanNestedJar(nestedJar = nestedJar)
                }
            }
            .fold(noClassScanned) { scan: BytecodeScan, jarScan: BytecodeScan -> scan.mergedWith(other = jarScan) }
    }

    /** Walks every .class entry of the opened jar stream. */
    private fun scanNestedJar(nestedJar: ZipInputStream): BytecodeScan {
        return generateSequence { nestedJar.nextEntry }
            .filter { entry: ZipEntry -> entry.name.endsWith(".class") }
            .fold(noClassScanned) { scan: BytecodeScan, _: ZipEntry ->
                // readBytes() reads only the current entry: the stream reports end-of-entry as end-of-stream.
                scan.mergedWith(other = scanClass(classBytes = nestedJar.readBytes()))
            }
    }

    /**
     * Scans the classes the archive carries itself, the layout of a plain JAR. Two kinds get
     * special treatment, because a consumer on an older JVM never loads them:
     *
     *  - the module descriptor (module-info.class at the archive's root), compiled for Java 9 or
     *    newer whatever the library targets, is left out;
     *  - the versioned classes of a multi-release JAR (under META-INF/versions/, picked only by
     *    a runtime of that version or newer) do not count toward the bytecode floor.
     *
     * Counting either would report a bytecode floor above the one consumers have to meet. The
     * Kotlin metadata stamp of a versioned class still counts: which classes a JVM loads says
     * nothing about which ones a Kotlin compiler reads.
     *
     * Neither rule reaches into the jars nested in an AAR: those are scanned whole.
     */
    private fun scanOwnClasses(archive: ZipFile): BytecodeScan {
        return archive.entries().asSequence()
            .filter { entry: ZipEntry -> entry.name.endsWith(".class") && entry.name != MODULE_DESCRIPTOR_ENTRY_NAME }
            .fold(noClassScanned) { scan: BytecodeScan, classEntry: ZipEntry ->
                val classBytes: ByteArray = archive.getInputStream(classEntry)
                    .use { stream: InputStream -> stream.readBytes() }
                val classScan: BytecodeScan = scanClass(classBytes = classBytes)
                val isVersioned: Boolean = classEntry.name.startsWith(VERSIONED_CLASSES_DIRECTORY)
                scan.mergedWith(other = if (isVersioned) classScan.copy(maxClassMajorVersion = null) else classScan)
            }
    }

    /**
     * Runs one class's bytes through ASM and returns what it found. The SKIP_* flags leave out
     * method bodies, debug info and stack frames, none of which this scan reads.
     */
    private fun scanClass(classBytes: ByteArray): BytecodeScan {
        val visitor = ScanningClassVisitor()
        ClassReader(classBytes).accept(
            visitor,
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return BytecodeScan(maxClassMajorVersion = visitor.majorVersion, maxMetadataVersion = visitor.metadataVersion)
    }

}
