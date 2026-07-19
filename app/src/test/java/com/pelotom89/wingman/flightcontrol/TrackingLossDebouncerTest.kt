package com.pelotom89.wingman.flightcontrol

import com.pelotom89.wingman.vision.BoundingBox
import com.pelotom89.wingman.vision.TrackingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises the hysteresis the plan calls out explicitly: a single dropped frame must NOT
 * flip VisualTrack -> GpsGuided, only sustained loss should. Uses a fake clock so these
 * run instantly rather than sleeping for real seconds.
 */
class TrackingLossDebouncerTest {

    private val box = BoundingBox(0.5f, 0.5f, 0.2f, 0.2f)

    @Test
    fun `single dropped frame within the debounce window does not switch to GpsGuided`() {
        var clock = 0L
        val debouncer = TrackingLossDebouncer(lostThresholdMillis = 2000, nowMillis = { clock })

        val result1 = debouncer.onTick(TrackingResult.Lost(1, box), FlightState.VisualTrack)
        clock += 500
        val result2 = debouncer.onTick(TrackingResult.Tracking(box, 1f), FlightState.VisualTrack)

        assertNull("should not have switched yet", result1)
        assertNull("regained tracking before threshold, should stay VisualTrack", result2)
    }

    @Test
    fun `sustained loss past the threshold switches to GpsGuided`() {
        var clock = 0L
        val debouncer = TrackingLossDebouncer(lostThresholdMillis = 2000, nowMillis = { clock })

        debouncer.onTick(TrackingResult.Lost(1, box), FlightState.VisualTrack) // starts the clock
        clock += 2500
        val result = debouncer.onTick(TrackingResult.Lost(50, box), FlightState.VisualTrack)

        assertEquals(FlightState.GpsGuided, result)
    }

    @Test
    fun `brief reacquisition flicker while in GpsGuided does not bounce back immediately`() {
        var clock = 0L
        val debouncer = TrackingLossDebouncer(reacquireThresholdMillis = 800, nowMillis = { clock })

        val result1 = debouncer.onTick(TrackingResult.Tracking(box, 1f), FlightState.GpsGuided)
        clock += 200
        val result2 = debouncer.onTick(TrackingResult.Lost(1, box), FlightState.GpsGuided)

        assertNull("brief flicker should not switch back yet", result1)
        assertNull("loss resets the reacquire timer, stays GpsGuided", result2)
    }

    @Test
    fun `sustained reacquisition past the threshold switches back to VisualTrack`() {
        var clock = 0L
        val debouncer = TrackingLossDebouncer(reacquireThresholdMillis = 800, nowMillis = { clock })

        debouncer.onTick(TrackingResult.Tracking(box, 1f), FlightState.GpsGuided) // starts the clock
        clock += 1000
        val result = debouncer.onTick(TrackingResult.Tracking(box, 1f), FlightState.GpsGuided)

        assertEquals(FlightState.VisualTrack, result)
    }

    @Test
    fun `states other than VisualTrack or GpsGuided are left untouched`() {
        val debouncer = TrackingLossDebouncer()

        val result = debouncer.onTick(TrackingResult.Lost(100, box), FlightState.Idle)

        assertNull(result)
    }
}
