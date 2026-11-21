package io.github.ahmetsirim.kot

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Producer-side gate over a built archive (an AAR or a plain JAR): reads the consumer floors the
 * artifact actually emits and fails the build when any of them demands more than the floors
 * declared in the kot { } block.
 */
// Cacheable: the same artifact and floors produce the same verdict and the same report.
@CacheableTask
abstract class VerifyConsumerFloorTask : DefaultTask() {

    // PathSensitivity.NONE: the verdict depends only on the bytes of the archive, so a moved or
    // renamed archive is not a change.
    //
    // @Optional does not make the artifact optional for the gate. It only moves the "no value"
    // failure from Gradle's input validation into the action, which can say why the value is
    // missing (see resolveArtifact).

    /**
     * The archive under verification. The AGP wiring supplies the release AAR of a library
     * with a single release variant, and the Java plugin wiring supplies the archive of the
     * `jar` task; a value set on the task wins over either. With no value the task fails.
     */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifact: RegularFileProperty

    // Every floor below is @Optional: absence reaches the action, where it means "dimension not
    // declared" and is reported as skipped.

    /** Task-level counterpart of [KotExtension.kotlinMetadataFloor]; a value set here wins over the `kot { }` block. */
    @get:Input
    @get:Optional
    abstract val kotlinMetadataFloor: Property<String>

    /** Task-level counterpart of [KotExtension.compileSdkFloor]; a value set here wins over the `kot { }` block. */
    @get:Input
    @get:Optional
    abstract val compileSdkFloor: Property<Int>

    /** Task-level counterpart of [KotExtension.agpFloor]; a value set here wins over the `kot { }` block. */
    @get:Input
    @get:Optional
    abstract val agpFloor: Property<String>

    /** Task-level counterpart of [KotExtension.jvmTargetFloor]; a value set here wins over the `kot { }` block. */
    @get:Input
    @get:Optional
    abstract val jvmTargetFloor: Property<Int>

    /**
     * The kotlin-stdlib version consumers receive with the library, held against
     * [kotlinMetadataFloor]: a consumer's compiler reads the stdlib on its classpath by the same
     * rule as the library's own classes. The wirings supply the version the project's runtime
     * classpath resolves to, whatever archive the task reads; a value set here wins. With no
     * value (neither plugin applied, or several release variants) the stdlib is not checked,
     * and the run says so. Not a floor: nothing is declared for it.
     */
    @get:Input
    @get:Optional
    abstract val kotlinStdlibVersion: Property<String>

    /**
     * Names of the release variants the AGP wiring saw, one entry per variant. More than one
     * entry means a flavored library: the wiring then supplies no [artifact], and the task
     * names these variants when it fails for lack of one.
     */
    @get:Input
    @get:Optional
    abstract val wiredVariantNames: ListProperty<String>

    /**
     * The measured emitted floors as a properties file, written on every run that reads the
     * artifact, pass or fail: the producer always sees the consumer bounds the artifact
     * carries, and downstream tasks can consume the file as an input.
     */
    @get:OutputFile
    abstract val emittedFloorsReport: RegularFileProperty

    /**
     * Guards, reads the artifact once, checks each dimension, then reports and gates.
     *
     * Every floor's check function has the same three exits: an undeclared floor returns
     * [FloorCheckResult.Skipped]; a declared floor whose emitted counterpart cannot be read out
     * of the artifact fails hard, because a gate that cannot measure is broken, not passing; a
     * successful measurement returns [FloorCheckResult.Passed] or [FloorCheckResult.Violated].
     * The kotlin-stdlib check is the exception: it has no floor of its own and adds a result
     * only when a version is known.
     */
    @TaskAction
    fun verify() {
        val archiveFile: File = resolveArtifact()
        ensureAtLeastOneFloorIsDeclared()

        val emittedFloors: EmittedFloors = EmittedFloorReader.read(archiveFile = archiveFile)
        writeEmittedFloorsReport(emittedFloors = emittedFloors)

        val checkResults: List<FloorCheckResult> = listOfNotNull(
            checkKotlinMetadataFloor(emittedFloors = emittedFloors, archiveFileName = archiveFile.name),
            checkKotlinStdlibFloor(),
            checkCompileSdkFloor(emittedFloors = emittedFloors, archiveFileName = archiveFile.name),
            checkAgpFloor(emittedFloors = emittedFloors, archiveFileName = archiveFile.name),
            checkJvmTargetFloor(emittedFloors = emittedFloors, archiveFileName = archiveFile.name),
        )

        reportAndGate(checkResults = checkResults, archiveFileName = archiveFile.name)
    }

