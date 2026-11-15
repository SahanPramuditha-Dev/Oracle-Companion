package dev.oracle.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
internal fun DeviceControlsPane(
    api: CompanionApi, pc: String, connected: Boolean, remote: Boolean,
    health: JSONObject?, onBack: () -> Unit, onOpenMonitor: (() -> Unit)? = null,
    lastConnectedAt: Long = 0L, connectionIssue: String? = null
) {
    var page by rememberSaveable { mutableIntStateOf(1) } // Default to Paired PC
    BackHandler(onBack = {
        if (page == 0) page = 1 else onBack()
    })

    if (page == 1) {
        RemoteControlsDashboard(
            api = api,
            pcName = pc,
            connected = connected,
            remote = remote,
            onBack = onBack,
            onSwitchToPhone = { page = 0 },
            lastConnectedAt = lastConnectedAt,
            connectionIssue = connectionIssue
        )
    } else {
        CompanionPage {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { page = 1 }) { Text("← Paired PC Controls") }
                if (onOpenMonitor != null) {
                    TextButton(onClick = onOpenMonitor) { Text("System Monitor ›") }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (connected) Color(0xff10b981).copy(alpha = 0.15f) else Color(0xffef4444).copy(alpha = 0.15f)
                ) {
                    Text(
                        text = if (!connected) "Offline" else if (remote) "Tailscale Remote" else "Local LAN",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (connected) Color(0xff10b981) else Color(0xffef4444),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Text("Device Controls", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            TabRow(
                selectedTabIndex = page,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(selected = page == 0, onClick = { page = 0 }, text = { Text("This phone") })
                Tab(selected = page == 1, onClick = { page = 1 }, text = { Text(if (pc.isNotBlank()) pc else "Paired PC") })
            }

            Spacer(Modifier.height(8.dp))
            PhoneControls()
        }
    }
}

// ==========================================
// PHONE CONTROLS
// ==========================================
@Composable
private fun PhoneControls() {
    val context = LocalContext.current
    val audio = context.getSystemService(AudioManager::class.java)
    val stream = AudioManager.STREAM_MUSIC
    val maximum = audio?.getStreamMaxVolume(stream)?.coerceAtLeast(1) ?: 1
    var volume by remember { mutableFloatStateOf((audio?.getStreamVolume(stream) ?: 0).toFloat()) }
    var previousVolume by remember { mutableIntStateOf((audio?.getStreamVolume(stream) ?: 1).coerceAtLeast(1)) }
    var draggingVolume by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf("") }
    var system by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(Settings.System.canWrite(context)) }
    val window = (context as? Activity)?.window
    var brightness by remember {
        mutableFloatStateOf(window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: .5f)
    }
    fun readBrightness() = (Settings.System.getInt(
        context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128
    ) / 255f).coerceIn(.05f, 1f)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        allowed = Settings.System.canWrite(context)
    }
    fun setVolume(value: Int) {
        try {
            audio?.setStreamVolume(stream, value, 0)
            volume = (audio?.getStreamVolume(stream) ?: 0).toFloat()
            if (volume > 0) previousVolume = volume.roundToInt()
            issue = ""
        } catch (_: SecurityException) { issue = "Android blocked the volume change." }
    }
    LaunchedEffect(audio) {
        while (true) {
            if (!draggingVolume && audio != null) {
                volume = audio.getStreamVolume(stream).toFloat()
                if (volume > 0) previousVolume = volume.roundToInt()
            }
            delay(1000)
        }
    }
    DisposableEffect(window) {
        val prior = window?.attributes?.screenBrightness
        onDispose {
            if (prior != null) window.attributes = window.attributes.apply { screenBrightness = prior }
        }
    }
    CompanionPanel {
        Text("Phone Media Volume", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("${(volume * 100 / maximum).roundToInt()}%", style = MaterialTheme.typography.headlineMedium)
        val enabled = audio != null && !audio.isVolumeFixed
        Slider(value = volume, onValueChange = { draggingVolume = true; volume = it },
            onValueChangeFinished = { setVolume(volume.roundToInt()); draggingVolume = false },
            valueRange = 0f..maximum.toFloat(), enabled = enabled)
        OutlinedButton(enabled = enabled, onClick = { setVolume(if (volume > 0) 0 else previousVolume) }) {
            Text(if (volume > 0) "Mute phone" else "Restore phone volume")
        }
        if (!enabled) Text("This device uses a fixed media volume.")
    }
    CompanionPanel {
        Text("Phone Brightness", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(if (system) "System-wide · ${(brightness * 100).roundToInt()}%"
        else "Inside Companion · ${(brightness * 100).roundToInt()}%")
        Slider(value = brightness, valueRange = .05f..1f,
            enabled = if (system) allowed else window != null,
            onValueChange = { level ->
                brightness = level
                if (!system && window != null)
                    window.attributes = window.attributes.apply { screenBrightness = level }
            },
            onValueChangeFinished = {
                if (system) {
                    allowed = Settings.System.canWrite(context)
                    val changed = allowed && runCatching {
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) &&
                                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS,
                                    (brightness * 255).roundToInt())
                    }.getOrDefault(false)
                    issue = if (changed) "" else "Android could not change system brightness."
                }
            })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !system, onClick = {
                system = false
                if (window != null) window.attributes = window.attributes.apply { screenBrightness = brightness }
            }, label = { Text("Companion") })
            FilterChip(selected = system, onClick = {
                system = true
                allowed = Settings.System.canWrite(context)
                brightness = readBrightness()
                if (window != null) window.attributes = window.attributes.apply { screenBrightness = -1f }
            }, label = { Text("System") })
        }
        if (system) Text("Adjusting switches Android to manual brightness.")
        if (system && !allowed) OutlinedButton(onClick = {
            try {
                permission.launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            } catch (_: android.content.ActivityNotFoundException) { issue = "Open display settings to adjust brightness." }
        }) { Text("Allow system brightness control") }
    }
    if (issue.isNotBlank()) Text(issue, color = MaterialTheme.colorScheme.error)
}

