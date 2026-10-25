package com.example.otginterval

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Transparent, app-local touch shield. Does not intercept hardware buttons
 * or claim privileged access to Android system UI.
 */
class TouchLockOverlay(context: Context, private val unlock: () -> Unit) : FrameLayout(context) {
    private var startX = 0f
    private var startY = 0f
    private var gestureStartedOnLeft = false
    private val density = resources.displayMetrics.density

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        isFocusable = true
        contentDescription = "Управление заблокировано. Проведите слева направо для разблокировки."

        val hint = TextView(context).apply {
            text = "🔒 Управление заблокировано\nПроведите слева направо для разблокировки"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(205, 20, 25, 30))
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        addView(hint, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(18), 0, dp(18), dp(140))
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                gestureStartedOnLeft = startX <= width * 0.55f
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - startX
                val dy = event.y - startY
                if (gestureStartedOnLeft && dx >= dp(140) &&
                    abs(dy) <= dp(110) && dx > abs(dy) * 1.5f) {
                    performClick()
                    unlock()
                }
                gestureStartedOnLeft = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                gestureStartedOnLeft = false
                return true
            }
            else -> return true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
