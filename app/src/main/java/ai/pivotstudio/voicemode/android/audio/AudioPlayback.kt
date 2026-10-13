package ai.pivotstudio.voicemode.android.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Streams PCM16 24kHz mono audio deltas from the OpenAI Realtime API out
 * through the device speaker/earpiece as they arrive, instead of waiting
 * for a complete utterance like the cascade version's TTS step did —
 * this is what makes the reply feel like it's being "spoken live" rather
 * than played back after a full generation.
 *
 * [enqueue] is called repeatedly (once per `response.audio.delta` event,
 * see [ai.pivotstudio.voicemode.android.realtime.RealtimeClient]) and
 * just appends raw bytes to [AudioTrack]'s internal buffer via
 * [AudioTrack.write] in STREAM mode — Android handles the actual
 * low-latency playback buffering.
 *
 * [stop] immediately flushes/stops playback — used for barge-in: when
 * the user starts talking again while the assistant is still replying,
 * the caller sends a `response.cancel` event AND calls [stop] here so
 * stale audio doesn't keep playing over the user's next utterance,
 * approximating ChatGPT's own interrupt behavior without true
 * server-side duck/overlap handling.
 */
class AudioPlayback {

    private var track: AudioTrack? = null

    fun start() {
        if (track != null) return
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBufferSize, MIN_TRACK_BUFFER_BYTES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
    }

    /** Appends raw PCM16 little-endian bytes decoded from a base64 audio delta. */
    fun enqueue(pcm16Bytes: ByteArray) {
        track?.write(pcm16Bytes, 0, pcm16Bytes.size)
    }

    /** Stops and discards any buffered-but-not-yet-played audio (barge-in). */
    fun clearBuffered() {
        track?.pause()
        track?.flush()
        track?.play()
    }

    fun stop() {
        track?.apply {
            stop()
            release()
        }
        track = null
    }

    companion object {
        /** OpenAI Realtime API's PCM16 output sample rate. */
        const val SAMPLE_RATE_HZ = 24000
        private const val MIN_TRACK_BUFFER_BYTES = 24000 * 2 // ~1s of headroom
    }
}
