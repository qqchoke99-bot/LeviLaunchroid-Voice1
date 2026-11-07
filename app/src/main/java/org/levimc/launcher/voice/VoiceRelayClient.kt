package org.levimc.launcher.voice

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * WSS client for the PCL voice relay. This class only carries already-encoded
 * PCLV/Opus packets; the local mod/launcher socket bridge and UI configuration
 * are separate integration steps.
 */
class VoiceRelayClient(private val listener: Listener) : AutoCloseable {
    interface Listener {
        fun onConnectionChanged(connected: Boolean, detail: String)
        fun onVoicePacket(packet: ByteArray)
    }

    companion object {
        private const val TAG = "PCLVoiceRelay"
        private const val HEADER_BYTES = 36
        private const val MAX_PACKET_BYTES = HEADER_BYTES + 400
        private const val PROTOCOL_VERSION = 2
        private const val MAX_QUEUED_BYTES = 64 * 1024L
        private const val INITIAL_RETRY_MS = 500L
        private const val MAX_RETRY_MS = 10_000L
    }

    private val lock = Any()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "pcl-relay-reconnect").apply { isDaemon = true }
    }
    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var running = false
    private var relayUrl = ""
    private var room = ""
    private var token = ""
    private var socket: WebSocket? = null
    private var generation = 0L
    private var retryDelayMs = INITIAL_RETRY_MS
    private var reconnectTask: ScheduledFuture<*>? = null

    /** Connect to a relay base URL, e.g. wss://voice.example.net. */
    fun connect(relayBaseUrl: String, roomId: String, roomToken: String) {
        require(relayBaseUrl.startsWith("wss://")) {
            "Relay must use WSS so room tokens and voice are encrypted in transit"
        }
        require(roomId.isNotBlank() && roomId.length <= 64)
        require(roomToken.isNotBlank())
        synchronized(lock) {
            stopLocked()
            relayUrl = relayBaseUrl.trimEnd('/')
            room = roomId
            token = roomToken
            running = true
            retryDelayMs = INITIAL_RETRY_MS
            openLocked()
        }
    }

    /** Returns false when disconnected, malformed, or the bounded send queue is full. */
    fun sendPacket(packet: ByteArray): Boolean {
        if (!validPacket(packet)) return false
        val current = synchronized(lock) {
            if (!running) null else socket
        } ?: return false
        if (current.queueSize() > MAX_QUEUED_BYTES) return false
        return current.send(ByteString.of(*packet.copyOf()))
    }

    private fun openLocked() {
        if (!running) return
        reconnectTask?.cancel(false)
        reconnectTask = null
        val id = ++generation
        val encodedRoom = URLEncoder.encode(room, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("$relayUrl/v1/voice?room=$encodedRoom")
            .header("Authorization", "Bearer $token")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) {
                    if (!running || generation != id) {
                        webSocket.cancel()
                        return
                    }
                    socket = webSocket
                    retryDelayMs = INITIAL_RETRY_MS
                }
                Log.i(TAG, "relay connected")
                listener.onConnectionChanged(true, "connected")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!isCurrent(id)) return
                val packet = bytes.toByteArray()
                if (!validPacket(packet)) {
                    Log.w(TAG, "relay sent a malformed voice packet; closing connection")
                    webSocket.close(1002, "invalid PCLV packet")
                    return
                }
                listener.onVoicePacket(packet)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.w(TAG, "unexpected text message from relay; closing connection")
                webSocket.close(1003, "binary voice packets required")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                disconnected(id, "closed ($code)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = response?.let { "HTTP ${it.code}" } ?:
                    (t.message ?: t.javaClass.simpleName)
                Log.w(TAG, "relay connection failed: $detail")
                disconnected(id, detail)
            }
        })
    }

    private fun disconnected(id: Long, detail: String) {
        synchronized(lock) {
            if (!running || generation != id) return
            socket = null
            scheduleReconnectLocked(detail)
        }
        listener.onConnectionChanged(false, detail)
    }

    private fun scheduleReconnectLocked(detail: String) {
        if (!running || reconnectTask != null) return
        val delay = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        reconnectTask = scheduler.schedule({
            synchronized(lock) {
                reconnectTask = null
                if (running) openLocked()
            }
        }, delay, TimeUnit.MILLISECONDS)
        Log.i(TAG, "retrying relay connection in ${delay}ms ($detail)")
    }

    private fun isCurrent(id: Long): Boolean = synchronized(lock) {
        running && generation == id
    }

    private fun stopLocked() {
        running = false
        generation++
        reconnectTask?.cancel(false)
        reconnectTask = null
        socket?.close(1000, "voice stopped")
        socket = null
    }

    override fun close() {
        synchronized(lock) { stopLocked() }
        scheduler.shutdownNow()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun validPacket(packet: ByteArray): Boolean {
        if (packet.size !in HEADER_BYTES..MAX_PACKET_BYTES) return false
        if (packet[0] != 'P'.code.toByte() || packet[1] != 'C'.code.toByte() ||
            packet[2] != 'L'.code.toByte() || packet[3] != 'V'.code.toByte() ||
            (packet[4].toInt() and 0xff) != PROTOCOL_VERSION
        ) return false
        val kind = packet[5].toInt() and 0xff
        val payloadSize = ByteBuffer.wrap(packet, 18, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        if (payloadSize != packet.size - HEADER_BYTES || payloadSize > 400) return false
        return (kind == 1 && payloadSize == 0) || (kind == 2 && payloadSize > 0)
    }

}
