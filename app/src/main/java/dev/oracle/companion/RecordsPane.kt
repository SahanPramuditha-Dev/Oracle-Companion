package dev.oracle.companion

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class CompanionTask(
    val id: String,
    val title: String,
    val kind: String, // "timer" or "reminder"
    val due: Long,
    val created: Long,
    val state: String, // "pending" or "alert"
    val url: String? = null
)

internal data class CompanionNote(
    val id: String,
    val title: String,
    val text: String,
    val created: Long,
    val updated: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordsPane(
    api: CompanionApi?,
    online: Boolean,
    onNavigateChat: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val isDark = LocalDarkTheme.current
    var activeTab by rememberSaveable { mutableIntStateOf(0) } // 0: Reminders, 1: Timers, 2: Notes

    var tasks by remember { mutableStateOf<List<CompanionTask>>(emptyList()) }
    var notes by remember { mutableStateOf<List<CompanionNote>>(emptyList()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }

    // Dialog & Sheet states
    var showAddReminder by remember { mutableStateOf(false) }
    var showAddTimer by remember { mutableStateOf(false) }
    var showAddNote by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<CompanionNote?>(null) }

    fun refresh() {
        if (api == null || !online) return
        scope.launch {
            loading = true
            errorMsg = ""
            try {
                val taskRes = withContext(Dispatchers.IO) { api.call("records/tasks") }
                val parsedTasks = mutableListOf<CompanionTask>()
                val tasksArr = taskRes.optJSONArray("tasks")
                if (tasksArr != null) {
                    for (i in 0 until tasksArr.length()) {
                        val obj = tasksArr.getJSONObject(i)
                        parsedTasks.add(
                            CompanionTask(
                                id = obj.getString("id"),
                                title = obj.getString("title"),
                                kind = obj.optString("kind", "reminder"),
                                due = obj.optLong("due"),
                                created = obj.optLong("created"),
                                state = obj.optString("state", "pending"),
                                url = obj.optString("url").takeIf { it.isNotEmpty() }
                            )
                        )
                    }
                }
                tasks = parsedTasks

                val notesRes = withContext(Dispatchers.IO) {
                    api.call(if (searchQuery.isNotEmpty()) "records/notes?q=$searchQuery" else "records/notes")
                }
                val parsedNotes = mutableListOf<CompanionNote>()
                val notesArr = notesRes.optJSONArray("notes")
                if (notesArr != null) {
                    for (i in 0 until notesArr.length()) {
                        val obj = notesArr.getJSONObject(i)
                        parsedNotes.add(
                            CompanionNote(
                                id = obj.getString("id"),
                                title = obj.getString("title"),
                                text = obj.getString("text"),
                                created = obj.optLong("created"),
                                updated = obj.optLong("updated")
                            )
                        )
                    }
                }
                notes = parsedNotes
            } catch (err: Exception) {
                errorMsg = err.message ?: "Failed to sync records"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(api, online, searchQuery) {
        refresh()
    }

    val reminders = remember(tasks) { tasks.filter { it.kind == "reminder" } }
    val timers = remember(tasks) { tasks.filter { it.kind == "timer" } }

    val greenColor = if (isDark) Color(0xff50e3c2) else Color(0xff059669)
    val amberColor = if (isDark) Color(0xffffb800) else Color(0xffd97706)
    val blueColor = if (isDark) Color(0xff38bdf8) else Color(0xff0284c7)
    val purpleColor = if (isDark) Color(0xffc084fc) else Color(0xff7c3aed)

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Tab Selector Row
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, CardBorderBrush),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val tabLabels = listOf(
                    "Reminders (${reminders.size})" to 0,
                    "Timers (${timers.size})" to 1,
                    "Notes (${notes.size})" to 2,
                    "Google" to 3
                )
                tabLabels.forEach { (label, index) ->
                    val isSelected = activeTab == index
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        onClick = { activeTab = index }
                    ) {
                        Box(
                            Modifier.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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

        // Tab Content
        when (activeTab) {
            0 -> RemindersSection(
                reminders = reminders,
                online = online,
                onAdd = { showAddReminder = true },
                onDelete = { id ->
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                api?.call("records/tasks/delete", JSONObject().put("id", id))
                            }
                            refresh()
                        } catch (e: Exception) { errorMsg = e.message ?: "Failed to remove reminder" }
                    }
                },
                onSnooze = { id, mins ->
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                api?.call("records/tasks/snooze", JSONObject().put("id", id).put("minutes", mins))
                            }
                            refresh()
                        } catch (e: Exception) { errorMsg = e.message ?: "Failed to snooze" }
                    }
                }
            )
            1 -> TimersSection(
                timers = timers,
                online = online,
                onAdd = { showAddTimer = true },
                onDismiss = { id ->
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                api?.call("records/tasks/delete", JSONObject().put("id", id))
                            }
                            refresh()
                        } catch (e: Exception) { errorMsg = e.message ?: "Failed to dismiss timer" }
                    }
                },
                onAddMinute = { id ->
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                api?.call("records/tasks/snooze", JSONObject().put("id", id).put("minutes", 1))
                            }
                            refresh()
                        } catch (e: Exception) { errorMsg = e.message ?: "Failed to extend timer" }
                    }
                }
            )
            2 -> NotesSection(
                notes = notes,
                online = online,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                onAdd = { showAddNote = true },
                onEdit = { editingNote = it },
                onDelete = { id ->
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                api?.call("records/notes/delete", JSONObject().put("id", id))
                            }
                            refresh()
                        } catch (e: Exception) { errorMsg = e.message ?: "Failed to delete note" }
                    }
                }
            )
            3 -> GoogleWorkspacePane(
                api = api,
                online = online,
                onNavigateChat = onNavigateChat
            )
        }
    }

    // Add Reminder Bottom Sheet
    if (showAddReminder) {
        AddReminderSheet(
            onDismiss = { showAddReminder = false },
            onConfirm = { title, delayMs ->
                scope.launch {
                    try {
                        val body = JSONObject()
                            .put("title", title)
                            .put("due", System.currentTimeMillis() + delayMs)
                            .put("kind", "reminder")
                        withContext(Dispatchers.IO) { api?.call("records/tasks", body) }
                        showAddReminder = false
                        refresh()
                    } catch (e: Exception) { errorMsg = e.message ?: "Could not create reminder" }
                }
            }
        )
    }

    // Add Timer Bottom Sheet
    if (showAddTimer) {
        AddTimerSheet(
            onDismiss = { showAddTimer = false },
            onConfirm = { title, durationMs ->
                scope.launch {
                    try {
                        val body = JSONObject()
                            .put("title", title)
                            .put("due", System.currentTimeMillis() + durationMs)
                            .put("duration_ms", durationMs)
                            .put("kind", "timer")
                        withContext(Dispatchers.IO) { api?.call("records/tasks", body) }
                        showAddTimer = false
                        refresh()
                    } catch (e: Exception) { errorMsg = e.message ?: "Could not create timer" }
                }
            }
        )
    }

    // Add Note Bottom Sheet
    if (showAddNote) {
        NoteEditorSheet(
            note = null,
            onDismiss = { showAddNote = false },
            onSave = { title, text ->
                scope.launch {
                    try {
                        val body = JSONObject().put("title", title).put("text", text)
                        withContext(Dispatchers.IO) { api?.call("records/notes", body) }
                        showAddNote = false
                        refresh()
                    } catch (e: Exception) { errorMsg = e.message ?: "Could not save note" }
                }
            }
        )
    }

    // Edit Note Bottom Sheet
    editingNote?.let { noteToEdit ->
        NoteEditorSheet(
            note = noteToEdit,
            onDismiss = { editingNote = null },
            onSave = { title, text ->
                scope.launch {
                    try {
                        val body = JSONObject().put("id", noteToEdit.id).put("title", title).put("text", text)
                        withContext(Dispatchers.IO) { api?.call("records/notes/edit", body) }
                        editingNote = null
                        refresh()
                    } catch (e: Exception) { errorMsg = e.message ?: "Could not update note" }
                }
            }
        )
    }
}

