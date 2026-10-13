package ai.pivotstudio.voicemode.android.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captures mic audio at [SAMPLE_RATE_HZ] (24kHz mono PCM16 — OpenAI's
 * Realtime API default sample rate, avoids a resampling step) and hands
 * off fixed-size chunks through a single-consumer [Channel].
 *
 * Ported from the sibling `visual-impaired-assistant` project's
 * `core/AudioCapture.kt` (same discipline: buffers are always copied
 * before leaving the read loop, since [AudioRecord.read] reuses the
 * backing array on the next call). The key difference from that app:
 * this is a CONTINUOUS stream for the whole session (no press-and-hold
 * button — true speech-to-speech conversation has no manual record
 * gesture, the server-side VAD in OpenAI's Realtime API decides when the
 * user has started/stopped talking), so [start]/[stop] bound an entire
 * voice session, not one utterance.
 */
class AudioCapture(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var captureJob: kotlinx.coroutines.Job? = null

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts continuous capture and returns a channel the caller drains
     * until [stop] is called (e.g. when the user ends the voice
     * session).
     */
    @SuppressLint("MissingPermission") // caller must check hasMicPermission() first
    fun start(scope: CoroutineScope): ReceiveChannel<ShortArray> {
        check(hasMicPermission()) { "RECORD_AUDIO permission not granted" }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBufferSize, CHUNK_SAMPLES * 4)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        audioRecord = record

        val channel = Channel<ShortArray>(capacity = Channel.UNLIMITED)
        record.startRecording()

        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(CHUNK_SAMPLES)
            while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    channel.send(buffer.copyOf(read))
                }
            }
            channel.close()
        }

        return channel
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        audioRecord?.apply {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
            release()
        }
        audioRecord = null
    }

    companion object {
        /** OpenAI Realtime API's PCM16 input sample rate. */
        const val SAMPLE_RATE_HZ = 24000
        /** 20ms chunks at 24kHz. */
        const val CHUNK_SAMPLES = 480
    }
}
