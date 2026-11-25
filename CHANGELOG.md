# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Plain JARs are verified. The classes an archive carries itself are read, so the Kotlin
  metadata and bytecode floors cover JVM libraries as well as Android ones. Among those classes,
  a module descriptor and the versioned classes of a multi-release JAR do not raise the bytecode
  floor, because a consumer on an older JVM never loads them. The jars nested in an AAR are
  scanned whole, as before.
- With the Java plugin applied (the Java library and Kotlin JVM plugins apply it), the archive
  of the `jar` task is wired into the gate and built before it is verified. An artifact set on
  the task still wins.
- The `kotlin-stdlib` version a library brings to its consumers is held against
  `kotlinMetadataFloor`. The wirings take the highest version on the project's runtime
  classpath (the release variant's for an Android library), selected or asked for by a
  dependency, and it is written to the report. A `kotlin-stdlib` version the build forces lower
  does not count, because the force is not published. Where no classpath is wired a version
  can be set on the task as `kotlinStdlibVersion`.
- `minSdkFloor`: the `minSdkVersion` an AAR's manifest records is held against a declared
  floor, the way `minCompileSdk` is. A consumer app with a lower minSdk cannot merge the
  library, so a rise narrows who can use it. The value is written to the report and offered in
  the block shown when no floor is declared.

### Changed

- The `kotlin-stdlib` check can turn an existing build red in two ways. A library with a
  Kotlin floor declared whose runtime classpath carries a newer `kotlin-stdlib`, selected or
  asked for, now fails the gate even when its own classes are stamped within the floor: a
  consumer's compiler reads that stdlib too, so such a build was green over a higher real
  floor. And with a Kotlin floor declared, a runtime dependency that does not resolve now
  fails the gate instead of going unnoticed.
- A run with no floor declared still fails, and now shows the floors the artifact emits as a
  `kot { }` block to start from. The artifact is read before the failure, so the report is
  written on such a run too, and an unreadable artifact fails with its own error first.
- In a project with the Java plugin applied, `check` now runs the gate. This can turn an
  existing JVM module's build red: a module that applies the plugin with no floor declared,
  with only `compileSdkFloor` or `agpFloor` declared, or with its `jar` task disabled and no
  artifact set, now fails `check`. Declare its Kotlin metadata or bytecode floor, set the
  artifact, or opt out with `attachToCheck.set(false)`.
- The message for a run with no artifact names both wirings.
- An AAR with no `classes.jar` is no longer refused outright. Its Android floors are verified
  as before; a declared Kotlin metadata or bytecode floor still fails, because the archive has
  no class to measure.
- The message for an archive with no class to set a bytecode floor is reworded.

### Fixed

- An entry named like a class whose bytes do not start as a class file, or a class file of a
  version the class reader does not support, fails with a message that names the entry and the
  archive (and the nested jar it sits in), instead of the class reader's bare error.

## [0.1.0] - 2026-11-20

### Added

- A library with product flavors can use the gate. An artifact set on the task is verified
  whenever it is set, and the run says that the other release variants were not checked.
- KDoc on the public surface (`KotPlugin`, `KotExtension` and the task's properties), shown by
  the IDE as quick documentation.

### Changed

- With more than one release variant the wiring supplies no AAR. The task fails and names the
  variants until an artifact is set on it; it no longer refuses a flavored library outright.
- A run with no artifact at all fails with the task's own message instead of Gradle's input
  validation message.
- ASM, the one dependency that ships with the plugin, moves from 9.10.1 to 9.11.1.
- Gradle 8.2 is the oldest supported version, lower than the 8.5 stated before; the test suite
  now runs against it.
- On an older Gradle, a build that applies the plugin through the `plugins { }` block is refused
  with a message that names both versions. Before, its Kotlin build script failed with a
  protocol buffer error.

### Fixed

- An artifact that is not a zip archive fails with a message that names the file, instead of the
  JDK's bare `ZipException`.
- A `minCompileSdk` that is recorded but is not a number is reported as such, instead of as
  missing.
- Classes in the jars an AAR bundles under `libs/` are scanned too. A newer Kotlin metadata stamp
  or bytecode version there no longer passes unnoticed, so a build that was green can turn red.

## [0.0.2] - 2026-07-11

### Added

- The measured floors are written to `build/reports/kot/emitted-floors.properties` on every run
  that reads the artifact, pass or fail.
- With the Android library plugin applied, the gate runs as part of `check`. Opt out with
  `attachToCheck.set(false)` in the `kot { }` block.
- The task is cacheable.

### Changed

- An artifact set on the task wins over the automatic wiring.
- A library with more than one release variant is refused instead of having a single flavor
  verified silently.

### Fixed

- A class carrying a second, lower `@kotlin.Metadata` stamp can no longer mask its real floor.
- A malformed `kotlinMetadataFloor` fails as a configuration error instead of being read as 0.
- A library whose release variant is disabled no longer gets the gate attached to `check`.

## [0.0.1] - 2026-07-07

### Added

- The `verifyConsumerFloor` task and the `kot { }` block. The task opens a built AAR and fails
  the build when the artifact demands more from consumers than the declared floors: Kotlin
  metadata version, `minCompileSdk`, `minAndroidGradlePluginVersion` and bytecode major version.
- With the Android library plugin applied, the release AAR is wired into the task and built
  before it is verified.
- Undeclared floors are skipped and named in the output; declaring none is an error.
- Every violated dimension is reported in a single failure.
- The task is compatible with the configuration cache.

[Unreleased]: https://github.com/AhmetSIRIM/kot/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/AhmetSIRIM/kot/compare/v0.0.2...v0.1.0
[0.0.2]: https://github.com/AhmetSIRIM/kot/compare/v0.0.1...v0.0.2
[0.0.1]: https://github.com/AhmetSIRIM/kot/releases/tag/v0.0.1
