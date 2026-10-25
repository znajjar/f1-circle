package com.f1circle.app.model

import com.f1circle.app.data.Driver
import com.f1circle.app.data.GapSample
import com.f1circle.app.data.Lap
import com.f1circle.app.data.SessionData
import com.f1circle.app.data.TrackStatus
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * "Progress" is laps completed plus the fraction of the current lap, where the fraction is measured
 * in *time* (share of a reference lap), not distance. One second therefore spans the same angle
 * everywhere on the circle, which keeps gaps and the pit-loss band honest.
 */
data class CarState(
    val driver: Driver,
    val progress: Double,
    val position: Int?,
    /** Seconds behind the leader; null when lapped or unknown. */
    val gapToLeader: Double?,
    val lapsBehind: Int,
    val inPit: Boolean,
    val stopped: Boolean,
    val finished: Boolean,
)

data class CircleFrame(
    val timeMs: Long,
    val lap: Int,
    val totalLaps: Int,
    val status: TrackStatus,
    val referenceLap: Double,
    /** Where the S1/S2 and S2/S3 lines sit as a fraction of the lap. */
    val sector2Start: Double,
    val sector3Start: Double,
    val leader: Int?,
    val cars: List<CarState>,
)

enum class PitLossSource { MEASURED, PIT_LANE, DEFAULT }

data class PitWindow(
    val lossSeconds: Double,
    val source: PitLossSource,
    val rejoinProgress: Double,
    val rejoinPosition: Int,
    val carAhead: CarState?,
    val carBehind: CarState?,
)

/**
 * Places every car on the circle at any moment of a session, using only data that would already
 * have arrived by that moment (so the replay behaves like a live feed would).
 *
 * - Each car's own sector-line crossings are exact anchors; between them it moves at its recent pace.
 * - A car's gap to the leader says it is where the leader was that many seconds ago, so
 *   non-leaders are placed on the leader's (interpolated) path, which is far more precise than
 *   their own extrapolation.
 */
class CircleModel(private val data: SessionData, private val useGaps: Boolean = true) {

    private class Anchors(val times: LongArray, val laps: IntArray, val points: IntArray)

    private class GreenLap(val driver: Int, val endMs: Long, val duration: Double, val f2: Double, val f3: Double)

    /** Learned sector-line positions, plus how slow a car may plausibly be running right now. */
    private class Fractions(val s2: Double, val s3: Double, val maxPace: Double = 3.0)

    private val anchors: Map<Int, Anchors> = data.laps.groupBy { it.driver }.mapValues { (_, laps) -> buildAnchors(laps) }
    private val lapsByDriver: Map<Int, Map<Int, Lap>> = data.laps.groupBy { it.driver }.mapValues { (_, l) -> l.associateBy { it.number } }

    private val statusTimes = data.statuses.map { it.timeMs }.toLongArray()
    private val chequeredMs = data.statuses.firstOrNull { it.status == TrackStatus.CHEQUERED }?.timeMs

    private val gapTimes: Map<Int, LongArray>
    private val gapSamples: Map<Int, List<GapSample>>

    private val positionTimes: Map<Int, LongArray>
    private val positionValues: Map<Int, IntArray>
    private val leaderTimes: LongArray
    private val leaderValues: IntArray

    private val greenLaps: List<GreenLap>
    private val greenLapEnds: LongArray
    private val greenS2Prefix: DoubleArray
    private val greenS3Prefix: DoubleArray
    private val greenLapsByDriver: Map<Int, List<GreenLap>>

    /** A clean green-flag stop: when it became known, its raw loss, and its pit-lane time. */
    private class PitLossSample(val knownMs: Long, val rawLoss: Double, val lane: Double)

    private val pitLossSamples: List<PitLossSample>

