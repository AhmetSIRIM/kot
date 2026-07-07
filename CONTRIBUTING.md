# Contributing

Thanks for taking the time. kot is a small, focused plugin; contributions work best when they
stay inside that focus: producer-side verification of emitted consumer floors.

## Before you code

Open an issue first for anything beyond a trivial fix, so the direction is agreed before the
work exists.

## Building and testing

- JDK 17 (the build pins its own toolchain)
- `./gradlew check` runs everything: compilation, detekt, plugin validation and the functional
  test suite
- The AGP end-to-end test needs an Android SDK; `ANDROID_HOME` or the default SDK location is
  picked up automatically

## Conventions the build enforces

- detekt gates style (its defaults plus the deviations in `config/detekt/detekt.yml`)
- KDoc is a first-class artifact: `dokkaGenerate` fails on any warning
- The functional tests double as the behavioral spec; a behavior change lands together with
  the test that pins it

## Commits and PRs

- Commits are atomic; the subject names the change, the body carries the why and the trade-offs
- The `summary` check must be green before a PR merges
