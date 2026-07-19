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
custom replacement built from raw per-direction distance telemetry
(`IPerceptionManager`), and it is the single most safety-critical file in this repo. See
its header comment and `ObstacleSafetyClampTest.kt` before changing it.

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
4. Open the project in Android Studio and let it sync — this project does not commit a
   Gradle wrapper jar/scripts (no local Gradle install was available to generate one
   correctly); Android Studio regenerates `gradlew`/`gradlew.bat`/`gradle-wrapper.jar` on
   first sync. If you're not using Android Studio, run `gradle wrapper` once with a local
   Gradle 8.9 install instead.
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
- `sdk/VideoFeedRepository.kt`'s `toBitmapOrNull()` is unimplemented — needs the actual
  frame `format` MSDK V5 delivers (commonly NV21/YUV420) confirmed before wiring frames
  into the vision pipeline.
- `ui/MainActivity.kt`'s tap-to-select callback doesn't yet pass the current video frame
  into `TapToSelectHandler.onBoxSelected` — needs a "latest frame" holder shared between
  `CameraPreviewScreen` and the gesture handler.
- `FlightStateMachine`'s `ReturnToHome`/`EmergencyStop` states zero the VirtualStick output
  but don't yet trigger the aircraft's native go-home/land behavior
  (`FlightController.startGoHome()`/`startLanding()`, intentionally independent of the
  VirtualStick path) — needed before this is safe to rely on for a real battery/geofence
  escalation.
- Every DJI SDK call in `sdk/` and `ui/CameraPreviewScreen.kt` follows the documented MSDK
  V5 pattern from research but hasn't been compiled against the real SDK jar — expect to
  need small signature fixes on first build. Each file flags this in its own header
  comment.