    init {
        val gapsByDriver = data.gaps.groupBy { it.driver }
        gapSamples = gapsByDriver
        gapTimes = gapsByDriver.mapValues { (_, g) -> g.map { it.timeMs }.toLongArray() }

        val posByDriver = data.positions.groupBy { it.driver }
        positionTimes = posByDriver.mapValues { (_, p) -> p.map { it.timeMs }.toLongArray() }
        positionValues = posByDriver.mapValues { (_, p) -> p.map { it.position }.toIntArray() }
        val lt = ArrayList<Long>()
        val lv = ArrayList<Int>()
        for (p in data.positions) {
            if (p.position == 1 && lv.lastOrNull() != p.driver) {
                lt += p.timeMs
                lv += p.driver
            }
        }
        leaderTimes = lt.toLongArray()
        leaderValues = lv.toIntArray()

        var fastest = Double.MAX_VALUE
        greenLaps = data.laps.filter { it.endMs != null }.sortedBy { it.endMs }.mapNotNull { lap ->
            val dur = lap.duration!!
            val end = lap.endMs!!
            if (lap.number >= 2) fastest = minOf(fastest, dur)
            val s1 = lap.s1 ?: return@mapNotNull null
            val s2 = lap.s2 ?: return@mapNotNull null
            val inLap = data.pits.any { it.driver == lap.driver && it.entryMs in lap.startMs..end }
            if (lap.number < 2 || lap.isPitOutLap || inLap || !isAllGreen(lap.startMs, end)) return@mapNotNull null
            // Formation laps after a red flag, spins, traffic jams
            if (dur > fastest * 1.12) return@mapNotNull null
            GreenLap(lap.driver, end, dur, s1 / dur, (s1 + s2) / dur)
        }
        greenLapEnds = greenLaps.map { it.endMs }.toLongArray()
        greenS2Prefix = DoubleArray(greenLaps.size + 1).also { a -> greenLaps.forEachIndexed { i, g -> a[i + 1] = a[i] + g.f2 } }
        greenS3Prefix = DoubleArray(greenLaps.size + 1).also { a -> greenLaps.forEachIndexed { i, g -> a[i + 1] = a[i] + g.f3 } }
        greenLapsByDriver = greenLaps.groupBy { it.driver }

        pitLossSamples = data.pits.mapNotNull { pit ->
            if (pit.laneDuration > 60) return@mapNotNull null
            val laps = lapsByDriver[pit.driver] ?: return@mapNotNull null
            val outLap = laps.values.firstOrNull { l -> l.endMs != null && pit.exitMs in l.startMs until l.endMs!! } ?: return@mapNotNull null
            val inLap = laps[outLap.number - 1] ?: return@mapNotNull null
            val stintEnd = outLap.endMs ?: return@mapNotNull null
            if (inLap.duration == null || outLap.duration == null || !isAllGreen(inLap.startMs, stintEnd)) return@mapNotNull null
            val base = greenLapsByDriver[pit.driver].orEmpty().filter { it.endMs <= inLap.startMs }.takeLast(3)
            if (base.isEmpty()) return@mapNotNull null
            val loss = inLap.duration + outLap.duration - 2 * base.map { it.duration }.average()
            if (loss in 10.0..50.0) PitLossSample(stintEnd, loss, pit.laneDuration) else null
        }.sortedBy { it.knownMs }
    }

    val startMs: Long get() = data.raceStartMs - 30_000
    val endMs: Long get() = data.raceEndMs + 30_000

