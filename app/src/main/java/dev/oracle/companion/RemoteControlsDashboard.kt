package dev.oracle.companion

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlin.math.roundToInt

private enum class ControlSection(val label: String, val icon: String) {
    OVERVIEW("Overview", "dashboard"),
    ACTIVITY("Activity", "clock"),
    POWER("Power", "power"),
    SCHEDULES("Schedules", "clock"),
    AUDIO("Audio", "volume"),
    MEDIA("Media", "music"),
    DISPLAY("Display", "monitor"),
    APPS("Apps", "grid"),
    INPUT("Input", "touch"),
    AUTOMATIONS("Routines", "star"),
    SCREEN("Screen", "eye"),
    DIAGNOSTICS("Diagnostics", "cpu")
}

@Composable
internal fun RemoteControlsDashboard(
    api: CompanionApi,
    pcName: String,
    connected: Boolean,
    remote: Boolean,
    onBack: () -> Unit,
    onSwitchToPhone: (() -> Unit)? = null,
    lastConnectedAt: Long = 0L,
    connectionIssue: String? = null
) {
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf(ControlSection.OVERVIEW) }
    var state by remember { mutableStateOf<PcStateSnapshot?>(null) }
    var capabilities by remember { mutableStateOf(PcCapabilities()) }
    var bannerMessage by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var refreshCounter by remember { mutableIntStateOf(0) }

    // Auto-refresh state snapshot every 3 seconds while screen is active
    LaunchedEffect(api, connected, refreshCounter) {
        if (!connected) return@LaunchedEffect
        try {
            capabilities = RemoteControlsApi.getCapabilities(api)
        } catch (_: Exception) {}
        while (true) {
            try {
                val snapshot = RemoteControlsApi.getState(api)
                if (snapshot != null) state = snapshot
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {}
            delay(3000)
        }
    }

    fun dispatch(action: String, params: JSONObject = JSONObject(), feedback: String = "") {
        if (isBusy || !connected) return
        isBusy = true
        bannerMessage = ""
        scope.launch {
            try {
                val res = RemoteControlsApi.sendCommand(api, action, params)
                val msg = res.optJSONObject("result")?.optString("message") ?: res.optString("message", feedback)
                bannerMessage = msg.ifEmpty { "PC replied. See Activity for the receipt." }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                bannerMessage = (e as? ApiFailure)?.message ?: "Unable to confirm the action. Check Activity before trying again."
            } finally {
                isBusy = false
                refreshCounter++
            }
        }
    }

    CompanionPage {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onBack) {
                    Text("← Back")
                }
                if (onSwitchToPhone != null) {
                    TextButton(onClick = onSwitchToPhone) {
                        Text("Phone Controls")
                    }
                }
            }
            Surface(
                shape = CircleShape,
                color = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusRadarDot(online = connected)
                    Text(
                        when {
                            !connected -> "Offline"
                            remote -> "Tailscale Remote"
                            else -> "Local Wi-Fi"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (connected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Device Summary Banner
        CompanionPanel {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(pcName.ifEmpty { "Windows PC" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    val batt = state?.battery
                    val battText = if (batt != null) "${batt.percent}% · ${if (batt.charging) "Charging" else if (batt.pluggedIn) "Plugged In" else "Battery"}" else "AC Desktop"
                    val uptimeH = ((state?.uptimeSeconds ?: 0L) / 3600).toInt()
                    val uptimeM = (((state?.uptimeSeconds ?: 0L) % 3600) / 60).toInt()
                    Text(
                        "Uptime: ${uptimeH}h ${uptimeM}m · $battText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { refreshCounter++ },
                    enabled = !isBusy && connected
                ) {
                    Text("↻", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (bannerMessage.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text(
                        bannerMessage,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Section Selector Navigation Chips
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ControlSection.values().forEach { sec ->
                FilterChip(
                    selected = section == sec,
                    onClick = { section = sec },
                    label = { Text(sec.label) }
                )
            }
        }

        // Active Section Content
        Crossfade(targetState = section, label = "SectionCrossfade") { current ->
            when (current) {
                ControlSection.OVERVIEW -> OverviewSubView(
                    state = state,
                    capabilities = capabilities,
                    connected = connected,
                    isBusy = isBusy,
                    onNavigate = { section = it },
                    onDispatch = ::dispatch
                )
                ControlSection.POWER -> PowerSubView(
                    state = state,
                    capabilities = capabilities,
                    connected = connected,
                    isBusy = isBusy,
                    onDispatch = ::dispatch
                )
                ControlSection.SCHEDULES -> SchedulesSubView(
                    api = api,
                    state = state,
                    connected = connected,
                    isBusy = isBusy,
                    onRefresh = { refreshCounter++ }
                )
                ControlSection.AUDIO -> AudioSubView(
                    api = api,
                    state = state,
                    connected = connected,
                    isBusy = isBusy,
                    onDispatch = ::dispatch,
                    onRefresh = { refreshCounter++ }
                )
                ControlSection.MEDIA -> MediaSubView(
                    state = state,
                    connected = connected,
                    isBusy = isBusy,
                    onDispatch = ::dispatch
                )
                ControlSection.DISPLAY -> DisplaySubView(
                    state = state,
                    capabilities = capabilities,
                    connected = connected,
                    isBusy = isBusy,
                    onDispatch = ::dispatch
                )
                ControlSection.APPS -> ApplicationsSubView(
                    api = api,
                    connected = connected,
                    isBusy = isBusy,
                    onDispatch = ::dispatch,
                    onRefresh = { refreshCounter++ }
                )
                ControlSection.INPUT -> RemoteInputSubView(
                    api = api,
                    connected = connected,
                    isBusy = isBusy
                )
                ControlSection.AUTOMATIONS -> AutomationsSubView(
                    api = api,
                    state = state,
                    connected = connected,
                    isBusy = isBusy,
                    onRefresh = { refreshCounter++ }
                )
                ControlSection.SCREEN -> ScreenPreviewSubView(
                    api = api,
                    connected = connected
                )
                ControlSection.ACTIVITY -> ActivityReceiptsView(api)
                ControlSection.DIAGNOSTICS -> DiagnosticsSubView(
                    lastConnectedAt = lastConnectedAt,
                    connectionIssue = connectionIssue,
                    api = api,
                    capabilities = capabilities,
                    connected = connected,
                    remote = remote
                )
            }
        }
    }
}

// ==============================================================================
// ORACLE SYSTEM LIFECYCLE CARD
// ==============================================================================
@Composable
private fun OracleSystemCard(
    oracleSystem: PcOracleSystemStatus?,
    connected: Boolean,
    isBusy: Boolean,
    onDispatch: (String, JSONObject, String) -> Unit
) {
    val isRunning = oracleSystem?.running == true
    val emerald = Color(0xff10b981)
    val amber = Color(0xfff59e0b)
    val rose = Color(0xfff43f5e)
    val cyan = Color(0xff06b6d4)
    val statusColor = if (isRunning) emerald else amber

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xff151822),
        border = BorderStroke(1.dp, Color(0x26ffffff))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Category Tag & Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "ORACLE SYSTEM LIFECYCLE",
                        style = MaterialTheme.typography.labelSmall,
                        color = cyan,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        "Central AI & Services",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = statusColor.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StatusRadarDot(online = isRunning)
                        Text(
                            if (isRunning) "SYSTEM ONLINE" else "SYSTEM STOPPED",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
            }

            Text(
                if (isRunning)
                    "Oracle Central services (FastAPI, Ollama local model, PostgreSQL, and Presence UI) are actively running."
                else
                    "Oracle Central is currently switched off to conserve power & GPU resources. The background gateway is ready to wake it remotely.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xff94a3b8)
            )

            // Subsystem Status Telemetry Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SubsystemBadge(
                    name = "API :8000",
                    active = oracleSystem?.backend == true,
                    modifier = Modifier.weight(1f)
                )
                SubsystemBadge(
                    name = "OLLAMA",
                    active = oracleSystem?.ollama == true,
                    modifier = Modifier.weight(1f)
                )
                SubsystemBadge(
                    name = "DB :55432",
                    active = oracleSystem?.database == true,
                    modifier = Modifier.weight(1f)
                )
                val port = oracleSystem?.frontendPort ?: 5173
                SubsystemBadge(
                    name = "UI :$port",
                    active = oracleSystem?.frontend == true,
                    modifier = Modifier.weight(1f)
                )
            }

            // Primary Control Actions
            if (!isRunning) {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xff7c3aed),
                        contentColor = Color.White
                    ),
                    onClick = {
                        onDispatch("system.oracle_start", JSONObject(), "Starting Oracle AI Central...")
                    },
                    enabled = connected && !isBusy
                ) {
                    Text(
                        "▶  START ORACLE SYSTEM",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, rose.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = rose
                        ),
                        onClick = {
                            onDispatch("system.oracle_stop", JSONObject(), "Stopping Oracle AI Central...")
                        },
                        enabled = connected && !isBusy
                    ) {
                        Text("⏹ Stop Oracle", fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0x33ffffff)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White
                        ),
                        onClick = {
                            onDispatch("system.oracle_restart", JSONObject(), "Restarting Oracle AI Central...")
                        },
                        enabled = connected && !isBusy
                    ) {
                        Text("↺ Restart", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SubsystemBadge(name: String, active: Boolean, modifier: Modifier = Modifier) {
    val dotColor = if (active) Color(0xff10b981) else Color(0xff64748b)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color(0xff0d0e15),
        border = BorderStroke(1.dp, Color(0x1fffffff))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (active) Color.White else Color(0xff64748b),
                maxLines = 1
            )
        }
    }
}

// ==============================================================================
// 1. OVERVIEW VIEW
// ==============================================================================
@Composable
private fun OverviewSubView(
    state: PcStateSnapshot?,
    capabilities: PcCapabilities,
    connected: Boolean,
    isBusy: Boolean,
    onNavigate: (ControlSection) -> Unit,
    onDispatch: (String, JSONObject, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Oracle AI System Lifecycle Card
        OracleSystemCard(
            oracleSystem = state?.oracleSystem,
            connected = connected,
            isBusy = isBusy,
            onDispatch = onDispatch
        )

        // Quick Action Grid
        CompanionPanel {
            Text("Quick Actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { onDispatch("power.lock", JSONObject(), "Workstation locked.") },
                    enabled = connected && !isBusy
                ) {
                    Text("Lock PC")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { onDispatch("display.turn_off", JSONObject(), "Display turned off.") },
                    enabled = connected && !isBusy
                ) {
                    Text("Screen Off")
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { onDispatch("media.play_pause", JSONObject(), "Media playback toggled.") },
                    enabled = connected && !isBusy
                ) {
                    Text(if (state?.media?.isPlaying == true) "Pause Media" else "Play Media")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val currentMute = state?.muted ?: false
                        onDispatch("audio.mute", JSONObject().put("muted", !currentMute), if (!currentMute) "Muted" else "Unmuted")
                    },
                    enabled = connected && !isBusy
                ) {
                    Text(if (state?.muted == true) "Unmute" else "Mute")
                }
            }
        }

        // Live Audio Telemetry Card
        CompanionPanel {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Master Volume", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { onNavigate(ControlSection.AUDIO) }) { Text("Mixer →") }
            }
            val vol = state?.volume ?: 50
            Text("$vol% ${if (state?.muted == true) "· Muted" else ""}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = vol.toFloat(),
                onValueChange = {},
                onValueChangeFinished = {},
                valueRange = 0f..100f,
                enabled = false
            )
        }

        // Next Scheduled Action Card
        state?.nextSchedule?.let { sched ->
            CompanionPanel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Pending Power Action", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { onNavigate(ControlSection.SCHEDULES) }) { Text("All →") }
                }
                val dueFormatted = DateFormat.getDateTimeInstance().format(Date(sched.due * 1000))
                val remainingMin = ((sched.remainingSeconds + 59) / 60).coerceAtLeast(0)
                val actionTitle = sched.action.replace("power.", "").replace("_", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                Text("$actionTitle scheduled at $dueFormatted")
                Text("About $remainingMin min remaining", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }

        // Now Playing Preview Card
        state?.media?.takeIf { it.title.isNotEmpty() }?.let { media ->
            CompanionPanel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Now Playing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { onNavigate(ControlSection.MEDIA) }) { Text("Controls →") }
                }
                Text(media.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(media.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ==============================================================================
// 2. POWER VIEW
// ==============================================================================
@Composable
private fun PowerSubView(
    state: PcStateSnapshot?,
    capabilities: PcCapabilities,
    connected: Boolean,
    isBusy: Boolean,
    onDispatch: (String, JSONObject, String) -> Unit
) {
    var confirmationTarget by remember { mutableStateOf<String?>(null) }
    var stayAwakeMinutes by rememberSaveable { mutableIntStateOf(60) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Oracle Central System Card
        OracleSystemCard(
            oracleSystem = state?.oracleSystem,
            connected = connected,
            isBusy = isBusy,
            onDispatch = onDispatch
        )

        // Workstation & Session Lock
        CompanionPanel {
            Text("Session Security", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Locking your PC secures the interactive session without stopping background apps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onDispatch("power.lock", JSONObject(), "Workstation locked.") },
                enabled = connected && !isBusy && capabilities.canLock
            ) {
                Text("Lock Workstation")
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { confirmationTarget = "sign_out" },
                enabled = connected && !isBusy
            ) {
                Text("Sign Out Active User")
            }
        }

        // Stay Awake Card
        CompanionPanel {
            Text("Prevent Sleep (Stay Awake)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val active = state?.stayAwake?.active == true
            if (active) {
                val remM = ((state?.stayAwake?.remainingSeconds ?: 0) + 59) / 60
                Text("Stay-awake active: about $remM min remaining.", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onDispatch("power.cancel_stay_awake", JSONObject(), "Stay-awake cancelled.") },
                    enabled = connected && !isBusy
                ) {
                    Text("Cancel Stay Awake (Restore Normal Sleep)")
                }
            } else {
                Text("Keep PC and display awake for $stayAwakeMinutes minutes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = stayAwakeMinutes.toFloat(),
                    onValueChange = { stayAwakeMinutes = it.roundToInt() },
                    valueRange = 15f..240f,
                    steps = 14,
                    enabled = connected && !isBusy
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onDispatch("power.stay_awake", JSONObject().put("duration_seconds", stayAwakeMinutes * 60), "Stay-awake activated.")
                    },
                    enabled = connected && !isBusy
                ) {
                    Text("Activate Stay Awake")
                }
            }
        }

        // Sleep, Hibernate, Display Off
        CompanionPanel {
            Text("Low Power Modes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { onDispatch("display.turn_off", JSONObject(), "Display turned off.") },
                    enabled = connected && !isBusy && capabilities.canTurnOffDisplay
                ) {
                    Text("Display Off")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { confirmationTarget = "sleep" },
                    enabled = connected && !isBusy && capabilities.canSleep
                ) {
                    Text("Sleep PC")
                }
            }
            if (capabilities.canHibernate) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { confirmationTarget = "hibernate" },
                    enabled = connected && !isBusy
                ) {
                    Text("Hibernate PC")
                }
            }
        }

        // Destructive Controls (Shutdown / Restart)
        CompanionPanel {
            Text("Power Down", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Safely shut down or restart the PC. Unsaved work will be closed by Windows.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = { confirmationTarget = "shutdown" },
                    enabled = connected && !isBusy && capabilities.canShutdown
                ) {
                    Text("Shut Down")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { confirmationTarget = "restart" },
                    enabled = connected && !isBusy && capabilities.canRestart
                ) {
                    Text("Restart")
                }
            }
        }
    }

    // Confirmation Dialog
    confirmationTarget?.let { target ->
        val title = when (target) {
            "shutdown" -> "Shut Down PC"
            "restart" -> "Restart PC"
            "sleep" -> "Sleep PC"
            "hibernate" -> "Hibernate PC"
            "sign_out" -> "Sign Out"
            else -> "Confirm Action"
        }
        val message = when (target) {
            "shutdown" -> "Are you sure you want to shut down your PC? Any unsaved work in running desktop applications may be lost."
            "restart" -> "Are you sure you want to restart? Oracle Companion will temporarily lose connection until Windows boots."
            "sleep" -> "Put PC to sleep? The remote connection will pause until someone wakes the PC from the desk or via Wake-on-LAN."
            "hibernate" -> "Hibernate Windows session to disk and power down?"
            "sign_out" -> "Sign out active Windows user? Interactive applications and windows will be closed."
            else -> "Execute this power action?"
        }
        val actionTag = when (target) {
            "shutdown" -> "power.shutdown"
            "restart" -> "power.restart"
            "sleep" -> "power.sleep"
            "hibernate" -> "power.hibernate"
            "sign_out" -> "power.sign_out"
            else -> "power.lock"
        }

        AlertDialog(
            onDismissRequest = { confirmationTarget = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (target in listOf("shutdown", "sign_out")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    ),
                    onClick = {
                        confirmationTarget = null
                        onDispatch(actionTag, JSONObject(), "$title requested.")
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmationTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ==============================================================================
// 3. SCHEDULES VIEW
// ==============================================================================
@Composable
private fun SchedulesSubView(
    api: CompanionApi,
    state: PcStateSnapshot?,
    connected: Boolean,
    isBusy: Boolean,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var schedules by remember { mutableStateOf<List<PcSchedule>>(emptyList()) }
    var selectedAction by remember { mutableStateOf("power.shutdown") }
    var mode by rememberSaveable { mutableStateOf("countdown") }
    var minutesDelay by rememberSaveable { mutableIntStateOf(30) }
    var exactTimestamp by rememberSaveable { mutableLongStateOf(0L) }
    var recurrence by rememberSaveable { mutableStateOf("none") }
    var feedback by remember { mutableStateOf("") }

    LaunchedEffect(api, connected) {
        try {
            schedules = RemoteControlsApi.listSchedules(api)
        } catch (_: Exception) {}
    }

    fun submitSchedule() {
        if (!connected || isBusy) return
        feedback = ""
        scope.launch {
            try {
                val dueVal = if (mode == "exact") exactTimestamp else null
                val minVal = if (mode == "countdown") minutesDelay else null
                val recVal = if (recurrence != "none") recurrence else null
                RemoteControlsApi.createSchedule(api, selectedAction, mode, minVal, dueVal, recVal)
                feedback = "Schedule created successfully."
                schedules = RemoteControlsApi.listSchedules(api)
                onRefresh()
            } catch (e: Exception) {
                feedback = (e as? ApiFailure)?.message ?: (e.message ?: "Could not create schedule.")
            }
        }
    }

    fun cancelSchedule(id: String) {
        scope.launch {
            try {
                RemoteControlsApi.cancelSchedule(api, id)
                feedback = "Schedule cancelled."
                schedules = RemoteControlsApi.listSchedules(api)
                onRefresh()
            } catch (e: Exception) {
                feedback = "Failed to cancel: ${e.message}"
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Schedule Creator Panel
        CompanionPanel {
            Text("Create Scheduled Action", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            
            // Action Selector
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "power.shutdown" to "Shutdown",
                    "power.restart" to "Restart",
                    "power.sleep" to "Sleep",
                    "power.lock" to "Lock",
                    "power.turn_off_display" to "Display Off"
                ).forEach { (act, label) ->
                    FilterChip(
                        selected = selectedAction == act,
                        onClick = { selectedAction = act },
                        label = { Text(label) }
                    )
                }
            }

            // Mode Selector
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == "countdown",
                    onClick = { mode = "countdown" },
                    label = { Text("Countdown Timer") }
                )
                FilterChip(
                    selected = mode == "exact",
                    onClick = { mode = "exact" },
                    label = { Text("Exact Date & Time") }
                )
            }

            if (mode == "countdown") {
                Text("Delay: $minutesDelay minutes", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = minutesDelay.toFloat(),
                    onValueChange = { minutesDelay = it.roundToInt() },
                    valueRange = 5f..240f,
                    steps = 46,
                    enabled = connected
                )
            } else {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val cal = Calendar.getInstance()
                        DatePickerDialog(context, { _, year, month, day ->
                            cal.set(year, month, day)
                            TimePickerDialog(context, { _, hour, minute ->
                                cal.set(Calendar.HOUR_OF_DAY, hour)
                                cal.set(Calendar.MINUTE, minute)
                                cal.set(Calendar.SECOND, 0)
                                exactTimestamp = cal.timeInMillis / 1000
                            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
                        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
                            datePicker.minDate = System.currentTimeMillis()
                        }.show()
                    }
                ) {
                    Text(if (exactTimestamp > 0) DateFormat.getDateTimeInstance().format(Date(exactTimestamp * 1000)) else "Pick Date and Time")
                }
            }

            // Recurrence
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("none" to "Once", "daily" to "Daily", "weekdays" to "Weekdays", "weekly" to "Weekly").forEach { (rec, label) ->
                    FilterChip(
                        selected = recurrence == rec,
                        onClick = { recurrence = rec },
                        label = { Text(label) }
                    )
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = ::submitSchedule,
                enabled = connected && (mode == "countdown" || exactTimestamp > System.currentTimeMillis() / 1000)
            ) {
                Text("Confirm Schedule")
            }

            if (feedback.isNotEmpty()) {
                Text(feedback, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }

        // Active Schedules List
        CompanionPanel {
            Text("Pending & Active Schedules", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (schedules.isEmpty()) {
                Text("No scheduled power actions currently pending.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                schedules.forEach { s ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val sActionTitle = s.action.replace("power.", "").replace("_", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                                Text(sActionTitle, fontWeight = FontWeight.Bold)
                                Text("Due: ${DateFormat.getDateTimeInstance().format(Date(s.due * 1000))}", style = MaterialTheme.typography.bodySmall)
                                s.recurrence?.let { Text("Repeats: $it", style = MaterialTheme.typography.labelSmall) }
                            }
                            if (s.canCancel) {
                                OutlinedButton(onClick = { cancelSchedule(s.id) }) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==============================================================================
// 4. AUDIO VIEW (Master Volume, Devices, App Mixer, Presets)
// ==============================================================================
@Composable
private fun AudioSubView(
    api: CompanionApi,
    state: PcStateSnapshot?,
    connected: Boolean,
    isBusy: Boolean,
    onDispatch: (String, JSONObject, String) -> Unit,
    onRefresh: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var masterLevel by remember(state?.volume) { mutableFloatStateOf(state?.volume?.toFloat() ?: 50f) }
    var micLevel by remember(state?.micPercent) { mutableFloatStateOf(state?.micPercent?.toFloat() ?: 80f) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Master Volume Card
        CompanionPanel {
            Text("Master Output Volume", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${masterLevel.roundToInt()}% ${if (state?.muted == true) "· Muted" else ""}", style = MaterialTheme.typography.titleLarge)
            Slider(
                value = masterLevel,
                onValueChange = { masterLevel = it },
                onValueChangeFinished = {
                    onDispatch("audio.set_volume", JSONObject().put("percent", masterLevel.roundToInt()), "Volume updated.")
                },
                valueRange = 0f..100f,
                enabled = connected && !isBusy
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val currentMute = state?.muted ?: false
                        onDispatch("audio.mute", JSONObject().put("muted", !currentMute), "")
                    },
                    enabled = connected && !isBusy
                ) {
                    Text(if (state?.muted == true) "Unmute" else "Mute")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val stepDown = (masterLevel - 10).coerceAtLeast(0f)
                        masterLevel = stepDown
                        onDispatch("audio.set_volume", JSONObject().put("percent", stepDown.roundToInt()), "")
                    },
                    enabled = connected && !isBusy
                ) {
                    Text("Vol −10")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val stepUp = (masterLevel + 10).coerceAtMost(100f)
                        masterLevel = stepUp
                        onDispatch("audio.set_volume", JSONObject().put("percent", stepUp.roundToInt()), "")
                    },
                    enabled = connected && !isBusy
                ) {
                    Text("Vol +10")
                }
            }
        }

        // Audio Presets
        CompanionPanel {
            Text("Audio Presets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Study" to 25, "Music" to 65, "Night" to 15, "Meeting" to 50).forEach { (preset, targetVol) ->
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            masterLevel = targetVol.toFloat()
                            onDispatch("audio.set_volume", JSONObject().put("percent", targetVol), "Applied $preset preset ($targetVol%).")
                        },
                        enabled = connected && !isBusy
                    ) {
                        Text(preset, maxLines = 1)
                    }
                }
            }
        }

        // Microphone Control
        if (state?.micSupported == true) {
            CompanionPanel {
                Text("Microphone Input", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${micLevel.roundToInt()}% ${if (state.micMuted) "· Muted" else ""}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = micLevel,
                    onValueChange = { micLevel = it },
                    onValueChangeFinished = {
                        onDispatch("audio.set_mic_volume", JSONObject().put("percent", micLevel.roundToInt()), "Mic volume set.")
                    },
                    valueRange = 0f..100f,
                    enabled = connected && !isBusy
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onDispatch("audio.set_mic_mute", JSONObject().put("muted", !state.micMuted), "")
                    },
                    enabled = connected && !isBusy
                ) {
                    Text(if (state.micMuted) "Unmute Microphone" else "Mute Microphone")
                }
            }
        }

        // Application Volume Mixer
        CompanionPanel {
            Text("Per-Application Volume Mixer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val sessions = state?.appMixer ?: emptyList()
            if (sessions.isEmpty()) {
                Text("No active audio applications detected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                sessions.forEach { s ->
                    var appVol by remember(s.volume) { mutableFloatStateOf(s.volume.toFloat()) }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(s.label, fontWeight = FontWeight.SemiBold)
                                Text("${appVol.roundToInt()}% ${if (s.muted) "· Muted" else ""}", style = MaterialTheme.typography.bodySmall)
                            }
                            Slider(
                                value = appVol,
                                onValueChange = { appVol = it },
                                onValueChangeFinished = {
                                    onDispatch("audio.set_app_volume", JSONObject().put("pid", s.pid).put("percent", appVol.roundToInt()), "")
                                },
                                valueRange = 0f..100f,
                                enabled = connected
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==============================================================================
// 5. MEDIA VIEW (Now Playing, Playback Controls)
// ==============================================================================
@Composable
private fun MediaSubView(
    state: PcStateSnapshot?,
    connected: Boolean,
    isBusy: Boolean,
    onDispatch: (String, JSONObject, String) -> Unit
) {
    val media = state?.media ?: PcMedia("Media Player", "Windows Audio", "", false, "System")

    CompanionPanel {
        Text("Now Playing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("♪", fontSize = 32.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Text(media.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text("${media.artist} · ${media.source}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Media Buttons
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onDispatch("media.prev", JSONObject(), "Previous track.") },
                enabled = connected && !isBusy
            ) {
                Text("⏮", fontSize = 28.sp)
            }
            Button(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                onClick = { onDispatch("media.play_pause", JSONObject(), "Toggled playback.") },
                enabled = connected && !isBusy
            ) {
                Text(if (media.isPlaying) "⏸" else "▶", fontSize = 24.sp)
            }
            IconButton(
                onClick = { onDispatch("media.next", JSONObject(), "Next track.") },
                enabled = connected && !isBusy
            ) {
                Text("⏭", fontSize = 28.sp)
            }
        }
    }
}

// ==============================================================================
// 6. DISPLAY VIEW (Brightness & Monitors)
// ==============================================================================
@Composable
private fun DisplaySubView(
    state: PcStateSnapshot?,
    capabilities: PcCapabilities,
    connected: Boolean,
    isBusy: Boolean,
    onDispatch: (String, JSONObject, String) -> Unit
) {
    val disp = state?.display
    var brightnessLevel by remember(disp?.brightness) { mutableFloatStateOf(disp?.brightness?.toFloat() ?: 100f) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CompanionPanel {
            Text("Display Brightness", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (disp?.brightnessSupported == true || capabilities.canControlBrightness) {
                Text("${brightnessLevel.roundToInt()}%", style = MaterialTheme.typography.titleLarge)
                Slider(
                    value = brightnessLevel,
                    onValueChange = { brightnessLevel = it },
                    onValueChangeFinished = {
                        onDispatch("display.set_brightness", JSONObject().put("percent", brightnessLevel.roundToInt()), "Brightness set.")
                    },
                    valueRange = 5f..100f,
                    enabled = connected && !isBusy
                )
            } else {
                Text("Hardware/driver reports WMI internal brightness control is not available on this desktop monitor.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onDispatch("display.turn_off", JSONObject(), "Display turned off.") },
                enabled = connected && !isBusy
            ) {
                Text("Turn Connected Display Off (Keep PC Awake)")
            }
        }

        CompanionPanel {
            Text("Connected Displays", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${disp?.monitorCount ?: 1} monitor(s) detected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ==============================================================================
// 7. APPLICATIONS VIEW (Window Management & Process Terminate)
// ==============================================================================
@Composable
private fun ApplicationsSubView(
    api: CompanionApi,
    connected: Boolean,
    isBusy: Boolean,
    onDispatch: (String, JSONObject, String) -> Unit,
    onRefresh: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var runningApps by remember { mutableStateOf<List<PcAppWindow>>(emptyList()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedAppToKill by remember { mutableStateOf<PcAppWindow?>(null) }

    LaunchedEffect(api, connected) {
        try {
            runningApps = RemoteControlsApi.getRunningApps(api)
        } catch (_: Exception) {}
    }

    val filteredApps = remember(runningApps, searchQuery) {
        if (searchQuery.isBlank()) runningApps
        else runningApps.filter { it.title.contains(searchQuery, ignoreCase = true) || it.appName.contains(searchQuery, ignoreCase = true) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // App Launcher
        CompanionPanel {
            Text("Launch Approved Application", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "vscode" to "VS Code",
                    "chrome" to "Chrome",
                    "edge" to "Edge",
                    "calc" to "Calculator",
                    "notepad" to "Notepad",
                    "explorer" to "Explorer",
                    "spotify" to "Spotify"
                ).forEach { (appId, label) ->
                    OutlinedButton(onClick = { onDispatch("apps.launch", JSONObject().put("app_id", appId), "Launched $label.") }, enabled = connected && !isBusy) {
                        Text(label)
                    }
                }
            }
        }

        // Running Windows List
        CompanionPanel {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Open Windows (${filteredApps.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = {
                    scope.launch {
                        try { runningApps = RemoteControlsApi.getRunningApps(api) } catch (_: Exception) {}
                    }
                }) {
                    Text("↻", fontWeight = FontWeight.Bold)
                }
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter applications…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            filteredApps.forEach { app ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(app.appName, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("${app.memoryMb.roundToInt()} MB · PID ${app.pid}", style = MaterialTheme.typography.labelSmall)
                        }
                        Text(app.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { onDispatch("apps.window_state", JSONObject().put("hwnd", app.hwnd).put("state", "focus"), "Focused.") }) { Text("Focus") }
                            TextButton(onClick = { onDispatch("apps.window_state", JSONObject().put("hwnd", app.hwnd).put("state", "minimize"), "Minimized.") }) { Text("Minimize") }
                            TextButton(onClick = { onDispatch("apps.window_state", JSONObject().put("hwnd", app.hwnd).put("state", "restore"), "Restored.") }) { Text("Maximize") }
                            TextButton(onClick = { onDispatch("apps.window_state", JSONObject().put("hwnd", app.hwnd).put("state", "close"), "Close requested.") }) { Text("Close") }
                            TextButton(
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                onClick = { selectedAppToKill = app }
                            ) { Text("Force Close") }
                        }
                    }
                }
            }
        }
    }

    selectedAppToKill?.let { target ->
        AlertDialog(
            onDismissRequest = { selectedAppToKill = null },
            title = { Text("Force Close ${target.appName}?") },
            text = { Text("Forcefully terminating PID ${target.pid} will immediately stop the process without saving unsaved data.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val pid = target.pid
                        selectedAppToKill = null
                        onDispatch("apps.terminate", JSONObject().put("pid", pid).put("force", true), "Terminated process.")
                    }
                ) { Text("Force Close") }
            },
            dismissButton = {
                TextButton(onClick = { selectedAppToKill = null }) { Text("Cancel") }
            }
        )
    }
}

// ==============================================================================
// 8. REMOTE INPUT VIEW (Trackpad, Keyboard, Presentation, Clipboard)
// ==============================================================================
@Composable
private fun RemoteInputSubView(
    api: CompanionApi,
    connected: Boolean,
    isBusy: Boolean
) {
    val scope = rememberCoroutineScope()
    var inputTab by rememberSaveable { mutableIntStateOf(0) }
    var textInput by rememberSaveable { mutableStateOf("") }
    var clipboardText by remember { mutableStateOf("") }
    var sensitivity by rememberSaveable { mutableFloatStateOf(1.2f) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TabRow(selectedTabIndex = inputTab) {
            listOf("Trackpad", "Keyboard", "Slides", "Clipboard").forEachIndexed { idx, title ->
                Tab(selected = inputTab == idx, onClick = { inputTab = idx }, text = { Text(title) })
            }
        }

        when (inputTab) {
            0 -> { // Trackpad
                CompanionPanel {
                    Text("Touch Trackpad", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Drag on surface to move pointer. Tap for Left Click.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .pointerInput(connected) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    scope.launch {
                                        try {
                                            RemoteControlsApi.sendInput(
                                                api, "move",
                                                JSONObject().put("dx", (dragAmount.x * sensitivity).roundToInt())
                                                    .put("dy", (dragAmount.y * sensitivity).roundToInt())
                                            )
                                        } catch (_: Exception) {}
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Touchpad Active Area", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }

                    // Mouse Click Buttons
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                scope.launch {
                                    try { RemoteControlsApi.sendInput(api, "click", JSONObject().put("button", "left")) } catch (_: Exception) {}
                                }
                            },
                            enabled = connected
                        ) { Text("Left Click") }
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                scope.launch {
                                    try { RemoteControlsApi.sendInput(api, "click", JSONObject().put("button", "right")) } catch (_: Exception) {}
                                }
                            },
                            enabled = connected
                        ) { Text("Right Click") }
                    }

                    // Sensitivity slider
                    Text("Pointer Sensitivity: ${(sensitivity * 10).roundToInt() / 10f}x", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = sensitivity,
                        onValueChange = { sensitivity = it },
                        valueRange = 0.5f..3.0f
                    )
                }
            }
            1 -> { // Keyboard
                CompanionPanel {
                    Text("Remote Keyboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = { Text("Type text to send to PC…") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (textInput.isNotEmpty()) {
                                val t = textInput
                                textInput = ""
                                scope.launch {
                                    try { RemoteControlsApi.sendInput(api, "text", JSONObject().put("text", t)) } catch (_: Exception) {}
                                }
                            }
                        })
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (textInput.isNotEmpty()) {
                                val t = textInput
                                textInput = ""
                                scope.launch {
                                    try { RemoteControlsApi.sendInput(api, "text", JSONObject().put("text", t)) } catch (_: Exception) {}
                                }
                            }
                        },
                        enabled = connected && textInput.isNotEmpty()
                    ) {
                        Text("Send Text")
                    }

                    // Common Keys
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Enter" to "enter", "Backspace" to "backspace", "Escape" to "escape", "Tab" to "tab").forEach { (label, k) ->
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    scope.launch {
                                        try { RemoteControlsApi.sendInput(api, "key", JSONObject().put("key", k)) } catch (_: Exception) {}
                                    }
                                },
                                enabled = connected
                            ) { Text(label, maxLines = 1) }
                        }
                    }
                    // Arrow Keys
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf("←" to "left", "↑" to "up", "↓" to "down", "→" to "right").forEach { (label, k) ->
                            Button(
                                onClick = {
                                    scope.launch {
                                        try { RemoteControlsApi.sendInput(api, "key", JSONObject().put("key", k)) } catch (_: Exception) {}
                                    }
                                },
                                enabled = connected
                            ) { Text(label) }
                        }
                    }
                }
            }
            2 -> { // Presentation Remote
                CompanionPanel {
                    Text("Presentation Controller", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                scope.launch {
                                    try { RemoteControlsApi.sendInput(api, "shortcut", JSONObject().put("key", "presentation_start")) } catch (_: Exception) {}
                                }
                            },
                            enabled = connected
                        ) { Text("Start (F5)") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                scope.launch {
                                    try { RemoteControlsApi.sendInput(api, "shortcut", JSONObject().put("key", "presentation_end")) } catch (_: Exception) {}
                                }
                            },
                            enabled = connected
                        ) { Text("End (Esc)") }
                    }
                    Row(modifier = Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = RoundedCornerShape(16.dp),
                            onClick = {
                                scope.launch {
                                    try { RemoteControlsApi.sendInput(api, "shortcut", JSONObject().put("key", "presentation_prev")) } catch (_: Exception) {}
                                }
                            },
                            enabled = connected
                        ) { Text("◀ Previous", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                        Button(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = RoundedCornerShape(16.dp),
                            onClick = {
                                scope.launch {
                                    try { RemoteControlsApi.sendInput(api, "shortcut", JSONObject().put("key", "presentation_next")) } catch (_: Exception) {}
                                }
                            },
                            enabled = connected
                        ) { Text("Next ▶", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            3 -> { // Clipboard
                CompanionPanel {
                    Text("Shared Clipboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                scope.launch {
                                    try { clipboardText = RemoteControlsApi.readClipboard(api) } catch (_: Exception) {}
                                }
                            },
                            enabled = connected
                        ) { Text("Fetch from PC") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                scope.launch {
                                    try {
                                        RemoteControlsApi.writeClipboard(api, clipboardText)
                                    } catch (_: Exception) {}
                                }
                            },
                            enabled = connected && clipboardText.isNotEmpty()
                        ) { Text("Send to PC") }
                    }
                    OutlinedTextField(
                        value = clipboardText,
                        onValueChange = { clipboardText = it },
                        label = { Text("Clipboard text") },
                        modifier = Modifier.fillMaxWidth().height(120.dp)
                    )
                }
            }
        }
    }
}

// ==============================================================================
// 9. AUTOMATIONS VIEW (Routines)
// ==============================================================================
@Composable
private fun AutomationsSubView(
    api: CompanionApi,
    state: PcStateSnapshot?,
    connected: Boolean,
    isBusy: Boolean,
    onRefresh: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var routines by remember { mutableStateOf<List<PcRoutine>>(emptyList()) }
    var activeRunId by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf("") }

    LaunchedEffect(api, connected) {
        try {
            routines = RemoteControlsApi.listRoutines(api)
        } catch (_: Exception) {}
    }

    fun runRoutine(id: String, name: String) {
        if (!connected || isBusy) return
        activeRunId = id
        feedback = ""
        scope.launch {
            try {
                RemoteControlsApi.runRoutine(api, id)
                feedback = "Started '$name'."
                delay(1000)
                routines = RemoteControlsApi.listRoutines(api)
                onRefresh()
            } catch (e: Exception) {
                feedback = "Failed: ${e.message}"
            } finally {
                activeRunId = null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CompanionPanel {
            Text("Automated Routines", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Run multi-action sequences with one touch.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (feedback.isNotEmpty()) {
                Text(feedback, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            routines.forEach { r ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(r.name, fontWeight = FontWeight.Bold)
                            Text(r.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            r.lastStatus?.let { Text("Status: $it", style = MaterialTheme.typography.labelSmall) }
                        }
                        Button(
                            onClick = { runRoutine(r.id, r.name) },
                            enabled = connected && activeRunId == null
                        ) {
                            Text(if (activeRunId == r.id) "Running…" else "Run")
                        }
                    }
                }
            }
        }
    }
}

// ==============================================================================
// 10. SCREEN PREVIEW VIEW
// ==============================================================================
@Composable
private fun ScreenPreviewSubView(
    api: CompanionApi,
    connected: Boolean
) {
    val scope = rememberCoroutineScope()
    var isSharingActive by remember { mutableStateOf(false) }
    var currentBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var errorText by remember { mutableStateOf("") }

    LaunchedEffect(isSharingActive, connected) {
        if (!isSharingActive || !connected) return@LaunchedEffect
        while (isSharingActive) {
            var delayMs = 800L
            try {
                val res = withContext(Dispatchers.IO) { RemoteControlsApi.getScreenFrame(api) }
                if (res.optBoolean("active", false)) {
                    val b64 = res.optString("image", "")
                    if (b64.isNotEmpty()) {
                        val bytes = Base64.decode(b64, Base64.NO_WRAP)
                        currentBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        errorText = ""
                        delayMs = 800L
                    }
                } else {
                    errorText = res.optString("error", "Screen sharing not active.")
                    delayMs = 2000L
                }
            } catch (e: Exception) {
                errorText = e.message ?: "Failed to grab frame."
                delayMs = 2500L
            }
            delay(delayMs)
        }
    }

    CompanionPanel {
        Text("Remote Screen Preview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("View active desktop display in real time with consent.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Button(
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isSharingActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ),
            onClick = {
                val next = !isSharingActive
                scope.launch {
                    try {
                        RemoteControlsApi.toggleScreenSharing(api, next)
                        isSharingActive = next
                    } catch (e: Exception) {
                        errorText = e.message ?: "Could not toggle screen sharing."
                    }
                }
            },
            enabled = connected
        ) {
            Text(if (isSharingActive) "Stop Screen Preview" else "Start Live Screen Preview")
        }

        if (errorText.isNotEmpty()) {
            Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        currentBitmap?.let { bmp ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "PC Screen Frame",
                    modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                )
            }
        }
    }
}

// ==============================================================================
// 11. DIAGNOSTICS VIEW
// ==============================================================================
@Composable
private fun DiagnosticsSubView(
    api: CompanionApi,
    capabilities: PcCapabilities,
    connected: Boolean,
    remote: Boolean,
    lastConnectedAt: Long,
    connectionIssue: String?
) {
    val scope = rememberCoroutineScope()
    var wolFeedback by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Connection Posture
        CompanionPanel {
            Text("Connection Diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Status: ${if (connected) "Online" else "Disconnected"}")
            if (connected) Text("Network route: ${if (remote) "Tailscale" else "Local network"}")
            Text("Last reached: ${if (lastConnectedAt == 0L) "Unavailable" else DateFormat.getDateTimeInstance().format(Date(lastConnectedAt))}")
            connectionIssue?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        // Wake-on-LAN
        CompanionPanel {
            Text("Wake-on-LAN (WOL)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val macs = capabilities.macAddresses
            if (macs.isNotEmpty()) {
                Text("Discovered MAC Addresses:", fontWeight = FontWeight.SemiBold)
                macs.forEach { mac ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(mac, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = {
                                scope.launch {
                                    try {
                                        val res = RemoteControlsApi.sendWol(api, mac)
                                        wolFeedback = "Magic packet broadcasted for $mac."
                                    } catch (e: Exception) {
                                        wolFeedback = "WOL failed: ${e.message}"
                                    }
                                }
                            },
                            enabled = connected
                        ) { Text("Send WOL") }
                    }
                }
                if (wolFeedback.isNotEmpty()) {
                    Text(wolFeedback, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("No network interface MAC address available for WOL.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Capability Matrix
        CompanionPanel {
            Text("Discovered PC Capabilities", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            listOf(
                "Workstation Lock" to capabilities.canLock,
                "Sleep Suspended" to capabilities.canSleep,
                "Hibernate to Disk" to capabilities.canHibernate,
                "Power Shutdown" to capabilities.canShutdown,
                "System Restart" to capabilities.canRestart,
                "Display Off" to capabilities.canTurnOffDisplay,
                "Stay Awake (Execution State)" to capabilities.canStayAwake,
                "Core Audio Master Volume" to capabilities.canControlMasterVolume,
                "Microphone Volume/Mute" to capabilities.canControlMic,
                "Application Volume Mixer" to capabilities.canControlAppMixer,
                "Display Brightness (WMI)" to capabilities.canControlBrightness,
                "Window Management" to capabilities.canManageWindows,
                "Remote Mouse/Keyboard Input" to capabilities.canSendRemoteInput,
                "Screen Frame Sharing" to capabilities.canShareScreen,
                "Durable Scheduler" to capabilities.canScheduleActions,
                "Automation Routines" to capabilities.canRunRoutines,
                "Wake-on-LAN Configured" to capabilities.wakeOnLanConfigured
            ).forEach { (feature, supported) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(feature, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (supported) "Supported" else "Unavailable",
                        color = if (supported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}


@Composable
private fun ActivityReceiptsView(api: CompanionApi) {
    val receipts = api.actionReceipts ?: return
    val history by receipts.history.collectAsState()
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CompanionPanel {
            Text("Action history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (history.isEmpty()) "PC controls, schedules and routines sent from this phone will appear here."
                else "Recent requests sent from this phone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        history.forEach { receipt ->
            CompanionPanel {
                Text(receipt.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val color = when (receipt.status) {
                    "Failed", "Rejected" -> MaterialTheme.colorScheme.error
                    "PC replied" -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(receipt.status, style = MaterialTheme.typography.labelLarge, color = color)
                Text(
                    "Requested ${dateFormat.format(Date(receipt.sentAt))}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (receipt.respondedAt > 0L) {
                    Text("Response ${dateFormat.format(Date(receipt.respondedAt))}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
