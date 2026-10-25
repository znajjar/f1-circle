package com.f1circle.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

/** Turns raw OpenF1 (api.openf1.org/v1) JSON responses into [SessionData]. */
object OpenF1Parser {

    fun parseSessions(json: String): List<RaceSession> = objects(json).map {
        RaceSession(
            sessionKey = it.getInt("session_key"),
            name = it.optString("session_name"),
            circuit = it.optString("circuit_short_name"),
            country = it.optString("country_name"),
            startMs = time(it.getString("date_start")),
        )
    }.sortedBy { it.startMs }

    fun parseSession(
        session: RaceSession,
        drivers: String,
        laps: String,
        intervals: String,
        positions: String,
        pits: String,
        raceControl: String,
    ): SessionData {
        val lapList = parseLaps(laps)
        return SessionData(
            session = session,
            drivers = parseDrivers(drivers),
            laps = lapList,
            gaps = parseGaps(intervals),
            positions = objects(positions).mapNotNull {
                if (it.isNull("position")) null
                else PositionSample(time(it.getString("date")), it.getInt("driver_number"), it.getInt("position"))
            }.sortedBy { it.timeMs },
            pits = objects(pits).mapNotNull {
                val lane = it.optDoubleOrNull("lane_duration") ?: return@mapNotNull null
                val exit = time(it.getString("date"))
                // OpenF1 stamps the record when the car leaves the pit lane
                PitStop(it.getInt("driver_number"), it.optInt("lap_number"), exit - (lane * 1000).toLong(), exit, lane)
            }.sortedBy { it.entryMs },
            statuses = parseStatuses(raceControl, lapList),
        )
    }

    fun parseDrivers(json: String): List<Driver> = objects(json).map {
        val hex = it.optString("team_colour").takeIf { c -> c.length == 6 } ?: "888888"
        Driver(
            number = it.getInt("driver_number"),
            tla = it.optString("name_acronym").ifEmpty { it.getInt("driver_number").toString() },
            team = it.optString("team_name"),
            colour = 0xFF000000 or hex.toLong(16),
        )
    }.distinctBy { it.number }

    fun parseLaps(json: String): List<Lap> = objects(json).mapNotNull {
        if (it.isNull("date_start")) return@mapNotNull null
        Lap(
            driver = it.getInt("driver_number"),
            number = it.getInt("lap_number"),
            startMs = time(it.getString("date_start")),
            s1 = it.optDoubleOrNull("duration_sector_1"),
            s2 = it.optDoubleOrNull("duration_sector_2"),
            s3 = it.optDoubleOrNull("duration_sector_3"),
            duration = it.optDoubleOrNull("lap_duration"),
            isPitOutLap = it.optBoolean("is_pit_out_lap"),
        )
    }.sortedWith(compareBy({ it.driver }, { it.number }))

    fun parseGaps(json: String): List<GapSample> = objects(json).map {
        val t = time(it.getString("date"))
        val d = it.getInt("driver_number")
        when (val g = it.opt("gap_to_leader")) {
            is Number -> GapSample(t, d, g.toDouble(), 0)
            // "+1 LAP", "+2 LAPS"
            is String -> GapSample(t, d, null, g.filter(Char::isDigit).toIntOrNull() ?: 1)
            else -> GapSample(t, d, null, 0)
        }
    }.sortedBy { it.timeMs }

    /**
     * Derives a track-status timeline from race control messages. The feed doesn't always announce
     * the return to green, so a safety car "in this lap" ends when the leader next crosses the line,
     * and "VSC ending" ends after a short delay.
     */
    fun parseStatuses(json: String, laps: List<Lap>): List<StatusChange> {
        val lapStarts = laps.map { it.startMs }.sorted()
        val out = mutableListOf(StatusChange(Long.MIN_VALUE, TrackStatus.GREEN))
        var current = TrackStatus.GREEN
        var pendingGreenAt: Long? = null

        fun set(t: Long, s: TrackStatus) {
            if (s != current) {
                out += StatusChange(t, s)
                current = s
            }
            pendingGreenAt = null
        }

        for (m in objects(json).sortedBy { time(it.getString("date")) }) {
            val t = time(m.getString("date"))
            pendingGreenAt?.let { if (it <= t) set(it, TrackStatus.GREEN) }
            val msg = m.optString("message").uppercase()
            val category = m.optString("category")
            val flag = m.optString("flag").uppercase()
            val trackScope = m.optString("scope") == "Track"
            val isSc = category == "SafetyCar"
            when {
                flag == "CHEQUERED" -> set(t, TrackStatus.CHEQUERED)
                flag == "RED" || "RED FLAG" in msg -> set(t, TrackStatus.RED)
                isSc && ("VSC DEPLOYED" in msg || "VIRTUAL SAFETY CAR DEPLOYED" in msg) -> set(t, TrackStatus.VSC)
                isSc && ("VSC ENDING" in msg || "VIRTUAL SAFETY CAR ENDING" in msg) -> {
                    set(t, TrackStatus.VSC_ENDING)
                    pendingGreenAt = t + 15_000
                }
                isSc && "SAFETY CAR DEPLOYED" in msg -> set(t, TrackStatus.SAFETY_CAR)
                isSc && "IN THIS LAP" in msg -> pendingGreenAt = lapStarts.firstOrNull { it > t + 5_000 }
                current == TrackStatus.RED -> if (category == "SessionStatus" && "STARTED" in msg) set(t, TrackStatus.GREEN)
                current != TrackStatus.CHEQUERED && trackScope && (flag == "CLEAR" || flag == "GREEN") ->
                    set(t, TrackStatus.GREEN)
            }
        }
        pendingGreenAt?.let { if (current != TrackStatus.GREEN) out += StatusChange(it, TrackStatus.GREEN) }
        return out
    }

    private fun objects(json: String): List<JSONObject> {
        val arr = JSONArray(json)
        return List(arr.length()) { arr.getJSONObject(it) }
    }

    private fun time(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }
}
