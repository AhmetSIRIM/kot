# kot

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.ahmetsirim.kot)](https://plugins.gradle.org/plugin/io.github.ahmetsirim.kot)

Producer-side verification of the consumer floors your Android library actually ships.

> **Tip:** *kot* is the Turkish construction term for a surveyed elevation level; *kot farkı* is
> the measured difference between two levels. A build toolchain stamps elevation marks into every
> artifact it produces; kot reads them back and breaks the build when they rise above the level
> you promised your consumers. It is also, conveniently, how Kotlin starts.

## The problem

Your library's build files say nothing about the minimum Kotlin version your consumers need.
The compiler decides that, by stamping a binary metadata version into every class it emits.
Consumers whose Kotlin is older than the stamp fail with:

```
Module was compiled with an incompatible version of Kotlin.
The binary version of its metadata is 2.3.0, expected version is 2.2.0.
```

The producer never sees this error: the same toolchain that stamped the artifact reads it back
happily. And since AGP 9 ships its own bundled Kotlin compiler (built-in Kotlin), a routine AGP
upgrade can raise the stamp with no change in your code, your version catalog, or your CI
output. The floor your consumers must clear drifts silently.

Automated dependency bumps make the drift routine: a Renovate or Dependabot PR that raises AGP
(or any part of the toolchain) looks green, precisely because the producer's own build cannot
observe the floors the new toolchain stamps. Merge it and your consumers' window narrows with
nobody deciding that. kot turns that narrowing into a red build, so widening the floor goes
back to being a decision.

kot opens the built AAR and verifies what was actually **emitted**, not what was configured:

| Dimension                       | Emitted where                              | Check               |
|---------------------------------|--------------------------------------------|---------------------|
| Kotlin metadata version         | `@kotlin.Metadata` mv stamp on every class | `<=` declared floor |
| `minCompileSdk`                 | `aar-metadata.properties`                  | `<=` declared floor |
| `minAndroidGradlePluginVersion` | `aar-metadata.properties`                  | `=` declared floor  |
| Bytecode major version          | class-file header of every class           | `<=` declared floor |

## Usage

```kotlin
plugins {
    id("com.android.library")
    id("io.github.ahmetsirim.kot") version "0.0.1"
}

kot {
    kotlinMetadataFloor.set("2.2")
    compileSdkFloor.set(36)
    agpFloor.set("8.1.0")
    jvmTargetFloor.set(17)
}
```

Run `./gradlew verifyConsumerFloor`: the release AAR is built (the wiring rides AGP's
`SingleArtifact.AAR`, so the ordering comes for free) and every declared floor is verified
against it. Behavior worth knowing:

- With `com.android.library` applied, the gate also runs as part of `./gradlew check` (and so
  `build`) by default; opt out with `attachToCheck.set(false)` in the `kot { }` block.
- Every run that reads the artifact (pass or fail) writes the measured floors to
  `build/reports/kot/emitted-floors.properties`, so the consumer bounds your artifact carries
  are always one file away.
- Undeclared floors are skipped, and the skip is named in the output; declaring none at all is
  an error, because a gate that guards nothing should not run green.
- Every violated dimension is reported in a single failure, so one red build shows the full
  damage of a toolchain bump.
- The task is configuration-cache compatible.
- A floor set directly on the task overrides the `kot { }` block.

Without AGP, or to verify a hand-picked artifact, wire the input yourself:

```kotlin
tasks.named<io.github.ahmetsirim.kot.VerifyConsumerFloorTask>("verifyConsumerFloor") {
    artifact.set(layout.buildDirectory.file("outputs/aar/mylib-release.aar"))
}
```

### Why is the AGP dimension an equality check?

AGP writes `minAndroidGradlePluginVersion` from your own `aarMetadata.minAgpVersion`
declaration. If that declaration is removed, AGP quietly records its `1.0.0` default, and a
`<=` check would wave the loss through. Equality turns drift in either direction red: a raised
floor and a lost declaration are both findings.

## Why not an existing tool?

Each of these is useful; none reads the emitted floor on the producer's side:

- **Dependency scanners (e.g. kdrift):** run on the consumer's side, after the incompatible
  artifact is already published.
- **compat-patrouille:** pins the build's *input* configuration (Kotlin/Java versions); it does
  not open the artifact to verify what actually came out. Under AGP's built-in Kotlin, the
  emitted metadata version is not part of your configuration at all.
- **binary-compatibility-validator / KGP abiValidation:** guard the API *surface* axis (what
  symbols you expose), not the toolchain floor axis (which toolchains can read them).

kot is the missing quadrant: producer-side, emitted-value verification.

## Compatibility

Exercised end-to-end by the functional test suite on every PR, not just claimed:

| Axis   | Oldest tested | Newest tested |
|--------|---------------|---------------|
| AGP    | 8.1           | 9.0           |
| Gradle | 8.5           | 9.6           |

The AGP wiring compiles against the 8.1 Variants API on purpose, and a dedicated test cell
runs the plugin inside a real AGP 8.1 / Gradle 8.5 build, so code demanding anything newer
fails in CI rather than on a consumer's machine. The grid between the floor and the newest
combination is on the roadmap.

## Status and roadmap

Pre-release (`0.x`), awaiting first-publish approval on the Gradle Plugin Portal. Planned,
roughly in order:

- The full Gradle x AGP compatibility matrix (the floor and newest cells exist; the grid
  between them, and `--configuration-cache` across versions, are planned)
- Flavored-variant support (per-variant verification instead of the single release wiring)
- Plain-JAR artifacts: the Kotlin metadata and bytecode dimensions apply to any JVM library
- Published API docs (Dokka) and the Gradle Plugin Portal release

## Contributing

Build instructions and the conventions the build enforces live in
[CONTRIBUTING.md](CONTRIBUTING.md).
