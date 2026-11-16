package com.quaytest.app

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

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
