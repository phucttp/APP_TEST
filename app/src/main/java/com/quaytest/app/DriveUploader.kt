package com.quaytest.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Tải video lên Google Drive — MỘT hàng chờ chung, chạy nền dạng dịch vụ tiền cảnh
 * (có thông báo "Đang tải…") để để máy qua đêm vẫn tải tiếp.
 *
 * - Hàng chờ lưu trong máy (Store.driveQueue) → tắt app / khởi động lại máy vẫn còn.
 * - Tải từng khúc 8 MB lên link "resumable" của Drive; link được lưu lại → rớt mạng thì
 *   chờ có mạng lại rồi hỏi Drive "đã nhận tới byte nào" và tải TIẾP từ đó, không tải lại từ đầu.
 * - Mỗi video xong → 1 thông báo "Đã tải lên Drive".
 *
 * Không cần mật khẩu trên điện thoại: Apps Script (chạy bằng tài khoản chủ Sheet) tạo
 * link tải lên → app PUT video thẳng lên link đó. Xong thì báo script gắn link vào Sheet.
 */
class DriveUploader(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG = "DriveUpload"
        const val UNIQUE = "drive_sync"
        const val TAG_ALL = "drive"

        // Tiến độ gửi cho màn Lịch sử quay
        const val P_CODE = "code"
        const val P_PCT = "pct"
        const val P_LEFT = "left"
        const val P_WAIT_NET = "waitNet"

        private const val CH_PROGRESS = "drive_progress"
        private const val CH_DONE = "drive_done"
        private const val GROUP_DONE = "drive_done_group"
        private const val NOTI_PROGRESS = 2001
        private const val NOTI_SUMMARY = 2002

        private const val CHUNK = 8L * 1024 * 1024       // bội số 256 KB (yêu cầu của Drive)
        private const val NET_WAIT_MS = 30 * 60_000L     // mất mạng: chờ trong worker tối đa 30 phút
        private const val STALL_MS = 90_000L             // 90s không gửi được byte nào → coi như rớt mạng
        private const val MAX_FAILS = 8                  // lỗi liên tiếp khi vẫn có mạng → nghỉ, thử lại sau

        // Chỉ 1 lượt tải chạy tại 1 thời điểm (lượt cũ bị dừng mà còn kẹt mạng thì lượt mới chờ nó xong)
        private val runLock = Mutex()
        // kick() chạy tuần tự → không có 2 lần "kiểm tra rồi xếp việc" chồng nhau
        private val kickExec = Executors.newSingleThreadExecutor()

        // Khoá dữ liệu của bản cũ (mỗi video 1 việc riêng)
        private const val K_URI = "uri"
        private const val K_NAME = "name"
        private const val K_ROW = "rowId"
        private const val K_SHEET = "sheet"
        private const val K_CODE = "code"

        /**
         * Thêm 1 video vào hàng chờ tải.
         * manual = false: tự tải sau khi quay (chỉ khi bật trong Cài đặt, theo tuỳ chọn Wi‑Fi).
         * manual = true : người dùng bấm ĐỒNG BỘ DRIVE → tải ngay khi có mạng bất kỳ.
         */
        fun enqueue(ctx: Context, uri: Uri, file: String, rowId: String, sheet: String, code: String,
                    manual: Boolean = false) {
            if (!manual && !Store.driveOn(ctx)) return
            Store.driveQueueAdd(ctx, JSONObject()
                .put("uri", uri.toString()).put("file", file).put("row", rowId)
                .put("sheet", sheet).put("code", code)
                .put("wifi", !manual && Store.driveWifiOnly(ctx)))
            kick(ctx)
        }

        /**
         * Thông tin dòng Sheet của 1 video — gửi kèm khi báo tải xong để script GHI LẠI dòng nếu Sheet
         * đang thiếu (video đã lên Drive thì Sheet bắt buộc phải có dòng).
         * Ưu tiên thông tin lưu lúc quay; video bản cũ không có → dựng tối thiểu từ tên file MÃ_yyyyMMdd_HHmmss.mp4.
         */
        fun rowFor(ctx: Context, file: String, rowId: String = ""): JSONObject {
            Store.rowInfo(ctx, file)?.let { r ->
                if (r.optString("id").isEmpty()) r.put("id", rowId.ifEmpty { "auto-$file" })
                if (r.optString("file").isEmpty()) r.put("file", file)
                return r
            }
            val m = Regex("^(.*)_(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})(\\d{2})\\.mp4$").find(file)
            val date = m?.let { "${it.groupValues[4]}/${it.groupValues[3]}/${it.groupValues[2]}" }
                ?: java.text.SimpleDateFormat("dd/MM/yyyy", Locale.US).format(java.util.Date())
            val time = m?.let { "${it.groupValues[5]}:${it.groupValues[6]}:${it.groupValues[7]}" }.orEmpty()
            val code = (m?.groupValues?.get(1) ?: file.removeSuffix(".mp4")).replace('_', ' ').trim()
            val tab = if (Store.autoMonth(ctx)) {
                try { Store.monthTab(java.text.SimpleDateFormat("dd/MM/yyyy", Locale.US).parse(date)!!) }
                catch (e: Exception) { Store.currentTab(ctx) }
            } else Store.currentTab(ctx)
            return JSONObject().put("id", rowId.ifEmpty { "auto-$file" }).put("file", file).put("sheet", tab)
                .put("date", date).put("time", time).put("code", code)
                .put("note", "Tự ghi lại từ video trên Drive (thiếu thông tin khách / người test)")
        }

        /** Bỏ video khỏi hàng chờ (khi xoá lần quay). Đang tải dở thì worker tự dừng video đó. */
        fun cancel(ctx: Context, file: String) = Store.driveQueueRemove(ctx, file)

        /** Chạy worker nếu hàng chờ còn video. Worker đang chạy thì thôi (nó tự lấy video mới thêm). */
        fun kick(ctx: Context) {
            val app = ctx.applicationContext
            kickExec.execute {
                try {
                    val q = Store.driveQueue(app)
                    if (q.isEmpty() && Store.driveDonePending(app).isEmpty()) return@execute
                    val wm = WorkManager.getInstance(app)
                    if (wm.getWorkInfosForUniqueWork(UNIQUE).get().any { it.state == WorkInfo.State.RUNNING }) return@execute
                    wm.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request(q.all { it.optBoolean("wifi") }))
                } catch (e: Exception) {
                    Log.w(TAG, "kick lỗi", e)
                }
            }
        }

        private fun request(wifiOnly: Boolean): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<DriveUploader>()
                .addTag(TAG_ALL)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(
                    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                // Nghỉ ngắn rồi thử lại (tăng tuyến tính 30s, 60s, 90s…) — không để luỹ thừa lên hàng giờ
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()

        fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH_PROGRESS) == null)
                nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Đang tải video lên Drive", NotificationManager.IMPORTANCE_LOW))
            if (nm.getNotificationChannel(CH_DONE) == null)
                nm.createNotificationChannel(NotificationChannel(CH_DONE, "Đã tải xong video lên Drive", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private enum class Outcome { DONE, GONE, DEFER, RETRY }
    private class ServerError(msg: String) : Exception(msg)
    private class Chunk(val next: Long = -1, val fileId: String? = null, val expired: Boolean = false)

    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var foreground = false

    // Trạng thái đang tải (luồng tải ghi, luồng báo tiến độ đọc)
    @Volatile private var curCode = ""
    @Volatile private var curTotal = 0L
    private val curSent = AtomicLong(0)
    @Volatile private var leftCount = 0
    @Volatile private var waitingNet = false

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) { runLock.withLock { work() } }

    private suspend fun work(): Result {
        val ctx = applicationContext

        // Việc của bản app cũ (mỗi video 1 việc riêng) → chuyển vào hàng chờ chung
        inputData.getString(K_URI)?.let { u ->
            inputData.getString(K_NAME)?.let { name ->
                Store.driveQueueAdd(ctx, JSONObject().put("uri", u).put("file", name)
                    .put("row", inputData.getString(K_ROW).orEmpty())
                    .put("sheet", inputData.getString(K_SHEET) ?: Store.DEFAULT_TAB)
                    .put("code", inputData.getString(K_CODE).orEmpty()).put("wifi", false))
            }
            kick(ctx)
            return Result.success()
        }

        val script = Store.getString(ctx, SheetLogger.KEY_URL).trim()
        if (!SheetLogger.isScriptUrl(script)) return Result.success()
        flushDone(script)
        if (Store.driveQueue(ctx).isEmpty()) return Result.success()

        ensureChannels(ctx)
        leftCount = Store.driveQueue(ctx).size
        // Chạy tiền cảnh → hệ thống không giết khi tắt màn hình / để qua đêm.
        // Android 12+ có thể không cho bắt đầu tiền cảnh khi app đang ở nền (trừ khi app được
        // bỏ tối ưu pin) → vẫn tải bình thường, chỉ là có thể bị dừng sớm rồi tự chạy lại.
        foreground = try { setForeground(fgInfo()); true } catch (e: Exception) {
            Log.w(TAG, "không chạy tiền cảnh được", e); false
        }

        @Suppress("DEPRECATION")
        val wifiLock = (ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "QuayTest:drive")
            .apply { setReferenceCounted(false) }
        val reporter = bg.launch { while (true) { report(); delay(2000) } }

        try {
            wifiLock.acquire()
            val deferred = HashSet<String>()
            while (!isStopped) {
                val metered = isMetered()
                val q = Store.driveQueue(ctx)
                leftCount = q.size
                val item = q.firstOrNull {
                    it.optString("file") !in deferred && (!it.optBoolean("wifi") || !metered)
                } ?: break
                val file = item.optString("file")
                // Đã lên Drive rồi (vd. lượt trước tải xong nhưng chưa kịp bỏ khỏi hàng chờ) → KHÔNG tải lại
                if (file in Store.uploadedFiles(ctx)) { Store.driveQueueRemove(ctx, file); continue }
                when (uploadOne(item, script)) {
                    Outcome.DONE -> Store.driveQueueRemove(ctx, file)
                    Outcome.GONE -> Store.driveQueueRemove(ctx, file)
                    Outcome.DEFER -> deferred += file
                    Outcome.RETRY -> return Result.retry()
                }
                curCode = ""; curTotal = 0; curSent.set(0)
                report()
            }
            if (isStopped) return Result.retry()
            flushDone(script)
            val left = Store.driveQueue(ctx)
            return when {
                deferred.isNotEmpty() -> Result.retry()   // dòng Sheet chưa gửi được → thử lại sau
                left.isNotEmpty() -> {
                    // Chỉ còn video "chỉ tải qua Wi‑Fi" mà đang dùng 4G → hẹn việc mới chờ Wi‑Fi
                    WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request(true))
                    Result.success()
                }
                else -> Result.success()
            }
        } finally {
            reporter.cancel()
            bg.cancel()
            try { if (wifiLock.isHeld) wifiLock.release() } catch (e: Exception) { }
        }
    }

    // ================= 1 VIDEO =================

    private suspend fun uploadOne(item: JSONObject, script: String): Outcome {
        val ctx = applicationContext
        val file = item.optString("file")
        val uri = Uri.parse(item.optString("uri"))
        val rowId = item.optString("row")
        val sheet = item.optString("sheet").ifEmpty { Store.DEFAULT_TAB }

        // Ngày/giờ quay lấy từ tên file MÃ_yyyyMMdd_HHmmss.mp4 → script xếp thư mục năm/tháng/ngày
        val m = Regex("_(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})\\d{2}\\.mp4$").find(file)
        val shotDate = m?.let { "${it.groupValues[3]}/${it.groupValues[2]}/${it.groupValues[1]}" }.orEmpty()
        val shotTime = m?.let { "${it.groupValues[4]}${it.groupValues[5]}" }.orEmpty()
        val shotCode = item.optString("code").ifEmpty { file.replace(Regex("_\\d{8}_\\d{6}\\.mp4$"), "") }

        // Gửi bù dòng nhật ký trước (nếu được). Chưa gửi được cũng không sao: lúc báo tải xong,
        // script tự ghi dòng từ thông tin gửi kèm → video trên Drive luôn có dòng trên Sheet.
        if (rowId.isNotEmpty()) SheetLogger.flush(ctx)

        // Video còn trong máy không (có thể đã bị xoá)
        val size = try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
        } catch (e: Exception) { -1L }
        if (size <= 0) return Outcome.GONE

        curCode = shotCode; curTotal = size; curSent.set(0)
        report()

        var fails = 0
        while (true) {
            if (isStopped) return Outcome.RETRY
            if (!inQueue(file)) return Outcome.GONE   // người dùng đã xoá lần quay này
            if (file in Store.uploadedFiles(ctx)) return Outcome.DONE
            try {
                // 1) Có link phiên cũ → hỏi Drive đã nhận tới đâu
                var url = Store.driveSession(ctx, file)
                var offset = 0L
                if (url != null) {
                    val st = status(url, size)
                    when {
                        st.fileId != null -> return finish(item, st.fileId, shotCode, shotDate, shotTime, sheet, rowId, size)
                        st.expired -> { Store.setDriveSession(ctx, file, null); url = null }
                        else -> offset = st.next
                    }
                }
                // 2) Chưa có → xin link tải lên mới
                if (url == null) {
                    val init = SheetLogger.postAndRead(script, JSONObject()
                        .put("action", "uploadInit").put("name", file).put("size", size)
                        .put("mime", "video/mp4").put("sheet", sheet)
                        .put("date", shotDate).put("time", shotTime).put("code", shotCode))
                    val u = init?.optString("uploadUrl").orEmpty()
                    if (init == null) throw IOException("uploadInit không trả lời")
                    if (!init.optBoolean("ok") || u.isEmpty()) throw ServerError("uploadInit lỗi: $init")
                    url = u
                    Store.setDriveSession(ctx, file, u)
                    offset = 0
                }
                // 3) Tải từng khúc
                while (offset < size) {
                    if (isStopped) return Outcome.RETRY
                    if (!inQueue(file)) return Outcome.GONE
                    curSent.set(offset)
                    val r = putChunk(url, uri, offset, minOf(CHUNK, size - offset), size)
                    when {
                        r.fileId != null -> return finish(item, r.fileId, shotCode, shotDate, shotTime, sheet, rowId, size)
                        r.expired -> { Store.setDriveSession(ctx, file, null); throw IOException("phiên tải lên hết hạn") }
                        else -> offset = r.next
                    }
                    fails = 0
                }
                // Gửi đủ byte mà chưa nhận fileId → vòng sau hỏi lại trạng thái
            } catch (e: CancellationException) {
                throw e   // worker bị dừng → thoát ngay, không coi là rớt mạng
            } catch (e: ServerError) {
                Log.w(TAG, e.message.orEmpty())
                if (++fails >= MAX_FAILS) return Outcome.RETRY
                delay(10_000L * fails)
            } catch (e: Exception) {
                // Rớt mạng / hết thời gian chờ → đợi có mạng lại rồi tải TIẾP
                Log.w(TAG, "tải dở ($file): ${e.message}")
                if (!waitForNetwork()) return Outcome.RETRY
                if (++fails >= MAX_FAILS * 3) return Outcome.RETRY
                delay(3000)
            }
        }
    }

    private suspend fun finish(item: JSONObject, fileId: String, code: String, date: String, time: String,
                               sheet: String, rowId: String, size: Long): Outcome {
        val ctx = applicationContext
        val file = item.optString("file")
        // Ghi nhận NGAY: đánh dấu đã lên Drive + bỏ khỏi hàng chờ, rồi mới báo script.
        // (Trước đây rớt mạng đúng lúc báo script → video còn trong hàng chờ → tải lại thành 2 file.)
        Store.markUploaded(ctx, file, fileId)
        Store.driveQueueRemove(ctx, file)
        Store.driveDonePendingAdd(ctx, JSONObject()
            .put("action", "uploadDone").put("sheet", sheet).put("id", rowId)
            .put("fileId", fileId).put("code", code).put("date", date).put("time", time)
            .put("row", rowFor(ctx, file, rowId)))
        notifyDone(file, code, date, time, size)
        flushDone(Store.getString(ctx, SheetLogger.KEY_URL).trim())
        return Outcome.DONE
    }

    /**
     * Báo script các video đã tải xong: script chuyển video vào năm/tháng/ngày và gắn link vào
     * ô Mã máy trên Sheet. Lỗi (mất mạng) → giữ lại, báo bù ở lượt sau. Video đã an toàn trên Drive.
     */
    private fun flushDone(script: String) {
        if (!SheetLogger.isScriptUrl(script)) return
        for (o in Store.driveDonePending(applicationContext)) {
            if (isStopped) return
            val res = SheetLogger.postAndRead(script, o) ?: return   // mất mạng → để sau
            // ok, hoặc lỗi không sửa được (vd. file đã bị xoá trên Drive) → bỏ khỏi danh sách
            Store.driveDonePendingRemove(applicationContext, o.optString("fileId"))
            if (!res.optBoolean("ok")) Log.w(TAG, "uploadDone lỗi: $res")
        }
    }

    private fun inQueue(file: String) = Store.driveQueue(applicationContext).any { it.optString("file") == file }

    // ================= HTTP (Drive resumable upload) =================

    /** Hỏi Drive phiên tải lên đã nhận bao nhiêu byte. */
    private fun status(url: String, total: Long): Chunk {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; doOutput = true
            connectTimeout = 20_000; readTimeout = 60_000
            setFixedLengthStreamingMode(0)
            setRequestProperty("Content-Range", "bytes */$total")
        }
        try {
            conn.outputStream.close()
            return response(conn)
        } finally { conn.disconnect() }
    }

    /** Gửi 1 khúc [offset, offset+len). Có "chó canh": 90s không gửi được byte nào → cắt kết nối. */
    private fun putChunk(url: String, uri: Uri, offset: Long, len: Long, total: Long): Chunk {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; doOutput = true
            connectTimeout = 20_000; readTimeout = 90_000
            setFixedLengthStreamingMode(len)
            setRequestProperty("Content-Range", "bytes $offset-${offset + len - 1}/$total")
        }
        val last = AtomicLong(System.currentTimeMillis())
        val dog: Job = bg.launch {
            while (true) {
                delay(10_000)
                if (System.currentTimeMillis() - last.get() > STALL_MS) { conn.disconnect(); break }
            }
        }
        try {
            applicationContext.contentResolver.openInputStream(uri)!!.use { input ->
                skipFully(input, offset)
                conn.outputStream.use { out ->
                    val buf = ByteArray(256 * 1024)
                    var sent = 0L
                    while (sent < len) {
                        if (isStopped) throw IOException("worker dừng")
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), len - sent).toInt())
                        if (n < 0) throw ServerError("file ngắn hơn dự kiến")
                        out.write(buf, 0, n)
                        sent += n
                        curSent.set(offset + sent)
                        last.set(System.currentTimeMillis())
                    }
                }
            }
            last.set(System.currentTimeMillis())
            return response(conn)
        } finally {
            dog.cancel()
            conn.disconnect()
        }
    }

    private fun response(conn: HttpURLConnection): Chunk = when (val code = conn.responseCode) {
        308 -> {
            // "Range: bytes=0-12345" → byte tiếp theo là 12346; không có Range = chưa nhận gì
            val end = conn.getHeaderField("Range")?.substringAfterLast('-')?.trim()?.toLongOrNull()
            Chunk(next = if (end == null) 0 else end + 1)
        }
        200, 201 -> {
            val id = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optString("id")
            if (id.isEmpty()) throw ServerError("Drive không trả id") else Chunk(fileId = id)
        }
        404, 410 -> Chunk(expired = true)
        in 500..599 -> throw IOException("Drive tạm lỗi $code")   // lỗi tạm → chờ rồi tải tiếp
        else -> throw ServerError("Drive trả $code: " + conn.errorStream?.bufferedReader()?.readText()?.take(200))
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val s = input.skip(left)
            if (s > 0) { left -= s; continue }
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw ServerError("file ngắn hơn dự kiến")
            left -= r
        }
    }

    // ================= MẠNG =================

    private fun cm() = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun hasNetwork(): Boolean {
        val caps = cm().getNetworkCapabilities(cm().activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isMetered() = cm().isActiveNetworkMetered

    /** Chờ có mạng lại (tối đa 30 phút, vẫn giữ tiền cảnh). Hết giờ → trả false để WorkManager lo tiếp. */
    private suspend fun waitForNetwork(): Boolean {
        if (hasNetwork()) return true
        waitingNet = true
        report()
        val until = System.currentTimeMillis() + NET_WAIT_MS
        try {
            while (System.currentTimeMillis() < until) {
                if (isStopped) return false
                delay(5000)
                if (hasNetwork()) { delay(3000); return true }
            }
            return false
        } finally {
            waitingNet = false
        }
    }

    // ================= THÔNG BÁO =================

    private fun openHistory(): PendingIntent = PendingIntent.getActivity(
        applicationContext, 2,
        Intent(applicationContext, SheetLogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun pct(): Int = if (curTotal > 0) (curSent.get() * 100 / curTotal).toInt().coerceIn(0, 100) else 0

    private fun fgInfo(): ForegroundInfo {
        val text = when {
            waitingNet -> "Mất mạng — sẽ tải tiếp khi có mạng lại"
            curCode.isNotEmpty() -> String.format(Locale.US, "%s — %d%%  (%.0f / %.0f MB)",
                curCode, pct(), curSent.get() / 1048576.0, curTotal / 1048576.0)
            else -> "Đang chuẩn bị…"
        }
        val n = NotificationCompat.Builder(applicationContext, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Đang tải lên Drive — còn $leftCount video")
            .setContentText(text)
            .setProgress(100, pct(), curCode.isEmpty() || waitingNet)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openHistory())
            .build()
        return ForegroundInfo(NOTI_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** Cập nhật tiến độ cho màn Lịch sử quay + thông báo tiền cảnh. */
    private suspend fun report() {
        try {
            setProgress(workDataOf(P_CODE to curCode, P_PCT to pct(), P_LEFT to leftCount, P_WAIT_NET to waitingNet))
            if (foreground) setForeground(fgInfo())
        } catch (e: Exception) { /* worker đã dừng */ }
    }

    private fun notifyDone(file: String, code: String, date: String, time: String, size: Long) {
        val ctx = applicationContext
        val whenShot = listOf(date, if (time.length == 4) "${time.take(2)}:${time.drop(2)}" else "")
            .filter { it.isNotEmpty() }.joinToString(" ")
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("✔ Đã tải lên Drive: $code")
            .setContentText(listOf(whenShot, String.format(Locale.US, "%.0f MB", size / 1048576.0))
                .filter { it.isNotEmpty() }.joinToString("  •  "))
            .setGroup(GROUP_DONE)
            .setAutoCancel(true)
            .setContentIntent(openHistory())
            .build()
        val summary = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Video đã tải lên Drive")
            .setGroup(GROUP_DONE)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(openHistory())
            .build()
        try {
            val nm = NotificationManagerCompat.from(ctx)
            nm.notify(3000 + (file.hashCode() and 0xFFFF), n)
            nm.notify(NOTI_SUMMARY, summary)
        } catch (e: SecurityException) { /* chưa cho phép thông báo */ }
    }
}
