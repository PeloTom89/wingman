package com.pelotom89.wingman.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Deliberately separate dispatchers per concern rather than sharing Dispatchers.Default
 * everywhere: the VirtualStick command loop's timing is safety-critical (see
 * sdk/VirtualStickController.kt) and must not be starved by a slow vision inference pass
 * or video decode, both of which are the heaviest CPU/GPU consumers in the app.
 */
object WingmanDispatchers {
    /** VirtualStick command loop only — nothing else should be scheduled here. */
    val flightControl: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wingman-flight-control")
    }.asCoroutineDispatcher()

    /** Vision detection + tracking pipeline. */
    val vision: CoroutineDispatcher = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "wingman-vision")
    }.asCoroutineDispatcher()

    /** Video decode / frame delivery. */
    val videoDecode: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** Telemetry, GPS, and other light I/O-bound listeners. */
    val telemetry: CoroutineDispatcher = Dispatchers.IO
}
