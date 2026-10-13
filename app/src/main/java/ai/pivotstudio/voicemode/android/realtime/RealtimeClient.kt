package ai.pivotstudio.voicemode.android.realtime

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for OpenAI's Realtime API over WebSocket — true speech-to-speech:
 * raw mic audio streams IN as base64 PCM16 chunks, raw model audio streams
 * OUT the same way, with NO separate ASR/text-LLM/TTS stages in between
 * (unlike the cascade prototype built for visual-impaired-assistant, which
 * this app replaces with a proper from-scratch implementation per the
 * user's explicit request: "implement speech to speech architecture
 * similar to chatgpt voice mode" in "a new app").
 *
 * Session flow:
 * 1. Connect to `wss://api.openai.com/v1/realtime?model=...` with the
 *    API key as a header (this is the simple, officially documented
 *    "direct from client" auth approach — acceptable for a personal/
 *    sideloaded app the user builds and runs themselves; a shipped
 *    public app should instead mint short-lived ephemeral tokens via a
 *    backend, but there is no backend here).
 * 2. Send `session.update` once connected to configure voice, audio
 *    format, and **server-side VAD** (`turn_detection`) — the server
 *    itself decides when the user started/stopped talking from the
 *    continuous audio stream, which is what makes this feel like a real
 *    conversation instead of a push-to-talk app: no button, no local
 *    VAD/ASR step at all.
 * 3. Continuously send `input_audio_buffer.append` events as mic chunks
 *    arrive (see [sendAudioChunk]) — the server appends them to a
 *    rolling input buffer and fires `input_audio_buffer.speech_started`
 *    / `speech_stopped` on its own VAD, then auto-creates a response.
 * 4. Listen for `response.audio.delta` events (streamed base64 PCM16
 *    output chunks) and hand them to [onAudioDelta] for immediate
 *    playback — streaming playback as deltas arrive, not after the full
 *    response finishes, is what gives low perceived latency.
 * 5. On `input_audio_buffer.speech_started` while a response is still
 *    playing, the caller should call [cancelResponse] AND stop local
 *    playback (barge-in) — approximates true interruption without a
 *    duplex audio channel.
 */
class RealtimeClient(
    private val apiKey: String,
    private val onAudioDelta: (ByteArray) -> Unit,
    private val onTranscriptDelta: (role: String, text: String) -> Unit,
    private val onSpeechStarted: () -> Unit,
    private val onSpeechStopped: () -> Unit,
    private val onResponseDone: () -> Unit,
    private val onConnectionStateChanged: (connected: Boolean, error: String?) -> Unit,
) {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket: no read timeout, connection is long-lived
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    fun connect() {
        val request = Request.Builder()
            .url("$ENDPOINT?model=$MODEL")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket open")
                configureSession(webSocket)
                onConnectionStateChanged(true, null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerEvent(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                onConnectionStateChanged(false, t.message ?: "Connection failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                onConnectionStateChanged(false, null)
            }
        })
    }

    private fun configureSession(webSocket: WebSocket) {
        val sessionUpdate = JSONObject().apply {
            put("type", "session.update")
            put(
                "session",
                JSONObject().apply {
                    put("modalities", JSONArray(listOf("text", "audio")))
                    put("voice", VOICE)
                    put("input_audio_format", "pcm16")
                    put("output_audio_format", "pcm16")
                    put(
                        "turn_detection",
                        JSONObject().apply {
                            put("type", "server_vad")
                            put("threshold", 0.5)
                            put("silence_duration_ms", 600)
                        },
                    )
                    put(
                        "instructions",
                        "You are a helpful, friendly voice assistant. Keep replies conversational " +
                            "and reasonably short, like a real spoken conversation.",
                    )
                },
            )
        }
        webSocket.send(sessionUpdate.toString())
    }

    /** Sends one chunk of raw PCM16 mic audio (see [ai.pivotstudio.voicemode.android.audio.AudioCapture]). */
    fun sendAudioChunk(pcm16: ShortArray) {
        val bytes = shortsToLittleEndianBytes(pcm16)
        val base64Audio = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val event = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", base64Audio)
        }
        webSocket?.send(event.toString())
    }

    /** Barge-in: asks the server to stop generating/streaming the current response. */
    fun cancelResponse() {
        webSocket?.send(JSONObject().put("type", "response.cancel").toString())
    }

    fun disconnect() {
        webSocket?.close(1000, "User ended session")
        webSocket = null
    }

    private fun handleServerEvent(rawJson: String) {
        val event = try {
            JSONObject(rawJson)
        } catch (e: Exception) {
            Log.e(TAG, "Malformed server event", e)
            return
        }
        when (event.optString("type")) {
            "response.audio.delta" -> {
                val base64Audio = event.optString("delta")
                if (base64Audio.isNotEmpty()) {
                    onAudioDelta(Base64.decode(base64Audio, Base64.DEFAULT))
                }
            }
            "response.audio_transcript.delta" -> {
                onTranscriptDelta("assistant", event.optString("delta"))
            }
            "conversation.item.input_audio_transcription.delta" -> {
                onTranscriptDelta("user", event.optString("delta"))
            }
            "input_audio_buffer.speech_started" -> onSpeechStarted()
            "input_audio_buffer.speech_stopped" -> onSpeechStopped()
            "response.done" -> onResponseDone()
            "error" -> {
                val message = event.optJSONObject("error")?.optString("message") ?: "Unknown realtime API error"
                Log.e(TAG, "Realtime API error: $message")
                onConnectionStateChanged(true, message)
            }
            else -> {
                // Other event types (response.created, rate_limits.updated,
                // session.updated, etc.) are informational only in this
                // first version — not needed to drive the UI/audio loop.
            }
        }
    }

    private fun shortsToLittleEndianBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            val value = shorts[i].toInt()
            bytes[i * 2] = (value and 0xFF).toByte()
            bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    companion object {
        private const val TAG = "VoiceMode/Realtime"
        private const val ENDPOINT = "wss://api.openai.com/v1/realtime"
        private const val MODEL = "gpt-4o-realtime-preview-2024-12-17"
        private const val VOICE = "alloy"
    }
}
