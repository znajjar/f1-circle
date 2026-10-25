package com.f1circle.app.model

import com.f1circle.app.data.OpenF1Parser
import com.f1circle.app.data.RaceSession
import com.f1circle.app.data.SessionData
import com.f1circle.app.data.TrackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Runs the model over the 2026 Italian GP (safety car, red flag and VSC included). */
class CircleModelTest {

    private val data: SessionData by lazy {
        fun res(name: String) = javaClass.getResource("/monza2026/$name.json")!!.readText()
        OpenF1Parser.parseSession(
            RaceSession(11361, "Race", "Monza", "Italy", 0),
            res("drivers"), res("laps"), res("intervals"), res("position"), res("pit"), res("race_control"),
        )
    }

    @Test
    fun statusTimelineCoversNeutralisations() {
        val seen = data.statuses.map { it.status }.toSet()
        for (s in listOf(TrackStatus.SAFETY_CAR, TrackStatus.RED, TrackStatus.VSC, TrackStatus.CHEQUERED)) {
            assertTrue("missing $s in ${data.statuses}", s in seen)
        }
        assertEquals(TrackStatus.GREEN, data.statuses.last { it.status != TrackStatus.CHEQUERED }.status)
    }

    /**
     * Each sector-line crossing is ground truth for where that car is. Just before the crossing
     * arrives, the model is still estimating; compare that estimate with the truth.
     */
    @Test
    fun predictsCarsWithinAFewTenths() {
        val results = listOf(false, true).associateWith { useGaps ->
            val model = CircleModel(data, useGaps)
            val errors = ArrayList<Double>()
            val worst = ArrayList<Pair<Double, String>>()
            for (lap in data.laps) {
                val s1 = lap.s1 ?: continue
                if (lap.number < 3 || lap.isPitOutLap) continue
                val t = lap.startMs + (s1 * 1000).toLong()
                if (model.statusAt(t - 120_000) != TrackStatus.GREEN || model.statusAt(t) != TrackStatus.GREEN) continue
                if (data.pits.any { it.driver == lap.driver && t in it.entryMs - 120_000..it.exitMs + 120_000 }) continue

                val before = model.frame(t - 1)
                val car = before.cars.first { it.driver.number == lap.driver }
                if (car.stopped || car.finished) continue
                val truth = lap.number - 1 + model.frame(t).sector2Start
                errors += (car.progress - truth) * before.referenceLap
                worst += abs(errors.last()) to "${car.driver.tla} lap ${lap.number} P${car.position} leader=${before.leader}"
            }
            if (useGaps) println("worst: " + worst.sortedByDescending { it.first }.take(6).joinToString { "%.1fs %s".format(it.first, it.second) })
            errors
        }
        for ((useGaps, errors) in results) {
            val abs = errors.map { abs(it) }.sorted()
            println(
                "%s: n=%d  median=%.2fs  p90=%.2fs  max=%.2fs".format(
                    if (useGaps) "own anchors + gaps" else "own anchors only ",
                    abs.size, abs[abs.size / 2], abs[(abs.size * 0.9).toInt()], abs.last(),
                )
            )
        }
        val withGaps = results.getValue(true).map { abs(it) }.sorted()
        assertTrue(withGaps.size > 500)
        assertTrue("p90 too large", withGaps[(withGaps.size * 0.9).toInt()] < 1.0)
    }

    @Test
    fun measuresPitLossFromRealStops() {
        val model = CircleModel(data)
        val (loss, source) = model.pitLoss(data.raceEndMs)
        println("pit loss at the flag: $loss ($source)")
        assertEquals(PitLossSource.MEASURED, source)
        assertTrue(loss in 18.0..32.0)
        // One slow stop alone shouldn't count as a measurement
        val lap20 = data.laps.first { it.number == 20 }.startMs
        assertEquals(PitLossSource.PIT_LANE, model.pitLoss(lap20).second)
    }

    @Test
    fun gapLabels() {
        val model = CircleModel(data)
        val frame = model.frame(data.raceStartMs + 60 * 60_000)
        val leader = frame.cars.first { it.driver.number == frame.leader }
        val p2 = frame.cars.first { it.position == 2 }
        assertEquals("", gapText(leader, leader, frame.referenceLap))
        assertTrue(gapText(p2, leader, frame.referenceLap).startsWith("+"))
        assertTrue(gapText(leader, p2, frame.referenceLap).startsWith("-"))
        frame.cars.sortedBy { it.position }.forEach {
            println("P${it.position} ${it.driver.tla} %.3f %s".format(it.progress, gapText(it, leader, frame.referenceLap)))
        }
    }
}
