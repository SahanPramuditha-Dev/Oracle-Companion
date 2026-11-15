package dev.oracle.companion

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.net.ConnectivityManager
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.*

internal enum class CameraMode(val title: String, val icon: String, val endpoint: String?) {
    ASK_AI("Ask Oracle", "✨", "vision"),
    DETECT("Live Detect", "🎯", "vision/detect"),
    READ_TEXT("Read Text", "📄", "vision/read"),
    BARCODE("QR / Code", "⚡", "barcode"),
    PEOPLE("People", "👤", "vision/people"),
    PRODUCT("Inspect", "🏷️", "vision/product")
}

private data class Mark(
    val rawClass: String,
    val label: String,
    val x: Float, val y: Float, val w: Float, val h: Float,
    val id: Long = 0, val seen: Long = 0, val face: Boolean = false,
    val confidence: Float = 0f
) {
    val centerX get() = x + w / 2f
    val centerY get() = y + h / 2f

    fun overlap(b: Mark): Float {
        val common = (min(x + w, b.x + b.w) - max(x, b.x)).coerceAtLeast(0f) *
                (min(y + h, b.y + b.h) - max(y, b.y)).coerceAtLeast(0f)
        return common / (w * h + b.w * b.h - common).coerceAtLeast(.000001f)
    }

    fun centerDistance(b: Mark): Float = hypot(centerX - b.centerX, centerY - b.centerY)

    fun isSameSubject(b: Mark): Boolean {
        if (face != b.face) return false
        val iou = overlap(b)
        if (iou >= 0.18f) return true
        val dist = centerDistance(b)
        return dist < 0.22f && (rawClass.equals(b.rawClass, ignoreCase = true) || rawClass.isBlank() || b.rawClass.isBlank())
    }

    fun smooth(previous: Mark) = copy(
        id = previous.id,
        x = .65f * x + .35f * previous.x,
        y = .65f * y + .35f * previous.y,
        w = .65f * w + .35f * previous.w,
        h = .65f * h + .35f * previous.h
    )

    fun crop(image: Bitmap): Bitmap {
        val left = (x * image.width).toInt().coerceIn(0, image.width - 1)
        val top = (y * image.height).toInt().coerceIn(0, image.height - 1)
        val right = ((x + w) * image.width).toInt().coerceIn(left + 1, image.width)
        val bottom = ((y + h) * image.height).toInt().coerceIn(top + 1, image.height)
        return Bitmap.createBitmap(image, left, top, right - left, bottom - top)
    }
}

private class RenderMark(
    val id: Long,
    val rawClass: String,
    var label: String,
    val isFace: Boolean,
    var x: Float,
    var y: Float,
    var w: Float,
    var h: Float,
    var targetX: Float,
    var targetY: Float,
    var targetW: Float,
    var targetH: Float,
    var alpha: Float = 0f,
    var targetAlpha: Float = 1f
) {
    fun updateTarget(mark: Mark) {
        targetX = mark.x
        targetY = mark.y
        targetW = mark.w
        targetH = mark.h
        label = mark.label
        targetAlpha = 1f
    }

    fun step() {
        x += (targetX - x) * 0.25f
        y += (targetY - y) * 0.25f
        w += (targetW - w) * 0.25f
        h += (targetH - h) * 0.25f
        alpha += (targetAlpha - alpha) * 0.20f
    }
}

private fun marks(json: JSONObject, image: Bitmap, faces: Boolean): List<Mark> {
    val list = json.optJSONArray(if (faces) "identified" else "detections") ?: return emptyList()
    val now = SystemClock.elapsedRealtime()
    return (0 until min(list.length(), 40)).mapNotNull { i ->
        val row = list.optJSONObject(i) ?: return@mapNotNull null
        val conf = row.optDouble("confidence", 0.0).toFloat()
        if (!faces && conf < 0.35f) return@mapNotNull null
        val box = row.optJSONArray("box") ?: return@mapNotNull null
        val x = box.optDouble(0).toFloat() / image.width
        val y = box.optDouble(1).toFloat() / image.height
        val w = box.optDouble(2).toFloat() / image.width
        val h = box.optDouble(3).toFloat() / image.height
        if (!(x.isFinite() && y.isFinite() && w > 0 && h > 0 && w.isFinite() && h.isFinite())) return@mapNotNull null
        val left = x.coerceIn(0f, 1f)
        val top = y.coerceIn(0f, 1f)
        val width = (x + w).coerceIn(0f, 1f) - left
        val height = (y + h).coerceIn(0f, 1f) - top
        if (width <= 0 || height <= 0) return@mapNotNull null

        val rawCls = if (!faces) row.optString("class", "object").lowercase() else "face"
        val label = if (!faces) {
            val name = rawCls.replaceFirstChar { it.uppercase() }
            if (conf > 0.05f) "$name · ${(conf * 100).roundToInt()}%" else name
        } else if (row.optBoolean("matched")) {
            listOf(
                row.optString("name", "Enrolled Person"),
                row.optString("permission").takeUnless { it == "null" }.orEmpty()
            ).filter { it.isNotBlank() }.joinToString(" · ")
        } else "Unrecognized Face"

        Mark(rawCls, label.take(75), left, top, width, height, seen = now, face = faces, confidence = conf)
    }
}

