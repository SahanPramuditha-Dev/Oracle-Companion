package dev.oracle.companion

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class ActionReceipt(
    val id: String,
    val label: String,
    val sentAt: Long,
    val status: String,
    val respondedAt: Long = 0L
)

internal class ActionReceipts(private val preferences: SharedPreferences, private val key: String) {
    private val entries = MutableStateFlow(read())
    val history = entries.asStateFlow()

    @Synchronized
    fun begin(label: String): ActionReceipt {
        val receipt = ActionReceipt(UUID.randomUUID().toString(), label, System.currentTimeMillis(), "Sending")
        save((listOf(receipt) + entries.value).take(50))
        return receipt
    }

    @Synchronized
    fun finish(id: String, status: String, receivedReply: Boolean) {
        save(entries.value.map {
            if (it.id == id) it.copy(
                status = status,
                respondedAt = if (receivedReply) System.currentTimeMillis() else 0L
            ) else it
        })
    }

    private fun read(): List<ActionReceipt> {
        val array = runCatching { JSONArray(preferences.getString(key, "[]") ?: "[]") }
            .getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            val status = row.optString("status")
            ActionReceipt(row.optString("id"), row.optString("label"), row.optLong("sentAt"),
                if (status == "Sending") "Unable to confirm" else status, row.optLong("respondedAt"))
        }
    }

    private fun save(receipts: List<ActionReceipt>) {
        val array = JSONArray()
        receipts.forEach {
            array.put(JSONObject().put("id", it.id).put("label", it.label).put("sentAt", it.sentAt)
                .put("status", it.status).put("respondedAt", it.respondedAt))
        }
        preferences.edit().putString(key, array.toString()).apply()
        entries.value = receipts
    }
}

internal fun pcReceiptLabel(path: String, body: JSONObject): String? = when {
    path == "controls/commands" || path == "device/controls" -> body.optString("action")
        .takeIf { it.matches(Regex("[a-zA-Z0-9_.-]{1,64}")) }
        ?.replace(Regex("[_.-]+"), " ")?.replaceFirstChar { it.titlecase() }
    path == "controls/schedules" -> "Create schedule"
    path.startsWith("controls/schedules/") -> "Cancel schedule"
    path.startsWith("controls/routines/") && path.endsWith("/run") -> "Run routine"
    else -> null
}
