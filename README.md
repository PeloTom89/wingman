# wingman

Android app that flies a DJI Mini 4 Pro to keep a subject (a cyclist/streamer) in frame
continuously, using GPS-only following: no on-device vision tracking, no visual
line-of-sight requirement on the subject (as distinct from the aircraft — see the
regulatory note below). The aircraft holds a standoff distance from the subject's own
phone GPS position, yaws to face them, and pitches the gimbal down at their ground-level
position, computed purely from aircraft altitude and horizontal distance.

## Why this app exists, and why it's built the way it is

DJI's own ActiveTrack API does not exist for the Mini 4 Pro — it's exclusive to the legacy
Mobile SDK V4, which only supports older drones (Mavic 2 series, Mavic Air 2/2S, Mini 2,
Phantom 4, Inspire 2). The Mini 4 Pro requires MSDK V5, and V5 has no ActiveTrack
equivalent.

This project's first design built custom on-device vision tracking (detect-then-track on
live video) to fill that gap, with GPS as a fallback for when the subject left frame. Real
on-device testing of that pipeline surfaced repeated, hard-to-fully-resolve problems even
under easy conditions (indoor, close range, stationary) — jitter, drift on tilt, failure to
re-acquire after the subject left and re-entered frame. Given the actual deployment target
(a small, fast-moving, distant subject on a bike, wearing gear that gives a detector far
less to work with than a face up close), that pipeline was judged not reliable enough to
build a safety-relevant flight behavior on top of.

**The pivot: drop vision entirely, go GPS-only.** The subject is always the person
carrying the controller phone, and — critically — a cyclist is always at ground level.
That means the aircraft never needs to *see* the subject to track them: it just needs the
phone's own GPS (`location/SubjectLocationProvider.kt`), and it can aim the camera at the
subject's position by computing a gimbal pitch from altitude and horizontal distance alone
(`FlightCommandCalculator.computeGimbalPitchDegrees` — no subject-height estimate needed).
`vision/` (detector, tracker, phone-camera test harness, and all their tests) has been
deleted outright rather than kept around disconnected — see git history if you need to
resurrect any of it.

That still leaves one problem vision tracking would otherwise have helped with: obstacles.
**DJI's own obstacle avoidance (APAS) is disabled whenever VirtualStick control is active
on the Mini 4 Pro** (unlike the M300/M350/M30 series and Mavic 3E/3M, which keep APAS
active under VirtualStick — a hardware tradeoff this project deliberately chose not to
make, to stay on cheaper/lighter consumer hardware). `flightcontrol/ObstacleSafetyClamp.kt`
is a custom replacement built from raw obstacle-distance telemetry (`PerceptionManager` /
`ObstacleData`), and it is the single most safety-critical file in this repo — GPS-only
following makes it more load-bearing than before, not less, since there's no vision-based
"can I actually see a clear path" signal to fall back on. The real API doesn't give
discrete forward/backward/left/right readings — just a ring of horizontal distance samples
around the aircraft plus separate up/down values — so the clamp samples the ring at the
aircraft's actual bearing of travel; see `sdk/PerceptionRepository.kt`'s header comment for
two assumptions (sample units, ring indexing) that are flagged as unverified against real
hardware and need confirming before the clamp's distances can be trusted. Read
`ObstacleSafetyClamp.kt`'s header comment and `ObstacleSafetyClampTest.kt` before changing
either.

## Regulatory note — read before flying

Under FAA Part 107 (or your local equivalent), the remote pilot must keep the **aircraft**
itself within visual line of sight at all times — this is completely independent of how the
aircraft decides where to fly (GPS-only following included). The app surfaces an explicit
acknowledgment of this on every session's preflight screen
(`ui/PreflightChecklistScreen.kt`); it isn't a substitute for actually knowing and
following the rule.

## Setup