    /**
     * Persists the measured emitted floors as a properties file, written before the gate decides
     * so it exists on failing runs too: the measurement already happened, and a red build is
     * exactly when the numbers are wanted. Written by hand rather than Properties.store, which
     * stamps a timestamp comment and would make the output non-deterministic for the build cache.
     * Absent facts are omitted; the file mirrors [EmittedFloors]' nullability.
     */
    private fun writeEmittedFloorsReport(emittedFloors: EmittedFloors) {
        val reportFile: File = emittedFloorsReport.get().asFile
        reportFile.parentFile.mkdirs()

        val lines: List<String> = listOfNotNull(
            emittedFloors.kotlinMetadataVersion?.let { parts: List<Int> ->
                "kotlinMetadataVersion=${parts.joinToString(separator = ".")}"
            },
            emittedFloors.minCompileSdk?.let { value: String -> "minCompileSdk=$value" },
            emittedFloors.minAgpVersion?.let { value: String -> "minAndroidGradlePluginVersion=$value" },
            emittedFloors.maxClassMajorVersion?.let { value: Int -> "maxClassMajorVersion=$value" },
            kotlinStdlibVersion.orNull?.let { value: String -> "kotlinStdlibVersion=$value" },
        )
        reportFile.writeText(lines.joinToString(separator = "\n", postfix = "\n"))

        logger.lifecycle("kot: emitted floors written to $reportFile")
    }

    /**
     * Returns the archive to verify, or fails saying why there is none.
     *
     * A flavored library has several release variants, and the wiring supplies none of their
     * AARs: picking one would let a green run cover a single flavor while the others ship
     * unverified, and a partial truth reads as coverage. The author names the AAR on the task
     * instead; proper per-variant modeling is a roadmap item. A value set on the task is used
     * as it is, however many variants exist, and the run says that the other variants were
     * not verified: a green run over one flavor must not read as coverage of all of them.
     */
    private fun resolveArtifact(): File {
        val wired: List<String> = wiredVariantNames.getOrElse(emptyList())
        val wiredNames: String = wired.joinToString(separator = ", ")

        artifact.orNull?.let { archive: RegularFile ->
            if (wired.size > 1) {
                logger.lifecycle(
                    "kot: ${wired.size} release variants exist ($wiredNames); " +
                        "only the artifact set on the task is verified."
                )
            }
            return archive.asFile
        }

        throw GradleException(
            if (wired.size > 1) {
                "kot: ${wired.size} release variants found ($wiredNames); " +
                    "the wiring does not pick one of their AARs, because verifying a single flavor would " +
                    "silently skip the rest. Flavored libraries are not modeled yet. " +
                    "Set the task's artifact to the AAR to verify."
            } else {
                "kot: no artifact to verify: neither the Android library wiring nor the Java plugin wiring " +
                    "supplied one (a disabled jar task supplies none), and none is set on the task."
            }
        )
    }

    /**
     * Fails fast when no floor is declared: a gate with no floors would run green while guarding
     * nothing, which is worse than having no gate at all.
     */
    private fun ensureAtLeastOneFloorIsDeclared() {
        val anyFloorIsDeclared: Boolean = listOf(kotlinMetadataFloor, compileSdkFloor, agpFloor, jvmTargetFloor)
            .any { floor: Property<*> -> floor.isPresent }

        if (!anyFloorIsDeclared) {
            throw GradleException("kot: no consumer floor declared; declare at least one floor in the kot { } block.")
        }
    }

