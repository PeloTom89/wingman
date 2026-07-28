package com.pelotom89.wingman.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pelotom89.wingman.core.FlightLogger
import com.pelotom89.wingman.flightcontrol.FlightState
import com.pelotom89.wingman.flightcontrol.FlightStateMachine
import com.pelotom89.wingman.flightcontrol.LatLon
import com.pelotom89.wingman.flightcontrol.ManualOverrideGate
import com.pelotom89.wingman.flightcontrol.SafetyLimits
import com.pelotom89.wingman.location.SubjectLocationProvider
import com.pelotom89.wingman.sdk.AircraftConnectionRepository
import com.pelotom89.wingman.sdk.FlightSafetyActionsController
import com.pelotom89.wingman.sdk.GimbalController
import com.pelotom89.wingman.sdk.PerceptionRepository
import com.pelotom89.wingman.sdk.SdkRegistrationState
import com.pelotom89.wingman.sdk.VirtualStickCommand
import com.pelotom89.wingman.sdk.VirtualStickController
import com.pelotom89.wingman.sdk.WingmanApplication
import com.pelotom89.wingman.vision.SubjectTrackingRepository
import dji.sdk.keyvalue.value.common.ComponentIndexType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long the FC link may stay down after ProductConnect before the UI calls it stalled
 *  (on healthy runs it was observed coming up within a few seconds of onProductConnect). */
private const val FC_LINK_STALL_TIMEOUT_MS = 20_000L

/** How long EmergencyStop's automatic landing waits for
 *  AircraftConnectionRepository.landingConfirmationNeededFlow to actually report true before
 *  confirming anyway as a fallback -- see FlightSafetyActionsController.confirmLanding(). */
private const val EMERGENCY_LANDING_CONFIRM_TIMEOUT_MS = 30_000L

/** Flight-trace CSV cadence (see FlightLogger). 5Hz is plenty to reconstruct a flight
 *  after the fact without bloating the file. */
private const val FLIGHT_LOG_INTERVAL_MS = 200L

/** Gimbal tracking control loop (velocity P-controller -- see GimbalController). All tunable
 *  by feel on the next flight; conservative first values. */
private const val GIMBAL_LOOP_INTERVAL_MS = 100L        // 10Hz control/read rate
private const val GIMBAL_SMOOTHING_ALPHA = 0.3          // low-pass on the (noisy GPS) target pitch
private const val GIMBAL_PITCH_KP = 3.0                 // deg/s of gimbal speed per deg of error
private const val GIMBAL_MAX_PITCH_SPEED_DEG_S = 25.0   // cap slew rate -- smooth, not frantic
private const val GIMBAL_PITCH_DEADBAND_DEG = 1.0       // within this, hold (let it stabilize)
/** Camera vertical FOV (deg), converts a subject's vertical pixel offset to a gimbal-pitch
 *  correction for vision framing. Approximate for the Mini 4 Pro (~50 deg); it's a response
 *  gain, so exactness isn't critical -- tune by feel. */
private const val GIMBAL_VERTICAL_FOV_DEG = 50.0

/** Horizontal vision framing (aircraft yaw). FOV wider than vertical (~70 deg). Kept gentle
 *  and low-capped for the first flight test of vision-into-flight-control -- yaw only, so
 *  worst case is a little unwanted rotation, never translation. All tunable by feel. */
private const val VISION_HORIZONTAL_FOV_DEG = 70.0
private const val VISION_YAW_KP = 1.2               // deg/s of aircraft yaw per deg of frame error
private const val VISION_YAW_MAX_DEG_S = 20.0       // gentle cap
private const val VISION_YAW_DEADBAND_DEG = 2.0     // hold when roughly centered

/** Cadence/bound for the read-only FC probe while waiting (see
 *  AircraftConnectionRepository.probeFlightControllerLink for what it can and can't do). */
private const val FC_PROBE_INTERVAL_MS = 5_000L
private const val FC_PROBE_MAX_ATTEMPTS = 24

