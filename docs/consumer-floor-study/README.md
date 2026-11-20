# Consumer floors of widely used Android SDKs, measured on 20 November 2026 (UTC)

This study runs kot against published releases of widely used Android SDKs, as listed by
AppBrain, and records how the floors they demand from consumers moved from release to release.
It is a dated snapshot: the numbers describe what the repositories served on the measurement
date.

## Summary

96 SDKs were measured across 860 published AARs. kot read 845 of them; the other 15 carry no
class and are not measured (14 of them are releases of one SDK, Appodeal).

How the columns are counted:

- An SDK **carries** a floor when at least one of its measured releases records it.
- A floor **rose** when, between two neighboring measured releases that both record it, the
  newer one demands more. A floor that rose and later fell still counts.
- It **rose within a major version** when those two releases share their major version number.
- A release that does not record a floor is skipped, so a floor that appears for the first time
  is not counted as a rise.
- Kotlin metadata is compared at major.minor, the AGP floor at all three parts.

| Floor                           | SDKs that carry it | Rose | Rose within a major version |
|---------------------------------|--------------------|------|-----------------------------|
| Kotlin metadata version         | 46                 | 36   | 31                          |
| Bytecode major version          | 96                 | 69   | 59                          |
| `minCompileSdk`                 | 83                 | 29   | 28                          |
| `minAndroidGradlePluginVersion` | 81                 | 19   | 18                          |

AndroidX is one publisher with one toolchain, and it supplies 41 of the 96 SDKs. Every AGP rise
in the table above is an AndroidX library, and so are 23 of the 29 `minCompileSdk` rises and 12
of the 31 Kotlin rises within a major version. The same counts by publisher:

| Publisher                        | SDKs | Kotlin floor rose within a major | `minCompileSdk` rose | Declares an AGP floor |
|----------------------------------|------|----------------------------------|----------------------|-----------------------|
| AndroidX                         | 41   | 12 of 17                         | 23 of 36             | 24 of 36              |
| Google (Firebase, Play services) | 5    | 0 of 1                           | 0 of 3               | 0 of 3                |
| Everyone else                    | 50   | 19 of 28                         | 6 of 44              | 0 of 42               |

Six SDKs have a single measured release and so cannot show a rise, yet they count among the
SDKs that carry a floor: Appodeal and five AndroidX libraries (Legacy core utils, Legacy core
UI, Cursor Adapter, Multi-Dex, Graphics).

Three observations:

- Outside AndroidX, 45 SDKs write the AGP property and none of them declares a floor of its
  own. All record `1.0.0`, the value AGP writes when the library declares nothing.
- Outside AndroidX, 29 SDKs carry Kotlin metadata, and 19 of them raised its version without a
  new major version.
- In 24 of the 46 Kotlin SDKs, the newest measured release that carries Kotlin metadata is at
  2.1 or newer. A Kotlin compiler reads metadata one minor version ahead of its own, so those
  releases need a consumer on Kotlin 2.0 or newer; the 8 at metadata 2.2 or newer need more.

## What the release notes say

For four rises and one fall, the vendor's release notes were read in full for the releases in
between. None of them states a minimum Kotlin version for the integrating app. Two announce a
Kotlin upgrade; neither says what it means for the integrator.

| SDK                 | Releases compared | Kotlin metadata | Release notes                                             |
|---------------------|-------------------|-----------------|-----------------------------------------------------------|
| Unity Ads           | 4.17.0 to 4.19.0  | 1.8 to 2.1      | "Upgraded Kotlin to 2.1.x." ([changelog][unity])          |
| HyprMX              | 6.2.3 to 6.4.7    | 1.7 to 2.2      | "Updated Kotlin to 2.2." ([changelog][hyprmx])            |
| Chartboost          | 9.12.1 to 9.13.0  | 1.9 to 2.1      | No mention of Kotlin ([changelog][chartboost])            |
| Startapp (Start.io) | 5.2.6 to 5.3.2    | 2.0 to 2.4      | "Updated dependencies and build tools versions." ([changelog][startio]) |
| Coil                | 3.3.0 to 3.4.0    | 2.2 to 2.1      | "Set Kotlin language version to 2.1." ([changelog][coil]) |

Coil is the fall: the release updates the compiler and lowers the metadata version in the same
step. Chartboost's changelog has an earlier entry of the same kind, "Downgrade Kotlin to 1.8."
in 9.8.2.

