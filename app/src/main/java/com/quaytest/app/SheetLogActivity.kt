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
import com.quaytest.app.databinding.ActivitySheetLogBinding
import com.quaytest.app.databinding.ItemSheetRowBinding
import com.quaytest.app.databinding.ItemDayHeaderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
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
        val video: Video?,
        val driveId: String = ""     // fileId video trên Drive (lấy từ link ở ô Mã máy)
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
        b.btnSync.setOnClickListener { startSyncSelect() }
        // Chọn nhiều
        onBackPressedDispatcher.addCallback(this, backCb)
        b.btnSelClose.setOnClickListener { exitSelect() }
        b.btnSelAll.setOnClickListener {
            if (selected.size >= adapter.items.size) selected.clear()
            else { selected.clear(); selected += adapter.items.map { keyOf(it) } }
            refreshSelection()
        }
        b.btnSelDelete.setOnClickListener { askDeleteSelected() }
        b.btnSelUpload.setOnClickListener { uploadSelected() }
        // Theo dõi các việc tải Drive đang chạy nền → cập nhật tiến độ + nhãn từng dòng
        androidx.work.WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(DriveUploader.UNIQUE)
            .observe(this) { infos -> onDriveWork(infos) }
    }

    private fun onDriveWork(infos: List<androidx.work.WorkInfo>) {
        // Hàng chờ tải nằm trong máy (Store) — WorkInfo chỉ để biết đang chạy / chờ mạng + tiến độ
        uploadingFiles = Store.driveQueue(this).map { it.optString("file") }.toSet()
        uploadedFiles = Store.uploadedFiles(this)
        val run = infos.firstOrNull { it.state == androidx.work.WorkInfo.State.RUNNING }
        val p = run?.progress
        val code = p?.getString(DriveUploader.P_CODE).orEmpty()
        val n = uploadingFiles.size
        // Không hiện "Đã lên Drive: n video" nữa — mỗi video xong đã có thông báo riêng.
        // Chỉ hiện dòng này khi đang có video chờ / đang tải.
        b.tvDrive.text = when {
            n == 0 -> ""
            run == null -> "☁ Còn $n video chờ tải — sẽ tự tải khi có mạng"
            p?.getBoolean(DriveUploader.P_WAIT_NET, false) == true -> "☁ Mất mạng — còn $n video, tự tải tiếp khi có mạng"
            code.isNotEmpty() -> "☁ Đang tải $code: ${p?.getInt(DriveUploader.P_PCT, 0)}%  •  còn $n video"
            else -> "☁ Đang tải lên Drive  •  còn $n video"
        }
        b.tvDrive.visibility = if (n == 0) View.INVISIBLE else View.VISIBLE
        if (wasUploading && n == 0) load()   // vừa tải xong hết → làm mới danh sách
        wasUploading = n > 0
        adapter.notifyDataSetChanged()
    }

    // ================= CHỌN NHIỀU (xoá / tải lên Drive) =================

    private var selecting = false
    private val selected = LinkedHashSet<String>()
    private val backCb = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exitSelect()
    }

    private fun keyOf(it: Item) = it.file.ifEmpty { "row:${it.id}|${it.code}|${it.date}|${it.time}" }
    private fun selectedItems() = adapter.items.filter { keyOf(it) in selected }
    /** Chưa lên Drive (để chọn sẵn khi bấm ĐỒNG BỘ). */
    private fun canUpload(it: Item) = canPick(it) && it.file !in uploadedFiles
    /** Chọn để tải được: còn video trong máy, không đang tải (video đã tải trước đó sẽ được kiểm tra trên Drive). */
    private fun canPick(it: Item) = it.video != null && it.file.isNotEmpty() && it.file !in uploadingFiles

    private fun enterSelect(keys: Collection<String>) {
        selecting = true
        selected.clear(); selected += keys
        backCb.isEnabled = true
        refreshSelection()
    }

    private fun exitSelect() {
        selecting = false
        selected.clear()
        backCb.isEnabled = false
        refreshSelection()
    }

    private fun toggle(it: Item) {
        val k = keyOf(it)
        if (!selected.remove(k)) selected += k
        refreshSelection()
    }

    private fun refreshSelection() {
        b.selBar.visibility = if (selecting) View.VISIBLE else View.GONE
        b.driveBar.visibility = if (selecting) View.GONE else View.VISIBLE
        if (selecting) {
            val items = selectedItems()
            val mb = items.sumOf { it.video?.size ?: 0L } / 1048576.0
            b.tvSelCount.text = if (items.isEmpty()) "Chạm để chọn video"
                else String.format(Locale.US, if (mb < 10) "Đã chọn %d  •  %.1f MB" else "Đã chọn %d  •  %.0f MB", items.size, mb)
            val up = items.count { canPick(it) }
            b.btnSelUpload.text = if (up > 0) "☁ TẢI LÊN ($up)" else "☁ TẢI LÊN"
            b.btnSelUpload.isEnabled = up > 0
            b.btnSelUpload.alpha = if (up > 0) 1f else 0.35f
            b.btnSelDelete.text = if (items.isNotEmpty()) "XOÁ (${items.size})" else "XOÁ"
            b.btnSelDelete.isEnabled = items.isNotEmpty()
            b.btnSelDelete.alpha = if (items.isNotEmpty()) 1f else 0.35f
            b.btnSelAll.text = if (adapter.items.isNotEmpty() && selected.size >= adapter.items.size) "BỎ CHỌN" else "CHỌN HẾT"
        }
        adapter.notifyDataSetChanged()
    }

    /** Nút ĐỒNG BỘ DRIVE: vào chế độ chọn, chọn sẵn mọi video trong máy chưa lên Drive. */
    private fun startSyncSelect() {
        if (!SheetLogger.isScriptUrl(scriptUrl())) { toast("Chưa có link Apps Script (⚙ CÀI ĐẶT)"); return }
        val todo = adapter.items.filter { canUpload(it) }
        if (todo.isEmpty()) { toast("Không còn video nào cần tải lên Drive"); return }
        // Xổ các ngày có video được chọn sẵn
        expandedDays += todo.map { dayOf(stamp(it)) }
        enterSelect(todo.map { keyOf(it) })
        b.list.scrollToPosition(adapter.positionOf(todo.first()).coerceAtLeast(0))
        toast("Đã chọn sẵn ${todo.size} video chưa lên Drive — bỏ chọn video không cần rồi bấm TẢI LÊN")
    }

    private fun uploadSelected() {
        if (!SheetLogger.isScriptUrl(scriptUrl())) { toast("Chưa có link Apps Script (⚙ CÀI ĐẶT)"); return }
        val chosen = selectedItems().filter { canPick(it) }
        if (chosen.isEmpty()) { toast("Các video đã chọn không còn trong máy hoặc đang tải"); return }
        val fresh = chosen.filter { it.file !in uploadedFiles }
        val again = chosen.filter { it.file in uploadedFiles }
        if (again.isEmpty()) { confirmUpload(fresh, 0); return }

        // Có video đã tải trước đó → hỏi Drive xem còn không: còn thì bỏ qua, mất thì tải lại
        if (scriptVersion < 17) {
            tcDialog(
                title = "Chưa kiểm tra được Drive", accentColor = R.color.tc_amber,
                message = "${again.size} video đã từng tải lên Drive. Script trên Google Sheet là bản cũ nên " +
                    "chưa kiểm tra được video còn trên Drive không → tạm BỎ QUA các video này.\n\n" +
                    "Cập nhật Code.gs mới (Triển khai → Quản lý → ✏️ → Phiên bản mới) để dùng tính năng này.",
                ok = if (fresh.isEmpty()) "OK" else "TẢI ${fresh.size} VIDEO MỚI", cancel = if (fresh.isEmpty()) null else "HUỶ",
            ) { t -> t.dismiss(); if (fresh.isNotEmpty()) confirmUpload(fresh, again.size) }
            return
        }
        b.btnSelUpload.isEnabled = false
        b.tvSelCount.text = "Đang kiểm tra ${again.size} video trên Drive…"
        lifecycleScope.launch {
            val body = org.json.JSONObject().put("action", "driveCheck").put("items", org.json.JSONArray().apply {
                again.forEach { v ->
                    put(org.json.JSONObject().put("file", v.file)
                        .put("fileId", Store.uploadedId(this@SheetLogActivity, v.file))
                        .put("sheet", tab).put("id", v.id)
                        // Còn trên Drive mà Sheet mất dòng → script ghi lại dòng
                        .put("row", DriveUploader.rowFor(this@SheetLogActivity, v.file,
                            if (v.status == Status.VIDEO_ONLY) "" else v.id)))
                }
            })
            val res = withContext(Dispatchers.IO) { SheetLogger.postAndRead(scriptUrl(), body) }
            val exists = res?.optJSONObject("exists")
            if (res == null || !res.optBoolean("ok") || exists == null) {
                refreshSelection()
                toast("Không kiểm tra được Drive (mất mạng?) — thử lại sau")
                return@launch
            }
            // Mất trên Drive → bỏ đánh dấu để tải lại; còn → bỏ qua
            val missing = again.filter { !exists.optBoolean(it.file, false) }
            missing.forEach { Store.unmarkUploaded(this@SheetLogActivity, it.file) }
            uploadedFiles = Store.uploadedFiles(this@SheetLogActivity)
            val skipped = again.size - missing.size
            val pick = fresh + missing
            if (pick.isEmpty()) {
                refreshSelection()
                tcDialog(title = "Không có gì để tải", cancel = null, accentColor = R.color.tc_green,
                    message = "Cả $skipped video đã chọn đều đã có trên Google Drive → bỏ qua, không tải thêm.")
                return@launch
            }
            confirmUpload(pick, skipped)
        }
    }

    /** Xếp hàng tải [pick] (hỏi trước nếu đang dùng 4G). [skipped] = số video bỏ qua vì đã có trên Drive. */
    private fun confirmUpload(pick: List<Item>, skipped: Int) {
        val mb = pick.sumOf { it.video!!.size } / 1048576.0
        val go = {
            pick.forEach {
                DriveUploader.enqueue(this, it.video!!.uri, it.file,
                    if (it.status == Status.VIDEO_ONLY) "" else it.id, tab, it.code, manual = true)
            }
            uploadingFiles = Store.driveQueue(this).map { q -> q.optString("file") }.toSet()
            exitSelect()
            toast(String.format(Locale.US, "Đang tải %d video (~%.0f MB) lên Drive — chạy nền, có thể thoát app / để qua đêm", pick.size, mb) +
                if (skipped > 0) "\nBỏ qua $skipped video đã có trên Drive" else "")
            askIgnoreBattery()
        }
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        if (cm.isActiveNetworkMetered) {
            tcDialog(
                title = "Đang dùng 4G / dữ liệu di động",
                message = String.format(Locale.US, "Tải %d video (~%.0f MB) có thể tốn dung lượng 4G.\n\nVẫn tải bây giờ?", pick.size, mb),
                ok = "VẪN TẢI", cancel = "ĐỢI WI‑FI", okColor = R.color.tc_amber,
            ) { t -> t.dismiss(); go() }
        } else go()
    }

    /**
     * Để tải qua đêm không bị hệ thống (nhất là Realme/Oppo/Xiaomi) cắt khi tắt màn hình:
     * xin bỏ tối ưu pin cho app. Chỉ hỏi khi chưa được cho phép.
     */
    private fun askIgnoreBattery() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        tcDialog(
            title = "Cho phép tải nền qua đêm",
            message = "Để video tiếp tục tải lên Drive khi tắt màn hình hoặc để máy qua đêm, " +
                "hãy cho app chạy nền không bị giới hạn pin.\n\nBấm CHO PHÉP rồi chọn \"Cho phép\".",
            ok = "CHO PHÉP", cancel = "ĐỂ SAU",
        ) { t ->
            t.dismiss()
            try {
                @android.annotation.SuppressLint("BatteryLife")
                val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"))
                startActivity(i)
            } catch (e: Exception) {
                try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                catch (e2: Exception) { toast("Mở Cài đặt → Pin → Quay Test → Không hạn chế") }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DriveUploader.kick(this)   // còn video trong hàng chờ mà chưa có việc chạy → chạy lại
        load()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /** Mở từ thông báo nhắc 16:00 → tải danh sách xong thì mở luôn hộp chọn video đồng bộ. */
    private fun maybeAutoSync() {
        if (intent?.getBooleanExtra(Reminder.EXTRA_SYNC, false) == true) {
            intent.removeExtra(Reminder.EXTRA_SYNC)
            startSyncSelect()
        }
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
            // Danh sách tab: chạm 1 dòng = chọn và đóng
            val box = android.widget.LinearLayout(this@SheetLogActivity).apply { orientation = android.widget.LinearLayout.VERTICAL }
            lateinit var dlg: TcDialog
            val ripple = android.util.TypedValue().also { tv ->
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true) }.resourceId
            tabs.forEach { name ->
                val cur = name == tab
                box.addView(android.widget.TextView(this@SheetLogActivity).apply {
                    text = if (cur) "✓  $name" else "     $name"
                    textSize = 16f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, if (cur) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    setTextColor(getColor(if (cur) R.color.tc_blue else R.color.tc_text))
                    minHeight = 48.dp()
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(8.dp(), 0, 8.dp(), 0)
                    setBackgroundResource(ripple)
                    setOnClickListener { tab = name; invalidateOptionsMenu(); dlg.dismiss(); load() }
                })
            }
            dlg = tcDialog(
                title = "Chọn sheet",
                subtitle = if (remote != null) "Các tab trong bảng tính" else "Chưa tải được từ Google — danh sách trong máy",
                body = box, ok = "ĐÓNG", cancel = null,
            )
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
            // fileId Drive → tên file video trong máy (dòng trên Sheet không có tên file ẩn vẫn ghép được qua link)
            val fileByDrive = videos.mapNotNull { v ->
                Store.uploadedId(this@SheetLogActivity, v.name).takeIf { it.isNotEmpty() }?.let { it to v.name }
            }.toMap()
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
                    val f = o.optString("file").ifEmpty { fileByDrive[o.optString("drive")].orEmpty() }
                    if (f.isNotEmpty()) used += f
                    items += Item(Status.SHEET, o.optString("id"), o.optString("code"), o.optString("date"),
                        o.optString("time"), o.optString("customer"), o.optString("staff"), o.optString("type"),
                        o.optString("warranty_date"), f, if (f.isNotEmpty()) byFile[f] else null,
                        driveId = o.optString("drive"))
                }
            }
            // 3) Video trong máy chưa khớp dòng nào (chưa có Sheet / Sheet không tải được)
            videos.filter { it.name !in used }.forEach { v ->
                items += Item(Status.VIDEO_ONLY, "", codeFromFile(v.name),
                    dateFmt.format(Date(v.dateMs)), "", "", "", "", "", v.name, v)
            }

            // MỖI MÁY CHỈ THẤY VIDEO CỦA MÌNH: dòng trên Sheet chỉ hiện nếu do máy này quay
            // (video còn trong máy / có thông tin lưu lúc quay / máy này đã tải lên Drive).
            // Sheet vẫn ghi chung đầy đủ cho mọi máy.
            val ownFiles = videos.map { it.name }.toSet() + Store.rowInfoFiles(this@SheetLogActivity) +
                Store.uploadedFiles(this@SheetLogActivity)
            val ownDrive = Store.uploadedIds(this@SheetLogActivity)
            val totalRows = remote?.optInt("total") ?: 0
            items.retainAll {
                it.status != Status.SHEET ||
                    (it.file.isNotEmpty() && it.file in ownFiles) ||
                    (it.driveId.isNotEmpty() && it.driveId in ownDrive)
            }

            val shown = (if (q.isEmpty()) items else items.filter {
                "${it.code} ${it.customer} ${it.staff} ${it.type} ${it.file}".uppercase().contains(q)
            }).sortedByDescending { stamp(it) }   // mới nhất → cũ nhất (theo ngày + giờ quay)
            // Đang tìm → xổ hết các ngày có kết quả
            if (q.isNotEmpty()) expandedDays += shown.map { dayOf(stamp(it)) }
            adapter.items = shown
            if (selecting) selected.retainAll(shown.map { keyOf(it) }.toSet())
            refreshSelection()
            b.tvStatus.text = when {
                !SheetLogger.isScriptUrl(url) -> "Chưa có link Apps Script — chỉ hiện video trong máy"
                remote == null -> "Không tải được Sheet (mất mạng?) — đang hiện dữ liệu trong máy"
                else -> "Tab $tab • máy này: ${shown.size} video (Sheet chung: $totalRows dòng)"
            }
            uploadedFiles = Store.uploadedFiles(this@SheetLogActivity)
            if (remote != null && q.isEmpty()) { ensureSheetRows(items); syncFromDrive() }
            maybeAutoSync()
        }
    }

    private val ensureTried = HashSet<String>()

    /**
     * Video ĐÃ LÊN DRIVE thì Sheet bắt buộc có dòng: video nào đã lên Drive mà tab này không có dòng
     * (chưa gửi được / bị xoá tay trên Sheet) → nhờ script ghi lại dòng + link. Video không còn trên
     * Drive → bỏ đánh dấu "đã lên Drive" để tải lại được.
     */
    private fun ensureSheetRows(items: List<Item>) {
        if (scriptVersion < 18) return
        val done = Store.uploadedFiles(this)
        val orphanList = items.filter { it.status == Status.VIDEO_ONLY && it.file in done && it.file !in ensureTried }
            .map { it.file to DriveUploader.rowFor(this, it.file) }
            .filter { it.second.optString("sheet") == tab }
        // Video đã tải trước bản này: gửi thông tin đầy đủ (lưu trong máy) lên mô tả file Drive,
        // để sau này Sheet mất dòng thì Drive tự tạo lại được (kể cả khi đã xoá video khỏi máy)
        val descTodo = if (scriptVersion < 19) emptyList() else {
            val sent = Store.descDone(this)
            done.filter { it !in sent && it !in ensureTried && orphanList.none { o -> o.first == it } }
                .mapNotNull { f -> Store.rowInfo(this, f)?.let { f to DriveUploader.rowFor(this, f) } }
                .take(30)
        }
        val orphans = orphanList + descTodo
        if (orphans.isEmpty()) return
        ensureTried += orphans.map { it.first }
        lifecycleScope.launch {
            val body = org.json.JSONObject().put("action", "ensureRows").put("items", org.json.JSONArray().apply {
                orphans.forEach { (file, row) ->
                    put(org.json.JSONObject().put("row", row).put("fileId", Store.uploadedId(this@SheetLogActivity, file)))
                }
            })
            val res = withContext(Dispatchers.IO) { SheetLogger.postAndRead(scriptUrl(), body) } ?: return@launch
            if (!res.optBoolean("ok")) return@launch
            res.optJSONArray("gone")?.let { g ->
                for (i in 0 until g.length()) Store.unmarkUploaded(this@SheetLogActivity, g.getString(i))
            }
            res.optJSONArray("done")?.let { d ->
                Store.addDescDone(this@SheetLogActivity, (0 until d.length()).map { d.getString(it) })
            }
            val created = res.optInt("created")
            if (created > 0) toast("Đã ghi bổ sung $created dòng lên Sheet cho video đã có trên Drive")
            if (created > 0 || (res.optJSONArray("gone")?.length() ?: 0) > 0) load()
        }
    }

    private val lastDriveSync = HashMap<String, Long>()

    /**
     * Nhờ script quét video của tab (tháng) đang xem trên Drive: video còn trên Drive mà Sheet mất dòng
     * → tạo lại dòng từ thông tin lưu trong mô tả file Drive. Tối đa 2 phút / lần mỗi tab.
     */
    private fun syncFromDrive() {
        if (scriptVersion < 19) return
        val now = System.currentTimeMillis()
        if (now - (lastDriveSync[tab] ?: 0L) < 120_000L) return
        lastDriveSync[tab] = now
        val t = tab
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                SheetLogger.postAndRead(scriptUrl(), org.json.JSONObject().put("action", "syncFromDrive").put("sheet", t))
            } ?: return@launch
            val n = res.optInt("created")
            if (res.optBoolean("ok") && n > 0 && t == tab) {
                toast("Đã khôi phục $n dòng trên Sheet từ video trên Drive")
                load()
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

    /** Đã có video trên Drive: máy này đã tải lên, HOẶC dòng trên Sheet có link video Drive. */
    private fun onDrive(it: Item) = (it.file.isNotEmpty() && it.file in uploadedFiles) || it.driveId.isNotEmpty()

    /**
     * Xoá các lần quay đã chọn. MẶC ĐỊNH chỉ xoá video trong điện thoại — Sheet giữ nguyên.
     * Chỉ khi tick "Xoá luôn dòng trên Google Sheet" mới xoá dòng, và chỉ với lần quay CHƯA có video
     * trên Drive (có trên Drive thì thông tin đi theo video, không xoá từ app).
     */
    private fun askDeleteSelected() {
        val items = selectedItems()
        if (items.isEmpty()) { toast("Chưa chọn video nào"); return }
        val withVideo = items.filter { it.video != null }
        // Dòng có thể xoá trên Sheet: chưa có video trên Drive + có dòng (đã lưu / chờ gửi)
        val rowsDeletable = items.filter {
            !onDrive(it) && (it.status == Status.PENDING || (it.status == Status.SHEET && it.id.isNotEmpty()))
        }
        val notOnDrive = withVideo.count { !onDrive(it) }

        if (withVideo.isEmpty() && rowsDeletable.isEmpty()) {
            tcDialog(title = "Không có video trong máy", cancel = null,
                message = "Video này chỉ còn trên Google Drive — trong điện thoại không còn gì để xoá.")
            return
        }

        val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val msg = android.widget.TextView(this).apply {
            textSize = 16f
            setTextColor(getColor(R.color.tc_text))
            setLineSpacing(4 * resources.displayMetrics.density, 1f)
        }
        box.addView(msg)
        // Chỉ cảnh báo khi có video CHƯA lên Drive (xoá là mất hẳn)
        if (notOnDrive > 0) box.addView(android.widget.TextView(this).apply {
            text = "⚠  $notOnDrive video chưa lên Drive — xoá là mất hẳn."
            textSize = 14f
            setTextColor(getColor(R.color.tc_red))
            setPadding(0, 10.dp(), 0, 0)
        })
        val cb = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = "Xoá cả thông tin trên Google Sheet"
            textSize = 14f
            visibility = if (rowsDeletable.isEmpty()) View.GONE else View.VISIBLE
            setPadding(0, 4.dp(), 0, 0)
        }
        box.addView(cb)
        fun describe() {
            msg.text = (if (withVideo.isNotEmpty()) "Xoá ${withVideo.size} video khỏi điện thoại." else "Không có video trong máy.") +
                "\n" + if (cb.isChecked) "Thông tin trên Google Sheet cũng bị xoá." else "Thông tin trên Google Sheet vẫn giữ nguyên."
        }
        cb.setOnCheckedChangeListener { _, _ -> describe() }
        describe()

        tcDialog(
            title = "Xoá ${if (withVideo.isNotEmpty()) withVideo.size else items.size} video?",
            body = box, ok = "XOÁ", okColor = R.color.tc_red, accentColor = R.color.tc_red,
        ) { t ->
            if (withVideo.isEmpty() && !cb.isChecked) { toast("Không có video trong máy để xoá"); return@tcDialog }
            t.dismiss()
            deleteMany(items, if (cb.isChecked) rowsDeletable.toSet() else emptySet())
        }
    }

    /** Xoá video trong máy của [items]; dòng Sheet chỉ xoá cho các lần quay trong [rows]. */
    private fun deleteMany(items: List<Item>, rows: Set<Item>) = lifecycleScope.launch {
        if (scriptVersion < 2 && rows.any { it.status == Status.SHEET }) {
            tcDialog(
                title = "Cần cập nhật Apps Script", accentColor = R.color.tc_amber,
                message = "Script trên Google Sheet là bản cũ, chưa có lệnh xoá.\n\n" +
                    "Dán lại file Code.gs mới → Triển khai → Quản lý → ✏️ → Phiên bản mới → Triển khai.",
                cancel = null,
            )
            return@launch
        }
        b.btnSelDelete.isEnabled = false
        b.btnSelUpload.isEnabled = false
        var videos = 0
        var videoFail = 0
        var rowsDel = 0
        var kept = 0
        var netFail = false
        for ((i, it) in items.withIndex()) {
            b.tvStatus.text = "Đang xoá ${i + 1}/${items.size}…"
            if (it in rows) {
                // 1) Dòng trên Sheet trước — mất mạng thì dừng, chưa xoá gì của lần quay này
                if (it.status == Status.SHEET && it.id.isNotEmpty()) {
                    val r = withContext(Dispatchers.IO) { SheetLogger.deleteRemote(scriptUrl(), tab, it.id) }
                    if (r == null) { netFail = true; break }
                    if (r) rowsDel++ else kept++
                }
                if (it.status == Status.PENDING && it.id.isNotEmpty()) {
                    SheetLogger.removePending(this@SheetLogActivity, it.id); rowsDel++
                }
                if (it.file.isNotEmpty()) DriveUploader.cancel(this@SheetLogActivity, it.file)
            }
            // 2) Video trong máy
            it.video?.let { v -> if (deleteVideo(v)) videos++ else videoFail++ }
        }
        exitSelect()
        // Báo ngắn gọn: đã xoá gì; chỉ nói thêm khi có trục trặc
        val msg = StringBuilder(if (videos > 0) "Đã xoá $videos video" else "Đã xoá")
        if (rowsDel > 0) msg.append(" và $rowsDel dòng trên Sheet")
        if (netFail) msg.append(". Mất mạng — chưa xoá hết, thử lại sau")
        if (videoFail > 0) msg.append(". $videoFail video không xoá được — xoá trong Thư viện ảnh")
        if (kept > 0) msg.append(". $kept dòng có video trên Drive được giữ lại")
        toast(msg.toString())
        load()
    }

    private fun deleteVideo(v: Video): Boolean = try {
        contentResolver.delete(v.uri, null, null) > 0
    } catch (e: Exception) { false }   // RecoverableSecurityException: video do bản app cũ tạo

    // ================= NGÀY / GIỜ =================

    private val expandedDays = HashSet<Long>()   // ngày đang xổ (yyyyMMdd)
    private var daysInit = false                  // lần đầu: xổ sẵn ngày mới nhất
    private val stampCache = HashMap<Item, Long>()
    private val ymd = SimpleDateFormat("yyyyMMdd", Locale.US)

    /** yyyyMMddHHmmss từ ngày + giờ quay (thiếu thì lấy thời điểm tạo video). */
    private fun stamp(it: Item): Long = stampCache.getOrPut(it) {
        val d = Regex("(\\d{1,2})/(\\d{1,2})/(\\d{4})").find(it.date)
        if (d == null) {
            it.video?.let { v -> SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date(v.dateMs)).toLong() } ?: 0L
        } else {
            val t = Regex("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?").find(it.time.ifBlank { it.date.drop(d.range.last + 1) })
            val (dd, mm, yy) = d.destructured
            val h = t?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val mi = t?.groupValues?.get(2)?.toLongOrNull() ?: 0
            val sec = t?.groupValues?.get(3)?.toLongOrNull() ?: 0
            ((yy.toLong() * 100 + mm.toLong()) * 100 + dd.toLong()) * 1_000_000 + h * 10_000 + mi * 100 + sec
        }
    }
    private fun dayOf(stamp: Long) = stamp / 1_000_000

    /** "09:05" của lần quay (đã có ngày ở tiêu đề nhóm). */
    private fun timeOf(it: Item): String {
        val st = stamp(it)
        if (st <= 0) return "${it.date} ${it.time}".trim()
        // Không có giờ quay (dòng khôi phục từ Drive) → để trống, đừng hiện "00:00"
        if (it.time.isBlank() && !it.date.contains(':') && it.video == null) return ""
        return String.format(Locale.US, "%02d:%02d", st / 10_000 % 100, st / 100 % 100)
    }

    private fun dayLabel(day: Long): String {
        if (day <= 0) return "Không rõ ngày"
        val y = (day / 10_000).toInt(); val m = (day / 100 % 100).toInt(); val d = (day % 100).toInt()
        val cal = Calendar.getInstance().apply { clear(); set(y, m - 1, d) }
        val wd = arrayOf("Chủ Nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy")[cal.get(Calendar.DAY_OF_WEEK) - 1]
        val today = ymd.format(Date()).toLong()
        val yesterday = ymd.format(Date(System.currentTimeMillis() - 86_400_000L)).toLong()
        val prefix = when (day) { today -> "Hôm nay  •  "; yesterday -> "Hôm qua  •  "; else -> "" }
        return String.format(Locale.US, "%s%s, %02d/%02d/%04d", prefix, wd, d, m, y)
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ================= DANH SÁCH =================

    private sealed class Row {
        class Day(val day: Long, val items: List<Item>) : Row()
        class Entry(val item: Item) : Row()
    }

    /**
     * Danh sách nhóm theo NGÀY (mới nhất trước). Mỗi ngày 1 thanh tiêu đề "Thứ Hai, 17/11/2026 • 5 video",
     * chạm để xổ / thu. Đang chọn nhiều: nhấn giữ tiêu đề = chọn / bỏ chọn cả ngày.
     */
    inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Item> = emptyList()      // tất cả video đang hiện (đã sắp xếp), kể cả ngày đang thu
            set(v) { field = v; stampCache.keys.retainAll(v.toSet()); rebuild() }
        private var rows: List<Row> = emptyList()

        inner class VH(val v: ItemSheetRowBinding) : RecyclerView.ViewHolder(v.root)
        inner class DayVH(val v: ItemDayHeaderBinding) : RecyclerView.ViewHolder(v.root)

        fun rebuild() {
            val groups = LinkedHashMap<Long, MutableList<Item>>()
            items.forEach { groups.getOrPut(dayOf(stamp(it))) { ArrayList() } += it }
            if (!daysInit && groups.isNotEmpty()) { expandedDays += groups.keys.first(); daysInit = true }
            val out = ArrayList<Row>()
            groups.forEach { (day, list) ->
                out += Row.Day(day, list)
                if (day in expandedDays) list.forEach { out += Row.Entry(it) }
            }
            rows = out
            notifyDataSetChanged()
        }

        fun positionOf(item: Item) = rows.indexOfFirst { it is Row.Entry && it.item === item }

        override fun getItemViewType(pos: Int) = if (rows[pos] is Row.Day) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) DayVH(ItemDayHeaderBinding.inflate(inf, parent, false).also {
                it.root.chamfer(getColor(R.color.tc_elevated), 8f)    // thanh ngày góc vát
                it.tvCount.chamfer(Color.WHITE, 5f)
            })
            else VH(ItemSheetRowBinding.inflate(inf, parent, false))
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
            when (val r = rows[pos]) {
                is Row.Day -> bindDay(holder as DayVH, r)
                is Row.Entry -> bindItem(holder as VH, r.item)
            }
        }

        private fun bindDay(h: DayVH, r: Row.Day) {
            val open = r.day in expandedDays
            h.v.tvArrow.text = if (open) "▾" else "▸"
            h.v.tvDay.text = dayLabel(r.day)
            val sel = if (selecting) r.items.count { keyOf(it) in selected } else 0
            h.v.tvCount.text = if (sel > 0) "$sel/${r.items.size} chọn" else "${r.items.size} video"
            h.v.tvCount.setTextColor(getColor(if (sel > 0) R.color.tc_blue else R.color.tc_text_2))
            h.v.root.setOnClickListener {
                if (!expandedDays.remove(r.day)) expandedDays += r.day
                rebuild()
            }
            h.v.root.setOnLongClickListener { v ->
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                val keys = r.items.map { keyOf(it) }
                expandedDays += r.day
                if (!selecting) { enterSelect(keys); rebuild() }
                else {
                    if (keys.all { it in selected }) selected.removeAll(keys.toSet()) else selected += keys
                    rebuild(); refreshSelection()
                }
                true
            }
        }

        private fun bindItem(h: VH, it: Item) {
            h.v.tvCode.text = it.code.ifEmpty { "(không mã)" }
            h.v.tvLine1.text = listOf(
                timeOf(it),
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
                Status.PENDING -> Triple("CHƯA GỬI", "#FFAA00", true)
                Status.VIDEO_ONLY -> Triple("CHỈ CÓ VIDEO", "#64748B", false)
            }
            h.v.tvBadge.text = label
            h.v.tvBadge.setBackgroundColor(Color.parseColor(color))
            h.v.tvBadge.setTextColor(if (dark) Color.parseColor("#111111") else Color.WHITE)
            h.v.accent.setBackgroundColor(Color.parseColor(color))

            // Chọn nhiều: nhấn giữ để bắt đầu chọn; đang chọn thì chạm = chọn / bỏ chọn
            val sel = selecting && keyOf(it) in selected
            h.v.cbSel.visibility = if (selecting) View.VISIBLE else View.GONE
            h.v.cbSel.isChecked = sel
            h.v.root.strokeWidth = if (sel) 2.dp() else 1.dp()
            h.v.root.strokeColor = Color.parseColor(if (sel) "#0055FF" else "#CBD5E1")
            h.v.root.setCardBackgroundColor(Color.parseColor(if (sel) "#EAF1FF" else "#FFFFFF"))
            h.v.root.setOnClickListener { _ -> if (selecting) toggle(it) else play(it) }
            h.v.root.setOnLongClickListener { v ->
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                if (selecting) toggle(it) else enterSelect(listOf(keyOf(it)))
                true
            }

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
