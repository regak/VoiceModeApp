package ai.pivotstudio.voicemode.android.ui

import ai.pivotstudio.voicemode.android.BuildConfig
import ai.pivotstudio.voicemode.android.audio.AudioCapture
import ai.pivotstudio.voicemode.android.audio.AudioPlayback
import ai.pivotstudio.voicemode.android.realtime.GeminiLiveClient
import ai.pivotstudio.voicemode.android.realtime.RealtimeClient
import ai.pivotstudio.voicemode.android.realtime.VoiceBackend
import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch

/**
 * Single-screen speech-to-speech voice assistant — recreates ChatGPT's
 * Advanced Voice Mode UX (explicit user request, see the reference
 * screenshots: a big orb-style status indicator, tap to start/stop
 * talking, no chat bubbles required for a minimal first version).
 *
 * Unlike visual-impaired-assistant's cascade prototype (press-and-hold,
 * transcribe, text-chat-call, speak reply), this is a genuinely
 * continuous session once connected: mic streams constantly, the
 * server's own VAD (not a local one) detects speech start/stop, and
 * audio replies stream back and play as they arrive.
 *
 * Supports TWO interchangeable audio-native backends behind the shared
 * [VoiceBackend] interface — tap the small label under the orb to
 * switch before connecting:
 * - OpenAI Realtime API ([RealtimeClient]) — 24kHz mic input.
 * - Google Gemini Live API ([GeminiLiveClient]) — 16kHz mic input,
 *   free tier available on Google AI Studio (no billing card needed),
 *   added per user request to compare against OpenAI's paid-only
 *   Realtime API after a billing/quota error blocked testing.
 */
private enum class SessionState { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, ERROR }
private enum class Backend { OPENAI, GEMINI }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VoiceModeScreen()
                }
            }
        }
    }
}

