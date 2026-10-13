package ai.pivotstudio.voicemode.android.ui

import ai.pivotstudio.voicemode.android.BuildConfig
import ai.pivotstudio.voicemode.android.audio.AudioCapture
import ai.pivotstudio.voicemode.android.audio.AudioPlayback
import ai.pivotstudio.voicemode.android.realtime.RealtimeClient
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
 * audio replies stream back and play as they arrive — see
 * [RealtimeClient]'s class doc for the full protocol flow.
 */
private enum class SessionState { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, ERROR }

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

    val audioCapture = remember { AudioCapture(context) }
    val audioPlayback = remember { AudioPlayback() }

    var micPermissionGranted by remember {
        mutableStateOf(audioCapture.hasMicPermission())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micPermissionGranted = granted
        if (!granted) {
            statusText = "Microphone permission is required to use Voice Mode."
        }
    }

    val realtimeClient = remember {
        RealtimeClient(
            apiKey = BuildConfig.OPENAI_API_KEY,
            onAudioDelta = { bytes ->
                audioPlayback.enqueue(bytes)
                sessionState = SessionState.SPEAKING
                statusText = "Speaking..."
            },
            onTranscriptDelta = { _, _ -> /* captions not shown in this first version */ },
            onSpeechStarted = {
                // Barge-in: if the assistant is still speaking when the
                // user starts talking again, stop local playback and
                // ask the server to cancel its in-flight response —
                // approximates ChatGPT's interrupt behavior.
                if (sessionState == SessionState.SPEAKING) {
                    audioPlayback.clearBuffered()
                }
                sessionState = SessionState.LISTENING
                statusText = "Listening..."
            },
            onSpeechStopped = {
                sessionState = SessionState.THINKING
                statusText = "Thinking..."
            },
            onResponseDone = {
                if (sessionState != SessionState.LISTENING) {
                    sessionState = SessionState.LISTENING
                    statusText = "Listening..."
                }
            },
            onConnectionStateChanged = { connected, error ->
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
            },
        )
    }

    val startSession: () -> Unit = {
        if (!micPermissionGranted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (BuildConfig.OPENAI_API_KEY.isBlank()) {
            statusText = "No OpenAI API key configured. Ask your developer to set one."
            sessionState = SessionState.ERROR
        } else {
            sessionState = SessionState.CONNECTING
            statusText = "Connecting..."
            lastErrorText = null
            audioPlayback.start()
            realtimeClient.connect()
            coroutineScope.launch {
                val chunks = audioCapture.start(this)
                chunks.consumeEach { chunk -> realtimeClient.sendAudioChunk(chunk) }
            }
        }
    }

    val stopSession: () -> Unit = {
        audioCapture.stop()
        audioPlayback.stop()
        realtimeClient.disconnect()
        sessionState = SessionState.IDLE
        statusText = "Tap to start talking"
    }

    DisposableEffect(Unit) {
        onDispose {
            audioCapture.stop()
            audioPlayback.stop()
            realtimeClient.disconnect()
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
    }
}
