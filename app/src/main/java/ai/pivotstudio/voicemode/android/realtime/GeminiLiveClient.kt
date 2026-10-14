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
 * Client for Google's Gemini **Live API** over raw WebSocket — the
 * Gemini equivalent of OpenAI's Realtime API: a genuinely audio-native
 * multimodal model (not an ASR+text-LLM+TTS cascade), input/output audio
 * streamed continuously over one stateful connection, server-side VAD,
 * and barge-in support. Implements the same [VoiceBackend] contract as
 * [RealtimeClient] so the UI/audio pipeline is backend-agnostic.
 *
 * Reference: https://ai.google.dev/api/live (WebSockets API reference),
 * https://ai.google.dev/gemini-api/docs/live-api/get-started-websocket
 *
 * Protocol notes (as of the GA/preview Live API, subject to change):
 * - Endpoint: `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=API_KEY`
 *   (simple API-key-as-query-param auth — fine for a personal sideloaded
 *   app; a shipped public app should mint short-lived ephemeral tokens
 *   via `AuthTokenService.CreateToken` instead and never embed a raw key).
 * - First message after connecting MUST be `{"setup": {...}}`; wait for
 *   `setupComplete` before sending audio (we don't strictly block on it
 *   since the socket buffers, but we log it for visibility).
 * - Audio in: `{"realtimeInput": {"audio": {"data": base64, "mimeType": "audio/pcm;rate=16000"}}}`
 *   — Gemini Live expects 16kHz mic input (NOT 24kHz like OpenAI's Realtime
 *   API), so [sendAudioChunk] assumes the caller is feeding 16kHz PCM16.
 * - Audio out: delivered inside `serverContent.modelTurn.parts[].inlineData.data`
 *   (base64), raw PCM16 at 24kHz — same output rate as OpenAI, so
 *   playback-side code doesn't need to change.
 * - Turn/interruption signal: `serverContent.interrupted == true` means
 *   the user barged in; caller should flush local playback immediately.
 * - `serverContent.turnComplete == true` marks the end of one model turn
 *   (roughly analogous to OpenAI's `response.done`).
 * - Client-side interruption/barge-in cancel: Gemini's default
 *   `ActivityHandling` already auto-interrupts the model's response as
 *   soon as it detects the user started talking again (server VAD), so
 *   there is no separate "cancel" message to send the way OpenAI needs
 *   `response.cancel` — [cancelResponse] is a no-op kept only to satisfy
 *   the shared [VoiceBackend] interface.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val onAudioDelta: (ByteArray) -> Unit,
    private val onTranscriptDelta: (role: String, text: String) -> Unit,
    private val onSpeechStarted: () -> Unit,
    private val onSpeechStopped: () -> Unit,
    private val onResponseDone: () -> Unit,
    private val onConnectionStateChanged: (connected: Boolean, error: String?) -> Unit,
    private val onDebugEvent: (String) -> Unit = {},
) : VoiceBackend {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var wasInterrupted = false

    override fun connect() {
        val request = Request.Builder()
            .url("$ENDPOINT?key=$apiKey")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket open")
                sendSetup(webSocket)
                onConnectionStateChanged(true, null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "<<< $text")
                onDebugEvent(text.take(500))
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val bodySnippet = try { response?.body?.string()?.take(500) } catch (e: Exception) { null }
                Log.e(TAG, "WebSocket failure (http=${response?.code}) body=$bodySnippet", t)
                onDebugEvent("FAILURE http=${response?.code} ${t.message} body=$bodySnippet")
                onConnectionStateChanged(false, t.message ?: "Connection failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                onConnectionStateChanged(false, null)
            }
        })
    }

    private fun sendSetup(webSocket: WebSocket) {
        val setup = JSONObject().apply {
            put(
                "setup",
                JSONObject().apply {
                    put("model", "models/$MODEL")
                    put(
                        "generationConfig",
                        JSONObject().apply {
                            put("responseModalities", org.json.JSONArray().put("AUDIO"))
                        },
                    )
                    put(
                        "systemInstruction",
                        JSONObject().apply {
                            put(
                                "parts",
                                org.json.JSONArray().put(
                                    JSONObject().put(
                                        "text",
                                        "You are a helpful, friendly voice assistant. Keep replies " +
                                            "conversational and reasonably short, like a real spoken conversation.",
                                    ),
                                ),
                            )
                        },
                    )
                    // Enable transcripts of both sides so the UI can show captions,
                    // mirroring OpenAI's conversation.item.input_audio_transcription
                    // and response.output_audio_transcript.delta events.
                    put("inputAudioTranscription", JSONObject())
                    put("outputAudioTranscription", JSONObject())
                },
            )
        }
        webSocket.send(setup.toString())
    }

    /** Sends one chunk of raw PCM16 mic audio, expected at [INPUT_SAMPLE_RATE_HZ] (16kHz). */
    override fun sendAudioChunk(pcm16: ShortArray) {
        val bytes = shortsToLittleEndianBytes(pcm16)
        val base64Audio = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val event = JSONObject().apply {
            put(
                "realtimeInput",
                JSONObject().apply {
                    put(
                        "audio",
                        JSONObject().apply {
                            put("data", base64Audio)
                            put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE_HZ")
                        },
                    )
                },
            )
        }
        webSocket?.send(event.toString())
    }

    /**
     * No-op for Gemini Live: the server's default `ActivityHandling`
     * (`START_OF_ACTIVITY_INTERRUPTS`) already auto-interrupts generation
     * as soon as it detects the user talking again — there's no separate
     * client-sent cancel message the way OpenAI's Realtime API needs
     * `response.cancel`. Kept to satisfy [VoiceBackend].
     */
    override fun cancelResponse() {
        // Intentionally empty — see class doc.
    }

    override fun disconnect() {
        webSocket?.close(1000, "User ended session")
        webSocket = null
    }

    private fun handleServerMessage(rawJson: String) {
        val message = try {
            JSONObject(rawJson)
        } catch (e: Exception) {
            Log.e(TAG, "Malformed server message", e)
            return
        }

        if (message.has("setupComplete")) {
            Log.i(TAG, "Gemini Live setup complete")
            return
        }

        val serverContent = message.optJSONObject("serverContent")
        if (serverContent != null) {
            if (serverContent.optBoolean("interrupted", false)) {
                wasInterrupted = true
                onSpeechStarted() // reuse as a generic "barge-in happened" signal
            }

            val modelTurn = serverContent.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val inlineData = part.optJSONObject("inlineData")
                    val base64Audio = inlineData?.optString("data")
                    if (!base64Audio.isNullOrEmpty()) {
                        onAudioDelta(Base64.decode(base64Audio, Base64.DEFAULT))
                    }
                }
            }

            val outputTranscription = serverContent.optJSONObject("outputTranscription")
            val outputText = outputTranscription?.optString("text")
            if (!outputText.isNullOrEmpty()) {
                onTranscriptDelta("assistant", outputText)
            }

            val inputTranscription = serverContent.optJSONObject("inputTranscription")
            val inputText = inputTranscription?.optString("text")
            if (!inputText.isNullOrEmpty()) {
                onTranscriptDelta("user", inputText)
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                if (!wasInterrupted) {
                    onSpeechStopped()
                }
                wasInterrupted = false
                onResponseDone()
            }
        }

        // Top-level voiceActivity (ACTIVITY_START/ACTIVITY_END) is sent
        // independently of serverContent when the server's VAD fires —
        // use it to flip the UI to "listening" promptly.
        val voiceActivity = message.optJSONObject("voiceActivity")
        if (voiceActivity != null) {
            when (voiceActivity.optString("type")) {
                "ACTIVITY_START" -> onSpeechStarted()
                "ACTIVITY_END" -> onSpeechStopped()
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
        private const val TAG = "VoiceMode/GeminiLive"
        private const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val MODEL = "gemini-3.8-live"

        /** Gemini Live expects 16kHz mono PCM16 mic input (OpenAI's Realtime API uses 24kHz). */
        const val INPUT_SAMPLE_RATE_HZ = 16000

        /** Gemini Live always outputs 24kHz mono PCM16 — same as OpenAI, so playback is shared. */
        const val OUTPUT_SAMPLE_RATE_HZ = 24000
    }
}
