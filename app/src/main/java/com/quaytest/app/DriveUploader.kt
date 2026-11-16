package com.quaytest.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Tải 1 video lên Google Drive, chạy nền (WorkManager → tự thử lại khi mất mạng / app bị tắt).
 *
 * Không cần mật khẩu trên điện thoại: Apps Script (chạy bằng tài khoản chủ Sheet) tạo
 * "link tải lên 1 lần" (resumable upload session) → app PUT video thẳng lên link đó.
 * Xong thì báo script gắn link video vào ô "Mã máy" trên Sheet.
 */
class DriveUploader(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG = "DriveUpload"
        private const val K_URI = "uri"
        private const val K_NAME = "name"
        private const val K_ROW = "rowId"
        private const val K_SHEET = "sheet"
        private const val K_CODE = "code"

        fun workName(file: String) = "drive_$file"

        /** Xếp hàng tải 1 video (gọi sau khi quay xong). */
        fun enqueue(ctx: Context, uri: Uri, file: String, rowId: String, sheet: String, code: String) {
            if (!Store.driveOn(ctx)) return
            val req = OneTimeWorkRequestBuilder<DriveUploader>()
                .setInputData(workDataOf(K_URI to uri.toString(), K_NAME to file, K_ROW to rowId,
                    K_SHEET to sheet, K_CODE to code))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(
                        if (Store.driveWifiOnly(ctx)) NetworkType.UNMETERED else NetworkType.CONNECTED
                    ).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(workName(file), ExistingWorkPolicy.KEEP, req)
        }

        /** Huỷ tải (khi người dùng xoá lần quay đó). */
        fun cancel(ctx: Context, file: String) =
            WorkManager.getInstance(ctx).cancelUniqueWork(workName(file))
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val uri = Uri.parse(inputData.getString(K_URI) ?: return@withContext Result.failure())
        val name = inputData.getString(K_NAME) ?: return@withContext Result.failure()
        val rowId = inputData.getString(K_ROW).orEmpty()
        val sheet = inputData.getString(K_SHEET) ?: Store.DEFAULT_TAB
        val code = inputData.getString(K_CODE).orEmpty()
        val script = Store.getString(ctx, SheetLogger.KEY_URL).trim()
        if (!SheetLogger.isScriptUrl(script)) return@withContext Result.retry()

        // Dòng nhật ký phải lên Sheet trước (để gắn link vào đúng dòng)
        SheetLogger.flush(ctx)
        if (SheetLogger.pendingRows(ctx).any { it.optString("id") == rowId }) return@withContext Result.retry()

        // Video còn trong máy không (có thể đã bị xoá)
        val size = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
        if (size <= 0) return@withContext Result.failure()

        // 1) Xin link tải lên 1 lần
        val init = SheetLogger.postAndRead(script, JSONObject()
            .put("action", "uploadInit").put("name", name).put("size", size)
            .put("mime", "video/mp4").put("sheet", sheet))
        val uploadUrl = init?.optString("uploadUrl").orEmpty()
        if (init == null || !init.optBoolean("ok") || uploadUrl.isEmpty()) {
            Log.w(TAG, "uploadInit lỗi: $init")
            return@withContext if (runAttemptCount < 8) Result.retry() else Result.failure()
        }

        // 2) PUT video thẳng lên Drive
        val fileId = try {
            put(uploadUrl, uri, size)
        } catch (e: Exception) {
            Log.w(TAG, "PUT lỗi", e); null
        } ?: return@withContext if (runAttemptCount < 8) Result.retry() else Result.failure()

        Store.markUploaded(ctx, name, fileId)

        // 3) Gắn link video vào ô Mã máy trên Sheet (lỗi cũng không sao, video đã lên Drive)
        SheetLogger.postAndRead(script, JSONObject()
            .put("action", "uploadDone").put("sheet", sheet).put("id", rowId)
            .put("fileId", fileId).put("code", code))
        Result.success()
    }

    /** PUT toàn bộ file lên link resumable của Drive; trả về fileId hoặc null. */
    private fun put(uploadUrl: String, uri: Uri, size: Long): String? {
        val conn = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 120000
            setFixedLengthStreamingMode(size)
            setRequestProperty("Content-Type", "video/mp4")
        }
        try {
            applicationContext.contentResolver.openInputStream(uri)!!.use { input ->
                conn.outputStream.use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (isStopped) throw InterruptedException("worker dừng")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            val code = conn.responseCode
            if (code !in 200..201) {
                Log.w(TAG, "Drive trả $code: " + (conn.errorStream?.bufferedReader()?.readText()?.take(200)))
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body).optString("id").ifEmpty { null }
        } finally {
            conn.disconnect()
        }
    }
}
