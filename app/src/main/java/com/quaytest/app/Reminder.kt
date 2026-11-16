package com.quaytest.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Calendar

/**
 * - Nhắc đồng bộ Drive lúc 16:00 mỗi ngày (chỉ khi còn video chưa lên Drive).
 * - Dọn video trong máy quá 14 ngày NHƯNG CHỈ những video đã lên Drive
 *   (video chưa lên Drive thì giữ lại, không tự xoá → không mất bằng chứng test).
 */
object Reminder {
    private const val CHANNEL = "sync_reminder"
    private const val NOTI_ID = 1601
    const val HOUR = 16
    const val KEEP_DAYS = 14
    const val EXTRA_SYNC = "open_sync"

    /** Hẹn lần nhắc kế tiếp lúc 16:00 (hôm nay nếu chưa qua, không thì ngày mai). */
    fun scheduleNext(ctx: Context) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, HOUR); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
        }
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Không cần chính xác tới giây → dùng loại không cần quyền "báo thức chính xác"
        am.setWindow(AlarmManager.RTC_WAKEUP, cal.timeInMillis, 10 * 60 * 1000L, pi)
    }

    /** Video trong máy (Movies/QuayTest): danh sách (uri, tên file, ngày thêm ms). */
    private fun localVideos(ctx: Context): List<Triple<android.net.Uri, String, Long>> {
        val out = ArrayList<Triple<android.net.Uri, String, Long>>()
        ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED),
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("${MainActivity.VIDEO_DIR}%"), null
        )?.use { c ->
            while (c.moveToNext()) out += Triple(
                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                c.getString(1).orEmpty(), c.getLong(2) * 1000
            )
        }
        return out
    }

    /** Số video trong máy chưa lên Drive. */
    fun unsyncedCount(ctx: Context): Int {
        val done = Store.uploadedFiles(ctx)
        return localVideos(ctx).count { it.second !in done }
    }

    /** Xoá video trong máy quá 14 ngày và ĐÃ lên Drive. Trả về số video đã xoá. */
    fun cleanupOld(ctx: Context): Int {
        val done = Store.uploadedFiles(ctx)
        val limit = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000
        var n = 0
        localVideos(ctx).filter { it.third < limit && it.second in done }.forEach {
            try { if (ctx.contentResolver.delete(it.first, null, null) > 0) n++ } catch (e: Exception) { }
        }
        return n
    }

    fun showIfNeeded(ctx: Context) {
        val count = unsyncedCount(ctx)
        if (count <= 0) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Nhắc đồng bộ Drive", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, SheetLogActivity::class.java).putExtra(EXTRA_SYNC, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Đồng bộ video lên Google Drive?")
            .setContentText("Có $count video chưa đồng bộ — chạm để chọn và tải lên")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.stat_sys_upload, "ĐỒNG BỘ", open)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(NOTI_ID, n) } catch (e: SecurityException) { /* chưa cho phép thông báo */ }
    }
}

/** Nhận báo thức 16:00 + khởi động lại máy → nhắc, dọn video cũ, hẹn lần sau. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
                    Reminder.cleanupOld(ctx)
                    Reminder.showIfNeeded(ctx)
                }
                Reminder.scheduleNext(ctx)
            } finally { pending.finish() }
        }.start()
    }
}
