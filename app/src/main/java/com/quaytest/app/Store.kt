package com.quaytest.app

import android.content.Context
import org.json.JSONArray

/** Lưu cài đặt dạng danh sách (nhân viên, sheet, khách gần đây) trong SharedPreferences. */
object Store {
    const val KEY_STAFF_LIST = "staff_list"
    const val KEY_SHEET_LIST = "sheet_list"
    const val KEY_RECENT_CUSTOMERS = "recent_customers"
    const val KEY_STAFF = "staff"
    const val KEY_SHEET_TAB = "sheet_tab"
    const val KEY_RECENT_TYPES = "recent_types"
    const val KEY_LAST_TYPE = "last_type"
    const val KEY_LAST_CUSTOMER = "last_customer"
    const val DEFAULT_TAB = "Log"
    const val KEY_AUTO_MONTH = "auto_month_tab"

    /** Tab theo tháng_năm, vd. 11_2026. */
    fun monthTab(d: java.util.Date = java.util.Date()): String =
        java.text.SimpleDateFormat("MM_yyyy", java.util.Locale.US).format(d)

    fun autoMonth(ctx: Context) = prefs(ctx).getBoolean(KEY_AUTO_MONTH, true)
    fun setAutoMonth(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_AUTO_MONTH, on).apply()

    /** Tab sẽ ghi vào lúc này: theo tháng (nếu bật) hoặc tab đã chọn tay. */
    fun currentTab(ctx: Context, d: java.util.Date = java.util.Date()): String =
        if (autoMonth(ctx)) monthTab(d)
        else getString(ctx, KEY_SHEET_TAB, DEFAULT_TAB).ifEmpty { DEFAULT_TAB }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(SheetLogger.PREFS, Context.MODE_PRIVATE)