private fun cameraUpload(image: Bitmap, live: Boolean = false): JSONObject {
    val bytes = ByteArrayOutputStream()
    for (quality in if (live) listOf(64, 48, 32, 18) else listOf(76, 58, 42, 26, 12)) {
        bytes.reset()
        image.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
        if (bytes.size() in 1..128000)
            return JSONObject().put("image", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
    }
    error("Photo is too large.")
}

private fun decodeCode(image: Bitmap): Result? {
    val pixels = IntArray(image.width * image.height)
    image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
    val luminance = RGBLuminanceSource(image.width, image.height, pixels)
    val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    return listOf(luminance, luminance.invert()).firstNotNullOfOrNull { source ->
        try {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints)
        } catch (_: ReaderException) {
            null
        }
    }
}

@Suppress("DEPRECATION")
private class LivePreview(
    context: Context, private val id: Int,
    private val update: (Boolean, String) -> Unit
) : TextureView(context), TextureView.SurfaceTextureListener {
    private var camera: Camera? = null
    private var orientation = 0
    private var front = false
    private var sensor = 0
    val hasTorch
        get() = runCatching {
            Camera.Parameters.FLASH_MODE_TORCH in camera?.parameters?.supportedFlashModes.orEmpty()
        }.getOrDefault(false)
    val maxZoom
        get() = runCatching {
            camera?.parameters?.let { if (it.isZoomSupported) it.maxZoom else 0 } ?: 0
        }.getOrDefault(0)

    init {
        surfaceTextureListener = this
    }

    private fun orient() {
        val degrees = (display?.rotation ?: 0) * 90
        val next = if (front) (360 - (sensor + degrees) % 360) % 360 else (sensor - degrees + 360) % 360
        camera?.setDisplayOrientation(next)
        orientation = next
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        try {
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(id, info)
            front = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
            sensor = info.orientation
            val active = Camera.open(id)
            camera = active
            val p = active.parameters
            p.supportedPreviewSizes.minByOrNull {
                abs(it.width.toDouble() / it.height - 4.0 / 3) * 5000 + abs(it.width - 1024)
            }?.let { p.setPreviewSize(it.width, it.height) }
            if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in p.supportedFocusModes.orEmpty())
                p.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            active.parameters = p
            orient()
            active.setPreviewTexture(surface)
            active.startPreview()
            update(true, "")
        } catch (_: Exception) {
            close()
            update(false, "Camera unavailable. Close it and try again.")
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        runCatching { orient() }
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        close()
        return true
    }

    fun close() {
        val active = camera
        camera = null
        runCatching { active?.release() }
    }

    fun capture(maxSide: Int): Bitmap? {
        if (camera == null || !isAvailable || width <= 0 || height <= 0) return null
        val scale = maxSide.toFloat() / max(width, height)
        return getBitmap(
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1)
        )
    }

    fun torch(on: Boolean): Boolean = runCatching {
        val active = camera ?: return false
        val p = active.parameters
        p.flashMode = if (on) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
        active.parameters = p
        true
    }.getOrDefault(false)

    fun zoom(value: Int) {
        runCatching {
            val active = camera ?: return
            val p = active.parameters
            if (p.isZoomSupported) {
                p.zoom = value.coerceIn(0, p.maxZoom)
                active.parameters = p
            }
        }
    }

    fun focus(u: Float, v: Float): Boolean = runCatching {
        val active = camera ?: return false
        val p = active.parameters
        if (Camera.Parameters.FOCUS_MODE_AUTO !in p.supportedFocusModes.orEmpty()) return false
        val a = if (front) 1 - u else u
        val (x, y) = when (orientation) {
            90 -> v to (1 - a)
            180 -> (1 - a) to (1 - v)
            270 -> (1 - v) to a
            else -> a to v
        }
        val l = (x * 2000 - 1100).toInt().coerceIn(-1000, 800)
        val t = (y * 2000 - 1100).toInt().coerceIn(-1000, 800)
        active.cancelAutoFocus()
        val original = p.focusMode
        p.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
        if (p.maxNumFocusAreas > 0) p.focusAreas = listOf(Camera.Area(Rect(l, t, l + 200, t + 200), 900))
        active.parameters = p
        active.autoFocus { _, device ->
            if (camera === device) runCatching {
                val updated = device.parameters
                updated.focusMode = original
                if (updated.maxNumFocusAreas > 0) updated.focusAreas = null
                device.parameters = updated
            }
        }
        true
    }.getOrDefault(false)
}

