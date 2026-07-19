# CLAUDE.md

Guidance for Claude Code when working in this repo specifically. See `README.md` first for
the full picture (why MSDK V5 not V4, why obstacle avoidance is custom-built, why there's
no GPS beacon/backend). This file is just the terse operational notes.

## This is a standalone repo

Despite the thematic overlap with the `slipstreamirl-*` projects elsewhere in the
`F:\claudecode` workspace (also IRL-cycling-streaming-adjacent), **wingman has no
dependency on them** and shouldn't grow one — different language/runtime entirely
(Kotlin/Android vs. the Expo/Node stack), different repo, different deploy target.

## Build/run

No Gradle wrapper is committed (no local Gradle install was available at scaffold time to
generate `gradlew`/`gradlew.bat`/`gradle-wrapper.jar` correctly) — open in Android Studio
and let it sync to regenerate the wrapper, or run `gradle wrapper` once with a local
Gradle 8.9+ install. `./gradlew test` runs the unit tests once the wrapper exists.

Real device only for anything DJI-related — MSDK V5 does not run in the emulator (needs a
USB accessory connection and an aircraft radio link via the RC-N2).

## Safety-critical files — treat changes here differently

- `flightcontrol/ObstacleSafetyClamp.kt` — the app's entire obstacle-avoidance story, since
  DJI's own APAS is disabled under VirtualStick control on the Mini 4 Pro. Any change here
  needs `ObstacleSafetyClampTest.kt` extended to cover the new behavior, not just the
  existing cases re-run.
- `flightcontrol/SafetyLimits.kt` — hard ceilings (speed/altitude/geofence/battery). Don't
  loosen defaults without it being an explicit, deliberate ask — they start deliberately
  conservative per the milestone testing progression in README.md.
- `sdk/VirtualStickController.kt` — the only class allowed to call
  `sendVirtualStickAdvancedParam`. If a change seems to need a second call site for this,
  that's a sign the architecture is being routed around rather than through — reconsider
  before adding one.

## Known state: this is unverified against the real SDK

Every DJI SDK call (in `sdk/` and `ui/CameraPreviewScreen.kt`) was written from
documented MSDK V5 patterns researched via DJI's API reference, GitHub samples, and SDK
forum — none of it has been compiled against the real SDK jar yet. Expect first-build
signature mismatches; each such file has a header comment flagging this. Fix signatures
against the pinned version's actual API reference (`developer.dji.com/api-reference-v5`)
rather than guessing further from search results.

See README.md's "Known gaps" section for what's an intentional placeholder
(`CoastingBoxTracker`, unwired video-frame format conversion, tap-to-select frame passing,
native RTH trigger) vs. what's expected-but-unverified SDK glue.
