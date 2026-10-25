package com.f1circle.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.f1circle.app.data.TrackStatus
import com.f1circle.app.model.CarState
import com.f1circle.app.model.CircleFrame
import com.f1circle.app.model.PitWindow
import com.f1circle.app.model.gapText
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.atan2

private val TrackColour = Color(0xFF3C3C3C)
private val MutedText = Color(0xFF8A8A8A)
private val WindowColour = Color(0xFF7B2CBF)
private val RejoinColour = Color(0xFFFF8A00)
private val OffLapDot = Color(0xFF555555)
private val OffLapFill = Color(0xFF333333)
private val OffLapText = Color(0xFF8A8A8A)

/** Half-width of the pit-window band, covering normal variation in stop time. */
private const val WINDOW_SPREAD_S = 1.5

/** The pit lane is drawn as an inner arc this many degrees either side of the start/finish line. */
private const val PIT_ARC_HALF_DEG = 32f

private const val LABEL_HEIGHT_DP = 17

/** A label drawn along the radius at [angle], from [inner] to [outer] px from the centre. */
private class LabelBox(val driver: Int, val angle: Double, val inner: Float, val outer: Float)

@Composable
fun CircleCanvas(
    frame: CircleFrame,
    /** Smoothed progress per driver number, used for drawing instead of the raw frame values. */
    display: Map<Int, Double>,
    selected: Int?,
    window: PitWindow?,
    /** Drivers whose labels are switched off; their dots still show. */
    hiddenLabels: Set<Int>,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = rememberTextMeasurer(cacheSize = 256)
    val hitBoxes = remember { ArrayList<LabelBox>() }
    val dotHits = remember { ArrayList<Pair<Int, Offset>>() }

    Canvas(
        modifier.pointerInput(Unit) {
            detectTapGestures { tap ->
                val c = Offset(size.width / 2f, size.height / 2f)
                val d = tap - c
                val dist = d.getDistance()
                val tapAngle = atan2(d.y.toDouble(), d.x.toDouble())
                val slack = 4.dp.toPx()
                val hit = hitBoxes
                    .filter { dist in it.inner - slack..it.outer + slack }
                    .map { it to abs(wrapRad(tapAngle - it.angle)) * dist }
                    .filter { it.second < LABEL_HEIGHT_DP.dp.toPx() / 2 + slack }
                    .minByOrNull { it.second }?.first?.driver
                    ?: dotHits.minByOrNull { (it.second - tap).getDistance() }
                        ?.takeIf { (it.second - tap).getDistance() < 28.dp.toPx() }?.first
                onSelect(hit)
            }
        }
    ) {
        val center = Offset(size.width / 2, size.height / 2)
        // As big as possible while leaving room for the labels that stick out of the ring
        val r = minOf(size.width, size.height) / 2 - 74.dp.toPx()
        val pitR = r * 0.68f
        val selectedCar = frame.cars.firstOrNull { it.driver.number == selected }
        val ref = selectedCar ?: frame.cars.firstOrNull { it.driver.number == frame.leader }

        /** With a driver selected, cars a lap or more ahead of or behind them fade to grey. */
        fun offLap(car: CarState) = selectedCar != null && car !== selectedCar && abs(selectedCar.progress - car.progress) >= 1.0

        fun progressOf(car: CarState) = display[car.driver.number] ?: car.progress

        // Track ring
        drawCircle(TrackColour, r, center, style = Stroke(3.dp.toPx()))

        // Pit window band: where the selected car would come back out
        window?.let { w ->
            val spread = WINDOW_SPREAD_S / frame.referenceLap
            drawArc(
                WindowColour.copy(alpha = 0.55f), degrees(w.rejoinProgress - spread), (spread * 2 * 360).toFloat(),
                useCenter = false, topLeft = center - Offset(r, r), size = Size(r * 2, r * 2),
                style = Stroke(22.dp.toPx(), cap = StrokeCap.Round),
            )
            val a = radians(w.rejoinProgress)
            drawLine(RejoinColour, point(center, r - 13.dp.toPx(), a), point(center, r + 13.dp.toPx(), a), 4.dp.toPx(), StrokeCap.Round)
        }

        // Sector lines and start/finish
        drawTick(center, r, radians(frame.sector2Start), "S2", text)
        drawTick(center, r, radians(frame.sector3Start), "S3", text)
        drawChequered(point(center, r, radians(0.0)))

        // Pit lane
        drawArc(
            TrackColour, -90f - PIT_ARC_HALF_DEG, PIT_ARC_HALF_DEG * 2, useCenter = false,
            topLeft = center - Offset(pitR, pitR), size = Size(pitR * 2, pitR * 2), style = Stroke(2.dp.toPx()),
        )
        drawCentered(text, "pit", point(center, pitR + 10.dp.toPx(), radians(0.0)), TextStyle(color = MutedText, fontSize = 10.sp))

        // Cars
        dotHits.clear()
        val onTrack = frame.cars.filter { !it.inPit }
        val pitCars = frame.cars.filter { it.inPit }
        for (car in pitCars) {
            val deg = degrees(progressOf(car)).let { wrapDeg(it + 90f).coerceIn(-PIT_ARC_HALF_DEG, PIT_ARC_HALF_DEG) - 90f }
            val p = point(center, pitR, Math.toRadians(deg.toDouble()))
            drawCar(car, p, car.driver.number == selected, offLap(car))
            if (car.driver.number !in hiddenLabels) {
                val colour = if (offLap(car)) OffLapText else Color.White
                drawCentered(text, car.driver.tla, p + Offset(0f, 14.dp.toPx()), TextStyle(color = colour, fontSize = 9.sp, fontWeight = FontWeight.Bold))
            }
            dotHits += car.driver.number to p
        }

        // Labels point straight out from each car, so they travel with it instead of reshuffling
        hitBoxes.clear()
        val labelStart = r + 10.dp.toPx()
        val labelH = LABEL_HEIGHT_DP.dp.toPx()
        val padX = 5.dp.toPx()
        val spacing = 4.dp.toPx()
        onTrack.filter { it.driver.number !in hiddenLabels }.sortedBy { it.driver.number == selected }.forEach { car ->
            val a = radians(progressOf(car))
            val alpha = if (car.stopped) 0.35f else 1f
            val greyed = offLap(car)
            val bg = if (greyed) OffLapFill else Color(car.driver.colour)
            val fg = (if (greyed) OffLapText else if (bg.luminance() > 0.5f) Color.Black else Color.White).copy(alpha = alpha)
            val sub = when {
                car.stopped -> "OUT"
                car.finished && car === ref -> "P${car.position ?: ""}"
                ref != null -> gapText(car, ref, frame.referenceLap)
                else -> ""
            }
            val name = text.measure(car.driver.tla, TextStyle(color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold))
            val gap = sub.takeIf { it.isNotEmpty() }?.let { text.measure(it, TextStyle(color = fg.copy(alpha = 0.85f * alpha), fontSize = 9.5.sp)) }
            val w = padX * 2 + name.size.width + (gap?.let { spacing + it.size.width } ?: 0f)
            val mid = point(center, labelStart + w / 2, a)
            // On the left half the label is turned round so the text stays upright; the name
            // then goes last so it still sits next to the car
            val flip = cos(a) < 0
            val deg = Math.toDegrees(a).toFloat() + if (flip) 180f else 0f
            rotate(deg, pivot = mid) {
                drawRoundRect(bg.copy(alpha = alpha), mid - Offset(w / 2, labelH / 2), Size(w, labelH), CornerRadius(labelH / 2))
                val parts = if (flip) listOfNotNull(gap, name) else listOfNotNull(name, gap)
                var x = mid.x - w / 2 + padX
                for (part in parts) {
                    drawText(part, topLeft = Offset(x, mid.y - part.size.height / 2f))
                    x += part.size.width + spacing
                }
            }
            hitBoxes += LabelBox(car.driver.number, a, labelStart, labelStart + w)
        }
        onTrack.sortedBy { it.driver.number == selected }.forEach { car ->
            val p = point(center, r, radians(progressOf(car)))
            drawCar(car, p, car.driver.number == selected, offLap(car))
            dotHits += car.driver.number to p
        }

        // Centre read-out
        val lapText = if (frame.lap == 0) "Grid" else "${frame.lap}/${frame.totalLaps}"
        drawCentered(text, lapText, center - Offset(0f, 34.dp.toPx()), TextStyle(color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold))
        val (statusText, statusColour) = statusLabel(frame.status)
        val chip = text.measure(statusText, TextStyle(color = statusColour, fontSize = 13.sp, fontWeight = FontWeight.Bold))
        val chipRect = Rect(center + Offset(-chip.size.width / 2f - 8.dp.toPx(), 2.dp.toPx()), Size(chip.size.width + 16.dp.toPx(), chip.size.height + 6.dp.toPx()))
        drawRoundRect(statusColour.copy(alpha = 0.18f), chipRect.topLeft, chipRect.size, CornerRadius(4.dp.toPx()))
        drawText(chip, topLeft = chipRect.topLeft + Offset(8.dp.toPx(), 3.dp.toPx()))
        drawCentered(text, "Reference lap: ${lapTime(frame.referenceLap)}", center + Offset(0f, 42.dp.toPx()), TextStyle(color = Color(0xFFCCCCCC), fontSize = 11.sp))
        drawCentered(text, "Avg. of leader's last 5 green laps", center + Offset(0f, 57.dp.toPx()), TextStyle(color = MutedText, fontSize = 9.sp))
        ref?.takeIf { it.driver.number == selected }?.let {
            drawCentered(text, "Gap to ${it.driver.tla}", center + Offset(0f, 76.dp.toPx()), TextStyle(color = Color.White, fontSize = 12.sp))
        }
    }
}

