package io.github.ahmetsirim.kot

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One synthetic class inside the fixture AAR's classes.jar, every emitted fact dialed per class.
 *
 * [metadataVersion] null means a plain, non-Kotlin class (no @kotlin.Metadata at all);
 * [decoyMetadataVersion] adds a SECOND, duplicate annotation no healthy compiler emits.
 * Not a data class on purpose: IntArray fields would make its generated equals lie.
 */
internal class FixtureClass(
    val name: String = "fixture/Sample", // JVM internal name, slash-separated.
    val classMajorVersion: Int = 61, // 61 = Java 17 (release + 44).
    val metadataVersion: IntArray? = intArrayOf(2, 2, 0),
    val decoyMetadataVersion: IntArray? = null,
)

/**
 * Synthesizes a minimal AAR whose emitted consumer floors are fully controlled by the test.
 *
 * The AAR is hand-built as a zip carrying exactly the entries the reader inspects: a nested
 * `classes.jar` with ASM-generated classes, and AGP's `aar-metadata.properties`. Generating
 * the classes with ASM (instead of committing a prebuilt binary or running AGP in the test)
 * lets each test dial every dimension independently, including violation values and broken
 * shapes no healthy toolchain would emit.
 *
 * Matches a real AGP-built AAR in: entry paths, properties keys, `@kotlin.Metadata` annotation
 * descriptor and its `mv` int-array shape, class-file major version placement.
 * Diverges in: the classes carry no real Kotlin metadata payload (only the `mv` and `k`
 * elements) and the AAR has no manifest or resources; the reader touches none of those.
 */
internal object AarFixture {

    /** The one-line facade most tests need: a single healthy class plus complete aar-metadata. */
    fun write(
        destination: File,
        metadataVersion: IntArray = intArrayOf(2, 2, 0),
        decoyMetadataVersion: IntArray? = null,
        classMajorVersion: Int = 61,
        minCompileSdk: Int = 36,
        minAgpVersion: String = "8.1.0",
    ): File = writeCustom(
        destination = destination,
        classes = listOf(
            FixtureClass(
                metadataVersion = metadataVersion,
                decoyMetadataVersion = decoyMetadataVersion,
                classMajorVersion = classMajorVersion,
            )
        ),
        aarMetadataLines = listOf(
            "minCompileSdk=$minCompileSdk",
            "minAndroidGradlePluginVersion=$minAgpVersion",
        ),
    )

    /**
     * The full-control writer for edge cases: any number of classes (order = jar entry order),
     * [aarMetadataLines] null omits the properties entry entirely (a line list allows dropping
     * single keys), [includeClassesJar] false omits the nested jar itself.
     */
    fun writeCustom(
        destination: File,
        classes: List<FixtureClass>,
        aarMetadataLines: List<String>?,
        includeClassesJar: Boolean = true,
    ): File {
        ZipOutputStream(destination.outputStream()).use { aar: ZipOutputStream ->
            if (includeClassesJar) {
                aar.putNextEntry(ZipEntry("classes.jar")) // The nested jar, exactly where AGP places it.
                aar.write(classesJarBytes(classes = classes))
                aar.closeEntry()
            }

            if (aarMetadataLines != null) {
                aar.putNextEntry(ZipEntry("META-INF/com/android/build/gradle/aar-metadata.properties"))
                aar.write(aarMetadataLines.joinToString(separator = "\n", postfix = "\n").toByteArray())
                aar.closeEntry()
            }
        }
        return destination
    }

    private fun classesJarBytes(classes: List<FixtureClass>): ByteArray {
        return ByteArrayOutputStream().also { buffer: ByteArrayOutputStream ->
            ZipOutputStream(buffer).use { jar: ZipOutputStream ->
                classes.forEach { fixtureClass: FixtureClass ->
                    jar.putNextEntry(ZipEntry("${fixtureClass.name}.class"))
                    jar.write(classBytes(fixtureClass = fixtureClass))
                    jar.closeEntry()
                }
                if (classes.isEmpty()) {
                    // A zip with zero entries cannot be finished (ZipOutputStream throws); a
                    // manifest keeps the jar valid while leaving it without a single class.
                    jar.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                    jar.write("Manifest-Version: 1.0\n".toByteArray())
                    jar.closeEntry()
                }
            }
        }.toByteArray()
    }

    /**
     * Emits one public class with the requested class-file major version and, when
     * [FixtureClass.metadataVersion] is present, a `@kotlin.Metadata(k = 1, mv = [...])`
     * annotation mirroring how kotlinc stamps the binary metadata version onto every class.
     */
    private fun classBytes(fixtureClass: FixtureClass): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            /* version = */ fixtureClass.classMajorVersion,
            /* access = */ Opcodes.ACC_PUBLIC,
            /* name = */ fixtureClass.name,
            /* signature = */ null,
            /* superName = */ "java/lang/Object",
            /* interfaces = */ null,
        )
        fixtureClass.metadataVersion?.let { stamp: IntArray ->
            writer.visitAnnotation(/* descriptor = */ "Lkotlin/Metadata;", /* visible = */ true).apply {
                visit(/* name = */ "k", /* value = */ 1) // k = 1 marks a regular class in real metadata.
                visit(/* name = */ "mv", /* value = */ stamp) // Primitive arrays go through visit in one call.
                visitEnd()
            }
        }
        fixtureClass.decoyMetadataVersion?.let { decoy: IntArray ->
            // The JVM does not reject a duplicated annotation; ASM happily writes and replays both.
            writer.visitAnnotation(/* descriptor = */ "Lkotlin/Metadata;", /* visible = */ true).apply {
                visit(/* name = */ "k", /* value = */ 1)
                visit(/* name = */ "mv", /* value = */ decoy)
                visitEnd()
            }
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
