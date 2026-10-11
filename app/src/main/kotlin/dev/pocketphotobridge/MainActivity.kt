package dev.pocketphotobridge

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.pocketphotobridge.camera.network.AttachedWifi
import dev.pocketphotobridge.camera.network.NetworkCameraTransport
import dev.pocketphotobridge.camera.network.WifiConnector
import dev.pocketphotobridge.photos.PhotoStore
import dev.pocketphotobridge.sony.ProtocolFailure
import dev.pocketphotobridge.sony.ReceivedPhoto
import dev.pocketphotobridge.sony.SonyTransferSession
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private lateinit var wifi: WifiConnector
    private lateinit var photos: PhotoStore
    private lateinit var status: TextView
    private lateinit var diagnostics: TextView
    private lateinit var image: ImageView
    private lateinit var connect: Button
    private lateinit var diagnose: Button
    private lateinit var receive: Button
    private lateinit var save: Button
    private lateinit var cancel: Button
    private val worker = Executors.newSingleThreadExecutor()
    private val lines = ArrayDeque<String>()
    private var attached: AttachedWifi? = null
    private var transport: NetworkCameraTransport? = null
    private var task: Future<*>? = null
    private var generation = 0
    private var busy = false
    private var photo: ReceivedPhoto? = null
    private var bitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifi = WifiConnector(this)
        photos = PhotoStore(applicationContext)
        photos.clear()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val padding = (20 * resources.displayMetrics.density).toInt()
        content.setPadding(padding, padding, padding, padding)
        val scroll = ScrollView(this).apply { addView(content) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
        fun label(text: String, size: Float = 16f) = TextView(this).apply {
            this.text = text; textSize = size; setPadding(0, 8, 0, 8); content.addView(this)
        }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text; setOnClickListener { action() }; content.addView(this)
        }
        label("Pocket Photo Bridge", 26f)
        label("Experimental RX100 VII receiver · hardware validation pending")
        label("1. On camera: Send to Smartphone → Select on This Device → one JPEG.\n2. Join the camera Wi-Fi in Android Settings and accept no internet.\n3. Return here, Connect, then Receive. Keep this app visible.")
        button("Open Wi-Fi settings") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        connect = button("Connect to current Wi-Fi", ::attach)
        status = label("Select one JPEG on camera, then join its Wi-Fi.")
        diagnose = button("Diagnose", ::diagnose)
        receive = button("Receive selected JPEG", ::receive)
        cancel = button("Cancel / Disconnect") { disconnect("Disconnected. Re-enter camera sharing before retrying.") }
        image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Preview of the received camera-selected photo"
            visibility = View.GONE
            content.addView(this, LinearLayout.LayoutParams(-1, (300 * resources.displayMetrics.density).toInt()))
        }
        save = button("Save to Gallery", ::save)
        label("Session diagnostics", 20f)
        diagnostics = label("").apply { setTextIsSelectable(true) }
        button("Share safe diagnostics") {
            val report = "Pocket Photo Bridge 0.1.0 · Android API ${Build.VERSION.SDK_INT}\n" + lines.joinToString("\n")
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report)
            }, "Share safe diagnostics"))
        }
        log("HARDWARE_VALIDATION_PENDING")
        controls()
    }

    private fun log(line: String) {
        if (lines.size >= 200) lines.removeFirst()
        lines.addLast(line)
        diagnostics.text = lines.joinToString("\n")
    }

    private fun controls() {
        connect.isEnabled = !busy
        diagnose.isEnabled = !busy && attached != null
        receive.isEnabled = !busy && attached != null
        save.isEnabled = !busy && photo != null
        cancel.isEnabled = busy || attached != null
    }

    private fun attach() {
        disconnect(null)
        try {
            attached = wifi.attach {
                disconnect("Camera Wi-Fi lost. Join it again and tap Connect.")
                log("WIFI_LOST")
            }
            log("WIFI_ATTACHED")
            log(if (attached!!.gatewayFromWifi) "GATEWAY_FROM_WIFI" else "GATEWAY_FALLBACK")
            status.text = getString(R.string.wifi_attached)
        } catch (e: Exception) {
            val code = when (e.message) {
                "NO_WIFI" -> "NO_WIFI"
                "MULTIPLE_WIFI_NETWORKS" -> "MULTIPLE_WIFI_NETWORKS"
                else -> "WIFI_ATTACH_FAILED"
            }
            log(code)
            status.text = if (code == "NO_WIFI") "Join camera Wi-Fi in Settings first." else "Cannot attach one Wi-Fi route. Check Android Settings."
        }
        controls()
    }

    private fun runOperation(saving: Boolean = false, operation: (Int, SonyTransferSession?) -> Unit) {
        if (busy) return
        val connection = attached
        if (!saving && connection == null) return
        busy = true
        val token = ++generation
        val http = if (saving) null else NetworkCameraTransport(connection!!.network) { code ->
            deliver(token) { log("HTTP_STATUS $code") }
        }
        transport = http
        val session = http?.let { SonyTransferSession(it, connection!!.endpoint,
            report = { event -> deliver(token) { log(event.safeText()) } },
            validateJpeg = { file -> photos.preview(file).recycle() }) }
        controls()
        task = worker.submit {
            try { operation(token, session) }
            catch (e: Exception) {
                val code = when {
                    e is ProtocolFailure -> e.failure.name
                    e is SocketTimeoutException -> "TIMEOUT"
                    saving -> "SAVE_FAILED"
                    e is IOException && e.message == "INVALID_JPEG" -> "INVALID_JPEG"
                    e is IOException -> "IO_ERROR"
                    else -> "OPERATION_FAILED"
                }
                deliver(token) {
                    log(code)
                    status.text = getString(R.string.operation_failed, code)
                }
            } finally {
                http?.close()
                deliver(token) { busy = false; transport = null; controls() }
            }
        }
    }

    private fun deliver(token: Int, update: () -> Unit) {
        runOnUiThread { if (token == generation && !isDestroyed) update() }
    }

    private fun diagnose() {
        status.text = getString(R.string.diagnosing)
        runOperation { token, session ->
            session!!.diagnose()
            deliver(token) { status.text = getString(R.string.diagnosed) }
        }
    }

    private fun clearPreview() {
        image.setImageDrawable(null)
        image.visibility = View.GONE
        bitmap?.recycle(); bitmap = null
        photo?.file?.delete(); photo = null
    }

    private fun receive() {
        clearPreview()
        status.text = getString(R.string.receiving)
        runOperation { token, session ->
            val result = session!!.receive(photos.directory)
            val preview = try { photos.preview(result.file) } catch (e: Exception) { result.file.delete(); throw e }
            runOnUiThread {
                if (token != generation || isDestroyed) { preview.recycle(); result.file.delete() }
                else {
                    photo = result; bitmap = preview
                    image.setImageBitmap(preview); image.visibility = View.VISIBLE
                    status.text = getString(R.string.received, result.bytes)
                    log("PREVIEW_READY")
                }
            }
        }
    }

    private fun save() {
        val current = photo ?: return
        status.text = getString(R.string.saving)
        runOperation(saving = true) { token, _ ->
            photos.save(current.file)
            deliver(token) {
                log("GALLERY_SAVED")
                status.text = getString(R.string.saved)
                clearPreview()
            }
        }
    }

    private fun disconnect(message: String?) {
        ++generation
        transport?.close(); transport = null
        task?.cancel(true); task = null
        wifi.release(); attached = null; busy = false
        message?.let { status.text = it; log("DISCONNECTED") }
        if (::connect.isInitialized) controls()
    }

    override fun onStop() {
        disconnect("Session paused. Tap Connect again when ready.")
        super.onStop()
    }

    override fun onDestroy() {
        disconnect(null)
        clearPreview()
        // Queue cleanup after the cancelled worker so it cannot leave a partial cache file.
        worker.submit { photos.clear() }
        worker.shutdown()
        super.onDestroy()
    }
}