    fun frame(now: Long): CircleFrame {
        val status = statusAt(now)
        val leader = leaderAt(now)
        // A slow previous sector (standing start, spin) shouldn't drag a car back under green
        val fr = fractionsAt(now, maxPace = if (status == TrackStatus.GREEN) 1.25 else 3.0)
        val tRef = referenceLap(now, leader)

        /** A car is where the leader was [GapSample.gap] seconds ago. */
        fun viaLeader(d: Int, gap: GapSample?): Double? {
            if (!useGaps || leader == null || d == leader || gap?.gap == null || now - gap.timeMs > 20_000) return null
            val la = anchors[leader] ?: return null
            if (lastAtOrBefore(la.times, now) < 0) return null
            return progressAt(la, now - (gap.gap * 1000).toLong(), now, fr, tRef)
        }

        val cars = data.drivers.mapNotNull { driver ->
            val d = driver.number
            val a = anchors[d]
            val position = positionAt(d, now)
            val gap = latestGap(d, now)
            val kNow = if (a == null) -1 else lastAtOrBefore(a.times, now)
            val inPit = data.pits.any { it.driver == d && now in it.entryMs..it.exitMs }

            if (a == null || kNow < 0) {
                if (now < data.raceStartMs) {
                    // On the grid: stagger cars just behind the line
                    return@mapNotNull CarState(driver, -0.004 * (position ?: 20), position, gap?.gap, gap?.lapsBehind ?: 0,
                        inPit = false, stopped = false, finished = false)
                }
                // Joined a live session part-way: until this car crosses a timing line, its gap is all we have
                val p = viaLeader(d, gap) ?: return@mapNotNull null
                return@mapNotNull CarState(driver, p, position, gap?.gap, gap?.lapsBehind ?: 0, inPit, stopped = false, finished = false)
            }

            val finishAnchor = chequeredMs?.let { tc ->
                (0..kNow).firstOrNull { a.points[it] == 0 && a.times[it] >= tc }
            }
            if (finishAnchor != null) {
                return@mapNotNull CarState(driver, progressOf(a, finishAnchor, fr), position, gap?.gap,
                    gap?.lapsBehind ?: 0, inPit = false, stopped = false, finished = true)
            }

            val segment = (nextProgress(a, kNow, fr) - progressOf(a, kNow, fr)) * tRef * paceRatio(a, kNow, fr, tRef)
            val stopped = !inPit && status != TrackStatus.RED && (now - a.times[kNow]) / 1000.0 > maxOf(3 * segment, 60.0)

            var progress = progressAt(a, now, now, fr, tRef)
            if (!stopped) viaLeader(d, gap)?.let { if (abs(it - progress) < 0.3) progress = it }
            CarState(driver, progress, position, gap?.gap, gap?.lapsBehind ?: 0, inPit, stopped, finished = false)
        }

        val leaderProgress = cars.firstOrNull { it.driver.number == leader }?.progress ?: cars.maxOfOrNull { it.progress } ?: 0.0
        val lap = if (now < data.raceStartMs) 0 else (floor(leaderProgress).toInt() + 1).coerceIn(1, data.totalLaps)
        return CircleFrame(now, lap, data.totalLaps, status, tRef, fr.s2, fr.s3, leader, cars)
    }

    /** Where [car] would rejoin if it pitted right now. [lossAdjust] is the user's manual tweak. */
    fun pitWindow(frame: CircleFrame, car: CarState, lossAdjust: Double): PitWindow? {
        if (car.inPit || car.stopped || car.finished) return null
        if (frame.status == TrackStatus.RED || frame.status == TrackStatus.CHEQUERED || frame.lap == 0) return null
        val (base, source) = pitLoss(frame.timeMs)
        // A stop costs less under neutralisation because the field is lapping slowly
        val factor = when (frame.status) {
            TrackStatus.SAFETY_CAR -> 0.55
            TrackStatus.VSC, TrackStatus.VSC_ENDING -> 0.65
            else -> 1.0
        }
        val loss = (base + lossAdjust) * factor
        val rejoin = car.progress - loss / frame.referenceLap
        val others = frame.cars.filter { it !== car && !it.stopped && !it.finished }
        return PitWindow(
            lossSeconds = loss,
            source = source,
            rejoinProgress = rejoin,
            rejoinPosition = others.count { it.progress > rejoin } + 1,
            carAhead = others.filter { it.progress > rejoin }.minByOrNull { it.progress },
            carBehind = others.filter { it.progress <= rejoin }.maxByOrNull { it.progress },
        )
    }