    /**
     * The headline dimension: which Kotlin compilers can read this archive.
     *
     * Background: kotlinc stamps every class it compiles with a @kotlin.Metadata annotation whose
     * mv field holds the binary metadata version as ints, for example [2, 2, 0]. A consumer's
     * Kotlin compiler refuses classes stamped newer than it understands ("Module was compiled
     * with an incompatible version of Kotlin..."), so the stamped version IS the artifact's real
     * Kotlin floor. The stamping compiler belongs to the producer's build (AGP's built-in
     * Kotlin), which is why the emitted version can rise with no change in the module's own code.
     *
     * Comparison: only major.minor of the emitted stamp against the declared "major.minor"
     * string, both as int lists, because comparing versions as text would order "2.10" below "2.9".
     */
    private fun checkKotlinMetadataFloor(emittedFloors: EmittedFloors, archiveFileName: String): FloorCheckResult {
        val declaredFloor: String = kotlinMetadataFloor.orNull
            ?: return FloorCheckResult.Skipped(floorName = "kotlinMetadataFloor")
        val emittedVersionParts: List<Int> = emittedFloors.kotlinMetadataVersion
            ?: throw GradleException("kot: no class carrying kotlin.Metadata found in $archiveFileName")

        // Only major.minor participate; the patch component carries no compatibility meaning here.
        val emittedMajorMinor: List<Int> = emittedVersionParts
            .take(n = 2)
        val emittedVersion: String = emittedMajorMinor
            .joinToString(separator = ".")
        val declaredFloorParts: List<Int> = parseKotlinFloor(declaredFloor = declaredFloor)

        return if (compareVersionParts(left = emittedMajorMinor, right = declaredFloorParts) > 0) {
            FloorCheckResult.Violated(
                line = "emitted Kotlin metadata version $emittedVersion exceeds the declared floor $declaredFloor"
            )
        } else {
            FloorCheckResult.Passed(line = "Kotlin metadata $emittedVersion <= floor $declaredFloor")
        }
    }

    /**
     * Holds the kotlin-stdlib consumers receive against the same Kotlin floor.
     *
     * Background: a library's classes are not all a consumer's compiler reads. Dependency
     * resolution hands the consumer the newest kotlin-stdlib anything in the graph asks for, and
     * the compiler refuses a stdlib more than one minor version ahead of itself, exactly as it
     * refuses the library's classes. A library that keeps its own metadata stamp low but
     * depends on a newer stdlib raises the floor all the same.
     *
     * Returns null when the Kotlin floor is not declared (the metadata check already reports the
     * skip) or when no stdlib version is known, which the log line states.
     */
    private fun checkKotlinStdlibFloor(): FloorCheckResult? {
        val declaredFloor: String? = kotlinMetadataFloor.orNull
        val stdlibVersion: String? = kotlinStdlibVersion.orNull

        return when {
            declaredFloor == null -> null

            stdlibVersion == null -> {
                logger.lifecycle(
                    "kot: no kotlin-stdlib version to check: the runtime classpath holds none, or none is " +
                        "wired (no Android or Java plugin, or several release variants) and none is set on the task"
                )
                null
            }

            else -> {
                // Only major.minor participate, as for the metadata stamp; both have to be there.
                val stdlibMajorMinor: List<Int> = stdlibVersion.split(".").take(n = 2)
                    .mapNotNull { part: String -> part.toIntOrNull() }
                    .takeIf { parts: List<Int> -> parts.size == 2 }
                    ?: throw GradleException(
                        "kot: kotlin-stdlib version \"$stdlibVersion\" does not start with major.minor."
                    )
                val declaredFloorParts: List<Int> = parseKotlinFloor(declaredFloor = declaredFloor)

                if (compareVersionParts(left = stdlibMajorMinor, right = declaredFloorParts) > 0) {
                    FloorCheckResult.Violated(
                        line = "kotlin-stdlib $stdlibVersion exceeds the declared Kotlin floor $declaredFloor " +
                            "(consumers receive it with the library)"
                    )
                } else {
                    FloorCheckResult.Passed(line = "kotlin-stdlib $stdlibVersion <= Kotlin floor $declaredFloor")
                }
            }
        }
    }

