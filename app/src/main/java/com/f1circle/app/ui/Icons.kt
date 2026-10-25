package com.f1circle.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** A few 24×24 icons, drawn here to avoid pulling in a whole icon library. */
object AppIcons {
    val Play: ImageVector = icon("Play") {
        moveTo(8f, 5f)
        lineTo(19f, 12f)
        lineTo(8f, 19f)
        close()
    }

    val Pause: ImageVector = icon("Pause") {
        moveTo(6f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(6f, 19f); close()
        moveTo(14f, 5f); lineTo(18f, 5f); lineTo(18f, 19f); lineTo(14f, 19f); close()
    }

    /** A price-tag shape with a hole, for the label settings. */
    val Labels: ImageVector = icon("Labels") {
        moveTo(3f, 5f); lineTo(14f, 5f); lineTo(21f, 12f); lineTo(14f, 19f); lineTo(3f, 19f); close()
        moveTo(14.5f, 12f)
        arcToRelative(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -3f, dy1 = 0f)
        arcToRelative(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 3f, dy1 = 0f)
        close()
    }

    private fun icon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd, pathBuilder = block)
            .build()
}