@Composable
internal fun LiveCameraPane(
    api: CompanionApi?,
    online: Boolean,
    onNavigateChat: (String) -> Unit = {},
    onCapture: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = LocalDarkTheme.current
    var cameras by remember { mutableStateOf(runCatching { (0 until Camera.getNumberOfCameras()).toList() }.getOrDefault(emptyList())) }
    fun refreshCameras(): List<Int> {
        val list = runCatching { (0 until Camera.getNumberOfCameras()).toList() }.getOrDefault(emptyList())
        cameras = list
        return list
    }
    var cameraIndex by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<LivePreview?>(null) }
    var opened by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var currentMode by remember { mutableStateOf(CameraMode.ASK_AI) }
    var liveFaces by remember { mutableStateOf(false) }
    var faceLabelsUntil by remember { mutableLongStateOf(0L) }
    var auto by remember { mutableStateOf(false) }
    var interval by remember { mutableLongStateOf(1500L) }
    var scan by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableIntStateOf(0) }
    var torch by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var resultTitle by remember { mutableStateOf("") }
    var boxes by remember { mutableStateOf(emptyList<Mark>()) }
    var exact by remember { mutableStateOf(emptyMap<Long, Mark>()) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var serial by remember { mutableLongStateOf(0) }
    var action by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var facePrompt by remember { mutableStateOf(false) }
    var liveFacePrompt by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var voicePhoto by remember { mutableStateOf<Bitmap?>(null) }
    var galleryPhoto by remember { mutableStateOf<Bitmap?>(null) }
    var customPrompt by remember { mutableStateOf("") }
    var speaker by remember { mutableStateOf<TextToSpeech?>(null) }
    var speakerReady by remember { mutableStateOf(false) }
    val requestGate = remember { kotlinx.coroutines.sync.Mutex() }

    // Auto-open camera if permission is already granted on launch
    LaunchedEffect(Unit) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            val list = refreshCameras()
            if (list.isNotEmpty()) {
                opened = true
                status = ""
            }
        }
    }

    // Reticle animation for tap-to-focus
    var tapPoint by remember { mutableStateOf<Offset?>(null) }
    val focusPulse = remember { Animatable(0f) }

    val renderMarks = remember { mutableMapOf<Long, RenderMark>() }
    var renderTick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(opened, ready) {
        if (!opened) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { frameTime ->
                renderTick = frameTime
            }
        }
    }

    fun clearMarks() {
        faceLabelsUntil = 0L
        boxes = emptyList()
        exact = emptyMap()
        frame = null
        selected = null
        renderMarks.clear()
    }

    fun close() {
        preview?.close()
        opened = false
        ready = false
        auto = false
        liveFaces = false
        scan = 0
        clearMarks()
        torch = false
        zoom = 0
        facePrompt = false
        liveFacePrompt = false
        galleryPhoto = null
        status = ""
        result = ""
        resultTitle = ""
        if (!listening) {
            action?.cancel()
            speaker?.stop()
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val list = refreshCameras()
            if (list.isNotEmpty()) {
                opened = true
                status = ""
            } else {
                status = "No cameras detected on this device."
            }
        } else {
            opened = false
            status = "Camera access denied. Choose a photo from gallery."
        }
    }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use {
                            android.graphics.BitmapFactory.decodeStream(it)
                        }
                    }
                    if (bitmap != null) {
                        galleryPhoto = bitmap
                        opened = true
                        clearMarks()
                        result = ""
                        status = "Photo loaded from gallery. Tap shutter to analyze."
                        onCapture(bitmap)
                    }
                } catch (_: Exception) {
                    status = "Could not load selected photo."
                }
            }
        }
    }

    DisposableEffect(context) {
        val lifecycle = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) close()
        }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            preview?.close()
            speaker?.shutdown()
        }
    }

    fun currentImage(size: Int = 960, useSelection: Boolean = true): Bitmap? {
        val gallery = galleryPhoto
        if (gallery != null) return gallery
        return if (useSelection && selected != null) frame?.let { exact[selected]?.crop(it) } ?: preview?.capture(size)
        else preview?.capture(size)
    }

    fun perform(mode: CameraMode, prompt: String = "") {
        val path = mode.endpoint ?: return
        if (busy) return
        val image = currentImage() ?: run {
            status = "Hold camera steady or choose a photo."
            return
        }
        if (path != "barcode" && (!online || api == null)) {
            status = "Oracle Central is offline."
            return
        }
        auto = false
        liveFaces = false
        scan = 0
        clearMarks()
        result = ""
        resultTitle = mode.title
        busy = true
        status = "Analyzing with Oracle neural vision…"

        action = scope.launch {
            try {
                val payload = withContext(Dispatchers.IO) {
                    if (path == "barcode") {
                        val code = decodeCode(image)
                        JSONObject().put(
                            "text",
                            code?.let { format -> "${format.barcodeFormat}: ${format.text.take(4000)}" }.orEmpty()
                        )
                    } else {
                        requestGate.lock()
                        try {
                            requireNotNull(api).call(path, cameraUpload(image).apply {
                                if (prompt.isNotBlank()) put("prompt", prompt)
                            })
                        } finally {
                            requestGate.unlock()
                        }
                    }
                }
                ensureActive()
                status = ""
                when (path) {
                    "vision/people" -> {
                        boxes = marks(payload, image, true).map { it.copy(id = ++serial) }
                        exact = boxes.associateBy { it.id }
                        frame = image
                        result = if (boxes.isEmpty()) "No face detected in frame." else boxes.joinToString("\n") { it.label }
                    }
                    "vision/read" -> {
                        result = payload.optString("text").take(12000).ifBlank { "No readable text detected in this frame." }
                    }
                    "barcode" -> {
                        result = payload.optString("text").ifBlank { "No QR or barcode found. Move closer or align with center." }
                    }
                    "vision/product" -> {
                        val state = payload.optString("product_status")
                        val product = payload.optJSONObject("product")
                        val verified = state == "verified" && payload.optJSONObject("knowledge")?.optBoolean("verified") == true
                        val name = product?.optString("name")?.takeUnless { it == "null" }.orEmpty()
                        val kind = payload.optString("object_type").takeUnless { it == "null" }.orEmpty()
                        result = listOf(
                            if (verified) "✓ Verified Product" else "Unverified Product",
                            name.takeIf { it.isNotBlank() }?.let { if (verified) it else "Possible match: $it" }.orEmpty(),
                            kind.takeIf { it.isNotBlank() }?.let { "Category: $it" }.orEmpty(),
                            if (payload.optBoolean("needs_more_evidence")) "Align label or barcode clearly." else ""
                        ).filter { it.isNotBlank() }.distinct().joinToString("\n")
                            .ifBlank { "No matching product found. Try scanning barcode or label." }
                    }
                    else -> {
                        result = payload.optString("answer").ifBlank { "Oracle analyzed the image successfully." }
                        if (!speakerReady || speaker?.speak(
                                result.take(TextToSpeech.getMaxSpeechInputLength()),
                                TextToSpeech.QUEUE_FLUSH, null, "camera-answer"
                            ) != TextToSpeech.SUCCESS
                        ) {
                            // Audio playback silent
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                status = if (failure is ApiFailure && failure.status == 404)
                    "Update Oracle Central to enable ${mode.title}."
                else if (failure is ApiFailure) failure.message.orEmpty().ifBlank { "Analysis unavailable." }
                else "Vision request failed. Check connection."
            } finally {
                busy = false
            }
        }
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
        listening = false
        val image = voicePhoto
        voicePhoto = null
        val question = response.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (image != null) opened = true
        if (response.resultCode == android.app.Activity.RESULT_OK && image != null && question.isNotBlank())
            perform(CameraMode.ASK_AI, question.take(400))
    }

    LaunchedEffect(faceLabelsUntil) {
        if (faceLabelsUntil > 0L) {
            delay((faceLabelsUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            clearMarks()
            if (liveFaces) status = "Awaiting fresh face match…"
        }
    }

    LaunchedEffect(opened, ready, online, api, auto, liveFaces, interval, scan) {
        if (!opened || !ready || !online || api == null || (!auto && !liveFaces && scan == 0)) return@LaunchedEffect
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val continuous = auto || liveFaces
        val recognize = liveFaces
        val path = if (recognize) "vision/people" else "vision/detect"
        val cadence = if (recognize) interval.coerceAtLeast(1000L) else interval
        fun localUnmetered() = !api.isRemote && connectivity?.isActiveNetworkMetered == false
        fun pauseLive() {
            auto = false
            liveFaces = false
            clearMarks()
            status = "Live scanning paused on metered or remote connection."
        }
        do {
            if (continuous && !localUnmetered()) {
                pauseLive()
                break
            }
            val started = SystemClock.elapsedRealtime()
            val image = preview?.capture(if (recognize) 720 else 640) ?: break
            scanning = true
            try {
                val payload = withContext(Dispatchers.IO) {
                    requestGate.lock()
                    try {
                        ensureActive()
                        if (continuous && !localUnmetered()) null
                        else api.call(path, cameraUpload(image, live = continuous))
                    } finally {
                        requestGate.unlock()
                    }
                }
                ensureActive()
                if (payload == null || (continuous && !localUnmetered())) {
                    pauseLive()
                    break
                }
                val elapsed = SystemClock.elapsedRealtime() - started
                val fresh = if (recognize && elapsed > 3000L) emptyList() else marks(payload, image, recognize)
                val available = boxes.filter { it.face == recognize && SystemClock.elapsedRealtime() - it.seen < max(3500L, cadence * 2) }.toMutableList()
                val originals = linkedMapOf<Long, Mark>()
                boxes = fresh.map { mark ->
                    val previous = available.filter { it.isSameSubject(mark) }
                        .minByOrNull { it.centerDistance(mark) }
                    val tracked = if (previous == null) mark.copy(id = ++serial) else {
                        available.remove(previous)
                        mark.smooth(previous)
                    }
                    originals[tracked.id] = mark
                    tracked
                }
                exact = originals
                frame = image
                if (selected !in originals.keys) selected = null
                if (recognize && boxes.isNotEmpty())
                    faceLabelsUntil = started + (cadence * 2).coerceIn(3000L, 4500L)
                else faceLabelsUntil = 0L
                status = when {
                    recognize && elapsed > 3000L -> "Host PC response slow. Tap Shutter for still."
                    recognize && boxes.isEmpty() -> "Scanning for faces…"
                    recognize -> ""
                    boxes.isEmpty() -> "Scanning environment…"
                    else -> "${boxes.size} objects detected · Tap to select"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                clearMarks()
                auto = false
                liveFaces = false
                status = if (failure is ApiFailure && failure.status == 404)
                    "Update Oracle Central for live detection."
                else "Live detection paused. Tap shutter to retry."
            } finally {
                scanning = false
            }
            scan = 0
            if (continuous) delay((cadence - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(350L))
        } while ((auto || liveFaces) && opened)
    }

    LaunchedEffect(online) {
        if (!online) {
            auto = false
            liveFaces = false
            scan = 0
            clearMarks()
        }
    }

    val pulseScale = 1.05f

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (!opened) {
            // ==========================================
            // PERMISSION REQUIRED STATE: SLEEK NATIVE CARD
            // ==========================================
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, CardBorderBrush)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(68.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("📷", fontSize = 28.sp)
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "Camera Permission Needed",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Enable camera permission to detect objects, transcribe text, and run real-time neural vision models directly on your device.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            modifier = Modifier.weight(1.3f).height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            onClick = { permission.launch(Manifest.permission.CAMERA) }
                        ) {
                            Text("Enable Camera", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            modifier = Modifier.weight(0.9f).height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, CardBorderBrush),
                            onClick = { galleryPicker.launch("image/*") }
                        ) {
                            Text("Use Gallery", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else {
            // ==========================================
            // ACTIVE LIVE CAMERA HUD & VIEWFINDER
            // ==========================================
            val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val frozen = frame?.takeIf { !liveFaces && boxes.any { mark -> mark.face } }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = Color.Black,
                border = BorderStroke(1.5.dp, CardBorderBrush)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (landscape) 4f / 3f else 3f / 4f)
                        .clip(RoundedCornerShape(26.dp))
                ) {
                    if (galleryPhoto != null) {
                        androidx.compose.foundation.Image(
                            bitmap = galleryPhoto!!.asImageBitmap(),
                            contentDescription = "Selected photo",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        // TextureView Camera Preview
                        key(cameraIndex) {
                            AndroidView(
                                factory = { ctx ->
                                    LivePreview(ctx, cameras[cameraIndex]) { active, message ->
                                        ready = active
                                        if (message.isNotEmpty()) status = message
                                    }.also { preview = it }
                                },
                                modifier = Modifier.fillMaxSize(),
                                onRelease = { it.close() },
                                update = {}
                            )
                        }

                        if (frozen != null) {
                            androidx.compose.foundation.Image(
                                bitmap = frozen.asImageBitmap(),
                                contentDescription = "Frozen frame",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.FillBounds
                            )
                        }
                    }

                    // Cyber HUD Overlay Canvas: Fluid 60fps tracking reticle + Bounding boxes
                    if (galleryPhoto == null) {
                        Canvas(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(renderMarks, preview) {
                                detectTapGestures { point ->
                                    val u = point.x / size.width
                                    val v = point.y / size.height
                                    val hit = renderMarks.values.filter { it.alpha > 0.25f && u in it.x..(it.x + it.w) && v in it.y..(it.y + it.h) }
                                        .minByOrNull { it.w * it.h }?.id
                                    selected = if (hit == selected) null else hit
                                    if (hit == null && frozen == null) {
                                        preview?.focus(u, v)
                                        tapPoint = point
                                        scope.launch {
                                            focusPulse.snapTo(1f)
                                            focusPulse.animateTo(0f, tween(800, easing = FastOutSlowInEasing))
                                            tapPoint = null
                                        }
                                    }
                                }
                            }
                    ) {
                        // 60 FPS tick trigger
                        val tick = renderTick

                        // Sync RenderMarks with current boxes
                        val currentIds = boxes.map { it.id }.toSet()
                        for (box in boxes) {
                            val rm = renderMarks[box.id]
                            if (rm != null) {
                                rm.updateTarget(box)
                            } else {
                                renderMarks[box.id] = RenderMark(
                                    id = box.id,
                                    rawClass = box.rawClass,
                                    label = box.label,
                                    isFace = box.face,
                                    x = box.x, y = box.y, w = box.w, h = box.h,
                                    targetX = box.x, targetY = box.y, targetW = box.w, targetH = box.h,
                                    alpha = 0f, targetAlpha = 1f
                                )
                            }
                        }
                        val itMarks = renderMarks.entries.iterator()
                        while (itMarks.hasNext()) {
                            val entry = itMarks.next()
                            if (entry.key !in currentIds) {
                                entry.value.targetAlpha = 0f
                                if (entry.value.alpha <= 0.02f) itMarks.remove()
                            }
                        }

                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            textSize = 11.5.dp.toPx()
                            isFakeBoldText = true
                            color = android.graphics.Color.WHITE
                            setShadowLayer(4f, 0f, 1f, android.graphics.Color.BLACK)
                        }

                        // Draw each tracked subject with smooth 60fps interpolation
                        for (rm in renderMarks.values) {
                            rm.step()
                            if (rm.alpha <= 0.02f) continue
                            val currentAlpha = rm.alpha

                            val isTargetSelected = selected == rm.id
                            val baseColor = when {
                                isTargetSelected -> Color(0xff58dfa4) // Cyber Mint Lock
                                rm.isFace -> Color(0xffffb703) // Golden Amber
                                else -> Color(0xff38bdf8) // Sky Cyan Tech
                            }

                            val left = rm.x * size.width
                            val top = rm.y * size.height
                            val width = rm.w * size.width
                            val height = rm.h * size.height

                            // 1. Holographic Energy Field Fill
                            drawRoundRect(
                                color = baseColor.copy(alpha = (if (isTargetSelected) 0.12f else 0.05f) * currentAlpha),
                                topLeft = Offset(left, top),
                                size = Size(width, height),
                                cornerRadius = CornerRadius(8.dp.toPx())
                            )

                            // 2. Soft Outer Glow & Clean Boundary
                            drawRoundRect(
                                color = baseColor.copy(alpha = (if (isTargetSelected) 0.40f else 0.22f) * currentAlpha),
                                topLeft = Offset(left, top),
                                size = Size(width, height),
                                cornerRadius = CornerRadius(8.dp.toPx()),
                                style = Stroke(3.dp.toPx())
                            )
                            drawRoundRect(
                                color = baseColor.copy(alpha = 0.85f * currentAlpha),
                                topLeft = Offset(left, top),
                                size = Size(width, height),
                                cornerRadius = CornerRadius(8.dp.toPx()),
                                style = Stroke(1.5.dp.toPx())
                            )

                            // 3. Cyber Corner Brackets ┌ ┐ └ ┘
                            val bracketLen = min(width, height) * 0.24f
                            val strokeW = (if (isTargetSelected) 4.dp else 3.dp).toPx()
                            // Top-left
                            drawLine(baseColor.copy(alpha = currentAlpha), Offset(left, top), Offset(left + bracketLen, top), strokeW, StrokeCap.Round)
                            drawLine(baseColor.copy(alpha = currentAlpha), Offset(left, top), Offset(left, top + bracketLen), strokeW, StrokeCap.Round)
                            // Top-right
                            drawLine(baseColor.copy(alpha = currentAlpha), Offset(left + width, top), Offset(left + width - bracketLen, top), strokeW, StrokeCap.Round)
                            drawLine(baseColor.copy(alpha = currentAlpha), Offset(left + width, top), Offset(left + width, top + bracketLen), strokeW, StrokeCap.Round)
                            // Bottom-left
                            drawLine(baseColor.copy(alpha = currentAlpha), Offset(left, top + height), Offset(left + bracketLen, top + height), strokeW, StrokeCap.Round)
                            drawLine(baseColor.copy(alpha = currentAlpha), Offset(left, top + height), Offset(left, top + height - bracketLen), strokeW, StrokeCap.Round)
                            // Bottom-right
                            drawLine(baseColor.copy(alpha = currentAlpha), Offset(left + width, top + height), Offset(left + width - bracketLen, top + height), strokeW, StrokeCap.Round)
                            drawLine(baseColor.copy(alpha = currentAlpha), Offset(left + width, top + height), Offset(left + width, top + height - bracketLen), strokeW, StrokeCap.Round)

                            // 4. Animated Holographic Laser Scanline (moves up & down)
                            val sweepTime = ((tick / 1_000_000L) % 1800L) / 1800f
                            val sweepCos = 0.5f - 0.5f * kotlin.math.cos(sweepTime * 2 * kotlin.math.PI.toFloat())
                            val laserY = top + height * sweepCos
                            drawLine(
                                baseColor.copy(alpha = (if (isTargetSelected) 0.85f else 0.55f) * currentAlpha),
                                Offset(left + 6.dp.toPx(), laserY),
                                Offset(left + width - 6.dp.toPx(), laserY),
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round
                            )

                            // 5. Center Precision Reticle (+)
                            val cx = left + width / 2f
                            val cy = top + height / 2f
                            val crossArm = 5.dp.toPx()
                            val crossGap = 4.dp.toPx()
                            val reticleColor = baseColor.copy(alpha = 0.65f * currentAlpha)
                            drawLine(reticleColor, Offset(cx - crossGap - crossArm, cy), Offset(cx - crossGap, cy), 1.5.dp.toPx(), StrokeCap.Round)
                            drawLine(reticleColor, Offset(cx + crossGap, cy), Offset(cx + crossGap + crossArm, cy), 1.5.dp.toPx(), StrokeCap.Round)
                            drawLine(reticleColor, Offset(cx, cy - crossGap - crossArm), Offset(cx, cy - crossGap), 1.5.dp.toPx(), StrokeCap.Round)
                            drawLine(reticleColor, Offset(cx, cy + crossGap), Offset(cx, cy + crossGap + crossArm), 1.5.dp.toPx(), StrokeCap.Round)
                            drawCircle(baseColor.copy(alpha = 0.8f * currentAlpha), radius = 1.8.dp.toPx(), center = Offset(cx, cy))

                            // 6. Cyber HUD Glass Pill Badge
                            val labelStr = if (isTargetSelected) "${rm.label} · LOCKED" else rm.label
                            val textWidth = paint.measureText(labelStr)
                            val badgeH = 22.dp.toPx()
                            val badgeW = textWidth + 24.dp.toPx()
                            val badgeY = (top - badgeH - 4.dp.toPx()).coerceAtLeast(8.dp.toPx())
                            val badgeX = left.coerceIn(8.dp.toPx(), (size.width - badgeW - 8.dp.toPx()).coerceAtLeast(8.dp.toPx()))

                            // Badge Frosted Fill & Border
                            drawRoundRect(
                                color = Color(0xee080e1a).copy(alpha = currentAlpha * 0.95f),
                                topLeft = Offset(badgeX, badgeY),
                                size = Size(badgeW, badgeH),
                                cornerRadius = CornerRadius(6.dp.toPx())
                            )
                            drawRoundRect(
                                color = baseColor.copy(alpha = 0.7f * currentAlpha),
                                topLeft = Offset(badgeX, badgeY),
                                size = Size(badgeW, badgeH),
                                cornerRadius = CornerRadius(6.dp.toPx()),
                                style = Stroke(1.dp.toPx())
                            )

                            // Pulsing Badge Indicator Dot
                            drawCircle(
                                color = baseColor.copy(alpha = currentAlpha),
                                radius = 3.dp.toPx(),
                                center = Offset(badgeX + 10.dp.toPx(), badgeY + badgeH / 2f)
                            )

                            // Text
                            drawContext.canvas.nativeCanvas.drawText(
                                labelStr,
                                badgeX + 18.dp.toPx(),
                                badgeY + badgeH - 6.5.dp.toPx(),
                                paint
                            )
                        }

                        // Tap-to-focus ring
                        tapPoint?.let { pt ->
                            val radius = (28.dp.toPx()) * (1f + (1f - focusPulse.value) * 0.4f)
                            drawCircle(
                                color = Color(0xff58dfa4).copy(alpha = focusPulse.value),
                                radius = radius,
                                center = pt,
                                style = Stroke(2.5.dp.toPx())
                            )
                            drawCircle(
                                color = Color(0xff58dfa4).copy(alpha = focusPulse.value * 0.7f),
                                radius = 4.dp.toPx(),
                                center = pt
                            )
                        }
                    }
                    }

                    // ==========================================
                    // TOP FLOATING GLASS HUD OVERLAY
                    // ==========================================
                    if (galleryPhoto != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                                .align(Alignment.TopCenter),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Color(0xaa0f172a),
                                border = BorderStroke(1.dp, Color(0x33ffffff))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("🖼", fontSize = 12.sp)
                                    Text("PHOTO STUDIO", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = Color(0xaa0f172a),
                                border = BorderStroke(1.dp, Color(0x33ffffff)),
                                modifier = Modifier.clickable {
                                    galleryPhoto = null
                                    result = ""
                                    status = ""
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("✕", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("Live Camera", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                                .align(Alignment.TopCenter),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                        // Flash / Torch toggle
                        Surface(
                            shape = CircleShape,
                            color = if (torch) Color(0xffffb703) else Color(0x88000000),
                            border = BorderStroke(1.dp, if (torch) Color(0xffffd166) else Color(0x44ffffff)),
                            modifier = Modifier
                                .size(40.dp)
                                .clickable(enabled = ready && preview?.hasTorch == true) {
                                    if (preview?.torch(!torch) == true) torch = !torch
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "⚡",
                                    fontSize = 16.sp,
                                    color = if (torch) Color.Black else Color.White
                                )
                            }
                        }

                        // Status pill badge
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xaa0f172a),
                            border = BorderStroke(1.dp, Color(0x33ffffff))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(
                                            if (scanning) Color(0xffffb703)
                                            else if (ready && online) Color(0xff58dfa4)
                                            else Color(0xffef4444),
                                            CircleShape
                                        )
                                )
                                Text(
                                    when {
                                        scanning -> "SCANNING"
                                        auto -> "LIVE DETECT"
                                        liveFaces -> "LIVE FACE ID"
                                        ready -> "LIVE"
                                        else -> "CONNECTING"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        // Right: Flip Camera & Close
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (cameras.size > 1) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0x88000000),
                                    border = BorderStroke(1.dp, Color(0x44ffffff)),
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clickable(enabled = !busy) {
                                            auto = false
                                            liveFaces = false
                                            scan = 0
                                            ready = false
                                            preview?.close()
                                            clearMarks()
                                            torch = false
                                            zoom = 0
                                            result = ""
                                            status = ""
                                            cameraIndex = (cameraIndex + 1) % cameras.size
                                        }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("↻", fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Surface(
                                shape = CircleShape,
                                color = Color(0x88000000),
                                border = BorderStroke(1.dp, Color(0x44ffffff)),
                                modifier = Modifier
                                    .size(40.dp)
                                    .clickable { close() }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("✕", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    }

                    // Floating Zoom Chips (Right side or bottom overlay)
                    val maxZoom = if (ready) preview?.maxZoom ?: 0 else 0
                    if (galleryPhoto == null && maxZoom > 0 && frozen == null) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 14.dp, bottom = 14.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = Color(0x99000000),
                            border = BorderStroke(1.dp, Color(0x44ffffff))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(0 to "1x", (maxZoom / 2).coerceAtLeast(1) to "2x").forEach { (lvl, lbl) ->
                                    val isSelected = (zoom == lvl) || (lvl > 0 && zoom >= lvl)
                                    Surface(
                                        shape = CircleShape,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clickable {
                                                zoom = lvl
                                                preview?.zoom(lvl)
                                            }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                lbl,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) Color.White else Color(0xddffffff)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Frozen Frame Resume Banner
                    if (frozen != null) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xcc1e293b),
                            border = BorderStroke(1.dp, Color(0x44ffffff))
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable {
                                        clearMarks()
                                        result = ""
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("▶ Resume Live View", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xff61e8b1))
                            }
                        }
                    }
                }
            }

            // ==========================================
            // HORIZONTAL MODE SELECTOR RIBBON
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CameraMode.values().forEach { mode ->
                    val isSelected = currentMode == mode
                    Surface(
                        modifier = Modifier.clickable {
                            currentMode = mode
                            if (mode == CameraMode.DETECT) {
                                auto = true
                            } else {
                                auto = false
                                liveFaces = false
                            }
                            clearMarks()
                            result = ""
                            status = ""
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, CardBorderBrush)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(mode.icon, fontSize = 14.sp)
                            Text(
                                mode.title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ==========================================
            // SUB-CONTROLS FOR LIVE DETECTION / INTERVAL
            // ==========================================
            if (currentMode == CameraMode.DETECT) {
                val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered != false
                val canAuto = online && api?.isRemote == false && !metered && ready && !busy

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, CardBorderBrush)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Switch(
                                checked = auto,
                                onCheckedChange = {
                                    liveFaces = false
                                    auto = it
                                    scan = 0
                                    clearMarks()
                                    result = ""
                                    status = ""
                                },
                                enabled = canAuto,
                                modifier = Modifier.scale(0.85f)
                            )
                            Text(
                                "Continuous Stream",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(750L to "Fast", 1500L to "Bal", 3000L to "Save").forEach { (delay, label) ->
                                val active = interval == delay
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, CardBorderBrush),
                                    modifier = Modifier.clickable(enabled = canAuto) { interval = delay }
                                ) {
                                    Text(
                                        label,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // PRIMARY SHUTTER & ACTION DOCK
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Gallery / Pick photo
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    border = if (galleryPhoto != null) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.5.dp, CardBorderBrush),
                    modifier = Modifier
                        .size(52.dp)
                        .clickable(enabled = !busy) { galleryPicker.launch("image/*") }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (galleryPhoto != null) {
                            androidx.compose.foundation.Image(
                                bitmap = galleryPhoto!!.asImageBitmap(),
                                contentDescription = "Selected photo",
                                modifier = Modifier.size(52.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text("🖼", fontSize = 20.sp)
                        }
                    }
                }

                // Center: Big Master Shutter Button
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        3.5.dp,
                        Brush.sweepGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary,
                                MaterialTheme.colorScheme.primary
                            )
                        )
                    ),
                    modifier = Modifier
                        .size(76.dp)
                        .clickable(enabled = (ready || galleryPhoto != null) && !busy) {
                            when (currentMode) {
                                CameraMode.ASK_AI -> {
                                    perform(CameraMode.ASK_AI, customPrompt.ifBlank { "Describe this scene and identify important details." })
                                }
                                CameraMode.DETECT -> {
                                    clearMarks()
                                    result = ""
                                    scan++
                                    if (galleryPhoto != null) perform(CameraMode.DETECT)
                                }
                                CameraMode.READ_TEXT -> {
                                    currentImage()?.let { perform(CameraMode.READ_TEXT) }
                                }
                                CameraMode.BARCODE -> {
                                    currentImage(useSelection = false)?.let { perform(CameraMode.BARCODE) }
                                }
                                CameraMode.PEOPLE -> {
                                    if (galleryPhoto != null) perform(CameraMode.PEOPLE)
                                    else facePrompt = true
                                }
                                CameraMode.PRODUCT -> {
                                    currentImage()?.let { perform(CameraMode.PRODUCT) }
                                }
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .padding(6.dp)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(currentMode.icon, fontSize = 28.sp)
                    }
                }

                // Right: Voice Prompt Button
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
                    modifier = Modifier
                        .size(52.dp)
                        .clickable(enabled = (ready || galleryPhoto != null) && online && !busy && !listening) {
                            voicePhoto = currentImage()
                            if (voicePhoto != null) {
                                if (speaker == null) speaker = TextToSpeech(context) { speakerReady = it == TextToSpeech.SUCCESS }
                                listening = true
                                try {
                                    speech.launch(
                                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Oracle about this scene")
                                    )
                                } catch (_: android.content.ActivityNotFoundException) {
                                    listening = false
                                    voicePhoto = null
                                    status = "Speech recognition unavailable. Type your question."
                                }
                            }
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("🎙", fontSize = 20.sp)
                    }
                }
            }
        }

        // ==========================================
        // DIALOGS FOR FACE RECOGNITION PRIVACY CONSENT
        // ==========================================
        if (liveFacePrompt) {
            AlertDialog(
                onDismissRequest = { liveFacePrompt = false },
                title = { Text("Start Live Face Recognition?", fontWeight = FontWeight.Bold) },
                text = { Text("Frames will be sent to your paired PC to compare with enrolled identities. Ask people in view for consent before starting.") },
                confirmButton = {
                    Button(
                        shape = RoundedCornerShape(12.dp),
                        onClick = {
                            liveFacePrompt = false
                            if (opened && ready && online && api?.isRemote == false && !busy) {
                                auto = false
                                scan = 0
                                clearMarks()
                                result = ""
                                liveFaces = true
                                status = "Scanning for enrolled faces…"
                            }
                        }
                    ) { Text("Start Recognition") }
                },
                dismissButton = {
                    TextButton(onClick = { liveFacePrompt = false }) { Text("Cancel") }
                }
            )
        }

        if (facePrompt) {
            AlertDialog(
                onDismissRequest = { facePrompt = false },
                title = { Text("Recognize Enrolled People?", fontWeight = FontWeight.Bold) },
                text = { Text("Send one frame to your paired PC to compare faces with its enrollment database. Ask people in view for consent.") },
                confirmButton = {
                    Button(
                        shape = RoundedCornerShape(12.dp),
                        onClick = {
                            facePrompt = false
                            currentImage(useSelection = false)?.let { perform(CameraMode.PEOPLE) }
                        }
                    ) { Text("Scan This Frame") }
                },
                dismissButton = {
                    TextButton(onClick = { facePrompt = false }) { Text("Cancel") }
                }
            )
        }

        // ==========================================
        // STATUS BANNER
        // ==========================================
        if (status.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, CardBorderBrush)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (busy || scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text("ℹ", color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ==========================================
        // ORACLE VISION RESULTS CARD
        // ==========================================
        if (result.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, CardBorderBrush)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header with mode icon, title, and copy button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(currentMode.icon, fontSize = 16.sp)
                                }
                            }
                            Column {
                                Text(
                                    resultTitle.ifBlank { "Vision Result" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "Analyzed with Oracle PC Model",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Copy Action
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, CardBorderBrush),
                            modifier = Modifier
                                .size(34.dp)
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("Oracle Vision Result", result))
                                    status = "Copied to clipboard ✓"
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("📋", fontSize = 14.sp)
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Result Content Text
                    SelectionContainer {
                        Text(
                            result,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Quick Follow-up Inquiries
                    Text(
                        "Quick Inquiries",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "Describe this in deep detail",
                            "Transcribe all text",
                            "Explain what this is used for",
                            "Are there any errors or issues?"
                        ).forEach { prompt ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, CardBorderBrush),
                                modifier = Modifier.clickable(enabled = !busy) {
                                    perform(CameraMode.ASK_AI, prompt)
                                }
                            ) {
                                Text(
                                    prompt,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // Follow-up Question Input
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customPrompt,
                            onValueChange = { customPrompt = it.take(300) },
                            placeholder = { Text("Ask follow-up question…", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            shape = RoundedCornerShape(14.dp),
                            enabled = !busy
                        )
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(44.dp)
                                .clickable(enabled = customPrompt.isNotBlank() && !busy) {
                                    val q = customPrompt.trim()
                                    customPrompt = ""
                                    perform(CameraMode.ASK_AI, q)
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("→", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            }
                        }
                    }

                    // Bottom Quick Actions: Chat follow-up or Dismiss
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, CardBorderBrush),
                            onClick = {
                                onNavigateChat("Regarding the camera analysis: $result")
                            }
                        ) {
                            Text("💬 Discuss in Chat", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }

                        Button(
                            modifier = Modifier.weight(0.7f).height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            onClick = {
                                result = ""
                                resultTitle = ""
                                galleryPhoto = null
                            }
                        ) {
                            Text("Dismiss", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
