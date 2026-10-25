package com.f1circle.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Connects to F1's live timing service (ASP.NET SignalR Core, JSON protocol) and feeds every
 * update into a [LiveSessionBuilder]. Reconnects on its own until [stop] is called.
 *
 * No login is needed for the topics used here; F1 only withholds car positions and a few extras
 * from anonymous clients.
 */
class LiveTimingClient(
    private val builder: LiveSessionBuilder,
    private val onState: (String) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var running = false
    @Volatile private var socket: WebSocket? = null
    private var thread: Thread? = null

    /** Feed timestamps minus local receive times; the largest is the least-delayed message. */
    private val offsets = ArrayDeque<Long>()
    @Volatile private var offsetMs = 0L

    /** Current time on F1's clock, which is what every event in the feed is stamped with. */
    fun serverNow(): Long = System.currentTimeMillis() + offsetMs

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "live-timing").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        socket?.close(1000, null)
        thread?.interrupt()
    }

    private fun loop() {
        var backoff = 2_000L
        while (running) {
            val closed = CountDownLatch(1)
            try {
                onState("Connecting…")
                connect { closed.countDown() }
                backoff = 2_000L
                closed.await()
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                onState("Connection failed: ${e.message}")
            }
            if (!running) return
            onState("Reconnecting…")
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                return
            }
            backoff = minOf(backoff * 2, 30_000L)
        }
    }

    private fun connect(onClosed: () -> Unit) {
        // The load balancer pins the socket to the server that negotiated, via its cookies
        val probe = http.newCall(Request.Builder().url(NEGOTIATE).method("OPTIONS", null).build()).execute()
        val cookies = probe.headers("Set-Cookie").map { it.substringBefore(';') }
        probe.close()
        val cookieHeader = cookies.joinToString("; ")

        val negotiate = http.newCall(
            Request.Builder().url("$NEGOTIATE?negotiateVersion=1")
                .header("Cookie", cookieHeader)
                .post(ByteArray(0).toRequestBody(null))
                .build()
        ).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("negotiate returned ${r.code}")
            JSONObject(r.body!!.string())
        }
        val token = negotiate.getString("connectionToken")

        val request = Request.Builder().url("$HUB?id=$token").header("Cookie", cookieHeader).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"protocol":"json","version":1}$RS""")
                val subscribe = JSONObject()
                    .put("type", 1)
                    .put("invocationId", "0")
                    .put("target", "Subscribe")
                    .put("arguments", JSONArray().put(JSONArray(TOPICS)))
                webSocket.send(subscribe.toString() + RS)
                onState("Connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val received = System.currentTimeMillis()
                for (part in text.split(RS)) {
                    if (part.isBlank()) continue
                    runCatching { handle(webSocket, JSONObject(part), received) }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed()

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onState("Disconnected: ${t.message}")
                onClosed()
            }
        })
    }

    private fun handle(ws: WebSocket, msg: JSONObject, received: Long) {
        when (msg.optInt("type")) {
            // Invocation: a live update
            1 -> if (msg.optString("target") == "feed") {
                val args = msg.getJSONArray("arguments")
                val topic = args.getString(0)
                val data = args.optJSONObject(1) ?: return
                val t = runCatching { LiveSessionBuilder.parseTime(args.getString(2)) }.getOrDefault(received + offsetMs)
                trackOffset(t - received)
                builder.applyUpdate(topic, data, t)
            }
            // Completion of Subscribe: the current state of every topic
            3 -> msg.optJSONObject("result")?.let { result ->
                val now = serverNow()
                // Drivers first so later topics can attach to them
                val keys = result.keys().asSequence().sortedBy { if (it == "DriverList") 0 else 1 }
                for (topic in keys) result.optJSONObject(topic)?.let { builder.applySnapshot(topic, it, now) }
            }
            6 -> ws.send("""{"type":6}$RS""")
            7 -> ws.close(1000, null)
        }
    }

    private fun trackOffset(sample: Long) {
        synchronized(offsets) {
            offsets.addLast(sample)
            if (offsets.size > 200) offsets.removeFirst()
            offsetMs = offsets.max()
        }
    }

    private companion object {
        const val RS = "\u001e"
        const val HUB = "https://livetiming.formula1.com/signalrcore"
        const val NEGOTIATE = "$HUB/negotiate"
        val TOPICS = listOf(
            "Heartbeat", "DriverList", "SessionInfo", "SessionStatus", "TrackStatus", "LapCount",
            "TimingData", "RaceControlMessages",
        )
    }
}
