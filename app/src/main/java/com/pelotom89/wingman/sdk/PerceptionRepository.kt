package com.pelotom89.wingman.sdk

import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleData
import dji.v5.manager.aircraft.perception.listener.ObstacleDataListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.roundToInt

/**
 * Raw obstacle distance telemetry. This exists specifically because DJI's own APAS
 * obstacle avoidance is confirmed DISABLED whenever VirtualStick control is active on the
 * Mini 4 Pro — see flightcontrol/ObstacleSafetyClamp.kt, which is what actually consumes
 * this data to replace that missing safety net.
 *
 * CORRECTED against the real MSDK V5 jar (dji-sdk-v5-aircraft-provided:5.18.0): the API
 * does NOT expose discrete forward/backward/left/right readings the way an earlier draft
 * of this file assumed. `ObstacleData` (delivered via `ObstacleDataListener`, not
 * `PerceptionInformationListener` — that listener instead reports configured warning/
 * braking thresholds and per-sensor "is it working" booleans, not live distances) gives:
 *  - a RING of horizontal distance samples (`getHorizontalObstacleDistance(): List<Int>`)
 *    spaced `getHorizontalAngleInterval()` degrees apart around the aircraft,
 *  - a single upward distance (`getUpwardObstacleDistance(): Int`),
 *  - a single downward distance (`getDownwardObstacleDistance(): Int`).
 *
 * UNVERIFIED ASSUMPTIONS flagged explicitly rather than silently guessed, since this feeds
 * a safety-critical clamp — confirm both against real hardware output before flight:
 *  1. Units: the raw values are `Int`, DJI's own docs weren't accessible from bytecode
 *     alone. [RAW_DISTANCE_UNIT_METERS] assumes centimeters; verify against real sensor
 *     output (e.g. log raw values while walking a known distance in front of the aircraft)
 *     before trusting the braking/warning thresholds in ObstacleSafetyClamp.
 *  2. Ring indexing: assumed index 0 = aircraft nose (forward), increasing clockwise.
 *     Confirm this against real hardware — e.g. walk directly in front of a stationary,
 *     armed aircraft and check that the shortest distance lands near index 0.
 */
class PerceptionRepository {

    val obstacleSnapshotFlow: Flow<ObstacleSnapshot> = callbackFlow {
        val listener = ObstacleDataListener { data -> trySend(data.toSnapshot()) }
        PerceptionManager.getInstance().addObstacleDataListener(listener)
        awaitClose { PerceptionManager.getInstance().removeObstacleDataListener(listener) }
    }

    private fun ObstacleData.toSnapshot(): ObstacleSnapshot = ObstacleSnapshot(
        horizontalDistancesMeters = horizontalObstacleDistance.map { it * RAW_DISTANCE_UNIT_METERS },
        horizontalAngleIntervalDegrees = horizontalAngleInterval.toDouble(),
        upwardDistanceMeters = upwardObstacleDistance * RAW_DISTANCE_UNIT_METERS,
        downwardDistanceMeters = downwardObstacleDistance * RAW_DISTANCE_UNIT_METERS,
    )

    private companion object {
        /** UNVERIFIED — see class header. Assumes raw units are centimeters. */
        const val RAW_DISTANCE_UNIT_METERS = 0.01
    }
}

data class ObstacleSnapshot(
    val horizontalDistancesMeters: List<Double>,
    val horizontalAngleIntervalDegrees: Double,
    val upwardDistanceMeters: Double,
    val downwardDistanceMeters: Double,
) {
    /**
     * Nearest obstacle distance along [bearingDegrees] (0 = nose/forward, 90 = right,
     * clockwise) — see the ring-indexing assumption flagged in this file's header. Returns
     * null if no ring data is available yet (e.g. before the first listener callback).
     */
    fun horizontalDistanceAtBearing(bearingDegrees: Double): Double? {
        if (horizontalDistancesMeters.isEmpty() || horizontalAngleIntervalDegrees <= 0) return null
        val normalized = ((bearingDegrees % 360) + 360) % 360
        val index = (normalized / horizontalAngleIntervalDegrees).roundToInt() % horizontalDistancesMeters.size
        return horizontalDistancesMeters[index]
    }

    companion object {
        val EMPTY = ObstacleSnapshot(emptyList(), 0.0, Double.MAX_VALUE, Double.MAX_VALUE)
    }
}
