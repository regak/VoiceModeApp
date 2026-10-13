# VoiceModeApp

A minimal Android recreation of ChatGPT's **Advanced Voice Mode** —
true speech-to-speech conversation using OpenAI's **Realtime API**
(`gpt-4o-realtime-preview`) over a persistent WebSocket connection.

Unlike a "cascade" voice assistant (speech-to-text → text LLM →
text-to-speech, three separate stages/models), this app streams raw
PCM16 mic audio directly to OpenAI and streams raw PCM16 audio replies
directly back — no local ASR/TTS, one multimodal model handling both
directions of audio.

## How it works

1. Tap the orb to start a session. The app requests mic permission,
   opens a WebSocket to `wss://api.openai.com/v1/realtime`, and sends a
   `session.update` configuring voice, PCM16 audio format, and
   **server-side VAD** (the server itself detects when you start/stop
   talking from the continuous stream — no push-to-talk button).
2. Mic audio streams continuously as base64 PCM16 chunks
   (`input_audio_buffer.append` events).
3. The server's VAD fires `input_audio_buffer.speech_started` /
   `speech_stopped` events and auto-generates a response once you stop
   talking.
4. `response.audio.delta` events stream back base64 PCM16 chunks, which
   play immediately via `AudioTrack` as they arrive — not after the
   full reply is generated, which is what keeps perceived latency low.
5. If you start talking again while the assistant is still replying,
   the app clears buffered playback and sends `response.cancel` —
   basic barge-in/interruption support.
6. Tap the orb again to end the session.

The orb color reflects session state: grey = idle, amber = connecting,
blue = listening, purple = thinking, green = speaking, red = error.

## Setup

1. Get an OpenAI API key with Realtime API access from
   https://platform.openai.com/api-keys (billing must be enabled — the
   Realtime API bills per audio token, notably more expensive than
   text-only calls).
2. Create `local.properties` in the project root (gitignored, never
   committed) with:
   ```
   sdk.dir=/path/to/android/sdk
   OPENAI_API_KEY=sk-...
   ```
3. `./gradlew assembleDebug` (or the bundled Gradle on this machine —
   see `app/build.gradle.kts`), then sideload the APK.

## Architecture notes

- **Auth**: the API key is sent directly from the client as a
  WebSocket header. This is fine for a personal/sideloaded app you
  build and run yourself, but a shipped public app should mint
  short-lived ephemeral tokens via a backend instead of embedding a
  long-lived key in the APK.
- **No background mode**: the session ends if the app is
  backgrounded — there's no foreground service keeping the WebSocket
  and mic alive off-screen in this first version.
- **WebSocket, not WebRTC**: OpenAI's Realtime API supports both
  transports. WebSocket + manual PCM16 framing was chosen here to keep
  the implementation dependency-light and easy to reason about,
  trading off some of WebRTC's built-in jitter-buffering/echo
  cancellation sophistication.
