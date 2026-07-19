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
5. The MediaPipe `efficientdet_lite0.tflite` person-detection model is committed at
   `app/src/main/assets/efficientdet_lite0.tflite` (pulled from
   `storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/`).
   Swap it for a different MediaPipe-compatible detector if you want — see
   `vision/SubjectDetector.kt`.
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
launched on a real device (Moto G Play 2026, Android 16) and **the UI actually renders** —
`PreflightChecklistScreen` shows up correctly, including both checklist items correctly
gated as not-ready (no DJI key/aircraft/GPS in that bench test) and the "Begin flight"
button correctly disabled. See the next section for the launch-crash history and fix. What
still hasn't been verified: DJI SDK *registration itself* succeeding (as opposed to
reaching the registration call, which does work) — that needs a real DJI developer key and
a connected RC-N2/aircraft, and hasn't been tested yet.

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
  vision/         on-device subject detection + tracking (+ PhoneCameraFrameSource, test-only)
  flightcontrol/  state machine, obstacle safety clamp, hard safety limits, manual override
  location/       phone GPS as subject-position proxy
  ui/             Compose UI — camera preview, tap-to-select, HUD, override button, VisionTestScreen
  core/           shared dispatchers, structured flight logging, YUV->Bitmap conversion
```

`flightcontrol/FlightStateMachine.kt` is the only place flight *policy* lives — every
other layer is a dumb data source or a dumb actuator. States: `Idle`, `ManualOverride`,
`VisualTrack`, `GpsGuided`, `ReturnToHome`, `EmergencyStop`. The `VisualTrack ⇄ GpsGuided`
transition is debounced (`TrackingLossDebouncer.kt`) so a single dropped frame doesn't flap
the aircraft's behavior.

`vision/` is deliberately decoupled from where frames come from — `SubjectTracker.onFrame()`
just takes a `Bitmap`. Two independent frame sources feed it: `sdk/VideoFeedRepository`
(the real DJI aircraft camera stream) for actual flight, and `vision/PhoneCameraFrameSource`
(CameraX, the phone's own camera) used ONLY by `ui/VisionTestScreen` so the detect/track
pipeline is testable with no drone connected at all — see Milestone 2 below.

## What's actually tested, and what isn't

There is no meaningful unit-test story for the flight-command loop itself — it's live
sensor timing plus hardware physics, and needs the milestone progression below instead.
What **is** unit tested (`app/src/test/`, 32 tests), because it's pure logic with no SDK
dependency: `ObstacleSafetyClamp`'s clamping math, `SafetyLimits`' threshold/geofence/
haversine math, `TrackingLossDebouncer`'s hysteresis timing, `SubjectTracker`'s
detection-matching logic, and `TemplateMatchBoxTracker`'s template-search math. Run
`./gradlew test` before touching any of these files.

Beyond unit tests: the vision pipeline (`SubjectDetector` + `SubjectTracker` +
`TemplateMatchBoxTracker`) has been exercised **on a real device** end-to-end via
`VisionTestScreen` — tap-to-select seeding a subject, sustained tracking, FPS readout —
verified stable for 35+ continuous seconds with zero crashes (Moto G Play 2026). This is
the same `vision/` code the real DJI flight path uses; only the frame *source* differs, so
this is meaningful evidence beyond "it compiles," even though the DJI camera path itself
hasn't been exercised yet (needs a connected aircraft).

## Milestones — build-and-test progression

Don't skip ahead on real hardware; each stage exists because the previous one is a
prerequisite for testing it safely.

1. **SDK connect + telemetry + manual VirtualStick smoke test.** Props-off bench test,
   then MSDK V5's built-in flight simulator for a hover test.
2. **Vision pipeline standalone, no flight control wired.** ✅ Achievable and verified
   today with zero DJI hardware — open `VisionTestScreen` (button on the preflight
   screen), drag a box around yourself, watch it track. When an aircraft is available,
   also re-verify against the real DJI camera stream, not just the phone's own camera.
3. **VisualTrack flight logic, open field, low speed/altitude.** Set `SafetyLimits` very
   conservatively (~2-3 m/s, 5-8m altitude) to start.
4. **GpsGuided fallback via deliberate occlusion.** Real hardware, spotter present.
5. **Combined state machine**, both transitions live under realistic conditions.
6. **Obstacle clamp validated deliberately before trusting it in tracking flight** —
   low-speed approach toward a soft/known obstacle with a spotter and prop guards,
   confirming the clamp actually stops/redirects before contact. This is the most
   safety-critical milestone; don't rush it.

## Known gaps (not yet implemented)

- Perception data's units and ring-indexing convention are assumptions, not confirmed
  facts — see `sdk/PerceptionRepository.kt`'s header comment. Log raw `ObstacleData`
  values against a known real-world distance/direction before trusting
  `ObstacleSafetyClamp`'s thresholds in flight.
- `GimbalController.kt` exists but isn't wired into `WingmanViewModel` or the vision
  pipeline yet — VisualTrack currently re-centers the subject via aircraft yaw/pitch only
  (`FlightCommandCalculator`), not gimbal movement, even though the gimbal path would be
  cheaper/faster for small corrections (see that file's header comment).
- The vision pipeline has only been verified against the phone's own camera
  (`PhoneCameraFrameSource`), not yet the real DJI aircraft camera stream
  (`VideoFeedRepository`) — same `vision/` code either way, but the DJI path itself
  (frame delivery, NV21 format/timing from the actual aircraft) is unverified pending a
  connected drone.
- `vision/SubjectDetector.kt` runs MediaPipe on **CPU delegate, not GPU** — the GPU
  delegate crashed intermittently on-device (Moto G Play 2026) with `ToTensorConverter:
  input data size does not match expected size` after ~25 successful detections, not on
  the first one, pointing at delegate/driver state rather than a per-frame input bug (a
  raw-pixel-array Bitmap normalization was tried and did NOT fix it). CPU verified stable
  for 35+ seconds including active tracking; revisit GPU only if CPU inference proves to
  be an actual bottleneck on real hardware, and expect this to be device-specific — it may
  behave differently on other phones.
- **All vision-pipeline testing so far has been close-up and indoor** (a face a couple
  feet from the phone). The actual deployment target — a rider on a bike, tens of meters
  out, most of their visible surface being helmet/jersey/bike rather than skin — is a
  materially different detection problem that hasn't been tested at all yet:
  `TemplateMatchBoxTracker`'s bridging match is now RGB-color-based specifically for this
  (see its header comment — colored gear against comparatively neutral road/grass/sky is
  the intended signal, not skin tone, which was tried and reverted as actively wrong for a
  geared-up rider), but EfficientDet-Lite0 itself is a small, mobile-optimized model and
  its accuracy on a small/distant "person" instance is unverified and may simply be worse
  than what close-up indoor testing has shown — that's a model-capability question, not
  something the bridging tracker can compensate for. Field-test at realistic range before
  trusting this for anything beyond continued development.

`ReturnToHome`/`EmergencyStop` DO now trigger real aircraft behavior —
`sdk/FlightSafetyActionsController.kt` calls the native `KeyStartGoHome`/
`KeyStartAutoLanding` action keys, wired from `WingmanViewModel` on state-class transition
(not from `FlightStateMachine` itself, to keep that class testable without an SDK
dependency).
