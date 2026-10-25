package com.f1circle.app.data

import com.f1circle.app.model.CircleModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs

class LiveSessionBuilderTest {

    @Test
    fun parsesGapsAndLapTimes() {
        assertEquals(0.0, LiveSessionBuilder.parseGap("LAP 12", 0, 1)!!.gap!!, 0.0)
        assertEquals(1.234, LiveSessionBuilder.parseGap("+1.234", 0, 1)!!.gap!!, 1e-9)
        assertEquals(2, LiveSessionBuilder.parseGap("2L", 0, 1)!!.lapsBehind)
        assertNull(LiveSessionBuilder.parseGap("", 0, 1))
        assertEquals(87.733, LiveSessionBuilder.parseLapTime("1:27.733")!!, 1e-9)
    }

    @Test
    fun buildsLapsFromLiveUpdates() {
        val b = LiveSessionBuilder()
        b.applySnapshot("DriverList", JSONObject("""{"1":{"Tla":"VER","TeamName":"Red Bull","TeamColour":"3671C6"}}"""), 0)
        b.applyUpdate("SessionStatus", JSONObject("""{"Status":"Started"}"""), 1_000)
        b.applyUpdate("TimingData", JSONObject("""{"Lines":{"1":{"Sectors":{"0":{"Value":"30.000"}}}}}"""), 31_000)
        b.applyUpdate("TimingData", JSONObject("""{"Lines":{"1":{"Sectors":{"1":{"Value":"30.500"}}}}}"""), 61_500)
        // S3 lands after the lap counter has already ticked over
        b.applyUpdate("TimingData", JSONObject("""{"Lines":{"1":{"NumberOfLaps":1,"LastLapTime":{"Value":"1:31.000"}}}}"""), 92_000)
        b.applyUpdate("TimingData", JSONObject("""{"Lines":{"1":{"Sectors":{"2":{"Value":"30.500"}}}}}"""), 92_100)
        val laps = b.snapshot().laps
        assertEquals(2, laps.size)
        val lap1 = laps[0]
        assertEquals(1_000, lap1.startMs)
        assertEquals(30.5, lap1.s3!!, 1e-9)
        assertEquals(91.0, lap1.duration!!, 1e-9)
        assertEquals(92_000, laps[1].startMs)
        assertNull(laps[1].s1)
    }

    /**
     * Replays a recorded live feed (see the recorder in the project notes) and measures how well
     * cars are placed just before each sector crossing, like the replay test does for OpenF1 data.
     * Skipped when no recording is available.
     */
    @Test
    fun recordedRaceIsPlacedAccurately() {
        val path = System.getenv("F1_LIVE_RECORDING") ?: javaClass.getResource("/live/usgp2026.jsonl.gz")?.file
        val file = path?.let(::File)
        assumeTrue("no live recording", file != null && file.exists())
        val b = LiveSessionBuilder()
        val stream = if (file!!.name.endsWith(".gz")) GZIPInputStream(file.inputStream()) else file.inputStream()
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                val json = line.substringAfter(' ')
                if (!json.startsWith("{")) continue
                val received = line.substringBefore(' ').toLong()
                val msg = JSONObject(json)
                when (msg.optInt("type")) {
                    1 -> {
                        val args = msg.getJSONArray("arguments")
                        val data = args.optJSONObject(1) ?: continue
                        b.applyUpdate(args.getString(0), data, LiveSessionBuilder.parseTime(args.getString(2)))
                    }
                    3 -> msg.optJSONObject("result")?.let { r ->
                        for (k in r.keys().asSequence().sortedBy { if (it == "DriverList") 0 else 1 }) {
                            r.optJSONObject(k)?.let { b.applySnapshot(k, it, received) }
                        }
                    }
                }
            }
        }
        val data = b.snapshot()
        println("live: ${data.drivers.size} drivers, ${data.laps.size} laps, ${data.gaps.size} gaps, ${data.pits.size} stops, statuses ${data.statuses.map { it.status }}")
        val model = CircleModel(data)
        val errors = ArrayList<Double>()
        for (lap in data.laps) {
            val s1 = lap.s1 ?: continue
            if (lap.number < 3 || lap.isPitOutLap) continue
            val t = lap.startMs + (s1 * 1000).toLong()
            if (model.statusAt(t - 120_000) != TrackStatus.GREEN || model.statusAt(t) != TrackStatus.GREEN) continue
            if (data.pits.any { it.driver == lap.driver && t in it.entryMs - 120_000..it.exitMs + 120_000 }) continue
            val before = model.frame(t - 1)
            val car = before.cars.firstOrNull { it.driver.number == lap.driver } ?: continue
            if (car.stopped || car.finished) continue
            errors += (car.progress - (lap.number - 1 + model.frame(t).sector2Start)) * before.referenceLap
        }
        val sorted = errors.map { abs(it) }.sorted()
        assumeTrue("not enough green laps recorded yet", sorted.size > 50)
        println("live accuracy: n=%d median=%.2fs p90=%.2fs max=%.2fs".format(
            sorted.size, sorted[sorted.size / 2], sorted[(sorted.size * 0.9).toInt()], sorted.last()))
        assertTrue(sorted[(sorted.size * 0.9).toInt()] < 1.0)
    }
}
