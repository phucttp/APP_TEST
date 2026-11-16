package com.quaytest.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lớp phủ lên camera:
 *  - Khi đang khoanh vùng (isEnabled = true): kéo 1 ngón để khoanh vùng có mã sản phẩm.
 *  - Lúc nào cũng vậy: chụm / banh 2 ngón để ZOOM, chạm 1 ngón để LẤY NÉT (khi không khoanh).
 * Vùng ngoài khung bị làm tối, khung viền xanh lá (#00CC66 kiểu Titanium).
 */
class SelectionOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Khung đang chọn, toạ độ theo view (cùng kích thước với PreviewView). */
    var selection: RectF? = null
        private set

    /** Đặt / xoá khung từ code (vd. khôi phục khung cũ). */
    fun setSelectionRect(r: RectF?) {
        selection = r?.let { RectF(it) }
        invalidate()
    }

    /** Gọi khi người dùng nhấc tay sau khi kéo xong một khung hợp lệ. */
    var onSelected: ((RectF) -> Unit)? = null
    /** Chụm/banh 2 ngón: hệ số zoom tương đối (>1 phóng to, <1 thu nhỏ). */
    var onZoom: ((Float) -> Unit)? = null
    /** Chạm 1 ngón (không kéo) khi không khoanh vùng → lấy nét tại điểm đó. */
    var onTap: ((Float, Float) -> Unit)? = null

    private var startX = 0f
    private var startY = 0f
    private var multiTouch = false          // gesture này đã có 2 ngón → không phải khoanh / chạm
    private val dp = resources.displayMetrics.density

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                onZoom?.invoke(d.scaleFactor)
                return true
            }
        })

    private val dimPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * dp
        color = Color.parseColor("#00CC66")
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6 * dp
        strokeCap = Paint.Cap.SQUARE
        color = Color.parseColor("#00CC66")
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        val selecting = isEnabled

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                startX = e.x; startY = e.y
                if (selecting) selection = RectF(startX, startY, startX, startY)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Ngón thứ 2 chạm → đây là zoom, huỷ khung đang kéo dở
                multiTouch = true
                if (selecting) selection = null
            }
            MotionEvent.ACTION_MOVE -> {
                if (selecting && !multiTouch) selection = rectTo(e.x, e.y)
            }
            MotionEvent.ACTION_UP -> {
                if (!multiTouch) {
                    if (selecting) {
                        val r = rectTo(e.x, e.y)
                        if (r.width() < 40 * dp || r.height() < 20 * dp) selection = null
                        else { selection = r; onSelected?.invoke(r) }
                    } else if (abs(e.x - startX) < 20 * dp && abs(e.y - startY) < 20 * dp) {
                        onTap?.invoke(e.x, e.y)
                    }
                }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { if (selecting) selection = null }
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun rectTo(x: Float, y: Float): RectF {
        val cx = x.coerceIn(0f, width.toFloat())
        val cy = y.coerceIn(0f, height.toFloat())
        return RectF(min(startX, cx), min(startY, cy), max(startX, cx), max(startY, cy))
    }

    override fun onDraw(canvas: Canvas) {
        val s = selection ?: return
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, s.top, dimPaint)
        canvas.drawRect(0f, s.bottom, w, h, dimPaint)
        canvas.drawRect(0f, s.top, s.left, s.bottom, dimPaint)
        canvas.drawRect(s.right, s.top, w, s.bottom, dimPaint)
        canvas.drawRect(s, borderPaint)
        // 4 góc đậm kiểu khung ngắm
        val c = min(18 * dp, min(s.width(), s.height()) / 3)
        canvas.drawLine(s.left, s.top, s.left + c, s.top, cornerPaint)
        canvas.drawLine(s.left, s.top, s.left, s.top + c, cornerPaint)
        canvas.drawLine(s.right, s.top, s.right - c, s.top, cornerPaint)
        canvas.drawLine(s.right, s.top, s.right, s.top + c, cornerPaint)
        canvas.drawLine(s.left, s.bottom, s.left + c, s.bottom, cornerPaint)
        canvas.drawLine(s.left, s.bottom, s.left, s.bottom - c, cornerPaint)
        canvas.drawLine(s.right, s.bottom, s.right - c, s.bottom, cornerPaint)
        canvas.drawLine(s.right, s.bottom, s.right, s.bottom - c, cornerPaint)
    }
}
