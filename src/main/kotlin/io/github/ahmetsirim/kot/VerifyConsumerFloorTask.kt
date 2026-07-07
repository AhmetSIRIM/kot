package io.github.ahmetsirim.kot

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Producer-side gate over a built AAR: reads the consumer floors the artifact actually emits
 * and fails the build when any of them demands more than the floors declared in the kot { } block.
 */
abstract class VerifyConsumerFloorTask : DefaultTask() {

    // Declared inputs (every abstract property below: the artifact through @InputFile because a
    // file's CONTENT is the input, the floors through @Input because plain values are the input;
    // "input" = what the task consumes to produce its verdict). Gradle reads these annotations
    // to drive three separate machineries:
    //  - Up-to-date check: before running a task, Gradle compares every declared input with its
    //    value from the previous run; when nothing changed the task is skipped and the build
    //    output prints UP-TO-DATE next to it.
    //  - Build cache: declared inputs are hashed into a cache key, so a run with the same inputs
    //    can pull its result from the cache (local or shared with CI) instead of executing.
    //  - Configuration cache: declared inputs are what Gradle serializes to disk, so a later
    //    build can skip the whole configuration phase and still run this task correctly.
    // The flip side: state read inside the action but not declared here (a path from a system
    // property, say) is invisible to all three machineries, so Gradle may skip, cache or replay
    // the task as if that hidden state never changed. That is the classic "task did not rerun" bug.

    // The artifact under verification; wired by hand for now, by the AGP layer per variant later.
    // RegularFileProperty is the file-flavored Property: the same lazy promise, resolving to a file.
    // PathSensitivity declares how much of the file's PATH takes part in "did this input change",
    // next to its content:
    //  - ABSOLUTE: the full path counts; the same AAR under another checkout root is a change
    //    (kills cache sharing across machines, rarely what anyone wants).
    //  - RELATIVE: the path relative to the input's root counts; libs/a/x.aar -> libs/b/x.aar
    //    is a change even with identical bytes.
    //  - NAME_ONLY: only the file name counts; renaming x.aar to y.aar is a change, moving it
    //    into another directory is not.
    //  - NONE: only the bytes count; the same AAR moved elsewhere or renamed is still unchanged
    //    and the task stays up-to-date.
    // NONE fits here because the verdict depends purely on what is inside the archive.
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifact: RegularFileProperty

    // Every floor below is @Optional: without it Gradle itself fails the build when an @Input
    // carries no value, before the action even starts; with it absence is allowed through to the
    // action, where it means "dimension not declared" and is reported as skipped. What each floor
    // gates, and how, is documented on its check function further down.

    @get:Input
    @get:Optional
    abstract val kotlinMetadataFloor: Property<String>

    @get:Input
    @get:Optional
    abstract val compileSdkFloor: Property<Int>

    @get:Input
    @get:Optional
    abstract val agpFloor: Property<String>

    @get:Input
    @get:Optional
    abstract val jvmTargetFloor: Property<Int>

    /**
     * Execution-time entry point ([TaskAction] marks the method Gradle invokes when the task runs).
     *
     * Background: a build passes through three phases. Initialization decides which projects take
     * part; configuration runs every build script and plugin apply() to assemble the task graph
     * ([KotPlugin.apply] runs there); execution runs the actions of the tasks selected for this
     * invocation, and this method is ours. Everything before this point (property wiring, the
     * convention links) only connected promises; the get()/orNull calls below are the first
     * moment values are actually resolved.
     *
     * Orchestration only: guard, read the artifact once, check each dimension, report and gate.
     * Every check function follows the same three-exit template: an undeclared floor returns
     * [FloorCheckResult.Skipped]; a declared floor whose emitted counterpart cannot be read out
     * of the artifact fails hard, because a gate that cannot measure is broken, not passing;
     * a successful measurement compares emitted against declared and returns
     * [FloorCheckResult.Passed] or [FloorCheckResult.Violated].
     */
    @TaskAction
    fun verify() {
        ensureAtLeastOneFloorIsDeclared()

        val aarFile: File = artifact.get().asFile
        val emittedFloors: EmittedFloors = EmittedFloorReader.read(aarFile = aarFile)

        val checkResults: List<FloorCheckResult> = listOf(
            checkKotlinMetadataFloor(emittedFloors = emittedFloors, aarFileName = aarFile.name),
            checkCompileSdkFloor(emittedFloors = emittedFloors, aarFileName = aarFile.name),
            checkAgpFloor(emittedFloors = emittedFloors, aarFileName = aarFile.name),
            checkJvmTargetFloor(emittedFloors = emittedFloors, aarFileName = aarFile.name),
        )

        reportAndGate(checkResults = checkResults, aarFileName = aarFile.name)
    }

    /**
     * Fails fast when the kot { } block declares nothing: a gate with no floors would run green
     * while guarding nothing, which is worse than having no gate at all. Presence is asked via
     * [Property.isPresent], which is true whether the value was set directly on the task or
     * flowed in from the kot { } block through the convention link.
     */
    private fun ensureAtLeastOneFloorIsDeclared() {
        val anyFloorIsDeclared: Boolean = listOf(kotlinMetadataFloor, compileSdkFloor, agpFloor, jvmTargetFloor)
            .any { floor: Property<*> -> floor.isPresent }

        if (!anyFloorIsDeclared) {
            throw GradleException("kot: no consumer floor declared; declare at least one floor in the kot { } block.")
        }
    }

