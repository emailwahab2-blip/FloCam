package com.flocam.app

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.FrameLayout

/**
 * Root overlay yang menangkap tombol back langsung di dispatchKeyEvent,
 * tanpa bergantung pada view mana yang sedang fokus (fokus bisa hilang
 * setelah siklus fullscreen pertama).
 */
class BackAwareFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onBack: (() -> Boolean)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && onBack?.invoke() == true) return true
            if (onBack != null && event.action == KeyEvent.ACTION_DOWN) return true
        }
        return super.dispatchKeyEvent(event)
    }
}
