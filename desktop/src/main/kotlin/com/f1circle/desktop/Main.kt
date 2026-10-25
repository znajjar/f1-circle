package com.f1circle.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val settings = remember { Settings() }
    val live = remember { LiveCircle(settings) }
    val windowState = rememberWindowState(position = settings.position, size = settings.size)
    var locked by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(true) }
    var unavailable by remember { mutableStateOf(emptyList<Hotkey>()) }

    DisposableEffect(Unit) {
        live.start()
        val hotkeys = Hotkeys(onUnavailable = { unavailable = it }) { key ->
            when (key) {
                Hotkey.LOCK -> {
                    locked = !locked
                    visible = true
                }
                Hotkey.HIDE -> visible = !visible
                Hotkey.PAUSE -> live.togglePause()
                Hotkey.BACK -> live.shift(1_000)
                Hotkey.FORWARD -> live.shift(-1_000)
                Hotkey.BACK_FINE -> live.shift(100)
                Hotkey.FORWARD_FINE -> live.shift(-100)
                Hotkey.PREVIOUS_DRIVER -> live.cycleSelection(-1)
                Hotkey.NEXT_DRIVER -> live.cycleSelection(1)
                Hotkey.CLEAR_DRIVER -> live.selected = null
            }
        }
        hotkeys.start()
        onDispose {
            hotkeys.stop()
            live.stop()
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        visible = visible,
        title = "F1 Circle",
        icon = AppIcon,
        undecorated = true,
        transparent = true,
        resizable = true,
        alwaysOnTop = true,
        // Locked, it never takes focus away from the video player
        focusable = !locked,
    ) {
        LaunchedEffect(locked, visible) {
            if (visible) setClickThrough(window, locked)
        }
        LaunchedEffect(windowState) {
            snapshotFlow { windowState.position to windowState.size }.collect { (position, size) ->
                settings.saveBounds(position, size)
            }
        }
        LaunchedEffect(live) { live.run() }

        Overlay(
            live = live,
            settings = settings,
            windowState = windowState,
            locked = locked,
            unavailableHotkeys = unavailable,
            onLock = { locked = true },
            onClose = ::exitApplication,
        )
    }
}

/** Taskbar icon: a red ring with one car on it. */
private object AppIcon : Painter() {
    override val intrinsicSize = Size(64f, 64f)

    override fun DrawScope.onDraw() {
        val r = size.minDimension
        drawCircle(Color(0xFF111111))
        drawCircle(Color(0xFFE10600), r * 0.32f, style = Stroke(r * 0.09f))
        drawCircle(Color.White, r * 0.1f, center + Offset(0f, -r * 0.32f))
    }
}
