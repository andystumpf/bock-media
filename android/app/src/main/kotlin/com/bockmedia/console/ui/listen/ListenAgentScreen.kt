package com.bockmedia.console.ui.listen

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.httpErrorMessage
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.discovery.playDiscoveryTracksLocally
import com.bockmedia.console.ui.theme.BockGreen
import com.bockmedia.console.ui.theme.BockMuted
import com.bockmedia.console.ui.theme.SpotifyBackground
import com.bockmedia.console.ui.theme.SpotifyElevated
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException

private fun listenErrorMessage(e: Throwable): String {
    if (e is HttpException) {
        val raw = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty()
        when {
            raw.contains("artist_not_found") -> return "Couldn't find that artist in your library"
            raw.contains("album_not_found") -> return "Couldn't find that album in your library"
            raw.contains("song_not_found") -> return "Couldn't find that song in your library"
            raw.contains("playlist_not_found") -> return "Couldn't find that playlist"
            raw.contains("no_tracks_found") -> return "Found a match but no playable tracks"
        }
    }
    return httpErrorMessage(e, "Could not play that request")
}

private fun speechErrorMessage(code: Int): String? = when (code) {
    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
    -> "Didn't catch that — tap the mic to try again"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    -> "Network problem during speech recognition"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> null
    else -> "Speech recognition error — tap the mic to try again"
}

