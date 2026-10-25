package com.f1circle.app.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.f1circle.app.data.SessionData
import com.f1circle.app.model.CircleFrame
import com.f1circle.app.model.CircleModel
import com.f1circle.app.model.PitLossSource
import com.f1circle.app.model.PitWindow
import com.f1circle.app.model.smoothProgress
import com.f1circle.app.data.TrackStatus
import java.util.Locale
import kotlin.math.abs

private val Speeds = listOf(1f, 4f, 16f, 64f)

private const val HIDDEN_LABELS_KEY = "hiddenLabels"

/** Replay clock plus per-car smoothing so dots glide instead of jumping when new data lands. */
private class ReplayState(val model: CircleModel) {
    var clock by mutableStateOf(model.startMs)
    var playing by mutableStateOf(false)
    var speed by mutableFloatStateOf(4f)
    var frame by mutableStateOf(model.frame(model.startMs))
    var display by mutableStateOf<Map<Int, Double>>(emptyMap())

    fun tick(realMs: Double) {
        val replayMs = if (playing) realMs * speed else 0.0
        clock = (clock + replayMs.toLong()).coerceIn(model.startMs, model.endMs)
        if (clock == model.endMs) playing = false
        update(realMs, replayMs, snap = speed > 16f)
    }

    fun seek(t: Long) {
        clock = t.coerceIn(model.startMs, model.endMs)
        update(0.0, 0.0, snap = true)
    }

    private fun update(realMs: Double, replayMs: Double, snap: Boolean) {
        val f = model.frame(clock)
        display = smoothProgress(display, f, realMs, replayMs, snap)
        frame = f
    }
}

/** Which drivers' labels are hidden, remembered by TLA so the choice carries across races. */
@Composable
internal fun rememberHiddenLabels(): Pair<Set<String>, (Set<String>) -> Unit> {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("display", Context.MODE_PRIVATE) }
    var hidden by remember { mutableStateOf(prefs.getStringSet(HIDDEN_LABELS_KEY, null).orEmpty().toSet()) }
    return hidden to { tlas ->
        hidden = tlas
        prefs.edit().putStringSet(HIDDEN_LABELS_KEY, tlas).apply()
    }
}

@Composable
fun ReplayScreen(data: SessionData, model: CircleModel, onBack: () -> Unit) {
    val state = remember(model) { ReplayState(model) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var lossAdjust by remember { mutableDoubleStateOf(0.0) }

    val (hiddenTlas, setHidden) = rememberHiddenLabels()

    LaunchedEffect(state) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) state.tick((now - last) / 1e6)
                last = now
            }
        }
    }

    val frame = state.frame
    val selectedCar = frame.cars.firstOrNull { it.driver.number == selected }
    val window = selectedCar?.let { model.pitWindow(frame, it, lossAdjust) }

    Column(Modifier.fillMaxSize().background(Color(0xFF111111))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Races", color = Color(0xFFBBBBBB)) }
            Spacer(Modifier.weight(1f))
            Text("${data.session.country} · ${data.session.name}", color = Color.White, fontWeight = FontWeight.SemiBold)
            LabelsMenu(data, hiddenTlas, setHidden)
        }

        // A fixed square: nothing below can move or resize the circle
        CircleCanvas(
            frame = frame,
            display = state.display,
            selected = selected,
            window = window,
            hiddenLabels = data.drivers.filter { it.tla in hiddenTlas }.map { it.number }.toSet(),
            onSelect = { selected = if (it == selected) null else it },
            // Slightly taller than wide so labels at 12 and 6 o'clock have room
            modifier = Modifier.fillMaxWidth().aspectRatio(0.9f),
        )

        // The pit panel has its own slot, so showing it never shifts anything else
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp)) {
            PitPanel(frame, selectedCar?.driver?.tla, window, lossAdjust) { lossAdjust += it }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(raceClock(state.clock - data.raceStartMs), color = Color(0xFFBBBBBB), fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                SpeedPicker(state.speed) { state.speed = it }
            }
            Slider(
                value = ((state.clock - model.startMs).toFloat() / (model.endMs - model.startMs)).coerceIn(0f, 1f),
                onValueChange = { state.seek(model.startMs + (it * (model.endMs - model.startMs)).toLong()) },
            )
            Spacer(Modifier.height(16.dp))
            // Lifted off the bottom edge so the buttons sit within easy thumb reach
            Row(
                Modifier.fillMaxWidth().padding(bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { state.seek(state.clock - 30_000) }) { Text("−30s", color = Color.White) }
                FilledTonalIconButton(onClick = { state.playing = !state.playing }, modifier = Modifier.size(56.dp)) {
                    Icon(
                        if (state.playing) AppIcons.Pause else AppIcons.Play,
                        contentDescription = if (state.playing) "Pause" else "Play",
                        modifier = Modifier.size(30.dp),
                    )
                }
                TextButton(onClick = { state.seek(state.clock + 30_000) }) { Text("+30s", color = Color.White) }
            }
        }
    }
}

