package com.quaytest.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.quaytest.app.databinding.ActivityMainBinding
import com.quaytest.app.databinding.DialogConfirmBinding
import com.quaytest.app.databinding.DialogInfoBinding
import com.quaytest.app.databinding.DialogSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Flow: BẮT ĐẦU → khoanh vùng mã → xác nhận mã → điền thông tin → đếm ngược 3s → quay.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "QuayTest"
        const val VIDEO_DIR = "Movies/QuayTest"
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var stampEffect: OverlayEffect? = null
    private var recording: Recording? = null
    private var paused = false

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // Trạng thái flow
    private var selecting = false       // đang chờ khoanh vùng
    private var reading = false         // đang đọc mã
    private var countingDown = false
    private var lastSelection: RectF? = null
    private var lastSelectionRotation = -1          // khung cũ chỉ dùng lại khi cùng chiều xoay
    private var previewUseCase: Preview? = null

    /** Mã đã xác nhận cho lần quay này. */
    private var confirmedCode: String? = null
    private var staff = ""
    private var sheetTab = Store.DEFAULT_TAB
    // Các cột theo sheet mẫu: Tên khách, Mã mạch, Loại máy, Ngày tạo bảo hành, Ghi chú
    private var customer = ""        // nhập mới mỗi lần test (không nhớ)
    private var machineType = ""     // giữ lại cho máy sau
    private var board = ""           // xoá sau mỗi lần quay
    private var note = ""            // xoá sau mỗi lần quay
    private var warrantyDate = ""    // "" = không tạo bảo hành
    private var warrantyMonths = 12   // thời hạn BH lần quay này (chỉ in trên video)

    // Thông tin của video đang quay (ghi Sheet khi quay xong)
    private var recStart = Date()
    private var recTab = Store.DEFAULT_TAB   // tab ghi cho lần quay này (tính lúc bắt đầu quay)
    private var recFileName = ""
    private var recDurationNanos = 0L

    // Chữ in lên video — đọc từ luồng camera nên để @Volatile
    @Volatile private var stampCode = ""
    @Volatile private var stampStaff = ""
    @Volatile private var stampCustomer = ""
    @Volatile private var stampWarranty = ""
    @Volatile private var stampActive = false          // chỉ vẽ chữ khi đang quay
    private var stampThread: android.os.HandlerThread? = null

    /** Chỉ cho mở 1 hộp thoại một lúc — máy chậm, bấm 2 lần không bị chồng hộp. */
    private var activeDialog: AlertDialog? = null
    private fun dialogOpen() = activeDialog?.isShowing == true
    private fun MaterialAlertDialogBuilder.showSingle(): AlertDialog = show().also { dlg ->
        activeDialog = dlg
        // Chạm ra ngoài ô nhập → bàn phím tự lui xuống
        dlg.window?.decorView?.let { it.post { it.tapOutsideHidesKeyboard() } }
    }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", Locale.getDefault())
    private val clockTick = object : Runnable {
        override fun run() {
            b.tvClock.text = clockFmt.format(Date())
            clockHandler.postDelayed(this, 1000)
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res[Manifest.permission.CAMERA] == true) startCamera()
        else {
            toast("App cần quyền Camera để quay")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        prefs = getSharedPreferences(SheetLogger.PREFS, MODE_PRIVATE)
        staff = Store.getString(this, Store.KEY_STAFF)
        sheetTab = Store.getString(this, Store.KEY_SHEET_TAB, Store.DEFAULT_TAB)
        machineType = Store.getString(this, Store.KEY_LAST_TYPE)

        setupUi()

        // Camera + micro bắt buộc; thông báo (Android 13+) để nhắc đồng bộ 16:00 — từ chối vẫn dùng app được
        val need = listOfNotNull(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
        ).toTypedArray()
        if (need.all { granted(it) }) startCamera() else permLauncher.launch(need)

        lifecycleScope.launch { SheetLogger.flush(this@MainActivity) }
        // Nhắc đồng bộ Drive 16:00 + dọn video đã lên Drive quá 14 ngày
        Reminder.scheduleNext(this)
        lifecycleScope.launch(Dispatchers.IO) {
            val n = Reminder.cleanupOld(this@MainActivity)
            if (n > 0) withContext(Dispatchers.Main) {
                toast("Đã dọn $n video cũ (>${Reminder.KEEP_DAYS} ngày, đã có trên Drive)")
            }
        }
    }

    /**
     * Xoay máy (dọc ↔ ngang): KHÔNG khởi động lại activity (configChanges trong manifest),
     * chỉ dựng lại giao diện theo layout chiều mới (layout / layout-land) → camera, hộp thoại,
     * bước đang làm dở đều giữ nguyên.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        setupUi()
        if (recording == null && !countingDown && videoCapture != null) {
            // Rảnh → dựng lại camera theo chiều mới: quay ngang ra video NGANG thật (không cắt khung dọc)
            startCamera()
        } else {
            // Đang quay (chiều đã khoá) → chỉ gắn khung xem trước vào PreviewView mới
            previewUseCase?.setSurfaceProvider(b.preview.surfaceProvider)
        }
    }

    // ================= ZOOM + LẤY NÉT =================

    private var zoomRatio = 1f
    private val ocrWarm = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun setZoom(ratio: Float) {
        val cam = camera ?: return
        val z = cam.cameraInfo.zoomState.value
        val minZ = z?.minZoomRatio ?: 1f
        val maxZ = z?.maxZoomRatio ?: 1f
        zoomRatio = ratio.coerceIn(minZ, maxZ)
        cam.cameraControl.setZoomRatio(zoomRatio)
        b.tvZoom.text = String.format(Locale.US, "%.1fx", zoomRatio)
    }

    /** Chạm để lấy nét: GIỮ nét tại điểm chạm (không tự nhả sau vài giây) + hiện vòng báo kết quả. */
    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val p = b.preview.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(p, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .disableAutoCancel()
            .build()
        val overlay = b.selection
        if (!cam.cameraInfo.isFocusMeteringSupported(action)) {
            overlay.showFocus(x, y, SelectionOverlayView.FocusState.FAIL)
            toast("Camera máy này không hỗ trợ chạm lấy nét")
            return
        }
        overlay.showFocus(x, y, SelectionOverlayView.FocusState.FOCUSING)
        val f = cam.cameraControl.startFocusAndMetering(action)
        f.addListener({
            val ok = try { f.get().isFocusSuccessful } catch (e: Exception) { false }
            if (overlay.isAttachedToWindow) overlay.showFocus(
                x, y, if (ok) SelectionOverlayView.FocusState.OK else SelectionOverlayView.FocusState.FAIL
            )
        }, ContextCompat.getMainExecutor(this))
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        (if (Build.VERSION.SDK_INT >= 30) display?.rotation else windowManager.defaultDisplay.rotation)
            ?: android.view.Surface.ROTATION_0

    /** Khoá chiều màn hình hiện tại (lúc đếm ngược + quay) / mở lại xoay tự do. */
    private fun lockOrientation(lock: Boolean) {
        requestedOrientation = if (lock) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    }

    /** Dựng giao diện màn chính (gọi lúc mở app và mỗi lần xoay máy). */
    private fun setupUi() {
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Tự chừa chỗ cho thanh trạng thái / thanh vuốt (ColorOS hay vẽ đè lên app)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // ColorOS báo inset thanh vuốt chỉ 2px nhưng vẫn vẽ thanh đè → chừa tối thiểu 20dp
            val minBottom = (20 * resources.displayMetrics.density).toInt()
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, minBottom))
            WindowInsetsCompat.CONSUMED
        }

        b.btnStart.setOnClickListener { startSelecting() }
        b.selection.onSelected = { r -> if (selecting) onAreaChosen(r) }
        b.selection.onZoom = { f -> setZoom(zoomRatio * f) }
        b.selection.onTap = { x, y -> focusAt(x, y) }
        b.tvZoom.text = String.format(Locale.US, "%.1fx", zoomRatio)
        b.tvZoom.setOnClickListener { setZoom(1f) }
        b.btnReuse.setOnClickListener { lastSelection?.let { onAreaChosen(it) } }
        b.btnCancelSel.setOnClickListener { backToIdle() }
        // Không khoanh vùng → nhập mã thiết bị bằng tay
        b.btnManual.setOnClickListener {
            selecting = false
            b.selection.setSelectionRect(null)
            updateButtons()
            showCodeDialog(emptyList(), manual = true)
        }
        b.btnPause.setOnClickListener { togglePause() }
        b.btnStop.setOnClickListener {
            if (countingDown) cancelCountdown() else recording?.stop()
        }
        b.btnSheet.setOnClickListener { startActivity(Intent(this, SheetLogActivity::class.java)) }
        b.btnConfig.setOnClickListener { showConfigDialog() }

        // Khung khoanh mã theo toạ độ màn hình cũ → bỏ khi dựng lại (vẫn nhớ lastSelection
        // nhưng xoay rồi thì khung cũ không còn khớp → không cho "DÙNG KHUNG CŨ" sai chỗ)
        if (lastSelectionRotation != displayRotation()) lastSelection = null
        b.selection.setSelectionRect(null)
        b.tvCountdown.visibility = if (countingDown) View.VISIBLE else View.GONE
        b.tvClock.text = clockFmt.format(Date())
        updateInfo()
        updateButtons()
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockTick)
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockTick)
        if (countingDown) cancelCountdown()
    }

    override fun onDestroy() {
        super.onDestroy()
        recording?.stop()
        recognizer.close()
        stampEffect?.close()
        stampThread?.quitSafely()
    }

    // ================= BƯỚC 1: KHOANH VÙNG =================

    private fun startSelecting() {
        if (recording != null || countingDown || reading || dialogOpen()) return
        if (videoCapture == null) { toast("Camera chưa sẵn sàng"); return }
        if (Store.staffList(this).isEmpty() || Store.typeList(this).isEmpty()) {
            val thieu = listOfNotNull(
                "nhân viên".takeIf { Store.staffList(this).isEmpty() },
                "loại máy".takeIf { Store.typeList(this).isEmpty() }
            ).joinToString(" và ")
            toast("Chưa có danh sách $thieu — thêm ở ⚙ CÀI ĐẶT")
            showConfigDialog(); return
        }
        selecting = true
        confirmedCode = null
        b.selection.setSelectionRect(null)
        updateInfo(); updateButtons()
    }

    private fun onAreaChosen(r: RectF) {
        selecting = false
        lastSelection = RectF(r)
        lastSelectionRotation = displayRotation()
        b.selection.setSelectionRect(r)
        updateButtons()
        focusThenRead()
    }

    private fun backToIdle() {
        selecting = false
        reading = false
        confirmedCode = null
        customer = ""
        board = ""
        note = ""
        b.selection.setSelectionRect(null)
        updateInfo(); updateButtons()
    }

    // ================= ĐỌC MÃ (OCR) =================

    /** Lấy nét vào giữa khung chọn rồi mới đọc chữ — chữ nhỏ đọc chuẩn hơn. */
    private fun focusThenRead() {
        if (recording != null) return
        val cam = camera
        val sel = b.selection.selection
        setReading(true)
        if (cam == null || sel == null) { readCode(); return }
        val point = b.preview.meteringPointFactory.createPoint(sel.centerX(), sel.centerY())
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)
            .addListener({ readCode() }, ContextCompat.getMainExecutor(this))
    }

    private fun readCode() {
        val full = b.preview.bitmap
        if (full == null) {
            toast("Camera chưa sẵn sàng")
            backToIdle(); return
        }
        val crop = cropToSelection(full)
        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { t -> setReading(false); showCodeDialog(candidates(t.text)) }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR lỗi", e)
                setReading(false); showCodeDialog(emptyList())
            }
    }

    private fun setReading(on: Boolean) {
        reading = on
        updateButtons()
    }

    private fun cropToSelection(full: Bitmap): Bitmap {
        val sel = b.selection.selection ?: return full
        val sx = full.width / b.selection.width.toFloat()
        val sy = full.height / b.selection.height.toFloat()
        val l = (sel.left * sx).toInt().coerceIn(0, full.width - 1)
        val t = (sel.top * sy).toInt().coerceIn(0, full.height - 1)
        val r = (sel.right * sx).toInt().coerceIn(l + 1, full.width)
        val btm = (sel.bottom * sy).toInt().coerceIn(t + 1, full.height)
        var crop = Bitmap.createBitmap(full, l, t, r - l, btm - t)
        // Khung nhỏ → phóng to cho ML Kit đọc dễ hơn
        val longSide = max(crop.width, crop.height)
        if (longSide < 800) {
            val k = 800f / longSide
            crop = Bitmap.createScaledBitmap(crop, (crop.width * k).toInt(), (crop.height * k).toInt(), true)
        }
        return crop
    }

    /** Các dòng đọc được → chuẩn hoá, bỏ dòng rác, xếp dòng nhiều chữ+số lên đầu, tối đa 6. */
    private fun candidates(raw: String): List<String> {
        val lines = raw.lines().map { normalizeCode(it) }.filter { it.length >= 3 }.distinct()
        // Mã máy (vd. VQR14795976) luôn có số → ưu tiên dòng có số; không có thì lấy dòng thường
        val withDigits = lines.filter { l -> l.any { it.isDigit() } }
        return (withDigits.ifEmpty { lines })
            .sortedByDescending { line -> line.count { it.isLetterOrDigit() } }
            .take(6)
    }

    // ================= BƯỚC 2: XÁC NHẬN MÃ =================

    private fun showCodeDialog(cands: List<String>, manual: Boolean = false) {
        if (isFinishing || dialogOpen()) return
        val d = DialogConfirmBinding.inflate(layoutInflater)
        val best = cands.firstOrNull().orEmpty()
        d.etCode.apply {
            setText(confirmedCode ?: best)
            setSelection(text?.length ?: 0)
            // VISIBLE_PASSWORD + NO_SUGGESTIONS: tắt bộ gõ tiếng Việt (tránh "VA" thành "VẢ")
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(40))
        }
        // Gợi ý khác (ngoài dòng đã điền) — chạm để chọn
        val others = cands.drop(1)
        d.tvCandidates.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE
        others.forEach { c ->
            d.cgCandidates.addView(Chip(this).apply {
                text = c
                typeface = Typeface.MONOSPACE
                setOnClickListener { d.etCode.setText(c); d.etCode.setSelection(c.length) }
            })
        }

        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(when {
                manual -> "Nhập mã thiết bị"
                best.isEmpty() -> "Không đọc được mã"
                else -> "Xác nhận mã thiết bị"
            })
            .setMessage(if (best.isEmpty() && !manual) "Nhập tay mã hoặc bấm KHOANH LẠI." else null)
            .setView(d.root)
            .setCancelable(false)
            .setPositiveButton("XÁC NHẬN", null)          // gán listener sau để không tự đóng khi mã trống
            .setNeutralButton("KHOANH LẠI") { _, _ -> b.root.post { startSelecting() } }
            .setNegativeButton("HUỶ") { _, _ -> backToIdle() }
            .showSingle()
        if (best.isEmpty()) {
            // Phải gõ tay → bật sẵn bàn phím vào ô mã
            d.etCode.requestFocus()
            dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val v = normalizeCode(d.etCode.text.toString())
            if (v.isEmpty()) { d.etCode.error = "Nhập mã"; return@setOnClickListener }
            confirmedCode = v
            updateInfo()
            dlg.dismiss()
            b.root.post { showDetailsDialog() }
        }
    }

    // ================= BƯỚC 3: ĐIỀN THÔNG TIN =================

    private fun showDetailsDialog() {
        if (isFinishing || dialogOpen()) return
        val code = confirmedCode ?: return backToIdle()
        val d = DialogInfoBinding.inflate(layoutInflater)
        d.tvCode.text = code

        fun adapter(l: List<String>) = ArrayAdapter(this, android.R.layout.simple_list_item_1, l)

        // Tên khách: nhập mới mỗi lần test (không nhớ, không gợi ý tên cũ)
        d.etCustomer.setText("")
        // Loại máy: chọn từ danh sách trong Cài đặt (giữ loại của máy trước nếu còn trong danh sách)
        val types = Store.typeList(this)
        d.ddType.setAdapter(adapter(types))
        if (machineType in types) d.ddType.setText(machineType, false)
        else if (types.size == 1) d.ddType.setText(types[0], false)
        if (types.isEmpty()) d.tilType.helperText = "Chưa có loại máy — thêm ở ⚙ CÀI ĐẶT"
        // Mã mạch: GIỮ NGUYÊN VĂN (không viết hoa, không đổi _ / . ...) — chỉ tắt bộ gõ tiếng Việt
        d.etBoard.apply {
            setText(board)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(200))
        }
        d.etNote.setText(note)
        // Nút 📷 ở ô Mã mạch → quét mã vạch / QR, tự điền vào ô
        d.tilBoard.setEndIconOnClickListener {
            b.root.hideKeyboard()
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(
                this,
                com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                    .enableAutoZoom()
                    .build()
            ).startScan()
                .addOnSuccessListener { bc ->
                    // Nguyên văn những gì quét được, chỉ bỏ ký tự xuống dòng/thừa ở 2 đầu
                    val v = bc.rawValue.orEmpty().trim()
                    if (v.isEmpty()) toast("Mã quét được không hợp lệ")
                    else { d.etBoard.setText(v); d.etBoard.setSelection(v.length); d.tilBoard.error = null }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Quét mã mạch lỗi", e)
                    toast("Không mở được trình quét (cần Google Play Services) — nhập tay mã mạch")
                }
            // Huỷ quét: không làm gì
        }

        val staffs = Store.staffList(this)
        d.ddStaff.setAdapter(adapter(staffs))
        if (staff in staffs) d.ddStaff.setText(staff, false)
        else if (staffs.size == 1) d.ddStaff.setText(staffs[0], false)

        val sheets = Store.sheetList(this)
        val autoMonth = Store.autoMonth(this)
        if (autoMonth) {
            // Chế độ theo tháng: tab tự đặt = tháng_năm, không cho chọn
            d.ddSheet.setText("${Store.monthTab()}  (tự động theo tháng)", false)
            d.ddSheet.isEnabled = false
        } else {
            d.ddSheet.setAdapter(adapter(sheets))
            d.ddSheet.setText(if (sheetTab in sheets) sheetTab else sheets.first(), false)
        }

        // Bảo hành: chọn thời hạn, MẶC ĐỊNH 1 NĂM mỗi lần test.
        // Sheet ghi "Ngày tạo bảo hành" = ngày test (nếu có BH); thời hạn chỉ in trên video.
        val warrantyOpts = listOf(0 to "Không bảo hành", 6 to "6 tháng", 12 to "1 năm", 24 to "2 năm", 36 to "3 năm")
        var pickedMonths = 12
        d.ddWarranty.setAdapter(adapter(warrantyOpts.map { it.second }))
        d.ddWarranty.setText("1 năm", false)
        d.ddWarranty.setOnItemClickListener { _, _, pos, _ -> pickedMonths = warrantyOpts[pos].first }

        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Thông tin test")
            .setView(d.root)
            .setCancelable(false)
            // Chữ ngắn để 3 nút nằm 1 hàng (dài quá Material xếp dọc → nút bị che)
            .setPositiveButton("● QUAY", null)
            .setNeutralButton("SỬA MÃ") { _, _ -> b.root.post { showCodeDialog(listOf(code)) } }
            .setNegativeButton("HUỶ") { _, _ -> backToIdle() }
            .showSingle()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            // 3 trường bắt buộc: Tên khách, Loại máy, Người test
            val pickedStaff = d.ddStaff.text.toString().trim()
            val pickedType = d.ddType.text.toString().trim()
            val typedCustomer = d.etCustomer.text.toString().trim()
            d.tilCustomer.error = if (typedCustomer.isEmpty()) "Nhập tên khách" else null
            d.tilType.error = if (pickedType.isEmpty()) "Chọn loại máy" else null
            d.tilStaff.error = if (pickedStaff.isEmpty()) "Chọn người test" else null
            if (typedCustomer.isEmpty() || pickedType.isEmpty() || pickedStaff.isEmpty()) return@setOnClickListener
            val clean = { s: String -> s.trim().replace(Regex("\\s+"), " ") }
            customer = clean(d.etCustomer.text.toString())
            machineType = pickedType
            board = d.etBoard.text.toString().trim()   // nguyên văn
            note = clean(d.etNote.text.toString())
            staff = pickedStaff
            if (!autoMonth) sheetTab = d.ddSheet.text.toString().trim().ifEmpty { Store.DEFAULT_TAB }
            warrantyMonths = pickedMonths
            // Có bảo hành → ngày tạo = ngày test (Sheet chỉ ghi ngày tạo)
            warrantyDate = if (pickedMonths > 0) SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date()) else ""
            // Nhớ loại máy + người test gần nhất cho lần sau (tên khách KHÔNG nhớ)
            Store.putString(this, Store.KEY_STAFF, staff)
            Store.putString(this, Store.KEY_SHEET_TAB, sheetTab)
            Store.putString(this, Store.KEY_LAST_TYPE, machineType)
            Store.remember(this, Store.KEY_RECENT_TYPES, machineType)
            updateInfo()
            dlg.dismiss()
            startCountdown()
        }
    }

    // ================= BƯỚC 4: ĐẾM NGƯỢC → QUAY =================

    private var countdownLeft = 0
    private val beeper by lazy {
        try { android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80) }
        catch (e: Exception) { null }
    }
    private val countdownTick = object : Runnable {
        override fun run() {
            if (!countingDown) return
            if (countdownLeft <= 0) {
                countingDown = false
                b.tvCountdown.visibility = View.GONE
                beeper?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 300)
                startRecording()
                return
            }
            b.tvCountdown.text = countdownLeft.toString()
            beeper?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
            countdownLeft--
            clockHandler.postDelayed(this, 1000)
        }
    }

    private fun startCountdown() {
        // Ẩn khung khoanh mã khi đếm ngược / quay (vẫn nhớ trong lastSelection cho "DÙNG KHUNG CŨ")
        b.selection.setSelectionRect(null)
        // Khoá chiều màn hình từ lúc đếm ngược → video không đảo chiều giữa chừng
        lockOrientation(true)
        countingDown = true
        countdownLeft = 3
        b.tvCountdown.visibility = View.VISIBLE
        updateButtons()
        clockHandler.post(countdownTick)
    }

    private fun cancelCountdown() {
        countingDown = false
        clockHandler.removeCallbacks(countdownTick)
        b.tvCountdown.visibility = View.GONE
        lockOrientation(false)
        backToIdle()
        toast("Đã huỷ")
    }

    // ================= CAMERA =================

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val rotation = displayRotation()
            // Khung xem trước 16:9 = đúng khung video → thấy gì quay nấy
            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .setResolutionSelector(
                    androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                            androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                        ).build()
                ).build()
            preview.setSurfaceProvider(b.preview.surfaceProvider)
            previewUseCase = preview

            // Máy yếu (RAM < 3.5GB) quay HD 720p cho mượt; máy khoẻ quay Full HD
            val mem = android.app.ActivityManager.MemoryInfo().also {
                (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it)
            }
            val lowEnd = mem.totalMem < 3_500_000_000L
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        if (lowEnd) Quality.HD else Quality.FHD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                ).build()
            // Video dựng theo chiều đang cầm máy lúc bind (xoay máy → bind lại, xem onConfigurationChanged)
            val vc = VideoCapture.Builder(recorder).setTargetRotation(rotation).build()

            provider.unbindAll()
            // Dọn bộ in chữ + luồng vẽ của lần bind trước
            stampEffect?.close(); stampEffect = null
            stampThread?.quitSafely(); stampThread = null
            camera = try {
                // In chữ (mã, NV, KH, BH, ngày giờ) thẳng lên video
                val effect = createStampEffect().also { stampEffect = it }
                val group = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(vc)
                    .addEffect(effect)
                    .build()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
            } catch (e: Exception) {
                // Máy không hỗ trợ effect → vẫn quay được, chỉ không có chữ trong hình
                Log.e(TAG, "Overlay effect không chạy được", e)
                toast("Máy không hỗ trợ in chữ lên video — vẫn quay bình thường")
                stampEffect?.close(); stampEffect = null
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, vc)
            }
            videoCapture = vc
            // Giữ mức zoom sau khi bind lại (xoay máy)
            if (zoomRatio != 1f) setZoom(zoomRatio)
            if (ocrWarm.compareAndSet(false, true)) warmUpOcr()
        }, ContextCompat.getMainExecutor(this))
    }

    /** Nạp sẵn bộ đọc chữ ML Kit ở nền → lần ĐỌC MÃ đầu tiên không bị khựng. */
    private fun warmUpOcr() {
        try {
            val tiny = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
            recognizer.process(InputImage.fromBitmap(tiny, 0))
                .addOnCompleteListener { tiny.recycle() }
        } catch (e: Exception) { /* không quan trọng */ }
    }

    /** Vẽ khung thông tin góc trên-trái của video, kiểu Titanium (nền đen, vạch xanh, chữ mono). */
    private fun createStampEffect(): OverlayEffect {
        // Vẽ trên LUỒNG NỀN riêng (trước đây vẽ trên luồng giao diện 30 lần/giây → bấm nút bị trễ)
        val thread = android.os.HandlerThread("stamp-overlay").also { it.start(); stampThread = it }
        val effect = OverlayEffect(
            CameraEffect.VIDEO_CAPTURE, 0, Handler(thread.looper)
        ) { t -> Log.e(TAG, "Overlay error", t) }

        // Kiểu "nhẹ": nền gần như trong suốt, chữ trắng có bóng mờ → đọc rõ trên nền sáng
        // mà không thành một ô UI đè lên hình
        val bg = Paint().apply { color = Color.argb(55, 0, 0, 0) }
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 255, 255, 255)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255)
            typeface = Typeface.MONOSPACE
        }
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        var cleared = false
        var cachedSecond = -1L
        var cachedClock = ""

        effect.setOnDrawListener { frame ->
            val c = frame.overlayCanvas
            // Không quay → xoá lớp chữ 1 lần rồi thôi (không tốn CPU mỗi khung hình)
            if (!stampActive) {
                if (!cleared) { c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR); cleared = true }
                return@setOnDrawListener true
            }
            cleared = false
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            // Đồng hồ chỉ định dạng lại mỗi giây 1 lần
            val sec = System.currentTimeMillis() / 1000
            if (sec != cachedSecond) { cachedSecond = sec; cachedClock = fmt.format(Date(sec * 1000)) }
            val w = frame.size.width.toFloat()
            val h = frame.size.height.toFloat()

            // Xoay canvas để chữ đứng thẳng theo hướng video khi phát lại
            c.save()
            val uw: Float
            val uh: Float
            when (frame.rotationDegrees) {
                90 -> { c.translate(0f, h); c.rotate(-90f); uw = h; uh = w }
                180 -> { c.translate(w, h); c.rotate(180f); uw = w; uh = h }
                270 -> { c.translate(w, 0f); c.rotate(90f); uw = h; uh = w }
                else -> { uw = w; uh = h }
            }

            val base = min(uw, uh)
            big.textSize = base / 20f
            small.textSize = base / 30f
            // Bóng mờ quanh chữ (thay cho khung nền đậm)
            big.setShadowLayer(big.textSize * 0.18f, 0f, big.textSize * 0.06f, Color.argb(200, 0, 0, 0))
            small.setShadowLayer(small.textSize * 0.2f, 0f, small.textSize * 0.06f, Color.argb(200, 0, 0, 0))
            val pad = small.textSize * 0.6f
            val lines = listOfNotNull(
                "NV: $stampStaff",
                "KH: $stampCustomer",
                stampWarranty.ifEmpty { null },
                cachedClock
            )
            val codeText = "MÃ: $stampCode"
            val boxW = max(big.measureText(codeText), lines.maxOf { small.measureText(it) }) + pad * 2
            val lineH = small.textSize * 1.3f
            val boxH = big.textSize * 1.3f + lineH * lines.size + pad * 1.2f
            val x0 = pad
            val y0 = pad
            c.drawRoundRect(x0, y0, x0 + boxW, y0 + boxH, pad, pad, bg)

            var y = y0 + pad * 0.4f + big.textSize
            c.drawText(codeText, x0 + pad, y, big)
            y += big.textSize * 0.3f
            for (s in lines) {
                y += lineH
                c.drawText(s, x0 + pad, y, small)
            }
            c.restore()
            true
        }
        return effect
    }

    // ================= QUAY VIDEO =================

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: run { toast("Camera chưa sẵn sàng"); lockOrientation(false); backToIdle(); return }
        val code = confirmedCode ?: run { toast("Chưa có mã"); lockOrientation(false); backToIdle(); return }
        // Video theo đúng chiều đang cầm máy (ngang → video ngang, dọc → video dọc)
        vc.targetRotation = displayRotation()

        recStart = Date()
        val name = safeFileName(code) + "_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(recStart)
        recFileName = "$name.mp4"
        recDurationNanos = 0L
        recTab = Store.currentTab(this, recStart)

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, VIDEO_DIR)
            // Lưu thông tin vào mô tả file để màn hình danh sách hiển thị lại
            put(MediaStore.Video.Media.DESCRIPTION, "NV: $staff | KH: $customer")
        }
        val opts = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        stampCode = code
        stampStaff = staff
        stampCustomer = customer + if (machineType.isNotEmpty()) "  |  $machineType" else ""
        stampWarranty = if (warrantyDate.isNotEmpty()) {
            val dur = if (warrantyMonths % 12 == 0) "${warrantyMonths / 12} năm" else "$warrantyMonths tháng"
            "BH: $dur (tạo $warrantyDate)"
        } else "BH: không"
        stampActive = true

        var pending = vc.output.prepareRecording(this, opts)
        if (granted(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()

        paused = false
        recording = pending.start(ContextCompat.getMainExecutor(this)) { onRecordEvent(it) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateButtons()
    }

    private fun togglePause() {
        val r = recording ?: return
        if (paused) r.resume() else r.pause()
    }

    private fun onRecordEvent(ev: VideoRecordEvent) {
        when (ev) {
            is VideoRecordEvent.Status -> {
                recDurationNanos = ev.recordingStats.recordedDurationNanos
                val s = TimeUnit.NANOSECONDS.toSeconds(recDurationNanos)
                b.tvTimer.text = String.format(
                    Locale.US, "%s %02d:%02d", if (paused) "❚❚" else "●", s / 60, s % 60
                )
            }
            is VideoRecordEvent.Pause -> { paused = true; updateButtons() }
            is VideoRecordEvent.Resume -> { paused = false; updateButtons() }
            is VideoRecordEvent.Finalize -> {
                stampActive = false
                lockOrientation(false)
                recording = null
                paused = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (ev.outputResults.outputUri != Uri.EMPTY) onVideoSaved(ev.outputResults.outputUri)
                else { toast("Lỗi quay video (mã lỗi ${ev.error})"); backToIdle() }
                updateButtons()
            }
            else -> Unit
        }
    }

    private fun onVideoSaved(videoUri: Uri) {
        val row = JSONObject().apply {
            // Khớp cột sheet: Ngày | Tên khách | Mã mạch | Mã máy | Thời gian quay clip |
            //                  Người Test | Loại Máy | Ngày tạo bảo hành | Ghi Chú (+ cột kỹ thuật)
            put("id", UUID.randomUUID().toString())
            put("date", SimpleDateFormat("dd/MM/yyyy", Locale.US).format(recStart))
            put("customer", customer)
            put("board", board)
            put("code", confirmedCode.orEmpty())
            put("time", SimpleDateFormat("HH:mm:ss", Locale.US).format(recStart))
            put("staff", staff)
            put("type", machineType)
            put("warranty_date", warrantyDate)
            put("note", note)
            put("sheet", recTab)
            put("file", recFileName)
            put("duration", TimeUnit.NANOSECONDS.toSeconds(recDurationNanos))
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        }
        SheetLogger.enqueue(this, row)
        // Tải video lên Google Drive (chạy nền, mặc định chỉ khi có Wi‑Fi)
        DriveUploader.enqueue(this, videoUri, recFileName, row.optString("id"), recTab, confirmedCode.orEmpty())
        lifecycleScope.launch {
            val sent = SheetLogger.flush(this@MainActivity)
            val left = SheetLogger.pendingCount(this@MainActivity)
            when {
                prefs.getString(SheetLogger.KEY_URL, "").isNullOrBlank() ->
                    Toast.makeText(this@MainActivity,
                        "Chưa cài link Apps Script (⚙ CÀI ĐẶT) — $left dòng đang chờ gửi Sheet",
                        Toast.LENGTH_LONG).show()
                sent > 0 && left == 0 -> toast("Đã ghi lên Google Sheet")
                left > 0 -> toast("Chưa gửi được Sheet ($left dòng) — sẽ gửi lại sau")
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Đã lưu video")
            .setMessage("$recFileName\n\nXem / xoá trong LỊCH SỬ QUAY.\nSản phẩm tiếp theo: bấm BẮT ĐẦU.")
            .setPositiveButton("OK", null)
            .show()
        backToIdle()
    }

    // ================= CÀI ĐẶT =================

    /**
     * Màn quản lý 1 danh sách (nhân viên / loại máy / sheet): ô nhập + THÊM ở trên,
     * danh sách cuộn trong khung cố định ở dưới, mỗi dòng có nút xoá.
     * Sửa trực tiếp vào [items]; [onDone] chạy khi đóng để cập nhật dòng tóm tắt.
     */
    private fun showListEditor(title: String, hint: String, items: MutableList<String>, onDone: () -> Unit) {
        val d = com.quaytest.app.databinding.DialogListEditBinding.inflate(layoutInflater)
        d.tilNew.hint = hint

        class VH(val v: com.quaytest.app.databinding.ItemListRowBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(v.root)
        lateinit var adapter: androidx.recyclerview.widget.RecyclerView.Adapter<VH>
        fun refresh() {
            d.tvCount.text = "ĐÃ CÓ ${items.size}"
            d.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
        }
        adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<VH>() {
            override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int) =
                VH(com.quaytest.app.databinding.ItemListRowBinding.inflate(layoutInflater, p, false))
            override fun getItemCount() = items.size
            override fun onBindViewHolder(h: VH, pos: Int) {
                val name = items[pos]
                h.v.tvName.text = name
                h.v.btnDel.setOnClickListener {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setMessage("Xoá \"$name\" khỏi danh sách?")
                        .setPositiveButton("XOÁ") { _, _ -> items.remove(name); refresh() }
                        .setNegativeButton("HUỶ", null).show()
                }
            }
        }
        d.list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        d.list.addItemDecoration(
            androidx.recyclerview.widget.DividerItemDecoration(this, androidx.recyclerview.widget.DividerItemDecoration.VERTICAL)
        )
        d.list.adapter = adapter

        fun add() {
            val v = d.etNew.text.toString().trim().replace(Regex("\\s+"), " ")
            when {
                v.isEmpty() -> Unit
                items.any { it.equals(v, true) } -> d.tilNew.error = "Đã có \"$v\""
                else -> {
                    d.tilNew.error = null
                    items += v
                    refresh()
                    d.list.scrollToPosition(items.size - 1)
                }
            }
            d.etNew.setText("")
        }
        d.btnAdd.setOnClickListener { add() }
        d.etNew.setOnEditorActionListener { _, _, _ -> add(); true }
        refresh()

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(d.root)
            .setPositiveButton("XONG") { _, _ ->
                // Ô nhập còn chữ chưa bấm THÊM → vẫn thêm cho khỏi mất
                val v = d.etNew.text.toString().trim()
                if (v.isNotEmpty() && items.none { it.equals(v, true) }) items += v
                onDone()
            }
            .setOnCancelListener { onDone() }
            .show()
            .also { dlg -> dlg.window?.decorView?.let { it.post { it.tapOutsideHidesKeyboard() } } }
    }

    /** "Phúc, Tuấn, Tài" hoặc dòng nhắc khi trống. */
    private fun summary(items: List<String>, empty: String) =
        if (items.isEmpty()) empty else items.joinToString(", ")

    /** Cài đặt ít khi đổi: danh sách nhân viên, danh sách sheet, link bảng tính + Apps Script. */
    private fun showConfigDialog() {
        if (dialogOpen()) return
        val d = DialogSettingsBinding.inflate(layoutInflater)
        d.etSheetLink.setText(prefs.getString(SheetLogger.KEY_SHEET_LINK, SheetLogger.DEFAULT_SHEET_LINK))
        d.etScriptUrl.setText(prefs.getString(SheetLogger.KEY_URL, ""))
        // Danh sách sửa trong bộ nhớ, chỉ lưu khi bấm LƯU (HUỶ = bỏ thay đổi)
        val staffItems = Store.staffList(this).toMutableList()
        val typeItems = Store.typeList(this).toMutableList()
        val sheetItems = Store.getList(this, Store.KEY_SHEET_LIST, listOf(Store.DEFAULT_TAB)).toMutableList()
        fun refreshSummaries() {
            d.tvStaffTitle.text = "Nhân viên test (${staffItems.size})"
            d.tvStaffSum.text = summary(staffItems, "⚠ Chưa có — bấm SỬA để thêm")
            d.tvTypeTitle.text = "Loại máy (${typeItems.size})"
            d.tvTypeSum.text = summary(typeItems, "⚠ Chưa có — bấm SỬA để thêm")
            d.tvSheetTitle.text = "Danh sách sheet (${sheetItems.size})"
            d.tvSheetSum.text = summary(sheetItems, "Log")
        }
        refreshSummaries()
        d.btnEditStaff.setOnClickListener {
            showListEditor("Nhân viên test", "Tên nhân viên mới", staffItems) { refreshSummaries() }
        }
        d.btnEditType.setOnClickListener {
            showListEditor("Loại máy", "Loại máy mới (vd. MDB 10đ)", typeItems) { refreshSummaries() }
        }
        d.btnEditSheet.setOnClickListener {
            showListEditor("Danh sách sheet", "Tên sheet mới", sheetItems) { refreshSummaries() }
        }
        d.swDrive.isChecked = Store.driveOn(this)
        d.swDriveWifi.isChecked = Store.driveWifiOnly(this)
        d.swDriveWifi.isEnabled = d.swDrive.isChecked
        d.swDrive.setOnCheckedChangeListener { _, on -> d.swDriveWifi.isEnabled = on }
        d.rowStaff.setOnClickListener { d.btnEditStaff.performClick() }
        d.rowType.setOnClickListener { d.btnEditType.performClick() }
        d.swAutoMonth.isChecked = Store.autoMonth(this)
        d.sheetManualBox.visibility = if (d.swAutoMonth.isChecked) View.GONE else View.VISIBLE
        d.swAutoMonth.setOnCheckedChangeListener { _, on ->
            d.sheetManualBox.visibility = if (on) View.GONE else View.VISIBLE
        }

        fun readFields(): Pair<String, String> {
            var sheet = d.etSheetLink.text.toString().trim()
            var script = d.etScriptUrl.text.toString().trim()
            // Dán nhầm ô thì tự xếp lại cho đúng
            if (SheetLogger.isScriptUrl(sheet) && !SheetLogger.isScriptUrl(script)) { script = sheet; sheet = "" }
            if (SheetLogger.isSheetLink(script) && !SheetLogger.isSheetLink(sheet)) { sheet = script; script = "" }
            return sheet to script
        }
        fun status() {
            val pending = SheetLogger.pendingCount(this)
            val script = readFields().second
            d.tvStatus.text = buildString {
                append(
                    when {
                        script.isEmpty() -> "⚠ Chưa có link Apps Script → chưa ghi được lên Sheet"
                        !SheetLogger.isScriptUrl(script) -> "⚠ Link Apps Script phải dạng script.google.com/.../exec"
                        else -> "Link Apps Script: OK"
                    }
                )
                if (pending > 0) append("\nCòn $pending dòng chờ gửi")
            }
        }
        status()

        d.btnTest.setOnClickListener {
            val script = readFields().second
            if (!SheetLogger.isScriptUrl(script)) { status(); return@setOnClickListener }
            d.btnTest.isEnabled = false
            d.tvStatus.text = "Đang thử kết nối..."
            lifecycleScope.launch {
                val tabNow = if (d.swAutoMonth.isChecked) Store.monthTab()
                    else Store.getString(this@MainActivity, Store.KEY_SHEET_TAB, Store.DEFAULT_TAB)
                val res = withContext(Dispatchers.IO) { SheetLogger.fetch(script, "", 1, tabNow) }
                d.btnTest.isEnabled = true
                d.tvStatus.text = if (res != null)
                    "✔ Kết nối được — tab $tabNow đang có ${res.optInt("total")} dòng" +
                        (if (res.optInt("version") < 3) "\n⚠ Script bản cũ — cập nhật Code.gs để chọn được tab" else "")
                else
                    "✘ Không kết nối được. Kiểm tra: đã Triển khai dạng Ứng dụng web, quyền 'Bất kỳ ai', đúng link /exec"
            }
        }
        d.btnOpenSheet.setOnClickListener {
            val sheet = readFields().first.ifEmpty { SheetLogger.DEFAULT_SHEET_LINK }
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sheet))) }
            catch (e: Exception) { toast("Không mở được link") }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Cài đặt")
            .setView(d.root)
            .setPositiveButton("LƯU") { _, _ ->
                val (sheet, script) = readFields()
                prefs.edit()
                    .putString(SheetLogger.KEY_SHEET_LINK, sheet.ifEmpty { SheetLogger.DEFAULT_SHEET_LINK })
                    .putString(SheetLogger.KEY_URL, script)
                    .apply()
                Store.setAutoMonth(this, d.swAutoMonth.isChecked)
                Store.setDrive(this, d.swDrive.isChecked, d.swDriveWifi.isChecked)
                Store.setList(this, Store.KEY_TYPE_LIST, typeItems)
                Store.setList(this, Store.KEY_STAFF_LIST, staffItems)
                Store.setList(this, Store.KEY_SHEET_LIST, sheetItems.ifEmpty { mutableListOf(Store.DEFAULT_TAB) })
                // Nhân viên / sheet đang chọn bị xoá khỏi danh sách → bỏ chọn
                if (staff.isNotEmpty() && staff !in staffItems) { staff = ""; Store.putString(this, Store.KEY_STAFF, "") }
                if (sheetTab !in Store.sheetList(this)) {
                    sheetTab = Store.sheetList(this).first(); Store.putString(this, Store.KEY_SHEET_TAB, sheetTab)
                }
                updateInfo()
                lifecycleScope.launch { SheetLogger.flush(this@MainActivity) }
            }
            .setNegativeButton("HUỶ", null)
            .showSingle()
    }

    // ================= GIAO DIỆN =================

    private fun updateInfo() {
        val code = confirmedCode
        b.tvCode.text = code ?: "— chưa có —"
        b.tvCode.setTextColor(
            if (code != null) Color.parseColor("#00CC66") else Color.parseColor("#94A3B8")
        )
        b.tvPeople.text = listOf(
            "NV: ${staff.ifBlank { "?" }}",
            "Sheet " + Store.currentTab(this),
            machineType.ifBlank { null }
        ).filterNotNull().joinToString("  •  ")
        b.tvPeople.maxLines = 1
        b.tvPeople.ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun updateButtons() {
        val rec = recording != null
        val idle = !rec && !countingDown && !reading && !selecting

        // Nút BẮT ĐẦU giữa màn hình chỉ hiện khi rảnh
        b.btnStart.visibility = if (idle && confirmedCode == null) View.VISIBLE else View.GONE
        // Đang khoanh vùng: hiện hướng dẫn + nút phụ
        b.tvHint.visibility = if (selecting || reading) View.VISIBLE else View.GONE
        b.tvHint.text = if (reading) "Đang đọc mã..." else "Kéo tay khoanh vùng MÃ THIẾT BỊ"
        b.selPanel.visibility = if (selecting) View.VISIBLE else View.GONE
        b.btnReuse.visibility = if (selecting && lastSelection != null) View.VISIBLE else View.GONE
        b.selection.isEnabled = selecting

        b.btnPause.isEnabled = rec
        b.btnPause.text = if (paused) "TIẾP TỤC" else "TẠM DỪNG"
        b.btnStop.isEnabled = rec || countingDown
        b.btnStop.text = if (countingDown) "HUỶ" else "■ DỪNG"
        b.btnSheet.isEnabled = idle
        b.btnConfig.isEnabled = idle
        // Nút bị khoá thì mờ hẳn để nhân viên không bấm nhầm
        listOf(b.btnPause, b.btnStop, b.btnSheet, b.btnConfig)
            .forEach { it.alpha = if (it.isEnabled) 1f else 0.3f }

        b.tvTimer.visibility = if (rec) View.VISIBLE else View.GONE
        b.statusBar.setBackgroundColor(
            Color.parseColor(
                when {
                    rec && paused -> "#FBBF24"
                    rec -> "#FF3333"
                    confirmedCode != null -> "#00CC66"
                    else -> "#0055FF"
                }
            )
        )
    }

    /** Bỏ dấu tiếng Việt (Ả→A, Đ→D), in hoa, chỉ giữ A-Z 0-9 - và 1 khoảng trắng. */
    private fun normalizeCode(s: String): String =
        java.text.Normalizer.normalize(
            s.uppercase().replace('Đ', 'D').replace('đ', 'D'), java.text.Normalizer.Form.NFD
        )
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^A-Z0-9\\- ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun safeFileName(code: String): String =
        code.replace(Regex("[^A-Za-z0-9-]+"), "_").trim('_').ifEmpty { "NOCODE" }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
