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

    /** Mã đã xác nhận cho lần quay này. */
    private var confirmedCode: String? = null
    private var staff = ""
    private var sheetTab = Store.DEFAULT_TAB
    // Các cột theo sheet mẫu: Tên khách, Mã mạch, Loại máy, Ngày tạo bảo hành, Ghi chú
    private var customer = ""        // giữ lại cho máy sau (thường test cả lô của 1 khách)
    private var machineType = ""     // giữ lại cho máy sau
    private var board = ""           // xoá sau mỗi lần quay
    private var note = ""            // xoá sau mỗi lần quay
    private var warrantyDate = ""    // "" = không tạo bảo hành
    private var warrantyToday = false // lần trước chọn "Hôm nay" → lần sau chọn sẵn

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
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Tự chừa chỗ cho thanh trạng thái / thanh vuốt (ColorOS hay vẽ đè lên app)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // ColorOS báo inset thanh vuốt chỉ 2px nhưng vẫn vẽ thanh đè → chừa tối thiểu 20dp
            val minBottom = (20 * resources.displayMetrics.density).toInt()
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, minBottom))
            WindowInsetsCompat.CONSUMED
        }

        prefs = getSharedPreferences(SheetLogger.PREFS, MODE_PRIVATE)
        staff = Store.getString(this, Store.KEY_STAFF)
        sheetTab = Store.getString(this, Store.KEY_SHEET_TAB, Store.DEFAULT_TAB)
        machineType = Store.getString(this, Store.KEY_LAST_TYPE)

        b.btnStart.setOnClickListener { startSelecting() }
        b.selection.onSelected = { r -> if (selecting) onAreaChosen(r) }
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

        b.selection.setSelectionRect(null)
        updateInfo()
        updateButtons()

        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (need.all { granted(it) }) startCamera() else permLauncher.launch(need)

        lifecycleScope.launch { SheetLogger.flush(this@MainActivity) }
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
        b.selection.setSelectionRect(r)
        updateButtons()
        focusThenRead()
    }

    private fun backToIdle() {
        selecting = false
        reading = false
        confirmedCode = null
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

        fun suggest(key: String) =
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, Store.getList(this, key))

        // Khách + loại máy giữ lại từ máy trước (test cả lô), có gợi ý các giá trị cũ
        d.etCustomer.setText(customer)
        d.etCustomer.setAdapter(suggest(Store.KEY_RECENT_CUSTOMERS))
        // Loại máy: chọn từ danh sách trong Cài đặt (giữ loại của máy trước nếu còn trong danh sách)
        val types = Store.typeList(this)
        d.ddType.setAdapter(adapter(types))
        if (machineType in types) d.ddType.setText(machineType, false)
        else if (types.size == 1) d.ddType.setText(types[0], false)
        if (types.isEmpty()) d.tilType.helperText = "Chưa có loại máy — thêm ở ⚙ CÀI ĐẶT"
        d.etBoard.apply {
            setText(board)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(40))
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
                    val v = normalizeCode(bc.rawValue.orEmpty())
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

        // Ngày tạo bảo hành: Không / Hôm nay / Chọn ngày khác
        val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        val today = dateFmt.format(Date())
        var pickedWarranty = if (warrantyToday) today else ""
        val optNone = "Không tạo bảo hành"
        val optToday = "Hôm nay ($today)"
        val optPick = "Chọn ngày khác…"
        d.ddWarranty.setAdapter(adapter(listOf(optNone, optToday, optPick)))
        d.ddWarranty.setText(if (pickedWarranty.isEmpty()) optNone else optToday, false)
        d.ddWarranty.setOnItemClickListener { _, _, pos, _ ->
            when (pos) {
                0 -> pickedWarranty = ""
                1 -> pickedWarranty = today
                2 -> {
                    val cal = Calendar.getInstance()
                    android.app.DatePickerDialog(this, { _, y, m, day ->
                        cal.set(y, m, day)
                        pickedWarranty = dateFmt.format(cal.time)
                        d.ddWarranty.setText(pickedWarranty, false)
                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                }
            }
        }

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
            board = normalizeCode(d.etBoard.text.toString())
            note = clean(d.etNote.text.toString())
            staff = pickedStaff
            if (!autoMonth) sheetTab = d.ddSheet.text.toString().trim().ifEmpty { Store.DEFAULT_TAB }
            warrantyDate = pickedWarranty
            warrantyToday = pickedWarranty == today
            // Nhớ làm mặc định / gợi ý cho lần sau
            Store.putString(this, Store.KEY_STAFF, staff)
            Store.putString(this, Store.KEY_SHEET_TAB, sheetTab)
            Store.putString(this, Store.KEY_LAST_TYPE, machineType)
            Store.remember(this, Store.KEY_RECENT_CUSTOMERS, customer)
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
        backToIdle()
        toast("Đã huỷ")
    }

    // ================= CAMERA =================

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(b.preview.surfaceProvider)

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.FHD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                ).build()
            val vc = VideoCapture.withOutput(recorder)

            provider.unbindAll()
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
        }, ContextCompat.getMainExecutor(this))
    }

    /** Vẽ khung thông tin góc trên-trái của video, kiểu Titanium (nền đen, vạch xanh, chữ mono). */
    private fun createStampEffect(): OverlayEffect {
        val effect = OverlayEffect(
            CameraEffect.VIDEO_CAPTURE, 0, Handler(Looper.getMainLooper())
        ) { t -> Log.e(TAG, "Overlay error", t) }

        val bg = Paint().apply { color = Color.argb(170, 17, 17, 17) }
        val accent = Paint().apply { color = Color.parseColor("#0055FF") }
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.MONOSPACE
        }
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

        effect.setOnDrawListener { frame ->
            val c = frame.overlayCanvas
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
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
            big.textSize = base / 16f
            small.textSize = base / 26f
            val pad = small.textSize * 0.6f
            val lines = listOfNotNull(
                "NV: $stampStaff",
                "KH: $stampCustomer",
                stampWarranty.ifEmpty { null },
                fmt.format(Date())
            )
            val codeText = "MÃ: $stampCode"
            val boxW = max(big.measureText(codeText), lines.maxOf { small.measureText(it) }) + pad * 3
            val lineH = small.textSize * 1.3f
            val boxH = big.textSize * 1.3f + lineH * lines.size + pad * 1.5f
            val x0 = pad
            val y0 = pad
            c.drawRect(x0, y0, x0 + boxW, y0 + boxH, bg)
            c.drawRect(x0, y0, x0 + pad * 0.5f, y0 + boxH, accent)

            var y = y0 + pad * 0.5f + big.textSize
            c.drawText(codeText, x0 + pad * 1.5f, y, big)
            y += big.textSize * 0.3f
            for (s in lines) {
                y += lineH
                c.drawText(s, x0 + pad * 1.5f, y, small)
            }
            c.restore()
            true
        }
        return effect
    }

    // ================= QUAY VIDEO =================

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: run { toast("Camera chưa sẵn sàng"); backToIdle(); return }
        val code = confirmedCode ?: run { toast("Chưa có mã"); backToIdle(); return }

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
        stampWarranty = if (warrantyDate.isNotEmpty()) "BH tạo: $warrantyDate" else ""

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
                recording = null
                paused = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (ev.outputResults.outputUri != Uri.EMPTY) onVideoSaved()
                else { toast("Lỗi quay video (mã lỗi ${ev.error})"); backToIdle() }
                updateButtons()
            }
            else -> Unit
        }
    }

    private fun onVideoSaved() {
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

    /** Hiện danh sách dạng chip có nút xoá; thêm bằng ô nhập + nút THÊM / Enter. */
    private fun bindChipList(
        group: ChipGroup,
        input: android.widget.EditText,
        addBtn: View,
        items: MutableList<String>
    ) {
        fun render() {
            group.removeAllViews()
            items.forEach { name ->
                group.addView(Chip(this).apply {
                    text = name
                    isCloseIconVisible = true
                    setOnCloseIconClickListener { items.remove(name); render() }
                })
            }
        }
        fun add() {
            val v = input.text.toString().trim().replace(Regex("\\s+"), " ")
            if (v.isNotEmpty() && items.none { it.equals(v, true) }) { items += v; render() }
            input.setText("")
        }
        addBtn.setOnClickListener { add() }
        input.setOnEditorActionListener { _, _, _ -> add(); true }
        render()
    }

    /** Cài đặt ít khi đổi: danh sách nhân viên, danh sách sheet, link bảng tính + Apps Script. */
    private fun showConfigDialog() {
        if (dialogOpen()) return
        val d = DialogSettingsBinding.inflate(layoutInflater)
        d.etSheetLink.setText(prefs.getString(SheetLogger.KEY_SHEET_LINK, SheetLogger.DEFAULT_SHEET_LINK))
        d.etScriptUrl.setText(prefs.getString(SheetLogger.KEY_URL, ""))
        val staffItems = Store.staffList(this).toMutableList()
        val sheetItems = Store.getList(this, Store.KEY_SHEET_LIST, listOf(Store.DEFAULT_TAB)).toMutableList()
        bindChipList(d.cgStaff, d.etNewStaff, d.btnAddStaff, staffItems)
        val typeItems = Store.typeList(this).toMutableList()
        bindChipList(d.cgType, d.etNewType, d.btnAddType, typeItems)
        bindChipList(d.cgSheet, d.etNewSheet, d.btnAddSheet, sheetItems)
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
                // Ô nhập còn chữ chưa bấm THÊM → vẫn thêm vào cho khỏi mất
                d.etNewStaff.text.toString().trim()
                    .takeIf { it.isNotEmpty() && staffItems.none { s -> s.equals(it, true) } }
                    ?.let { staffItems += it }
                d.etNewSheet.text.toString().trim()
                    .takeIf { it.isNotEmpty() && sheetItems.none { s -> s.equals(it, true) } }
                    ?.let { sheetItems += it }
                Store.setAutoMonth(this, d.swAutoMonth.isChecked)
                d.etNewType.text.toString().trim()
                    .takeIf { it.isNotEmpty() && typeItems.none { s -> s.equals(it, true) } }
                    ?.let { typeItems += it }
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
