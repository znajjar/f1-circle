package com.f1circle.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.TreeMap

/**
 * Folds F1's live timing feed (the TimingData, TrackStatus, … topics) into the same [SessionData]
 * the replay uses, so the circle model works unchanged on live data.
 *
 * The subscribe response is a snapshot of the current state, which says where things stand but
 * not when they happened, so sector times in it are ignored; only live updates create timing
 * anchors. Called from the socket thread while the UI takes snapshots, hence the locking.
 */
class LiveSessionBuilder {

    private class LapBuilder(val number: Int) {
        var startMs: Long? = null
        var s1: Double? = null
        var s2: Double? = null
        var s3: Double? = null
        var duration: Double? = null
        var pitOut = false
    }

    private class DriverState {
        var completed = 0
        val laps = TreeMap<Int, LapBuilder>()
        var inPitSince: Long? = null
        /** Sector times from the connect snapshot, for the lap that was under way when we joined. */
        var joinedSectors: List<Double?>? = null
        /** The same, kept for the whole session as a hint about the lap's shape. */
        var priorSectors: DoubleArray? = null
        fun lap(n: Int) = laps.getOrPut(n) { LapBuilder(n) }
    }

    private val drivers = LinkedHashMap<Int, Driver>()
    private val states = HashMap<Int, DriverState>()
    private val gaps = ArrayList<GapSample>()
    private val positions = ArrayList<PositionSample>()
    private val pits = ArrayList<PitStop>()
    private val statuses = mutableListOf(StatusChange(Long.MIN_VALUE, TrackStatus.GREEN))
    private var session = RaceSession(0, "Live", "", "", 0)
    private var totalLaps: Int? = null
    private var startedMs: Long? = null
    private var sessionStatus = ""

    /** True once the session has started (lights out for a race). */
    val started: Boolean @Synchronized get() = startedMs != null

    val status: String @Synchronized get() = sessionStatus

    @Synchronized
    fun applySnapshot(topic: String, data: JSONObject, timeMs: Long) = apply(topic, data, timeMs, live = false)

    @Synchronized
    fun applyUpdate(topic: String, data: JSONObject, timeMs: Long) = apply(topic, data, timeMs, live = true)

    @Synchronized
    fun snapshot(): SessionData {
        val laps = states.flatMap { (d, st) ->
            st.laps.values.mapNotNull { b ->
                val start = b.startMs ?: return@mapNotNull null
                val sum = if (b.s1 != null && b.s2 != null && b.s3 != null) b.s1!! + b.s2!! + b.s3!! else null
                Lap(d, b.number, start, b.s1, b.s2, b.s3, b.duration ?: sum, b.pitOut)
            }
        }.sortedWith(compareBy({ it.driver }, { it.number }))
        // A stop still in progress has no exit yet; its huge lane time keeps it out of pit-loss maths
        val openStops = states.mapNotNull { (d, st) ->
            st.inPitSince?.let { PitStop(d, st.completed, it, Long.MAX_VALUE / 4, 9_999.0) }
        }
        return SessionData(
            session = session,
            drivers = drivers.values.toList(),
            laps = laps,
            gaps = gaps.sortedBy { it.timeMs },
            positions = positions.sortedBy { it.timeMs },
            pits = (pits + openStops).sortedBy { it.entryMs },
            statuses = statuses.toList(),
            scheduledLaps = totalLaps,
            startedMs = startedMs ?: (Long.MAX_VALUE / 4),
            priorSectorTimes = states.values.mapNotNull { it.priorSectors },
        )
    }

    private fun apply(topic: String, data: JSONObject, t: Long, live: Boolean) {
        when (topic) {
            "DriverList" -> applyDrivers(data)
            "SessionInfo" -> applySessionInfo(data)
            "SessionStatus" -> applySessionStatus(data.optString("Status"), t, live)
            "TrackStatus" -> trackStatus(data.optString("Status"))?.let { addStatus(t, it) }
            "LapCount" -> if (data.has("TotalLaps")) totalLaps = data.optInt("TotalLaps")
            "RaceControlMessages" -> applyRaceControl(data.opt("Messages"))
            "TimingData" -> data.optJSONObject("Lines")?.let { lines ->
                for (key in lines.keys()) {
                    val d = key.toIntOrNull() ?: continue
                    lines.optJSONObject(key)?.let { applyLine(d, it, t, live) }
                }
            }
        }
    }

