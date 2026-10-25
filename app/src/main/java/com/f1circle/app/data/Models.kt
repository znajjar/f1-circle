package com.f1circle.app.data

/** All times are epoch milliseconds; durations are seconds unless the name says otherwise. */

data class RaceSession(
    val sessionKey: Int,
    val name: String,
    val circuit: String,
    val country: String,
    val startMs: Long,
)

data class Driver(
    val number: Int,
    val tla: String,
    val team: String,
    /** 0xAARRGGBB */
    val colour: Long,
)

data class Lap(
    val driver: Int,
    val number: Int,
    val startMs: Long,
    val s1: Double?,
    val s2: Double?,
    val s3: Double?,
    val duration: Double?,
    val isPitOutLap: Boolean,
) {
    val endMs: Long? get() = duration?.let { startMs + (it * 1000).toLong() }
}

/** [gap] is null when the car is lapped ([lapsBehind] > 0) or the gap is unknown. */
data class GapSample(val timeMs: Long, val driver: Int, val gap: Double?, val lapsBehind: Int)

data class PositionSample(val timeMs: Long, val driver: Int, val position: Int)

data class PitStop(val driver: Int, val lap: Int, val entryMs: Long, val exitMs: Long, val laneDuration: Double)

enum class TrackStatus { GREEN, SAFETY_CAR, VSC, VSC_ENDING, RED, CHEQUERED }

data class StatusChange(val timeMs: Long, val status: TrackStatus)

class SessionData(
    val session: RaceSession,
    val drivers: List<Driver>,
    val laps: List<Lap>,
    val gaps: List<GapSample>,
    val positions: List<PositionSample>,
    val pits: List<PitStop>,
    val statuses: List<StatusChange>,
) {
    val totalLaps: Int = laps.maxOfOrNull { it.number } ?: 0
    val raceStartMs: Long = laps.minOfOrNull { it.startMs } ?: session.startMs
    val raceEndMs: Long = laps.mapNotNull { it.endMs }.maxOrNull() ?: raceStartMs
}
