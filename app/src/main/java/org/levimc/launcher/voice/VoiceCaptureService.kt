package org.levimc.launcher.voice

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import android.util.Log

/**
 * Voice Chat Levi - microphone capture, on the Java side of the launcher.
 *
 * 0.2.2: the transport is an ABSTRACT UNIX DOMAIN SOCKET, not UDP loopback.
 *
 * Why: 0.2.1 sent frames to 127.0.0.1:24456 with a socket explicitly bound to
 * loopback, and the device still answered sendto with EPERM - repeatedly, even
 * after the socket was rebuilt. That is Android's per-uid network filtering
 * (netd): a socket belonging to an app with no matching network rule is refused
 * even for loopback, because loopback still goes through the network stack.
 * Rebuilding the socket cannot fix a rule that is applied per uid.
 *
 * A Unix domain socket never enters the network stack, so none of those rules
 * apply. The abstract namespace (no filesystem path) also means there is nothing
 * to clean up and no permission to set.
 *
 * Framing: one write per 20 ms frame, 320 samples of 16-bit PCM = 640 bytes. The
 * native side reads exactly 640 bytes per frame.
 */
class VoiceCaptureService : Service() {

    companion object {
        private const val TAG = "VoiceChatLevi"
        private const val SOCKET_NAME = "org.levimc.voicechatlevi.audio"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 320              // 20 ms at 16 kHz
        private const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val RECONNECT_MS = 500L
        private const val HEARTBEAT_FRAMES = 500           // 10 s of audio

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

    /** Connect to the native listener, retrying while it starts up. */
    private fun connect(): LocalSocket? {
        val socket = LocalSocket()
        return try {
            socket.connect(
                LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT)
            )
            socket
        } catch (error: Exception) {
            Log.i(TAG, "unix socket not ready yet: ${error.javaClass.simpleName}: ${error.message}")
            try {
                socket.close()
            } catch (_: Exception) {
            }
            null
        }
    }

    private fun captureLoop() {
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minimum, FRAME_BYTES * 8)
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
        var socket: LocalSocket? = null
        var stream: java.io.OutputStream? = null
        var sent = 0L
        var failed = 0L
        var exitReason = "running flag cleared"
        val frame = ByteArray(FRAME_BYTES)

        try {
            record.startRecording()
            Log.i(TAG, "capturing ${SAMPLE_RATE}Hz mono, ${FRAME_SAMPLES} samples per frame, " +
                "writing to abstract socket $SOCKET_NAME, record state ${record.recordingState}")

            while (running) {
                // (Re)connect whenever the native side is not there. Capture keeps
                // running meanwhile, so a restart of the listener costs no audio
                // device churn.
                if (socket == null) {
                    val fresh = connect()
                    if (fresh == null) {
                        Thread.sleep(RECONNECT_MS)
                        continue
                    }
                    socket = fresh
                    stream = fresh.outputStream
                    Log.i(TAG, "connected to the voice socket")
                }

                var read = 0
                while (read < FRAME_BYTES && running) {
                    val count = record.read(frame, read, FRAME_BYTES - read)
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
                if (read != FRAME_BYTES) continue

                try {
                    stream?.let {
                        it.write(frame, 0, FRAME_BYTES)
                        sent++
                        if (sent % HEARTBEAT_FRAMES == 0L) {
                            Log.i(TAG, "capture heartbeat: $sent frames sent, $failed failed")
                        }
                    } ?: throw IllegalStateException("no stream")
                } catch (error: Exception) {
                    failed++
                    if (failed == 1L || failed % 100 == 0L) {
                        Log.w(TAG, "write failed: ${error.javaClass.simpleName}: ${error.message} (total $failed)")
                    }
                    // The listener went away: drop the socket and reconnect. This
                    // never stops capture - that was the 0.2.0 bug.
                    try {
                        socket?.close()
                    } catch (_: Exception) {
                    }
                    socket = null
                    stream = null
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
