package dev.oracle.companion

import android.graphics.BitmapFactory
import android.os.Bundle
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID

private data class DesktopProposal(val id: String, val name: String)

private data class ActionResult(val status: String, val message: String) {
    constructor(data: JSONObject) : this(data.optString("status"), data.getString("message"))

    val title: String
        get() = when (status) {
            "launch_requested" -> "Launch accepted"
            "confirming" -> "Waiting for Windows"
            "failed" -> "Action failed"
            "unknown" -> "Outcome unverified"
            else -> "Desktop action"
        }
}

private data class Entry(val id: String, val role: String, val text: String, val state: String)

private fun JSONObject.entries(key: String): List<JSONObject> {
    val list = getJSONArray(key)
    return (0 until list.length()).map(list::getJSONObject)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferences = remember { getSharedPreferences("companion_settings", MODE_PRIVATE) }
            var themePref by remember { mutableStateOf(preferences.getString("theme_mode", "dark") ?: "dark") }
            val systemDark = isSystemInDarkTheme()
            val isDark = when (themePref) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            CompositionLocalProvider(LocalDarkTheme provides isDark) {
                MaterialTheme(
                    colorScheme = if (isDark) OracleDarkColors else OracleLightColors,
                    typography = OracleTypography
                ) {
                    Companion(
                        isDark = isDark,
                        onToggleTheme = {
                            val next = if (isDark) "light" else "dark"
                            themePref = next
                            preferences.edit().putString("theme_mode", next).apply()
                        }
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Companion(isDark: Boolean, onToggleTheme: () -> Unit) {
        val store = remember { ConnectionStore(this) }
        val scope = rememberCoroutineScope()
        val preferences = remember { getSharedPreferences("companion_settings", MODE_PRIVATE) }
        var actionAlerts by remember { mutableStateOf(preferences.getBoolean("action_alerts", false)) }
        val alerts = remember { SnackbarHostState() }
        var selectedResult by remember { mutableStateOf<ActionResult?>(null) }
        var target by remember { mutableStateOf(store.read()) }
        val api = remember(target) { target?.let { CompanionApi(it, applicationContext) } }
        var tab by rememberSaveable { mutableStateOf(HomeTab.HOME) }
        val conversation = rememberLazyListState()
        var deviceSheet by rememberSaveable { mutableStateOf(false) }
        var showPairing by rememberSaveable { mutableStateOf(false) }
        var pairing by remember { mutableStateOf("") }
        var manualText by remember { mutableStateOf("") }
        var showOptions by remember { mutableStateOf(false) }
        var showManual by remember { mutableStateOf(false) }
        var deepReply by remember { mutableStateOf(false) }
        var modeSheet by remember { mutableStateOf(false) }
        var partialReply by remember { mutableStateOf("") }
        var stopRequested by remember { mutableStateOf(false) }
        var lastQuick by remember { mutableStateOf<String?>(null) }
        var generatingReply by remember { mutableStateOf<String?>(null) }
        var draft by rememberSaveable { mutableStateOf("") }
        var retryId by rememberSaveable { mutableStateOf<String?>(null) }
        var checkingDelivery by rememberSaveable { mutableStateOf(false) }
        var status by remember { mutableStateOf<JSONObject?>(null) }
        var remoteConnection by remember { mutableStateOf(false) }
        var lastConnectedAt by remember { mutableStateOf(store.lastConnectedAt()) }
        var connectionIssue by remember { mutableStateOf<String?>(null) }
        var connectionTestPassed by remember { mutableStateOf(false) }
        var testingConnection by remember { mutableStateOf(false) }
        val connectionChecks = remember(api) { Channel<Unit>(Channel.CONFLATED) }
        var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
        var messages by remember { mutableStateOf(emptyList<Entry>()) }
        var events by remember { mutableStateOf(emptyList<ActionResult>()) }
        var proposal by remember { mutableStateOf<DesktopProposal?>(null) }
        var error by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val supportsModes = status?.optBoolean("replyModes") == true
        var incomingTrip by remember(api) { mutableStateOf<JSONObject?>(null) }
        val live = status?.optBoolean("oracle") == true

        fun setHistory(result: JSONObject) {
            messages = result.entries("messages").map {
                Entry(it.getString("id"), it.getString("role"), it.getString("text"), it.getString("state"))
            }
        }

        fun recoverDelivery(): Boolean {
            val id = retryId ?: return false
            val message = messages.firstOrNull { it.id == id }
            checkingDelivery = message?.state == "pending"
            if (message?.state != "complete") return false
            retryId = null
            draft = ""
            partialReply = ""
            error = ""
            return true
        }

        fun reset() {
            alerts.currentSnackbarData?.dismiss()
            selectedResult = null
            store.clear()
            deviceSheet = false
            tab = HomeTab.HOME
            target = null
            status = null
            lastConnectedAt = 0L
            connectionIssue = null
            connectionTestPassed = false
            testingConnection = false
            proposal = null
            messages = emptyList()
            events = emptyList()
            draft = ""
            retryId = null
            checkingDelivery = false
        }

        fun operation(block: suspend () -> Unit) {
            if (busy) return
            busy = true
            error = ""
            scope.launch {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = when (failure) {
                        is ApiFailure, is IllegalArgumentException -> failure.message ?: "Request failed."
                        is javax.net.ssl.SSLException -> "Desktop identity could not be verified. Check the PC pairing code."
                        else -> "Could not reach Oracle. Check Wi-Fi or Tailscale and make sure the PC is online."
                    }
                    if (failure is ApiFailure && failure.status == 401) reset()
                } finally {
                    busy = false
                }
            }
        }

        val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        pairing = withContext(Dispatchers.IO) {
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                            require(options.outWidth > 0 && options.outHeight > 0)
                            options.inJustDecodeBounds = false
                            options.inSampleSize = 1
                            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1800) {
                                options.inSampleSize *= 2
                            }
                            val bitmap = contentResolver.openInputStream(uri)?.use {
                                BitmapFactory.decodeStream(it, null, options)
                            } ?: error("Image unavailable")
                            try {
                                val pixels = IntArray(bitmap.width * bitmap.height)
                                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(
                                    RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                                ))).text
                            } finally { bitmap.recycle() }
                        }
                        error = ""
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "No readable QR code was found. Use a clear image or paste the pairing text."
                    }
                }
            }
        }

        val camera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.getStringExtra("pairing")?.let {
                pairing = it
                error = ""
            }
        }
        fun scanQr() {
            error = ""
            try {
                camera.launch(android.content.Intent(this@MainActivity, PairingScannerActivity::class.java))
            } catch (_: Exception) {
                error = "Camera unavailable. Choose a QR image instead."
            }
        }

        var listening by remember { mutableStateOf(false) }
        var voiceSession by remember { mutableStateOf(false) }
        var voicePhase by remember { mutableStateOf("Listening…") }
        var voiceCaption by remember { mutableStateOf("") }
        var voiceReady by remember { mutableStateOf(false) }
        var speaker by remember { mutableStateOf<TextToSpeech?>(null) }
        var nextTurn by remember { mutableStateOf(false) }
        var utterance by remember { mutableStateOf<String?>(null) }

        DisposableEffect(Unit) {
            val engine = TextToSpeech(this@MainActivity) { result ->
                runOnUiThread { voiceReady = result == TextToSpeech.SUCCESS }
            }
            speaker = engine
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) {
                    runOnUiThread {
                        if (voiceSession && id != null && id == utterance) nextTurn = true
                    }
                }
                @Deprecated("Required by Android TextToSpeech")
                override fun onError(id: String?) {
                    runOnUiThread {
                        if (voiceSession && id != null && id == utterance)
                            voicePhase = "Could not play the spoken reply."
                    }
                }
            })
            onDispose { engine.stop(); engine.shutdown() }
        }

        fun endVoice() {
            voiceSession = false
            nextTurn = false
            utterance = null
            speaker?.stop()
        }

        val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            listening = false
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.trim()?.take(4000)
            if (voiceSession && result.resultCode == RESULT_OK && !spoken.isNullOrBlank() && !busy && api != null) {
                voiceCaption = spoken
                voicePhase = "Preparing reply…"
                operation {
                    try {
                        val body = JSONObject().put("id", UUID.randomUUID().toString())
                            .put("text", spoken).put("mode", "quick")
                        val response = withContext(Dispatchers.IO) { api.call("chat", body) }
                        setHistory(response)
                        if (voiceSession) {
                            val reply = messages.lastOrNull()?.takeIf { it.role == "model" }?.text
                                ?: error("Oracle returned no reply.")
                            voiceCaption = reply
                            voicePhase = "Speaking…"
                            val id = UUID.randomUUID().toString()
                            utterance = id
                            val spokenReply = reply.take(TextToSpeech.getMaxSpeechInputLength())
                            if (speaker?.speak(spokenReply, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS)
                                voicePhase = "Could not play the spoken reply."
                        }
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        if (voiceSession) voicePhase = (failure as? ApiFailure)?.message
                            ?: "Could not get a reply. Check the connection and try again."
                    }
                }
            } else if (voiceSession) {
                endVoice()
            }
        }

        fun startVoice() {
            if (busy || listening) return
            if (!live) {
                val message = "Reconnect to Oracle Central to use voice."
                if (voiceSession) voicePhase = message else error = message
                return
            }
            if (!voiceReady) {
                error = "Spoken replies are unavailable. Check the phone’s text-to-speech settings."
                return
            }
            try {
                voiceSession = true
                voiceCaption = ""
                voicePhase = "Listening…"
                listening = true
                voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to Oracle"))
            } catch (_: android.content.ActivityNotFoundException) {
                listening = false
                endVoice()
                error = "Speech recognition is unavailable on this phone. You can type your message."
            }
        }

        LaunchedEffect(nextTurn, busy) {
            if (nextTurn && !busy) {
                delay(350)
                nextTurn = false
                if (voiceSession) startVoice()
            }
        }

        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, _ ->
                foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }

        DisposableEffect(api, foreground) {
            val manager = getSystemService(ConnectivityManager::class.java)
            if (api == null || !foreground || manager == null) return@DisposableEffect onDispose {}
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = checkRoute()
                override fun onLost(network: Network) = checkRoute()

                private fun checkRoute() {
                    api.invalidateRoute()
                    connectionChecks.trySend(Unit)
                }
            }
            manager.registerDefaultNetworkCallback(callback)
            onDispose { manager.unregisterNetworkCallback(callback) }
        }

        LaunchedEffect(api, foreground) {
            if (api == null || !foreground) return@LaunchedEffect
            api.invalidateRoute()
            var waitMillis = 3000L
            while (true) {
                val newStatus = try {
                    withContext(Dispatchers.IO) { api.call("status") }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    status = null
                    connectionTestPassed = false
                    testingConnection = false
                    if (failure is ApiFailure && failure.status == 401) {
                        reset()
                        error = "This phone’s access was revoked. Pair again from Windows."
                        return@LaunchedEffect
                    }
                    connectionIssue = when (failure) {
                        is javax.net.ssl.SSLException ->
                            "Could not verify the desktop certificate. Check Oracle Central and pair again if the certificate changed."
                        is ApiFailure -> "The desktop responded with error ${failure.status}. Check Oracle Central and try again."
                        else -> if (target?.remoteHost != null || api.isRemote)
                            "Desktop unreachable. Check Tailscale on both devices and make sure Oracle Central is running."
                        else "Desktop unreachable. Check that both devices are on the same network and Oracle Central is running."
                    }
                    null
                }
                if (newStatus != null) {
                    android.util.Log.d("OracleStatus", "STATUS: $newStatus")
                    remoteConnection = api.isRemote
                    status = newStatus
                    val now = System.currentTimeMillis()
                    store.recordConnection(now)
                    lastConnectedAt = now
                    connectionIssue = if (newStatus.optBoolean("oracle")) null
                        else "Pairing verified. Start Oracle Central on the desktop."
                    connectionTestPassed = connectionIssue == null && (testingConnection || connectionTestPassed)
                    testingConnection = false
                }
                try {
                    val current = target
                    if (current != null && newStatus != null && newStatus.has("remoteHost")) {
                        val remote = newStatus.optString("remoteHost").takeIf(Connection::isRemoteAddress)
                        if (remote != current.remoteHost) {
                            val updated = current.copy(remoteHost = remote)
                            withContext(Dispatchers.IO) { store.write(updated) }
                            target = updated
                            return@LaunchedEffect
                        }
                    }
                    if (newStatus != null && !busy) {
                        val history = withContext(Dispatchers.IO) { api.call("chat") }
                        if (!busy) {
                            setHistory(history)
                            recoverDelivery()
                        }
                        events = withContext(Dispatchers.IO) { api.call("activity") }.entries("events")
                            .map(::ActionResult)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (failure is ApiFailure && failure.status == 401) {
                        reset()
                        error = "This phone’s access was revoked. Pair again from Windows."
                        return@LaunchedEffect
                    }
                }
                if (newStatus != null && tab == HomeTab.ACTIVITY) {
                    try {
                        incomingTrip = withContext(Dispatchers.IO) {
                            api.call("atlas/trip").optJSONObject("trip")
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        incomingTrip = null
                    }
                }
                if (newStatus != null) waitMillis = 3000
                val refreshed = withTimeoutOrNull(waitMillis) { connectionChecks.receive() } != null
                if (refreshed) {
                    waitMillis = 3000
                } else if (newStatus == null) {
                    waitMillis = (waitMillis * 2).coerceAtMost(30_000)
                }
            }
        }

        val candidate = remember(pairing) {
            runCatching {
                val payload = JSONObject(pairing.trim())
                require(payload.getString("code").isNotBlank())
                Connection.parse(payload).copy(token = "")
            }.getOrNull()
        }

        val chatOpen = target != null && tab == HomeTab.CHAT
        val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0

        BackHandler(enabled = target != null && tab != HomeTab.HOME) {
            tab = HomeTab.HOME
        }
        fun returnToWelcome() {
            pairing = ""
            manualText = ""
            showOptions = false
            showManual = false
            error = ""
            showPairing = false
        }

        BackHandler(enabled = target == null && showPairing) {
            if (!busy) returnToWelcome()
        }

        Scaffold(
            snackbarHost = { SnackbarHost(alerts) },
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.imePadding(),
            bottomBar = {
                if (target != null && !(chatOpen && keyboardOpen)) {
                    CompanionNavigation(
                        tab = tab,
                        onSettings = { deviceSheet = true }
                    ) { tab = it }
                }
            }
        ) { inset ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.padding(inset).consumeWindowInsets(inset).widthIn(max = 600.dp).fillMaxSize()
                    .padding(horizontal = if (chatOpen) 16.dp else 20.dp, vertical = if (chatOpen) 6.dp else 12.dp)) {
                    val paired = target
                    if (paired == null) {
                        if (!showPairing) {
                            CompanionWelcome(
                                error = error,
                                onGetStarted = {
                                    error = ""
                                    showPairing = true
                                }
                            )
                            return@Column
                        }
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TextButton(onClick = ::returnToWelcome, enabled = !busy) { Text("Back") }
                            Text("Connect to Oracle Central", style = MaterialTheme.typography.headlineMedium)
                            if (candidate == null) {
                                CompanionPanel {
                                    Text("Pair with your desktop", style = MaterialTheme.typography.titleMedium)
                                    Text("On your PC, open Oracle Central → System Settings → Connections → Companion.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Button(onClick = ::scanQr, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                    Text("Scan QR code")
                                }
                                TextButton(onClick = { showOptions = !showOptions }) {
                                    Text(if (showOptions) "Hide other options" else "Other ways to connect")
                                }
                                if (showOptions) {
                                    TextButton(onClick = { imagePicker.launch("image/*") }) { Text("Choose QR image") }
                                    TextButton(onClick = {
                                        showManual = !showManual
                                        manualText = ""
                                        error = ""
                                    }) { Text(if (showManual) "Hide manual entry" else "Enter pairing text manually") }
                                    if (showManual) {
                                        OutlinedTextField(manualText, { manualText = it.take(1000) },
                                            label = { Text("Paste pairing text") }, maxLines = 3,
                                            modifier = Modifier.fillMaxWidth())
                                        OutlinedButton(enabled = manualText.isNotBlank(), onClick = {
                                            val valid = runCatching {
                                                val payload = JSONObject(manualText.trim())
                                                require(payload.getString("code").isNotBlank())
                                                Connection.parse(payload)
                                            }.isSuccess
                                            if (valid) {
                                                pairing = manualText.trim()
                                                manualText = ""
                                                showManual = false
                                                error = ""
                                            } else {
                                                error = "This pairing text is invalid. Copy a fresh code from your PC."
                                            }
                                        }) { Text("Review PC") }
                                    }
                                }
                            } else {
                                CompanionPanel {
                                    Text("Pairing code detected", style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary)
                                    Text(candidate.pc, style = MaterialTheme.typography.titleLarge)
                                    Text(candidate.host, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Button(enabled = !busy, onClick = {
                                    val selected = candidate
                                    operation {
                                        val body = JSONObject().put("label", Build.MODEL)
                                            .put("code", JSONObject(pairing.trim()).getString("code"))
                                        val result = withContext(Dispatchers.IO) {
                                            CompanionApi(selected).pair(body)
                                        }
                                        val connected = selected.copy(pc = result.getString("pc"), token = result.getString("token"))
                                        store.write(connected)
                                        returnToWelcome()
                                        tab = HomeTab.HOME
                                        target = connected
                                    }
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (busy) "Connecting…" else "Connect to this PC")
                                }
                                TextButton(onClick = {
                                    pairing = ""
                                    scanQr()
                                }, enabled = !busy) { Text("Scan again") }
                            }
                            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                        }
                        return@Column
                    }

                    val currentTab = tab
                    if (currentTab == HomeTab.HOME) {
                            CompanionHome(
                                pc = paired.pc,
                                connected = status != null,
                                error = error,
                                voiceEnabled = !busy,
                                voiceStatus = if (voiceSession) voicePhase else null,
                                onTalk = ::startVoice,
                                onDevice = { deviceSheet = true },
                                onNavigate = { tab = it },
                                health = status?.optJSONObject("deviceHealth"),
                                remoteConnection = remoteConnection,
                                onQuickPrompt = { prompt ->
                                    draft = prompt
                                    tab = HomeTab.CHAT
                                },
                                isDark = isDark,
                                onToggleTheme = onToggleTheme,
                                oracleRunning = live,
                                onPcAction = { action, label ->
                                    when (action) {
                                        "vscode" -> {
                                            draft = "Open VS Code"
                                            tab = HomeTab.CHAT
                                        }
                                        "browser" -> {
                                            draft = "Open Browser"
                                            tab = HomeTab.CHAT
                                        }
                                        "system.oracle_start", "system.oracle_stop", "system.oracle_restart" -> {
                                            if (api != null) {
                                                scope.launch {
                                                    try {
                                                        val res = withContext(Dispatchers.IO) {
                                                            RemoteControlsApi.sendCommand(api, action)
                                                        }
                                                        val msg = res.optJSONObject("result")?.optString("message")
                                                            ?: res.optString("message", "Sent to ${paired.pc}: $label")
                                                        alerts.showSnackbar(msg)
                                                    } catch (e: Exception) {
                                                        alerts.showSnackbar("Command error: ${e.message ?: "Failed"}")
                                                    }
                                                }
                                            } else {
                                                scope.launch {
                                                    alerts.showSnackbar("Pair with PC to run remote commands.")
                                                }
                                            }
                                        }
                                        else -> {
                                            if (api != null) {
                                                scope.launch {
                                                    try {
                                                        withContext(Dispatchers.IO) {
                                                            api.call("device/controls", JSONObject().put("action", action))
                                                        }
                                                        alerts.showSnackbar("Sent to ${paired.pc}: $label")
                                                    } catch (e: Exception) {
                                                        alerts.showSnackbar("Command error: ${e.message ?: "Failed"}")
                                                    }
                                                }
                                            } else {
                                                scope.launch {
                                                    alerts.showSnackbar("Pair with PC to run remote commands.")
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                val isCurrentChat = currentTab == HomeTab.CHAT
                                if (isCurrentChat) {
                                    Text("Chat", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.SansSerif)
                                } else if (currentTab == HomeTab.VISION) {
                                    Text("Vision Studio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(8.dp))
                                } else {
                                    Text(currentTab.label, style = MaterialTheme.typography.headlineSmall)
                                    Spacer(Modifier.height(8.dp))
                                }
                                val connectionLabel = when {
                        live -> if (chatOpen) "Online" else "Oracle online"
                        status != null -> "Oracle unavailable"
                        else -> "Disconnected"
                    }
                    Surface(
                        onClick = { deviceSheet = true },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, CardBorderBrush)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(Modifier.size(8.dp).background(if (live) Color(0xff58dfa4) else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                            Text(paired?.pc.orEmpty(), fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelLarge)
                            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(connectionLabel,
                                color = if (live) Color(0xff58dfa4) else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium)
                            if (status != null) {
                                Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (remoteConnection) "Tailscale" else "Local",
                                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            }
                            Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                    }
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(if (chatOpen) 6.dp else 12.dp))

                    when (currentTab) {
                        HomeTab.HOME -> Unit
                        HomeTab.RECORDS -> RecordsPane(
                            api = api,
                            online = live,
                            onNavigateChat = { query ->
                                draft = query
                                tab = HomeTab.CHAT
                            }
                        )
                        HomeTab.ATLAS -> AtlasMapPane(
                            api = api,
                            online = live,
                            onNavigateChat = { query ->
                                draft = query
                                tab = HomeTab.CHAT
                            }
                        )
                        HomeTab.PEOPLE -> PeopleDirectoryPane(
                            api = api,
                            online = live,
                            onNavigateChat = { query ->
                                draft = query
                                tab = HomeTab.CHAT
                            }
                        )
                        HomeTab.VISION -> VisionPane(
                            api = api,
                            online = live,
                            onNavigateChat = { query ->
                                draft = query
                                tab = HomeTab.CHAT
                            }
                        )
                        HomeTab.CHAT -> {
                            val atLatest by remember {
                                derivedStateOf { !conversation.canScrollBackward }
                            }
                            val reply = generatingReply?.let { partialReply.ifEmpty { it } }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                if (messages.isEmpty() && reply == null) {
                                    Text(if (live) "Ask Oracle a question." else "Reconnect to send messages.",
                                        modifier = Modifier.align(Alignment.Center),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = FontFamily.SansSerif)
                                }
                                LazyColumn(
                                    Modifier.fillMaxSize(), state = conversation, reverseLayout = true,
                                    contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (reply != null) item(key = "streaming-reply") {
                                        ChatBubble("Oracle", reply, false, isStreaming = true)
                                    }
                                    items(messages.asReversed(), key = { it.id }) { entry ->
                                        val user = entry.role == "user"
                                        ChatBubble(if (user) "You" else "Oracle", entry.text, user,
                                            when (entry.state) {
                                                "complete" -> null
                                                "pending" -> "Awaiting reply"
                                                else -> "Reply failed"
                                            })
                                    }
                                }
                                if (!atLatest) {
                                    FilledTonalIconButton(
                                        onClick = { scope.launch { conversation.scrollToItem(0) } },
                                        modifier = Modifier.align(Alignment.BottomCenter)
                                            .semantics { contentDescription = "Jump to latest message" }
                                    ) { Text("↓") }
                                }
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(24.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.Bottom) {
                                    TextField(
                                        draft, { draft = it.take(4000); retryId = null; checkingDelivery = false }, Modifier.weight(1f),
                                        placeholder = { Text("Message Oracle", fontFamily = FontFamily.SansSerif) },
                                        enabled = !busy, maxLines = 3,
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
                                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                            disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                                            unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent
                                        )
                                    )
                                    if (generatingReply != null && status?.optBoolean("chatStreaming") == true) {
                                        FilledIconButton(enabled = !stopRequested, onClick = {
                                            stopRequested = true
                                            scope.launch(Dispatchers.IO) { api?.stopStream() }
                                        }, modifier = Modifier.padding(bottom = 4.dp).size(48.dp)
                                            .semantics { contentDescription = if (stopRequested) "Stopping reply" else "Stop reply" }) {
                                            ChatActionIcon(stop = true)
                                        }
                                } else FilledIconButton(enabled = live && status?.optBoolean("model") == true && !busy && !checkingDelivery && draft.isNotBlank(),
                                    onClick = {
                                        if (atLatest) conversation.requestScrollToItem(0)
                                        val previousAttempt = retryId
                                        val id = previousAttempt ?: UUID.randomUUID().toString()
                                        retryId = id
                                        operation {
                                            val client = requireNotNull(api)
                                            if (previousAttempt != null) {
                                                setHistory(withContext(Dispatchers.IO) { client.call("chat") })
                                                if (recoverDelivery() || checkingDelivery) return@operation
                                            }
                                            val deep = supportsModes && deepReply
                                            val streaming = status?.optBoolean("chatStreaming") == true
                                            val question = draft
                                            partialReply = ""
                                            stopRequested = false
                                            deepReply = false
                                            generatingReply = if (deep) "Thinking…" else "Preparing reply…"
                                            val payload = JSONObject().put("id", id).put("text", draft)
                                                .put("mode", if (deep) "deep" else "quick")
                                            try {
                                                val result = withContext(Dispatchers.IO) {
                                                    if (streaming) {
                                                        payload.put("stream", true)
                                                        api!!.call("chat", payload) { delta ->
                                                            scope.launch { if (generatingReply != null) partialReply += delta }
                                                        }
                                                    } else api!!.call("chat", payload)
                                                }
                                                setHistory(result)
                                                partialReply = ""
                                                lastQuick = if (deep) null else question
                                            } catch (failure: Exception) {
                                                if (failure is CancellationException) throw failure
                                                deepReply = deep
                                                if (!stopRequested) {
                                                    if (failure is java.io.IOException) {
                                                        checkingDelivery = true
                                                        connectionChecks.trySend(Unit)
                                                    }
                                                    throw failure
                                                }
                                                retryId = null
                                                draft = ""
                                            } finally {
                                                generatingReply = null
                                            }
                                            retryId = null
                                            draft = ""
                                        }
                                    }, modifier = Modifier.padding(bottom = 4.dp).size(48.dp)
                                        .semantics { contentDescription = "Send message" }) { ChatActionIcon() }
                                }
                            }
                            if (checkingDelivery && !busy) {
                                Text("Checking whether Oracle received your message…",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (supportsModes) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    TextButton(onClick = { modeSheet = true }, enabled = !busy) {
                                        Text(if (deepReply) "Think deeper ▾" else "Quick ▾", fontFamily = FontFamily.SansSerif)
                                    }
                                    if (lastQuick != null && !busy && !keyboardOpen) {
                                        TextButton(onClick = {
                                            draft = lastQuick.orEmpty()
                                            retryId = null
                                            checkingDelivery = false
                                            deepReply = true
                                        }) { Text("Retry with Think deeper", fontFamily = FontFamily.SansSerif) }
                                    }
                                }
                            }
                        }
                        HomeTab.MONITOR -> SystemMonitorPane(
                            api = requireNotNull(api),
                            pc = target?.pc.orEmpty(),
                            connected = status != null,
                            remote = remoteConnection,
                            onBack = { tab = HomeTab.HOME }
                        )
                        HomeTab.DEVICE_CONTROLS -> DeviceControlsPane(
                            api = requireNotNull(api), pc = target?.pc.orEmpty(), connected = status != null,
                            remote = remoteConnection, health = status?.optJSONObject("deviceHealth"),
                            onBack = { tab = HomeTab.CONTROL },
                            onOpenMonitor = { tab = HomeTab.MONITOR },
                            lastConnectedAt = lastConnectedAt, connectionIssue = connectionIssue
                        )
                        HomeTab.CONTROL -> {
                            CompanionPage {
                                CompanionPanel {
                                    Text("PC Remote Control", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text("Complete command center: Power, Audio, Media, Displays, Apps, Input, Screen Preview, and Routines.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Button(onClick = { tab = HomeTab.DEVICE_CONTROLS }) { Text("Open PC Controls") }
                                }
                                CompanionPanel {
                                    Text("System Monitor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text("Real-time CPU, RAM, GPU, storage, network & thermal intelligence.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Button(onClick = { tab = HomeTab.MONITOR }) { Text("Open System Monitor") }
                                }
                                Surface(
                                    onClick = { tab = HomeTab.VISION },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, CardBorderBrush)
                                ) {
                                    Row(
                                        Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                                CompanionGlyph("Voice", MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text("Vision & Photo Analysis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                            Text("Send a photo or screenshot to Oracle on PC",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                        }
                                        CompanionGlyph("Next", MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                CompanionPanel {
                                    Text("Developer & Workspaces", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    if (!live) {
                                        Text("Connect to Oracle Central to run desktop actions.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text("Visual Studio Code", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                                                Text("Launch active project workspace on PC", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                            }
                                            Button(
                                                enabled = live && !busy,
                                                onClick = {
                                                    operation {
                                                        proposal = withContext(Dispatchers.IO) {
                                                            DesktopProposal(api!!.call("actions/propose", JSONObject()).getString("id"), "VS Code")
                                                        }
                                                    }
                                                },
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Text("Launch")
                                            }
                                        }
                                    }

                                    if (status?.optBoolean("atlasAction") == true) {
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(14.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text("Atlas Workspace", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                                                    Text("Open Atlas visualization workspace on PC", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                                }
                                                Button(
                                                    enabled = live && !busy,
                                                    onClick = {
                                                        operation {
                                                            proposal = withContext(Dispatchers.IO) {
                                                                val result = api!!.call("actions/propose", JSONObject().put("target", "atlas"))
                                                                DesktopProposal(result.getString("id"), "Atlas")
                                                            }
                                                        }
                                                    },
                                                    shape = RoundedCornerShape(12.dp)
                                                ) {
                                                    Text("Launch")
                                                }
                                            }
                                        }
                                    }
                                }

                                CompanionPanel {
                                    Text("Action Security", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text("Desktop actions require explicit confirmation on your device to keep your computer safe and authorized.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        HomeTab.DEVICE -> {
                            CompanionPage {
                                CompanionPanel {
                                    Text("Paired desktop", style = MaterialTheme.typography.titleMedium)
                                    Text(target?.host.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(when {
                                        status == null -> "Offline"
                                        remoteConnection -> "Remote via Tailscale"
                                        else -> "Local network"
                                    })
                                    val lastSeen = if (lastConnectedAt == 0L) "Never" else
                                    java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT
                                    ).format(java.util.Date(lastConnectedAt))
                                    Text("Last connected: $lastSeen", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    connectionIssue?.let {
                                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (connectionTestPassed) Text("Desktop reached. Pairing verified.")
                                    OutlinedButton(enabled = !testingConnection, onClick = {
                                        testingConnection = true
                                        connectionIssue = null
                                        connectionTestPassed = false
                            api?.invalidateRoute()
                            connectionChecks.trySend(Unit)
                                    }) { Text(if (testingConnection) "Testing connection…" else "Test connection") }
                                }
                                CompanionPanel {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Desktop action alerts", Modifier.weight(1f))
                                        Switch(checked = actionAlerts, onCheckedChange = { enabled ->
                                            actionAlerts = enabled
                                            preferences.edit().putBoolean("action_alerts", enabled).apply()
                                            if (!enabled) alerts.currentSnackbarData?.dismiss()
                                        }, modifier = Modifier.semantics { contentDescription = "Desktop action alerts" })
                                    }
                                }
                                OutlinedButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy, onClick = {
                                    operation {
                                        if (status != null) withContext(Dispatchers.IO) { api!!.call("unpair", JSONObject()) }
                                        reset()
                                    }
                                }) { Text(if (status == null) "Forget on this phone" else "Unpair phone") }
                            }
                        }
                        HomeTab.ACTIVITY -> {
                            LazyColumn(Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (status == null) item {
                                    Text("Disconnected · Activity may be out of date.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                incomingTrip?.takeIf { it.optLong("expires") > System.currentTimeMillis() / 1000 }
                                    ?.let { trip ->
                                    item {
                                        CompanionPanel {
                                            Text("Atlas destination", style = MaterialTheme.typography.titleSmall)
                                            Text(trip.optString("destination"))
                                            Button(onClick = {
                                                val uri = android.net.Uri.parse(trip.optString("url"))
                                                when {
                                                    trip.optLong("expires") <= System.currentTimeMillis() / 1000 -> {
                                                        incomingTrip = null
                                                        error = "This destination expired. Send it again from Atlas."
                                                    }
                                                    uri.scheme != "https" || uri.host != "www.google.com" ||
                                                        uri.path != "/maps/dir/" -> error = "Invalid Maps link."
                                                    else -> try {
                                                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                                                    } catch (_: android.content.ActivityNotFoundException) {
                                                        error = "No app is available to open directions."
                                                    }
                                                }
                                            }) { Text("Open in Maps") }
                                        }
                                    }
                                }
                                if (events.isEmpty() && incomingTrip == null) item {
                                    CompanionPanel {
                                        Text("No action results yet.", style = MaterialTheme.typography.titleSmall)
                                        Text("Completed desktop actions appear here.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                items(events) { result ->
                                    val (badgeText, badgeColor) = when (result.status) {
                                        "launch_requested" -> "Accepted" to Color(0xff58dfa4)
                                        "confirming" -> "Pending" to MaterialTheme.colorScheme.primary
                                        "failed" -> "Failed" to MaterialTheme.colorScheme.error
                                        else -> "Verified" to MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    OutlinedCard(
                                        onClick = { selectedResult = result },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(18.dp),
                                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        border = BorderStroke(1.dp, Brush.verticalGradient(listOf(badgeColor.copy(alpha = 0.35f), MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))))
                                    ) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text(result.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = badgeColor.copy(alpha = 0.15f),
                                                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.4f))
                                                ) {
                                                    Text(badgeText, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                        color = badgeColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            Text(result.message, style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        }

        val paired = target
        if (deviceSheet && paired != null) {
            ConnectedDeviceSheet(
                pc = paired.pc,
                connected = status != null,
                oracleOnline = live,
                remoteConnection = remoteConnection,
                model = status?.optString("modelName")?.takeUnless { it.isBlank() || it == "null" },
                health = status?.optJSONObject("deviceHealth"),
                onDismiss = { deviceSheet = false },
                onControl = {
                    deviceSheet = false
                    tab = HomeTab.CONTROL
                },
                onManage = {
                    deviceSheet = false
                    tab = HomeTab.DEVICE
                }
            )
        }

        if (modeSheet && supportsModes) {
            CompanionSheet(onDismiss = { modeSheet = false }) {
                    Text("Reply mode", style = MaterialTheme.typography.titleLarge)
                for ((deep, label, description) in listOf(
                    Triple(false, "Quick", "Fast replies for everyday questions."),
                    Triple(true, "Think deeper", "More time for complex questions. Applies to the next message.")
                )) {
                    Row(Modifier.fillMaxWidth().selectable(
                        selected = deep == deepReply,
                        role = Role.RadioButton,
                        onClick = { deepReply = deep; modeSheet = false }
                    ).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected = deep == deepReply, onClick = null)
                        Column {
                            Text(label, style = MaterialTheme.typography.titleSmall)
                            Text(description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (voiceSession) {
            CompanionSheet(onDismiss = ::endVoice) {
                Text("Talk to Oracle", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    OracleOrb(Modifier.size(110.dp), isListening = (voicePhase == "Listening…"), isThinking = (voicePhase == "Preparing reply…"))
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, CardBorderBrush),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(voicePhase, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.primary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                }
                if (voiceCaption.isNotBlank()) {
                    CompanionPanel {
                        Text(if (voicePhase == "Speaking…") "Oracle reply" else "Spoken input",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(voiceCaption, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (!busy && !listening && !nextTurn && voicePhase != "Speaking…") {
                    Button(onClick = ::startVoice, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Speak again") }
                }
                OutlinedButton(onClick = ::endVoice, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("End voice session") }
            }
        }

        selectedResult?.let { result ->
            AlertDialog(onDismissRequest = { selectedResult = null }, containerColor = MaterialTheme.colorScheme.surface, title = { Text(result.title) },
                text = { Text(result.message) },
                confirmButton = { TextButton(onClick = { selectedResult = null }) { Text("Close") } })
        }

        proposal?.let { action ->
            AlertDialog(onDismissRequest = { proposal = null }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("Open ${action.name}?") },
                text = { Text("Send a launch request to ${target?.pc.orEmpty()}.") },
                confirmButton = { Button(onClick = {
                    proposal = null
                    if (!actionAlerts) tab = HomeTab.ACTIVITY
                    operation {
                        val result = withContext(Dispatchers.IO) {
                            api!!.call("actions/confirm", JSONObject().put("id", action.id))
                        }
                        val outcome = ActionResult(result)
                        events = listOf(outcome) + events
                        if (actionAlerts) {
                            alerts.currentSnackbarData?.dismiss()
                            scope.launch {
                                if (alerts.showSnackbar("${action.name} · ${outcome.title}", actionLabel = "View",
                                        withDismissAction = true) == SnackbarResult.ActionPerformed) {
                                    tab = HomeTab.ACTIVITY
                                    selectedResult = outcome
                                }
                            }
                        }
                    }
                }, enabled = !busy) { Text("Confirm") } },
                dismissButton = { TextButton(onClick = { proposal = null }) { Text("Cancel") } })
        }
    }
}



@Composable
private fun ChatBubble(
    author: String,
    message: String,
    user: Boolean,
    detail: String? = null,
    isStreaming: Boolean = false
) {
    val cursorAlpha = if (isStreaming) {
        val cursorTransition = rememberInfiniteTransition(label = "cursor")
        cursorTransition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(500, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "cursor_blink"
        ).value
    } else 0f

    val body = remember(message, user, isStreaming, if (isStreaming) cursorAlpha > 0.4f else false) {
        val fullText = if (isStreaming && cursorAlpha > 0.4f) "$message ▋" else message
        if (user || !fullText.contains("**")) {
            AnnotatedString(fullText)
        } else {
            buildAnnotatedString {
                var cursor = 0
                for (match in replyBold.findAll(fullText)) {
                    append(fullText.substring(cursor, match.range.first))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(match.groupValues[1])
                    }
                    cursor = match.range.last + 1
                }
                append(fullText.substring(cursor))
            }
        }
    }

    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(0.88f),
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (user) 18.dp else 4.dp,
                bottomEnd = if (user) 4.dp else 18.dp
            ),
            border = if (!user) BorderStroke(1.dp, CardBorderBrush) else null
        ) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        author, color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif
                    )
                    if (isStreaming) {
                        Box(Modifier.size(6.dp).background(Color(0xff58dfa4), CircleShape))
                    }
                }
                Text(body, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.SansSerif)
                if (detail != null) Text(
                    detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.SansSerif
                )
            }
        }
    }
}

private val replyBold = Regex("""(?<![\\*])\*\*(?![\s*])(.+?)(?<![\s\\])\*\*(?!\*)""")

@Composable
private fun ChatActionIcon(stop: Boolean = false) {
    val tint = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        if (stop) {
            val inset = size.width * 0.25f
            drawRoundRect(
                tint,
                topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
            )
        } else {
            val tip = Offset(size.width * 0.5f, size.height * 0.23f)
            val stroke = 2.dp.toPx()
            drawLine(tint, tip, Offset(tip.x, size.height * 0.78f), stroke, StrokeCap.Round)
            drawLine(tint, tip, Offset(size.width * 0.26f, size.height * 0.47f), stroke, StrokeCap.Round)
            drawLine(tint, tip, Offset(size.width * 0.74f, size.height * 0.47f), stroke, StrokeCap.Round)
        }
    }
}
