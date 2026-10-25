package com.f1circle.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.f1circle.app.data.LiveSessionBuilder
import com.f1circle.app.data.LiveTimingClient
import com.f1circle.app.data.SessionData
import com.f1circle.app.model.CircleFrame
import com.f1circle.app.model.CircleModel
import com.f1circle.app.model.smoothProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The live feed turned into circle frames, held [delayMs] behind live timing so the circle lines
 * up with a video stream. The delay is kept between runs, since a given stream tends to run the
 * same distance behind each time.
 */
class LiveCircle(private val settings: Settings) {
    private val builder = LiveSessionBuilder()
    var connection by mutableStateOf("Connecting…")
        private set
    private val client = LiveTimingClient(builder) { connection = it }

    var data by mutableStateOf<SessionData?>(null)
        private set
    var model by mutableStateOf<CircleModel?>(null)
        private set
    var frame by mutableStateOf<CircleFrame?>(null)
        private set
    var display by mutableStateOf<Map<Int, Double>>(emptyMap())
        private set

    // While paused the picture holds still, so the delay grows; resuming carries on from the
    // same moment, like pausing a stream
    var delayMs by mutableLongStateOf(settings.delayMs)
        private set
    var paused by mutableStateOf(false)
        private set
    /** Bumped on every delay or pause change, so the overlay can flash its status. */
    var changes by mutableIntStateOf(0)
        private set
    private var snapNext = true

    var selected by mutableStateOf<Int?>(null)

    val started: Boolean get() = builder.started

    fun start() = client.start()

    fun stop() = client.stop()

    /** Moves the circle [ms] further behind live, or closer for a negative value; live is the limit. */
    fun shift(ms: Long) {
        delayMs = maxOf(0L, delayMs + ms)
        changed()
    }

    fun togglePause() {
        paused = !paused
        changed()
    }

    fun goLive() {
        delayMs = 0
        paused = false
        changed()
    }

    private fun changed() {
        snapNext = true
        settings.delayMs = delayMs
        changes++
    }

    /** Steps the selection through the running order: +1 to the car behind, -1 to the car ahead. */
    fun cycleSelection(step: Int) {
        val cars = frame?.cars?.sortedBy { it.position ?: Int.MAX_VALUE }.orEmpty()
        if (cars.isEmpty()) return
        val i = cars.indexOfFirst { it.driver.number == selected }
        val next = if (i < 0) (if (step > 0) 0 else cars.lastIndex) else (i + step).mod(cars.size)
        selected = cars[next].driver.number
    }

    fun statusText(): String = when {
        connection != "Connected" -> connection
        paused -> "Paused · ${seconds(delayMs)} behind"
        delayMs >= 100 -> "${seconds(delayMs)} behind live"
        else -> "Live"
    }

    suspend fun run() = coroutineScope {
        // The model is rebuilt from everything received so far once a second; frames in between
        // are computed from it at the current moment
        launch {
            while (true) {
                val built = withContext(Dispatchers.Default) {
                    val d = builder.snapshot()
                    if (d.drivers.isEmpty()) null else d to CircleModel(d)
                }
                built?.let {
                    data = it.first
                    model = it.second
                }
                delay(1_000)
            }
        }
        var last = 0L
        var lastView = 0L
        while (true) {
            withFrameNanos { now ->
                val m = model
                val realMs = if (last == 0L) 0.0 else (now - last) / 1e6
                if (paused) delayMs += realMs.toLong()
                val view = client.serverNow() - delayMs
                if (m != null) {
                    val f = m.frame(view)
                    val raceMs = if (lastView == 0L) 0.0 else (view - lastView).toDouble()
                    display = smoothProgress(display, f, realMs, raceMs, snap = snapNext)
                    snapNext = false
                    frame = f
                }
                last = now
                lastView = view
            }
        }
    }
}

fun seconds(ms: Long): String = String.format(Locale.US, "%.1f s", ms / 1000.0)
