package io.github.ahmetsirim.kot

/**
 * The consumer-facing floors an archive actually emits, one field per verified dimension.
 * Every field is nullable: null means "the artifact does not carry this fact" (no metadata
 * entry, no Kotlin class, no class at all), and the task decides per dimension what absence means.
 */
internal data class EmittedFloors(
    val kotlinMetadataVersion: List<Int>?, // Highest @kotlin.Metadata mv stamp found, as ints like [2, 2, 0].
    val minCompileSdk: String?, // From aar-metadata.properties, as recorded; the compileSdk every consumer must reach.
    val minAgpVersion: String?, // From aar-metadata.properties; the AGP version every consumer must reach.
    val maxClassMajorVersion: Int?, // Highest class-file major version found across all classes.
    val minSdk: String?, // From AndroidManifest.xml, as recorded; the minSdk every consumer app must reach.
)
