package io.github.ahmetsirim.kot

import org.gradle.api.GradleException

/**
 * Parses a declared Kotlin floor such as "2.2" into its int parts. Strict: a malformed floor
 * fails the configuration mistake loudly. Falling back to 0 would turn a typo into a
 * guaranteed-red gate with a misleading message (or, with a floor like "99x", into a silently
 * weakened one).
 */
internal fun parseKotlinFloor(declaredFloor: String): List<Int> {
    return declaredFloor.split(".").map { part: String ->
        part.toIntOrNull() ?: throw GradleException(
            "kot: kotlinMetadataFloor \"$declaredFloor\" is not a dotted numeric version (like \"2.2\")."
        )
    }
}