    private fun applyDrivers(data: JSONObject) {
        for (key in data.keys()) {
            val d = key.toIntOrNull() ?: continue
            val o = data.optJSONObject(key) ?: continue
            val old = drivers[d]
            val hex = o.optString("TeamColour").takeIf { it.length == 6 }
            drivers[d] = Driver(
                number = d,
                tla = o.optString("Tla").ifEmpty { old?.tla ?: d.toString() },
                team = o.optString("TeamName").ifEmpty { old?.team ?: "" },
                colour = hex?.let { 0xFF000000 or it.toLong(16) } ?: old?.colour ?: 0xFF888888,
            )
            states.getOrPut(d) { DriverState() }
        }
    }

    private fun applySessionInfo(data: JSONObject) {
        val meeting = data.optJSONObject("Meeting") ?: return
        val start = runCatching {
            val local = LocalDateTime.parse(data.optString("StartDate"))
            // GmtOffset looks like "-05:00:00"
            val off = data.optString("GmtOffset").split(":").let { p ->
                val h = p[0].toInt()
                ZoneOffset.ofHoursMinutes(h, if (p[0].startsWith("-")) -p[1].toInt() else p[1].toInt())
            }
            local.toInstant(off).toEpochMilli()
        }.getOrDefault(0L)
        session = RaceSession(
            sessionKey = data.optInt("Key"),
            name = data.optString("Name", "Live"),
            circuit = meeting.optJSONObject("Circuit")?.optString("ShortName").orEmpty(),
            country = meeting.optJSONObject("Country")?.optString("Name").orEmpty(),
            startMs = start,
        )
    }

    private fun applySessionStatus(status: String, t: Long, live: Boolean) {
        if (status.isEmpty()) return
        sessionStatus = status
        if (status == "Started" && startedMs == null) {
            startedMs = t
            // Lights out: everyone starts lap 1 now. (Joining an already running session, we
            // don't know when that was, so laps only begin once cars are seen crossing lines.)
            if (live) for (st in states.values) if (st.completed == 0) st.lap(1).startMs = st.lap(1).startMs ?: t
        }
    }

