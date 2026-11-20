package io.github.ahmetsirim.kot

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

private const val KOTLIN_METADATA_DESCRIPTOR = "Lkotlin/Metadata;"

// The class-file version int packs the minor version into the upper 16 bits; this keeps the lower 16.
private const val MAJOR_VERSION_MASK = 0xFFFF

/**
 * Collects the two facts a single class contributes: its class-file major version and, when the
 * class is Kotlin, the @kotlin.Metadata mv stamp.
 */
internal class ScanningClassVisitor : ClassVisitor(Opcodes.ASM9) {

    var majorVersion: Int = 0
    var metadataVersion: List<Int>? = null // Stays null for non-Kotlin classes.

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
        majorVersion = version and MAJOR_VERSION_MASK
    }

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
        if (descriptor != KOTLIN_METADATA_DESCRIPTOR) return null

        return object : AnnotationVisitor(Opcodes.ASM9) {
            // ASM delivers a primitive array through visit(name, value) in one call, never through visitArray.
            override fun visit(name: String?, value: Any?) {
                if (name == "mv" && value is IntArray) {
                    // Max, not overwrite: the JVM does not reject a duplicated annotation, and a
                    // crafted second (lower) stamp must never mask the real floor.
                    metadataVersion = maxVersionParts(left = metadataVersion, right = value.toList())
                }
            }
        }
    }
}