@Composable
private fun VoiceModeScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var sessionState by remember { mutableStateOf(SessionState.IDLE) }
    var statusText by remember { mutableStateOf("Tap to start talking") }
    var lastErrorText by remember { mutableStateOf<String?>(null) }
    var backend by remember { mutableStateOf(Backend.OPENAI) }

    var activeSession by remember { mutableStateOf<ActiveSession?>(null) }
    val audioPlayback = remember { AudioPlayback() }

    var micPermissionGranted by remember {
        mutableStateOf(AudioCapture(context).hasMicPermission())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micPermissionGranted = granted
        if (!granted) {
            statusText = "Microphone permission is required to use Voice Mode."
        }
    }

    // Shared callback wiring — identical UI reactions regardless of which
    // backend is driving them, since both implement VoiceBackend and emit
    // the same semantic events (audio delta, speech started/stopped, etc).
    fun onAudioDelta(bytes: ByteArray) {
        audioPlayback.enqueue(bytes)
        sessionState = SessionState.SPEAKING
        statusText = "Speaking..."
    }
    fun onSpeechStarted() {
        if (sessionState == SessionState.SPEAKING) {
            audioPlayback.clearBuffered()
        }
        sessionState = SessionState.LISTENING
        statusText = "Listening..."
    }
    fun onSpeechStopped() {
        sessionState = SessionState.THINKING
        statusText = "Thinking..."
    }
    fun onResponseDone() {
        if (sessionState != SessionState.LISTENING) {
            sessionState = SessionState.LISTENING
            statusText = "Listening..."
        }
    }
    fun onConnectionStateChanged(connected: Boolean, error: String?) {
        if (error != null) {
            lastErrorText = error
            sessionState = SessionState.ERROR
            statusText = "Error: $error"
        } else if (connected) {
            sessionState = SessionState.LISTENING
            statusText = "Listening..."
        } else {
            sessionState = SessionState.IDLE
            statusText = "Tap to start talking"
        }
    }

    val startSession: () -> Unit = {
        val missingKey = when (backend) {
            Backend.OPENAI -> BuildConfig.OPENAI_API_KEY.isBlank()
            Backend.GEMINI -> BuildConfig.GEMINI_API_KEY.isBlank()
        }
        if (!micPermissionGranted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (missingKey) {
            statusText = "No API key configured for ${backend.name}. Ask your developer to set one."
            sessionState = SessionState.ERROR
        } else {
            sessionState = SessionState.CONNECTING
            statusText = "Connecting..."
            lastErrorText = null
            audioPlayback.start()

            val voiceBackend: VoiceBackend = when (backend) {
                Backend.OPENAI -> RealtimeClient(
                    apiKey = BuildConfig.OPENAI_API_KEY,
                    onAudioDelta = ::onAudioDelta,
                    onTranscriptDelta = { _, _ -> },
                    onSpeechStarted = ::onSpeechStarted,
                    onSpeechStopped = ::onSpeechStopped,
                    onResponseDone = ::onResponseDone,
                    onConnectionStateChanged = ::onConnectionStateChanged,
                )
                Backend.GEMINI -> GeminiLiveClient(
                    apiKey = BuildConfig.GEMINI_API_KEY,
                    onAudioDelta = ::onAudioDelta,
                    onTranscriptDelta = { _, _ -> },
                    onSpeechStarted = ::onSpeechStarted,
                    onSpeechStopped = ::onSpeechStopped,
                    onResponseDone = ::onResponseDone,
                    onConnectionStateChanged = ::onConnectionStateChanged,
                )
            }
            val micSampleRate = when (backend) {
                Backend.OPENAI -> RealtimeClient.SAMPLE_RATE_HZ
                Backend.GEMINI -> GeminiLiveClient.INPUT_SAMPLE_RATE_HZ
            }
            val audioCapture = AudioCapture(context, sampleRateHz = micSampleRate)

            voiceBackend.connect()
            coroutineScope.launch {
                val chunks = audioCapture.start(this)
                chunks.consumeEach { chunk -> voiceBackend.sendAudioChunk(chunk) }
            }

            activeSession = ActiveSession(voiceBackend, audioCapture)
        }
    }

    val stopSession: () -> Unit = {
        activeSession?.audioCapture?.stop()
        audioPlayback.stop()
        activeSession?.backend?.disconnect()
        activeSession = null
        sessionState = SessionState.IDLE
        statusText = "Tap to start talking"
    }

    DisposableEffect(Unit) {
        onDispose {
            activeSession?.audioCapture?.stop()
            audioPlayback.stop()
            activeSession?.backend?.disconnect()
            activeSession = null
        }
    }

    val orbColor = when (sessionState) {
        SessionState.IDLE -> Color(0xFF9E9E9E)
        SessionState.CONNECTING -> Color(0xFFFFC107)
        SessionState.LISTENING -> Color(0xFF2196F3)
        SessionState.THINKING -> Color(0xFF9C27B0)
        SessionState.SPEAKING -> Color(0xFF4CAF50)
        SessionState.ERROR -> Color(0xFFF44336)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(sessionState) {
                detectTapGestures(
                    onTap = {
                        when (sessionState) {
                            SessionState.IDLE, SessionState.ERROR -> startSession()
                            else -> stopSession()
                        }
                    },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .background(color = orbColor, shape = CircleShape),
        )
        Text(
            text = statusText,
            modifier = Modifier.padding(top = 32.dp).fillMaxWidth(),
            fontSize = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (sessionState != SessionState.IDLE) {
            Text(
                text = "Tap again to end the session",
                modifier = Modifier.padding(top = 12.dp),
                fontSize = 13.sp,
                color = Color.Gray,
            )
        }

        if (sessionState == SessionState.IDLE) {
            Row(
                modifier = Modifier
                    .padding(top = 40.dp)
                    .background(color = Color(0xFFEEEEEE), shape = RoundedCornerShape(20.dp))
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .pointerInput(backend) {
                        detectTapGestures(
                            onTap = {
                                backend = if (backend == Backend.OPENAI) Backend.GEMINI else Backend.OPENAI
                                statusText = "Tap to start talking"
                            },
                        )
                    },
            ) {
                Text(
                    text = if (backend == Backend.OPENAI) "  OpenAI Realtime  " else "  Gemini Live  ",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Text(
                text = "Tap the pill above to switch backend",
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 11.sp,
                color = Color.Gray,
            )
        }
    }
}

/** Bundles the live backend + capture instances so [stopSession] can tear both down. */
private data class ActiveSession(val backend: VoiceBackend, val audioCapture: AudioCapture)
