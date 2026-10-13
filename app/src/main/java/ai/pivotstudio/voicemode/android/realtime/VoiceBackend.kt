package ai.pivotstudio.voicemode.android.realtime

/**
 * Common contract both speech-to-speech backends implement, so
 * [ai.pivotstudio.voicemode.android.ui.MainActivity] can swap between
 * OpenAI's Realtime API ([RealtimeClient]) and Google's Gemini Live API
 * ([GeminiLiveClient]) with no UI/audio-pipeline changes. Both backends
 * are genuine audio-in/audio-out multimodal models — not ASR+text-LLM+TTS
 * cascades — so the same continuous-streaming architecture (raw mic PCM16
 * in, raw PCM16 deltas out, server-side VAD, barge-in cancel) applies to
 * either one.
 */
interface VoiceBackend {
    fun connect()
    fun sendAudioChunk(pcm16: ShortArray)
    fun cancelResponse()
    fun disconnect()
}