    fun getList(ctx: Context, key: String, default: List<String> = emptyList()): List<String> {
        val raw = prefs(ctx).getString(key, null) ?: return default
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) { default }
    }

    fun setList(ctx: Context, key: String, list: List<String>) {
        prefs(ctx).edit().putString(key, JSONArray(list).toString()).apply()
    }

    fun staffList(ctx: Context) = getList(ctx, KEY_STAFF_LIST)

    // ===== Google Drive =====
    const val KEY_DRIVE_ON = "drive_upload_on"
    const val KEY_DRIVE_WIFI = "drive_wifi_only"
    private const val KEY_DRIVE_DONE = "drive_uploaded"   // {tên file: fileId}
    // Mặc định TẮT cho tới khi tính năng Drive được thử xong
    fun driveOn(ctx: Context) = prefs(ctx).getBoolean(KEY_DRIVE_ON, false)
    fun driveWifiOnly(ctx: Context) = prefs(ctx).getBoolean(KEY_DRIVE_WIFI, true)
    fun setDrive(ctx: Context, on: Boolean, wifiOnly: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_DRIVE_ON, on).putBoolean(KEY_DRIVE_WIFI, wifiOnly).apply()

    @Synchronized fun markUploaded(ctx: Context, file: String, fileId: String) {
        val o = try { org.json.JSONObject(prefs(ctx).getString(KEY_DRIVE_DONE, "{}")!!) } catch (e: Exception) { org.json.JSONObject() }
        o.put(file, fileId)
        prefs(ctx).edit().putString(KEY_DRIVE_DONE, o.toString()).apply()
    }
    @Synchronized fun uploadedId(ctx: Context, file: String): String =
        jsonObj(ctx, KEY_DRIVE_DONE).optString(file)
    /** Video không còn trên Drive → bỏ đánh dấu "đã lên Drive" để tải lại được. */
    @Synchronized fun unmarkUploaded(ctx: Context, file: String) {
        val o = jsonObj(ctx, KEY_DRIVE_DONE)
        o.remove(file)
        prefs(ctx).edit().putString(KEY_DRIVE_DONE, o.toString()).apply()
    }
    @Synchronized fun uploadedFiles(ctx: Context): Set<String> {
        val o = try { org.json.JSONObject(prefs(ctx).getString(KEY_DRIVE_DONE, "{}")!!) } catch (e: Exception) { return emptySet() }
        return o.keys().asSequence().toSet()
    }

    // Hàng chờ tải Drive: [{uri, file, row, sheet, code, manual, wifi}] — lưu trong máy để
    // tắt app / khởi động lại máy / rớt mạng vẫn còn, tải tiếp khi có mạng.
    private const val KEY_DRIVE_QUEUE = "drive_queue"
    // Link phiên tải lên (resumable) theo tên file → rớt mạng thì tải TIẾP từ chỗ dở, không tải lại từ đầu
    private const val KEY_DRIVE_SESS = "drive_sessions"

    private fun jsonObj(ctx: Context, key: String) =
        try { org.json.JSONObject(prefs(ctx).getString(key, "{}")!!) } catch (e: Exception) { org.json.JSONObject() }

    @Synchronized fun driveQueue(ctx: Context): List<org.json.JSONObject> {
        val a = try { JSONArray(prefs(ctx).getString(KEY_DRIVE_QUEUE, "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }
    }
    @Synchronized fun driveQueueAdd(ctx: Context, item: org.json.JSONObject): Boolean {
        val file = item.optString("file")
        val cur = driveQueue(ctx)
        if (file.isEmpty() || file in uploadedFiles(ctx) || cur.any { it.optString("file") == file }) return false
        prefs(ctx).edit().putString(KEY_DRIVE_QUEUE, JSONArray(cur + item).toString()).apply()
        return true
    }
    @Synchronized fun driveQueueRemove(ctx: Context, file: String) {
        val cur = driveQueue(ctx).filter { it.optString("file") != file }
        prefs(ctx).edit().putString(KEY_DRIVE_QUEUE, JSONArray(cur).toString()).apply()
        setDriveSession(ctx, file, null)
    }
    @Synchronized fun driveSession(ctx: Context, file: String): String? =
        jsonObj(ctx, KEY_DRIVE_SESS).optString(file).ifEmpty { null }
    @Synchronized fun setDriveSession(ctx: Context, file: String, url: String?) {
        val o = jsonObj(ctx, KEY_DRIVE_SESS)
        if (url == null) o.remove(file) else o.put(file, url)
        prefs(ctx).edit().putString(KEY_DRIVE_SESS, o.toString()).apply()
    }

    // Video đã lên Drive nhưng chưa báo được script (chuyển thư mục + gắn link Sheet) → báo bù sau
    private const val KEY_DRIVE_DONE_PENDING = "drive_done_pending"
    @Synchronized fun driveDonePending(ctx: Context): List<org.json.JSONObject> {
        val a = try { JSONArray(prefs(ctx).getString(KEY_DRIVE_DONE_PENDING, "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }
    }
    @Synchronized fun driveDonePendingAdd(ctx: Context, o: org.json.JSONObject) {
        val cur = driveDonePending(ctx).filter { it.optString("fileId") != o.optString("fileId") }
        prefs(ctx).edit().putString(KEY_DRIVE_DONE_PENDING, JSONArray(cur + o).toString()).apply()
    }
    @Synchronized fun driveDonePendingRemove(ctx: Context, fileId: String) {
        val cur = driveDonePending(ctx).filter { it.optString("fileId") != fileId }
        prefs(ctx).edit().putString(KEY_DRIVE_DONE_PENDING, JSONArray(cur).toString()).apply()
    }

    // Thông tin dòng Sheet của từng video (lưu lúc quay) → Sheet thiếu dòng thì ghi lại được đầy đủ
    private const val KEY_ROW_INFO = "row_info"
    @Synchronized fun saveRowInfo(ctx: Context, file: String, row: org.json.JSONObject) {
        val o = jsonObj(ctx, KEY_ROW_INFO)
        o.put(file, row)
        // Giữ tối đa 800 video gần nhất
        val keys = o.keys().asSequence().toList()
        if (keys.size > 800) keys.take(keys.size - 800).forEach { o.remove(it) }
        prefs(ctx).edit().putString(KEY_ROW_INFO, o.toString()).apply()
    }
    /** Tên file mọi video máy này đã quay (có lưu thông tin lúc quay). */
    @Synchronized fun rowInfoFiles(ctx: Context): Set<String> = jsonObj(ctx, KEY_ROW_INFO).keys().asSequence().toSet()
    /** fileId Drive của mọi video máy này đã tải lên. */
    @Synchronized fun uploadedIds(ctx: Context): Set<String> {
        val o = jsonObj(ctx, KEY_DRIVE_DONE)
        return o.keys().asSequence().map { o.optString(it) }.filter { it.isNotEmpty() }.toSet()
    }
    @Synchronized fun rowInfo(ctx: Context, file: String): org.json.JSONObject? =
        jsonObj(ctx, KEY_ROW_INFO).optJSONObject(file)?.let { org.json.JSONObject(it.toString()) }

    // Video đã gửi thông tin lên phần mô tả file Drive (khỏi gửi lại)
    private const val KEY_DESC_DONE = "drive_desc_done"
    @Synchronized fun descDone(ctx: Context): Set<String> = getList(ctx, KEY_DESC_DONE).toSet()
    @Synchronized fun addDescDone(ctx: Context, files: Collection<String>) {
        if (files.isEmpty()) return
        setList(ctx, KEY_DESC_DONE, (getList(ctx, KEY_DESC_DONE) + files).distinct().takeLast(2000))
    }

    const val KEY_TYPE_LIST = "type_list"
    /** Danh sách loại máy (quản lý trong Cài đặt). Lần đầu: lấy từ các loại đã từng nhập. */
    fun typeList(ctx: Context): List<String> =
        if (prefs(ctx).contains(KEY_TYPE_LIST)) getList(ctx, KEY_TYPE_LIST)
        else getList(ctx, KEY_RECENT_TYPES)
    fun sheetList(ctx: Context) = getList(ctx, KEY_SHEET_LIST, listOf(DEFAULT_TAB)).ifEmpty { listOf(DEFAULT_TAB) }

    fun getString(ctx: Context, key: String, def: String = "") = prefs(ctx).getString(key, def) ?: def
    fun putString(ctx: Context, key: String, v: String) = prefs(ctx).edit().putString(key, v).apply()
    fun getInt(ctx: Context, key: String, def: Int) = prefs(ctx).getInt(key, def)
    fun putInt(ctx: Context, key: String, v: Int) = prefs(ctx).edit().putInt(key, v).apply()

    /** Ghi nhớ giá trị vừa nhập để gợi ý lần sau (tối đa 30, mới nhất đầu). */
    fun remember(ctx: Context, key: String, value: String) {
        if (value.isBlank()) return
        val l = getList(ctx, key).filter { !it.equals(value, true) }
        setList(ctx, key, (listOf(value) + l).take(30))
    }
}