// ==========================================
// PC REMOTE CONTROL HUB
// ==========================================
private data class ControlCommand(
    val label: String,
    val action: String,
    val params: JSONObject = JSONObject(),
    val isDestructive: Boolean = false,
    val confirmPrompt: String = ""
)

@Composable
private fun PcRemoteControlHub(
    api: CompanionApi, pc: String, connected: Boolean, remote: Boolean, health: JSONObject?,
    onOpenMonitor: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableIntStateOf(0) }
    val sectionLabels = listOf("Power", "Audio & Media", "Display", "Apps", "Input", "Routines", "Vitals")

    var state by remember { mutableStateOf<JSONObject?>(null) }
    var capabilities by remember { mutableStateOf<JSONObject?>(null) }
    var message by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var pendingConfirm by remember { mutableStateOf<ControlCommand?>(null) }
    var refreshCounter by remember { mutableIntStateOf(0) }

    // Fetch live state & capabilities periodically
    LaunchedEffect(api, connected, refreshCounter) {
        if (connected) {
            while (true) {
                try {
                    val s = withContext(Dispatchers.IO) { api.call("controls/state") }
                    state = s
                    if (capabilities == null) {
                        capabilities = withContext(Dispatchers.IO) { api.call("controls/capabilities") }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    // Fallback to legacy device/controls if controls/state not available
                    try {
                        state = withContext(Dispatchers.IO) { api.call("device/controls") }
                    } catch (_: Exception) {}
                }
                delay(4000)
            }
        }
    }

    fun dispatch(cmd: ControlCommand) {
        if (isBusy || !connected) return
        isBusy = true
        message = ""
        scope.launch {
            try {
                val body = JSONObject().put("action", cmd.action).put("parameters", cmd.params)
                val reply = withContext(Dispatchers.IO) {
                    api.call("controls/commands", body)
                }
                message = reply.optString("message", "Command executed successfully.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Try fallback to device/controls
                try {
                    val fallbackBody = JSONObject().put("action", cmd.action)
                    val it = cmd.params.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        fallbackBody.put(k, cmd.params.get(k))
                    }
                    val reply = withContext(Dispatchers.IO) { api.call("device/controls", fallbackBody) }
                    message = reply.optString("message", "Action sent.")
                } catch (_: Exception) {
                    message = (failure as? ApiFailure)?.message ?: "Action failed. Check PC connectivity."
                }
            } finally {
                isBusy = false
                refreshCounter++
            }
        }
    }

    // Scrollable top sub-tabs
    ScrollableTabRow(
        selectedTabIndex = section,
        edgePadding = 0.dp,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary
    ) {
        sectionLabels.forEachIndexed { idx, label ->
            Tab(selected = section == idx, onClick = { section = idx }, text = { Text(label, fontSize = 13.sp) })
        }
    }

    Spacer(Modifier.height(8.dp))

    if (message.isNotBlank()) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                TextButton(onClick = { message = "" }) { Text("Dismiss", fontSize = 11.sp) }
            }
        }
        Spacer(Modifier.height(6.dp))
    }

    // Section Content
    when (section) {
        0 -> PowerSection(api, pc, state, capabilities, connected, isBusy, onCommand = {
            if (it.isDestructive) pendingConfirm = it else dispatch(it)
        })
        1 -> AudioMediaSection(api, state, connected, isBusy, onCommand = { dispatch(it) })
        2 -> DisplaySection(api, state, connected, isBusy, onCommand = { dispatch(it) })
        3 -> ApplicationsSection(api, connected, isBusy, onCommand = {
            if (it.isDestructive) pendingConfirm = it else dispatch(it)
        })
        4 -> RemoteInputSection(api, connected, isBusy, onCommand = { dispatch(it) })
        5 -> RoutinesSection(api, connected, isBusy, onCommand = { dispatch(it) })
        6 -> VitalsSection(api, pc, state, health, connected, onOpenMonitor)
    }

    // Destructive Confirmation Dialog
    pendingConfirm?.let { cmd ->
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            title = { Text(cmd.label, fontWeight = FontWeight.Bold) },
            text = { Text(cmd.confirmPrompt.ifEmpty { "Are you sure you want to execute ${cmd.label} on $pc? Save unsaved work first." }) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val toRun = cmd
                        pendingConfirm = null
                        dispatch(toRun)
                    }
                ) {
                    Text(cmd.label)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirm = null }) { Text("Cancel") }
            }
        )
    }
}

