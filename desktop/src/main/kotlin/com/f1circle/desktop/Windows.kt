package com.f1circle.desktop

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import javax.swing.SwingUtilities

private const val WS_EX_TRANSPARENT = 0x00000020
private const val WS_EX_LAYERED = 0x00080000
private const val WM_HOTKEY = 0x0312
private const val WM_QUIT = 0x0012

private const val MOD_ALT = 0x1
private const val MOD_CONTROL = 0x2
private const val MOD_SHIFT = 0x4
private const val MOD_NOREPEAT = 0x4000

/**
 * Makes mouse clicks fall through the window to whatever is underneath (the video player) while
 * [enabled]. Windows only does this for layered windows, so that style is added too, and only
 * the styles added here are taken off again.
 */
fun setClickThrough(window: ComposeWindow, enabled: Boolean) {
    val hwnd = HWND(Pointer(window.windowHandle))
    val user32 = User32.INSTANCE
    val style = user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
    val next = if (enabled) {
        val added = (WS_EX_LAYERED or WS_EX_TRANSPARENT) and style.inv()
        window.rootPane.putClientProperty(ADDED_STYLES, added)
        style or added
    } else {
        val added = window.rootPane.getClientProperty(ADDED_STYLES) as? Int ?: return
        window.rootPane.putClientProperty(ADDED_STYLES, null)
        style and added.inv()
    }
    if (next != style) user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, next)
}

private const val ADDED_STYLES = "f1circle.addedExStyles"

/** System-wide shortcuts, so the overlay can be driven while clicks pass through it. */
enum class Hotkey(val keys: String, val description: String, val modifiers: Int, val vk: Int) {
    LOCK("Ctrl+Alt+L", "Lock in place (clicks pass through) / unlock to edit", MOD_CONTROL or MOD_ALT or MOD_NOREPEAT, 'L'.code),
    HIDE("Ctrl+Alt+H", "Hide / show", MOD_CONTROL or MOD_ALT or MOD_NOREPEAT, 'H'.code),
    PAUSE("Ctrl+Alt+P", "Pause / resume", MOD_CONTROL or MOD_ALT or MOD_NOREPEAT, 'P'.code),
    BACK("Ctrl+Alt+,", "1 s further behind live", MOD_CONTROL or MOD_ALT, 0xBC),
    FORWARD("Ctrl+Alt+.", "1 s closer to live", MOD_CONTROL or MOD_ALT, 0xBE),
    BACK_FINE("Ctrl+Alt+Shift+,", "0.1 s further behind live", MOD_CONTROL or MOD_ALT or MOD_SHIFT, 0xBC),
    FORWARD_FINE("Ctrl+Alt+Shift+.", "0.1 s closer to live", MOD_CONTROL or MOD_ALT or MOD_SHIFT, 0xBE),
    PREVIOUS_DRIVER("Ctrl+Alt+[", "Select the driver ahead", MOD_CONTROL or MOD_ALT, 0xDB),
    NEXT_DRIVER("Ctrl+Alt+]", "Select the driver behind", MOD_CONTROL or MOD_ALT, 0xDD),
    CLEAR_DRIVER("Ctrl+Alt+\\", "Clear the selection", MOD_CONTROL or MOD_ALT or MOD_NOREPEAT, 0xDC),
}

/**
 * Registers every [Hotkey] on a thread of its own (Windows delivers them to the registering
 * thread's message queue) and calls [onHotkey] on the UI thread. Shortcuts another app already
 * holds are reported through [onUnavailable].
 */
class Hotkeys(
    private val onUnavailable: (List<Hotkey>) -> Unit,
    private val onHotkey: (Hotkey) -> Unit,
) {
    @Volatile private var threadId = 0
    @Volatile private var stopped = false

    fun start() {
        Thread({ loop() }, "hotkeys").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopped = true
        threadId.takeIf { it != 0 }?.let { User32.INSTANCE.PostThreadMessage(it, WM_QUIT, null, null) }
    }

    private fun loop() {
        val user32 = User32.INSTANCE
        threadId = Kernel32.INSTANCE.GetCurrentThreadId()
        if (stopped) return
        val failed = Hotkey.entries.filterNot { user32.RegisterHotKey(null, it.ordinal + 1, it.modifiers, it.vk) }
        if (failed.isNotEmpty()) SwingUtilities.invokeLater { onUnavailable(failed) }
        val msg = WinUser.MSG()
        while (user32.GetMessage(msg, null, 0, 0) > 0) {
            if (msg.message == WM_HOTKEY) {
                val key = Hotkey.entries.getOrNull(msg.wParam.toInt() - 1) ?: continue
                SwingUtilities.invokeLater { onHotkey(key) }
            }
        }
        for (key in Hotkey.entries) if (key !in failed) user32.UnregisterHotKey(null, key.ordinal + 1)
    }
}