private fun DrawScope.drawCar(car: CarState, p: Offset, isSelected: Boolean, greyed: Boolean) {
    val colour = if (greyed) OffLapDot else Color(car.driver.colour)
    val alpha = if (car.stopped) 0.35f else 1f
    if (isSelected) drawCircle(colour.copy(alpha = 0.3f), 15.dp.toPx(), p)
    drawCircle(Color(0xFF111111), (if (isSelected) 9f else 7.5f).dp.toPx(), p)
    drawCircle(colour.copy(alpha = alpha), (if (isSelected) 7.5f else 6f).dp.toPx(), p)
}

private fun DrawScope.drawTick(center: Offset, r: Float, angle: Double, label: String, text: TextMeasurer) {
    drawLine(Color(0xFFBBBBBB), point(center, r - 6.dp.toPx(), angle), point(center, r + 6.dp.toPx(), angle), 2.dp.toPx())
    drawCentered(text, label, point(center, r - 18.dp.toPx(), angle), TextStyle(color = MutedText, fontSize = 10.sp))
}

private fun DrawScope.drawChequered(at: Offset) {
    val s = 4.dp.toPx()
    for (row in 0 until 3) for (col in 0 until 4) {
        val c = if ((row + col) % 2 == 0) Color.White else Color(0xFF222222)
        drawRect(c, at + Offset((col - 2) * s, (row - 3.5f) * s), Size(s, s))
    }
    drawLine(Color.White, at - Offset(0f, 2.dp.toPx()), at + Offset(0f, 8.dp.toPx()), 2.dp.toPx())
}

