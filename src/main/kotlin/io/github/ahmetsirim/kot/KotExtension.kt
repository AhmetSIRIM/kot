package io.github.ahmetsirim.kot

import org.gradle.api.provider.Property

/**
 * Backing model of the `kot { }` block: the floors a library promises its consumers.
 *
 * A floor that is left unset is not checked, and the task names it as skipped. Declaring no
 * floor at all fails the task.
 */
abstract class KotExtension {

    /**
     * Highest Kotlin metadata version the artifact may carry, as `major.minor` (for example
     * `"2.2"`). The patch component of the emitted version is ignored.
     */
    abstract val kotlinMetadataFloor: Property<String>

    /** Highest `minCompileSdk` the artifact may record: the compileSdk every consumer must reach. */
    abstract val compileSdkFloor: Property<Int>

    /**
     * Exact `minAndroidGradlePluginVersion` the artifact must record. Compared for equality,
     * because the recorded value is the module's own `aarMetadata.minAgpVersion` read back: a
     * lower value means the declaration was lost, a higher one means it was raised.
     */
    abstract val agpFloor: Property<String>

    /** Highest Java release any class in the artifact may target (for example `17`). */
    abstract val jvmTargetFloor: Property<Int>

    /**
     * Whether `verifyConsumerFloor` also runs as part of `check` when the Android library
     * plugin is applied. Set to `false` to keep `check` free of the gate; a gate that runs
     * only when called by name misses the silent toolchain bumps it exists to catch.
     */
    abstract val attachToCheck: Property<Boolean>
}
