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
 * 0.2.3 changes, both driven by a device log full of
 * "unix socket not ready yet: IOException: Connection refused":
 *
 * 1. ECONNREFUSED means nobody is listening, and that is NORMAL while the game is
 *    still starting: the native mod creates the listener when its enable hook
 *    runs, which is after this service. So the connect is now retried with
 *    exponential backoff (0.5 s -> 10 s) and logs once per state change plus a
 *    heartbeat every 30 s, instead of one line every 500 ms forever.
 *
 * 2. The mod now answers with an 8-byte handshake right after connecting:
 *        'V' 'C' 'L' 'S'   1   mode   0   0
 *    mode 0 = "send me audio", mode 1 = "this process already reads the
 *    microphone, stand down". On mode 1 the recorder is released - no battery,
 *    no fight over the microphone - and the service re-checks every 30 s in case
 *    the direct path dies later. An older library sends nothing; the read times
 *    out and the service behaves exactly as before.
 *
 * Transport note: an abstract Unix domain socket, not UDP loopback. Android's
 * per-uid network filtering answered sendto with EPERM even for 127.0.0.1, and no
 * amount of rebinding can change a per-uid rule. A Unix socket never enters the
 * network stack, so the rule does not apply.
 *
 * Framing after the handshake: one write per 20 ms frame, 320 samples of 16-bit
 * PCM = 640 bytes.
 */
class VoiceCaptureService : Service() {

    companion object {
        private const val TAG = "VoiceChatLevi"
        private const val SOCKET_NAME = "org.levimc.voicechatlevi.audio"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 320              // 20 ms at 16 kHz
        private const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val HEARTBEAT_FRAMES = 500           // 10 s of audio

        /** Backoff for "the mod is not listening yet": 0.5 s doubling to 10 s. */
        private const val BACKOFF_MIN_MS = 500L
        private const val BACKOFF_MAX_MS = 10_000L

        /** How often a parked service re-checks whether it is wanted again. */
        private const val PARKED_RECHECK_MS = 30_000L

        /** Log an unchanged "still waiting" line at most this often. */
        private const val WAIT_LOG_INTERVAL_MS = 30_000L

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

    /** One connection attempt. Returns the socket, or null with the reason logged. */
    private fun connect(): LocalSocket? = try {
        LocalSocket().apply {
            connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
        }
    } catch (error: Exception) {
        null
    }

    /**
     * Read the mod's 8-byte greeting. Returns true when the mod says it does not
     * need audio. A timeout or a short read means an older library, so false.
     */
    private fun isStandingDown(socket: LocalSocket): Boolean {
        return try {
            socket.soTimeout = 1500
            val input = socket.inputStream
            val hello = ByteArray(8)
            var read = 0
            while (read < 8) {
                val count = input.read(hello, read, 8 - read)
                if (count <= 0) break
                read += count
            }
            socket.soTimeout = 0
            if (read < 8 || hello[0] != 'V'.code.toByte() || hello[1] != 'C'.code.toByte() ||
                hello[2] != 'L'.code.toByte() || hello[3] != 'S'.code.toByte()
            ) {
                return false
            }
            hello[5].toInt() == 1
        } catch (error: Exception) {
            try {
                socket.soTimeout = 0
            } catch (_: Exception) {
            }
            false
        }
    }

    private fun captureLoop() {
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minimum, FRAME_BYTES * 8)
        val frame = ByteArray(FRAME_BYTES)

        var sent = 0L
        var failed = 0L
        var exits = 0
        var waitLogged = false
        var lastWaitLog = 0L
        var exitReason = "running flag cleared"

        try {
            while (running) {
                // ---- 1. wait for the mod's listener, with backoff -------------
                var attempt = 0
                var socket: LocalSocket? = null
                while (running && socket == null) {
                    val connected = connect()
                    if (connected != null) {
                        socket = connected
                        break
                    }
                    attempt++
                    val now = System.currentTimeMillis()
                    if (!waitLogged || now - lastWaitLog >= WAIT_LOG_INTERVAL_MS) {
                        Log.i(
                            TAG,
                            "waiting for the mod to open its voice socket " +
                                "(attempt $attempt) - this is normal until the game " +
                                "finishes starting"
                        )
                        waitLogged = true
                        lastWaitLog = now
                    }
                    val delay = minOf(
                        BACKOFF_MIN_MS shl minOf(attempt - 1, 5), BACKOFF_MAX_MS
                    )
                    Thread.sleep(delay)
                }
                val link = socket ?: break

                // ---- 2. ask the mod whether it wants our audio ---------------
                if (isStandingDown(link)) {
                    Log.i(
                        TAG,
                        "the game reads the microphone directly; capture service parked " +
                            "(released the mic, re-checking every ${PARKED_RECHECK_MS / 1000} s)"
                    )
                    try {
                        link.close()
                    } catch (_: Exception) {
                    }
                    Thread.sleep(PARKED_RECHECK_MS)
                    continue
                }

                waitLogged = false
                val stream = link.outputStream
                Log.i(TAG, "connected to the voice socket")

                // ---- 3. capture and write, releasing the recorder on the way out
                val record = openRecorder(bufferSize)
                if (record == null) {
                    running = false
                    exitReason = "AudioRecord could not be opened"
                    try {
                        link.close()
                    } catch (_: Exception) {
                    }
                    break
                }
                recorder = record
                val framesBefore = sent
                try {
                    record.startRecording()
                    Log.i(
                        TAG,
                        "capturing ${SAMPLE_RATE}Hz mono, $FRAME_SAMPLES samples per frame, " +
                            "writing to abstract socket $SOCKET_NAME"
                    )
                    while (running) {
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
                            stream.write(frame, 0, FRAME_BYTES)
                            sent++
                            if (sent % HEARTBEAT_FRAMES == 0L) {
                                Log.i(TAG, "capture heartbeat: $sent frames sent, $failed failed")
                            }
                        } catch (error: Exception) {
                            failed++
                            if (failed == 1L || failed % 100 == 0L) {
                                Log.w(
                                    TAG,
                                    "write failed: ${error.javaClass.simpleName}: " +
                                        "${error.message} (total $failed)"
                                )
                            }
                            // The mod went away. Drop the link, keep capture alive:
                            // the loop above reconnects.
                            break
                        }
                    }
                } finally {
                    try {
                        record.stop()
                    } catch (_: IllegalStateException) {
                    }
                    record.release()
                    recorder = null
                    try {
                        link.close()
                    } catch (_: Exception) {
                    }
                }
                exits++
                if (sent > framesBefore) {
                    Log.i(
                        TAG,
                        "link closed after ${sent - framesBefore} frames, reconnecting"
                    )
                }
            }
        } catch (error: Throwable) {
            exitReason = "${error.javaClass.simpleName}: ${error.message}"
            if (error !is InterruptedException) {
                Log.e(TAG, "capture loop error: $exitReason")
            }
        } finally {
            running = false
            recorder?.let {
                try {
                    it.stop()
                } catch (_: IllegalStateException) {
                }
                it.release()
            }
            recorder = null
            Log.i(
                TAG,
                "capture loop exited: $exitReason ($sent sent, $failed failed, $exits link(s))"
            )
        }
    }

    private fun openRecorder(bufferSize: Int): AudioRecord? {
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (error: Exception) {
            Log.e(
                TAG,
                "AudioRecord could not be created: ${error.javaClass.simpleName}: ${error.message}"
            )
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialised (state ${record.state})")
            record.release()
            return null
        }
        return record
    }

    override fun onDestroy() {
        stopCapture("service destroyed")
        super.onDestroy()
    }
}