private fun DrawScope.drawCentered(text: TextMeasurer, s: String, at: Offset, style: TextStyle) {
    val layout = text.measure(s, style)
    drawText(layout, topLeft = at - Offset(layout.size.width / 2f, layout.size.height / 2f))
}

/** Fraction of lap → canvas angle in radians (0 at the top, clockwise). */
private fun radians(progress: Double): Double = -PI / 2 + 2 * PI * (progress - floor(progress))

/** Fraction of lap → drawArc degrees (0 at 3 o'clock, clockwise). */
private fun degrees(progress: Double): Float = (-90.0 + 360.0 * (progress - floor(progress))).toFloat()

private fun wrapDeg(d: Float): Float = ((d + 180f) % 360f + 360f) % 360f - 180f

private fun point(c: Offset, r: Float, a: Double) = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())

private fun wrapRad(a: Double): Double = ((a + PI) % (2 * PI) + 2 * PI) % (2 * PI) - PI

private fun statusLabel(s: TrackStatus): Pair<String, Color> = when (s) {
    TrackStatus.GREEN -> "Track Clear" to Color(0xFF4CAF50)
    TrackStatus.SAFETY_CAR -> "Safety Car" to Color(0xFFFF9800)
    TrackStatus.VSC -> "Virtual Safety Car" to Color(0xFFFF9800)
    TrackStatus.VSC_ENDING -> "VSC Ending" to Color(0xFFFFC107)
    TrackStatus.RED -> "Red Flag" to Color(0xFFF44336)
    TrackStatus.CHEQUERED -> "Chequered Flag" to Color(0xFFEEEEEE)
}

private fun lapTime(seconds: Double): String {
    val m = (seconds / 60).toInt()
    return String.format(Locale.US, "%d:%06.3f", m, seconds - m * 60)
}
