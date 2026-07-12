package com.guidedog.app.utils

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class GestureDetector(
    private val onSingleTap: (() -> Unit)? = null,
    private val onDoubleTap: (() -> Unit)? = null,
    private val onLongPress: (() -> Unit)? = null
) : View.OnTouchListener {

    private val handler = Handler(Looper.getMainLooper())
    private var tapCount = 0
    private var isLongPressTriggered = false
    private var isPressed = false

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    companion object {
        private const val DOUBLE_TAP_TIMEOUT = 300L
        private const val LONG_PRESS_TIMEOUT = 800L
        private const val TAP_SLOP = 20f
    }

    private val longPressRunnable = Runnable {
        if (isPressed && !isLongPressTriggered) {
            isLongPressTriggered = true
            onLongPress?.invoke()
        }
    }

    private val singleTapRunnable = Runnable {
        if (tapCount == 1 && !isLongPressTriggered) {
            onSingleTap?.invoke()
        }
        tapCount = 0
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                isLongPressTriggered = false
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
                handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (dx > TAP_SLOP || dy > TAP_SLOP) {
                    handler.removeCallbacks(longPressRunnable)
                    isLongPressTriggered = false
                }
            }

            MotionEvent.ACTION_UP -> {
                isPressed = false
                handler.removeCallbacks(longPressRunnable)

                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                val duration = System.currentTimeMillis() - downTime

                if (!isLongPressTriggered && dx < TAP_SLOP && dy < TAP_SLOP && duration < LONG_PRESS_TIMEOUT) {
                    tapCount++
                    if (tapCount == 2) {
                        handler.removeCallbacks(singleTapRunnable)
                        tapCount = 0
                        onDoubleTap?.invoke()
                    } else {
                        handler.postDelayed(singleTapRunnable, DOUBLE_TAP_TIMEOUT)
                    }
                }

                isLongPressTriggered = false
            }

            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                handler.removeCallbacks(longPressRunnable)
                handler.removeCallbacks(singleTapRunnable)
                tapCount = 0
                isLongPressTriggered = false
            }
        }
        return true
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
    }
}
