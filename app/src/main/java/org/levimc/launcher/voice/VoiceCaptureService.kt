package org.levimc.launcher.voice

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Voice Chat Levi - microphone capture, on the Java side of the launcher.
 *
 * 0.2.1 changes, all of them from the first device log:
 *
 *  1. The socket is BOUND TO LOOPBACK explicitly. An unbound DatagramSocket
 *     rides the device's default network, so with no Wi-Fi/data (or through a
 *     VPN) sendto can fail even though the destination is 127.0.0.1. A socket
 *     bound to 127.0.0.1 never needs a default network.
 *  2. A send failure can no longer stop capture. Before, one exception fell into
 *     the catch, the loop exited and the recorder was released - which is why the
 *     log showed send errors and "capture stopped" together.
 *  3. The socket is recreated after repeated failures instead of being given up on.
 *  4. Explicit lines for every state change, so the next log answers the question
 *     instead of raising it:
 *        "capture started"
 *        "send failed: <class>: <message> (n)"
 *        "capture loop exited: <reason>"
 *        "capture heartbeat: n frames sent, m failed"
 *  5. START_STICKY, so the system restarts the service if it is killed.
 */
class VoiceCaptureService : Service() {

    companion object {
        private const val TAG = "VoiceChatLevi"
        private const val PORT = 24456
        private const val HOST = "127.0.0.1"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 320              // 20 ms at 16 kHz
        private const val BYTES_PER_FRAME = FRAME_SAMPLES * 2
        private const val FRAME_TAG = 0x56434C31           // "VCL1", little endian
        private const val HEARTBEAT_FRAMES = 500           // 10 s of audio
        private const val SOCKET_REBUILD_AFTER = 25        // consecutive send failures

        const val ACTION_START = "org.levimc.launcher.voice.START"
        const val ACTION_STOP = "org.levimc.launcher.voice.STOP"

        /** "granted", "not declared in the manifest", or "declared but not granted". */
        fun permissionState(context: Context): String = when {
            !declaresPermission(context) -> "not declared in the manifest"
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> "granted"
            else -> "declared but not granted"
        }

        private fun declaresPermission(context: Context): Boolean = try {
            val info = context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_PERMISSIONS
            )
            info.requestedPermissions?.contains(Manifest.permission.RECORD_AUDIO) == true
        } catch (error: Exception) {
            false
        }

        /** Start capture; returns the permission state so the caller can log it. */
        fun start(context: Context, tag: String = TAG): String {
            val state = permissionState(context)
            Log.i(tag, "voice capture: RECORD_AUDIO $state")
            if (state == "granted") {
                context.startService(
                    Intent(context, VoiceCaptureService::class.java).setAction(ACTION_START)
                )
            }
            return state
        }
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var recorder: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture("stop requested")
            else -> startCapture()
        }
        return START_STICKY
    }

    private fun startCapture() {
        if (running) {
            Log.i(TAG, "capture already running, start ignored")
            return
        }
        val state = permissionState(this)
        if (state != "granted") {
            Log.w(TAG, "not starting: RECORD_AUDIO $state")
            return
        }
        running = true
        Log.i(TAG, "capture started")
        thread = Thread({ captureLoop() }, "vcl-capture").also { it.start() }
    }

    private fun stopCapture(reason: String) {
        if (!running && thread == null) return
        Log.i(TAG, "voice capture stopped ($reason)")
        running = false
        thread?.join(700)
        thread = null
        recorder?.release()
        recorder = null
    }

    /** A UDP socket that is pinned to loopback, so no default network is needed. */
    private fun openLoopbackSocket(): DatagramSocket {
        val socket = DatagramSocket(null)
        socket.bind(InetSocketAddress(InetAddress.getByName(HOST), 0))
        socket.broadcast = false
        return socket
    }

    private fun captureLoop() {
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minimum, BYTES_PER_FRAME * 8)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (error: Exception) {
            Log.e(TAG, "AudioRecord could not be created: ${error.javaClass.simpleName}: ${error.message}")
            running = false
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialised (state ${record.state}) - capture unavailable")
            record.release()
            running = false
            return
        }

        recorder = record
        var socket: DatagramSocket? = null
        val target = InetAddress.getByName(HOST)
        val packet = ByteBuffer.allocate(BYTES_PER_FRAME + 4).order(ByteOrder.LITTLE_ENDIAN)
        var sent = 0L
        var failed = 0L
        var consecutiveFailures = 0
        var exitReason = "running flag cleared"

        try {
            socket = try {
                openLoopbackSocket()
            } catch (error: Exception) {
                Log.e(TAG, "loopback socket failed: ${error.javaClass.simpleName}: ${error.message}")
                running = false
                return
            }

            record.startRecording()
            Log.i(TAG, "capturing ${SAMPLE_RATE}Hz mono, ${FRAME_SAMPLES} samples per frame, " +
                "sending to $HOST:$PORT, record state ${record.recordingState}")

            while (running) {
                packet.clear()
                packet.putInt(FRAME_TAG)
                var read = 0
                while (read < BYTES_PER_FRAME && running) {
                    val count = record.read(packet.array(), 4 + read, BYTES_PER_FRAME - read)
                    if (count <= 0) {
                        if (count == AudioRecord.ERROR_DEAD_OBJECT ||
                            count == AudioRecord.ERROR_INVALID_OPERATION
                        ) {
                            exitReason = "AudioRecord.read returned $count"
                            running = false
                        }
                        break
                    }
                    read += count
                }
                if (read != BYTES_PER_FRAME) continue

                // A send is never allowed to end capture. Count it, complain at a
                // sane rate, and rebuild the socket if it keeps failing.
                val current = socket
                try {
                    if (current == null) throw IllegalStateException("socket is null")
                    current.send(DatagramPacket(packet.array(), BYTES_PER_FRAME + 4, target, PORT))
                    sent++
                    consecutiveFailures = 0
                    if (sent % HEARTBEAT_FRAMES == 0L) {
                        Log.i(TAG, "capture heartbeat: $sent frames sent, $failed failed")
                    }
                } catch (error: Exception) {
                    failed++
                    consecutiveFailures++
                    if (failed == 1L || consecutiveFailures % 10 == 0) {
                        Log.w(
                            TAG,
                            "send failed: ${error.javaClass.simpleName}: ${error.message} " +
                                "(total $failed, consecutive $consecutiveFailures)"
                        )
                    }
                    if (consecutiveFailures >= SOCKET_REBUILD_AFTER) {
                        try {
                            socket?.close()
                        } catch (_: Exception) {
                        }
                        socket = try {
                            openLoopbackSocket()
                        } catch (_: Exception) {
                            null
                        }
                        consecutiveFailures = 0
                        Log.i(TAG, "loopback socket rebuilt")
                    }
                }
            }
        } catch (error: Throwable) {
            exitReason = "${error.javaClass.simpleName}: ${error.message}"
            Log.e(TAG, "capture loop error: $exitReason")
        } finally {
            running = false
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            Log.i(TAG, "capture loop exited: $exitReason ($sent sent, $failed failed)")
        }
    }

    override fun onDestroy() {
        stopCapture("service destroyed")
        super.onDestroy()
    }
}
