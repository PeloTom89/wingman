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

The Gradle wrapper is committed and works (`./gradlew assembleDebug` / `./gradlew test`
have both been run against the real MSDK V5 5.18.0 + MediaPipe 0.10.14 jars and pass — see
README.md's "Build status"). `local.properties` is gitignored; you need your own
`sdk.dir` and `DJI_API_KEY` (see README.md's Setup section) to build.

Real device only for anything DJI-related — MSDK V5 does not run in the emulator (needs a
USB accessory connection and an aircraft radio link via the RC-N2). A clean compile proves
the API surface is real; it proves nothing about flight behavior.

**The app currently crashes on launch on Android 16** (confirmed on a real device) — see
README.md's "Known blocking issue" before spending time on anything downstream of SDK
registration. This is DJI's own bytecode failing ART's verifier, reproduced across two AGP
versions and two MSDK point releases, matching multiple unresolved upstream GitHub issues.
Don't re-try AGP/dexing-mode/SDK-version changes as a fix without a genuinely new reason —
that space is already ruled out. An older Android device is the next real diagnostic step.

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

## Known state: compiles and passes tests against the real SDK, unverified against real hardware

Every DJI/MediaPipe API call in this codebase has been checked against the actual jars
(via `javap` against the cached Gradle dependencies, not just docs/search results) and the
full app compiles, assembles, and passes its unit tests. That's a much stronger bar than
"written from documentation" — but it's still not the same as flying it. Two things remain
genuinely unverified because they can't be checked from bytecode alone: perception data
units and ring-indexing convention (`sdk/PerceptionRepository.kt`'s header comment) — both
feed `ObstacleSafetyClamp` directly, so confirm them against real sensor output before
trusting that file's thresholds in flight.

If you touch any DJI/MediaPipe call and aren't sure of a signature, don't guess from
search results — extract the real jar from the Gradle cache
(`~/.gradle/caches/modules-2/files-2.1/com.dji/...` /
`.../com.google.mediapipe/...`) and run `javap -public -classpath <jar> <class>` the way
this codebase's signatures were originally verified. It's faster and more reliable than
another round of web search.

See README.md's "Known gaps" section for what's an intentional placeholder
(`CoastingBoxTracker`, NV21-to-Bitmap conversion, tap-to-select frame passing, unwired
`GimbalController`).
