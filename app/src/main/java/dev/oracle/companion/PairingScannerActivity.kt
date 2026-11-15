package dev.oracle.companion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.graphics.Color
import android.graphics.Typeface
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject

@Suppress("DEPRECATION")
class PairingScannerActivity : Activity(), SurfaceHolder.Callback {
    private var camera: Camera? = null
    private var reader = MultiFormatReader()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showPreview()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) showPreview() else finish()
    }

    private fun showPreview() {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(SurfaceView(this).apply {
            holder.addCallback(this@PairingScannerActivity)
        }, FrameLayout.LayoutParams(-1, -1))

        val instructions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13, 18, 26))
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                text = "Scan pairing code"
                textSize = 22f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.rgb(244, 247, 252))
            })
            addView(TextView(context).apply {
                text = "Point the camera at the QR code in Oracle Central."
                textSize = 14f
                typeface = Typeface.SANS_SERIF
                setTextColor(Color.rgb(170, 185, 203))
            })
            addView(Button(context).apply {
                text = "Cancel"
                isAllCaps = false
                setTextColor(Color.rgb(152, 197, 242))
                setOnClickListener { finish() }
            })
        }
        root.addView(instructions, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bottom = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars()).bottom
            } else {
                insets.systemWindowInsetBottom
            }
            instructions.setPadding(padding, padding, padding, padding + bottom)
            insets
        }
        setContentView(root)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        try {
            val device = Camera.open()
            camera = device
            val settings = device.parameters
            if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in settings.supportedFocusModes.orEmpty()) {
                settings.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                device.parameters = settings
            }
            device.setDisplayOrientation(90)
            device.setPreviewDisplay(holder)
            device.startPreview()
            scan(device)
        } catch (_: Exception) {
            Toast.makeText(this, "Camera unavailable. You can choose a QR image instead.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun scan(device: Camera) {
        device.setOneShotPreviewCallback { frame, source ->
            val size = source.parameters.previewSize
            val code = runCatching {
                val luminance = PlanarYUVLuminanceSource(frame, size.width, size.height,
                    0, 0, size.width, size.height, false)
                reader.decode(BinaryBitmap(HybridBinarizer(luminance))).text.also {
                    val payload = JSONObject(it)
                    Connection.parse(payload)
                    require(payload.getString("code").isNotBlank())
                }
            }.getOrNull()
            if (code != null) {
                setResult(RESULT_OK, Intent().putExtra("pairing", code))
                finish()
            } else if (camera === source && !isFinishing) scan(source)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        camera?.release()
        camera = null
    }
}
