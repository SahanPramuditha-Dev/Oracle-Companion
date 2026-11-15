package dev.oracle.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class PcBattery(
    val percent: Int,
    val pluggedIn: Boolean,
    val charging: Boolean,
    val source: String
) {
    companion object {
        fun fromJson(obj: JSONObject?): PcBattery? {
            if (obj == null) return null
            return PcBattery(
                percent = obj.optInt("percent", 100),
                pluggedIn = obj.optBoolean("pluggedIn", true),
                charging = obj.optBoolean("charging", false),
                source = obj.optString("source", if (obj.optBoolean("pluggedIn", true)) "AC" else "Battery")
            )
        }
    }
}

internal data class PcStayAwake(
    val active: Boolean,
    val remainingSeconds: Int,
    val expiresAt: Long?
) {
    companion object {
        fun fromJson(obj: JSONObject?): PcStayAwake {
            if (obj == null) return PcStayAwake(false, 0, null)
            val expires = if (obj.has("expires_at") && !obj.isNull("expires_at")) obj.optLong("expires_at") else null
            return PcStayAwake(
                active = obj.optBoolean("active", false),
                remainingSeconds = obj.optInt("remaining_seconds", 0),
                expiresAt = expires
            )
        }
    }
}

internal data class PcAudioDevice(
    val name: String,
    val id: String,
    val isDefault: Boolean
)

internal data class PcAudioSession(
    val pid: Int,
    val name: String,
    val label: String,
    val volume: Int,
    val muted: Boolean
)

internal data class PcMedia(
    val title: String,
    val artist: String,
    val album: String,
    val isPlaying: Boolean,
    val source: String
) {
    companion object {
        fun fromJson(obj: JSONObject?): PcMedia {
            if (obj == null) return PcMedia("Media Player", "Windows Audio", "", false, "System")
            return PcMedia(
                title = obj.optString("title", "Media Player"),
                artist = obj.optString("artist", "Windows Audio"),
                album = obj.optString("album", ""),
                isPlaying = obj.optBoolean("is_playing", false),
                source = obj.optString("source", "System")
            )
        }
    }
}

internal data class PcDisplay(
    val brightness: Int,
    val brightnessSupported: Boolean,
    val monitorCount: Int
) {
    companion object {
        fun fromJson(obj: JSONObject?): PcDisplay {
            if (obj == null) return PcDisplay(100, false, 1)
            val info = obj.optJSONObject("info")
            return PcDisplay(
                brightness = obj.optInt("brightness", 100),
                brightnessSupported = obj.optBoolean("brightness_supported", false),
                monitorCount = info?.optInt("count", 1) ?: 1
            )
        }
    }
}

internal data class PcAppWindow(
    val hwnd: Long,
    val title: String,
    val process: String,
    val appName: String,
    val pid: Int,
    val memoryMb: Double,
    val isActive: Boolean,
    val isMinimized: Boolean,
    val isMaximized: Boolean
)

internal data class PcSchedule(
    val id: String,
    val action: String,
    val due: Long,
    val remainingSeconds: Long,
    val recurrence: String?,
    val status: String,
    val lastResult: String,
    val canCancel: Boolean
) {
    companion object {
        fun fromJson(obj: JSONObject?): PcSchedule? {
            if (obj == null) return null
            return PcSchedule(
                id = obj.optString("id", ""),
                action = obj.optString("action", "power.shutdown"),
                due = obj.optLong("due", obj.optLong("execute_at", 0L)),
                remainingSeconds = obj.optLong("remaining_seconds", 0L),
                recurrence = obj.optString("recurrence").takeIf { it.isNotEmpty() },
                status = obj.optString("status", "pending"),
                lastResult = obj.optString("last_result", ""),
                canCancel = obj.optBoolean("canCancel", true)
            )
        }
    }
}

internal data class PcRoutineStep(
    val action: String,
    val parameters: JSONObject,
    val delaySeconds: Int = 0
)

internal data class PcRoutine(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val stepsCount: Int,
    val lastRunAt: Long?,
    val lastStatus: String?
)

