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

abstract class VerifyConsumerFloorTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifact: RegularFileProperty

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

    @TaskAction
    fun verify() {
        val anyFloorDeclared = listOf(kotlinMetadataFloor, compileSdkFloor, agpFloor, jvmTargetFloor)
            .any { it.isPresent }
        if (!anyFloorDeclared) {
            throw GradleException(
                "kot: no consumer floor declared; declare at least one floor in the kot { } block."
            )
        }

        val aarFile = artifact.get().asFile
        val emitted = EmittedFloorReader.read(aarFile)

        val violations = mutableListOf<String>()
        val passed = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        if (kotlinMetadataFloor.isPresent) {
            val floor = kotlinMetadataFloor.get()
            val emittedParts = emitted.kotlinMetadataVersion
                ?: throw GradleException("kot: no class carrying kotlin.Metadata found in ${aarFile.name}")
            val emittedVersion = emittedParts.take(2).joinToString(".")
            val floorParts = floor.split(".").map { it.toIntOrNull() ?: 0 }
            if (compareVersionParts(emittedParts.take(2), floorParts) > 0) {
                violations += "emitted Kotlin metadata version $emittedVersion exceeds the declared floor $floor"
            } else {
                passed += "Kotlin metadata $emittedVersion <= floor $floor"
            }
        } else {
            skipped += "kotlinMetadataFloor"
        }

        if (compileSdkFloor.isPresent) {
            val floor = compileSdkFloor.get()
            val emittedMinCompileSdk = emitted.minCompileSdk
                ?: throw GradleException("kot: minCompileSdk missing from aar-metadata.properties in ${aarFile.name}")
            if (emittedMinCompileSdk > floor) {
                violations += "minCompileSdk $emittedMinCompileSdk exceeds the declared floor $floor"
            } else {
                passed += "minCompileSdk $emittedMinCompileSdk <= floor $floor"
            }
        } else {
            skipped += "compileSdkFloor"
        }

        if (agpFloor.isPresent) {
            val floor = agpFloor.get()
            val emittedMinAgpVersion = emitted.minAgpVersion
                ?: throw GradleException("kot: minAndroidGradlePluginVersion missing from aar-metadata.properties in ${aarFile.name}")
            if (emittedMinAgpVersion != floor) {
                violations += "minAndroidGradlePluginVersion $emittedMinAgpVersion does not equal the declared floor $floor (the aarMetadata.minAgpVersion declaration changed or was removed)"
            } else {
                passed += "minAndroidGradlePluginVersion = $floor"
            }
        } else {
            skipped += "agpFloor"
        }

        if (jvmTargetFloor.isPresent) {
            val floor = jvmTargetFloor.get()
            val floorClassMajor = floor + 44
            val emittedClassMajor = emitted.maxClassMajorVersion
                ?: throw GradleException("kot: no .class entries found in classes.jar of ${aarFile.name}")
            if (emittedClassMajor > floorClassMajor) {
                violations += "bytecode major version $emittedClassMajor exceeds the JVM $floor floor (class-file major $floorClassMajor)"
            } else {
                passed += "bytecode major $emittedClassMajor <= JVM $floor floor (class-file major $floorClassMajor)"
            }
        } else {
            skipped += "jvmTargetFloor"
        }

        if (skipped.isNotEmpty()) {
            logger.lifecycle("kot: skipped undeclared floors: ${skipped.joinToString()}")
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "kot: consumer floor regression in ${aarFile.name} " +
                    "(the artifact demands more from consumers than the declared floor):\n" +
                    violations.joinToString("\n") { "  - $it" } +
                    "\nRaise the floor deliberately in the kot { } block, or revert the change that widened it."
            )
        }

        logger.lifecycle(
            "kot: consumer floor check passed for ${aarFile.name}:\n" +
                passed.joinToString("\n") { "  - $it" }
        )
    }
}
