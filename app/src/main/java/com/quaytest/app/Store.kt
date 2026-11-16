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
