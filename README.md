# kot

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.ahmetsirim.kot)](https://plugins.gradle.org/plugin/io.github.ahmetsirim.kot)
[![PR Check](https://github.com/AhmetSIRIM/kot/actions/workflows/pr-check.yml/badge.svg)](https://github.com/AhmetSIRIM/kot/actions/workflows/pr-check.yml)
[![License](https://img.shields.io/github/license/AhmetSIRIM/kot)](LICENSE)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/AhmetSIRIM/kot/badge)](https://scorecard.dev/viewer/?uri=github.com/AhmetSIRIM/kot)

Producer-side verification of the consumer floors your Android or JVM library actually ships.

> **Tip:** *kot* is the Turkish construction term for a surveyed elevation level; *kot farkı* is
> the measured difference between two levels. A build toolchain stamps elevation marks into every
> artifact it produces; kot reads them back and breaks the build when they rise above the level
> you promised your consumers. It is also, conveniently, how Kotlin starts.

## The problem

Your library's build files say nothing about the minimum Kotlin version your consumers need.
The compiler decides that, by stamping a binary metadata version into every class it emits.
A Kotlin compiler reads metadata up to one minor version ahead of its own; consumers further
behind than that fail with:

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

Floor rises can be measured. A [study of 96 widely used Android SDKs](docs/consumer-floor-study/README.md),
measured on 20 November 2026, found that 19 of the 29 SDKs outside AndroidX that carry Kotlin
metadata raised its version without a new major version, and that all 45 outside AndroidX
that record an AGP floor carry AGP's default, not a declared one. Of five release notes read, two
announce a Kotlin upgrade and none states a minimum Kotlin version for the integrating app.

kot opens the built archive and verifies what was actually **emitted**, not what was configured:

| Dimension                       | Emitted where                              | Check               |
|---------------------------------|--------------------------------------------|---------------------|
| Kotlin metadata version         | `@kotlin.Metadata` mv stamp on every class | `<=` declared floor |
| `minCompileSdk`                 | `aar-metadata.properties`                  | `<=` declared floor |
| `minAndroidGradlePluginVersion` | `aar-metadata.properties`                  | `=` declared floor  |
| Bytecode major version          | class-file header of every class           | `<=` declared floor |

In an AAR, classes are read from `classes.jar` and from every jar bundled under `libs/`. The
classes an archive carries itself are read too, so the two class dimensions also cover a JVM
library's JAR; see [Plain JARs](#plain-jars).

## Usage

```kotlin
plugins {
    id("com.android.library")
    id("io.github.ahmetsirim.kot") version "<latest_version>"
}

android {
    defaultConfig {
        aarMetadata {
            minAgpVersion = "8.1.0" // What agpFloor below verifies the artifact still records.
        }
    }
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

- With `com.android.library` or the Java plugin applied, the gate also runs as part of
  `./gradlew check` (and so `build`) by default; opt out with `attachToCheck.set(false)` in the
  `kot { }` block.
- Every run that reads the artifact (pass or fail) writes the measured floors to
  `build/reports/kot/emitted-floors.properties`, so the consumer bounds your artifact carries
  are always one file away.
- Undeclared floors are skipped, and the skip is named in the output; declaring none at all is
  an error, because a gate that guards nothing should not run green.
- Every violated dimension is reported in a single failure, so one red build shows the full
  damage of a toolchain bump.
- The task is configuration-cache compatible.
- A floor set directly on the task overrides the `kot { }` block.

### What the Kotlin floor means

`kotlinMetadataFloor` is a metadata version, not a compiler version. Because a Kotlin compiler
reads metadata one minor version ahead of its own, a floor of `2.2` keeps the library readable
for consumers on Kotlin 2.1 and newer. Declare the floor one minor above the oldest Kotlin your
consumers use. The rule is the compiler's own; the
[kotlin-metadata-jvm documentation](https://github.com/JetBrains/kotlin/blob/master/libraries/kotlinx-metadata/jvm/ReadMe.md)
states it.

### Libraries with product flavors

A library with product flavors builds several release AARs, and kot does not pick one for you:
verifying a single flavor silently would read as coverage of all of them. Name the AAR to verify
on the task. Taking it from the Variants API also builds it first:

```kotlin
androidComponents {
    onVariants(selector().withBuildType("release").withFlavor("tier" to "paid")) { variant ->
        tasks.named<io.github.ahmetsirim.kot.VerifyConsumerFloorTask>("verifyConsumerFloor") {
            artifact.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.AAR))
        }
    }
}
```

The run then says that the other release variants were not checked.

### Plain JARs

A JVM library has the same Kotlin metadata and bytecode floors as an Android one. With the Java
plugin applied, which the Java library plugin and the Kotlin JVM plugin both do, kot verifies
the archive of the `jar` task:

```kotlin
plugins {
    kotlin("jvm") version "<kotlin_version>" // or `java-library`
    id("io.github.ahmetsirim.kot") version "<latest_version>"
}

kot {
    kotlinMetadataFloor.set("2.1")
    jvmTargetFloor.set(8)
}
```

`./gradlew verifyConsumerFloor` builds the jar and verifies it, and the gate runs as part of
`check`, as it does for an Android library. The archive read is the one the `jar` task builds:
if you publish another one (a shadow or fat jar), name that archive on the task, as shown below.
A disabled `jar` task supplies no archive. A Kotlin Multiplatform project builds one jar per JVM
target and is not wired either.

Two kinds of class do not raise a plain JAR's bytecode floor, because a consumer on an older JVM
never loads them: the module descriptor (`module-info.class` at the archive's root) and the
versioned classes of a multi-release JAR (under `META-INF/versions/`). A versioned class still
counts toward the Kotlin metadata floor. Jars nested inside a plain JAR are not opened.

The jars nested in an AAR (`classes.jar` and those under `libs/`) are scanned whole: a module
descriptor or a versioned class inside one of them does count toward the bytecode floor.

`compileSdkFloor` and `agpFloor` read a file only an AAR carries; declared over a plain JAR they
fail the build, because a floor that cannot be measured is not a passing one.

### Any other archive

To verify an archive that no plugin wires in, set the input yourself. A value set on the task
wins over the wiring:

```kotlin
tasks.named<io.github.ahmetsirim.kot.VerifyConsumerFloorTask>("verifyConsumerFloor") {
    artifact.set(layout.buildDirectory.file("libs/mylib-jvm.jar"))
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

| Axis   | Oldest tested | Newest tested                              |
|--------|---------------|--------------------------------------------|
| AGP    | 8.1           | 9.0                                        |
| Gradle | 8.2           | the version this repository's wrapper pins |

kot runs on JDK 17 or newer.

The AGP wiring compiles against the 8.1 Variants API on purpose, and a dedicated test cell
runs the plugin inside a real AGP 8.1 / Gradle 8.2 build, so code demanding anything newer
fails in CI rather than on a consumer's machine. The grid between the floor and the newest
combination is on the roadmap.

Gradle 8.2 is the floor because of Kotlin build scripts: with the Kotlin version kot is built
with today, the compiler embedded in Gradle 8.0 and 8.1 cannot read the plugin's metadata, and
the script fails with a protocol buffer error. Applied through the `plugins { }` block, kot
stops such a build itself and names the version it needs. A script that only has the plugin on
its classpath (a `buildscript` block, `apply false`, a convention plugin) is compiled before any
plugin code runs, and still fails with the protocol buffer error.

## Status and roadmap

Pre-release (`0.x`), published on the Gradle Plugin Portal. What each version changed is in
[CHANGELOG.md](CHANGELOG.md). Planned, roughly in order:

- The full Gradle x AGP compatibility matrix (the floor and newest cells exist; the grid
  between them, and `--configuration-cache` across versions, are planned)
- Flavored-variant support (per-variant verification instead of the single release wiring)
- Kotlin Multiplatform wiring (one archive per JVM target)
- OpenSSF Best Practices (the bestpractices.dev self-assessment; the badge arrives with the
  passing level, not before)

## Contributing

Build instructions and the conventions the build enforces live in
[CONTRIBUTING.md](CONTRIBUTING.md).