    /**
     * Best pit-loss estimate so far: measured from clean green-flag stops once there are two,
     * otherwise the typical pit-lane time (which tracks the real loss within a second or so).
     */
    fun pitLoss(now: Long): Pair<Double, PitLossSource> {
        val lanes = data.pits.filter { it.exitMs <= now && it.laneDuration <= 60 }.map { it.laneDuration }
        if (lanes.isEmpty()) return DEFAULT_PIT_LOSS to PitLossSource.DEFAULT
        val typicalLane = median(lanes)
        // Swap each stop's lane time for the typical one so a slow stop doesn't skew the estimate
        val measured = pitLossSamples.filter { it.knownMs <= now }.map { it.rawLoss - (it.lane - typicalLane) }
        if (measured.size >= 2) return median(measured) to PitLossSource.MEASURED
        return typicalLane to PitLossSource.PIT_LANE
    }

    // ---- per-car progress ----

    /**
     * Progress of the car with anchors [a] at time [t], using only anchors known by [now].
     * Between two known anchors it interpolates; past the last one it moves at the car's recent
     * pace but never beyond the next timing line it hasn't been seen crossing.
     */
    private fun progressAt(a: Anchors, t: Long, now: Long, fr: Fractions, tRef: Double): Double {
        val kNow = lastAtOrBefore(a.times, now)
        if (kNow < 0) return 0.0
        val k = minOf(lastAtOrBefore(a.times, t), kNow)
        if (k < 0) return progressOf(a, 0, fr) - (a.times[0] - t) / 1000.0 / tRef
        if (k < kNow) {
            val p0 = progressOf(a, k, fr)
            val p1 = progressOf(a, k + 1, fr)
            val span = (a.times[k + 1] - a.times[k]).toDouble()
            return p0 + (p1 - p0) * ((t - a.times[k]) / span)
        }
        val p0 = progressOf(a, kNow, fr)
        val next = nextProgress(a, kNow, fr)
        val p = p0 + (t - a.times[kNow]) / 1000.0 / (tRef * paceRatio(a, kNow, fr, tRef))
        return minOf(p, next - 0.002)
    }

    /** How much slower than the reference the car covered its last timed stretch (≈1 green, ≈1.5 under SC). */
    private fun paceRatio(a: Anchors, k: Int, fr: Fractions, tRef: Double): Double {
        if (k < 1) return 1.0
        val expected = (progressOf(a, k, fr) - progressOf(a, k - 1, fr)) * tRef
        if (expected <= 0) return 1.0
        return ((a.times[k] - a.times[k - 1]) / 1000.0 / expected).coerceIn(0.85, fr.maxPace)
    }

    private fun progressOf(a: Anchors, i: Int, fr: Fractions): Double = a.laps[i] + when (a.points[i]) {
        0 -> 0.0
        1 -> fr.s2
        else -> fr.s3
    }

    private fun nextProgress(a: Anchors, i: Int, fr: Fractions): Double = a.laps[i] + when (a.points[i]) {
        0 -> fr.s2
        1 -> fr.s3
        else -> 1.0
    }

    // ---- session-wide state at a moment ----

    private fun fractionsAt(now: Long, maxPace: Double): Fractions {
        val n = lastAtOrBefore(greenLapEnds, now) + 1
        if (n == 0) return Fractions(1 / 3.0, 2 / 3.0, maxPace)
        return Fractions(greenS2Prefix[n] / n, greenS3Prefix[n] / n, maxPace)
    }

