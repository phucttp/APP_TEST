package com.quaytest.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.quaytest.app.databinding.DialogFrameBinding

/**
 * Nền GÓC VÁT (chamfer) — quy tắc của design system: không bo tròn.
 * cutDp: 8 (sm) / 12 (md) / 20 (lg). strokeColor = viền (tuỳ chọn).
 */
fun View.chamfer(fillColor: Int, cutDp: Float = 8f, strokeColor: Int? = null, strokeDp: Float = 1f) {
    val d = resources.displayMetrics.density
    val shape = ShapeAppearanceModel.builder().setAllCorners(CornerFamily.CUT, cutDp * d).build()
    background = MaterialShapeDrawable(shape).apply {
        this.fillColor = ColorStateList.valueOf(fillColor)
        if (strokeColor != null) setStroke(strokeDp * d, strokeColor)
    }
}

/** Ẩn bàn phím + bỏ focus ô đang nhập. */
fun View.hideKeyboard() {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(windowToken, 0)
    rootView.findFocus()?.clearFocus()
}

/**
 * Chạm vào chỗ trống (không phải ô nhập / nút) trong cây view này → bàn phím tự lui xuống.
 * Gắn cho mọi ViewGroup (trừ ô nhập); ô nhập và nút tự "ăn" cú chạm nên không bị ảnh hưởng.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.tapOutsideHidesKeyboard() {
    if (this is EditText) return
    if (this is ViewGroup) {
        setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN) v.hideKeyboard()
            false
        }
        for (i in 0 until childCount) getChildAt(i).tapOutsideHidesKeyboard()
    }
}

/** Hộp thoại đang mở (khung chung [dialog_frame]). */
class TcDialog(val dialog: AlertDialog, val v: DialogFrameBinding) {
    val ok get() = v.btnOk
    fun dismiss() = dialog.dismiss()
    val isShowing get() = dialog.isShowing
}

/**
 * Hộp thoại kiểu chung của app: tiêu đề (+ dòng phụ) | nội dung cuộn được | hàng nút to ở đáy
 * [extra] [cancel] [ok]. Nút ok KHÔNG tự đóng nếu có [onOk] (để kiểm tra dữ liệu trước) —
 * tự gọi `it.dismiss()` khi hợp lệ. Nút cancel / extra luôn đóng hộp rồi mới chạy callback.
 */
fun Activity.tcDialog(
    title: CharSequence,
    subtitle: CharSequence? = null,
    message: CharSequence? = null,
    body: View? = null,
    ok: String = "OK",
    cancel: String? = "HUỶ",
    extra: String? = null,
    okColor: Int = R.color.tc_blue,
    accentColor: Int = okColor,
    cancelable: Boolean = true,
    onCancel: (() -> Unit)? = null,
    onExtra: (() -> Unit)? = null,
    onOk: ((TcDialog) -> Unit)? = null,
): TcDialog {
    val v = DialogFrameBinding.inflate(layoutInflater)
    v.root.chamfer(Color.WHITE, 14f)          // khung hộp thoại góc vát
    v.tvTitle.text = title
    v.accent.setBackgroundColor(getColor(accentColor))
    subtitle?.let { v.tvSubtitle.text = it; v.tvSubtitle.visibility = View.VISIBLE }
    message?.let { v.tvMessage.text = it; v.tvMessage.visibility = View.VISIBLE }
    body?.let { (it.parent as? ViewGroup)?.removeView(it); v.body.addView(it) }

    v.btnOk.text = ok
    v.btnOk.backgroundTintList = ColorStateList.valueOf(getColor(okColor))
    if (okColor == R.color.tc_amber) v.btnOk.setTextColor(getColor(R.color.tc_black))
    if (cancel == null) v.btnCancel.visibility = View.GONE else v.btnCancel.text = cancel
    if (extra != null) {
        v.btnExtra.text = extra
        v.btnExtra.visibility = View.VISIBLE
        // 3 nút 1 hàng: nút HUỶ (chữ ngắn) hẹp lại, nhường chỗ cho 2 nút chữ dài
        (v.btnCancel.layoutParams as android.widget.LinearLayout.LayoutParams).weight = 0.75f
        (v.btnExtra.layoutParams as android.widget.LinearLayout.LayoutParams).weight = 1.15f
    }
    // Chỉ còn 1 nút → không cần chia đôi, ok chiếm cả hàng

    val dlg = AlertDialog.Builder(this).setView(v.root).setCancelable(cancelable).create()
    val d = TcDialog(dlg, v)
    v.btnOk.setOnClickListener { if (onOk == null) dlg.dismiss() else onOk(d) }
    v.btnCancel.setOnClickListener { dlg.dismiss(); onCancel?.invoke() }
    v.btnExtra.setOnClickListener { dlg.dismiss(); onExtra?.invoke() }
    if (cancelable) dlg.setOnCancelListener { onCancel?.invoke() }
    dlg.show()
    dlg.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val dm = resources.displayMetrics
        val w = minOf((dm.widthPixels * 0.94f).toInt(), (560 * dm.density).toInt())
        setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    v.root.post { v.root.tapOutsideHidesKeyboard() }
    return d
}
