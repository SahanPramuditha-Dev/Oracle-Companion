package dev.oracle.companion

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal data class GoogleStatus(
    val connected: Boolean,
    val email: String? = null,
    val error: String? = null
)

internal data class CalendarEvent(
    val id: String,
    val summary: String,
    val startStr: String,
    val endStr: String,
    val location: String? = null,
    val htmlLink: String? = null,
    val status: String? = null
)

internal data class GoogleTaskItem(
    val id: String,
    val title: String,
    val status: String, // "needsAction" or "completed"
    val due: String? = null
) {
    val isCompleted: Boolean get() = status == "completed"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoogleWorkspacePane(
    api: CompanionApi?,
    online: Boolean,
    onNavigateChat: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = LocalDarkTheme.current
    var subTab by rememberSaveable { mutableIntStateOf(0) } // 0: Calendar Agenda, 1: Google Tasks

    var googleStatus by remember { mutableStateOf<GoogleStatus?>(null) }
    var events by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<GoogleTaskItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }

    var showAddEvent by remember { mutableStateOf(false) }
    var showAddTask by remember { mutableStateOf(false) }

    val blueColor = if (isDark) Color(0xff38bdf8) else Color(0xff0284c7)
    val greenColor = if (isDark) Color(0xff50e3c2) else Color(0xff059669)
    val redColor = if (isDark) Color(0xfff87171) else Color(0xffef4444)
    val amberColor = if (isDark) Color(0xffffb800) else Color(0xffd97706)

    fun refresh() {
        if (api == null || !online) return
        scope.launch {
            loading = true
            errorMsg = ""
            try {
                val statusRes = withContext(Dispatchers.IO) { api.call("google/status") }
                val connected = statusRes.optBoolean("connected", false)
                val email = statusRes.optString("email").takeIf { it.isNotEmpty() }
                val err = statusRes.optString("error").takeIf { it.isNotEmpty() }
                googleStatus = GoogleStatus(connected = connected, email = email, error = err)

                if (connected) {
                    // Fetch Calendar Events
                    val eventsRes = withContext(Dispatchers.IO) { api.call("google/events?days=30") }
                    val eventsArr = eventsRes.optJSONArray("events")
                    val parsedEvents = mutableListOf<CalendarEvent>()
                    if (eventsArr != null) {
                        for (i in 0 until eventsArr.length()) {
                            val obj = eventsArr.getJSONObject(i)
                            val startObj = obj.optJSONObject("start")
                            val endObj = obj.optJSONObject("end")
                            val startStr = startObj?.optString("dateTime") ?: startObj?.optString("date") ?: ""
                            val endStr = endObj?.optString("dateTime") ?: endObj?.optString("date") ?: ""
                            parsedEvents.add(
                                CalendarEvent(
                                    id = obj.optString("id", UUID.randomUUID().toString()),
                                    summary = obj.optString("summary", "Untitled Event"),
                                    startStr = startStr,
                                    endStr = endStr,
                                    location = obj.optString("location").takeIf { it.isNotEmpty() },
                                    htmlLink = obj.optString("htmlLink").takeIf { it.isNotEmpty() },
                                    status = obj.optString("status")
                                )
                            )
                        }
                    }
                    events = parsedEvents

                    // Fetch Google Tasks
                    val tasksRes = withContext(Dispatchers.IO) { api.call("google/tasks") }
                    val tasksArr = tasksRes.optJSONArray("tasks")
                    val parsedTasks = mutableListOf<GoogleTaskItem>()
                    if (tasksArr != null) {
                        for (i in 0 until tasksArr.length()) {
                            val obj = tasksArr.getJSONObject(i)
                            parsedTasks.add(
                                GoogleTaskItem(
                                    id = obj.getString("id"),
                                    title = obj.getString("title"),
                                    status = obj.optString("status", "needsAction"),
                                    due = obj.optString("due").takeIf { it.isNotEmpty() }
                                )
                            )
                        }
                    }
                    tasks = parsedTasks
                }
            } catch (e: Exception) {
                errorMsg = e.message ?: "Failed to sync Google Workspace"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(api, online) {
        refresh()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status & Account Header Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, CardBorderBrush)
        ) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (googleStatus?.connected == true) greenColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, if (googleStatus?.connected == true) greenColor else MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                        Text(if (googleStatus?.connected == true) "📅" else "🔒", fontSize = 16.sp)
                    }
                }

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (googleStatus?.connected == true) "Google Workspace Connected" else "Google Disconnected",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        googleStatus?.email ?: "Connect in Oracle Desktop Settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = ::refresh, enabled = !loading && online) {
                    Text("🔄", fontSize = 15.sp)
                }
            }
        }

        if (errorMsg.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("⚠️", fontSize = 14.sp)
                    Text(errorMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        if (googleStatus?.connected != true && !loading) {
            // Disconnected Guide Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, CardBorderBrush)
            ) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("🌐", fontSize = 36.sp)
                    Text("Google Account Required", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "To manage your Google Calendar events and Google Tasks from this phone, sign in to your Google Account on your paired PC under Oracle Settings > Google Workspace.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = ::refresh,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Check Connection Again")
                    }
                }
            }
        } else {
            // Sub-Tab Switcher: Agenda vs Tasks
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, CardBorderBrush),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("Calendar Agenda (${events.size})" to 0, "Google Tasks (${tasks.size})" to 1).forEach { (label, idx) ->
                        val selected = subTab == idx
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            onClick = { subTab = idx }
                        ) {
                            Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Tab Body
            when (subTab) {
                0 -> CalendarAgendaSection(
                    events = events,
                    online = online,
                    onAdd = { showAddEvent = true },
                    onOpenLink = { url ->
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: Exception) {}
                    }
                )
                1 -> GoogleTasksSection(
                    tasks = tasks,
                    online = online,
                    onAdd = { showAddTask = true },
                    onToggle = { task ->
                        scope.launch {
                            try {
                                val body = JSONObject().put("id", task.id).put("completed", !task.isCompleted)
                                withContext(Dispatchers.IO) { api?.call("google/tasks/complete", body) }
                                refresh()
                            } catch (e: Exception) {
                                errorMsg = e.message ?: "Failed to update task"
                            }
                        }
                    }
                )
            }
        }
    }

    // Add Event Sheet
    if (showAddEvent) {
        AddEventSheet(
            onDismiss = { showAddEvent = false },
            onSave = { title, startIso, endIso ->
                scope.launch {
                    try {
                        val body = JSONObject()
                            .put("title", title)
                            .put("start", startIso)
                            .put("end", endIso)
                            .put("operation_id", UUID.randomUUID().toString().replace("-", ""))
                        withContext(Dispatchers.IO) { api?.call("google/events", body) }
                        showAddEvent = false
                        refresh()
                    } catch (e: Exception) {
                        errorMsg = e.message ?: "Could not schedule event"
                    }
                }
            }
        )
    }

    // Add Task Sheet
    if (showAddTask) {
        AddGoogleTaskSheet(
            onDismiss = { showAddTask = false },
            onSave = { title, dueDate ->
                scope.launch {
                    try {
                        val body = JSONObject().put("title", title)
                        if (dueDate != null) body.put("due_date", dueDate)
                        withContext(Dispatchers.IO) { api?.call("google/tasks", body) }
                        showAddTask = false
                        refresh()
                    } catch (e: Exception) {
                        errorMsg = e.message ?: "Could not add task"
                    }
                }
            }
        )
    }
}