    /** Average of the leader's last 5 green laps, falling back to the field's. */
    fun referenceLap(now: Long, leader: Int? = leaderAt(now)): Double {
        val own = leader?.let { greenLapsByDriver[it] }.orEmpty().filter { it.endMs <= now }.takeLast(5)
        if (own.isNotEmpty()) return own.map { it.duration }.average()
        val n = lastAtOrBefore(greenLapEnds, now) + 1
        if (n > 0) return greenLaps.subList(maxOf(0, n - 5), n).map { it.duration }.average()
        return data.laps.mapNotNull { it.duration }.minOrNull()?.times(1.03) ?: 90.0
    }

    fun statusAt(t: Long): TrackStatus = data.statuses[maxOf(0, lastAtOrBefore(statusTimes, t))].status

    private fun isAllGreen(from: Long, to: Long): Boolean {
        val i = maxOf(0, lastAtOrBefore(statusTimes, from))
        return data.statuses[i].status == TrackStatus.GREEN && (i + 1 >= statusTimes.size || statusTimes[i + 1] > to)
    }

    private fun leaderAt(t: Long): Int? = lastAtOrBefore(leaderTimes, t).let { if (it < 0) null else leaderValues[it] }

    private fun positionAt(d: Int, t: Long): Int? {
        val times = positionTimes[d] ?: return null
        val i = lastAtOrBefore(times, t)
        return if (i < 0) null else positionValues.getValue(d)[i]
    }

    private fun latestGap(d: Int, t: Long): GapSample? {
        val times = gapTimes[d] ?: return null
        val i = lastAtOrBefore(times, t)
        return if (i < 0) null else gapSamples.getValue(d)[i]
    }

    companion object {
        const val DEFAULT_PIT_LOSS = 22.0

        private fun buildAnchors(laps: List<Lap>): Anchors {
            val raw = ArrayList<Triple<Long, Int, Int>>()
            laps.sortedBy { it.number }.forEachIndexed { i, lap ->
                val done = lap.number - 1
                raw += Triple(lap.startMs, done, 0)
                lap.s1?.let { s1 ->
                    raw += Triple(lap.startMs + (s1 * 1000).toLong(), done, 1)
                    lap.s2?.let { s2 -> raw += Triple(lap.startMs + ((s1 + s2) * 1000).toLong(), done, 2) }
                }
                if (i == laps.lastIndex) lap.endMs?.let { raw += Triple(it, lap.number, 0) }
            }
            // Anchors must be strictly ordered in time; drop anything the feed got out of order
            val kept = ArrayList<Triple<Long, Int, Int>>()
            for (r in raw.sortedBy { it.first }) {
                val last = kept.lastOrNull()
                if (last == null || (r.first > last.first && r.second * 3 + r.third > last.second * 3 + last.third)) kept += r
            }
            return Anchors(
                kept.map { it.first }.toLongArray(),
                kept.map { it.second }.toIntArray(),
                kept.map { it.third }.toIntArray(),
            )
        }

        private fun median(values: List<Double>): Double {
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }

        /** Index of the last element <= [t], or -1. */
        fun lastAtOrBefore(sorted: LongArray, t: Long): Int {
            var lo = 0
            var hi = sorted.size - 1
            var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid] <= t) {
                    ans = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return ans
        }
    }
}

/** Gap of [car] relative to [ref] as shown on its label, e.g. "+3.2", "-1.4", "1L +12.0". */
fun gapText(car: CarState, ref: CarState, referenceLap: Double): String {
    if (car === ref) return ""
    val carGap = car.gapToLeader
    val refGap = ref.gapToLeader
    val lapped = car.lapsBehind != ref.lapsBehind
    if (carGap != null && refGap != null && !lapped) return signed(carGap - refGap)
    val diff = ref.progress - car.progress
    val laps = floor(abs(diff)).toInt()
    val seconds = (abs(diff) - laps) * referenceLap
    return when {
        laps == 0 -> signed(diff * referenceLap)
        diff > 0 -> "${laps}L " + signed(seconds)
        else -> "-${laps}L"
    }
}

private fun signed(seconds: Double) = String.format(Locale.US, "%+.1f", seconds)