internal data class PcCapabilities(
    val canSleep: Boolean = false,
    val canHibernate: Boolean = false,
    val canShutdown: Boolean = true,
    val canRestart: Boolean = true,
    val canLock: Boolean = true,
    val canTurnOffDisplay: Boolean = true,
    val canStayAwake: Boolean = true,
    val canControlMasterVolume: Boolean = true,
    val canControlMic: Boolean = false,
    val canControlAppMixer: Boolean = true,
    val canControlBrightness: Boolean = false,
    val canManageWindows: Boolean = true,
    val canSendRemoteInput: Boolean = true,
    val canShareScreen: Boolean = true,
    val canScheduleActions: Boolean = true,
    val canRunRoutines: Boolean = true,
    val canControlOracleSystem: Boolean = true,
    val wakeOnLanConfigured: Boolean = false,
    val macAddresses: List<String> = emptyList()
) {
    companion object {
        fun fromJson(obj: JSONObject?): PcCapabilities {
            if (obj == null) return PcCapabilities()
            val macs = mutableListOf<String>()
            obj.optJSONArray("macAddresses")?.let { arr ->
                for (i in 0 until arr.length()) macs.add(arr.optString(i))
            }
            return PcCapabilities(
                canSleep = obj.optBoolean("canSleep", false),
                canHibernate = obj.optBoolean("canHibernate", false),
                canShutdown = obj.optBoolean("canShutdown", true),
                canRestart = obj.optBoolean("canRestart", true),
                canLock = obj.optBoolean("canLock", true),
                canTurnOffDisplay = obj.optBoolean("canTurnOffDisplay", true),
                canStayAwake = obj.optBoolean("canStayAwake", true),
                canControlMasterVolume = obj.optBoolean("canControlMasterVolume", true),
                canControlMic = obj.optBoolean("canControlMic", false),
                canControlAppMixer = obj.optBoolean("canControlAppMixer", true),
                canControlBrightness = obj.optBoolean("canControlBrightness", false),
                canManageWindows = obj.optBoolean("canManageWindows", true),
                canSendRemoteInput = obj.optBoolean("canSendRemoteInput", true),
                canShareScreen = obj.optBoolean("canShareScreen", true),
                canScheduleActions = obj.optBoolean("canScheduleActions", true),
                canRunRoutines = obj.optBoolean("canRunRoutines", true),
                canControlOracleSystem = obj.optBoolean("canControlOracleSystem", true),
                wakeOnLanConfigured = obj.optBoolean("wakeOnLanConfigured", false),
                macAddresses = macs
            )
        }
    }
}

internal data class PcOracleSystemStatus(
    val running: Boolean = false,
    val backend: Boolean = false,
    val frontend: Boolean = false,
    val frontendPort: Int? = null,
    val ollama: Boolean = false,
    val ollamaPort: Int? = null,
    val database: Boolean = false,
    val status: String = "stopped",
    val message: String = ""
) {
    companion object {
        fun fromJson(obj: JSONObject?): PcOracleSystemStatus {
            if (obj == null) return PcOracleSystemStatus()
            val frontPort = if (obj.has("frontendPort") && !obj.isNull("frontendPort")) obj.optInt("frontendPort") else null
            val oPort = if (obj.has("ollamaPort") && !obj.isNull("ollamaPort")) obj.optInt("ollamaPort") else null
            return PcOracleSystemStatus(
                running = obj.optBoolean("running", false),
                backend = obj.optBoolean("backend", false),
                frontend = obj.optBoolean("frontend", false),
                frontendPort = frontPort,
                ollama = obj.optBoolean("ollama", false),
                ollamaPort = oPort,
                database = obj.optBoolean("database", false),
                status = obj.optString("status", if (obj.optBoolean("running", false)) "running" else "stopped"),
                message = obj.optString("message", "")
            )
        }
    }
}