    /**
     * Asserts the AAR does not demand a higher compileSdk from consumers than the declared floor.
     *
     * Background: AGP writes a small properties file (aar-metadata.properties) into every AAR;
     * the consumer's AGP reads it at build time and rejects the library early when the app's
     * compileSdk is below the recorded minCompileSdk. The emitted value therefore dictates what
     * every consumer must have. Both sides are single API-level ints, so the comparison is plain.
     */
    private fun checkCompileSdkFloor(emittedFloors: EmittedFloors, archiveFileName: String): FloorCheckResult {
        val declaredFloor: Int = compileSdkFloor.orNull
            ?: return FloorCheckResult.Skipped(floorName = "compileSdkFloor")
        val recordedMinCompileSdk: String = emittedFloors.minCompileSdk
            ?: throw GradleException("kot: minCompileSdk missing from aar-metadata.properties in $archiveFileName")
        // Judged here, not in the reader: a value this check cannot read must not fail a build
        // that never declared the dimension.
        val emittedMinCompileSdk: Int = recordedMinCompileSdk.toIntOrNull()
            ?: throw GradleException(
                "kot: minCompileSdk in aar-metadata.properties of $archiveFileName " +
                    "is not a number: \"$recordedMinCompileSdk\""
            )

        return if (emittedMinCompileSdk > declaredFloor) {
            FloorCheckResult.Violated(
                line = "minCompileSdk $emittedMinCompileSdk exceeds the declared floor $declaredFloor"
            )
        } else {
            FloorCheckResult.Passed(line = "minCompileSdk $emittedMinCompileSdk <= floor $declaredFloor")
        }
    }

    /**
     * Asserts the AAR still records exactly the declared AGP floor.
     *
     * Background: the same aar-metadata.properties also records minAndroidGradlePluginVersion,
     * which AGP copies from this module's own aarMetadata.minAgpVersion declaration; consumers
     * building with an older AGP are rejected at build time.
     *
     * Equality, not <=: since the emitted value is our own declaration read back, any difference
     * means that declaration changed or was removed. A removed declaration degrades to AGP's
     * 1.0.0 default, which a <= check would wave through silently; equality turns both drifts red.
     */
    private fun checkAgpFloor(emittedFloors: EmittedFloors, archiveFileName: String): FloorCheckResult {
        val declaredFloor: String = agpFloor.orNull
            ?: return FloorCheckResult.Skipped(floorName = "agpFloor")
        val emittedMinAgpVersion: String = emittedFloors.minAgpVersion
            ?: throw GradleException(
                "kot: minAndroidGradlePluginVersion missing from aar-metadata.properties in $archiveFileName"
            )

        return if (emittedMinAgpVersion != declaredFloor) {
            FloorCheckResult.Violated(
                line = "minAndroidGradlePluginVersion $emittedMinAgpVersion does not equal the declared floor " +
                        "$declaredFloor (the aarMetadata.minAgpVersion declaration changed or was removed)"
            )
        } else {
            FloorCheckResult.Passed(line = "minAndroidGradlePluginVersion = $declaredFloor")
        }
    }

