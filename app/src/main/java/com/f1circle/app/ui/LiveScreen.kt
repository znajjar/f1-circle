package com.f1circle.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.f1circle.app.data.LiveSessionBuilder
import com.f1circle.app.data.LiveTimingClient
import com.f1circle.app.data.SessionData
import com.f1circle.app.model.CircleFrame
import com.f1circle.app.model.CircleModel
import com.f1circle.app.model.smoothProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Shift the live picture back in time to line up with a video stream that runs behind timing. */
private const val DELAY_STEP_MS = 1_000L

@Composable
fun LiveScreen(onBack: () -> Unit) {
    val builder = remember { LiveSessionBuilder() }
    var connection by remember { mutableStateOf("Connecting…") }
    val client = remember { LiveTimingClient(builder) { connection = it } }
    DisposableEffect(client) {
        client.start()
        onDispose { client.stop() }
    }

    var data by remember { mutableStateOf<SessionData?>(null) }
    var model by remember { mutableStateOf<CircleModel?>(null) }
    // The model is rebuilt from everything received so far once a second; frames in between
    // are computed from it at the current moment
    LaunchedEffect(builder) {
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

    // How far behind live the circle is. While paused the picture holds still, so the delay
    // grows; resuming carries on from the same moment, like pausing a stream.
    var delayMs by remember { mutableLongStateOf(0L) }
    var paused by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<CircleFrame?>(null) }
    var display by remember { mutableStateOf<Map<Int, Double>>(emptyMap()) }
    var snapNext by remember { mutableStateOf(true) }
    LaunchedEffect(client) {
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

    var selected by remember { mutableStateOf<Int?>(null) }
    var lossAdjust by remember { mutableDoubleStateOf(0.0) }
    val (hiddenTlas, setHidden) = rememberHiddenLabels()

    val d = data
    val f = frame
    val selectedCar = f?.cars?.firstOrNull { it.driver.number == selected }
    val window = if (f != null && selectedCar != null) model?.pitWindow(f, selectedCar, lossAdjust) else null

    Column(Modifier.fillMaxSize().background(Color(0xFF111111))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Races", color = Color(0xFFBBBBBB)) }
            Spacer(Modifier.weight(1f))
            Text(
                d?.session?.let { "${it.country} · ${it.name}" } ?: "Live timing",
                color = Color.White, fontWeight = FontWeight.SemiBold,
            )
            if (d != null) LabelsMenu(d, hiddenTlas, setHidden) else Spacer(Modifier.size(48.dp))
        }

        Box(Modifier.fillMaxWidth().aspectRatio(0.9f), contentAlignment = Alignment.Center) {
            if (d != null && f != null && f.cars.isNotEmpty()) {
                CircleCanvas(
                    frame = f,
                    display = display,
                    selected = selected,
                    window = window,
                    hiddenLabels = d.drivers.filter { it.tla in hiddenTlas }.map { it.number }.toSet(),
                    onSelect = { selected = if (it == selected) null else it },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val waiting = when {
                    d == null -> connection
                    !builder.started -> "Waiting for the session to start"
                    else -> "Waiting for cars to cross a timing line…"
                }
                Text(waiting, color = Color(0xFF999999), textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (f != null) PitPanel(f, selectedCar?.driver?.tla, window, lossAdjust) { lossAdjust += it }
        }

        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 48.dp)) {
            val connected = connection == "Connected"
            val behind = delayMs >= 500
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(if (connected && !behind) Color(0xFFE53935) else Color(0xFF555555), CircleShape))
                Text(
                    when {
                        !connected -> connection
                        paused -> "PAUSED"
                        behind -> "${(delayMs + 500) / 1000}s behind live"
                        else -> "LIVE"
                    },
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                if (behind) {
                    TextButton(onClick = {
                        delayMs = 0
                        paused = false
                        snapNext = true
                    }) { Text("Go live") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Back in time = more delay; forward stops at live
                TextButton(onClick = {
                    delayMs += DELAY_STEP_MS
                    snapNext = true
                }) { Text("−1s", color = Color.White) }
                FilledTonalIconButton(onClick = { paused = !paused }, modifier = Modifier.size(56.dp)) {
                    Icon(
                        if (paused) AppIcons.Play else AppIcons.Pause,
                        contentDescription = if (paused) "Resume" else "Pause",
                        modifier = Modifier.size(30.dp),
                    )
                }
                TextButton(
                    onClick = {
                        delayMs = maxOf(0L, delayMs - DELAY_STEP_MS)
                        snapNext = true
                    },
                    enabled = delayMs > 0,
                ) { Text("+1s", color = if (delayMs > 0) Color.White else Color(0xFF555555)) }
            }
            Text(
                "Pause or step back to line the circle up with your stream",
                color = Color(0xFF999999), fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}