private enum class ListenPhase { Idle, Listening, Finding, Starting }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenAgentScreen(
    repository: BockMediaRepository,
    onDismiss: () -> Unit,
    onPlayStarted: () -> Unit = {},
    autoStartListening: Boolean = true,
    initialPrompt: String? = null,
    autoSubmitPrompt: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(ListenPhase.Idle) }
    var transcript by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var showTyping by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var statusLine by remember { mutableStateOf<String?>(null) }
    var soundLevel by remember { mutableFloatStateOf(0f) }

    val recognizerAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    val recognizer = remember {
        if (recognizerAvailable) SpeechRecognizer.createSpeechRecognizer(context) else null
    }

    fun stopListening() {
        runCatching { recognizer?.cancel() }
        if (phase == ListenPhase.Listening) phase = ListenPhase.Idle
        soundLevel = 0f
    }

    suspend fun playPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || phase == ListenPhase.Finding) return
        phase = ListenPhase.Finding
        error = null
        val resp = try {
            repository.listenAgentPlay(trimmed)
        } catch (e: CancellationException) {
            // Composition/effect cancellation must propagate — never show it as an error.
            throw e
        } catch (e: Exception) {
            error = listenErrorMessage(e)
            phase = ListenPhase.Idle
            return
        }
        if (resp.tracks.isEmpty()) {
            error = "No tracks found for \"$trimmed\""
            phase = ListenPhase.Idle
            return
        }
        repository.playDiscoveryTracksLocally(
            context,
            resp.tracks,
            resp.name ?: "Listen",
            shuffle = resp.shuffle ?: false,
        )
        onPlayStarted()
        statusLine = "${resp.name ?: "Music"} · ${resp.trackCount ?: resp.tracks.size} tracks"
        phase = ListenPhase.Starting
        delay(1100)
        onDismiss()
    }

    fun requestPlay(text: String) {
        scope.launch { playPrompt(text) }
    }

    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                phase = ListenPhase.Listening
                error = null
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
                // rms ranges roughly -2..10 dB; normalize to 0..1
                soundLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                soundLevel = 0f
            }

            override fun onError(errorCode: Int) {
                soundLevel = 0f
                if (phase == ListenPhase.Listening) phase = ListenPhase.Idle
                speechErrorMessage(errorCode)?.let { error = it }
            }

            override fun onResults(results: Bundle?) {
                soundLevel = 0f
                val heard = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (heard.isNotBlank()) {
                    transcript = heard
                    requestPlay(heard)
                } else if (phase == ListenPhase.Listening) {
                    phase = ListenPhase.Idle
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (partial.isNotBlank()) transcript = partial
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        onDispose {
            runCatching { recognizer?.destroy() }
        }
    }

    fun startListening() {
        if (recognizer == null) {
            error = "Speech recognition is not available on this device"
            showTyping = true
            return
        }
        error = null
        transcript = ""
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        runCatching { recognizer.startListening(intent) }
            .onFailure { error = "Couldn't start the microphone" }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startListening()
        else {
            error = "Microphone permission is required for voice input"
            showTyping = true
        }
    }

    LaunchedEffect(initialPrompt, autoStartListening, autoSubmitPrompt) {
        val prompt = initialPrompt?.trim().orEmpty()
        if (autoSubmitPrompt && prompt.isNotEmpty()) {
            typed = prompt
            showTyping = true
            scope.launch { playPrompt(prompt) }
            return@LaunchedEffect
        }
        if (prompt.isNotEmpty()) {
            typed = prompt
        }
        if (autoStartListening) {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    val headline = when (phase) {
        ListenPhase.Listening -> "Listening…"
        ListenPhase.Finding -> "Finding your music…"
        ListenPhase.Starting -> "Here we go"
        ListenPhase.Idle -> "What would you like to listen to?"
    }

    Scaffold(
        containerColor = SpotifyBackground,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { stopListening(); onDismiss() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        stopListening()
                        showTyping = !showTyping
                    }) {
                        Icon(Icons.Default.Keyboard, contentDescription = "Type instead", tint = BockMuted)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            Text(
                headline,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            if (phase == ListenPhase.Idle && transcript.isBlank()) {
                Text(
                    "Try \u201cplay top songs from Steely Dan\u201d\nor \u201cplay the album Siamese Dream\u201d",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BockMuted,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(20.dp))

            // Live transcript — what the agent hears, as you speak.
            val transcriptText = when {
                statusLine != null && phase == ListenPhase.Starting -> "Playing $statusLine"
                transcript.isNotBlank() -> "\u201c$transcript\u201d"
                else -> ""
            }
            Text(
                transcriptText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (phase == ListenPhase.Starting) BockGreen else Color.White,
                textAlign = TextAlign.Center,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(36.dp))

            ListenMicVisualizer(
                phase = phase,
                soundLevel = soundLevel,
                onTap = {
                    when (phase) {
                        ListenPhase.Listening -> stopListening()
                        ListenPhase.Idle -> micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        else -> {}
                    }
                },
            )

            Spacer(Modifier.height(14.dp))
            Text(
                when (phase) {
                    ListenPhase.Listening -> "Tap to stop"
                    ListenPhase.Finding -> "Matching against your library"
                    ListenPhase.Starting -> ""
                    ListenPhase.Idle -> "Tap the mic to speak"
                },
                style = MaterialTheme.typography.bodySmall,
                color = BockMuted,
            )

            if (showTyping) {
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it; error = null },
                    label = { Text("Type your request") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = phase != ListenPhase.Finding,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { requestPlay(typed) },
                    enabled = phase != ListenPhase.Finding && typed.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Play")
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** Pulsing mic orb: idle breathing, voice-reactive rings while listening, spinner while finding. */
@Composable
private fun ListenMicVisualizer(
    phase: ListenPhase,
    soundLevel: Float,
    onTap: () -> Unit,
) {
    val listening = phase == ListenPhase.Listening
    val infinite = rememberInfiniteTransition(label = "mic-pulse")

    val breath by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    val ringPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
        ),
        label = "ring",
    )
    val level by animateFloatAsState(
        targetValue = soundLevel,
        animationSpec = tween(90),
        label = "level",
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
        if (listening) {
            Canvas(Modifier.fillMaxSize()) {
                val maxR = size.minDimension / 2f
                val baseR = maxR * 0.42f
                // Two expanding rings, offset by half a cycle.
                for (offset in listOf(0f, 0.5f)) {
                    val p = (ringPhase + offset) % 1f
                    val radius = baseR + (maxR - baseR) * p
                    val alpha = (1f - p) * 0.35f
                    drawCircle(
                        color = BockGreen,
                        radius = radius,
                        alpha = alpha,
                    )
                }
                // Voice-reactive halo directly around the orb.
                drawCircle(
                    color = BockGreen,
                    radius = baseR * (1f + 0.45f * level),
                    alpha = 0.30f + 0.25f * level,
                )
            }
        }

        Surface(
            onClick = onTap,
            shape = CircleShape,
            color = if (listening) BockGreen else SpotifyElevated,
            modifier = Modifier
                .size(96.dp)
                .scale(if (listening) 1f + 0.05f * level else breath)
                .clip(CircleShape),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.background(
                    Brush.radialGradient(
                        colors = if (listening) {
                            listOf(BockGreen, Color(0xFF13803A))
                        } else {
                            listOf(SpotifyElevated, Color(0xFF1C1C1C))
                        },
                    ),
                ),
            ) {
                when (phase) {
                    ListenPhase.Finding -> CircularProgressIndicator(
                        Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                        color = BockGreen,
                    )
                    ListenPhase.Listening -> Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop listening",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp),
                    )
                    else -> Icon(
                        Icons.Default.Mic,
                        contentDescription = "Speak",
                        tint = if (phase == ListenPhase.Starting) BockGreen else Color.White,
                        modifier = Modifier.size(42.dp),
                    )
                }
            }
        }
    }
}
