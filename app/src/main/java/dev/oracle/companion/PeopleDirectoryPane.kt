package dev.oracle.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

internal data class EnrolledPerson(
    val id: String,
    val name: String,
    val relationship: String, // "owner", "family", "friend", "colleague", "guest"
    val permission: String, // "standard", "administrator", "disabled"
    val enrollmentState: String, // "active", "incomplete", "needs_new_samples", "revoked"
    val consentStatus: String, // "granted", "pending", "revoked"
    val sampleCount: Int,
    val notes: String? = null
) {
    val isConsentGranted: Boolean get() = consentStatus == "granted"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PeopleDirectoryPane(
    api: CompanionApi?,
    online: Boolean,
    onNavigateChat: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val isDark = LocalDarkTheme.current
    var filterCategory by rememberSaveable { mutableStateOf("All") }

    var peopleList by remember { mutableStateOf<List<EnrolledPerson>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var statusNotice by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }

    var showEnrollSheet by remember { mutableStateOf(false) }
    var editingPerson by remember { mutableStateOf<EnrolledPerson?>(null) }

    val blueColor = if (isDark) Color(0xff38bdf8) else Color(0xff0284c7)
    val greenColor = if (isDark) Color(0xff50e3c2) else Color(0xff059669)
    val amberColor = if (isDark) Color(0xffffb800) else Color(0xffd97706)
    val redColor = if (isDark) Color(0xfff87171) else Color(0xffdc2626)
    val purpleColor = if (isDark) Color(0xffc084fc) else Color(0xff7c3aed)

    fun refreshPeople() {
        if (api == null || !online) return
        scope.launch {
            loading = true
            errorMsg = ""
            try {
                val res = withContext(Dispatchers.IO) { api.call("people") }
                val parsed = mutableListOf<EnrolledPerson>()
                val arr = res.optJSONArray("people")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        parsed.add(
                            EnrolledPerson(
                                id = obj.getString("id"),
                                name = obj.getString("name"),
                                relationship = obj.optString("relationship", "guest"),
                                permission = obj.optString("permission", "standard"),
                                enrollmentState = obj.optString("enrollment_state", "active"),
                                consentStatus = obj.optString("consent_status", "granted"),
                                sampleCount = obj.optInt("sample_count", 1),
                                notes = obj.optString("notes").takeIf { it.isNotEmpty() }
                            )
                        )
                    }
                }
                peopleList = parsed
            } catch (e: Exception) {
                errorMsg = e.message ?: "Failed to retrieve people registry"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(api, online) {
        refreshPeople()
    }

    val filteredList = remember(peopleList, filterCategory) {
        if (filterCategory == "All") peopleList
        else peopleList.filter { it.relationship.equals(filterCategory, ignoreCase = true) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, CardBorderBrush)
        ) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("People & Identities", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${peopleList.size} enrolled identities · Consent verified",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Button(
                    onClick = { showEnrollSheet = true },
                    enabled = online,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("+ Enroll Face", style = MaterialTheme.typography.labelMedium)
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
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⚠️", fontSize = 14.sp)
                    Text(errorMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        if (statusNotice.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = greenColor.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, greenColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✅", fontSize = 14.sp)
                    Text(statusNotice, style = MaterialTheme.typography.bodySmall, color = greenColor)
                }
            }
        }

        // Category Filter Chips
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("All", "Family", "Friend", "Colleague", "Guest").forEach { cat ->
                val isSelected = filterCategory == cat
                FilterChip(
                    selected = isSelected,
                    onClick = { filterCategory = cat },
                    label = { Text(cat) }
                )
            }
        }

        // People Directory List
        if (filteredList.isEmpty() && !loading) {
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
                    Text("👥", fontSize = 36.sp)
                    Text("No identities in this category", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text("Tap '+ Enroll Face' to add family members, friends, or trusted colleagues.",
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                items(filteredList, key = { it.id }) { person ->
                    val relColor = when (person.relationship.lowercase()) {
                        "family", "owner" -> greenColor
                        "friend" -> blueColor
                        "colleague" -> purpleColor
                        else -> amberColor
                    }

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, CardBorderBrush),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingPerson = person }
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = relColor.copy(alpha = 0.15f),
                                        border = BorderStroke(1.dp, relColor)
                                    ) {
                                        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                                            Text(
                                                person.name.take(1).uppercase(),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = relColor
                                            )
                                        }
                                    }

                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(person.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = relColor.copy(alpha = 0.15f)
                                            ) {
                                                Text(
                                                    person.relationship.uppercase(),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = relColor
                                                )
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (person.isConsentGranted) greenColor.copy(alpha = 0.15f) else redColor.copy(alpha = 0.15f)
                                            ) {
                                                Text(
                                                    if (person.isConsentGranted) "CONSENT ACTIVE" else "CONSENT REVOKED",
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (person.isConsentGranted) greenColor else redColor
                                                )
                                            }
                                        }
                                    }
                                }

                                IconButton(onClick = { editingPerson = person }) {
                                    Text("✏️", fontSize = 14.sp)
                                }
                            }

                            if (person.notes != null) {
                                Text(
                                    person.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${person.sampleCount} biometric samples · ${person.permission} tier",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (person.isConsentGranted) {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    val body = JSONObject().put("id", person.id)
                                                    withContext(Dispatchers.IO) { api?.call("people/consent/revoke", body) }
                                                    statusNotice = "Consent revoked for ${person.name}."
                                                    refreshPeople()
                                                } catch (e: Exception) {
                                                    errorMsg = e.message ?: "Failed to revoke consent"
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("Revoke Consent", style = MaterialTheme.typography.labelSmall, color = redColor)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Enroll Person Wizard Sheet
    if (showEnrollSheet) {
        EnrollPersonSheet(
            onDismiss = { showEnrollSheet = false },
            onEnroll = { name, relationship, photoB64 ->
                scope.launch {
                    try {
                        val body = JSONObject()
                            .put("name", name)
                            .put("relationship", relationship)
                            .put("image", photoB64)
                        withContext(Dispatchers.IO) { api?.call("people/enroll", body) }
                        showEnrollSheet = false
                        statusNotice = "Enrolled $name successfully."
                        refreshPeople()
                    } catch (e: Exception) {
                        errorMsg = e.message ?: "Could not enroll face sample"
                    }
                }
            }
        )
    }

    // Edit Person Details Sheet
    editingPerson?.let { person ->
        EditPersonSheet(
            person = person,
            onDismiss = { editingPerson = null },
            onSave = { updatedRel, updatedNotes, updatedPerm ->
                scope.launch {
                    try {
                        val body = JSONObject()
                            .put("id", person.id)
                            .put("relationship", updatedRel)
                            .put("permission", updatedPerm)
                        if (updatedNotes.isNotEmpty()) body.put("notes", updatedNotes)
                        withContext(Dispatchers.IO) { api?.call("people/update", body) }
                        editingPerson = null
                        statusNotice = "Profile updated for ${person.name}."
                        refreshPeople()
                    } catch (e: Exception) {
                        errorMsg = e.message ?: "Could not update profile"
                    }
                }
            },
            onDelete = {
                scope.launch {
                    try {
                        val body = JSONObject().put("id", person.id)
                        withContext(Dispatchers.IO) { api?.call("people/delete", body) }
                        editingPerson = null
                        statusNotice = "Removed ${person.name} from registry."
                        refreshPeople()
                    } catch (e: Exception) {
                        errorMsg = e.message ?: "Could not delete person"
                    }
                }
            }
        )
    }
}

@Composable
private fun EnrollPersonSheet(
    onDismiss: () -> Unit,
    onEnroll: (name: String, relationship: String, photoB64: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("friend") }
    var capturedPhoto by remember { mutableStateOf<Bitmap?>(null) }
    var photoB64 by remember { mutableStateOf("") }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            capturedPhoto = bitmap
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            photoB64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }
    }

    CompanionSheet(onDismiss = onDismiss) {
        Text("Enroll New Identity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Person's Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Relationship", style = MaterialTheme.typography.labelMedium)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("family", "friend", "colleague", "guest").forEach { rel ->
                val isSelected = relationship == rel
                FilterChip(
                    selected = isSelected,
                    onClick = { relationship = rel },
                    label = { Text(rel.replaceFirstChar { it.uppercase() }) }
                )
            }
        }

        // Camera capture button or thumbnail preview
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, CardBorderBrush),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { cameraLauncher.launch(null) }
        ) {
            Column(
                Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (capturedPhoto != null) {
                    Image(
                        bitmap = capturedPhoto!!.asImageBitmap(),
                        contentDescription = "Face Sample",
                        modifier = Modifier.size(90.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Text("Tap to retake face photo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text("📸", fontSize = 28.sp)
                    Text("Capture Face Photo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Ensure clear lighting facing the camera", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Button(
            onClick = { onEnroll(name.trim(), relationship, photoB64) },
            enabled = name.isNotBlank() && photoB64.isNotEmpty(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Complete Biometric Enrollment")
        }
    }
}

@Composable
private fun EditPersonSheet(
    person: EnrolledPerson,
    onDismiss: () -> Unit,
    onSave: (relationship: String, notes: String, permission: String) -> Unit,
    onDelete: () -> Unit
) {
    var relationship by remember { mutableStateOf(person.relationship) }
    var notes by remember { mutableStateOf(person.notes.orEmpty()) }
    var permission by remember { mutableStateOf(person.permission) }

    CompanionSheet(onDismiss = onDismiss) {
        Text("Edit ${person.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        Text("Relationship", style = MaterialTheme.typography.labelMedium)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("owner", "family", "friend", "colleague", "guest").forEach { rel ->
                val isSelected = relationship == rel
                FilterChip(
                    selected = isSelected,
                    onClick = { relationship = rel },
                    label = { Text(rel.replaceFirstChar { it.uppercase() }) }
                )
            }
        }

        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Personal Notes & Memory") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onSave(relationship, notes.trim(), permission) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Save Changes")
            }
            OutlinedButton(
                onClick = onDelete,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Delete")
            }
        }
    }
}
