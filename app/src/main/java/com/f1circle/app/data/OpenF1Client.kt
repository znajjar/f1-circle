package com.f1circle.app.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads historical session data from OpenF1 (free, no login). Finished sessions never change,
 * so responses are cached on disk. The session list is refetched each time but falls back to the
 * last copy, because OpenF1 locks out free users entirely while a live session is running.
 */
class OpenF1Client(private val cacheDir: File) {

    fun raceSessions(year: Int): List<RaceSession> {
        val now = System.currentTimeMillis()
        val file = File(cacheDir, "openf1/sessions-$year.json")
        val json = try {
            get("sessions?year=$year&session_type=Race", file, refresh = true)
        } catch (e: IOException) {
            if (file.exists()) file.readText() else throw e
        }
        return OpenF1Parser.parseSessions(json).filter { it.startMs < now }
    }

    fun loadSession(session: RaceSession, onProgress: (String) -> Unit): SessionData {
        fun fetch(endpoint: String, label: String): String {
            onProgress(label)
            val file = File(cacheDir, "openf1/${session.sessionKey}/$endpoint.json")
            return get("$endpoint?session_key=${session.sessionKey}", file)
        }
        val drivers = fetch("drivers", "Drivers")
        val laps = fetch("laps", "Lap times")
        val intervals = fetch("intervals", "Gaps")
        val positions = fetch("position", "Positions")
        val pits = fetch("pit", "Pit stops")
        val raceControl = fetch("race_control", "Race control")
        onProgress("Preparing replay")
        return OpenF1Parser.parseSession(session, drivers, laps, intervals, positions, pits, raceControl)
    }

    private fun get(path: String, cacheFile: File, refresh: Boolean = false): String {
        if (!refresh && cacheFile.exists()) return cacheFile.readText()
        // Free tier allows 3 requests/second
        Thread.sleep(400)
        var attempt = 0
        while (true) {
            val conn = URL("$BASE/$path").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            try {
                when (val code = conn.responseCode) {
                    200 -> {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        cacheFile.parentFile?.mkdirs()
                        cacheFile.writeText(body)
                        return body
                    }
                    429, 502, 503 -> if (++attempt > 3) throw IOException("OpenF1 returned $code") else Thread.sleep(2_000L * attempt)
                    401 -> throw IOException("OpenF1 is locked to paid users while a live session is running. Try again after it ends.")
                    else -> throw IOException("OpenF1 returned $code for $path")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private companion object {
        const val BASE = "https://api.openf1.org/v1"
    }
}