    /**
     * Asserts no class in the archive targets a newer JVM than the declared floor.
     *
     * Background: every .class file carries a "major version" (bytes 6-7 of its header), the
     * bytecode format the class targets; a JVM refuses classes newer than itself with
     * UnsupportedClassVersionError. The scale is the Java release plus 44 (Java 11 -> 55,
     * Java 17 -> 61), so the declared Java release converts onto that scale (+44) before
     * comparing against the highest major found across the artifact's classes.
     */
    private fun checkJvmTargetFloor(emittedFloors: EmittedFloors, archiveFileName: String): FloorCheckResult {
        val declaredFloor: Int = jvmTargetFloor.orNull
            ?: return FloorCheckResult.Skipped(floorName = "jvmTargetFloor")
        val emittedMaxClassMajor: Int = emittedFloors.maxClassMajorVersion
            ?: throw GradleException(
                "kot: no classes found in $archiveFileName that set a bytecode floor. A module descriptor and " +
                    "versioned classes do not, and the jars under libs/ are read only beside a classes.jar."
            )

        val declaredFloorAsClassMajor: Int = declaredFloor + JAVA_RELEASE_TO_CLASS_MAJOR_OFFSET

        return if (emittedMaxClassMajor > declaredFloorAsClassMajor) {
            FloorCheckResult.Violated(
                line = "bytecode major version $emittedMaxClassMajor exceeds the JVM $declaredFloor floor " +
                    "(class-file major $declaredFloorAsClassMajor)"
            )
        } else {
            FloorCheckResult.Passed(
                line = "bytecode major $emittedMaxClassMajor <= JVM $declaredFloor floor " +
                    "(class-file major $declaredFloorAsClassMajor)"
            )
        }
    }

    /**
     * Turns the per-dimension results into the task's outcome:
     *
     *  - skipped dimensions are named out loud first; a silent skip would read as coverage,
     *  - any violation fails the build, with every violated dimension listed at once instead of
     *    stopping at the first, so one red run shows the full damage,
     *  - the passed summary prints only on a clean run.
     */
    private fun reportAndGate(checkResults: List<FloorCheckResult>, archiveFileName: String) {
        val skippedFloorNames: List<String> = checkResults
            .filterIsInstance<FloorCheckResult.Skipped>()
            .map { skipped: FloorCheckResult.Skipped -> skipped.floorName }
        if (skippedFloorNames.isNotEmpty()) {
            logger.lifecycle("kot: skipped undeclared floors: ${skippedFloorNames.joinToString(separator = ", ")}")
        }

        val violationLines: List<String> = checkResults
            .filterIsInstance<FloorCheckResult.Violated>()
            .map { violated: FloorCheckResult.Violated -> violated.line }
        if (violationLines.isNotEmpty()) {
            throw GradleException(
                "kot: consumer floor regression in $archiveFileName " +
                        "(the artifact demands more from consumers than the declared floor):\n" +
                        violationLines.joinToString(separator = "\n") { line: String -> "  - $line" } +
                        "\nRaise the floor deliberately in the kot { } block, or revert the change that widened it."
            )
        }

        val passedLines: List<String> = checkResults
            .filterIsInstance<FloorCheckResult.Passed>()
            .map { passed: FloorCheckResult.Passed -> passed.line }

        logger.lifecycle(
            "kot: consumer floor check passed for $archiveFileName:\n" +
                    passedLines.joinToString(separator = "\n") { line: String -> "  - $line" }
        )
    }

    private companion object {
        // A class file's major version sits on this fixed offset from the Java release (Java 17 -> 61).
        private const val JAVA_RELEASE_TO_CLASS_MAJOR_OFFSET = 44
    }

    /** One dimension's verdict; the three cases drive the three sections of [reportAndGate]. */
    private sealed interface FloorCheckResult {

        /** The declared floor holds; [line] becomes one bullet of the passed summary. */
        data class Passed(val line: String) : FloorCheckResult

        /** The artifact demands more than the declared floor; [line] becomes one bullet of the failure message. */
        data class Violated(val line: String) : FloorCheckResult

        /** The dimension is not declared in the kot { } block; [floorName] is listed in the skipped log line. */
        data class Skipped(val floorName: String) : FloorCheckResult
    }

}