/**
 * Composition root wiring sdk/ + location/ + flightcontrol/ together, and the single
 * object the UI layer observes. Kept intentionally thin: every actual decision (what
 * command to send, when to switch modes) lives in FlightStateMachine, not here — this
 * class only assembles the dependency graph and exposes it as Compose-friendly state.
 *
 * GPS-only: vision/ was removed (see README) — the aircraft camera preview is still shown
 * to the operator (sdk/CameraPreviewScreen) purely for situational awareness, but nothing
 * here reads its frames anymore. Following is driven entirely by the controller phone's
 * own GPS (location/SubjectLocationProvider) and aimed by [GimbalController], not vision.
 */
class WingmanViewModel(application: Application) : AndroidViewModel(application) {

    private val aircraftConnectionRepository = AircraftConnectionRepository()
    private val perceptionRepository = PerceptionRepository()
    private val subjectLocationProvider = SubjectLocationProvider(application)
    private val gimbalController = GimbalController()
    private val subjectTrackingRepository = SubjectTrackingRepository(application)

    private val commandFlowHolder = MutableStateFlow(com.pelotom89.wingman.sdk.VirtualStickCommand.ZERO)
    // Single shared instance -- see ManualOverrideGate's header comment for the real bug
    // (two disconnected flows) this replaced.
    private val overrideActiveHolder = MutableStateFlow(false)
    private val virtualStickController = VirtualStickController(commandFlowHolder, overrideActiveHolder)
    private val manualOverrideGate = ManualOverrideGate(overrideActiveHolder, virtualStickController)
    private val flightLogger = FlightLogger(application)
    private val flightSafetyActionsController = FlightSafetyActionsController()
    private val safetyLimits = SafetyLimits()

    // Declared BEFORE init: the gimbal loop launched in init reads this on Dispatchers.Default
    // and would NPE on a later-declared property (Kotlin initializes in declaration order).
    private val visionFramingEnabledHolder = MutableStateFlow(false)

    // Manual joystick test control (video test screen) -- deliberately bypasses
    // FlightStateMachine entirely, same spirit as onTestTakeoffPressed/onTestLandPressed:
    // a direct, isolated path for confirming manual flight control works, independent of
    // (and never active at the same time UI-wise as) GPS-only Following. commandFlowHolder
    // mirrors whichever source is "live" -- FlightStateMachine normally, or this holder
    // while manual flight is toggled on -- so VirtualStickController still has exactly one
    // command input and SafetyLimits.clampSpeed is still applied to every command that
    // reaches it, same invariant FlightStateMachine.process() maintains for Following.
    private val manualFlightActiveHolder = MutableStateFlow(false)
    private val manualStickCommandHolder = MutableStateFlow(VirtualStickCommand.ZERO)
    val manualFlightActive: StateFlow<Boolean> get() = manualFlightActiveHolder.asStateFlow()

    private val flightStateMachine = FlightStateMachine(
        telemetryFlow = aircraftConnectionRepository.telemetryFlow,
        obstacleSnapshotFlow = perceptionRepository.obstacleSnapshotFlow,
        locationFixFlow = subjectLocationProvider.fixFlow, // Flow<out T> covariance: LocationFix satisfies LocationFix?
        manualOverrideActiveFlow = manualOverrideGate.activeFlow,
    )

    val registrationState: StateFlow<SdkRegistrationState> = WingmanApplication.instance.registrationStateFlow

    val flightState: StateFlow<FlightState> = flightStateMachine.flightStateFlow

