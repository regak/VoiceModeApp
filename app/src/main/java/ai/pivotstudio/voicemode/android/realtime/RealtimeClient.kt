package ai.pivotstudio.voicemode.android.realtime

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
 * **GA interface, not the retired beta.** OpenAI moved the Realtime API
 * from beta to general availability (Aug 2025) with a different event
 * shape than most tutorials/older docs describe. Real-device testing of
 * this app's first build caught this directly (user screenshot: "The
 * Realtime Beta API is no longer supported. Please use /v1/realtime for
 * the GA API."). Key differences from the old beta shape, all applied
 * below:
 * - No `OpenAI-Beta: realtime=v1` header needed/sent for the GA endpoint.
 * - `session.type = "realtime"` is now required.
 * - Audio config moved from flat `session.voice` / `session.input_audio_format`
 *   / `session.output_audio_format` fields into nested
 *   `session.audio.input.format` / `session.audio.input.turn_detection` /
 *   `session.audio.output.format` / `session.audio.output.voice`.
 * - `response.audio.delta` -> `response.output_audio.delta`.
 * - `response.audio_transcript.delta` -> `response.output_audio_transcript.delta`.
 * - Model id dropped the "-preview" beta naming: use `gpt-realtime`
 *   (snapshot `gpt-realtime-2025-08-28`) instead of e.g.
 *   `gpt-4o-realtime-preview-2024-12-17`.
 * See https://developers.openai.com/api/docs/guides/realtime#beta-to-ga-migration
 * if this needs updating again after a future API change.
 *
 * Session flow:
 * 1. Connect to `wss://api.openai.com/v1/realtime?model=gpt-realtime` with
 *    the API key as a header (this is the simple, officially documented
 *    "direct from client" auth approach — acceptable for a personal/
 *    sideloaded app the user builds and runs themselves; a shipped
 *    public app should instead mint short-lived ephemeral client secrets
 *    via `POST /v1/realtime/client_secrets` on a backend, but there is no
 *    backend here).
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
 * 4. Listen for `response.output_audio.delta` events (streamed base64
 *    PCM16 output chunks) and hand them to [onAudioDelta] for immediate
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
) : VoiceBackend {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket: no read timeout, connection is long-lived
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    override fun connect() {
        val request = Request.Builder()
            .url("$ENDPOINT?model=$MODEL")
            .addHeader("Authorization", "Bearer $apiKey")
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
        val pcmFormat = JSONObject().apply {
            put("type", "audio/pcm")
            put("rate", SAMPLE_RATE_HZ)
        }
        val sessionUpdate = JSONObject().apply {
            put("type", "session.update")
            put(
                "session",
                JSONObject().apply {
                    put("type", "realtime")
                    put(
                        "instructions",
                        "You are a helpful, friendly voice assistant. Keep replies conversational " +
                            "and reasonably short, like a real spoken conversation.",
                    )
                    put(
                        "audio",
                        JSONObject().apply {
                            put(
                                "input",
                                JSONObject().apply {
                                    put("format", pcmFormat)
                                    put(
                                        "turn_detection",
                                        JSONObject().apply {
                                            put("type", "server_vad")
                                            put("threshold", 0.5)
                                            put("silence_duration_ms", 600)
                                        },
                                    )
                                },
                            )
                            put(
                                "output",
                                JSONObject().apply {
                                    put("format", pcmFormat)
                                    put("voice", VOICE)
                                },
                            )
                        },
                    )
                },
            )
        }
        webSocket.send(sessionUpdate.toString())
    }

    /** Sends one chunk of raw PCM16 mic audio (see [ai.pivotstudio.voicemode.android.audio.AudioCapture]). */
    override fun sendAudioChunk(pcm16: ShortArray) {
        val bytes = shortsToLittleEndianBytes(pcm16)
        val base64Audio = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val event = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", base64Audio)
        }
        webSocket?.send(event.toString())
    }

    /** Barge-in: asks the server to stop generating/streaming the current response. */
    override fun cancelResponse() {
        webSocket?.send(JSONObject().put("type", "response.cancel").toString())
    }

    override fun disconnect() {
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
            // GA event name — was `response.audio.delta` in the retired beta interface.
            "response.output_audio.delta" -> {
                val base64Audio = event.optString("delta")
                if (base64Audio.isNotEmpty()) {
                    onAudioDelta(Base64.decode(base64Audio, Base64.DEFAULT))
                }
            }
            // GA event name — was `response.audio_transcript.delta` in the retired beta interface.
            "response.output_audio_transcript.delta" -> {
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
        // GA model id (not the retired "-preview" beta naming).
        private const val MODEL = "gpt-realtime"
        private const val VOICE = "alloy"
        const val SAMPLE_RATE_HZ = 24000
    }
}