// ==========================================
// 1. POWER & SESSION SECTION
// ==========================================
@Composable
private fun PowerSection(
    api: CompanionApi, pc: String, state: JSONObject?, capabilities: JSONObject?,
    connected: Boolean, busy: Boolean, onCommand: (ControlCommand) -> Unit
) {
    val context = LocalContext.current
    var minutes by rememberSaveable { mutableIntStateOf(30) }
    var stayAwakeMinutes by rememberSaveable { mutableIntStateOf(60) }
    var schedules by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()

    val uptime = state?.optInt("uptime", 0) ?: 0
    val uptimeStr = if (uptime > 0) {
        val hrs = uptime / 3600
        val mins = (uptime % 3600) / 60
        "${hrs}h ${mins}m"
    } else "Active"

    val battery = state?.optJSONObject("battery")
    val stayAwake = state?.optJSONObject("stay_awake")
    val isStayAwake = stayAwake?.optBoolean("active") == true
    val scheduled = state?.optJSONObject("scheduled")
    val due = scheduled?.optLong("due") ?: 0L

    // Fetch schedules list
    fun refreshSchedules() {
        scope.launch {
            try {
                val s = withContext(Dispatchers.IO) { api.call("controls/schedules") }
                schedules = s
            } catch (_: Exception) {}
        }
    }
    LaunchedEffect(Unit) { refreshSchedules() }

    CompanionPanel {
        Text("Power & Session Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("PC Uptime: $uptimeStr", style = MaterialTheme.typography.bodyMedium)
            if (battery != null) {
                val pct = battery.optInt("percent", 100)
                val plugged = battery.optBoolean("pluggedIn", true)
                Text("Battery: $pct% (${if (plugged) "Plugged in" else "On battery"})", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("Power: AC Desktop", style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (isStayAwake) {
            val remain = stayAwake?.optInt("remaining_seconds", 0) ?: 0
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Stay-Awake active (${remain / 60}m remaining)", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onCommand(ControlCommand("Cancel Stay-Awake", "power.cancel_stay_awake")) }) {
                        Text("Disable")
                    }
                }
            }
        }
    }

    CompanionPanel {
        Text("Instant Actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = connected && !busy,
                onClick = {
                    onCommand(ControlCommand("Lock Workstation", "power.lock",
                        isDestructive = true, confirmPrompt = "Lock the active Windows desktop on $pc?"))
                }
            ) { Text("Lock PC") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = connected && !busy,
                onClick = { onCommand(ControlCommand("Display Off", "power.display_off")) }
            ) { Text("Turn Display Off") }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = connected && !busy,
                onClick = {
                    onCommand(ControlCommand("Sleep PC", "power.sleep",
                        isDestructive = true, confirmPrompt = "Put $pc to sleep? Companion will lose connection until the PC wakes."))
                }
            ) { Text("Sleep") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = connected && !busy && (capabilities?.optBoolean("canHibernate", true) != false),
                onClick = {
                    onCommand(ControlCommand("Hibernate PC", "power.hibernate",
                        isDestructive = true, confirmPrompt = "Hibernate Windows on $pc? Current session state will be preserved to disk."))
                }
            ) { Text("Hibernate") }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = connected && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                onClick = {
                    onCommand(ControlCommand("Restart PC", "power.restart",
                        isDestructive = true, confirmPrompt = "Restart Windows on $pc? Make sure all unsaved documents are saved."))
                }
            ) { Text("Restart") }

            Button(
                modifier = Modifier.weight(1f),
                enabled = connected && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                onClick = {
                    onCommand(ControlCommand("Shut Down PC", "power.shutdown",
                        isDestructive = true, confirmPrompt = "Shut down $pc completely? Oracle will disconnect."))
                }
            ) { Text("Shut Down") }
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = connected && !busy,
            onClick = {
                onCommand(ControlCommand("Sign Out", "power.sign_out",
                    isDestructive = true, confirmPrompt = "Sign out active user from Windows on $pc?"))
            }
        ) { Text("Sign Out Active User") }
    }

    CompanionPanel {
        Text("Prevent Sleep / Stay Awake", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Keep system and display awake for: ${stayAwakeMinutes} min")
        Slider(
            value = stayAwakeMinutes.toFloat(),
            onValueChange = { stayAwakeMinutes = it.roundToInt() },
            valueRange = 15f..240f,
            steps = 14,
            enabled = connected && !busy
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = connected && !busy,
                onClick = {
                    onCommand(ControlCommand("Enable Stay Awake", "power.stay_awake",
                        JSONObject().put("minutes", stayAwakeMinutes)))
                }
            ) { Text("Activate Stay Awake") }
            if (isStayAwake) {
                OutlinedButton(
                    enabled = connected && !busy,
                    onClick = { onCommand(ControlCommand("Cancel Stay Awake", "power.cancel_stay_awake")) }
                ) { Text("Cancel") }
            }
        }
    }

    CompanionPanel {
        Text("Schedule Power Action", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        var actionToSchedule by remember { mutableStateOf("shutdown") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("shutdown" to "Shutdown", "restart" to "Restart", "sleep" to "Sleep").forEach { (act, label) ->
                FilterChip(selected = actionToSchedule == act, onClick = { actionToSchedule = act }, label = { Text(label) })
            }
        }
        Text("Countdown delay: $minutes minutes")
        Slider(
            value = minutes.toFloat(),
            onValueChange = { minutes = it.roundToInt() },
            valueRange = 5f..180f,
            enabled = connected && !busy
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = connected && !busy,
                onClick = {
                    onCommand(ControlCommand("Schedule $actionToSchedule", "power.schedule",
                        JSONObject().put("action", actionToSchedule).put("mode", "countdown").put("minutes", minutes),
                        isDestructive = true, confirmPrompt = "Schedule $actionToSchedule in $minutes minutes on $pc?"))
                    refreshSchedules()
                }
            ) { Text("Schedule ($minutes m)") }

            OutlinedButton(
                enabled = connected && !busy,
                onClick = {
                    val date = java.util.Calendar.getInstance()
                    android.app.DatePickerDialog(context, { _, yr, mon, day ->
                        date.set(yr, mon, day)
                        android.app.TimePickerDialog(context, { _, hr, min ->
                            date.set(java.util.Calendar.HOUR_OF_DAY, hr)
                            date.set(java.util.Calendar.MINUTE, min)
                            date.set(java.util.Calendar.SECOND, 0)
                            val targetEpoch = date.timeInMillis / 1000
                            onCommand(ControlCommand("Schedule $actionToSchedule", "power.schedule",
                                JSONObject().put("action", actionToSchedule).put("mode", "exact_time").put("due", targetEpoch),
                                isDestructive = true, confirmPrompt = "Schedule $actionToSchedule at ${date.time} on $pc?"))
                            refreshSchedules()
                        }, date.get(java.util.Calendar.HOUR_OF_DAY), date.get(java.util.Calendar.MINUTE),
                            android.text.format.DateFormat.is24HourFormat(context)).show()
                    }, date.get(java.util.Calendar.YEAR), date.get(java.util.Calendar.MONTH), date.get(java.util.Calendar.DAY_OF_MONTH)).show()
                }
            ) { Text("Pick Date & Time") }
        }

        // Active schedules list
        val activeList = schedules?.optJSONArray("active")
        if (activeList != null && activeList.length() > 0) {
            Spacer(Modifier.height(8.dp))
            Text("Pending Schedules", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            for (i in 0 until activeList.length()) {
                val sc = activeList.getJSONObject(i)
                val sId = sc.optString("id")
                val sAct = sc.optString("action")
                val sDue = sc.optLong("due") * 1000
                val remainingMins = maxOf(0, ((sDue - System.currentTimeMillis()) / 60000).toInt())
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("${sAct.replaceFirstChar { it.uppercase() }} in $remainingMins min", fontWeight = FontWeight.SemiBold)
                            Text("Due: ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sDue))}", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        api.call("controls/commands", JSONObject().put("action", "schedule.cancel").put("parameters", JSONObject().put("id", sId)))
                                    }
                                    refreshSchedules()
                                } catch (_: Exception) {}
                            }
                        }) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 2. AUDIO & MEDIA SECTION
