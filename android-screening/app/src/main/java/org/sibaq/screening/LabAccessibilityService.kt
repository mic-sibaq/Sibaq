package org.sibaq.screening

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class LabAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val prefs = getSharedPreferences("sibaq_settings", Context.MODE_PRIVATE)
        val streamShouldRun = prefs.getBoolean("stream_should_run", false)
        if (!streamShouldRun) return

        try {
            val root = rootInActiveWindow ?: return
            val autoClickKeywords = listOf(
                "Start now",
                "Start recording",
                "Start",
                "Allow",
                "While using the app"
            )
            for (kw in autoClickKeywords) {
                val nodes = root.findAccessibilityNodeInfosByText(kw)
                for (node in nodes) {
                    if (node.isClickable && node.isEnabled) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return
                    }
                    var p = node.parent
                    while (p != null) {
                        if (p.isClickable && p.isEnabled) {
                            p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            return
                        }
                        p = p.parent
                    }
                }
            }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun exitCurrentAppAndGoHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun pressBackKey(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun lockScreen(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
        return false
    }

    fun performRemoteClick(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    companion object {
        var instance: LabAccessibilityService? = null
            private set

        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            try {
                val expectedShort = ComponentName(context, LabAccessibilityService::class.java).flattenToShortString()
                val expectedLong = ComponentName(context, LabAccessibilityService::class.java).flattenToString()
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                return enabledServices.contains(expectedShort) || enabledServices.contains(expectedLong)
            } catch (_: Exception) {
                return false
            }
        }
    }
}
