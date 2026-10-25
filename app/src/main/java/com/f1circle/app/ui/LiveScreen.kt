package com.f1circle.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Shift the live picture back in time to line up with a video stream that runs behind timing. */
private const val DELAY_STEP_S = 5

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

    var delaySeconds by remember { mutableIntStateOf(0) }
    var frame by remember { mutableStateOf<CircleFrame?>(null) }
    var display by remember { mutableStateOf<Map<Int, Double>>(emptyMap()) }
    var snapNext by remember { mutableStateOf(true) }
    LaunchedEffect(client) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val m = model
                if (m != null) {
                    val realMs = if (last == 0L) 0.0 else (now - last) / 1e6
                    val f = m.frame(client.serverNow() - delaySeconds * 1000L)
                    display = smoothProgress(display, f, realMs, realMs, snap = snapNext)
                    snapNext = false
                    frame = f
                }
                last = now
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(if (connection == "Connected") Color(0xFFE53935) else Color(0xFF555555), CircleShape))
                Text(
                    if (connection == "Connected") "LIVE" else connection,
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Delay", color = Color.White, fontSize = 14.sp)
                    Text("Hold the circle back to match your stream", color = Color(0xFF999999), fontSize = 12.sp)
                }
                TextButton(onClick = {
                    delaySeconds = maxOf(0, delaySeconds - DELAY_STEP_S)
                    snapNext = true
                }) { Text("−", color = Color.White, fontSize = 20.sp) }
                Text("${delaySeconds}s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                TextButton(onClick = {
                    delaySeconds += DELAY_STEP_S
                    snapNext = true
                }) { Text("+", color = Color.White, fontSize = 20.sp) }
            }
        }
    }
}