// ==========================================
@Composable
private fun AudioMediaSection(
    api: CompanionApi, state: JSONObject?, connected: Boolean, busy: Boolean,
    onCommand: (ControlCommand) -> Unit
) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<JSONArray?>(null) }
    var sessions by remember { mutableStateOf<JSONArray?>(null) }

    val volumeObj = state?.optJSONObject("volume")
    val currentVol = volumeObj?.optInt("percent", 50) ?: 50
    val isMuted = volumeObj?.optBoolean("muted", false) == true

    val micObj = state?.optJSONObject("microphone")
    val micMuted = micObj?.optBoolean("muted", false) == true
    val mediaObj = state?.optJSONObject("media")

    var localVol by remember(currentVol) { mutableFloatStateOf(currentVol.toFloat()) }

    fun refreshAudioData() {
        scope.launch {
            try {
                val d = withContext(Dispatchers.IO) { api.call("controls/audio/devices") }
                devices = d.optJSONArray("devices")
                val s = withContext(Dispatchers.IO) { api.call("controls/audio/sessions") }
                sessions = s.optJSONArray("sessions")
            } catch (_: Exception) {}
        }
    }
    LaunchedEffect(Unit) { refreshAudioData() }

    // Master Volume
    CompanionPanel {
        Text("PC Master Volume", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${localVol.roundToInt()}%" + if (isMuted) " · MUTED" else "",
                style = MaterialTheme.typography.headlineMedium,
                color = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(enabled = connected && !busy, onClick = {
                    onCommand(ControlCommand("Volume Down", "audio.volume_down"))
                }) { Text("−") }
                OutlinedButton(enabled = connected && !busy, onClick = {
                    onCommand(ControlCommand("Volume Up", "audio.volume_up"))
                }) { Text("+") }
                Button(
                    enabled = connected && !busy,
                    colors = if (isMuted) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    else ButtonDefaults.buttonColors(),
                    onClick = {
                        onCommand(ControlCommand("Toggle Mute", "audio.set_mute", JSONObject().put("muted", !isMuted)))
                    }
                ) { Text(if (isMuted) "Unmute" else "Mute") }
            }
        }
        Slider(
            value = localVol,
            onValueChange = { localVol = it },
            valueRange = 0f..100f,
            enabled = connected && !busy,
            onValueChangeFinished = {
                onCommand(ControlCommand("Set Master Volume", "audio.set_volume", JSONObject().put("percent", localVol.roundToInt())))
            }
        )

        // Microphone Control
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Microphone: ${if (micMuted) "Muted" else "Active"}", style = MaterialTheme.typography.bodyMedium)
            Button(
                enabled = connected && !busy,
                onClick = {
                    onCommand(ControlCommand("Toggle Mic Mute", "audio.set_mic_mute", JSONObject().put("muted", !micMuted)))
                }
            ) { Text(if (micMuted) "Unmute Mic" else "Mute Mic") }
        }
    }

    // Audio Presets
    CompanionPanel {
        Text("Audio Presets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(modifier = Modifier.weight(1f), onClick = {
                onCommand(ControlCommand("Study Preset", "audio.set_volume", JSONObject().put("percent", 25)))
            }) { Text("Study 25%") }
            Button(modifier = Modifier.weight(1f), onClick = {
                onCommand(ControlCommand("Music Preset", "audio.set_volume", JSONObject().put("percent", 70)))
            }) { Text("Music 70%") }
            Button(modifier = Modifier.weight(1f), onClick = {
                onCommand(ControlCommand("Night Preset", "audio.set_volume", JSONObject().put("percent", 10)))
            }) { Text("Night 10%") }
        }
    }

    // Now Playing Media Player
    CompanionPanel {
        Text("Now Playing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        val title = mediaObj?.optString("title", "").orEmpty()
        val artist = mediaObj?.optString("artist", "").orEmpty()
        val album = mediaObj?.optString("album", "").orEmpty()
        val mediaStatus = mediaObj?.optString("status", "Stopped") ?: "Stopped"

        if (title.isNotBlank() || artist.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title.ifEmpty { "Unknown Track" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (artist.isNotBlank()) Text(artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (album.isNotBlank()) Text(album, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Status: $mediaStatus", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        } else {
            Text("No active media playing on Windows.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            OutlinedButton(enabled = connected && !busy, onClick = { onCommand(ControlCommand("Previous", "media.prev")) }) {
                Text("⏮ Prev")
            }
            Button(enabled = connected && !busy, onClick = { onCommand(ControlCommand("Play/Pause", "media.play_pause")) }) {
                Text(if (mediaStatus == "Playing") "⏸ Pause" else "▶ Play")
            }
            OutlinedButton(enabled = connected && !busy, onClick = { onCommand(ControlCommand("Next", "media.next")) }) {
                Text("⏭ Next")
            }
            OutlinedButton(enabled = connected && !busy, onClick = { onCommand(ControlCommand("Stop", "media.stop")) }) {
                Text("⏹ Stop")
            }
        }
    }

    // Per-Application Volume Mixer
    val sList = sessions
    if (sList != null && sList.length() > 0) {
        CompanionPanel {
            Text("Application Volume Mixer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            for (i in 0 until sList.length()) {
                val app = sList.getJSONObject(i)
                val pid = app.optInt("pid")
                val name = app.optString("name", "App")
                val appVol = app.optInt("percent", 100)
                val appMuted = app.optBoolean("muted", false)
                var localAppVol by remember(appVol) { mutableFloatStateOf(appVol.toFloat()) }

                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(name, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${localAppVol.roundToInt()}%" + if (appMuted) " (Muted)" else "")
                            TextButton(onClick = {
                                onCommand(ControlCommand("Mute $name", "audio.set_app_volume",
                                    JSONObject().put("pid", pid).put("percent", localAppVol.roundToInt()).put("muted", !appMuted)))
                                refreshAudioData()
                            }) {
                                Text(if (appMuted) "Unmute" else "Mute", fontSize = 11.sp)
                            }
                        }
                    }
                    Slider(
                        value = localAppVol,
                        onValueChange = { localAppVol = it },
                        valueRange = 0f..100f,
                        onValueChangeFinished = {
                            onCommand(ControlCommand("Set $name Volume", "audio.set_app_volume",
                                JSONObject().put("pid", pid).put("percent", localAppVol.roundToInt())))
                        }
                    )
                }
            }
        }
    }
}

// ==========================================
// 3. DISPLAY SECTION
// ==========================================
@Composable
private fun DisplaySection(
    api: CompanionApi, state: JSONObject?, connected: Boolean, busy: Boolean,
    onCommand: (ControlCommand) -> Unit
) {
    val scope = rememberCoroutineScope()
    var displayData by remember { mutableStateOf<JSONObject?>(null) }
    val brightnessVal = state?.optInt("brightness", -1) ?: -1
    var localBrightness by remember(brightnessVal) { mutableFloatStateOf(if (brightnessVal in 0..100) brightnessVal.toFloat() else 50f) }

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                val d = withContext(Dispatchers.IO) { api.call("controls/displays") }
                displayData = d
            } catch (_: Exception) {}
        }
    }

    CompanionPanel {
        Text("Display Brightness", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (brightnessVal >= 0) {
            Text("${localBrightness.roundToInt()}%", style = MaterialTheme.typography.headlineMedium)
            Slider(
                value = localBrightness,
                onValueChange = { localBrightness = it },
                valueRange = 5f..100f,
                enabled = connected && !busy,
                onValueChangeFinished = {
                    onCommand(ControlCommand("Set Brightness", "display.set_brightness",
                        JSONObject().put("percent", localBrightness.roundToInt())))
                }
            )
        } else {
            Text("Internal display brightness control is unavailable on this monitor (external DDC/CI desktop display).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(4.dp))
        Button(
            enabled = connected && !busy,
            onClick = { onCommand(ControlCommand("Turn Display Off", "power.display_off")) }
        ) {
            Text("Turn Display Off (Low Power)")
        }
    }

    CompanionPanel {
        Text("Display Arrangement & Topology", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Select multi-monitor display mode:", style = MaterialTheme.typography.bodySmall)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(modifier = Modifier.weight(1f), onClick = {
                onCommand(ControlCommand("Extend Displays", "display.set_topology", JSONObject().put("topology", "extend")))
            }) { Text("Extend") }
            Button(modifier = Modifier.weight(1f), onClick = {
                onCommand(ControlCommand("Duplicate Displays", "display.set_topology", JSONObject().put("topology", "clone")))
            }) { Text("Duplicate") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                onCommand(ControlCommand("PC Screen Only", "display.set_topology", JSONObject().put("topology", "internal")))
            }) { Text("PC Screen Only") }
            OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                onCommand(ControlCommand("Second Screen Only", "display.set_topology", JSONObject().put("topology", "external")))
            }) { Text("Second Screen Only") }
        }

        val monitors = displayData?.optJSONArray("monitors")
        if (monitors != null && monitors.length() > 0) {
            Spacer(Modifier.height(8.dp))
            Text("Connected Monitors", fontWeight = FontWeight.SemiBold)
            for (i in 0 until monitors.length()) {
                val m = monitors.getJSONObject(i)
                val dev = m.optString("device", "Display")
                val w = m.optInt("width", 0)
                val h = m.optInt("height", 0)
                val pri = m.optBoolean("primary", false)
                Text("• $dev: ${w}x${h} ${if (pri) "(Primary)" else ""}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ==========================================
// 4. APPLICATIONS SECTION
// ==========================================
@Composable
private fun ApplicationsSection(
    api: CompanionApi, connected: Boolean, busy: Boolean,
    onCommand: (ControlCommand) -> Unit
) {
    val scope = rememberCoroutineScope()
    var appList by remember { mutableStateOf<JSONArray?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var tabIdx by remember { mutableIntStateOf(0) } // 0: Running Apps, 1: App Launcher

    fun refreshApps() {
        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) { api.call("controls/apps/running") }
                appList = res.optJSONArray("apps")
            } catch (_: Exception) {}
        }
    }
    LaunchedEffect(Unit) { refreshApps() }

    TabRow(selectedTabIndex = tabIdx, containerColor = Color.Transparent) {
        Tab(selected = tabIdx == 0, onClick = { tabIdx = 0 }, text = { Text("Running Windows") })
        Tab(selected = tabIdx == 1, onClick = { tabIdx = 1 }, text = { Text("App Launcher") })
    }

    Spacer(Modifier.height(8.dp))

    if (tabIdx == 0) {
        CompanionPanel {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Active Windows", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = { refreshApps() }) { Text("Refresh") }
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search running apps") },
                modifier = Modifier.fillMaxWidth()
            )

            val apps = appList
            if (apps != null && apps.length() > 0) {
                for (i in 0 until apps.length()) {
                    val app = apps.getJSONObject(i)
                    val title = app.optString("title")
                    val name = app.optString("name")
                    val pid = app.optInt("pid")
                    val hwnd = app.optLong("hwnd")
                    val mem = app.optDouble("memory_mb", 0.0)
                    val active = app.optBoolean("active")

                    if (searchQuery.isBlank() || title.contains(searchQuery, ignoreCase = true) || name.contains(searchQuery, ignoreCase = true)) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(name, fontWeight = FontWeight.Bold)
                                    Text("${mem} MB", style = MaterialTheme.typography.labelSmall)
                                }
                                Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 1)

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(2.dp),
                                        onClick = {
                                            onCommand(ControlCommand("Focus $name", "app.window_state",
                                                JSONObject().put("hwnd", hwnd).put("state", "focus")))
                                        }
                                    ) { Text("Focus", fontSize = 11.sp) }

                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(2.dp),
                                        onClick = {
                                            onCommand(ControlCommand("Minimize $name", "app.window_state",
                                                JSONObject().put("hwnd", hwnd).put("state", "minimize")))
                                        }
                                    ) { Text("Min", fontSize = 11.sp) }

                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(2.dp),
                                        onClick = {
                                            onCommand(ControlCommand("Maximize $name", "app.window_state",
                                                JSONObject().put("hwnd", hwnd).put("state", "maximize")))
                                        }
                                    ) { Text("Max", fontSize = 11.sp) }

                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(2.dp),
                                        onClick = {
                                            onCommand(ControlCommand("Close $name", "app.window_state",
                                                JSONObject().put("hwnd", hwnd).put("state", "close")))
                                            refreshApps()
                                        }
                                    ) { Text("Close", fontSize = 11.sp) }

                                    Button(
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(2.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        onClick = {
                                            onCommand(ControlCommand("Force Close $name", "app.terminate",
                                                JSONObject().put("pid", pid).put("force", true),
                                                isDestructive = true, confirmPrompt = "Force terminate $name (PID $pid)? Any unsaved work in this process will be lost!"))
                                            refreshApps()
                                        }
                                    ) { Text("Kill", fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                }
            } else {
                Text("No open applications detected or loading...", style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        CompanionPanel {
            Text("Approved Applications Allowlist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Launch explicitly approved apps directly on your PC:", style = MaterialTheme.typography.bodySmall)

            val catalog = listOf(
                "vscode" to "Visual Studio Code",
                "chrome" to "Google Chrome",
                "msedge" to "Microsoft Edge",
                "spotify" to "Spotify",
                "calc" to "Calculator",
                "explorer" to "File Explorer"
            )

            catalog.forEach { (appId, appName) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(appName, fontWeight = FontWeight.SemiBold)
                    Button(
                        enabled = connected && !busy,
                        onClick = {
                            onCommand(ControlCommand("Launch $appName", "app.launch", JSONObject().put("app_id", appId)))
                        }
                    ) { Text("Launch") }
                }
            }
        }
    }
}