    private fun applyRaceControl(messages: Any?) {
        val list = when (messages) {
            is JSONArray -> List(messages.length()) { messages.optJSONObject(it) }
            is JSONObject -> messages.keys().asSequence().map { messages.optJSONObject(it) }.toList()
            else -> emptyList()
        }
        for (m in list.filterNotNull()) {
            if (m.optString("Flag") == "CHEQUERED") {
                val t = runCatching { LocalDateTime.parse(m.optString("Utc")).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
                if (t != null) addStatus(t, TrackStatus.CHEQUERED)
            }
        }
    }

    private fun applyLine(d: Int, o: JSONObject, t: Long, live: Boolean) {
        val st = states.getOrPut(d) { DriverState() }

        o.optString("Position").toIntOrNull()?.let { positions += PositionSample(t, d, it) }
        if (o.has("GapToLeader")) parseGap(o.optString("GapToLeader"), t, d)?.let { gaps += it }

        if (o.has("NumberOfLaps")) {
            val n = o.optInt("NumberOfLaps")
            if (live && n > st.completed) {
                // Crossed the line: lap n is done and lap n+1 starts now
                st.lap(n + 1).startMs = t
                st.joinedSectors = null
            }
            st.completed = maxOf(st.completed, n)
        }

        if (!live) {
            o.opt("Sectors")?.let { sectors ->
                val values = arrayOfNulls<Double>(3)
                forEachIndexed(sectors) { i, sec -> if (i < 3) values[i] = sec.optString("Value").toDoubleOrNull() }
                st.joinedSectors = values.toList()
                if (values.all { it != null }) st.priorSectors = DoubleArray(3) { values[it]!! }
            }
        }

        if (live) {
            o.opt("Sectors")?.let { sectors ->
                forEachIndexed(sectors) { i, sec ->
                    val v = sec.optString("Value").toDoubleOrNull()
                    if (v != null) assignSector(st, i, v, t)
                }
            }
            o.optJSONObject("LastLapTime")?.optString("Value")?.let(::parseLapTime)?.let { v ->
                val target = st.laps.descendingMap().values.firstOrNull { it.s3 != null && it.duration == null }
                    ?: st.laps[st.completed]
                target?.duration = v
            }
            if (o.optBoolean("PitOut")) st.lap(st.completed + 1).pitOut = true
        }

        if (o.has("InPit")) {
            val inPit = o.optBoolean("InPit")
            val since = st.inPitSince
            if (inPit && since == null) st.inPitSince = t
            if (!inPit && since != null) {
                st.inPitSince = null
                // A car already in the pits when we connected has an unknown entry time
                if (live) pits += PitStop(d, st.completed, since, t, (t - since) / 1000.0)
            }
        }
    }

    /**
     * Sector times arrive as each sector is completed. S3 can land just before or just after the
     * lap counter ticks over, so each value goes to the lap that is actually waiting for it.
     */
    private fun assignSector(st: DriverState, i: Int, value: Double, t: Long) {
        val lap = when (i) {
            0 -> st.lap(st.completed + 1).takeIf { it.s1 == null }
            1 -> st.laps.descendingMap().values.firstOrNull { it.s1 != null && it.s2 == null }
                ?: joinedLap(st)
            2 -> st.laps.descendingMap().values.firstOrNull { it.s2 != null && it.s3 == null }
            else -> null
        } ?: return
        when (i) {
            0 -> lap.s1 = value
            1 -> lap.s2 = value
            else -> lap.s3 = value
        }
        // Joined mid-lap: the sector time tells us when the lap began
        if (lap.startMs == null) {
            val elapsed = listOfNotNull(lap.s1, lap.s2.takeIf { i >= 1 }, lap.s3.takeIf { i >= 2 }).sum()
            lap.startMs = t - (elapsed * 1000).toLong()
        }
    }

    /**
     * A car reaching S2 on the lap that was under way when we joined crossed S1 before we connected,
     * so the S1 time in the snapshot belongs to this lap; with it, the S2 crossing becomes a usable
     * anchor instead of waiting up to a lap for the next line crossing.
     */
    private fun joinedLap(st: DriverState): LapBuilder? {
        val s1 = st.joinedSectors?.getOrNull(0) ?: return null
        val lap = st.lap(st.completed + 1)
        if (lap.s1 != null || lap.startMs != null) return null
        lap.s1 = s1
        st.joinedSectors = null
        return lap
    }

    private fun addStatus(t: Long, s: TrackStatus) {
        if (statuses.last().status == TrackStatus.CHEQUERED) return
        if (statuses.last().status != s) statuses += StatusChange(maxOf(t, statuses.last().timeMs), s)
    }

    companion object {
        fun parseTime(iso: String): Long = Instant.parse(iso).toEpochMilli()

        private fun trackStatus(code: String): TrackStatus? = when (code) {
            "1", "2" -> TrackStatus.GREEN
            "4" -> TrackStatus.SAFETY_CAR
            "5" -> TrackStatus.RED
            "6" -> TrackStatus.VSC
            "7" -> TrackStatus.VSC_ENDING
            else -> null
        }

        /** "+1.234", "1L", "LAP 23" (the leader) or "". */
        internal fun parseGap(s: String, t: Long, d: Int): GapSample? {
            val v = s.trim()
            return when {
                v.isEmpty() -> null
                v.startsWith("LAP") -> GapSample(t, d, 0.0, 0)
                v.endsWith("L") -> GapSample(t, d, null, v.dropLast(1).trim().toIntOrNull() ?: 1)
                else -> v.removePrefix("+").toDoubleOrNull()?.let { GapSample(t, d, it, 0) }
            }
        }

        /** "1:27.733" or "27.733" */
        internal fun parseLapTime(s: String): Double? {
            if (s.isBlank()) return null
            val parts = s.split(":")
            return if (parts.size == 2) parts[0].toIntOrNull()?.let { m -> parts[1].toDoubleOrNull()?.let { m * 60 + it } }
            else s.toDoubleOrNull()
        }

        /** Sectors arrive as an array in snapshots and as {"0": …} objects in updates. */
        private fun forEachIndexed(sectors: Any, block: (Int, JSONObject) -> Unit) {
            when (sectors) {
                is JSONArray -> for (i in 0 until sectors.length()) sectors.optJSONObject(i)?.let { block(i, it) }
                is JSONObject -> for (k in sectors.keys()) k.toIntOrNull()?.let { i -> sectors.optJSONObject(k)?.let { block(i, it) } }
            }
        }
    }
}
