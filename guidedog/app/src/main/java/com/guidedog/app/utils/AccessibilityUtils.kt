package com.guidedog.app.utils

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

object AccessibilityUtils {

    fun isTalkBackEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.isEnabled && am.isTouchExplorationEnabled
    }

    fun announceForAccessibility(view: View, text: CharSequence) {
        view.announceForAccessibility(text)
    }

    fun sendAccessibilityEvent(view: View, eventType: Int) {
        view.sendAccessibilityEvent(eventType)
    }

    fun setAccessibilityFocus(view: View) {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }
}
