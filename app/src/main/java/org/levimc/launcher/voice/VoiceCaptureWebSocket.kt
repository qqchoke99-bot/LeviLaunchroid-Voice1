package org.levimc.launcher.voice

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Random
import kotlin.concurrent.thread

/**
 * Voice Chat Levi 0.5.0 - the bridge, launcher side.
 *
 * This is the WebSocket version of VoiceCaptureService.kt. Same microphone, same
 * 20 ms framing, same permission handling - only the transport changes:
 *
 *     ws://127.0.0.1:24457      (the mod listens; this service connects)
 *
 * Why a WebSocket instead of the abstract Unix socket: the mod can tell when this
 * service is gone (a TCP write fails immediately) instead of waiting out a
 * timeout, and a reconnect is a clean, ordered event. It is also the transport
 * that can be pointed at a relay later without touching the audio code.
 *
 * Frame layout, one binary message per 20 ms - byte-for-byte what the mod's UDP
 * and Unix paths already accept:
 *
 *     0..3    tag "VCL1"  (0x31 0x4C 0x43 0x56)
 *     4..643  320 little-endian signed 16-bit samples, 16 kHz mono
 *
 * No dependency is added: RFC 6455 client framing is ~40 lines below. The client
 * MUST mask its frames (the mod refuses unmasked ones on purpose).
 *
 * Drop-in use, in the launcher's MinecraftActivity (or wherever the game starts):
 *
 *     VoiceCaptureWebSocket.start(applicationContext)
 *
 * and stop it when the game screen goes away. It parks itself (releasing the
 * recorder) while the mod is not connected, so it costs nothing on a normal boot.
 */
class VoiceCaptureWebSocket : Service() {

    companion object {
        private const val TAG = "VoiceChatLevi"
        private const val HOST = "127.0.0.1"
        private const val PORT = 24457
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 320            // 20 ms at 16 kHz
        private const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val TAG_BYTES = byteArrayOf(0x31, 0x4C, 0x43, 0x56)  // "VCL1"

        private const val BACKOFF_MIN_MS = 500L
        private const val BACKOFF_MAX_MS = 10_000L
        private const val HEARTBEAT_FRAMES = 500         // 10 s of audio

        @Volatile private var running = false

        fun start(context: android.content.Context) {
            if (running) return
            running = true
            context.startService(Intent(context, VoiceCaptureWebSocket::class.java))
        }

        fun stop(context: android.content.Context) {
            running = false
            context.stopService(Intent(context, VoiceCaptureWebSocket::class.java))
        }
    }

    @Volatile private var stopping = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        stopping = false
        worker = thread(name = "vcl-bridge", isDaemon = true) { pump() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopping = true
        worker?.join(1_500)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ loop
    private fun pump() {
        var backoff = BACKOFF_MIN_MS
        while (!stopping) {
            var socket: Socket? = null
            try {
                if (!hasPermission()) {
                    // The launcher holds RECORD_AUDIO, so this is only reachable if
                    // the user revoked it. Say so once per attempt instead of
                    // pretending to stream silence.
                    Log.w(TAG, "bridge: RECORD_AUDIO not granted - ask the user once, then re-check")
                    Thread.sleep(BACKOFF_MAX_MS)
                    continue
                }
                socket = Socket()
                socket.connect(InetSocketAddress(HOST, PORT), 1_000)
                socket.tcpNoDelay = true
                handshake(socket)
                backoff = BACKOFF_MIN_MS
                Log.i(TAG, "bridge: connected to the mod on ws://$HOST:$PORT")
                stream(socket)
            } catch (e: IOException) {
                Log.i(TAG, "bridge: mod not listening (${e.message}) - retrying in ${backoff}ms")
            } catch (e: InterruptedException) {
                break
            } finally {
                try { socket?.close() } catch (_: IOException) {}
            }
            if (stopping) break
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
        }
        Log.i(TAG, "bridge: stopped")
    }

    // ------------------------------------------------------------- handshake
    private fun handshake(socket: Socket) {
        val keyBytes = ByteArray(16)
        Random().nextBytes(keyBytes)
        val key = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP)
        val request = buildString {
            append("GET / HTTP/1.1\r\n")
            append("Host: $HOST:$PORT\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        socket.getOutputStream().write(request.toByteArray()).also { socket.getOutputStream().flush() }

        val input = DataInputStream(socket.getInputStream())
        val head = StringBuilder()
        while (!head.contains("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) throw IOException("closed during handshake")
            head.append(b.toChar())
            if (head.length > 4096) throw IOException("handshake too long")
        }
        if (!head.startsWith("HTTP/1.1 101")) throw IOException("mod refused the upgrade")
        expectedAccept(key).let { expected ->
            if (!head.contains(expected)) throw IOException("bad Sec-WebSocket-Accept")
        }
    }

    private fun expectedAccept(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
        return "Sec-WebSocket-Accept: " +
            android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
    }

    // --------------------------------------------------------------- capture
    private fun stream(socket: Socket) {
        val output = socket.getOutputStream()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) throw IOException("AudioRecord reports no buffer")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,   // echo cancellation on the path
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME_BYTES * 8)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IOException("microphone busy or unavailable")
        }
        val pcm = ShortArray(FRAME_SAMPLES)
        val payload = ByteArray(4 + FRAME_BYTES)
        TAG_BYTES.copyInto(payload, 0)
        var frames = 0
        try {
            recorder.startRecording()
            Log.i(TAG, "bridge: streaming 16 kHz mono, 20 ms frames")
            while (!stopping) {
                var read = 0
                while (read < FRAME_SAMPLES) {
                    val got = recorder.read(pcm, read, FRAME_SAMPLES - read)
                    if (got <= 0) throw IOException("microphone read failed ($got)")
                    read += got
                }
                // Little-endian PCM16, exactly like AudioRecord's own layout.
                var offset = 4
                for (sample in pcm) {
                    payload[offset++] = (sample.toInt() and 0xFF).toByte()
                    payload[offset++] = ((sample.toInt() shr 8) and 0xFF).toByte()
                }
                sendBinary(output, payload)
                frames++
                if (frames % HEARTBEAT_FRAMES == 0) {
                    Log.i(TAG, "bridge: ${frames} frames sent (${frames * 20 / 1000} s of audio)")
                }
            }
        } finally {
            try { recorder.stop() } catch (_: IllegalStateException) {}
            recorder.release()
        }
    }

    // ------------------------------------------------- RFC 6455 client frame
    /** One masked binary frame: FIN|opcode 0x2, 7-bit or 16-bit length, 4-byte mask. */
    private fun sendBinary(output: OutputStream, payload: ByteArray) {
        val header: ByteArray
        if (payload.size < 126) {
            header = byteArrayOf(0x82.toByte(), (0x80 or payload.size).toByte())
        } else {
            header = byteArrayOf(
                0x82.toByte(),
                (0x80 or 126).toByte(),
                ((payload.size shr 8) and 0xFF).toByte(),
                (payload.size and 0xFF).toByte()
            )
        }
        val mask = ByteArray(4).also { Random().nextBytes(it) }
        val frame = ByteArray(header.size + 4 + payload.size)
        header.copyInto(frame, 0)
        mask.copyInto(frame, header.size)
        for (i in payload.indices) {
            frame[header.size + 4 + i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        output.write(frame)
        output.flush()          // one frame per 20 ms: latency beats batching here
    }

    private fun hasPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