/** Shows the current speed; tapping it slides the other speeds out to its left. */
@Composable
private fun SpeedPicker(speed: Float, onPick: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(
            visible = open,
            enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut(),
        ) {
            Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Speeds.filter { it != speed }.forEach { s ->
                    SpeedChip(s, highlighted = false) {
                        onPick(s)
                        open = false
                    }
                }
            }
        }
        SpeedChip(speed, highlighted = true) { open = !open }
    }
}

@Composable
private fun SpeedChip(speed: Float, highlighted: Boolean, onClick: () -> Unit) {
    Text(
        "${speed.toInt()}×",
        color = if (highlighted) Color.Black else Color.White,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .width(52.dp)
            .background(if (highlighted) Color.White else Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

/** Button in the top bar that opens a per-driver on/off list for the circle labels. */
@Composable
internal fun LabelsMenu(data: SessionData, hidden: Set<String>, onChange: (Set<String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(AppIcons.Labels, contentDescription = "Driver labels", tint = Color(0xFFBBBBBB))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val all = data.drivers.map { it.tla }.toSet()
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { onChange(hidden - all) }) { Text("Show all") }
                TextButton(onClick = { onChange(hidden + all) }) { Text("Hide all") }
            }
            HorizontalDivider()
            data.drivers.sortedWith(compareBy({ it.team }, { it.tla })).forEach { d ->
                val shown = d.tla !in hidden
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(Color(d.colour), CircleShape))
                            Text(d.tla, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp, end = 8.dp))
                            Text(d.team, color = Color(0xFF999999), fontSize = 12.sp)
                        }
                    },
                    trailingIcon = { Checkbox(checked = shown, onCheckedChange = null) },
                    // Keep the menu open so several drivers can be toggled in one go
                    onClick = { onChange(if (shown) hidden + d.tla else hidden - d.tla) },
                )
            }
        }
    }
}

@Composable
internal fun PitPanel(
    frame: CircleFrame,
    tla: String?,
    window: PitWindow?,
    lossAdjust: Double,
    onAdjust: (Double) -> Unit,
) {
    val box = Modifier.fillMaxWidth().background(Color(0xFF1C1C1C), RoundedCornerShape(10.dp)).padding(12.dp)
    if (tla == null) {
        Text("Tap a driver to see where they'd rejoin if they pitted now.", color = Color(0xFF999999), fontSize = 13.sp, modifier = box)
        return
    }
    Column(box) {
        if (window == null) {
            Text("$tla: no pit window right now", color = Color.White, fontSize = 14.sp)
            return@Column
        }
        val ahead = window.carAhead?.driver?.tla
        val behind = window.carBehind?.driver?.tla
        val where = when {
            ahead != null && behind != null -> ", between $ahead and $behind"
            ahead != null -> ", behind $ahead"
            behind != null -> ", ahead of $behind"
            else -> ""
        }
        Text(
            "$tla pits now → rejoins P${window.rejoinPosition}$where",
            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            val source = when {
                lossAdjust != 0.0 -> "adjusted"
                window.source == PitLossSource.MEASURED -> "measured from stops"
                window.source == PitLossSource.PIT_LANE -> "from pit lane times"
                else -> "default"
            }
            Text(
                String.format(Locale.US, "Pit loss %.1f s (%s)", window.lossSeconds, source) +
                    when (frame.status) {
                        TrackStatus.SAFETY_CAR -> " · reduced for safety car"
                        TrackStatus.VSC, TrackStatus.VSC_ENDING -> " · reduced for VSC"
                        else -> ""
                    },
                color = Color(0xFFAAAAAA), fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onAdjust(-0.5) }) { Text("−", color = Color.White, fontSize = 18.sp) }
            TextButton(onClick = { onAdjust(0.5) }) { Text("+", color = Color.White, fontSize = 18.sp) }
        }
    }
}

private fun raceClock(ms: Long): String {
    val sign = if (ms < 0) "-" else ""
    val s = abs(ms) / 1000
    return String.format(Locale.US, "%s%d:%02d:%02d", sign, s / 3600, s / 60 % 60, s % 60)
}