@Composable
private fun CalendarAgendaSection(
    events: List<CalendarEvent>,
    online: Boolean,
    onAdd: () -> Unit,
    onOpenLink: (String) -> Unit
) {
    val isDark = LocalDarkTheme.current
    val blueColor = if (isDark) Color(0xff38bdf8) else Color(0xff0284c7)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Upcoming Schedule", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(
                onClick = onAdd,
                enabled = online,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("+ New Event", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (events.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, CardBorderBrush)
            ) {
                Column(
                    Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("🗓️", fontSize = 32.sp)
                    Text("No upcoming events", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    Text("Your calendar is clear for the next 30 days.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
            ) {
                items(events, key = { it.id }) { item ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = item.htmlLink != null) { item.htmlLink?.let(onOpenLink) },
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, CardBorderBrush)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(item.summary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                if (item.htmlLink != null) {
                                    Text("🔗 Open", fontSize = 11.sp, color = blueColor)
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = blueColor.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        item.startStr.take(16).replace("T", " · "),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = blueColor
                                    )
                                }
                                if (item.location != null) {
                                    Text(
                                        "📍 ${item.location}",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
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
private fun GoogleTasksSection(
    tasks: List<GoogleTaskItem>,
    online: Boolean,
    onAdd: () -> Unit,
    onToggle: (GoogleTaskItem) -> Unit
) {
    val isDark = LocalDarkTheme.current
    val greenColor = if (isDark) Color(0xff50e3c2) else Color(0xff059669)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Google Tasks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(
                onClick = onAdd,
                enabled = online,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("+ New Task", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (tasks.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, CardBorderBrush)
            ) {
                Column(
                    Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("✅", fontSize = 32.sp)
                    Text("All tasks completed", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    Text("Create a task to sync with Google Tasks on desktop and phone.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(task) },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, CardBorderBrush)
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Checkbox(
                                checked = task.isCompleted,
                                onCheckedChange = { onToggle(task) }
                            )

                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    task.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (task.isCompleted) FontWeight.Normal else FontWeight.SemiBold,
                                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                                    color = if (task.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                                )
                                if (task.due != null) {
                                    Text(
                                        "Due: ${task.due.take(10)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
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
private fun AddEventSheet(
    onDismiss: () -> Unit,
    onSave: (title: String, startIso: String, endIso: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var durationMinutes by remember { mutableIntStateOf(60) }

    CompanionSheet(onDismiss = onDismiss) {
        Text("Schedule Google Calendar Event", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Event Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Duration", style = MaterialTheme.typography.labelMedium)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(15, 30, 45, 60, 90, 120).forEach { mins ->
                FilterChip(
                    selected = durationMinutes == mins,
                    onClick = { durationMinutes = mins },
                    label = { Text("${mins}m") }
                )
            }
        }

        Button(
            onClick = {
                val now = System.currentTimeMillis() + (15 * 60 * 1000L) // starts in 15m
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                val startIso = sdf.format(Date(now))
                val endIso = sdf.format(Date(now + (durationMinutes * 60 * 1000L)))
                onSave(title.trim(), startIso, endIso)
            },
            enabled = title.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Create Event")
        }
    }
}

@Composable
private fun AddGoogleTaskSheet(
    onDismiss: () -> Unit,
    onSave: (title: String, dueDate: String?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var addDueDate by remember { mutableStateOf(false) }

    CompanionSheet(onDismiss = onDismiss) {
        Text("New Google Task", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Task Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Checkbox(checked = addDueDate, onCheckedChange = { addDueDate = it })
            Text("Set due date for today", style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                val todayStr = if (addDueDate) SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) else null
                onSave(title.trim(), todayStr)
            },
            enabled = title.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add to Google Tasks")
        }
    }
}
