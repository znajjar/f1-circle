package com.f1circle.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import java.util.prefs.Preferences

/** Overlay settings kept between runs (in the registry, via java.util.prefs). */
class Settings {
    private val prefs = Preferences.userRoot().node("com/f1circle/overlay")

    /** How far the circle runs behind live timing, to match the video stream. */
    var delayMs: Long
        get() = prefs.getLong("delayMs", 0L)
        set(value) = prefs.putLong("delayMs", value)

    /** Opacity of the dark disc behind the circle, so it reads over bright footage. */
    var backdrop: Float
        get() = prefs.getFloat("backdrop", 0.55f)
        set(value) = prefs.putFloat("backdrop", value)

    val position: WindowPosition
        get() = if (prefs.get("x", null) == null) {
            WindowPosition.PlatformDefault
        } else {
            WindowPosition(prefs.getFloat("x", 0f).dp, prefs.getFloat("y", 0f).dp)
        }

    val size: DpSize
        get() = DpSize(prefs.getFloat("width", 460f).dp, prefs.getFloat("height", 460f).dp)

    fun saveBounds(position: WindowPosition, size: DpSize) {
        if (position is WindowPosition.Absolute) {
            prefs.putFloat("x", position.x.value)
            prefs.putFloat("y", position.y.value)
        }
        prefs.putFloat("width", size.width.value)
        prefs.putFloat("height", size.height.value)
    }
}
