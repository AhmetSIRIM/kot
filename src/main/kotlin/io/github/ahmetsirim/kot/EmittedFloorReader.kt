package io.github.ahmetsirim.kot

import org.gradle.api.GradleException
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

internal data class EmittedFloors(
    val kotlinMetadataVersion: List<Int>?,
    val minCompileSdk: Int?,
    val minAgpVersion: String?,
    val maxClassMajorVersion: Int?,
)

internal object EmittedFloorReader {

    private const val AAR_METADATA_ENTRY = "META-INF/com/android/build/gradle/aar-metadata.properties"

    fun read(aarFile: File): EmittedFloors {
        var minCompileSdk: Int? = null
        var minAgpVersion: String? = null
        var maxClassMajorVersion: Int? = null
        var maxMetadataVersion: List<Int>? = null

        ZipFile(aarFile).use { aar ->
            aar.getEntry(AAR_METADATA_ENTRY)?.let { metadataEntry ->
                val properties = Properties().apply { aar.getInputStream(metadataEntry).use(::load) }
                minCompileSdk = properties.getProperty("minCompileSdk")?.toIntOrNull()
                minAgpVersion = properties.getProperty("minAndroidGradlePluginVersion")
            }

            val classesJarEntry = aar.getEntry("classes.jar")
                ?: throw GradleException("kot: classes.jar not found in ${aarFile.name}")
            ZipInputStream(aar.getInputStream(classesJarEntry)).use { classesJar ->
                generateSequence { classesJar.nextEntry }
                    .filter { it.name.endsWith(".class") }
                    .forEach { _ ->
                        val scanned = scanClass(classesJar.readBytes())
                        if (maxClassMajorVersion == null || scanned.majorVersion > maxClassMajorVersion!!) {
                            maxClassMajorVersion = scanned.majorVersion
                        }
                        maxMetadataVersion = maxVersion(maxMetadataVersion, scanned.metadataVersion)
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

    private fun scanClass(classBytes: ByteArray): ScanningClassVisitor {
        val visitor = ScanningClassVisitor()
        ClassReader(classBytes)
            .accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return visitor
    }

    private fun maxVersion(left: List<Int>?, right: List<Int>?): List<Int>? = when {
        left == null -> right
        right == null -> left
        compareVersionParts(left, right) >= 0 -> left
        else -> right
    }

    private class ScanningClassVisitor : ClassVisitor(Opcodes.ASM9) {
        var majorVersion: Int = 0
        var metadataVersion: List<Int>? = null

        override fun visit(
            version: Int,
            access: Int,
            name: String?,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
        ) {
            majorVersion = version and 0xFFFF
        }

        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
            if (descriptor != "Lkotlin/Metadata;") return null
            return object : AnnotationVisitor(Opcodes.ASM9) {
                // ASM delivers primitive arrays through visit(name, value) in one call,
                // never through visitArray (that path is for non-primitive arrays only).
                override fun visit(name: String?, value: Any?) {
                    if (name == "mv" && value is IntArray) {
                        metadataVersion = value.toList()
                    }
                }
            }
        }
    }
}

internal fun compareVersionParts(left: List<Int>, right: List<Int>): Int =
    (0 until maxOf(left.size, right.size))
        .map { left.getOrElse(it) { 0 } - right.getOrElse(it) { 0 } }
        .firstOrNull { it != 0 }
        ?: 0