internal data class PcStateSnapshot(
    val revision: Int,
    val timestamp: Long,
    val uptimeSeconds: Long,
    val battery: PcBattery?,
    val stayAwake: PcStayAwake,
    val canSleep: Boolean,
    val canHibernate: Boolean,
    val volume: Int,
    val muted: Boolean,
    val micSupported: Boolean,
    val micPercent: Int,
    val micMuted: Boolean,
    val outputDevices: List<PcAudioDevice>,
    val appMixer: List<PcAudioSession>,
    val media: PcMedia,
    val display: PcDisplay,
    val runningWindowsCount: Int,
    val nextSchedule: PcSchedule?,
    val activeRoutineName: String?,
    val oracleSystem: PcOracleSystemStatus = PcOracleSystemStatus()
) {
    companion object {
        fun fromJson(obj: JSONObject?): PcStateSnapshot? {
            if (obj == null) return null
            val power = obj.optJSONObject("power")
            val audio = obj.optJSONObject("audio")
            val mic = audio?.optJSONObject("microphone")
            val disp = obj.optJSONObject("display")
            val media = obj.optJSONObject("media")
            val sched = obj.optJSONObject("schedules")
            val nextS = sched?.optJSONObject("next")
            val activeR = obj.optJSONObject("active_routine")
            val sysObj = obj.optJSONObject("oracle_system")

            val devicesList = mutableListOf<PcAudioDevice>()
            audio?.optJSONArray("output_devices")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val d = arr.optJSONObject(i) ?: continue
                    devicesList.add(PcAudioDevice(d.optString("name", "Audio Device"), d.optString("id", ""), d.optBoolean("is_default", false)))
                }
            }

            val mixerList = mutableListOf<PcAudioSession>()
            audio?.optJSONArray("app_mixer")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    mixerList.add(PcAudioSession(
                        pid = s.optInt("pid", 0),
                        name = s.optString("name", ""),
                        label = s.optString("label", s.optString("name", "")),
                        volume = s.optInt("volume", 100),
                        muted = s.optBoolean("muted", false)
                    ))
                }
            }

            return PcStateSnapshot(
                revision = obj.optInt("revision", 1),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis() / 1000),
                uptimeSeconds = power?.optLong("uptime_seconds", 0L) ?: 0L,
                battery = PcBattery.fromJson(power?.optJSONObject("battery")),
                stayAwake = PcStayAwake.fromJson(power?.optJSONObject("stay_awake")),
                canSleep = power?.optBoolean("can_sleep", false) ?: false,
                canHibernate = power?.optBoolean("can_hibernate", false) ?: false,
                volume = audio?.optInt("volume", 50) ?: 50,
                muted = audio?.optBoolean("muted", false) ?: false,
                micSupported = mic?.optBoolean("supported", false) ?: false,
                micPercent = mic?.optInt("percent", 80) ?: 80,
                micMuted = mic?.optBoolean("muted", false) ?: false,
                outputDevices = devicesList,
                appMixer = mixerList,
                media = PcMedia.fromJson(media),
                display = PcDisplay.fromJson(disp),
                runningWindowsCount = obj.optJSONObject("applications")?.optInt("count", 0) ?: 0,
                nextSchedule = PcSchedule.fromJson(nextS),
                activeRoutineName = activeR?.optString("name"),
                oracleSystem = PcOracleSystemStatus.fromJson(sysObj)
            )
        }
    }
}

internal data class PcActivityEvent(
    val id: String,
    val action: String,
    val status: String,
    val message: String,
    val timestamp: Long
)

// API Call Helpers
internal object RemoteControlsApi {
    suspend fun getCapabilities(api: CompanionApi): PcCapabilities = withContext(Dispatchers.IO) {
        val res = api.call("controls/capabilities")
        PcCapabilities.fromJson(res)
    }

    suspend fun getState(api: CompanionApi): PcStateSnapshot? = withContext(Dispatchers.IO) {
        val res = api.call("controls/state")
        PcStateSnapshot.fromJson(res)
    }

    suspend fun sendCommand(api: CompanionApi, action: String, parameters: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("action", action)
            .put("parameters", parameters)
            .put("request_id", java.util.UUID.randomUUID().toString())
        api.call("controls/commands", body)
    }