The measured releases are a sample, so a change happened somewhere between the two releases
named, not necessarily in the newer one. The HyprMX changelog address sits under an
`android-amazon` path; whether the Android SDK has another changelog was not confirmed.

[unity]: https://docs.unity.com/ads/en-us/manual/Changelog
[hyprmx]: https://documentation.hyprmx.com/sdk-integration-guides/android-amazon/change-log/android-sdk-change-log.md
[chartboost]: https://docs.chartboost.com/en/monetization/integrate/android/changelog/
[startio]: https://support.start.io/hc/en-us/articles/5813405015442-Android-SDK-Change-Log
[coil]: https://github.com/coil-kt/coil/blob/main/CHANGELOG.md

## What this does not show

- It does not show harm. The study measures what an artifact demands, not how many consumers
  could no longer build. A floor that rises over the years is normal.
- It does not tell an intended rise from an accidental one. A vendor that writes "Upgraded
  Kotlin" chose the upgrade; whether the effect on consumers was weighed cannot be read from an
  artifact.
- The bytecode count includes old moves such as Java 6 to Java 8, which say little today.
- The sample has gaps. At most 15 releases per SDK were measured, so the release in which a
  floor moved is known only to the nearest measured pair.
- Releases are ordered by version number. Two vendors number out of step with time (PubNative,
  Appodeal), so their "oldest" values in `summary.csv` are not their earliest.

## Method

**Source.** The SDK list comes from AppBrain's Android library statistics, as served on
20 November 2026: the ad networks page, the social libraries page, and four views of the
development tools page (default, sorted by apps, top 500 apps, new libraries). Together the
pages yield 148 entries. `sdks.csv` lists all of them with the share of apps AppBrain reports.

**Selection.** Every entry was looked up on Google Maven, Maven Central or the vendor's own
Maven repository, and its newest stable version was checked for an AAR or a JAR. All 96 entries
that publish an AAR are measured; nothing was picked by hand. The other 52 stay in `sdks.csv`
with the reason:

| Status        | Entries | Meaning                                                |
|---------------|---------|--------------------------------------------------------|
| `aar`         | 96      | Measured.                                              |
| `jar`         | 18      | Published as a plain JAR, which kot 0.1.0 does not read. |
| `excluded`    | 15      | Not a library, or not on a public Maven repository.    |
| `not_checked` | 15      | Discontinued SDKs; no coordinate was looked up.        |
| `unverified`  | 4       | No AAR or JAR was found at the coordinate tried.       |

AppBrain counts a product family as one entry ("Firebase", "Jetpack Compose"). One module was
chosen to stand for each family; the `coordinate` column of `sdks.csv` shows which. This choice
is a judgment, and a different module of the same family can carry different floors.

**Sampling.** Per SDK, only stable versions count (dotted numbers with no qualifier). Each minor
line contributes its latest patch release. When an SDK has more than 15 minor lines, 15 are
taken at even spacing, the oldest and the newest always among them. A version that the
repository does not serve as an AAR is left out.

**Placeholder artifacts.** A library that became multiplatform keeps an empty AAR at its
familiar coordinate and ships the code under an artifact with the `-android` suffix. When the
artifact at the listed coordinate carries no class, or is not published as an AAR for that
version, the suffixed artifact of the same version is measured instead, and the
`relocated_from` column of `results.csv` records the move (26 rows). An artifact with no class
under either name is listed with `has_classes` set to `false` and not measured (15 rows).

**Measurement.** Each artifact is read by kot 0.1.0 from the Gradle Plugin Portal, through one
`VerifyConsumerFloorTask` per artifact. The study adds no reading of its own: the four values
in `results.csv` are the ones kot wrote to its emitted floors report.

## Files

| File          | Contents                                                                      |
|---------------|-------------------------------------------------------------------------------|
| `sdks.csv`    | The 148 entries of the source: share of apps, status, repository, coordinate. |
| `results.csv` | One row per artifact: the four floors, the URL, the SHA-256 of the file, the download time, and the Last-Modified date the repository sends (`published`; not always the release date). |
| `summary.csv` | One row per SDK: for each floor, the value in the oldest and in the newest release that records it, and whether it rose. |

The counts in this document are sums over `summary.csv`.

## Checking a row

Download the URL of a row of `results.csv` and compare the file's SHA-256 with the recorded one.
Then point kot at the file (the "Without AGP" section of the project README shows how) and
compare the floors it reports.
