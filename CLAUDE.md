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
have both been run against the real MSDK V5 5.18.0 jar and pass — see README.md's "Build
status"). `local.properties` is gitignored; you need your own `sdk.dir` and `DJI_API_KEY`
(see README.md's Setup section) to build.

Real device only for anything DJI-related — MSDK V5 does not run in the emulator (needs a
USB accessory connection and an aircraft radio link via the RC-N3). A clean compile proves
the API surface is real; it proves nothing about flight behavior.

**The app runs on-device now** (verified on Moto G Play 2026, Android 16) — a launch crash
that looked like an unfixable DJI bytecode bug turned out to be DJI's app-protection layer
refusing to inject its real classes into a *debuggable* build. See README.md's "Resolved:
launch crash on Android 16" for the mechanism. Load-bearing consequence: **DJI-touching
builds cannot be run under a Java debugger** (`isDebuggable = false` is required) — don't
"fix" that back to `true` without understanding why it's there.

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

## Known state: GPS-only rewrite compiles and passes tests, unflown

This app pivoted from vision+GPS tracking to **GPS-only following** — `vision/` (detector,
tracker, phone-camera test harness) was deleted outright, not kept disconnected. The
subject is always the controller phone's own GPS position; the aircraft holds a standoff
distance and points the gimbal at the subject's ground-level position (altitude +
horizontal distance only — the subject, a cyclist, is assumed always at ground level, no
height estimate needed). See README.md's "Why this app exists" section for the full
rationale.

Every DJI API call in this codebase has been checked against the actual jars (via `javap`
against the cached Gradle dependencies, not just docs/search results) and the full app
compiles, assembles, and passes its unit tests. That's a much stronger bar than "written
from documentation" — but it's still not the same as flying it. **Nothing about GPS-only
following (the standoff-distance approach/backoff, the gimbal-pitch sign convention, the
combined behavior at realistic speed) has been verified on real hardware yet** — see
README.md's "Known gaps" and milestone list.

If you touch any DJI call and aren't sure of a signature, don't guess from search
results — extract the real jar from the Gradle cache
(`~/.gradle/caches/modules-2/files-2.1/com.dji/...`) and run
`javap -public -classpath <jar> <class>` the way this codebase's signatures were
originally verified. It's faster and more reliable than another round of web search.
