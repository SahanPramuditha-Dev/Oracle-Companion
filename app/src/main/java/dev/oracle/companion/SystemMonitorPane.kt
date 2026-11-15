package dev.oracle.companion

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class MonitorSubTab(val title: String) {
    OVERVIEW("Overview"),
    CPU("CPU"),
    MEMORY("Memory"),
    GPU("GPU"),
    STORAGE("Storage"),
    NETWORK("Network"),
    THERMAL("Thermal"),
    PROCESSES("Processes"),
    SYSTEM("System"),
    AI("AI Engine"),
    SERVICES("Services"),
    HISTORY("History"),
    ALERTS("Alerts"),
    SETTINGS("Settings")
}

@Composable
internal fun SystemMonitorPane(
    api: CompanionApi,
    pc: String,
    connected: Boolean,
    remote: Boolean,
    onBack: () -> Unit
) {
    var subTab by rememberSaveable { mutableStateOf(MonitorSubTab.OVERVIEW) }
    var snapshot by remember(api) { mutableStateOf<JSONObject?>(null) }
    var hardware by remember(api) { mutableStateOf<JSONObject?>(null) }
    var issue by remember(api) { mutableStateOf("") }
    var paused by remember(api) { mutableStateOf(false) }
    var lastUpdated by remember(api) { mutableStateOf<Long?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }

    // Intercept back navigation: if inside a detail page, go back to OVERVIEW first!
    BackHandler {
        if (subTab != MonitorSubTab.OVERVIEW) {
            subTab = MonitorSubTab.OVERVIEW
        } else {
            onBack()
        }
    }

    // Live Telemetry polling & stream collector
    LaunchedEffect(api, connected, paused, retryCount) {
        if (!connected || paused) return@LaunchedEffect
        while (true) {
            try {
                val data = withContext(Dispatchers.IO) {
                    api.call("monitor/overview")
                }
                snapshot = data
                lastUpdated = System.currentTimeMillis()
                issue = ""
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                issue = (ex as? ApiFailure)?.message?.takeIf { it.isNotBlank() }
                    ?: "Could not refresh PC telemetry."
            }
            delay(1500)
        }
    }

    // Hardware metadata query (once at start)
    LaunchedEffect(api, connected) {
        if (!connected) return@LaunchedEffect
        try {
            val hw = withContext(Dispatchers.IO) { api.call("monitor/hardware") }
            hardware = hw
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Overview telemetry can still load without hardware metadata.
        }
    }

    val isDark = LocalDarkTheme.current
    val accentBlue = if (isDark) Color(0xff38bdf8) else Color(0xff0284c7)
    val accentGreen = if (isDark) Color(0xff10b981) else Color(0xff059669)
    val accentAmber = if (isDark) Color(0xfff59e0b) else Color(0xffea580c)
    val formattedUpdate = lastUpdated?.let {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))
    }
    val snapshotStatus = when {
        !connected -> if (snapshot == null) "Telemetry unavailable" else "Showing last reading"
        paused -> "Overview updates paused"
        issue.isNotEmpty() -> if (snapshot == null) "Telemetry unavailable" else "Showing last reading"
        snapshot == null -> "Waiting for telemetry"
        else -> "Overview updating"
    }
    val snapshotColor = when {
        !connected -> MaterialTheme.colorScheme.error
        paused || issue.isNotEmpty() || snapshot == null -> accentAmber
        else -> accentGreen
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Navigation Bar
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        if (subTab != MonitorSubTab.OVERVIEW) subTab = MonitorSubTab.OVERVIEW else onBack()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(if (subTab != MonitorSubTab.OVERVIEW) "‹ Overview" else "‹ Control")
                }
                Text(
                    if (subTab == MonitorSubTab.OVERVIEW) "System Monitor" else subTab.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, CardBorderBrush)
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusRadarDot(connected)
                    Text(
                        when {
                            !connected -> "Disconnected"
                            remote -> "Tailscale"
                            else -> "LAN"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (connected) accentGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Sub-tabs scrollable pill row
        ScrollableTabRow(
            selectedTabIndex = subTab.ordinal,
            edgePadding = 4.dp,
            divider = {},
            indicator = {},
            containerColor = Color.Transparent
        ) {
            MonitorSubTab.values().forEach { tabItem ->
                val selected = subTab == tabItem
                Surface(
                    onClick = { subTab = tabItem },
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, CardBorderBrush)
                ) {
                    Text(
                        tabItem.title,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, CardBorderBrush)
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(snapshotStatus, style = MaterialTheme.typography.labelLarge, color = snapshotColor)
                    if (formattedUpdate != null) {
                        Text(
                            "Last reading $formattedUpdate",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (connected && issue.isNotEmpty() && !paused && snapshot == null) {
                    TextButton(
                        onClick = { retryCount++ },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Retry") }
                } else if (connected && snapshot != null) {
                    TextButton(
                        onClick = { paused = !paused },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(if (paused) "Resume" else "Pause") }
                }
            }
        }
        if (connected && !paused && issue.isNotEmpty() && snapshot == null) {
            Text(
                issue,
                modifier = Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // Body Content based on selected subTab
        Crossfade(targetState = subTab, label = "MonitorCrossfade") { activeTab ->
            when (activeTab) {
                MonitorSubTab.OVERVIEW -> MonitorOverview(
                    api = api,
                    pc = pc,
                    snapshot = snapshot,
                    onNavigate = { subTab = it }
                )
                MonitorSubTab.CPU -> CpuDetailPane(api = api, snapshot = snapshot, hardware = hardware)
                MonitorSubTab.MEMORY -> MemoryDetailPane(api = api, snapshot = snapshot)
                MonitorSubTab.GPU -> GpuDetailPane(api = api, snapshot = snapshot, hardware = hardware)
                MonitorSubTab.STORAGE -> StorageDetailPane(api = api, snapshot = snapshot)
                MonitorSubTab.NETWORK -> NetworkDetailPane(api = api, snapshot = snapshot, remote = remote)
                MonitorSubTab.THERMAL -> ThermalDetailPane(api = api, snapshot = snapshot)
                MonitorSubTab.PROCESSES -> ProcessesPane(api = api)
                MonitorSubTab.SYSTEM -> SystemInfoPane(snapshot = snapshot, hardware = hardware)
                MonitorSubTab.AI -> AiEnginePane(api = api, snapshot = snapshot)
                MonitorSubTab.SERVICES -> ServicesHealthPane(snapshot = snapshot)
                MonitorSubTab.HISTORY -> PerformanceHistoryPane(api = api)
                MonitorSubTab.ALERTS -> AlertsPane(api = api, snapshot = snapshot)
                MonitorSubTab.SETTINGS -> MonitorSettingsPane(api = api)
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 1. OVERVIEW DASHBOARD
// ----------------------------------------------------------------------------
@Composable
private fun MonitorOverview(
    api: CompanionApi,
    pc: String,
    snapshot: JSONObject?,
    onNavigate: (MonitorSubTab) -> Unit
) {
    val isDark = LocalDarkTheme.current
    val accentBlue = if (isDark) Color(0xff38bdf8) else Color(0xff0284c7)
    val accentGreen = if (isDark) Color(0xff50e3c2) else Color(0xff059669)
    val accentAmber = if (isDark) Color(0xffff7a45) else Color(0xffea580c)
    val accentPurple = if (isDark) Color(0xffc084fc) else Color(0xff9333ea)
    val accentRed = if (isDark) Color(0xfff87171) else Color(0xffdc2626)

    val cpu = snapshot?.optJSONObject("cpu")
    val memory = snapshot?.optJSONObject("memory")
    val gpus = snapshot?.optJSONArray("gpus")
    val primaryGpu = if (gpus != null && gpus.length() > 0) gpus.optJSONObject(0) else null
    val storage = snapshot?.optJSONObject("storage")
    val volumes = storage?.optJSONArray("volumes")
    val primaryVol = if (volumes != null && volumes.length() > 0) volumes.optJSONObject(0) else null
    val network = snapshot?.optJSONObject("network")
    val battery = snapshot?.optJSONObject("battery")
    val ai = snapshot?.optJSONObject("ai")
    val alerts = snapshot?.optJSONArray("alerts")
    val healthSummaries = snapshot?.optJSONArray("healthSummary")

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Active Alerts Banner if present
        if (alerts != null && alerts.length() > 0) {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onNavigate(MonitorSubTab.ALERTS) },
                shape = RoundedCornerShape(16.dp),
                color = Color(0xff7f1d1d).copy(alpha = 0.35f),
                border = BorderStroke(1.dp, Color(0xffef4444).copy(alpha = 0.8f))
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.size(10.dp).background(Color(0xffef4444), CircleShape))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${alerts.length()} Active System Alert${if (alerts.length() > 1) "s" else ""}",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xfffca5a5),
                            style = MaterialTheme.typography.titleSmall
                        )
                        val topMsg = alerts.optJSONObject(0)?.optString("message", "") ?: ""
                        Text(topMsg, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("View ›", color = Color(0xfffca5a5), fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Health Summaries Pills Carousel
        if (healthSummaries != null && healthSummaries.length() > 0) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, CardBorderBrush)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("System Health Intelligence", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (i in 0 until min(healthSummaries.length(), 4)) {
                        val text = healthSummaries.optString(i)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).background(accentGreen, CircleShape))
                            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }

        // 4 Primary Metric Bento Cards (CPU, RAM, GPU, Network)
        Text("Primary Telemetry", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // CPU Card
            val cpuPct = cpu?.optDouble("usagePercent", 0.0) ?: 0.0
            val cpuFreq = cpu?.optInt("frequencyMhz", 0) ?: 0
            MetricCard(
                title = "CPU",
                value = "${cpuPct.roundToInt()}%",
                subtitle = if (cpuFreq > 0) "${(cpuFreq / 1000.0).format(1)} GHz" else "Active",
                accentColor = accentAmber,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(MonitorSubTab.CPU) }
            )

            // RAM Card
            val memTotal = memory?.optLong("totalBytes", 1L) ?: 1L
            val memUsed = memory?.optLong("usedBytes", 0L) ?: 0L
            val memPct = memory?.optDouble("usagePercent", 0.0) ?: 0.0
            MetricCard(
                title = "Memory",
                value = "${memPct.roundToInt()}%",
                subtitle = "${formatBytes(memUsed)} / ${formatBytes(memTotal)}",
                accentColor = accentBlue,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(MonitorSubTab.MEMORY) }
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // GPU Card
            val gpuPct = primaryGpu?.optDouble("usagePercent", 0.0) ?: 0.0
            val gpuTemp = primaryGpu?.optInt("temperatureC", -1) ?: -1
            val gpuName = primaryGpu?.optString("name", "GPU") ?: "GPU"
            MetricCard(
                title = "GPU",
                value = "${gpuPct.roundToInt()}%",
                subtitle = if (gpuTemp > 0) "$gpuTemp°C · ${gpuName.split(" ").lastOrNull().orEmpty()}" else "3D Engine",
                accentColor = accentGreen,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(MonitorSubTab.GPU) }
            )

            // Network Card
            val downRate = network?.optLong("downloadBytesPerSec", 0L) ?: 0L
            val upRate = network?.optLong("uploadBytesPerSec", 0L) ?: 0L
            MetricCard(
                title = "Network",
                value = "↓ ${formatBytes(downRate)}/s",
                subtitle = "↑ ${formatBytes(upRate)}/s",
                accentColor = accentPurple,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(MonitorSubTab.NETWORK) }
            )
        }

        // Secondary Metrics Overview Rows
        Text("Secondary Systems", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        // Storage & Disks Row
        if (primaryVol != null) {
            val volUsed = primaryVol.optLong("usedBytes", 0L)
            val volTotal = primaryVol.optLong("totalBytes", 1L)
            val volPct = primaryVol.optDouble("usagePercent", 0.0)
            val volMount = primaryVol.optString("mount", "C:")
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onNavigate(MonitorSubTab.STORAGE) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, CardBorderBrush)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("System Drive ($volMount)", fontWeight = FontWeight.SemiBold)
                        Text("${formatBytes(volUsed)} / ${formatBytes(volTotal)} (${volPct.roundToInt()}%)", style = MaterialTheme.typography.bodySmall)
                    }
                    LinearProgressIndicator(
                        progress = { (volUsed.toFloat() / volTotal.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = if (volPct >= 90) accentRed else accentBlue,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }

        // Power & Battery Row (if battery present)
        if (battery != null && battery.optBoolean("present")) {
            val bPct = battery.optInt("percent", 100)
            val charging = battery.optBoolean("charging")
            val plugged = battery.optBoolean("pluggedIn")
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onNavigate(MonitorSubTab.SYSTEM) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, CardBorderBrush)
            ) {
                Row(
                    Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Battery & Power", fontWeight = FontWeight.SemiBold)
                        Text(when {
                            charging -> "Charging · $bPct%"
                            plugged -> "Plugged In · $bPct%"
                            else -> "On Battery · $bPct%"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("$bPct%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (bPct < 20) accentRed else accentGreen)
                }
            }
        }

        // Quick Navigation Grid
        Text("Monitoring Modules", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        val modules = listOf(
            Triple("Processes", "Running apps & tasks", MonitorSubTab.PROCESSES),
            Triple("Thermal & Fans", "Core sensors & cooling", MonitorSubTab.THERMAL),
            Triple("System Info", "Windows specs & hardware", MonitorSubTab.SYSTEM),
            Triple("AI Engine", "Ollama models & inference", MonitorSubTab.AI),
            Triple("Oracle Services", "Subsystem health checks", MonitorSubTab.SERVICES),
            Triple("History & Charts", "Multi-window analysis", MonitorSubTab.HISTORY),
            Triple("Alerts & Rules", "Threshold alerts", MonitorSubTab.ALERTS),
            Triple("Settings", "Intervals & preferences", MonitorSubTab.SETTINGS)
        )

        for (chunk in modules.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                chunk.forEach { item ->
                    Surface(
                        modifier = Modifier.weight(1f).clickable { onNavigate(item.third) },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, CardBorderBrush)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(item.first, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text(item.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, CardBorderBrush)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.size(8.dp).background(accentColor, CircleShape))
            }
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = accentColor)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ----------------------------------------------------------------------------
// 2. CPU DETAIL PANE
// ----------------------------------------------------------------------------
@Composable
private fun CpuDetailPane(api: CompanionApi, snapshot: JSONObject?, hardware: JSONObject?) {
    val isDark = LocalDarkTheme.current
    val accentAmber = if (isDark) Color(0xffff7a45) else Color(0xffea580c)

    val cpu = snapshot?.optJSONObject("cpu")
    val model = cpu?.optString("model") ?: hardware?.optJSONObject("cpu")?.optString("model") ?: "Windows Processor"
    val physCores = cpu?.optInt("physicalCores", 0) ?: 0
    val logCores = cpu?.optInt("logicalCores", 0) ?: 0
    val usage = cpu?.optDouble("usagePercent", 0.0) ?: 0.0
    val freq = cpu?.optInt("frequencyMhz", 0) ?: 0
    val perCore = cpu?.optJSONArray("perCorePercent")

    var historyPoints by remember { mutableStateOf<List<Float>>(emptyList()) }
    var selectedWindow by rememberSaveable { mutableIntStateOf(300) } // 5m default

    LaunchedEffect(api, selectedWindow) {
        try {
            val hist = withContext(Dispatchers.IO) {
                api.call("monitor/history?window=$selectedWindow&points=40")
            }
            val pts = hist.optJSONArray("points")
            if (pts != null) {
                val list = mutableListOf<Float>()
                for (i in 0 until pts.length()) {
                    list.add(pts.optJSONObject(i)?.optDouble("cpu", 0.0)?.toFloat() ?: 0f)
                }
                historyPoints = list
            }
        } catch (_: Exception) {}
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Header Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, CardBorderBrush)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("$physCores Physical Cores · $logCores Logical Processors", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text("Utilization", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${usage.roundToInt()}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = accentAmber)
                    }
                    if (freq > 0) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Current Clock", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${(freq / 1000.0).format(2)} GHz", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Live Performance Chart Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, CardBorderBrush)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Processor Load Trend", fontWeight = FontWeight.SemiBold)
                    WindowSelector(selectedWindow = selectedWindow, onSelect = { selectedWindow = it })
                }

                val chartData = if (historyPoints.isNotEmpty()) historyPoints else listOf(usage.toFloat())
                TelemetryGraph(
                    data = chartData,
                    color = accentAmber,
                    unit = "%",
                    maxValue = 100f,
                    modifier = Modifier.fillMaxWidth().height(160.dp)
                )
            }
        }

        // Per-logical-core Visualizer Grid
        if (perCore != null && perCore.length() > 0) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, CardBorderBrush)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Logical Processors (${perCore.length()} Threads)", fontWeight = FontWeight.SemiBold)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (i in 0 until perCore.length() step 2) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                val val1 = perCore.optDouble(i, 0.0)
                                CoreProgress(coreIndex = i, value = val1, color = accentAmber, modifier = Modifier.weight(1f))

                                if (i + 1 < perCore.length()) {
                                    val val2 = perCore.optDouble(i + 1, 0.0)
                                    CoreProgress(coreIndex = i + 1, value = val2, color = accentAmber, modifier = Modifier.weight(1f))
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoreProgress(coreIndex: Int, value: Double, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Core $coreIndex", style = MaterialTheme.typography.labelSmall)
            Text("${value.roundToInt()}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
        LinearProgressIndicator(
            progress = { (value / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(5.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

// ----------------------------------------------------------------------------
// 3. MEMORY DETAIL PANE
// ----------------------------------------------------------------------------
@Composable
private fun MemoryDetailPane(api: CompanionApi, snapshot: JSONObject?) {
    val isDark = LocalDarkTheme.current
    val accentBlue = if (isDark) Color(0xff38bdf8) else Color(0xff0284c7)
    val accentPurple = if (isDark) Color(0xffc084fc) else Color(0xff9333ea)

    val memory = snapshot?.optJSONObject("memory")
    val total = memory?.optLong("totalBytes", 1L) ?: 1L
    val used = memory?.optLong("usedBytes", 0L) ?: 0L
    val available = memory?.optLong("availableBytes", 0L) ?: 0L
    val percent = memory?.optDouble("usagePercent", 0.0) ?: 0.0
    val swapTotal = memory?.optLong("swapTotalBytes", 0L) ?: 0L
    val swapUsed = memory?.optLong("swapUsedBytes", 0L) ?: 0L
    val swapPercent = memory?.optDouble("swapPercent", 0.0) ?: 0.0

    var historyPoints by remember { mutableStateOf<List<Float>>(emptyList()) }
    var selectedWindow by rememberSaveable { mutableIntStateOf(300) }

    LaunchedEffect(api, selectedWindow) {
        try {
            val hist = withContext(Dispatchers.IO) {
                api.call("monitor/history?window=$selectedWindow&points=40")
            }
            val pts = hist.optJSONArray("points")
            if (pts != null) {
                val list = mutableListOf<Float>()
                for (i in 0 until pts.length()) {
                    list.add(pts.optJSONObject(i)?.optDouble("ram", 0.0)?.toFloat() ?: 0f)
                }
                historyPoints = list
            }
        } catch (_: Exception) {}
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Header Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, CardBorderBrush)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Physical RAM", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("${percent.roundToInt()}% Allocated", color = accentBlue, fontWeight = FontWeight.Bold)
                }
                Text("${formatBytes(used)} Used of ${formatBytes(total)} Total", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = accentBlue)
                Text("Available Physical Memory: ${formatBytes(available)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                LinearProgressIndicator(
                    progress = { (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = accentBlue,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }

        // Live RAM Trend Chart
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, CardBorderBrush)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Memory Utilization Trend", fontWeight = FontWeight.SemiBold)
                    WindowSelector(selectedWindow = selectedWindow, onSelect = { selectedWindow = it })
                }
                val chartData = if (historyPoints.isNotEmpty()) historyPoints else listOf(percent.toFloat())
                TelemetryGraph(
                    data = chartData,
                    color = accentBlue,
                    unit = "%",
                    maxValue = 100f,
                    modifier = Modifier.fillMaxWidth().height(160.dp)
                )
            }
        }

        // Virtual Memory / Pagefile Card
        if (swapTotal > 0) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, CardBorderBrush)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pagefile / Virtual Memory", fontWeight = FontWeight.SemiBold)
                    Text("${formatBytes(swapUsed)} / ${formatBytes(swapTotal)} (${swapPercent.roundToInt()}%)", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { (swapUsed.toFloat() / swapTotal.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = accentPurple,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 4. GPU DETAIL PANE (Multi-GPU: NVIDIA Dedicated + Intel Integrated)
// ----------------------------------------------------------------------------
@Composable
private fun GpuDetailPane(api: CompanionApi, snapshot: JSONObject?, hardware: JSONObject?) {
    val isDark = LocalDarkTheme.current
    val accentGreen = if (isDark) Color(0xff50e3c2) else Color(0xff059669)
    val accentAmber = if (isDark) Color(0xffff7a45) else Color(0xffea580c)

    val gpus = snapshot?.optJSONArray("gpus")
    var selectedGpuIndex by rememberSaveable { mutableIntStateOf(0) }

    val activeGpu = if (gpus != null && gpus.length() > 0) {
        gpus.optJSONObject(selectedGpuIndex.coerceIn(0, gpus.length() - 1))
    } else null

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Multi-GPU switcher tabs if more than 1 GPU
        if (gpus != null && gpus.length() > 1) {
            TabRow(
                selectedTabIndex = selectedGpuIndex,
                containerColor = Color.Transparent
            ) {
                for (i in 0 until gpus.length()) {
                    val g = gpus.optJSONObject(i)
                    val label = "${g?.optString("vendor", "GPU")} ${g?.optString("type", "")}"
                    Tab(
                        selected = selectedGpuIndex == i,
                        onClick = { selectedGpuIndex = i },
                        text = { Text(label, maxLines = 1) }
                    )
                }
            }
        }

        if (activeGpu != null) {
            val name = activeGpu.optString("name", "Graphics Processor")
            val vendor = activeGpu.optString("vendor", "GPU")
            val type = activeGpu.optString("type", "graphics")
            val usage = activeGpu.optDouble("usagePercent", 0.0)
            val vramTotal = activeGpu.optLong("vramTotalBytes", 0L)
            val vramUsed = activeGpu.optLong("vramUsedBytes", 0L)
            val temp = activeGpu.optInt("temperatureC", -1)
            val power = activeGpu.optDouble("powerWatts", -1.0)
            val clock = activeGpu.optInt("clockMhz", -1)
            val memClock = activeGpu.optInt("memoryClockMhz", -1)
            val fan = activeGpu.optInt("fanPercent", -1)

            // GPU Header Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, CardBorderBrush)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                type.uppercase(Locale.getDefault()),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            Text("3D Engine Utilization", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${usage.roundToInt()}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = accentGreen)
                        }
                        if (temp > 0) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Core Temperature", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("$temp°C", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (temp > 80) accentAmber else accentGreen)
                            }
                        }
                    }
                }
            }

            // Dedicated VRAM Card
            if (vramTotal > 0) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, CardBorderBrush)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Dedicated VRAM Allocation", fontWeight = FontWeight.SemiBold)
                            Text("${formatBytes(vramUsed)} / ${formatBytes(vramTotal)}", style = MaterialTheme.typography.bodySmall)
                        }
                        LinearProgressIndicator(
                            progress = { (vramUsed.toFloat() / vramTotal.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = accentGreen,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }

            // Hardware Telemetry Gauges (Power, Clocks, Fan)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, CardBorderBrush)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Sensors & Operating State", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SensorBadge(label = "Power Draw", value = if (power >= 0) "$power W" else "N/A", modifier = Modifier.weight(1f))
                        SensorBadge(label = "Core Clock", value = if (clock > 0) "$clock MHz" else "N/A", modifier = Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SensorBadge(label = "Mem Clock", value = if (memClock > 0) "$memClock MHz" else "N/A", modifier = Modifier.weight(1f))
                        SensorBadge(label = "Fan Speed", value = if (fan >= 0) "$fan%" else "Dynamic", modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SensorBadge(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

// ----------------------------------------------------------------------------
// 5. STORAGE & SSD DETAIL PANE
// ----------------------------------------------------------------------------
@Composable
private fun StorageDetailPane(api: CompanionApi, snapshot: JSONObject?) {
    val isDark = LocalDarkTheme.current
    val accentBlue = if (isDark) Color(0xff38bdf8) else Color(0xff0284c7)
    val accentRed = if (isDark) Color(0xfff87171) else Color(0xffdc2626)

    val storage = snapshot?.optJSONObject("storage")
    val volumes = storage?.optJSONArray("volumes")
    val io = storage?.optJSONObject("io")
    val readRate = io?.optLong("readBytesPerSec", 0L) ?: 0L
    val writeRate = io?.optLong("writeBytesPerSec", 0L) ?: 0L
    val readIops = io?.optDouble("readOpsPerSec", 0.0) ?: 0.0
    val writeIops = io?.optDouble("writeOpsPerSec", 0.0) ?: 0.0

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Disk I/O Activity
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, CardBorderBrush)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Disk I/O Throughput", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SensorBadge(label = "Read Rate", value = "${formatBytes(readRate)}/s", modifier = Modifier.weight(1f))
                    SensorBadge(label = "Write Rate", value = "${formatBytes(writeRate)}/s", modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SensorBadge(label = "Read IOPS", value = "${readIops.roundToInt()}/s", modifier = Modifier.weight(1f))
                    SensorBadge(label = "Write IOPS", value = "${writeIops.roundToInt()}/s", modifier = Modifier.weight(1f))
                }
            }
        }

        // Logical Volumes List
        Text("Mounted Logical Volumes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (volumes != null) {
            for (i in 0 until volumes.length()) {
                val vol = volumes.optJSONObject(i)
                val mount = vol?.optString("mount", "Drive") ?: "Drive"
                val fs = vol?.optString("fs", "NTFS") ?: "NTFS"
                val used = vol?.optLong("usedBytes", 0L) ?: 0L
                val total = vol?.optLong("totalBytes", 1L) ?: 1L
                val free = vol?.optLong("freeBytes", 0L) ?: 0L
                val pct = vol?.optDouble("usagePercent", 0.0) ?: 0.0
                val lowSpace = vol?.optBoolean("lowSpaceWarning", false) ?: false

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = if (lowSpace) BorderStroke(1.dp, accentRed) else BorderStroke(1.dp, CardBorderBrush)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(mount, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Text(fs, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Text("${pct.roundToInt()}%", fontWeight = FontWeight.Bold, color = if (lowSpace) accentRed else accentBlue)
                        }

                        LinearProgressIndicator(
                            progress = { (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = if (lowSpace) accentRed else accentBlue,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Free: ${formatBytes(free)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Total: ${formatBytes(total)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 6. NETWORK DETAIL PANE
// ----------------------------------------------------------------------------
@Composable
private fun NetworkDetailPane(api: CompanionApi, snapshot: JSONObject?, remote: Boolean) {
    val isDark = LocalDarkTheme.current
    val accentPurple = if (isDark) Color(0xffc084fc) else Color(0xff9333ea)
    val accentGreen = if (isDark) Color(0xff50e3c2) else Color(0xff059669)

    val network = snapshot?.optJSONObject("network")
    val iface = network?.optString("activeInterface", "Network Adapter") ?: "Network Adapter"
    val connType = network?.optString("connectionType", "lan") ?: "lan"
    val localIp = network?.optString("localIp", "127.0.0.1") ?: "127.0.0.1"
    val tailscaleIp = network?.optString("tailscaleIp", "") ?: ""
    val downRate = network?.optLong("downloadBytesPerSec", 0L) ?: 0L
    val upRate = network?.optLong("uploadBytesPerSec", 0L) ?: 0L
    val totalRx = network?.optLong("totalBytesReceived", 0L) ?: 0L
    val totalTx = network?.optLong("totalBytesSent", 0L) ?: 0L
    val latency = network?.optDouble("latencyMs", 0.4) ?: 0.4

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Connection Diagnostics Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, CardBorderBrush)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(iface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            if (remote) "TAILSCALE MESH" else "LOCAL WI-FI",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SensorBadge(label = "LAN Address", value = localIp, modifier = Modifier.weight(1f))
                    SensorBadge(label = "Tailscale Address", value = if (tailscaleIp.isNotEmpty()) tailscaleIp else "Disconnected", modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SensorBadge(label = "Link Latency", value = "${latency.format(1)} ms", modifier = Modifier.weight(1f))
                    SensorBadge(label = "Mesh Security", value = "Encrypted TLS", modifier = Modifier.weight(1f))
                }
            }
        }

        // Live Throughput Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, CardBorderBrush)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Live Data Rates", fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SensorBadge(label = "Download Speed", value = "${formatBytes(downRate)}/s", modifier = Modifier.weight(1f))
                    SensorBadge(label = "Upload Speed", value = "${formatBytes(upRate)}/s", modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SensorBadge(label = "Total Received", value = formatBytes(totalRx), modifier = Modifier.weight(1f))
                    SensorBadge(label = "Total Transmitted", value = formatBytes(totalTx), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 7. THERMAL & COOLING PANE
// ----------------------------------------------------------------------------
@Composable
private fun ThermalDetailPane(api: CompanionApi, snapshot: JSONObject?) {
    val isDark = LocalDarkTheme.current
    val accentAmber = if (isDark) Color(0xffff7a45) else Color(0xffea580c)
    val accentGreen = if (isDark) Color(0xff50e3c2) else Color(0xff059669)

    val thermal = snapshot?.optJSONArray("thermal")
    val cooling = snapshot?.optJSONArray("cooling")

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Temperature Sensors", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (thermal != null) {
            for (i in 0 until thermal.length()) {
                val sensor = thermal.optJSONObject(i)
                val name = sensor?.optString("name", "Sensor") ?: "Sensor"
                val temp = sensor?.optInt("temperatureC", -1) ?: -1
                val status = sensor?.optString("status", "available") ?: "available"

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, CardBorderBrush)
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(name, fontWeight = FontWeight.SemiBold)
                            Text(if (status == "available") "Real hardware sensor" else "Elevated ACPI permission needed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (status == "available" && temp > 0) {
                            Text("$temp°C", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (temp > 80) accentAmber else accentGreen)
                        } else {
                            Text("Unavailable", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Text("Cooling & Fans", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (cooling != null) {
            for (i in 0 until cooling.length()) {
                val fan = cooling.optJSONObject(i)
                val name = fan?.optString("name", "Fan") ?: "Fan"
                val speed = fan?.optInt("speedPercent", -1) ?: -1
                val status = fan?.optString("status", "available") ?: "available"

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, CardBorderBrush)
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(name, fontWeight = FontWeight.SemiBold)
                        Text(if (status == "available" && speed >= 0) "$speed%" else "Dynamic", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = accentGreen)
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 8. PROCESSES MONITOR PANE (Mobile Task Manager)
// ----------------------------------------------------------------------------
@Composable
private fun ProcessesPane(api: CompanionApi) {
    val isDark = LocalDarkTheme.current
    val accentBlue = if (isDark) Color(0xff38bdf8) else Color(0xff0284c7)
    val accentAmber = if (isDark) Color(0xffff7a45) else Color(0xffea580c)

    var processes by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var sortBy by rememberSaveable { mutableStateOf("cpu") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var filterOracleOnly by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(api, sortBy, searchQuery) {
        loading = true
        try {
            val q = java.net.URLEncoder.encode(searchQuery, "UTF-8")
            val res = withContext(Dispatchers.IO) {
                api.call("monitor/processes?sort=$sortBy&limit=40&search=$q")
            }
            val arr = res.optJSONArray("processes")
            val list = mutableListOf<JSONObject>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { list.add(it) }
                }
            }
            processes = list
        } catch (_: Exception) {
        } finally {
            loading = false
        }
    }

    val displayed = if (filterOracleOnly) processes.filter { it.optBoolean("isOracle") } else processes

    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Search & Filter controls
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search processes…") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = sortBy == "cpu", onClick = { sortBy = "cpu" }, label = { Text("Sort CPU") })
                FilterChip(selected = sortBy == "ram", onClick = { sortBy = "ram" }, label = { Text("Sort RAM") })
                FilterChip(selected = sortBy == "name", onClick = { sortBy = "name" }, label = { Text("Name") })
            }
            FilterChip(
                selected = filterOracleOnly,
                onClick = { filterOracleOnly = !filterOracleOnly },
                label = { Text("Oracle AI") }
            )
        }

        if (loading && processes.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        LazyColumn(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(displayed, key = { it.optInt("pid") }) { proc ->
                val name = proc.optString("name", "process")
                val pid = proc.optInt("pid", 0)
                val cpuVal = proc.optDouble("cpuPercent", 0.0)
                val memMb = proc.optDouble("memoryMb", 0.0)
                val isOracle = proc.optBoolean("isOracle", false)

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, CardBorderBrush)
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                if (isOracle) {
                                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                        Text("ORACLE", modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                            }
                            Text("PID $pid", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${cpuVal.roundToInt()}%", fontWeight = FontWeight.Bold, color = accentAmber)
                                Text("CPU", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${memMb.roundToInt()} MB", fontWeight = FontWeight.Bold, color = accentBlue)
                                Text("RAM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 9. WINDOWS SYSTEM INFORMATION PANE
// ----------------------------------------------------------------------------
@Composable
private fun SystemInfoPane(snapshot: JSONObject?, hardware: JSONObject?) {
    val system = snapshot?.optJSONObject("system")
    val battery = snapshot?.optJSONObject("battery")
    val cpu = snapshot?.optJSONObject("cpu")
    val memory = snapshot?.optJSONObject("memory")

    val pcName = system?.optString("computerName", "PC") ?: "PC"
    val os = system?.optString("os", "Windows 11") ?: "Windows 11"
    val build = system?.optString("build", "") ?: ""
    val arch = system?.optString("architecture", "AMD64") ?: "AMD64"
    val mfg = system?.optString("manufacturer", "Generic") ?: "Generic"
    val prod = system?.optString("product", "PC") ?: "PC"
    val uptime = system?.optLong("uptimeSeconds", 0L) ?: 0L

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, CardBorderBrush)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(pcName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("$mfg · $prod", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SensorBadge(label = "Operating System", value = os, modifier = Modifier.weight(1f))
                    SensorBadge(label = "OS Build", value = build, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SensorBadge(label = "Architecture", value = arch, modifier = Modifier.weight(1f))
                    SensorBadge(label = "System Uptime", value = formatUptime(uptime), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 10. ORACLE AI ENGINE PANE
// ----------------------------------------------------------------------------
@Composable
private fun AiEnginePane(api: CompanionApi, snapshot: JSONObject?) {
    val ai = snapshot?.optJSONObject("ai")
    val provider = ai?.optString("provider", "local") ?: "local"
    val status = ai?.optString("status", "ready") ?: "ready"
    val model = ai?.optString("activeModel", "gemma:2b") ?: "gemma:2b"
    val latency = ai?.optDouble("recentLatencyMs", 42.0) ?: 42.0
    val loaded = ai?.optJSONArray("loadedModels")

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, CardBorderBrush)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Neural Architecture", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(status.uppercase(Locale.getDefault()), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
                Text("Active Model: $model", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Provider: ${provider.uppercase(Locale.getDefault())} · Baseline Latency: ${latency.format(1)} ms", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text("Subsystem Neural Health", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        val services = listOf(
            Pair("Chat & LLM Generation", "Ready"),
            Pair("Voice Acoustic Transcription", "Ready"),
            Pair("Vision Studio & OCR", "Ready"),
            Pair("Temporal Records & Memory", "Ready")
        )
        services.forEach {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, CardBorderBrush)
            ) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(it.first, fontWeight = FontWeight.SemiBold)
                    Text(it.second, color = Color(0xff50e3c2), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 11. ORACLE SERVICES HEALTH PANE
// ----------------------------------------------------------------------------
@Composable
private fun ServicesHealthPane(snapshot: JSONObject?) {
    val services = snapshot?.optJSONArray("services")

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Oracle Central Services", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (services != null) {
            for (i in 0 until services.length()) {
                val s = services.optJSONObject(i)
                val name = s?.optString("name", "Service") ?: "Service"
                val status = s?.optString("status", "healthy") ?: "healthy"
                val lat = s?.optDouble("latencyMs", 1.0) ?: 1.0

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, CardBorderBrush)
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(name, fontWeight = FontWeight.SemiBold)
                            Text("Response latency: ${lat.format(1)} ms", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (status == "healthy") Color(0xff064e3b) else Color(0xff7f1d1d)
                        ) {
                            Text(
                                status.uppercase(Locale.getDefault()),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (status == "healthy") Color(0xff6ee7b7) else Color(0xfffca5a5)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 12. PERFORMANCE HISTORY PANE
// ----------------------------------------------------------------------------
@Composable
private fun PerformanceHistoryPane(api: CompanionApi) {
    val isDark = LocalDarkTheme.current
    val accentAmber = if (isDark) Color(0xffff7a45) else Color(0xffea580c)
    val accentBlue = if (isDark) Color(0xff38bdf8) else Color(0xff0284c7)
    val accentGreen = if (isDark) Color(0xff50e3c2) else Color(0xff059669)

    var windowSec by rememberSaveable { mutableIntStateOf(300) }
    var cpuPoints by remember { mutableStateOf<List<Float>>(emptyList()) }
    var ramPoints by remember { mutableStateOf<List<Float>>(emptyList()) }
    var gpuPoints by remember { mutableStateOf<List<Float>>(emptyList()) }

    LaunchedEffect(api, windowSec) {
        try {
            val hist = withContext(Dispatchers.IO) {
                api.call("monitor/history?window=$windowSec&points=50")
            }
            val arr = hist.optJSONArray("points")
            if (arr != null) {
                val cList = mutableListOf<Float>()
                val rList = mutableListOf<Float>()
                val gList = mutableListOf<Float>()
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i)
                    cList.add(p?.optDouble("cpu", 0.0)?.toFloat() ?: 0f)
                    rList.add(p?.optDouble("ram", 0.0)?.toFloat() ?: 0f)
                    gList.add(p?.optDouble("gpu", 0.0)?.toFloat() ?: 0f)
                }
                cpuPoints = cList
                ramPoints = rList
                gpuPoints = gList
            }
        } catch (_: Exception) {}
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Multi-Interval History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            WindowSelector(selectedWindow = windowSec, onSelect = { windowSec = it })
        }

        // CPU Graph Card
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, CardBorderBrush)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CPU History (%)", fontWeight = FontWeight.SemiBold, color = accentAmber)
                TelemetryGraph(data = cpuPoints, color = accentAmber, unit = "%", maxValue = 100f, modifier = Modifier.fillMaxWidth().height(140.dp))
            }
        }

        // RAM Graph Card
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, CardBorderBrush)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("RAM History (%)", fontWeight = FontWeight.SemiBold, color = accentBlue)
                TelemetryGraph(data = ramPoints, color = accentBlue, unit = "%", maxValue = 100f, modifier = Modifier.fillMaxWidth().height(140.dp))
            }
        }

        // GPU Graph Card
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, CardBorderBrush)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GPU 3D Engine Load (%)", fontWeight = FontWeight.SemiBold, color = accentGreen)
                TelemetryGraph(data = gpuPoints, color = accentGreen, unit = "%", maxValue = 100f, modifier = Modifier.fillMaxWidth().height(140.dp))
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 13. ALERTS PANE
// ----------------------------------------------------------------------------
@Composable
private fun AlertsPane(api: CompanionApi, snapshot: JSONObject?) {
    val scope = rememberCoroutineScope()
    var alertList by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var historyList by remember { mutableStateOf<List<JSONObject>>(emptyList()) }

    LaunchedEffect(api, snapshot) {
        try {
            val res = withContext(Dispatchers.IO) { api.call("monitor/alerts") }
            val act = res.optJSONArray("active")
            val actList = mutableListOf<JSONObject>()
            if (act != null) {
                for (i in 0 until act.length()) act.optJSONObject(i)?.let { actList.add(it) }
            }
            alertList = actList

            val hist = res.optJSONArray("history")
            val hList = mutableListOf<JSONObject>()
            if (hist != null) {
                for (i in 0 until hist.length()) hist.optJSONObject(i)?.let { hList.add(it) }
            }
            historyList = hList
        } catch (_: Exception) {}
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Active Warnings & Conditions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (alertList.isEmpty()) {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, CardBorderBrush)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("✅", fontSize = 20.sp)
                    Text("All monitored systems nominal. No active warnings.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            alertList.forEach { alert ->
                val id = alert.optString("id")
                val title = alert.optString("title")
                val msg = alert.optString("message")
                val action = alert.optString("action")
                val isAck = alert.optBoolean("acknowledged", false)

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xff7f1d1d).copy(alpha = 0.25f),
                    border = BorderStroke(1.dp, Color(0xffef4444))
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(title, fontWeight = FontWeight.Bold, color = Color(0xfffca5a5))
                            if (!isAck) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    api.call("monitor/alerts/acknowledge", JSONObject().put("id", id))
                                                }
                                                alert.put("acknowledged", true)
                                                alertList = alertList.toList()
                                            } catch (_: Exception) {}
                                        }
                                    }
                                ) {
                                    Text("Acknowledge", color = Color(0xfffca5a5), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Text(msg, style = MaterialTheme.typography.bodySmall)
                        if (action.isNotEmpty()) {
                            Text("Recommended action: $action", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 14. MONITOR SETTINGS PANE
// ----------------------------------------------------------------------------
@Composable
private fun MonitorSettingsPane(api: CompanionApi) {
    val scope = rememberCoroutineScope()
    var cpuThreshold by remember { mutableFloatStateOf(90f) }
    var ramThreshold by remember { mutableFloatStateOf(90f) }
    var gpuTempThreshold by remember { mutableFloatStateOf(85f) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(api) {
        try {
            val settings = withContext(Dispatchers.IO) { api.call("monitor/settings") }
            cpuThreshold = settings.optDouble("cpu_threshold", 90.0).toFloat()
            ramThreshold = settings.optDouble("ram_threshold", 90.0).toFloat()
            gpuTempThreshold = settings.optDouble("gpu_temp_threshold", 85.0).toFloat()
        } catch (_: Exception) {}
    }

    fun saveSettings() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    api.call(
                        "monitor/settings",
                        JSONObject()
                            .put("cpu_threshold", cpuThreshold.toDouble())
                            .put("ram_threshold", ramThreshold.toDouble())
                            .put("gpu_temp_threshold", gpuTempThreshold.toDouble())
                    )
                }
                message = "Thresholds updated successfully."
            } catch (ex: Exception) {
                message = "Failed to update settings."
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, CardBorderBrush)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Alert Threshold Configuration", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)

                // CPU
                Column {
                    Text("CPU Warning: ${cpuThreshold.roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = cpuThreshold, onValueChange = { cpuThreshold = it }, valueRange = 50f..95f, onValueChangeFinished = ::saveSettings)
                }

                // RAM
                Column {
                    Text("RAM Warning: ${ramThreshold.roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = ramThreshold, onValueChange = { ramThreshold = it }, valueRange = 50f..95f, onValueChangeFinished = ::saveSettings)
                }

                // GPU Temp
                Column {
                    Text("GPU Temp Warning: ${gpuTempThreshold.roundToInt()}°C", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = gpuTempThreshold, onValueChange = { gpuTempThreshold = it }, valueRange = 65f..95f, onValueChangeFinished = ::saveSettings)
                }

                if (message.isNotEmpty()) {
                    Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// REUSABLE GRAPH COMPONENT
// ----------------------------------------------------------------------------
@Composable
private fun TelemetryGraph(
    data: List<Float>,
    color: Color,
    unit: String,
    maxValue: Float,
    modifier: Modifier = Modifier
) {
    if (data.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Gathering samples…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Canvas(modifier) {
        val width = size.width
        val height = size.height
        val padding = 4.dp.toPx()

        // Horizontal Grid Lines (25%, 50%, 75%, 100%)
        for (i in 1..4) {
            val y = height - (height * (i * 0.25f))
            drawLine(
                color = Color.Gray.copy(alpha = 0.2f),
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        val stepX = width / max(1, data.size - 1)
        val strokePath = Path()
        val fillPath = Path()

        val firstY = height - ((data[0] / maxValue).coerceIn(0f, 1f) * (height - padding * 2)) - padding
        strokePath.moveTo(0f, firstY)
        fillPath.moveTo(0f, height)
        fillPath.lineTo(0f, firstY)

        for (i in 1 until data.size) {
            val x = i * stepX
            val y = height - ((data[i] / maxValue).coerceIn(0f, 1f) * (height - padding * 2)) - padding
            strokePath.lineTo(x, y)
            fillPath.lineTo(x, y)
        }

        fillPath.lineTo(width, height)
        fillPath.close()

        // Draw area fill
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.02f)),
                startY = 0f,
                endY = height
            )
        )

        // Draw stroke line
        drawPath(
            path = strokePath,
            color = color,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun WindowSelector(selectedWindow: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(60 to "1m", 300 to "5m", 900 to "15m", 3600 to "1h").forEach { pair ->
            val sel = selectedWindow == pair.first
            Surface(
                onClick = { onSelect(pair.first) },
                shape = RoundedCornerShape(8.dp),
                color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                Text(
                    pair.second,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------
// UTILITY FUNCTIONS
// ----------------------------------------------------------------------------
private fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var b = bytes.toDouble()
    var idx = 0
    while (b >= 1024.0 && idx < units.lastIndex) {
        b /= 1024.0
        idx++
    }
    return "${b.format(1)} ${units[idx]}"
}

private fun formatUptime(seconds: Long): String {
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

private fun Double.format(digits: Int) = String.format(Locale.US, "%.${digits}f", this)