// ==========================================
// 5. REMOTE INPUT SECTION
// ==========================================
@Composable
private fun RemoteInputSection(
    api: CompanionApi, connected: Boolean, busy: Boolean,
    onCommand: (ControlCommand) -> Unit
) {
    var inputTab by remember { mutableIntStateOf(0) } // 0: Trackpad, 1: Keyboard, 2: Presentation, 3: Clipboard
    val scope = rememberCoroutineScope()
    var sensitivity by remember { mutableFloatStateOf(1.5f) }
    var keyboardText by remember { mutableStateOf("") }
    var pcClipboardText by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    TabRow(selectedTabIndex = inputTab, containerColor = Color.Transparent) {
        Tab(selected = inputTab == 0, onClick = { inputTab = 0 }, text = { Text("Trackpad") })
        Tab(selected = inputTab == 1, onClick = { inputTab = 1 }, text = { Text("Keyboard") })
        Tab(selected = inputTab == 2, onClick = { inputTab = 2 }, text = { Text("Presentation") })
        Tab(selected = inputTab == 3, onClick = { inputTab = 3 }, text = { Text("Clipboard") })
    }

    Spacer(Modifier.height(8.dp))

    when (inputTab) {
        0 -> {
            CompanionPanel {
                Text("Touch Trackpad Surface", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Drag finger to move mouse pointer. Tap for Left Click, two-finger tap for Right Click.", style = MaterialTheme.typography.bodySmall)

                // Trackpad Touch Surface
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    scope.launch {
                                        try {
                                            api.call("controls/input", JSONObject().put("type", "click").put("button", "left"))
                                        } catch (_: Exception) {}
                                    }
                                },
                                onDoubleTap = {
                                    scope.launch {
                                        try {
                                            api.call("controls/input", JSONObject().put("type", "double_click"))
                                        } catch (_: Exception) {}
                                    }
                                },
                                onLongPress = {
                                    scope.launch {
                                        try {
                                            api.call("controls/input", JSONObject().put("type", "click").put("button", "right"))
                                        } catch (_: Exception) {}
                                    }
                                }
                            )
                        }
                        .pointerInput(sensitivity) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val dx = (dragAmount.x * sensitivity).roundToInt()
                                val dy = (dragAmount.y * sensitivity).roundToInt()
                                scope.launch {
                                    try {
                                        api.call("controls/input", JSONObject().put("type", "move").put("dx", dx).put("dy", dy))
                                    } catch (_: Exception) {}
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("Touch & Drag Pointer Here", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }

                // Dedicated Left & Right Click buttons
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.weight(1f).height(48.dp),
                        onClick = {
                            scope.launch {
                                try { api.call("controls/input", JSONObject().put("type", "click").put("button", "left")) } catch (_: Exception) {}
                            }
                        }
                    ) { Text("Left Click") }

                    Button(
                        modifier = Modifier.weight(1f).height(48.dp),
                        onClick = {
                            scope.launch {
                                try { api.call("controls/input", JSONObject().put("type", "click").put("button", "right")) } catch (_: Exception) {}
                            }
                        }
                    ) { Text("Right Click") }
                }

                Text("Pointer Sensitivity: ${sensitivity}x")
                Slider(value = sensitivity, onValueChange = { sensitivity = it }, valueRange = 0.5f..3.5f)
            }
        }
        1 -> {
            CompanionPanel {
                Text("Remote Keyboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = keyboardText,
                    onValueChange = { keyboardText = it },
                    label = { Text("Type text to send to PC") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = keyboardText.isNotBlank(),
                    onClick = {
                        val txt = keyboardText
                        keyboardText = ""
                        scope.launch {
                            try {
                                api.call("controls/input", JSONObject().put("type", "text").put("text", txt))
                            } catch (_: Exception) {}
                        }
                    }
                ) { Text("Send Text to PC") }

                Spacer(Modifier.height(6.dp))
                Text("Special Keys", fontWeight = FontWeight.SemiBold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Enter", "Backspace", "Tab", "Esc").forEach { k ->
                        OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                            scope.launch {
                                try { api.call("controls/input", JSONObject().put("type", "key").put("key", k)) } catch (_: Exception) {}
                            }
                        }) { Text(k, fontSize = 11.sp) }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Left" to "←", "Up" to "↑", "Down" to "↓", "Right" to "→").forEach { (k, sym) ->
                        OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                            scope.launch {
                                try { api.call("controls/input", JSONObject().put("type", "key").put("key", k)) } catch (_: Exception) {}
                            }
                        }) { Text(sym, fontSize = 14.sp) }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text("Shortcuts", fontWeight = FontWeight.SemiBold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ctrl_c" to "Copy", "ctrl_v" to "Paste", "alt_tab" to "Alt+Tab", "win_d" to "Desktop").forEach { (sc, lbl) ->
                        Button(modifier = Modifier.weight(1f), onClick = {
                            scope.launch {
                                try { api.call("controls/input", JSONObject().put("type", "shortcut").put("key", sc)) } catch (_: Exception) {}
                            }
                        }) { Text(lbl, fontSize = 11.sp) }
                    }
                }
            }
        }
        2 -> {
            CompanionPanel {
                Text("Presentation Controller", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Use during slideshows (PowerPoint, Google Slides, Keynote, PDF):", style = MaterialTheme.typography.bodySmall)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.weight(1f).height(56.dp),
                        onClick = {
                            scope.launch {
                                try { api.call("controls/input", JSONObject().put("type", "shortcut").put("key", "presentation_start")) } catch (_: Exception) {}
                            }
                        }
                    ) { Text("Start Slideshow (F5)") }

                    OutlinedButton(
                        modifier = Modifier.weight(1f).height(56.dp),
                        onClick = {
                            scope.launch {
                                try { api.call("controls/input", JSONObject().put("type", "shortcut").put("key", "presentation_exit")) } catch (_: Exception) {}
                            }
                        }
                    ) { Text("Exit Slideshow (Esc)") }
                }

                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        modifier = Modifier.weight(1f).height(72.dp),
                        onClick = {
                            scope.launch {
                                try { api.call("controls/input", JSONObject().put("type", "key").put("key", "page_up")) } catch (_: Exception) {}
                            }
                        }
                    ) { Text("◀ Previous Slide", fontSize = 16.sp) }

                    Button(
                        modifier = Modifier.weight(1f).height(72.dp),
                        onClick = {
                            scope.launch {
                                try { api.call("controls/input", JSONObject().put("type", "key").put("key", "page_down")) } catch (_: Exception) {}
                            }
                        }
                    ) { Text("Next Slide ▶", fontSize = 16.sp) }
                }
            }
        }
        3 -> {
            CompanionPanel {
                Text("Shared PC Clipboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Button(onClick = {
                    scope.launch {
                        try {
                            val res = withContext(Dispatchers.IO) { api.call("controls/clipboard") }
                            pcClipboardText = res.optString("text", "")
                        } catch (_: Exception) {}
                    }
                }) { Text("Read PC Clipboard") }

                if (pcClipboardText.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(pcClipboardText, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = {
                                clipboardManager.setText(AnnotatedString(pcClipboardText))
                            }) { Text("Copy to Phone Clipboard") }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                var sendText by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = sendText,
                    onValueChange = { sendText = it },
                    label = { Text("Send Text to PC Clipboard") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = sendText.isNotBlank(),
                        onClick = {
                            val t = sendText
                            sendText = ""
                            scope.launch {
                                try {
                                    api.call("controls/clipboard", JSONObject().put("text", t))
                                } catch (_: Exception) {}
                            }
                        }
                    ) { Text("Paste to PC") }

                    OutlinedButton(onClick = {
                        scope.launch {
                            try { api.call("controls/commands", JSONObject().put("action", "clipboard.clear")) } catch (_: Exception) {}
                        }
                    }) { Text("Clear PC Clipboard") }
                }
            }
        }
    }
}