1. Register an app at [developer.dji.com](https://developer.dji.com) with
   `applicationId` **exactly** `com.pelotom89.wingman` (see `app/build.gradle.kts`) — a
   mismatch here is the most common MSDK registration failure and fails silently/opaquely
   at runtime.
2. Copy `local.properties.example` to `local.properties` and paste your DJI App Key into
   `DJI_API_KEY` (gitignored — never commit the real key).
3. Verify the pinned MSDK V5 version in `app/build.gradle.kts` (`djiSdkVersion`, currently
   `5.18.0`) against [developer.dji.com/mobile-sdk/downloads](https://developer.dji.com/mobile-sdk/downloads)
   — DJI ships frequent point releases and this needs to stay ≥5.13.0 for Mini 4 Pro
   support.
4. Open the project in Android Studio and let it sync (the Gradle wrapper is committed and
   has been verified against a real build — see "Build status" below).
5. Real device only. MSDK V5 will not run meaningfully in the emulator (no USB accessory,
   no aircraft radio link) — you need an actual Android phone connected to an RC-N3, and
   that same phone (carried by the cyclist) is also the GPS source `location/
   SubjectLocationProvider.kt` reads from.

## Build / run

Standard Android Studio project — `Run` on a connected device, or:

```
./gradlew assembleDebug
./gradlew test          # unit tests — see "What's actually tested" below
```

## Build status

`assembleDebug` and `test` both pass against the real MSDK V5 5.18.0 jar (pulled from
Maven Central — no special DJI repository needed, `mavenCentral()` alone resolves
`com.dji:*`), not just written from documentation. All unit tests pass (`FlightCommandCalculatorTest`,
`SafetyLimitsTest`, `ObstacleSafetyClampTest`). Beyond that: the app has previously been
installed and launched on a real device (Moto G Play 2026, Android 16) and the UI rendered
correctly under the earlier vision-tracking build. **The GPS-only rewrite itself (this
architecture) has not yet been re-verified on a real device** — it compiles and passes
unit tests, but the full following/gimbal-aiming behavior, and even just re-confirming the
UI still launches cleanly post-rewrite, still needs an on-device pass. See "Known gaps"
below.

## Resolved: launch crash on Android 16 was a debuggable-build issue, not a DJI SDK bug

Earlier investigation here concluded (wrongly) that DJI's own bytecode was fundamentally
broken and unfixable — a `VerifyError` in `dji.v5.manager.SDKManager`'s constructor,
reproduced identically across AGP versions and MSDK point releases, matching several
unresolved reports on DJI's GitHub tracker. That diagnosis was incomplete.

**The real cause:** DJI's MSDK V5 classes are wrapped in a commercial Android
app-protection/anti-tampering runtime (`com.cySdkyc.clx.Helper`, SecNeo-style). The
classes as published in `dji-sdk-v5-aircraft-provided` are intentionally-inert compile-time
stubs — every method's bytecode starts with a dead leading `return`, which is exactly what
produced the `VerifyError`. The real implementation ships **encrypted inside a native
library** and is injected into the app's classloader at runtime by `Helper.install()`
(called from `WingmanApplication.attachBaseContext`) — but that native injection routine
**refuses to run in a debuggable process** (anti-tamper behavior: it detects the debuggable
flag / an attached JDWP debugger and silently bails, so the inert stubs are all that's ever
left resolvable). The fix has two parts, both in `app/build.gradle.kts`:

1. `debug { isDebuggable = false }` — the load-bearing fix. Verified on-device (Moto G
   Play 2026, Android 16): flipping only this flag is the difference between the crash and
   the app running through `WingmanApplication.onCreate()` + SDK registration into
   `MainActivity`.
2. `dji-sdk-v5-aircraft-provided` scoped `compileOnly` (DJI's own official sample scope,
   not `implementation`) so the inert stubs never get packaged into the app's primary dex
   in the first place — leaving the runtime-injected real classes as the only definition
   that ever resolves.

**Real, permanent tradeoff:** a build that touches the DJI SDK cannot be run under a Java
debugger (no breakpoints/JDWP) — that's inherent to the protection mechanism, not
something to work around. Use logging for DJI-touching code; consider a separate
debuggable build variant if you need breakpoints for pure-UI work that doesn't init the SDK.

See `app/build.gradle.kts` and `sdk/WingmanApplication.kt`'s header comments for the full
mechanism. The `#671`/`#1311`/`#1104` DJI GitHub issues referenced during the earlier
investigation all reproduce on debuggable builds, consistent with this being the actual
shared root cause across those reports too.

## Architecture

```
app/src/main/java/com/pelotom89/wingman/
  sdk/            DJI SDK/telemetry — connection, VirtualStick command loop, perception, camera preview, gimbal
  flightcontrol/  state machine, GPS-following command math, obstacle safety clamp, hard safety limits, manual override
  location/       phone GPS as subject-position proxy
  ui/             Compose UI — aircraft camera preview (display-only), HUD, start-following/override buttons
  core/           shared dispatchers, structured flight logging
```

`flightcontrol/FlightStateMachine.kt` is the only place flight *policy* lives — every
other layer is a dumb data source or a dumb actuator. States: `Idle`, `ManualOverride`,
`Following`, `ReturnToHome`, `EmergencyStop`. There is only one active tracking mode now —
no vision/GPS transition or debounce logic, since GPS is the only signal.

`Following` holds a standoff distance from the subject (`FlightCommandCalculator.
computeFollowCommand` — proportional approach/backoff with a dead-band, not "close the gap
to zero") while the aircraft yaws to face them and, independently, the gimbal pitches down
at their ground-level position (`computeGimbalPitchDegrees`, forwarded straight to
`sdk/GimbalController.kt` from `WingmanViewModel`, outside the VirtualStick command path).
The aircraft's camera preview (`ui/CameraPreviewScreen.kt`) is shown to the operator purely
for situational awareness — nothing in the flight-control path reads from it.

## What's actually tested, and what isn't

There is no meaningful unit-test story for the flight-command loop itself — it's live
sensor timing plus hardware physics, and needs the milestone progression below instead.
What **is** unit tested (`app/src/test/`), because it's pure logic with no SDK dependency:
`FlightCommandCalculator`'s standoff-distance approach/backoff/heading-alignment math and
`computeGimbalPitchDegrees`' clamping, `ObstacleSafetyClamp`'s clamping math, and
`SafetyLimits`' threshold/geofence/haversine math. Run `./gradlew test` before touching any
of these files.

Nothing about GPS-only following has been exercised on real hardware yet — see "Known
gaps" below.

## Milestones — build-and-test progression

Don't skip ahead on real hardware; each stage exists because the previous one is a
prerequisite for testing it safely.

1. **SDK connect + telemetry + manual VirtualStick smoke test.** Props-off bench test,
   then MSDK V5's built-in flight simulator for a hover test.
2. **Following logic, open field, low speed/altitude, no gimbal trust yet.** Set
   `SafetyLimits` very conservatively (~2-3 m/s, 5-8m altitude) to start; confirm the
   aircraft actually approaches/backs off toward the standoff distance and yaws to track a
   walking (not cycling) subject before trying anything faster.
3. **Gimbal aiming validated against real DJI pitch convention.** `computeGimbalPitchDegrees`
   assumes 0° = level, negative = down — confirm that's actually how `GimbalKey.
   KeyRotateByAngle` behaves on this gimbal before trusting the sign in flight; a flipped
   sign here means the gimbal drives the wrong direction under real conditions.
4. **Obstacle clamp validated deliberately before trusting it in following flight** —
   low-speed approach toward a soft/known obstacle with a spotter and prop guards,
   confirming the clamp actually stops/redirects before contact. This is the most
   safety-critical milestone; don't rush it.
5. **Combined behavior at realistic speed** — following a cyclist at riding speed, open
   road/trail, spotter present, conservative altitude and standoff distance first.

## Known gaps (not yet implemented / not yet verified)

- **GPS-only following has not been flown at all.** Everything above the unit-test layer
  (`FlightCommandCalculator`, `FlightStateMachine`, gimbal wiring) compiles and passes pure
  logic tests but has zero real-hardware verification — that's the entire milestone list
  above, still outstanding.
- Gimbal pitch sign convention (`computeGimbalPitchDegrees`'s 0°=level/negative=down
  assumption, and `GimbalController.rotateTo`'s use of `GimbalAngleRotationMode.
  ABSOLUTE_ANGLE`) is unverified against the real Mini 4 Pro gimbal — see Milestone 3
  above.
- Perception data's units and ring-indexing convention are assumptions, not confirmed
  facts — see `sdk/PerceptionRepository.kt`'s header comment. Log raw `ObstacleData`
  values against a known real-world distance/direction before trusting
  `ObstacleSafetyClamp`'s thresholds in flight.
- The standoff-distance target (10m) and tolerance (2m) in `FlightCommandCalculator` are
  untested starting guesses, not tuned values — expect to adjust both after Milestone 2.

`ReturnToHome`/`EmergencyStop` DO trigger real aircraft behavior —
`sdk/FlightSafetyActionsController.kt` calls the native `KeyStartGoHome`/
`KeyStartAutoLanding` action keys, wired from `WingmanViewModel` on state-class transition
(not from `FlightStateMachine` itself, to keep that class testable without an SDK
dependency).