    /**
     * The headline dimension: which Kotlin compilers can read this AAR.
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
    private fun checkKotlinMetadataFloor(emittedFloors: EmittedFloors, aarFileName: String): FloorCheckResult {
        val declaredFloor: String = kotlinMetadataFloor.orNull
            ?: return FloorCheckResult.Skipped(floorName = "kotlinMetadataFloor")
        val emittedVersionParts: List<Int> = emittedFloors.kotlinMetadataVersion
            ?: throw GradleException("kot: no class carrying kotlin.Metadata found in $aarFileName")

        // Only major.minor participate; the patch component carries no compatibility meaning here.
        val emittedMajorMinor: List<Int> = emittedVersionParts
            .take(n = 2)
        val emittedVersion: String = emittedMajorMinor
            .joinToString(separator = ".")
        val declaredFloorParts: List<Int> = declaredFloor
            .split(".")
            .map { part: String -> part.toIntOrNull() ?: 0 }

        return if (compareVersionParts(left = emittedMajorMinor, right = declaredFloorParts) > 0) {
            FloorCheckResult.Violated(line = "emitted Kotlin metadata version $emittedVersion exceeds the declared floor $declaredFloor")
        } else {
            FloorCheckResult.Passed(line = "Kotlin metadata $emittedVersion <= floor $declaredFloor")
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
    private fun checkCompileSdkFloor(emittedFloors: EmittedFloors, aarFileName: String): FloorCheckResult {
        val declaredFloor: Int = compileSdkFloor.orNull
            ?: return FloorCheckResult.Skipped(floorName = "compileSdkFloor")
        val emittedMinCompileSdk: Int = emittedFloors.minCompileSdk
            ?: throw GradleException("kot: minCompileSdk missing from aar-metadata.properties in $aarFileName")

        return if (emittedMinCompileSdk > declaredFloor) {
            FloorCheckResult.Violated(line = "minCompileSdk $emittedMinCompileSdk exceeds the declared floor $declaredFloor")
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
    private fun checkAgpFloor(emittedFloors: EmittedFloors, aarFileName: String): FloorCheckResult {
        val declaredFloor: String = agpFloor.orNull
            ?: return FloorCheckResult.Skipped(floorName = "agpFloor")
        val emittedMinAgpVersion: String = emittedFloors.minAgpVersion
            ?: throw GradleException("kot: minAndroidGradlePluginVersion missing from aar-metadata.properties in $aarFileName")

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
     * Asserts no class in the AAR targets a newer JVM than the declared floor.
     *
     * Background: every .class file carries a "major version" (bytes 6-7 of its header), the
     * bytecode format the class targets; a JVM refuses classes newer than itself with
     * UnsupportedClassVersionError. The scale is the Java release plus 44 (Java 11 -> 55,
     * Java 17 -> 61), so the declared Java release converts onto that scale (+44) before
     * comparing against the highest major found across the artifact's classes.
     */
    private fun checkJvmTargetFloor(emittedFloors: EmittedFloors, aarFileName: String): FloorCheckResult {
        val declaredFloor: Int = jvmTargetFloor.orNull
            ?: return FloorCheckResult.Skipped(floorName = "jvmTargetFloor")
        val emittedMaxClassMajor: Int = emittedFloors.maxClassMajorVersion
            ?: throw GradleException("kot: no .class entries found in classes.jar of $aarFileName")

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
     * Turns the per-dimension results into the task's outcome. [filterIsInstance] splits the
     * mixed result list into its three cases, and each group feeds one section of the report:
     *
     *  - skipped dimensions are named out loud first; a silent skip would read as coverage,
     *  - any violation fails the build via [GradleException], with every violated dimension
     *    listed at once instead of stopping at the first, so one red run shows the full damage,
     *  - the passed summary prints only on a clean run, since throwing above ends the method.
     */
    private fun reportAndGate(checkResults: List<FloorCheckResult>, aarFileName: String) {
        val skippedFloorNames: List<String> = checkResults
            .filterIsInstance<FloorCheckResult.Skipped>()
            .map { skipped: FloorCheckResult.Skipped -> skipped.floorName }
        if (skippedFloorNames.isNotEmpty()) {
            // lifecycle is Gradle's default console verbosity: visible without --info or --debug.
            logger.lifecycle("kot: skipped undeclared floors: ${skippedFloorNames.joinToString(separator = ", ")}")
        }

        val violationLines: List<String> = checkResults
            .filterIsInstance<FloorCheckResult.Violated>()
            .map { violated: FloorCheckResult.Violated -> violated.line }
        if (violationLines.isNotEmpty()) {
            throw GradleException(
                "kot: consumer floor regression in $aarFileName " +
                        "(the artifact demands more from consumers than the declared floor):\n" +
                        violationLines.joinToString(separator = "\n") { line: String -> "  - $line" } +
                        "\nRaise the floor deliberately in the kot { } block, or revert the change that widened it."
            )
        }

        val passedLines: List<String> = checkResults
            .filterIsInstance<FloorCheckResult.Passed>()
            .map { passed: FloorCheckResult.Passed -> passed.line }

        logger.lifecycle(
            "kot: consumer floor check passed for $aarFileName:\n" +
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