@Composable
private fun RemindersSection(
    reminders: List<CompanionTask>,
    online: Boolean,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onSnooze: (String, Int) -> Unit
) {
    val isDark = LocalDarkTheme.current
    val amberColor = if (isDark) Color(0xffffb800) else Color(0xffd97706)
    val greenColor = if (isDark) Color(0xff50e3c2) else Color(0xff059669)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Active Reminders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(
                onClick = onAdd,
                enabled = online,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("+ New Reminder", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (reminders.isEmpty()) {
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
                    Text("🔔", fontSize = 32.sp)
                    Text("No pending reminders", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    Text("Ask Oracle to remind you or tap '+ New Reminder' above.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
            ) {
                items(reminders, key = { it.id }) { item ->
                    val isDue = item.state == "alert" || item.due <= System.currentTimeMillis()
                    val dueFormat = remember(item.due) {
                        SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault()).format(Date(item.due))
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = if (isDue) BorderStroke(1.dp, amberColor.copy(alpha = 0.6f)) else BorderStroke(1.dp, CardBorderBrush)
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (isDue) amberColor.copy(alpha = 0.2f) else greenColor.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, if (isDue) amberColor else greenColor)
                            ) {
                                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                    Text(if (isDue) "⏰" else "📌", fontSize = 16.sp)
                                }
                            }

                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isDue) amberColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                    ) {
                                        Text(
                                            if (isDue) "DUE NOW" else "SCHEDULED",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isDue) amberColor else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(dueFormat, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            // Actions
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { onSnooze(item.id, 10) }) {
                                    Text("+10m", style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(onClick = { onDelete(item.id) }) {
                                    Text("✓", fontWeight = FontWeight.Bold, color = greenColor)
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
private fun TimersSection(
    timers: List<CompanionTask>,
    online: Boolean,
    onAdd: () -> Unit,
    onDismiss: (String) -> Unit,
    onAddMinute: (String) -> Unit
) {
    val isDark = LocalDarkTheme.current
    val blueColor = if (isDark) Color(0xff38bdf8) else Color(0xff0284c7)
    val redColor = if (isDark) Color(0xfff87171) else Color(0xffdc2626)

    // Clock ticker every 1 second
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Active Countdowns", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(
                onClick = onAdd,
                enabled = online,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("+ New Timer", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (timers.isEmpty()) {
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
                    Text("⏳", fontSize = 32.sp)
                    Text("No active timers", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    Text("Set quick timers for cooking, tasks, or Pomodoro sessions.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
            ) {
                items(timers, key = { it.id }) { item ->
                    val remainingMs = (item.due - now).coerceAtLeast(0)
                    val expired = remainingMs <= 0
                    val totalSec = (remainingMs / 1000).toInt()
                    val minutes = totalSec / 60
                    val seconds = totalSec % 60
                    val timeText = if (expired) "00:00 (Time's Up!)" else String.format(Locale.US, "%02d:%02d", minutes, seconds)

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = if (expired) BorderStroke(1.dp, redColor) else BorderStroke(1.dp, CardBorderBrush)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(if (expired) "Finished" else "Counting down",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    timeText,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (expired) redColor else blueColor
                                )
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onAddMinute(item.id) },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("+1 Min")
                                }
                                Button(
                                    onClick = { onDismiss(item.id) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (expired) redColor else MaterialTheme.colorScheme.errorContainer,
                                        contentColor = if (expired) Color.White else MaterialTheme.colorScheme.onErrorContainer
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Dismiss")
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
private fun NotesSection(
    notes: List<CompanionNote>,
    online: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (CompanionNote) -> Unit,
    onDelete: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Personal Notes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(
                onClick = onAdd,
                enabled = online,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("+ New Note", style = MaterialTheme.typography.labelMedium)
            }
        }

        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Search notes…") },
            shape = RoundedCornerShape(14.dp),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (notes.isEmpty()) {
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
                    Text("📝", fontSize = 32.sp)
                    Text("No notes found", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    Text("Jot down quick ideas, meeting memos, or code snippets.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
            ) {
                items(notes, key = { it.id }) { note ->
                    val updatedStr = remember(note.updated) {
                        SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()).format(Date(note.updated))
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEdit(note) },
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, CardBorderBrush)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(note.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                IconButton(onClick = { onDelete(note.id) }, modifier = Modifier.size(24.dp)) {
                                    Text("✕", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(
                                note.text,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Updated $updatedStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddReminderSheet(
    onDismiss: () -> Unit,
    onConfirm: (title: String, delayMs: Long) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var selectedDelay by remember { mutableLongStateOf(30 * 60 * 1000L) } // default 30 mins

    val presets = listOf(
        "15 min" to 15 * 60 * 1000L,
        "30 min" to 30 * 60 * 1000L,
        "1 hour" to 60 * 60 * 1000L,
        "3 hours" to 3 * 60 * 60 * 1000L,
        "Tomorrow" to 24 * 60 * 60 * 1000L
    )

    CompanionSheet(onDismiss = onDismiss) {
        Text("Create Reminder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("What do you need to remember?") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text("When should Oracle remind you?", style = MaterialTheme.typography.labelMedium)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { (label, delayMs) ->
                val isSelected = selectedDelay == delayMs
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedDelay = delayMs },
                    label = { Text(label) }
                )
            }
        }

        Button(
            onClick = { onConfirm(title.trim(), selectedDelay) },
            enabled = title.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Reminder")
        }
    }
}

@Composable
private fun AddTimerSheet(
    onDismiss: () -> Unit,
    onConfirm: (title: String, durationMs: Long) -> Unit
) {
    var title by remember { mutableStateOf("Quick Timer") }
    var selectedMinutes by remember { mutableIntStateOf(5) }

    val presets = listOf(1, 3, 5, 10, 15, 25, 30, 45, 60)

    CompanionSheet(onDismiss = onDismiss) {
        Text("Set Countdown Timer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Timer label (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Duration: $selectedMinutes Minutes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { mins ->
                val isSelected = selectedMinutes == mins
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedMinutes = mins },
                    label = { Text("${mins}m") }
                )
            }
        }

        Button(
            onClick = { onConfirm(title.trim().ifEmpty { "Timer" }, selectedMinutes * 60 * 1000L) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start ${selectedMinutes}m Timer")
        }
    }
}

@Composable
private fun NoteEditorSheet(
    note: CompanionNote?,
    onDismiss: () -> Unit,
    onSave: (title: String, text: String) -> Unit
) {
    var title by remember { mutableStateOf(note?.title.orEmpty()) }
    var text by remember { mutableStateOf(note?.text.orEmpty()) }

    CompanionSheet(onDismiss = onDismiss) {
        Text(if (note == null) "New Note" else "Edit Note", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Content") },
            minLines = 4,
            maxLines = 10,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onSave(title.trim().ifEmpty { "Untitled" }, text.trim()) },
            enabled = text.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Note")
        }
    }
}
