# wingman

Android app that flies a DJI Mini 4 Pro to keep a subject (e.g. a cyclist/streamer) in
frame continuously: on-device vision tracking when they're visually in shot, GPS-guided
flight toward them when they're not.

## Why this app exists, and why it's built the way it is

DJI's own ActiveTrack API does not exist for the Mini 4 Pro — it's exclusive to the legacy
Mobile SDK V4, which only supports older drones (Mavic 2 series, Mavic Air 2/2S, Mini 2,
Phantom 4, Inspire 2). The Mini 4 Pro requires MSDK V5, and V5 has no ActiveTrack
equivalent. So this app builds its own on-device person detection + tracking (`vision/`)
and drives the aircraft via MSDK V5's `VirtualStick` API instead.

That choice has a real consequence worth understanding before flying this: **DJI's own
obstacle avoidance (APAS) is disabled whenever VirtualStick control is active on the
Mini 4 Pro** (unlike the M300/M350/M30 series and Mavic 3E/3M, which keep APAS active
under VirtualStick — a hardware tradeoff this project deliberately chose not to make, to
stay on cheaper/lighter consumer hardware). `flightcontrol/ObstacleSafetyClamp.kt` is a
custom replacement built from raw obstacle-distance telemetry (`PerceptionManager` /
`ObstacleData`), and it is the single most safety-critical file in this repo. The real API
doesn't give discrete forward/backward/left/right readings — just a ring of horizontal
distance samples around the aircraft plus separate up/down values — so the clamp samples
the ring at the aircraft's actual bearing of travel; see `sdk/PerceptionRepository.kt`'s
header comment for two assumptions (sample units, ring indexing) that are flagged as
unverified against real hardware and need confirming before the clamp's distances can be
trusted. Read `ObstacleSafetyClamp.kt`'s header comment and `ObstacleSafetyClampTest.kt`
before changing either.