// ==========================================
// 6. ROUTINES & AUTOMATIONS SECTION
// ==========================================
@Composable
private fun RoutinesSection(
    api: CompanionApi, connected: Boolean, busy: Boolean,
    onCommand: (ControlCommand) -> Unit
) {
    val scope = rememberCoroutineScope()
    var routines by remember { mutableStateOf<JSONArray?>(null) }
    var history by remember { mutableStateOf<JSONArray?>(null) }
    var runningRoutineId by remember { mutableStateOf<String?>(null) }

    fun refreshRoutines() {
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { api.call("controls/routines") }
                routines = r.optJSONArray("routines")
                val h = withContext(Dispatchers.IO) { api.call("controls/routines/history") }
                history = h.optJSONArray("history")
            } catch (_: Exception) {}
        }
    }
    LaunchedEffect(Unit) { refreshRoutines() }

    CompanionPanel {
        Text("PC Automation Routines", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Multi-action workflows executed sequentially on Windows:", style = MaterialTheme.typography.bodySmall)

        val rList = routines
        if (rList != null && rList.length() > 0) {
            for (i in 0 until rList.length()) {
                val r = rList.getJSONObject(i)
                val rid = r.optString("id")
                val name = r.optString("name")
                val desc = r.optString("description")
                val isRunning = runningRoutineId == rid

                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(name, fontWeight = FontWeight.Bold)
                            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            enabled = connected && !busy && !isRunning,
                            onClick = {
                                runningRoutineId = rid
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { api.call("controls/routines/$rid/run") }
                                        refreshRoutines()
                                    } catch (_: Exception) {}
                                    finally { runningRoutineId = null }
                                }
                            }
                        ) {
                            Text(if (isRunning) "Running…" else "Run")
                        }
                    }
                }
            }
        }
    }

    val hList = history
    if (hList != null && hList.length() > 0) {
        CompanionPanel {
            Text("Recent Execution Logs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            for (i in 0 until minOf(hList.length(), 5)) {
                val h = hList.getJSONObject(i)
                val rName = h.optString("routine_name")
                val status = h.optString("status")
                val stamp = h.optLong("executed_at") * 1000
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("$rName · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(stamp))}", style = MaterialTheme.typography.bodySmall)
                    Text(status.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall,
                        color = if (status == "completed") Color(0xff10b981) else Color(0xfff59e0b))
                }
            }
        }
    }
}

