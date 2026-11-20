# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

### Fixed

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

[Unreleased]: https://github.com/AhmetSIRIM/kot/compare/v0.0.2...HEAD
[0.0.2]: https://github.com/AhmetSIRIM/kot/compare/v0.0.1...v0.0.2
[0.0.1]: https://github.com/AhmetSIRIM/kot/releases/tag/v0.0.1