**The subject's GPS position, used when vision tracking loses them, comes from this
phone's own GPS chip** (`location/SubjectLocationProvider.kt`) — the phone stays with the
cyclist (mounted on/carried with the RC-N2 they're operating) throughout the flight, so
there's no separate beacon, companion app, or backend involved.

## Regulatory note — read before flying

Under FAA Part 107 (or your local equivalent), the remote pilot must keep the **aircraft**
itself within visual line of sight at all times, independent of whether the **subject** is
in frame. This app's GPS-guided fallback exists to keep the subject framed when the camera
loses them — it does not and cannot relax that obligation on the drone itself. The app
surfaces an explicit acknowledgment of this on every session's preflight screen
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
5. Download an `efficientdet_lite0.tflite` model (or your preferred MediaPipe-compatible
   person detector) into `app/src/main/assets/` — not committed as a binary in this repo.
   See `vision/SubjectDetector.kt`.
6. Real device only. MSDK V5 will not run meaningfully in the emulator (no USB accessory,
   no aircraft radio link) — you need an actual Android phone connected to an RC-N2.

## Build / run

Standard Android Studio project — `Run` on a connected device, or:

```
./gradlew assembleDebug
./gradlew test          # unit tests — see "What's actually tested" below
```

## Build status

`assembleDebug` and `test` have both been run and pass against the real MSDK V5 5.18.0 jar
(pulled from Maven Central — no special DJI repository needed, `mavenCentral()` alone
resolves `com.dji:*`) and the real MediaPipe 0.10.14 jar, not just written from
documentation. All 28 unit tests pass. Beyond that: the app has been installed and
launched on a real device (Moto G Play 2026, Android 16), and runs through
`WingmanApplication.onCreate()` and DJI SDK registration setup into `MainActivity` — see
the next section for the launch-crash history and fix. It still needs the MediaPipe model
asset (Setup step 5) to get further than `MainActivity`'s `WingmanViewModel` construction,
and DJI SDK *registration itself* (as opposed to reaching the registration call) hasn't
been verified yet — that needs a real DJI developer key and hasn't been tested.

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
  sdk/            DJI SDK/telemetry — connection, VirtualStick command loop, perception, video, gimbal
  vision/         on-device subject detection + tracking
  flightcontrol/  state machine, obstacle safety clamp, hard safety limits, manual override
  location/       phone GPS as subject-position proxy
  ui/             Compose UI — camera preview, tap-to-select, HUD, override button
  core/           shared dispatchers, structured flight logging
```

`flightcontrol/FlightStateMachine.kt` is the only place flight *policy* lives — every
other layer is a dumb data source or a dumb actuator. States: `Idle`, `ManualOverride`,
`VisualTrack`, `GpsGuided`, `ReturnToHome`, `EmergencyStop`. The `VisualTrack ⇄ GpsGuided`
transition is debounced (`TrackingLossDebouncer.kt`) so a single dropped frame doesn't flap
the aircraft's behavior.

## What's actually tested, and what isn't

There is no meaningful unit-test story for the flight-command loop itself — it's live
sensor timing plus hardware physics, and needs the milestone progression below instead.
What **is** unit tested (`app/src/test/`), because it's pure logic with no SDK dependency:
`ObstacleSafetyClamp`'s clamping math, `SafetyLimits`' threshold/geofence/haversine math,
`TrackingLossDebouncer`'s hysteresis timing, and `SubjectTracker`'s detection-matching
logic. Run `./gradlew test` before touching any of these files.

## Milestones — build-and-test progression

Don't skip ahead on real hardware; each stage exists because the previous one is a
prerequisite for testing it safely.

1. **SDK connect + telemetry + manual VirtualStick smoke test.** Props-off bench test,
   then MSDK V5's built-in flight simulator for a hover test.
2. **Vision pipeline standalone, no flight control wired.** Tap-to-select → detect → track,
   check latency/frame-drop against live decode load. No aircraft flight needed.
3. **VisualTrack flight logic, open field, low speed/altitude.** Set `SafetyLimits` very
   conservatively (~2-3 m/s, 5-8m altitude) to start.
4. **GpsGuided fallback via deliberate occlusion.** Real hardware, spotter present.
5. **Combined state machine**, both transitions live under realistic conditions.
6. **Obstacle clamp validated deliberately before trusting it in tracking flight** —
   low-speed approach toward a soft/known obstacle with a spotter and prop guards,
   confirming the clamp actually stops/redirects before contact. This is the most
   safety-critical milestone; don't rush it.

## Known gaps (not yet implemented)

- `vision/SubjectTracker.kt`'s `CoastingBoxTracker` is an honest placeholder (assumes no
  motion between detections) — swap in a real frame-to-frame tracker (OpenCV CSRT/KCF, or
  Lucas-Kanade optical flow) before Milestone 2 testing; coasting will drift badly on
  anything but a near-stationary subject.
- `sdk/VideoFeedRepository.kt`'s `toBitmapOrNull()` is unimplemented. The frame format IS
  now confirmed (NV21, requested explicitly in `addFrameListener`) — what's missing is the
  actual NV21 -> Bitmap conversion, needed before frames can reach the vision pipeline.
- `ui/MainActivity.kt`'s tap-to-select callback doesn't yet pass the current video frame
  into `TapToSelectHandler.onBoxSelected` — needs a "latest frame" holder shared between
  `CameraPreviewScreen` and the gesture handler.
- Perception data's units and ring-indexing convention are assumptions, not confirmed
  facts — see `sdk/PerceptionRepository.kt`'s header comment. Log raw `ObstacleData`
  values against a known real-world distance/direction before trusting
  `ObstacleSafetyClamp`'s thresholds in flight.
- `GimbalController.kt` exists but isn't wired into `WingmanViewModel` or the vision
  pipeline yet — VisualTrack currently re-centers the subject via aircraft yaw/pitch only
  (`FlightCommandCalculator`), not gimbal movement, even though the gimbal path would be
  cheaper/faster for small corrections (see that file's header comment).

`ReturnToHome`/`EmergencyStop` DO now trigger real aircraft behavior —
`sdk/FlightSafetyActionsController.kt` calls the native `KeyStartGoHome`/
`KeyStartAutoLanding` action keys, wired from `WingmanViewModel` on state-class transition
(not from `FlightStateMachine` itself, to keep that class testable without an SDK
dependency).
