package com.pelotom89.wingman.flightcontrol

import android.util.Log
import com.pelotom89.wingman.location.LocationFix
import com.pelotom89.wingman.sdk.AircraftTelemetry
import com.pelotom89.wingman.sdk.ObstacleSnapshot
import com.pelotom89.wingman.sdk.VirtualStickCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The single place flight-control POLICY lives — sdk/telemetry and location/ are
 * deliberately dumb data sources that know nothing about tracking state or safety, so this
 * is the only class that decides what the aircraft (and gimbal) should do next and why.
 * Every VirtualStick command this emits has already passed through [SafetyLimits] and
 * [ObstacleSafetyClamp] before [commandFlow] reaches VirtualStickController — nothing
 * downstream re-checks safety, so nothing upstream of this class should ever be trusted
 * to skip it.
 *
 * GPS-only: vision-based tracking (SubjectTracker et al.) was removed after real-world
 * testing raised doubts about detecting a small, fast, distant subject reliably — see
 * README. The subject's position is always the controller phone's own GPS fix (it stays
 * with the cyclist), and [FlightCommandCalculator.computeFollowCommand] holds a standoff
 * distance rather than closing the gap to zero.
 */
class FlightStateMachine(
    private val telemetryFlow: Flow<AircraftTelemetry>,
    private val obstacleSnapshotFlow: Flow<ObstacleSnapshot>,
    private val locationFixFlow: Flow<LocationFix?>,
    private val manualOverrideActiveFlow: Flow<Boolean>,
    private val safetyLimits: SafetyLimits = SafetyLimits(),
    private val obstacleSafetyClamp: ObstacleSafetyClamp = ObstacleSafetyClamp(),
    private val commandCalculator: FlightCommandCalculator = FlightCommandCalculator(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val _flightStateFlow = MutableStateFlow<FlightState>(FlightState.Idle)
    val flightStateFlow: StateFlow<FlightState> get() = _flightStateFlow.asStateFlow()

    private val _commandFlow = MutableStateFlow(VirtualStickCommand.ZERO)
    val commandFlow: StateFlow<VirtualStickCommand> get() = _commandFlow.asStateFlow()

    /** Target gimbal pitch, degrees (0 = level, negative = looking down — see
     *  computeGimbalPitchDegrees). Independent of commandFlow: WingmanViewModel forwards
     *  this to GimbalController directly, not through VirtualStickController. */
    private val _gimbalPitchDegreesFlow = MutableStateFlow(0.0)
    val gimbalPitchDegreesFlow: StateFlow<Double> get() = _gimbalPitchDegreesFlow.asStateFlow()

    private var launchPoint: LatLon? = null

    /** Called once, at takeoff — anchors the geofence check in [SafetyLimits]. */
    fun armLaunchPoint(point: LatLon) {
        launchPoint = point
    }

    /** Operator action: begin GPS following once armed. */
    fun startFollowing() {
        if (_flightStateFlow.value == FlightState.Idle) {
            _flightStateFlow.value = FlightState.Following
        }
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(
                telemetryFlow,
                obstacleSnapshotFlow,
                locationFixFlow,
                manualOverrideActiveFlow,
            ) { telemetry, obstacles, locationFix, overrideActive ->
                Tick(telemetry, obstacles, locationFix, overrideActive)
            }.collect { tick -> process(tick) }
        }
    }

    private var lastLoggedOverride: Boolean? = null

    private fun process(tick: Tick) {
        if (tick.overrideActive != lastLoggedOverride) {
            Log.i("WingmanUI", "tick.overrideActive changed to ${tick.overrideActive}")
            lastLoggedOverride = tick.overrideActive
        }
        if (tick.overrideActive) {
            Log.i("WingmanUI", "process() saw overrideActive=true, switching to ManualOverride")
            _flightStateFlow.value = FlightState.ManualOverride
            _commandFlow.value = VirtualStickCommand.ZERO
            return
        }

        // Safety escalations take priority over whatever following logic would otherwise
        // decide — any state -> ReturnToHome on battery threshold or geofence breach.
        val batteryStatus = safetyLimits.batteryStatus(tick.telemetry.batteryPercent)
        val launch = launchPoint
        val geofenceBreached = launch != null &&
            safetyLimits.isGeofenceBreached(launch, LatLon(tick.telemetry.latitude, tick.telemetry.longitude))

        val escalatedState = when {
            batteryStatus == BatteryStatus.CRITICAL ->
                FlightState.EmergencyStop("Battery critical (${tick.telemetry.batteryPercent}%)")
            batteryStatus == BatteryStatus.RTH_TRIGGER ->
                FlightState.ReturnToHome("Battery at RTH threshold (${tick.telemetry.batteryPercent}%)")
            geofenceBreached -> FlightState.ReturnToHome("Geofence radius exceeded")
            safetyLimits.isAltitudeExceeded(tick.telemetry.altitudeMeters) ->
                FlightState.ReturnToHome("Altitude ceiling exceeded")
            else -> null
        }

        if (escalatedState != null) {
            _flightStateFlow.value = escalatedState
            // RTH/EmergencyStop are intentionally NOT driven via VirtualStick: zero the
            // stick output here and trigger the aircraft's own native go-home/land
            // behavior out-of-band (see sdk/FlightSafetyActionsController.kt, wired from
            // WingmanViewModel on state-class transition, deliberately independent of the
            // VirtualStick path this class owns).
            _commandFlow.value = VirtualStickCommand.ZERO
            return
        }

        val currentState = _flightStateFlow.value
        if (currentState !is FlightState.Following) {
            // Idle, or recovering from a prior ReturnToHome/EmergencyStop: hold position,
            // wait for an explicit operator action (startFollowing()) rather than guessing.
            _commandFlow.value = VirtualStickCommand.ZERO
            return
        }

        val fix = tick.locationFix
        val proposedCommand: VirtualStickCommand
        if (fix == null || fix.isStale(nowMillis())) {
            // No trustworthy subject position — hold, don't guess. Gimbal also holds its
            // last target rather than snapping to level, since a stale fix is more likely
            // a brief GPS hiccup than the subject actually vanishing.
            proposedCommand = VirtualStickCommand.ZERO
        } else {
            val aircraft = LatLon(tick.telemetry.latitude, tick.telemetry.longitude)
            proposedCommand = commandCalculator.computeFollowCommand(
                aircraft = aircraft,
                aircraftHeadingDegrees = tick.telemetry.headingDegrees,
                subject = fix.position,
            )
            _gimbalPitchDegreesFlow.value = computeGimbalPitchDegrees(
                altitudeAglMeters = tick.telemetry.altitudeMeters,
                horizontalDistanceMeters = haversineMeters(aircraft, fix.position),
            )
        }

        val speedLimited = safetyLimits.clampSpeed(proposedCommand)
        val obstacleClamped = obstacleSafetyClamp.clamp(speedLimited, tick.obstacles)

        if (obstacleSafetyClamp.isFullyBlocked(speedLimited, obstacleClamped)) {
            _flightStateFlow.value = FlightState.EmergencyStop("Obstacle clamp blocked all axes of motion")
        }

        _commandFlow.value = obstacleClamped
    }

    private data class Tick(
        val telemetry: AircraftTelemetry,
        val obstacles: ObstacleSnapshot,
        val locationFix: LocationFix?,
        val overrideActive: Boolean,
    )
}
