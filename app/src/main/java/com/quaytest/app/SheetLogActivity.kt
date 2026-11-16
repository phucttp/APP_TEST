package com.quaytest.app

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Size
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.quaytest.app.databinding.ActivitySheetLogBinding
import com.quaytest.app.databinding.ItemSheetRowBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LỊCH SỬ QUAY: mỗi dòng = 1 lần quay (video trong máy + dòng nhật ký trên Google Sheet,
 * ghép theo tên file). Chạm = xem video. Giữ lâu = xoá cả video lẫn dòng trên Sheet.
 */
class SheetLogActivity : AppCompatActivity() {

    enum class Status { SHEET, PENDING, VIDEO_ONLY }

    data class Item(
        val status: Status,
        val id: String,              // ID dòng (rỗng = dòng nhập tay trên Sheet / chỉ có video)
        val code: String,
        val date: String,
        val time: String,
        val customer: String,
        val staff: String,
        val type: String,
        val warranty: String,
        val file: String,
        val video: Video?
    )

    data class Video(val uri: Uri, val name: String, val dateMs: Long, val durationMs: Long, val size: Long)

    private lateinit var b: ActivitySheetLogBinding
    private val adapter = Adapter()
    private var tab = Store.DEFAULT_TAB
    private var scriptVersion = 0
    private val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    // ===== Google Drive =====
    private var uploadedFiles: Set<String> = emptySet()   // đã lên Drive
    private var uploadingFiles: Set<String> = emptySet()  // đang chờ / đang tải
    private var wasUploading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySheetLogBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        tab = Store.currentTab(this)
        b.root.tapOutsideHidesKeyboard()
        b.btnLoad.setOnClickListener { load() }
        b.etSearch.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { load(); true } else false
        }
        b.btnSync.setOnClickListener { askSyncDrive() }
        // Theo dõi các việc tải Drive đang chạy nền → cập nhật tiến độ + nhãn từng dòng
        androidx.work.WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(DriveUploader.TAG_ALL)
            .observe(this) { infos -> onDriveWork(infos) }
    }

    private fun onDriveWork(infos: List<androidx.work.WorkInfo>) {
        val active = infos.filter { !it.state.isFinished }
        uploadingFiles = active.flatMap { w ->
            w.tags.filter { it.startsWith(DriveUploader.TAG_FILE) }.map { it.removePrefix(DriveUploader.TAG_FILE) }
        }.toSet()
        val running = active.count { it.state == androidx.work.WorkInfo.State.RUNNING }
        val failed = infos.count { it.state == androidx.work.WorkInfo.State.FAILED }
        uploadedFiles = Store.uploadedFiles(this)
        b.tvDrive.text = when {
            active.isNotEmpty() ->
                "☁ Đang tải lên Drive: còn ${active.size} video" + if (running == 0) " (chờ mạng…)" else ""
            failed > 0 -> "⚠ $failed video tải lỗi — bấm ĐỒNG BỘ để thử lại"
            else -> "☁ Đã lên Drive: ${uploadedFiles.size} video"
        }
        b.btnSync.isEnabled = active.isEmpty()
        b.btnSync.alpha = if (b.btnSync.isEnabled) 1f else 0.4f
        if (wasUploading && active.isEmpty()) load()   // vừa tải xong → làm mới danh sách
        wasUploading = active.isNotEmpty()
        adapter.notifyDataSetChanged()
    }

    /** Tải lên Drive mọi video trong máy chưa có trên Drive (theo danh sách đang xem). */
    private fun askSyncDrive() {
        val script = scriptUrl()
        if (!SheetLogger.isScriptUrl(script)) { toast("Chưa có link Apps Script (⚙ CÀI ĐẶT)"); return }
        val todo = adapter.items.filter {
            it.video != null && it.file.isNotEmpty() && it.file !in uploadedFiles && it.file !in uploadingFiles
        }
        if (todo.isEmpty()) { toast("Không còn video nào cần tải lên Drive"); return }
        val mb = todo.sumOf { it.video!!.size } / (1024.0 * 1024.0)
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val metered = cm.isActiveNetworkMetered
        MaterialAlertDialogBuilder(this)
            .setTitle("Đồng bộ Google Drive")
            .setMessage(String.format(Locale.US, "Tải %d video (~%.0f MB) lên Drive?", todo.size, mb) +
                (if (metered) "\n\n⚠ Đang dùng 4G/dữ liệu di động — có thể tốn dung lượng." else "") +
                "\n\nTải chạy nền, có thể thoát app; mất mạng sẽ tự thử lại.")
            .setPositiveButton("TẢI LÊN") { _, _ ->
                todo.forEach {
                    DriveUploader.enqueue(this, it.video!!.uri, it.file,
                        if (it.status == Status.VIDEO_ONLY) "" else it.id, tab, it.code, manual = true)
                }
                toast("Đã xếp ${todo.size} video vào hàng tải lên Drive")
            }
            .setNegativeButton("HUỶ", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    /** Nút "SHEET: X ▾" trên thanh tiêu đề để xem tab khác. */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "SHEET: $tab ▾").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != 1) return super.onOptionsItemSelected(item)
        // Lấy danh sách tab thật trong bảng tính; mất mạng / script cũ → dùng danh sách trong máy
        lifecycleScope.launch {
            val url = scriptUrl()
            val remote = if (SheetLogger.isScriptUrl(url))
                withContext(Dispatchers.IO) { SheetLogger.fetchTabs(url) } else null
            val tabs = ((remote ?: Store.sheetList(this@SheetLogActivity)) + Store.currentTab(this@SheetLogActivity) + tab)
                .filter { !it.startsWith("_") }   // ẩn tab kỹ thuật như _TEST_APP
                .distinct()
                .toTypedArray()
            MaterialAlertDialogBuilder(this@SheetLogActivity)
                .setTitle(if (remote != null) "Chọn sheet (tab trong bảng tính)" else "Chọn sheet (chưa tải được từ Google)")
                .setSingleChoiceItems(tabs, tabs.indexOf(tab)) { dlg, which ->
                    tab = tabs[which]; invalidateOptionsMenu(); dlg.dismiss(); load()
                }
                .show()
        }
        return true
    }

    private fun scriptUrl() = Store.getString(this, SheetLogger.KEY_URL).trim()

    // ================= TẢI + GHÉP =================

    private fun load() {
        b.root.hideKeyboard()
        val q = b.etSearch.text?.toString()?.trim()?.uppercase().orEmpty()
        val url = scriptUrl()
        b.btnLoad.isEnabled = false
        b.tvStatus.text = "Đang tải..."
        lifecycleScope.launch {
            val videos = withContext(Dispatchers.IO) { queryVideos() }
            val remote = if (SheetLogger.isScriptUrl(url))
                withContext(Dispatchers.IO) { SheetLogger.fetch(url, q, 500, tab) } else null
            scriptVersion = remote?.optInt("version") ?: 0
            b.btnLoad.isEnabled = true

            val byFile = videos.associateBy { it.name }
            val used = HashSet<String>()
            val items = ArrayList<Item>()

            // 1) Dòng chờ gửi (của tab đang xem)
            SheetLogger.pendingRows(this@SheetLogActivity)
                .filter { it.optString("sheet", Store.DEFAULT_TAB).ifEmpty { Store.DEFAULT_TAB } == tab }
                .reversed()
                .forEach { o ->
                    val f = o.optString("file")
                    used += f
                    items += Item(Status.PENDING, o.optString("id"), o.optString("code"), o.optString("date"),
                        o.optString("time"), o.optString("customer"), o.optString("staff"), o.optString("type"),
                        o.optString("warranty_date"), f, byFile[f])
                }
            // 2) Dòng trên Sheet (mới nhất trước)
            remote?.optJSONArray("rows")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val f = o.optString("file")
                    if (f.isNotEmpty()) used += f
                    items += Item(Status.SHEET, o.optString("id"), o.optString("code"), o.optString("date"),
                        o.optString("time"), o.optString("customer"), o.optString("staff"), o.optString("type"),
                        o.optString("warranty_date"), f, if (f.isNotEmpty()) byFile[f] else null)
                }
            }
            // 3) Video trong máy chưa khớp dòng nào (chưa có Sheet / Sheet không tải được)
            videos.filter { it.name !in used }.forEach { v ->
                items += Item(Status.VIDEO_ONLY, "", codeFromFile(v.name),
                    dateFmt.format(Date(v.dateMs)), "", "", "", "", "", v.name, v)
            }

            val shown = if (q.isEmpty()) items else items.filter {
                "${it.code} ${it.customer} ${it.staff} ${it.type} ${it.file}".uppercase().contains(q)
            }
            adapter.items = shown
            adapter.notifyDataSetChanged()
            b.tvStatus.text = when {
                !SheetLogger.isScriptUrl(url) -> "Chưa có link Apps Script — chỉ hiện video trong máy"
                remote == null -> "Không tải được Sheet (mất mạng?) — đang hiện dữ liệu trong máy"
                else -> "Tab $tab: ${remote.optInt("total")} dòng • hiện ${shown.size}"
            }
        }
    }

    /** Lấy tên mã từ tên file: MA_yyyyMMdd_HHmmss.mp4 → MA */
    private fun codeFromFile(name: String) =
        name.removeSuffix(".mp4").replace(Regex("_\\d{8}_\\d{6}$"), "").replace('_', ' ')

    private fun queryVideos(): List<Video> {
        val out = ArrayList<Video>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE
        )
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("${MainActivity.VIDEO_DIR}%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext() && out.size < 300) {
                out += Video(
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                    c.getString(1).orEmpty(), c.getLong(2) * 1000, c.getLong(3), c.getLong(4)
                )
            }
        }
        return out
    }

    // ================= XEM / XOÁ =================

    private fun play(it: Item) {
        val v = it.video ?: run { toast("Video này không có trong máy (quay ở máy khác hoặc đã xoá)"); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(v.uri, "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { toast("Không có app xem video") }
    }

    private fun askDelete(it: Item) {
        val parts = ArrayList<String>()
        if (it.video != null) parts += "• Video trong máy"
        when (it.status) {
            Status.SHEET -> parts += if (it.id.isNotEmpty()) "• Dòng trên Google Sheet (tab $tab)"
                else "• (Dòng này nhập tay trên Sheet, không có ID → phải xoá trên Sheet bằng tay)"
            Status.PENDING -> parts += "• Dòng nhật ký đang chờ gửi"
            Status.VIDEO_ONLY -> {}
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Xoá lần quay ${it.code}?")
            .setMessage("Sẽ xoá:\n" + parts.joinToString("\n") + "\n\nKhông khôi phục được.")
            .setPositiveButton("XOÁ") { _, _ -> doDelete(it) }
            .setNegativeButton("HUỶ", null)
            .show()
    }

    private fun doDelete(it: Item) = lifecycleScope.launch {
        val msgs = ArrayList<String>()
        // 1) Dòng trên Sheet trước — lỗi thì giữ video để thử lại, tránh lệch dữ liệu
        if (it.status == Status.SHEET && it.id.isNotEmpty()) {
            if (scriptVersion < 2) {
                MaterialAlertDialogBuilder(this@SheetLogActivity)
                    .setTitle("Cần cập nhật Apps Script")
                    .setMessage("Script trên Google Sheet là bản cũ, chưa có lệnh xoá.\n\n" +
                        "Dán lại file Code.gs mới → Triển khai → Quản lý → ✏️ → Phiên bản mới → Triển khai.")
                    .setPositiveButton("OK", null).show()
                return@launch
            }
            val r = withContext(Dispatchers.IO) { SheetLogger.deleteRemote(scriptUrl(), tab, it.id) }
            when (r) {
                null -> { toast("Không xoá được trên Sheet (mất mạng?) — chưa xoá gì"); return@launch }
                true -> msgs += "dòng Sheet"
                false -> msgs += "(không thấy dòng trên Sheet)"
            }
        }
        // 2) Dòng chờ gửi
        if (it.status == Status.PENDING && it.id.isNotEmpty()) {
            SheetLogger.removePending(this@SheetLogActivity, it.id)
            msgs += "dòng chờ gửi"
        }
        // 3) Đang chờ tải Drive → huỷ (video trên Drive nếu đã lên thì script đã bỏ vào thùng rác ở bước 1)
        if (it.file.isNotEmpty()) DriveUploader.cancel(this@SheetLogActivity, it.file)
        // 4) Video trong máy
        it.video?.let { v ->
            try {
                contentResolver.delete(v.uri, null, null)
                msgs += "video"
            } catch (e: RecoverableSecurityException) {
                msgs += "(video do bản app cũ tạo — xoá trong Thư viện ảnh)"
            } catch (e: Exception) {
                msgs += "(không xoá được video)"
            }
        }
        toast("Đã xoá: " + msgs.joinToString(", "))
        load()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ================= DANH SÁCH =================

    inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        var items: List<Item> = emptyList()
        inner class VH(val v: ItemSheetRowBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemSheetRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val it = items[pos]
            h.v.tvCode.text = it.code.ifEmpty { "(không mã)" }
            h.v.tvLine1.text = listOf(
                "${it.date} ${it.time}".trim(),
                it.customer.takeIf { s -> s.isNotBlank() }?.let { s -> "KH: $s" },
                it.type.takeIf { s -> s.isNotBlank() },
                it.staff.takeIf { s -> s.isNotBlank() }?.let { s -> "NV: $s" }
            ).filterNotNull().filter { s -> s.isNotBlank() }.joinToString("  •  ")
            val driveState = when {
                it.file.isEmpty() -> null
                it.file in uploadedFiles -> "☁ Đã lên Drive"
                it.file in uploadingFiles -> "⏫ Đang tải Drive"
                else -> null
            }
            h.v.tvLine2.text = listOfNotNull(
                driveState,
                it.video?.let { v ->
                    val s = v.durationMs / 1000
                    String.format(Locale.US, "%02d:%02d", s / 60, s % 60) + "  " +
                        Formatter.formatShortFileSize(this@SheetLogActivity, v.size)
                },
                it.warranty.takeIf { s -> s.isNotBlank() }?.let { s -> "BH tạo: $s" }
            ).joinToString("   ")

            val (label, color, dark) = when (it.status) {
                Status.SHEET -> Triple("ĐÃ LƯU SHEET", "#00CC66", false)
                Status.PENDING -> Triple("CHƯA GỬI", "#FBBF24", true)
                Status.VIDEO_ONLY -> Triple("CHỈ CÓ VIDEO", "#64748B", false)
            }
            h.v.tvBadge.text = label
            h.v.tvBadge.setBackgroundColor(Color.parseColor(color))
            h.v.tvBadge.setTextColor(if (dark) Color.parseColor("#111111") else Color.WHITE)
            h.v.accent.setBackgroundColor(Color.parseColor(color))

            h.v.root.setOnClickListener { _ -> play(it) }
            h.v.root.setOnLongClickListener { _ -> askDelete(it); true }

            // Ảnh thu nhỏ video
            h.v.thumb.setImageDrawable(null)
            h.v.tvNoVideo.visibility = if (it.video == null) View.VISIBLE else View.GONE
            val v = it.video ?: return
            h.v.thumb.tag = v.uri
            lifecycleScope.launch {
                val bmp: Bitmap? = withContext(Dispatchers.IO) {
                    try { contentResolver.loadThumbnail(v.uri, Size(240, 180), null) } catch (e: Exception) { null }
                }
                if (h.v.thumb.tag == v.uri) h.v.thumb.setImageBitmap(bmp)
            }
        }
    }
}