// ==========================================
// 7. VITALS & DIAGNOSTICS SECTION
// ==========================================
@Composable
private fun VitalsSection(
    api: CompanionApi, pc: String, state: JSONObject?, health: JSONObject?, connected: Boolean,
    onOpenMonitor: (() -> Unit)? = null
) {
    CompanionPanel {
        Text("PC Hardware & Performance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        val cpu = health?.optDouble("cpuPercent")?.takeIf { it.isFinite() }
        val ram = health?.optDouble("ramPercent")?.takeIf { it.isFinite() }
        if (cpu != null) {
            Text("CPU Usage: ${cpu.roundToInt()}%")
            LinearProgressIndicator(progress = { (cpu / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        if (ram != null) {
            Text("RAM Usage: ${ram.roundToInt()}%")
            LinearProgressIndicator(progress = { (ram / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    }
    PcPerformance(api, connected, onOpenMonitor)
}

@Composable
private fun PcPerformance(api: CompanionApi, connected: Boolean, onOpenMonitor: (() -> Unit)? = null) {
    var sample by remember(api) { mutableStateOf<JSONObject?>(null) }
    var issue by remember(api) { mutableStateOf("") }
    LaunchedEffect(api, connected) {
        sample = null
        issue = ""
        if (connected) while (true) {
            try {
                sample = withContext(Dispatchers.IO) { api.call("device/performance") }
                issue = ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                sample = null
                issue = "Performance data temporarily unavailable."
                if (failure is ApiFailure && failure.status in listOf(401, 403, 404)) break
            }
            delay(5000)
        }
    }
    CompanionPanel {
        Text("Storage & Network Telemetry", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val current = sample
        when {
            !connected -> Text("Reconnect to see live metrics.")
            issue.isNotBlank() -> Text(issue)
            current == null -> Text("Loading metrics…")
            else -> {
                for ((key, label) in listOf("storage" to "System Drive")) {
                    val usage = current.optJSONObject(key)
                    val total = usage?.optLong("total") ?: 0
                    if (total > 0) {
                        val used = usage?.optLong("used") ?: 0
                        Text("$label · ${formatBytes(used)} / ${formatBytes(total)}")
                        LinearProgressIndicator(progress = { (used.toDouble() / total).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                val gpu = current.optInt("gpu3d", -1)
                Text(if (gpu >= 0) "GPU 3D Engine: $gpu%" else "GPU 3D Engine: Idle / counters unavailable")
                val network = current.optJSONObject("network")
                Text(if (network != null) "Network: ↓ ${formatBytes(network.optLong("down"))}/s · ↑ ${formatBytes(network.optLong("up"))}/s"
                else "Network: Monitoring active connection")

                if (onOpenMonitor != null) {
                    OutlinedButton(
                        onClick = onOpenMonitor,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Text("Open Full System Intelligence & Monitoring ›")
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val unit = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var amount = bytes.toDouble()
    var index = 0
    while (amount >= 1024 && index < unit.lastIndex) {
        amount /= 1024
        index++
    }
    return "%.1f %s".format(amount, unit[index])
}