    suspend fun listSchedules(api: CompanionApi): List<PcSchedule> = withContext(Dispatchers.IO) {
        val res = api.call("controls/schedules")
        val list = mutableListOf<PcSchedule>()
        val arr = res.optJSONArray("schedules") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            PcSchedule.fromJson(item)?.let { list.add(it) }
        }
        list
    }

    suspend fun createSchedule(
        api: CompanionApi,
        action: String,
        mode: String,
        minutes: Int? = null,
        due: Long? = null,
        recurrence: String? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("action", action)
            .put("mode", mode)
            .put("parameters", JSONObject())
        if (minutes != null) body.put("minutes", minutes)
        if (due != null) body.put("due", due)
        if (recurrence != null) body.put("recurrence", recurrence)
        api.call("controls/schedules", body)
    }

    suspend fun cancelSchedule(api: CompanionApi, scheduleId: String): JSONObject = withContext(Dispatchers.IO) {
        api.call("controls/schedules/$scheduleId", JSONObject().put("_method", "DELETE"))
    }

    suspend fun listRoutines(api: CompanionApi): List<PcRoutine> = withContext(Dispatchers.IO) {
        val res = api.call("controls/routines")
        val list = mutableListOf<PcRoutine>()
        val arr = res.optJSONArray("routines") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val actions = r.optJSONArray("actions") ?: JSONArray()
            list.add(PcRoutine(
                id = r.optString("id", ""),
                name = r.optString("name", "Routine"),
                description = r.optString("description", ""),
                icon = r.optString("icon", "star"),
                stepsCount = actions.length(),
                lastRunAt = if (r.has("last_run_at") && !r.isNull("last_run_at")) r.optLong("last_run_at") else null,
                lastStatus = r.optString("last_status").takeIf { it.isNotEmpty() }
            ))
        }
        list
    }

    suspend fun runRoutine(api: CompanionApi, routineId: String): JSONObject = withContext(Dispatchers.IO) {
        api.call("controls/routines/$routineId/run", JSONObject())
    }

    suspend fun sendInput(api: CompanionApi, type: String, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject(params.toString()).put("type", type)
        api.call("controls/input", body)
    }

    suspend fun getScreenFrame(api: CompanionApi): JSONObject = withContext(Dispatchers.IO) {
        api.call("controls/screen/frame")
    }

    suspend fun toggleScreenSharing(api: CompanionApi, active: Boolean): JSONObject = withContext(Dispatchers.IO) {
        api.call("controls/screen/toggle", JSONObject().put("active", active))
    }

    suspend fun getRunningApps(api: CompanionApi): List<PcAppWindow> = withContext(Dispatchers.IO) {
        val res = api.call("controls/apps/running")
        val list = mutableListOf<PcAppWindow>()
        val arr = res.optJSONArray("apps") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            list.add(PcAppWindow(
                hwnd = w.optLong("hwnd", 0L),
                title = w.optString("title", "Window"),
                process = w.optString("process", ""),
                appName = w.optString("app_name", w.optString("process", "")),
                pid = w.optInt("pid", 0),
                memoryMb = w.optDouble("memory_mb", 0.0),
                isActive = w.optBoolean("is_active", false),
                isMinimized = w.optBoolean("is_minimized", false),
                isMaximized = w.optBoolean("is_maximized", false)
            ))
        }
        list
    }

    suspend fun setWindowState(api: CompanionApi, hwnd: Long, state: String): JSONObject = withContext(Dispatchers.IO) {
        api.call("controls/apps/window", JSONObject().put("hwnd", hwnd).put("state", state))
    }

    suspend fun terminateProcess(api: CompanionApi, pid: Int, force: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        api.call("controls/apps/terminate", JSONObject().put("pid", pid).put("force", force))
    }

    suspend fun readClipboard(api: CompanionApi): String = withContext(Dispatchers.IO) {
        val res = api.call("controls/clipboard")
        res.optString("text", "")
    }

    suspend fun writeClipboard(api: CompanionApi, text: String): JSONObject = withContext(Dispatchers.IO) {
        api.call("controls/clipboard", JSONObject().put("text", text))
    }

    suspend fun sendWol(api: CompanionApi, mac: String): JSONObject = withContext(Dispatchers.IO) {
        api.call("controls/wol/wake", JSONObject().put("mac", mac))
    }

    suspend fun getActivity(api: CompanionApi): List<PcActivityEvent> = withContext(Dispatchers.IO) {
        val res = api.call("controls/activity")
        val list = mutableListOf<PcActivityEvent>()
        val arr = res.optJSONArray("activity") ?: res.optJSONArray("events") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            list.add(PcActivityEvent(
                id = e.optString("id", java.util.UUID.randomUUID().toString()),
                action = e.optString("action", ""),
                status = e.optString("status", "completed"),
                message = e.optString("message", ""),
                timestamp = e.optLong("timestamp", System.currentTimeMillis() / 1000)
            ))
        }
        list
    }
}
