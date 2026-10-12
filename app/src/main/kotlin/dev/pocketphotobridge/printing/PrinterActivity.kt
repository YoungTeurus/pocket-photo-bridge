package dev.pocketphotobridge.printing

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.pocketphotobridge.BuildConfig
import dev.pocketphotobridge.printer.PrinterFailure
import dev.pocketphotobridge.printer.PrinterFailureCode
import dev.pocketphotobridge.printer.PrinterSession
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Foreground-only Android UI. The JVM protocol module has no Android dependency. */
class PrinterActivity : Activity() {
    private lateinit var guard: StoredPrintJobGuard
    @Volatile private var images: PrinterImages? = null
    private var initialStage: String? = null
    private var sonySource = false
    private var retainedOwner = false
    private lateinit var status: TextView
    private lateinit var diagnostics: TextView
    private lateinit var selectedLabel: TextView
    private lateinit var tokenInput: EditText
    private lateinit var image: ImageView
    private lateinit var print: Button
    private lateinit var clearUnknown: Button
    private val actions = ArrayList<Button>()
    private val lines = ArrayDeque<String>()
    private val worker = Executors.newSingleThreadExecutor()
    private val cleanup = Executors.newSingleThreadExecutor()
    private val connectionLock = Any()
    private var active: AndroidPrinterTransport? = null
    @Volatile private var generation = 0
    private var task: Future<*>? = null
    private var busy = false
    private var device: BluetoothDevice? = null
    private var deviceLabel = "Selected printer"
    private var fit = PrinterImages.FitMode.FIT
    private var prepared: File? = null
    private var preview: Bitmap? = null
    private var confirmation: AlertDialog? = null
    private var pendingPickedUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val retained = lastNonConfigurationInstance as? RetainedImages
        images = retained?.owner
        pendingPickedUri = retained?.pendingPick
        fit = retained?.fit ?: PrinterImages.FitMode.FIT
        initialStage = if (images == null) intent.getStringExtra(STAGED_IMAGE) else null
        sonySource = retained?.sonySource ?: (intent.hasExtra(STAGED_IMAGE) || savedInstanceState?.getBoolean("sony_source") == true)
        // A stage is consumed once. A failed/consumed stage must allow choosing a new image.
        intent.removeExtra(STAGED_IMAGE)
        // Secrets are never saved to view state, autofill, diagnostics or screenshots.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        guard = StoredPrintJobGuard(applicationContext)
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
            this.text = text; setOnClickListener { action() }; content.addView(this); actions.add(this)
        }
        label("Direct Bluetooth printing", 26f)
        label("Original Mi Portable profile (basil) · experimental\nReported SKU: TEJ4007CN · physical printing validation pending")
        label("Pair in Android Settings, then select that printer here. Keep this screen visible during printing. No Xiaomi Home or cloud connection is used.")
        button("Open Bluetooth settings") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        button("Select paired printer", ::selectDevice)
        selectedLabel = label("No printer selected")
        label("Device-specific token (optional for connection diagnostics). Enter your own existing 24 hexadecimal characters locally. Pairing alone does not supply this token. Never include it in a shared report.")
        tokenInput = EditText(this).apply {
            hint = "24 hex characters · kept only during this operation"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // Validate the whole value; a length filter can manufacture a valid-looking prefix.
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            content.addView(this)
        }
        button("Connect / Diagnose", ::diagnose)
        status = label("Prepare the test image without a camera. Printing requires a compatible printer and its token.")
        button("Generate test image") { prepareImage { it.testChart() } }
        button("Choose local image") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
            }, PICK_IMAGE)
        }
        button("Fit / Crop") {
            if (confirmation?.isShowing == true) return@button
            AlertDialog.Builder(this).setTitle("Image placement")
                .setSingleChoiceItems(arrayOf("Fit · white borders", "Crop · fill paper"), fit.ordinal) { dialog, index ->
                    fit = PrinterImages.FitMode.entries[index]; dialog.dismiss()
                    prepareImage { it.source ?: throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE) }
                }.show()
        }
        button("Prepare preview") { prepareImage { it.source ?: throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE) } }
        image = ImageView(this).apply {
            adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Prepared print with selected fit or crop"
            content.addView(this, LinearLayout.LayoutParams(-1, (350 * resources.displayMetrics.density).toInt()))
        }
        print = button("Print one copy", ::confirmPrint)
        clearUnknown = button("I checked the printer · clear unknown job", ::confirmClear)
        // Cancellation stays available while all other work controls are disabled.
        Button(this).apply {
            text = "Cancel / Disconnect"; setOnClickListener {
                pendingPickedUri = null; halt(); log("PRINTER_CANCELLED")
                status.text = "Connection cancelled. An accepted job may still print; check the printer before another attempt."
            }; content.addView(this)
        }
        label("Redacted diagnostics", 20f)
        diagnostics = label("").apply { setTextIsSelectable(true) }
        button("Share safe printer diagnostics") {
            val report = "Pocket Photo Bridge ${BuildConfig.VERSION_NAME} · Android API ${Build.VERSION.SDK_INT}\n" + lines.joinToString("\n")
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report)
            }, "Share safe printer diagnostics"))
        }
        log("PRINTER_HARDWARE_VALIDATION_PENDING")
        controls()
        prepareImage { owner ->
            owner.source ?: if (!sonySource) owner.testChart() else throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE)
        }
    }

    private fun controls() {
        actions.forEach { it.isEnabled = !busy }
        print.isEnabled = !busy && prepared != null && device != null && !guard.unresolved
        clearUnknown.isEnabled = !busy && guard.unresolved
        tokenInput.isEnabled = !busy
        if (guard.unresolved) status.text = "Previous print outcome is unknown. Check the printer and any sheet it produced before clearing this job. Automatic retry is disabled."
    }

    private fun log(line: String) {
        if (lines.size >= 200) lines.removeFirst()
        lines.addLast(line); diagnostics.text = lines.joinToString("\n")
    }

    private fun bluetoothAllowed(): Boolean {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_PERMISSION)
            status.text = "Allow Nearby devices, then tap Select paired printer again."
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun selectDevice() {
        if (!bluetoothAllowed()) return
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                log("PRINTER_BLUETOOTH_UNAVAILABLE"); status.text = "Enable Bluetooth in Android Settings."; return
            }
            val devices = adapter.bondedDevices.toList().sortedBy { it.name ?: "" }
            if (devices.isEmpty()) { status.text = "Pair the printer in Android Bluetooth Settings first."; return }
            // Names are displayed locally only; addresses never enter reports or preferences.
            val names = devices.mapIndexed { index, item -> "${index + 1}. ${item.name ?: "Paired Bluetooth device"}" }
            AlertDialog.Builder(this).setTitle("Select your printer")
                .setItems(names.toTypedArray()) { _, index ->
                    device = devices[index]; deviceLabel = names[index]
                    selectedLabel.text = deviceLabel; log("PRINTER_DEVICE_SELECTED"); controls()
                }.show()
        } catch (_: SecurityException) { log("PRINTER_PERMISSION_REQUIRED"); status.text = "Allow Nearby devices in Android app settings." }
    }

    private fun captureToken(): String = tokenInput.text.toString().trim().also {
        tokenInput.text.clear()
        log("PRINTER_KEY_LENGTH count=${it.length}")
    }

    private fun diagnose() {
        val selected = device ?: run { status.text = "Select a paired printer first."; return }
        if (!bluetoothAllowed()) return
        val token = captureToken()
        startWork { ticket ->
            withConnection(selected, ticket) { connection ->
                deliver(ticket) { log("PRINTER_SPP_CONNECTED") }
                if (token.isEmpty()) throw PrinterFailure(PrinterFailureCode.KEY_REQUIRED)
                PrinterSession(connection, token, report = { event -> deliver(ticket) { log(event.safeText()) } }).use { session ->
                    val state = session.diagnose()
                    deliver(ticket) {
                        status.text = "Printer status: ${state.phase}" + (state.battery?.let { " · battery $it%" } ?: "")
                    }
                }
            }
        }
    }

    private fun prepareImage(source: (PrinterImages) -> File) {
        if (busy) return
        // Clear the previous preview immediately: a failed conversion must never print it.
        prepared = null; image.setImageDrawable(null); preview?.recycle(); preview = null
        val placement = fit
        startWork { ticket ->
            val owner = images ?: run {
                val stage = initialStage
                initialStage = null
                try { PrinterImages(applicationContext, stage).also { images = it } }
                catch (e: Exception) {
                    images = PrinterImages(applicationContext)
                    throw e
                }
            }
            val file = owner.prepare(source(owner), placement)
            val bitmap = owner.preview(file)
            runOnUiThread {
                if (ticket != generation || isDestroyed) bitmap.recycle()
                else {
                    prepared = file; preview = bitmap; image.setImageBitmap(bitmap)
                    log("PRINTER_IMAGE_READY")
                    status.text = "1040 × 1560 JPEG · ${placement.name.lowercase()} · ready for confirmation"
                }
            }
        }
    }

    @Deprecated("Android activity result API used for the minimal native Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        importPicked(uri)
    }

    private fun importPicked(uri: Uri) {
        if (isFinishing || isDestroyed) return
        if (busy) {
            // Recreation may start initial preparation before Android delivers picker results.
            pendingPickedUri = uri
            status.text = "Selected image queued for local preparation…"
            return
        }
        prepareImage { owner ->
            val stream = contentResolver.openInputStream(uri) ?: throw PrinterFailure(PrinterFailureCode.INVALID_IMAGE)
            owner.copyPicked(stream)
        }
    }

    private fun confirmPrint() {
        if (busy || guard.unresolved || confirmation?.isShowing == true) return
        val selected = device ?: return
        val file = prepared ?: return
        if (!bluetoothAllowed()) return
        confirmation = AlertDialog.Builder(this).setTitle("Print one photo?")
            .setMessage("$deviceLabel\n${fit.name.lowercase()} · one 50 × 76 mm sheet\nThis consumes paper. Keep this screen visible. Cancelling after the printer accepts the job may not prevent printing.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Print one copy") { _, _ ->
                val token = captureToken()
                if (token.isEmpty()) { log("PRINTER_KEY_REQUIRED"); status.text = keyRequiredMessage(); return@setPositiveButton }
                startWork { ticket ->
                    // A healthy foreground print should not be interrupted by screen timeout.
                    deliver(ticket) { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                    try {
                        withConnection(selected, ticket) { connection ->
                            PrinterSession(connection, token, report = { event -> deliver(ticket) { log(event.safeText()) } }).use {
                                it.print(file.readBytes(), guard)
                                deliver(ticket) { status.text = "Printer reports this job finished. Verify the physical sheet." }
                            }
                        }
                    } finally { deliver(ticket) { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
                }
            }.create().also { it.show() }
    }

    private fun confirmClear() {
        if (busy || confirmation?.isShowing == true) return
        confirmation = AlertDialog.Builder(this).setTitle("Allow a new manual print?")
            .setMessage("Check that the printer has no active job and inspect any output. Clearing this marker cannot cancel a job already accepted by the printer.")
            .setNegativeButton("Keep blocked", null)
            .setPositiveButton("I checked the printer") { _, _ ->
                startWork { ticket ->
                    guard.acknowledgeCheckedPrinter()
                    deliver(ticket) { log("PRINTER_UNKNOWN_ACKNOWLEDGED"); status.text = "A new manually confirmed print is allowed." }
                }
            }.create().also { it.show() }
    }

    private fun startWork(operation: (Int) -> Unit) {
        if (busy) return
        busy = true
        val ticket = synchronized(connectionLock) { ++generation }
        controls()
        task = worker.submit {
            try { operation(ticket) }
            catch (e: Exception) {
                val code = when (e) {
                    is PrinterFailure -> e.code
                    is InterruptedException -> PrinterFailureCode.CANCELLED
                    else -> PrinterFailureCode.DISCONNECTED
                }
                deliver(ticket) {
                    log("PRINTER_${code.name}")
                    status.text = when (code) {
                        PrinterFailureCode.KEY_REQUIRED -> keyRequiredMessage()
                        PrinterFailureCode.INVALID_KEY -> "Expected exactly 24 hexadecimal characters. The input is not shortened into a token; check the field and format."
                        PrinterFailureCode.KEY_OR_PROFILE_REJECTED -> "Token or printer profile rejected. Check your own token and printer model."
                        PrinterFailureCode.PROTOCOL_ERROR, PrinterFailureCode.UNSUPPORTED_STATUS -> "Printer reply differs from the supported protocol. Share safe diagnostics; the rejection reason identifies the failed step."
                        PrinterFailureCode.PRINTER_FAULT -> "Printer reported a fault. Check paper, cover and battery; exact fault codes await hardware testing."
                        PrinterFailureCode.BUSY -> "Printer is busy. No automatic retry; check it before another attempt."
                        PrinterFailureCode.INVALID_IMAGE -> "Image unavailable or unsupported. Choose a local JPEG/PNG/WebP and prepare again."
                        else -> "Printer operation stopped: ${code.name}. Check the printer before retrying."
                    }
                }
            } finally { deliver(ticket) {
                busy = false; controls()
                preparePendingPick()
            } }
        }
    }

    private fun withConnection(selected: BluetoothDevice, ticket: Int, operation: (AndroidPrinterTransport) -> Unit) {
        val connection = AndroidPrinterTransport(selected)
        synchronized(connectionLock) {
            if (ticket != generation || Thread.currentThread().isInterrupted) {
                connection.close(); throw PrinterFailure(PrinterFailureCode.CANCELLED)
            }
            active = connection
        }
        try { connection.connect(); operation(connection) }
        finally {
            connection.close()
            synchronized(connectionLock) { if (active === connection) active = null }
        }
    }

    private fun deliver(ticket: Int, update: () -> Unit) {
        runOnUiThread { if (ticket == generation && !isDestroyed) update() }
    }

    private fun halt() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        confirmation?.dismiss(); confirmation = null
        val connection = synchronized(connectionLock) { ++generation; active.also { active = null } }
        connection?.let { cleanup.submit { it.cancel() } }
        task?.cancel(true); task = null; busy = false
        tokenInput.text.clear()
        controls()
    }

    override fun onStop() {
        halt()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) preparePendingPick()
    }

    private fun preparePendingPick() {
        if (busy) return
        val picked = pendingPickedUri ?: return
        pendingPickedUri = null
        importPicked(picked)
    }

    override fun onDestroy() {
        halt()
        image.setImageDrawable(null); preview?.recycle(); preview = null
        if (!retainedOwner) worker.submit { images?.clear() }
        worker.shutdown(); cleanup.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("sony_source", sonySource)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any? {
        val owner = images ?: return null
        retainedOwner = true
        // Contains image files and fit only; no token, device or job worker survives rotation.
        return RetainedImages(owner, fit, sonySource, pendingPickedUri)
    }

    private data class RetainedImages(val owner: PrinterImages, val fit: PrinterImages.FitMode,
        val sonySource: Boolean, val pendingPick: Uri?)

    private fun keyRequiredMessage() = "SPP diagnostics do not need a token. Encrypted status and printing require your own device-specific token; no verified pairing-only method to obtain it is available."

    companion object {
        const val STAGED_IMAGE = "staged_image"
        private const val PICK_IMAGE = 20
        private const val BLUETOOTH_PERMISSION = 21
    }
}
