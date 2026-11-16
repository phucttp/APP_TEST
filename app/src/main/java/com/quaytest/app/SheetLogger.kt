package com.quaytest.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ghi mỗi video thành 1 dòng trên Google Sheet qua Apps Script Web App.
 * Dòng nào gửi lỗi (mất mạng...) được giữ trong hàng đợi và gửi lại lần sau.
 * Mỗi dòng có "id" riêng → script phía Sheet bỏ qua nếu nhận trùng.
 */
object SheetLogger {
    const val PREFS = "quaytest"
    const val KEY_URL = "sheet_url"          // link Apps Script /exec (để ghi + đọc)
    const val KEY_SHEET_LINK = "sheet_link"  // link bảng tính (để mở xem)
    const val DEFAULT_SHEET_LINK =
        "https://docs.google.com/spreadsheets/d/1SOS2hb_2w5zn7dhX3iFCk9XePGGWxIDp2a9VBtJZygU/edit"

    fun isScriptUrl(s: String) = s.startsWith("https://script.google.com/") && s.contains("/exec")
    fun isSheetLink(s: String) = s.startsWith("https://docs.google.com/spreadsheets/")
    private const val KEY_PENDING = "pending_rows"
    private val mutex = Mutex()
    private val lock = Any()

    fun enqueue(ctx: Context, row: JSONObject) = synchronized(lock) {
        val arr = load(ctx)
        arr.put(row)
        save(ctx, arr)
    }

    fun pendingCount(ctx: Context): Int = synchronized(lock) { load(ctx).length() }

    /** Danh sách tab thật trong bảng tính (script v3+). null = lỗi / script cũ. Gọi trên IO thread. */
    fun fetchTabs(url: String): List<String>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url + (if (url.contains('?')) "&" else "?") + "action=tabs")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) return null
            val o = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val a = o.optJSONArray("tabs") ?: return null
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Xoá 1 dòng khỏi hàng chờ gửi (khi người dùng xoá lần quay đó). */
    fun removePending(ctx: Context, id: String) = synchronized(lock) {
        val cur = load(ctx)
        val remain = JSONArray()
        for (i in 0 until cur.length()) {
            val r = cur.getJSONObject(i)
            if (r.optString("id") != id) remain.put(r)
        }
        save(ctx, remain)
    }

    /**
     * Xoá dòng trên Sheet theo ID. Gọi trên IO thread.
     * Trả về: true = đã xoá, false = không thấy dòng, null = lỗi mạng / script.
     */
    fun deleteRemote(url: String, sheet: String, id: String): Boolean? {
        val body = JSONObject().put("action", "delete").put("sheet", sheet).put("id", id)
        val res = postAndRead(url, body) ?: return null
        return if (res.optBoolean("ok")) res.optBoolean("deleted") else null
    }

    /** POST rồi đọc kết quả JSON (Apps Script trả 302 → GET trang kết quả). */
    private fun postAndRead(url: String, body: JSONObject): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: return null
                conn.disconnect()
                conn = (URL(loc).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 30000
                }
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else if (code in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else return null
            JSONObject(text)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun pendingRows(ctx: Context): List<JSONObject> = synchronized(lock) {
        val a = load(ctx)
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    /** Đọc nhật ký từ Sheet (doGet của Apps Script). null = lỗi mạng / script cũ. Gọi trên IO thread. */
    fun fetch(url: String, q: String, limit: Int, sheet: String = Store.DEFAULT_TAB): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
            val full = url + (if (url.contains('?')) "&" else "?") +
                "limit=$limit&q=" + enc(q) + "&sheet=" + enc(sheet)
            conn = (URL(full).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true // GET: Apps Script chuyển hướng sang googleusercontent
            }
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(body).takeIf { it.optBoolean("ok") }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Gửi hết hàng đợi. Trả về số dòng gửi thành công. */
    suspend fun flush(ctx: Context): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            val url = prefs(ctx).getString(KEY_URL, "")?.trim().orEmpty()
            if (!url.startsWith("https://")) return@withContext 0
            val snapshot = synchronized(lock) { load(ctx) }
            val sentIds = HashSet<String>()
            for (i in 0 until snapshot.length()) {
                val row = snapshot.getJSONObject(i)
                if (post(url, row)) sentIds += row.optString("id") else break // mất mạng → dừng, lần sau gửi tiếp
            }
            if (sentIds.isNotEmpty()) synchronized(lock) {
                val cur = load(ctx)
                val remain = JSONArray()
                for (i in 0 until cur.length()) {
                    val r = cur.getJSONObject(i)
                    if (r.optString("id") !in sentIds) remain.put(r)
                }
                save(ctx, remain)
            }
            sentIds.size
        }
    }

    private fun post(url: String, row: JSONObject): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 20000
                doOutput = true
                // Apps Script trả 302 sau khi đã chạy xong doPost → coi 302 là thành công
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(row.toString().toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..399
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(ctx: Context): JSONArray =
        try { JSONArray(prefs(ctx).getString(KEY_PENDING, "[]")) } catch (e: Exception) { JSONArray() }

    private fun save(ctx: Context, arr: JSONArray) =
        prefs(ctx).edit().putString(KEY_PENDING, arr.toString()).apply()
}