    val telemetry = aircraftConnectionRepository.telemetryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Distinct from [registrationState]'s ProductConnected -- see
     *  AircraftConnectionRepository.flightControllerConnectedFlow's header comment. */
    val flightControllerConnected: StateFlow<Boolean> = aircraftConnectionRepository.flightControllerConnectedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** RC-N3 key-channel health, for isolating WHICH hop is broken when the FC link is
     *  down -- see AircraftConnectionRepository.remoteControllerConnectedFlow. */
    val remoteControllerConnected: StateFlow<Boolean> = aircraftConnectionRepository.remoteControllerConnectedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True while autonomous landing is paused awaiting a confirm -- see
     *  FlightSafetyActionsController.confirmLanding()'s header comment. Drives the "Confirm
     *  Landing" button's visibility and is what EmergencyStop's auto-confirm below waits on. */
    val landingConfirmationNeeded: StateFlow<Boolean> = aircraftConnectionRepository.landingConfirmationNeededFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True while SDKManager reports the product (RC-N3 over USB) connected but the
     *  aircraft's flight controller hasn't come up -- the window where either the link is
     *  still establishing (normal for the first few seconds) or the RC firmware is stuck
     *  in the DJI-acknowledged preempted state (Mobile-SDK-Android-V5 issue #427). */
    private val awaitingFlightController: Flow<Boolean> = combine(
        WingmanApplication.instance.registrationStateFlow,
        flightControllerConnected,
    ) { registration, fcConnected ->
        registration is SdkRegistrationState.ProductConnected && !fcConnected
    }.distinctUntilChanged()

    /** Surfaced on the preflight screen: the FC link has been down for
     *  [FC_LINK_STALL_TIMEOUT_MS] straight after ProductConnect, which on this hardware
     *  means it is NOT still establishing -- it's the issue-#427 stuck state, and the
     *  operator needs to act (force-stop DJI Fly, replug USB) rather than keep waiting.
     *  There is deliberately no automatic silver bullet behind this: DJI provides no API
     *  that forces the link up (see AircraftConnectionRepository's header). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val aircraftLinkStalled: StateFlow<Boolean> = awaitingFlightController
        .transformLatest { awaiting ->
            emit(false)
            if (awaiting) {
                delay(FC_LINK_STALL_TIMEOUT_MS)
                emit(true)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        // Persist a 5Hz flight trace to CSV (core/FlightLogger writes to the app's external
        // files dir, which survives being out of ADB range). Previously FlightLogger was
        // constructed but log() was never called -- so nothing was recorded. This is the
        // only post-flight record of what the GPS-following state machine actually did
        // (state, position, command) for a test we can't monitor live outdoors.
        viewModelScope.launch {
            while (true) {
                flightLogger.log(flightState.value, telemetry.value, commandFlowHolder.value)
                delay(FLIGHT_LOG_INTERVAL_MS)
            }
        }
        viewModelScope.launch {
            combine(
                flightStateMachine.commandFlow,
                manualStickCommandHolder,
                manualFlightActiveHolder,
                subjectTrackingRepository.selectedSubject,
            ) { fsmCommand, manualCommand, manualActive, subject ->
                when {
                    manualActive -> manualCommand
                    // HORIZONTAL vision framing: while following with the subject in view,
                    // refine the aircraft YAW to center them left/right in-frame (the gimbal
                    // can't pan enough, so the aircraft rotates). Only the yaw component is
                    // replaced -- pitch/roll (GPS distance-hold) are untouched -- and only
                    // while Following, so safety escalations (RTH/EmergencyStop -> ZERO fsm)
                    // are never overridden. GPS bearing yaw (fsm) is the fallback when no
                    // subject is detected. Gentle + clamped so a bad detection only rotates
                    // the aircraft a little, never translates it.
                    subject != null && flightStateMachine.flightStateFlow.value is FlightState.Following ->
                        fsmCommand.copy(yawDegreesPerSecond = visionFramingYaw(subject))
                    else -> fsmCommand
                }
            }.collect { commandFlowHolder.value = it }
        }
        // Gimbal aiming is independent of the VirtualStick command path entirely -- see
        // FlightCommandCalculator's header comment on why the aircraft yaws to face the
        // subject while the gimbal only ever pitches (no gimbal yaw needed or used).
        // GATED ON Following (2026-07-22): only actuate the gimbal once autonomous flight is
        // actively started -- NEVER before. gimbalPitchDegreesFlow's initial value (0.0)
        // would otherwise fire gimbalController.rotateTo(0.0, 0.0) (a GimbalKey.KeyRotateByAngle
        // performAction) at app launch, before the aircraft is connected. Same root-cause
        // class as the VirtualStick fix: sending ANY aircraft actuation command before/during
        // the connection handshake appears to disrupt it (aircraft LEDs going to error state
        // while Wingman runs, where competitor apps that send nothing pre-flight connect fine).
        // Gimbal tracking is a VELOCITY control loop, not repeated absolute-angle moves (which
        // were jumpy and fought the gimbal's stabilization -- see GimbalController's header).
        // On its own Dispatchers.Default thread so its getValue reads / performActions don't
        // sit on the main thread. Each tick (while Following): smooth the target pitch, read
        // the current pitch, and command a proportional angular velocity toward it -- with a
        // deadband so it holds (and the gimbal stabilizes) once framed. Gated on Following so
        // no gimbal command ever fires pre-connection (same rule as VirtualStick/camera).
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            var smoothedGpsTarget: Double? = null
            var commanding = false
            while (isActive) {
                val following = flightStateMachine.flightStateFlow.value is FlightState.Following
                // Frame during following (real use) OR when vision-framing is toggled on for a
                // ground test. Both are post-connection operator actions, so no gimbal command
                // ever fires pre-connection (same safety rule as VirtualStick/camera).
                val framing = following || visionFramingEnabledHolder.value
                val current = if (framing) gimbalController.currentPitchDegrees() else null
                if (current != null) {
                    val subject = subjectTrackingRepository.selectedSubject.value
                    // VISION target when we can see the subject: point the gimbal so their box
                    // moves to vertical frame-center. Vertical pixel offset -> degrees via the
                    // camera's vertical FOV. This is the GPS-primed refinement -- pixel-precise
                    // framing that corrects GPS's metre-level error.
                    // GPS fallback (only while actually following) when vision has no subject.
                    val target: Double = when {
                        subject != null -> {
                            smoothedGpsTarget = null // re-init GPS smoothing on next fallback
                            current + (0.5 - subject.centerY) * GIMBAL_VERTICAL_FOV_DEG
                        }
                        following -> {
                            val raw = flightStateMachine.gimbalPitchDegreesFlow.value
                            (smoothedGpsTarget?.let { it + GIMBAL_SMOOTHING_ALPHA * (raw - it) } ?: raw)
                                .also { smoothedGpsTarget = it }
                        }
                        else -> current // vision framing toggled on but nobody detected: hold
                    }
                    val error = target - current
                    val speed = if (kotlin.math.abs(error) < GIMBAL_PITCH_DEADBAND_DEG) {
                        0.0
                    } else {
                        (GIMBAL_PITCH_KP * error).coerceIn(-GIMBAL_MAX_PITCH_SPEED_DEG_S, GIMBAL_MAX_PITCH_SPEED_DEG_S)
                    }
                    gimbalController.setPitchSpeed(speed)
                    commanding = true
                } else {
                    if (commanding) {
                        gimbalController.setPitchSpeed(0.0) // stop slewing when framing ends
                        commanding = false
                    }
                    smoothedGpsTarget = null
                }
                delay(GIMBAL_LOOP_INTERVAL_MS)
            }
        }
        // Fires once per transition INTO a new state class (distinctUntilChangedBy keys
        // on the class, not full equality) -- ReturnToHome/EmergencyStop carry a `reason`
        // string that can change tick-to-tick (e.g. battery percent counting down), which
        // would otherwise re-trigger the native action on every tick under plain equality.
        viewModelScope.launch {
            flightState.distinctUntilChangedBy { it::class }.collect { state ->
                when (state) {
                    is FlightState.ReturnToHome -> flightSafetyActionsController.startGoHome()
                    is FlightState.EmergencyStop -> {
                        flightSafetyActionsController.startAutoLanding()
                        // Autonomous landing pauses at a low hover awaiting confirmLanding()
                        // (see that method's header comment) -- without this the aircraft
                        // would just sit there while EmergencyStop's own trigger (critical
                        // battery) keeps draining. Wait for the real signal, but confirm
                        // anyway after a timeout as a fallback rather than depending on the
                        // key read succeeding.
                        launch {
                            withTimeoutOrNull(EMERGENCY_LANDING_CONFIRM_TIMEOUT_MS) {
                                landingConfirmationNeeded.first { it }
                            }
                            flightSafetyActionsController.confirmLanding()
                        }
                    }
                    else -> Unit
                }
            }
        }
        flightStateMachine.start(viewModelScope)
        // NOTE: virtualStickController.start() is deliberately NOT called here anymore.
        // ROOT CAUSE of the connection failure (found 2026-07-22): starting it at app
        // launch immediately calls VirtualStickManager.enableVirtualStick() and then sends
        // sendVirtualStickAdvancedParam() at 10Hz -- a continuous stream of outbound
        // flight-control commands over the phone<->RC USB data channel, BEFORE the aircraft
        // is even connected. No working competitor app (Litchi/Dronelink/Maven/DJI Fly)
        // touches VirtualStick until an autonomous flight is actively started; verified
        // Wingman's DJI setup is otherwise byte-identical to Maven's (same 5.17.0 native
        // SDK, same SecNeo init, same capability assets), yet those apps connect reliably
        // on the exact hardware where Wingman stalled. The 10Hz command spam appears to
        // saturate/monopolize the RC data channel so the SDK's own connection-handshake
        // requests time out (REMOTECONTROLLER REQUEST_TIMEOUT, RC "key channel not
        // responding", FLIGHTCONTROLLER REQUEST_HANDLER_NOT_FOUND). VirtualStick is now
        // started only when the operator presses Start Following (see
        // onStartFollowingPressed) -- flight control is meaningless before that anyway.
    }

    /** Indoor command-path test: toggles the gimbal pitch between level and pointed down so
     *  the operator can see (in the live camera feed) whether aircraft COMMANDS actually
     *  reach the drone -- distinct from telemetry (getValue) reads. Uses the gimbal because
     *  it's visible and safe with no flight. */
    private var testGimbalDown = false

    fun onTestGimbalPressed() {
        testGimbalDown = !testGimbalDown
        val pitch = if (testGimbalDown) -45.0 else 0.0
        Log.i("WingmanUI", "onTestGimbalPressed -> gimbal pitch=$pitch")
        gimbalController.rotateTo(pitch, 0.0)
    }

    /** Indoor/outdoor command-path test: DJI's own autonomous takeoff (see
     *  FlightSafetyActionsController.startTakeoff) -- deliberately NOT routed through
     *  VirtualStickController, since GPS-only following itself hasn't been flight-tested
     *  yet and this is meant to be the lowest-risk way to confirm the aircraft actually
     *  flies on command. Turns manual joystick control off first (see
     *  onManualFlightToggled) -- an active VirtualStick session fighting DJI's own
     *  autonomous takeoff for flight-control authority is a real, observed failure mode
     *  (2026-07-24: Land wouldn't complete after the joystick had been used), not just a
     *  theoretical one. */
    fun onTestTakeoffPressed() {
        Log.i("WingmanUI", "onTestTakeoffPressed")
        if (manualFlightActiveHolder.value) onManualFlightToggled(false)
        flightSafetyActionsController.startTakeoff()
    }

    fun onTestLandPressed() {
        Log.i("WingmanUI", "onTestLandPressed")
        if (manualFlightActiveHolder.value) onManualFlightToggled(false)
        flightSafetyActionsController.startAutoLanding()
    }

    /** Completes a landing DJI has paused at its low confirmation hover -- see
     *  FlightSafetyActionsController.confirmLanding()'s header comment. Deliberately a
     *  separate, explicit operator action for the manual test-Land flow (not auto-sent),
     *  unlike EmergencyStop's landing which auto-confirms after a timeout (see the
     *  EmergencyStop branch above) since that one is safety-triggered by critical battery. */
    fun onConfirmLandingPressed() {
        Log.i("WingmanUI", "onConfirmLandingPressed")
        flightSafetyActionsController.confirmLanding()
    }

    /** Escape hatch out of the landing hover -- see FlightSafetyActionsController.stopAutoLanding. */
    fun onCancelLandingPressed() {
        Log.i("WingmanUI", "onCancelLandingPressed")
        flightSafetyActionsController.stopAutoLanding()
    }

    /** Enables/disables the joystick test control on the video test screen. Starting
     *  VirtualStick here follows the same rule as onStartFollowingPressed -- only ever do
     *  it on an operator action, on a link the operator has already confirmed is healthy
     *  (e.g. Takeoff already worked), never at launch. Disabling zeros the command and
     *  releases VirtualStick control back to the aircraft's own hold behavior, same as
     *  onCleared(). VirtualStickController.start()/stop() are idempotent, so this is safe
     *  to toggle even if Start Following elsewhere also called start(). */
    fun onManualFlightToggled(enabled: Boolean) {
        Log.i("WingmanUI", "onManualFlightToggled($enabled)")
        manualFlightActiveHolder.value = enabled
        if (enabled) {
            // Manual flight uses ANGLE (direct tilt) roll/pitch -- set BEFORE start() so the
            // loop's first send already uses it. See VirtualStickController.rollPitchAngleMode.
            virtualStickController.setRollPitchAngleMode(true)
            virtualStickController.start(viewModelScope)
        } else {
            manualStickCommandHolder.value = VirtualStickCommand.ZERO
            virtualStickController.stop()
            virtualStickController.setRollPitchAngleMode(false)
        }
    }

    /** Joystick input. Manual flight runs roll/pitch in ANGLE mode, so pitch/roll here are
     *  TILT DEGREES (yaw is deg/s, vertical m/s) -- MainActivity scales the normalized stick
     *  by SafetyLimits.maxManualTiltDegrees. Clamped via clampManualAngle (per-axis, angle
     *  units), not clampSpeed (m/s vector) -- manual flight bypasses FlightStateMachine (see
     *  manualFlightActiveHolder's comment above), so this is the one place enforcing manual
     *  limits. */
    fun onManualStickChanged(
        pitchMetersPerSecond: Double,
        rollMetersPerSecond: Double,
        yawDegreesPerSecond: Double,
        verticalMetersPerSecond: Double,
    ) {
        val clamped = safetyLimits.clampManualAngle(
            VirtualStickCommand(
                pitchMetersPerSecond = pitchMetersPerSecond,
                rollMetersPerSecond = rollMetersPerSecond,
                yawDegreesPerSecond = yawDegreesPerSecond,
                verticalMetersPerSecond = verticalMetersPerSecond,
            ),
        )
        // Log only the zero<->non-zero transition (a drag fires this many times/second) --
        // enough to confirm on-device whether stick input is producing real commands at all,
        // without flooding the buffer the way an unconditional per-call log would. Epsilon
        // check, not `== VirtualStickCommand.ZERO`: a released stick can settle on -0.0
        // (e.g. -0f * scale), and data-class equality on Double doesn't treat -0.0 as equal
        // to 0.0 -- that silently wedged this dedup "stuck non-zero" after the first real
        // drag in an on-device test (2026-07-23), hiding every log line after it.
        val isZero = listOf(
            clamped.pitchMetersPerSecond,
            clamped.rollMetersPerSecond,
            clamped.yawDegreesPerSecond,
            clamped.verticalMetersPerSecond,
        ).all { kotlin.math.abs(it) < 1e-6 }
        if (isZero != lastManualCommandWasZero) {
            Log.i("WingmanUI", "onManualStickChanged -> $clamped")
            lastManualCommandWasZero = isZero
        }
        manualStickCommandHolder.value = clamped
    }

    private var lastManualCommandWasZero = true

    /** STOP: exit following AND release VirtualStick, so the aircraft just hovers and BOTH
     *  the physical RC sticks and (after re-enabling manual) the virtual sticks can fly it.
     *  This replaced a ManualOverrideGate.trip() that latched a persistent "override active"
     *  state which zeroed ALL VirtualStick output including manual -- so after STOP the
     *  virtual sticks did nothing until a separate Resume, which the operator (rightly) found
     *  confusing. Releasing VirtualStick instead hands authority straight back to the RC. */
    fun onStopPressed() {
        Log.i("WingmanUI", "onStopPressed")
        flightStateMachine.stopFollowing()
        manualFlightActiveHolder.value = false
        manualStickCommandHolder.value = VirtualStickCommand.ZERO
        virtualStickController.emergencyZero()
        virtualStickController.stop()
        virtualStickController.setRollPitchAngleMode(false)
    }

    // --- Phase 1 vision (person detection over the camera preview + gimbal framing) ---
    val detections = subjectTrackingRepository.detections
    val selectedSubject = subjectTrackingRepository.selectedSubject
    val visionInferenceMs = subjectTrackingRepository.lastInferenceMs

    /** True while vision framing is driving the gimbal for a ground test (see the gimbal loop
     *  in init; the holder is declared up top so init can read it). During real following the
     *  gimbal frames automatically -- this toggle is only for exercising it on the ground. */
    val visionFramingActive: StateFlow<Boolean> get() = visionFramingEnabledHolder.asStateFlow()

    /** Start/stop the detection frame listener with the camera screen's lifecycle. */
    fun startVisionDetection() = subjectTrackingRepository.start(ComponentIndexType.LEFT_OR_MAIN)
    fun stopVisionDetection() {
        subjectTrackingRepository.stop()
        visionFramingEnabledHolder.value = false
    }

    fun onVisionFramingToggled(enabled: Boolean) {
        Log.i("WingmanUI", "onVisionFramingToggled($enabled)")
        visionFramingEnabledHolder.value = enabled
    }

    /** Aircraft yaw rate (deg/s) to center [subject] horizontally in-frame. Horizontal pixel
     *  offset -> degrees via the camera's horizontal FOV; +x (right of center) yaws clockwise
     *  (right) to bring them back, matching the GPS follow-yaw sign convention. Deadband so it
     *  holds when roughly centered, clamped gentle so a bad detection can't swing the aircraft. */
    private fun visionFramingYaw(subject: com.pelotom89.wingman.vision.DetectedSubject): Double {
        val errorDeg = (subject.centerX - 0.5) * VISION_HORIZONTAL_FOV_DEG
        if (kotlin.math.abs(errorDeg) < VISION_YAW_DEADBAND_DEG) return 0.0
        return (errorDeg * VISION_YAW_KP).coerceIn(-VISION_YAW_MAX_DEG_S, VISION_YAW_MAX_DEG_S)
    }

    /** Operator action (a button in MainActivity's flight screen, replacing the old
     *  tap-to-select-a-subject gesture) — the subject is always "whoever is carrying this
     *  phone," so there's nothing to select, just a decision to start. */
    fun onStartFollowingPressed() {
        // If the operator positioned with the app's manual joysticks, turn manual off first
        // -- otherwise the combine keeps routing the (centered = zero) manual command instead
        // of the follow command and the aircraft just sits there. Positioning with the
        // physical RC sticks instead needs none of this: enableVirtualStick (in start below)
        // takes authority from the RC directly.
        if (manualFlightActiveHolder.value) onManualFlightToggled(false)
        flightStateMachine.armLaunchPoint(
            telemetry.value?.let { LatLon(it.latitude, it.longitude) } ?: return,
        )
        // Enable VirtualStick + begin the 10Hz command loop ONLY now, when the operator is
        // actively starting autonomous flight -- never before (see the note in init on why
        // starting it at launch broke the connection). The button that calls this is gated
        // on FlightState.Idle, so this fires once per flight.
        // GPS-following uses VELOCITY roll/pitch (FlightCommandCalculator outputs ground
        // speeds); set it explicitly in case a manual session left ANGLE mode on.
        virtualStickController.setRollPitchAngleMode(false)
        virtualStickController.start(viewModelScope)
        flightStateMachine.startFollowing()
    }

    override fun onCleared() {
        super.onCleared()
        virtualStickController.stop()
        subjectTrackingRepository.close()
        flightLogger.close()
    }
}
