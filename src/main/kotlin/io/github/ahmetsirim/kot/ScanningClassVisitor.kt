package io.github.ahmetsirim.kot

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

// JVM type descriptor of kotlin.Metadata: "L" + slash-separated FQN + ";".
private const val KOTLIN_METADATA_DESCRIPTOR = "Lkotlin/Metadata;"

/**
 * Collects the two facts a single class contributes: its class-file major version and,
 * when the class is Kotlin, the @kotlin.Metadata mv stamp.
 *
 * How ASM drives this: [org.objectweb.asm.ClassReader] parses the class file and PUSHES what
 * it finds as callbacks (the push model of a SAX XML parser). visit() fires once with the
 * class header, visitAnnotation() fires once per class-level annotation. Returning null from
 * visitAnnotation tells ASM to skip that annotation's content; returning a visitor makes ASM
 * replay the annotation's values onto it.
 */
internal class ScanningClassVisitor : ClassVisitor(Opcodes.ASM9) {

    var majorVersion: Int = 0 // Read by EmittedFloorReader after the scan; 0 only if visit() never fired.
    var metadataVersion: List<Int>? = null // Stays null for non-Kotlin classes.

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
        // The class-file version packs the minor version into the upper 16 bits;
        // masking with 0xFFFF keeps the lower 16, the major version.
        majorVersion = version and 0xFFFF
    }

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
        if (descriptor != KOTLIN_METADATA_DESCRIPTOR) return null // Skip every annotation except @kotlin.Metadata.

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
