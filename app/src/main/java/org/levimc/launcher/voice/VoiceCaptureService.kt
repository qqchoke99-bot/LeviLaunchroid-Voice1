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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Voice Chat Levi - microphone capture, on the Java side of the launcher.
 *
 * Why the launcher and not the native mod: RECORD_AUDIO is checked by audioserver
 * against the calling uid. In LeviLauncher the game runs inside the launcher's own
 * process, so the launcher's manifest is the one that decides. A native mod cannot
 * grant itself a permission; this class is the part that can hold one.
 *
 * What it does: captures 16 kHz mono PCM16 in 20 ms frames and forwards each frame
 * to 127.0.0.1:24456, where the native mod's UdpCapture receives it and feeds it
 * into the same gate and ring that a direct AAudio capture would.
 *
 * Plain startService(), not a foreground service: the game is in the foreground
 * whenever voice matters, and that avoids both a notification and the API 34
 * requirement to call startForeground() within five seconds.
 */
class VoiceCaptureService : Service() {

    companion object {
        private const val TAG = "VoiceChatLevi"
        private const val PORT = 24456
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 320              // 20 ms at 16 kHz
        private const val BYTES_PER_FRAME = FRAME_SAMPLES * 2
        private const val FRAME_TAG = 0x56434C31           // "VCL1", little endian

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
                context.startService(Intent(context, VoiceCaptureService::class.java)
                    .setAction(ACTION_START))
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
            ACTION_STOP -> stopCapture()
            else -> startCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        if (running) return
        if (permissionState(this) != "granted") {
            Log.w(TAG, "not starting: ${permissionState(this)}")
            return
        }
        running = true
        thread = Thread({ captureLoop() }, "vcl-capture").also { it.start() }
    }

    private fun stopCapture() {
        running = false
        thread?.join(500)
        thread = null
        recorder?.release()
        recorder = null
        Log.i(TAG, "voice capture stopped")
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
            Log.e(TAG, "AudioRecord could not be created", error)
            running = false
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialised - capture unavailable")
            record.release()
            running = false
            return
        }

        recorder = record
        val socket = DatagramSocket()
        socket.broadcast = false
        val target = InetAddress.getByName("127.0.0.1")
        val packet = ByteBuffer.allocate(BYTES_PER_FRAME + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        try {
            record.startRecording()
            Log.i(TAG, "capturing ${SAMPLE_RATE}Hz mono, ${FRAME_SAMPLES} samples per frame")
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
                            Log.e(TAG, "AudioRecord read failed ($count), stopping")
                            running = false
                        }
                        break
                    }
                    read += count
                }
                if (read == BYTES_PER_FRAME) {
                    socket.send(DatagramPacket(packet.array(), BYTES_PER_FRAME + 4, target, PORT))
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "capture loop failed", error)
        } finally {
            running = false
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
            socket.close()
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
