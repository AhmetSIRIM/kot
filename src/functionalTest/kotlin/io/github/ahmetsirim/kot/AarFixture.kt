package io.github.ahmetsirim.kot

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Synthesizes a minimal AAR whose emitted consumer floors are fully controlled by the test.
 *
 * The AAR is hand-built as a zip carrying exactly the entries the reader inspects: a nested
 * `classes.jar` with one ASM-generated class, and AGP's `aar-metadata.properties`. Generating
 * the class with ASM (instead of committing a prebuilt binary or running AGP in the test) lets
 * each test dial every dimension independently, including violation values no healthy toolchain
 * would emit.
 *
 * Matches a real AGP-built AAR in: entry paths, properties keys, `@kotlin.Metadata` annotation
 * descriptor and its `mv` int-array shape, class-file major version placement.
 * Diverges in: the class carries no real Kotlin metadata payload (only the `mv` and `k`
 * elements) and the AAR has no manifest or resources; the reader touches none of those.
 */
internal object AarFixture {

    fun write(
        destination: File,
        metadataVersion: IntArray = intArrayOf(2, 2, 0), // The @kotlin.Metadata mv stamp the class will carry.
        classMajorVersion: Int = 61, // Class-file major; 61 = Java 17 (release + 44).
        minCompileSdk: Int = 36,
        minAgpVersion: String = "8.1.0",
    ): File {
        val classesJar: ByteArray = ByteArrayOutputStream().also { buffer: ByteArrayOutputStream ->
            ZipOutputStream(buffer).use { jar: ZipOutputStream ->
                jar.putNextEntry(ZipEntry("fixture/Sample.class"))
                jar.write(classWithMetadata(metadataVersion = metadataVersion, classMajorVersion = classMajorVersion))
                jar.closeEntry()
            }
        }.toByteArray()

        ZipOutputStream(destination.outputStream()).use { aar: ZipOutputStream ->
            aar.putNextEntry(ZipEntry("classes.jar")) // The nested jar, exactly where AGP places it.
            aar.write(classesJar)
            aar.closeEntry()

            aar.putNextEntry(ZipEntry("META-INF/com/android/build/gradle/aar-metadata.properties"))
            aar.write(
                "minCompileSdk=$minCompileSdk\nminAndroidGradlePluginVersion=$minAgpVersion\n".toByteArray()
            )
            aar.closeEntry()
        }
        return destination
    }

    /**
     * Emits a public class `fixture.Sample` with the requested class-file major version and a
     * `@kotlin.Metadata(k = 1, mv = [...])` annotation, mirroring how kotlinc stamps the binary
     * metadata version onto every compiled class.
     */
    private fun classWithMetadata(metadataVersion: IntArray, classMajorVersion: Int): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            /* version = */ classMajorVersion,
            /* access = */ Opcodes.ACC_PUBLIC,
            /* name = */ "fixture/Sample",
            /* signature = */ null,
            /* superName = */ "java/lang/Object",
            /* interfaces = */ null,
        )
        writer.visitAnnotation(/* descriptor = */ "Lkotlin/Metadata;", /* visible = */ true).apply {
            visit(/* name = */ "k", /* value = */ 1) // k = 1 marks a regular class in real metadata.
            visit(/* name = */ "mv", /* value = */ metadataVersion) // Primitive arrays go through visit in one call.
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
