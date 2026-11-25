package io.github.ahmetsirim.kot

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The minSdk dimension: an AAR's manifest records the lowest Android API level the library runs
 * on, and a consumer app with a lower minSdk cannot merge it. The value is read out of the
 * AndroidManifest.xml the archive carries.
 */
class MinSdkFloorFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    @Test
    fun `passes when the manifest's minSdk is within the floor`() {
        AarFixture.write(destination = File(projectDir, "fixture.aar"), minSdk = 23)
        writeConsumerBuild("""kot { minSdkFloor.set(23) }""")

        val result: BuildResult = runner().build()

        result.output shouldContain "minSdk 23 <= floor 23"
    }

    @Test
    fun `fails when the manifest's minSdk exceeds the floor`() {
        AarFixture.write(destination = File(projectDir, "fixture.aar"), minSdk = 24)
        writeConsumerBuild("""kot { minSdkFloor.set(23) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "minSdk 24 exceeds the declared floor 23"
    }

    @Test
    fun `writes the minSdk to the report`() {
        AarFixture.write(destination = File(projectDir, "fixture.aar"), minSdk = 24)
        writeConsumerBuild("""kot { minSdkFloor.set(24) }""")

        runner().build()

        File(projectDir, "build/reports/kot/emitted-floors.properties").readText() shouldContain "minSdk=24"
    }

    /** A plain JAR has no manifest: with the floor declared there is nothing to measure. */
    @Test
    fun `fails hard when the floor is declared but the archive carries no manifest`() {
        PlainJarFixture.write(destination = File(projectDir, "fixture.aar"), classes = listOf(FixtureClass()))
        writeConsumerBuild("""kot { minSdkFloor.set(23) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "minSdkVersion missing from AndroidManifest.xml in fixture.aar"
    }

    /** A manifest without a uses-sdk element records no minSdk; that is missing, not zero. */
    @Test
    fun `fails hard when the floor is declared but the manifest records no minSdk`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(FixtureClass()),
            aarMetadataLines = null,
            manifest = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" />""",
        )
        writeConsumerBuild("""kot { minSdkFloor.set(23) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "minSdkVersion missing from AndroidManifest.xml in fixture.aar"
    }

    /** A preview platform is recorded by its codename, which no int floor can be held against. */
    @Test
    fun `names a minSdk that is not a number instead of calling it missing`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(FixtureClass()),
            aarMetadataLines = null,
            manifest = AarFixture.manifestWithMinSdk(minSdkVersion = "Baklava"),
        )
        writeConsumerBuild("""kot { minSdkFloor.set(23) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "minSdkVersion in AndroidManifest.xml of fixture.aar is not a number: \"Baklava\""
    }

    /** A uses-sdk element that is commented out is not the manifest's own. */
    @Test
    fun `does not read a minSdk out of a comment`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(FixtureClass()),
            aarMetadataLines = null,
            manifest = """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <!-- <uses-sdk android:minSdkVersion="19" /> -->
                    <uses-sdk android:minSdkVersion="26" />
                </manifest>
            """.trimIndent(),
        )
        writeConsumerBuild("""kot { minSdkFloor.set(23) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "minSdk 26 exceeds the declared floor 23"
    }

    /** With no floor declared, the first-run block offers the measured minSdk with the others. */
    @Test
    fun `offers the minSdk in the block shown when no floor is declared`() {
        AarFixture.write(destination = File(projectDir, "fixture.aar"), minSdk = 24)
        writeConsumerBuild("")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "minSdkFloor.set(24)"
    }

    /** An undeclared minSdk floor is skipped like any other, whatever the manifest holds. */
    @Test
    fun `skips the minSdk when its floor is not declared`() {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(FixtureClass()),
            aarMetadataLines = null,
            manifest = AarFixture.manifestWithMinSdk(minSdkVersion = "Baklava"),
        )
        writeConsumerBuild("""kot { jvmTargetFloor.set(17) }""")

        val result: BuildResult = runner().build()

        result.output shouldContain
            "skipped undeclared floors: kotlinMetadataFloor, compileSdkFloor, minSdkFloor, agpFloor"
        result.output.contains("is not a number") shouldBe false
    }

    /** A codename cannot become an int floor, so the first-run block leaves it out. */
    @Test
    fun `leaves a minSdk that is not a number out of the block shown when no floor is declared`() {
        writeAarWithManifest(manifest = AarFixture.manifestWithMinSdk(minSdkVersion = "Baklava"))
        writeConsumerBuild("")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "jvmTargetFloor.set(17)"
        result.output.contains("minSdkFloor.set") shouldBe false
    }

    /** The element as a hand-edited manifest may spell it: spread over lines, single-quoted. */
    @Test
    fun `reads a minSdk written over several lines with single quotes`() {
        writeAarWithManifest(
            manifest = """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <uses-sdk
                        android:targetSdkVersion='36'
                        android:minSdkVersion = '26' >
                    </uses-sdk>
                </manifest>
            """.trimIndent(),
        )
        writeConsumerBuild("""kot { minSdkFloor.set(23) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "minSdk 26 exceeds the declared floor 23"
    }

    /** An element whose name merely starts like uses-sdk is another element. */
    @Test
    fun `does not take another element for uses-sdk`() {
        writeAarWithManifest(
            manifest = """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <uses-sdk-library android:minSdkVersion="1" />
                    <uses-sdk android:minSdkVersion="26" />
                </manifest>
            """.trimIndent(),
        )
        writeConsumerBuild("""kot { minSdkFloor.set(23) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "minSdk 26 exceeds the declared floor 23"
    }

    /** Text inside a CDATA section is character data, not markup. */
    @Test
    fun `does not read a minSdk out of a CDATA section`() {
        writeAarWithManifest(
            manifest = """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <meta-data><![CDATA[ <uses-sdk android:minSdkVersion="1" /> ]]></meta-data>
                    <uses-sdk android:minSdkVersion="26" />
                </manifest>
            """.trimIndent(),
        )
        writeConsumerBuild("""kot { minSdkFloor.set(23) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "minSdk 26 exceeds the declared floor 23"
    }

    /** A value whose quotes do not match is not a value; half of it must not pass for one. */
    @Test
    fun `does not read a minSdk whose quotes do not match`() {
        writeAarWithManifest(
            manifest = """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <uses-sdk android:minSdkVersion="2'9" />
                </manifest>
            """.trimIndent(),
        )
        writeConsumerBuild("""kot { minSdkFloor.set(23) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "minSdkVersion in AndroidManifest.xml of fixture.aar is not a number: \"2'9\""
    }

    /**
     * A manifest entry whose compressed bytes are damaged cannot be read at all. The failure
     * names the entry and the archive; the zip layer's own wording names neither.
     */
    @Test
    fun `names a manifest entry that cannot be read`() {
        val archive = File(projectDir, "fixture.aar")
        writeAarWithManifest(manifest = AarFixture.manifestWithMinSdk(minSdkVersion = "26").repeat(40))
        damageEntryData(archive = archive, entryName = "AndroidManifest.xml")
        writeConsumerBuild("""kot { minSdkFloor.set(23) }""")

        val result: BuildResult = runner().buildAndFail()

        result.output shouldContain "kot: AndroidManifest.xml in fixture.aar cannot be read"
    }

    private fun writeAarWithManifest(manifest: String) {
        AarFixture.writeCustom(
            destination = File(projectDir, "fixture.aar"),
            classes = listOf(FixtureClass()),
            aarMetadataLines = null,
            manifest = manifest,
        )
    }

    /** Overwrites the start of [entryName]'s compressed data, leaving the zip structure intact. */
    private fun damageEntryData(archive: File, entryName: String) {
        val bytes: ByteArray = archive.readBytes()
        val nameBytes: ByteArray = entryName.toByteArray()
        // The first occurrence of the name is in the entry's local header, right before its data.
        val nameStart: Int = (0..bytes.size - nameBytes.size).first { start: Int ->
            nameBytes.indices.all { offset: Int -> bytes[start + offset] == nameBytes[offset] }
        }
        val dataStart: Int = nameStart + nameBytes.size
        (dataStart until dataStart + DAMAGED_BYTE_COUNT).forEach { index: Int -> bytes[index] = 0xFF.toByte() }
        archive.writeBytes(bytes)
    }

    private fun runner(): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("verifyConsumerFloor", "--configuration-cache")
        .withPluginClasspath()

    private fun writeConsumerBuild(kotBlock: String) {
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "consumer"""")
        File(projectDir, "build.gradle.kts").writeText(
            """
            import io.github.ahmetsirim.kot.VerifyConsumerFloorTask

            plugins {
                id("io.github.ahmetsirim.kot")
            }

            $kotBlock

            tasks.named<VerifyConsumerFloorTask>("verifyConsumerFloor") {
                artifact.set(file("fixture.aar"))
            }
            """.trimIndent()
        )
    }

    private companion object {
        private const val DAMAGED_BYTE_COUNT = 16
    }
}
