package io.github.ahmetsirim.kot

import org.gradle.api.provider.Property

// Backing model of the consumer's kot { } block. Abstract on purpose: Gradle subclasses it at
// runtime and injects working Property implementations, which is why nothing here has a body.
abstract class KotExtension {
    // Property<T> carries the promise of a value, not the value itself: it is read at execution
    // time, so the consumer's script order (kot { } before or after anything) never matters.
    abstract val kotlinMetadataFloor: Property<String>
    abstract val compileSdkFloor: Property<Int>
    abstract val agpFloor: Property<String>
    abstract val jvmTargetFloor: Property<Int>

    // Whether the AGP wiring also hangs verifyConsumerFloor onto the consumer's check task
    // (defaults to true): a gate that only runs when someone remembers to invoke it would miss
    // exactly the silent toolchain bumps it exists to catch.
    abstract val attachToCheck: Property<Boolean>
}
