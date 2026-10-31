package org.levimc.launcher.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Voice Chat Levi 0.5.0 - the bridge, launcher side (client fixed: this file replaces
 * VoiceCaptureWebSocket.kt, which did not compile).
 *
 * The launcher records the microphone (it already holds RECORD_AUDIO) and streams
 * 20 ms frames to the mod over a loopback WebSocket:
 *
 *     ws://127.0.0.1:24457
 *
 * Frame layout, one binary message per 20 ms - byte-for-byte what the mod's UDP
 * and Unix paths already accept:
 *
 *     0..3    tag "VCL1"  (0x31 0x4C 0x43 0x56)
 *     4..643  320 little-endian signed 16-bit samples, 16 kHz mono
 *
 * Client framing is written out here (~40 lines, RFC 6455) so no dependency is
 * added. Frames MUST be masked; the mod refuses unmasked ones on purpose.
 *
 * Use it from the activity that hosts the game:
 *
 *     VoiceCaptureBridge.start(applicationContext)   // e.g. in onResume / onCreate
 *     VoiceCaptureBridge.stop()                      // in onPause / onDestroy
 *
 * It is deliberately NOT an android.app.Service, for three reasons that all cost
 * a build or a crash: a Service needs a manifest entry, startService() throws on
 * API 26+ unless it is a foreground service, and from API 34 a microphone service
 * needs foregroundServiceType="microphone" plus FOREGROUND_SERVICE_MICROPHONE.
 * A plain thread inside the app process needs none of that, and the capture only
 * matters while the game (and therefore the app) is in the foreground.
 */
object VoiceCaptureBridge {

    private const val TAG = "VoiceChatLevi"
    private const val HOST = "127.0.0.1"
    private const val PORT = 24457
    private const val SAMPLE_RATE = 16000
    private const val FRAME_SAMPLES = 320            // 20 ms at 16 kHz
    private const val FRAME_BYTES = FRAME_SAMPLES * 2
    private const val BACKOFF_MIN_MS = 500L
    private const val BACKOFF_MAX_MS = 10_000L
    private const val HEARTBEAT_FRAMES = 500         // 10 s of audio

    // Not `const`: const val only accepts String/primitive compile-time constants,
    // and byteArrayOf() is neither. (That mistake alone fails a Kotlin build.)
    private val TAG_BYTES = byteArrayOf(0x31, 0x4C, 0x43, 0x56)  // "VCL1"

    @Volatile private var running = false
    @Volatile private var stopping = false
    private var worker: Thread? = null
    private var appContext: Context? = null

    /** True while the pump thread is alive. */
    val isRunning: Boolean get() = running

    @Synchronized
    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext ?: context
        stopping = false
        running = true
        worker = Thread({ pump() }, "vcl-bridge").also {
            it.isDaemon = true
            it.start()
        }
        Log.i(TAG, "bridge: starting, will connect to the mod on ws://$HOST:$PORT")
    }

    @Synchronized
    fun stop() {
        if (!running) return
        stopping = true
        running = false
        worker?.interrupt()
        worker = null
    }

    // ------------------------------------------------------------------ loop
    private fun pump() {
        var backoff = BACKOFF_MIN_MS
        while (!stopping) {
            var socket: Socket? = null
            try {
                if (!hasPermission()) {
                    Log.w(TAG, "bridge: RECORD_AUDIO not granted - ask the user, then start again")
                    return
                }
                socket = Socket()
                socket.connect(InetSocketAddress(HOST, PORT), 1_000)
                socket.tcpNoDelay = true
                handshake(socket)
                backoff = BACKOFF_MIN_MS
                Log.i(TAG, "bridge: connected to the mod on ws://$HOST:$PORT")
                stream(socket)
            } catch (e: IOException) {
                // Normal while the game is still loading: the mod binds the port
                // when it enables. Say it once per attempt, then back off.
                Log.i(TAG, "bridge: mod not listening (${e.message}) - retry in ${backoff}ms")
            } catch (e: InterruptedException) {
                break
            } finally {
                try { socket?.close() } catch (_: IOException) {}
            }
            if (stopping) break
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                break
            }
            backoff = (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
        }
        running = false
        Log.i(TAG, "bridge: stopped")
    }

    // ------------------------------------------------------------- handshake
    private fun handshake(socket: Socket) {
        val keyBytes = ByteArray(16)
        Random.nextBytes(keyBytes)
        val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        val request = buildString {
            append("GET / HTTP/1.1\r\n")
            append("Host: ").append(HOST).append(':').append(PORT).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(request.toByteArray())
        out.flush()

        val input = DataInputStream(socket.getInputStream())
        val head = StringBuilder()
        while (!head.contains("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) throw IOException("closed during handshake")
            head.append(b.toChar())
            if (head.length > 4096) throw IOException("handshake too long")
        }
        if (!head.startsWith("HTTP/1.1 101")) throw IOException("mod refused the upgrade")
        if (!head.contains(expectedAccept(key))) throw IOException("bad Sec-WebSocket-Accept")
    }

    private fun expectedAccept(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
        return "Sec-WebSocket-Accept: " + Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    // --------------------------------------------------------------- capture
    private fun stream(socket: Socket) {
        val output = socket.getOutputStream()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) throw IOException("AudioRecord reports no buffer")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,   // keeps echo cancellation on
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
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
                var offset = 4
                for (sample in pcm) {
                    val value = sample.toInt()
                    payload[offset++] = (value and 0xFF).toByte()
                    payload[offset++] = ((value shr 8) and 0xFF).toByte()
                }
                sendBinary(output, payload)
                frames++
                if (frames % HEARTBEAT_FRAMES == 0) {
                    Log.i(TAG, "bridge: $frames frames sent (${frames * 20 / 1000} s of audio)")
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
        val header: ByteArray = if (payload.size < 126) {
            byteArrayOf(0x82.toByte(), (0x80 or payload.size).toByte())
        } else {
            byteArrayOf(
                0x82.toByte(),
                (0x80 or 126).toByte(),
                ((payload.size shr 8) and 0xFF).toByte(),
                (payload.size and 0xFF).toByte()
            )
        }
        val mask = ByteArray(4)
        Random.nextBytes(mask)
        val frame = ByteArray(header.size + 4 + payload.size)
        header.copyInto(frame, 0)
        mask.copyInto(frame, header.size)
        for (i in payload.indices) {
            frame[header.size + 4 + i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        output.write(frame)
        output.flush()          // one frame per 20 ms: latency beats batching here
    }

    private fun hasPermission(): Boolean {
        val context = appContext ?: return false
        if (Build.VERSION.SDK_INT < 23) return true      // granted at install time
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
}
