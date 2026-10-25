package com.f1circle.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowState
import com.f1circle.app.data.TrackStatus
import com.f1circle.app.model.PitWindow
import com.f1circle.app.ui.CircleCanvas
import kotlinx.coroutines.delay
import java.awt.Cursor
import java.awt.MouseInfo
import java.util.Locale

private val PanelColour = Color(0xDD1C1C1C)
private val MutedText = Color(0xFF999999)
private val BackdropSteps = listOf(0f, 0.3f, 0.55f, 0.8f)

/**
 * The overlay's contents. Unlocked, it shows a frame and controls for moving, sizing and lining the
 * circle up with the stream; locked, only the circle (and briefly the delay after a hotkey nudge).
 */
@Composable
fun FrameWindowScope.Overlay(
    live: LiveCircle,
    settings: Settings,
    windowState: WindowState,
    locked: Boolean,
    unavailableHotkeys: List<Hotkey>,
    onLock: () -> Unit,
    onClose: () -> Unit,
) {
    var backdrop by remember { mutableFloatStateOf(settings.backdrop) }
    var showHelp by remember { mutableStateOf(false) }

    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(live.changes) {
        if (live.changes == 0) return@LaunchedEffect
        flash = true
        delay(3_000)
        flash = false
    }

    val d = live.data
    val f = live.frame
    val selectedCar = f?.cars?.firstOrNull { it.driver.number == live.selected }
    val window = if (f != null && selectedCar != null) live.model?.pitWindow(f, selectedCar, 0.0) else null

    Box(Modifier.fillMaxSize()) {
        // Unlocked, the whole window is tinted so it can be seen (and transparent areas still take clicks)
        if (!locked) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color(0x33000000), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(8.dp))
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            if (backdrop > 0f) drawCircle(Color(0xFF111111).copy(alpha = backdrop), minOf(size.width, size.height) / 2)
        }

        if (d != null && f != null && f.cars.isNotEmpty()) {
            CircleCanvas(
                frame = f,
                display = live.display,
                selected = live.selected,
                window = window,
                hiddenLabels = emptySet(),
                onSelect = { live.selected = if (it == live.selected) null else it },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val waiting = when {
                d == null -> live.connection
                !live.started -> "Waiting for the session to start"
                else -> "Waiting for cars to cross a timing line…"
            }
            BasicText(
                waiting,
                Modifier.align(Alignment.Center).padding(32.dp),
                style = TextStyle(color = MutedText, fontSize = 14.sp, textAlign = TextAlign.Center),
            )
        }

        if (!locked) {
            WindowDraggableArea(Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp))) {
                Pill("⠿  ${live.statusText()}" + (d?.session?.let { " · ${it.country}" } ?: ""), bold = true)
            }
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ToolButton("?") { showHelp = !showHelp }
                ToolButton("Lock · ${Hotkey.LOCK.keys} unlocks") { onLock() }
                ToolButton("✕") { onClose() }
            }
        } else if (flash) {
            Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { Pill(live.statusText(), bold = true) }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            selectedCar?.let { car -> Pill(pitSummary(car.driver.tla, window, f.status)) }
            if (!locked) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolButton("« 1s") { live.shift(1_000) }
                    ToolButton("‹ 0.1") { live.shift(100) }
                    Pill(if (live.delayMs < 100) "Live" else "${seconds(live.delayMs)} behind", bold = true, modifier = Modifier.width(96.dp))
                    ToolButton("0.1 ›") { live.shift(-100) }
                    ToolButton("1s »") { live.shift(-1_000) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ToolButton(if (live.paused) "Resume" else "Pause") { live.togglePause() }
                    ToolButton("Go live") { live.goLive() }
                    ToolButton("Backdrop ${(backdrop * 100).toInt()}%") {
                        backdrop = BackdropSteps[(BackdropSteps.indexOf(backdrop) + 1) % BackdropSteps.size]
                        settings.backdrop = backdrop
                    }
                }
            }
        }

        if (!locked && showHelp) HotkeyHelp(unavailableHotkeys, Modifier.align(Alignment.Center))
        if (!locked) ResizeGrip(windowState, Modifier.align(Alignment.BottomEnd))
    }
}

private fun pitSummary(tla: String, window: PitWindow?, status: TrackStatus): String {
    if (window == null) return "$tla: no pit window right now"
    val ahead = window.carAhead?.driver?.tla
    val behind = window.carBehind?.driver?.tla
    val where = when {
        ahead != null && behind != null -> ", between $ahead and $behind"
        ahead != null -> ", behind $ahead"
        behind != null -> ", ahead of $behind"
        else -> ""
    }
    val neutralised = when (status) {
        TrackStatus.SAFETY_CAR -> ", SC"
        TrackStatus.VSC, TrackStatus.VSC_ENDING -> ", VSC"
        else -> ""
    }
    return "$tla pits now → P${window.rejoinPosition}$where · " +
        String.format(Locale.US, "loss %.1f s%s", window.lossSeconds, neutralised)
}

@Composable
private fun HotkeyHelp(unavailable: List<Hotkey>, modifier: Modifier) {
    Column(
        modifier.background(PanelColour, RoundedCornerShape(10.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicText("Hotkeys (work while locked)", style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold))
        for (key in Hotkey.entries) {
            val taken = key in unavailable
            Row {
                BasicText(key.keys, Modifier.width(120.dp), style = TextStyle(color = if (taken) Color(0xFFFF6B6B) else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                BasicText(
                    key.description + if (taken) " (in use by another app)" else "",
                    style = TextStyle(color = if (taken) Color(0xFFFF6B6B) else MutedText, fontSize = 12.sp),
                )
            }
        }
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier, bold: Boolean = false) {
    BasicText(
        text,
        modifier.background(PanelColour, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
        style = TextStyle(
            color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        ),
    )
}

@Composable
private fun ToolButton(label: String, onClick: () -> Unit) {
    BasicText(
        label,
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(Color(0xDD333333))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
    )
}

/**
 * Bottom-right handle for resizing, since an undecorated window has no border to drag. Works from
 * the pointer's screen position, which (unlike the local one) doesn't move as the window grows.
 */
@Composable
private fun ResizeGrip(state: WindowState, modifier: Modifier) {
    Canvas(
        modifier.size(20.dp)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.SE_RESIZE_CURSOR)))
            .pointerInput(state) {
                var start = java.awt.Point()
                var startSize = state.size
                detectDragGestures(
                    onDragStart = {
                        start = MouseInfo.getPointerInfo().location
                        startSize = state.size
                    },
                ) { change, _ ->
                    change.consume()
                    val now = MouseInfo.getPointerInfo().location
                    // AWT screen coordinates are already scaled for the display, like dp
                    state.size = DpSize(
                        (startSize.width.value + now.x - start.x).coerceAtLeast(280f).dp,
                        (startSize.height.value + now.y - start.y).coerceAtLeast(280f).dp,
                    )
                }
            }
    ) {
        val c = Color(0xAAFFFFFF)
        for (i in 1..3) {
            val o = size.width * i / 4
            drawLine(c, Offset(size.width - 3, o), Offset(o, size.height - 3), 1.5f)
        }
    }
}
