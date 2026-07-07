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
 * Background: an AAR is a plain zip. The two entries this reader cares about are
 * aar-metadata.properties (the Android-side floors, written by AGP) and classes.jar, a nested
 * jar (itself also a zip) holding all compiled bytecode. Classes are inspected with ASM rather
 * than a classloader: loading a class eagerly links its supertypes, and an AAR's supertypes
 * (Android framework types, dependencies) are not on the build's classpath.
 */
internal object EmittedFloorReader {

    private const val AAR_METADATA_ENTRY_NAME = "META-INF/com/android/build/gradle/aar-metadata.properties"

    // TODO (Ahmet SIRIM): Plain-JAR support. The Kotlin metadata and bytecode dimensions apply
    //  to any JVM artifact; only minCompileSdk and minAgpVersion are AAR-specific, and both are
    //  already optional. The reader would branch on the archive type: a JAR's classes sit at the
    //  top level instead of inside a nested classes.jar.
    private const val CLASSES_JAR_ENTRY_NAME = "classes.jar"

    /**
     * Single pass over the archive: the properties entry first, then every .class inside the
     * nested classes.jar. The nested jar is streamed with [ZipInputStream] straight out of the
     * outer [ZipFile]; nothing is extracted to disk. Across classes the HIGHEST values win,
     * because a single newer class raises the whole artifact's floor.
     */
    fun read(aarFile: File): EmittedFloors {
        // Accumulators; each starts as "fact not seen yet" and fills only when the artifact provides it.
        var minCompileSdk: Int? = null
        var minAgpVersion: String? = null
        var maxClassMajorVersion: Int? = null
        var maxMetadataVersion: List<Int>? = null

        ZipFile(aarFile).use { aar: ZipFile ->

            // Cluster 1: the Android-side floors from aar-metadata.properties. The entry can be
            // legitimately absent (a non-AGP archive); absence stays null here and the task
            // judges it per declared dimension.
            aar.getEntry(AAR_METADATA_ENTRY_NAME)?.let { metadataEntry: ZipEntry ->
                val properties = Properties()
                aar.getInputStream(metadataEntry).use { stream: InputStream -> properties.load(stream) }

                minCompileSdk = properties.getProperty("minCompileSdk")?.toIntOrNull() // Malformed value stays null.
                minAgpVersion = properties.getProperty("minAndroidGradlePluginVersion")
            }

            // Cluster 2: the bytecode facts from the nested classes.jar. ZipFile offers random
            // access by name but only over a real file on disk; the nested classes.jar is not a
            // file, just bytes inside the outer zip, so it is walked with the forward-only
            // ZipInputStream instead of being extracted first.
            val classesJarEntry: ZipEntry = aar.getEntry(CLASSES_JAR_ENTRY_NAME)
                ?: throw GradleException("kot: classes.jar not found in ${aarFile.name}")

            ZipInputStream(aar.getInputStream(classesJarEntry)).use { classesJar: ZipInputStream ->
                val bytecodeScan: BytecodeScan = scanClassesJar(classesJar = classesJar)
                maxClassMajorVersion = bytecodeScan.maxClassMajorVersion
                maxMetadataVersion = bytecodeScan.maxMetadataVersion
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

    /**
     * Walks every .class entry of the (already opened) classes.jar stream and keeps the HIGHEST
     * values seen; one newer class raises the whole artifact's floor.
     */
    private fun scanClassesJar(classesJar: ZipInputStream): BytecodeScan {
        var maxClassMajorVersion: Int? = null
        var maxMetadataVersion: List<Int>? = null

        generateSequence { classesJar.nextEntry } // nextEntry returns null after the last entry, ending the sequence.
            .filter { entry: ZipEntry -> entry.name.endsWith(".class") } // Skip manifests, kotlin_module files, etc.
            .forEach { _: ZipEntry ->
                // readBytes() reads only the current entry: the stream reports end-of-entry as end-of-stream.
                val scanned: ScanningClassVisitor = scanClass(classBytes = classesJar.readBytes())

                val currentMaxClassMajorVersion: Int? = maxClassMajorVersion
                if (currentMaxClassMajorVersion == null || scanned.majorVersion > currentMaxClassMajorVersion) {
                    maxClassMajorVersion = scanned.majorVersion
                }
                maxMetadataVersion = maxVersion(left = maxMetadataVersion, right = scanned.metadataVersion)
            }

        return BytecodeScan(
            maxClassMajorVersion = maxClassMajorVersion,
            maxMetadataVersion = maxMetadataVersion,
        )
    }

    /**
     * Runs one class's bytes through ASM: [ClassReader] parses the class-file format and replays
     * it onto a fresh [ScanningClassVisitor], which is returned with its findings. The SKIP_*
     * flags leave out method bodies, debug info and stack frames, none of which this scan reads.
     */
    private fun scanClass(classBytes: ByteArray): ScanningClassVisitor {
        val visitor = ScanningClassVisitor()
        ClassReader(classBytes).accept(
            /* classVisitor = */ visitor,
            /* parsingOptions = */ ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return visitor
    }

    /** Null-tolerant maximum: an absent side loses; two present sides compare component-wise. */
    private fun maxVersion(left: List<Int>?, right: List<Int>?): List<Int>? {
        return when {
            left == null -> right
            right == null -> left
            compareVersionParts(left = left, right = right) >= 0 -> left
            else -> right
        }
    }
}
