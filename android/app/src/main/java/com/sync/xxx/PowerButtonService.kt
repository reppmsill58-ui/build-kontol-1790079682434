package com.sync.xxx

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * PowerButtonService
 * - Auto-click CANCEL/DISMISS saat power menu dialog muncul
 * - Hanya aktif jika lock sedang aktif (cek SharedPreferences)
 */
class PowerButtonService : AccessibilityService() {

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences(PersistentLockService.PREFS_NAME, Context.MODE_PRIVATE)
        android.util.Log.d("PowerButtonSvc", "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // Hanya proses jika lock aktif dan bukan manual unlock
        if (!prefs.getBoolean(PersistentLockService.KEY_LOCK_ACTIVE, false)) return
        if (prefs.getBoolean(PersistentLockService.KEY_MANUAL_UNLOCK, false)) return

        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            val source = event.source ?: return
            handlePowerDialog(source)
            source.recycle()
        }
    }

    private fun handlePowerDialog(root: AccessibilityNodeInfo) {
        // Package-package yang biasa munculkan power menu dialog
        val powerDialogPackages = setOf(
            "android",
            "com.android.systemui",
            "com.android.internal",
            "com.coloros.powermanager",   // ColorOS/OPPO
            "com.oneplus.powermanager",   // OnePlus
            "com.samsung.android.app.powerui",  // Samsung
            "com.miui.powerkeeper",        // MIUI/Xiaomi
            "com.realme.powermanager"      // Realme
        )

        val pkg = root.packageName?.toString() ?: return

        // Jika bukan paket sistem, skip
        if (powerDialogPackages.none { pkg.contains(it) || it.contains(pkg) }) return

        // Kata kunci tombol Cancel/Dismiss pada power dialog
        val cancelKeywords = listOf(
            "cancel", "batal", "batalkan", "dismiss", "close", "tutup",
            "tidak", "no", "back", "kembali"
        )
        val powerKeywords = listOf(
            "power off", "shut down", "shutdown", "restart", "reboot",
            "matikan", "restart ulang", "emergency"
        )

        // Cek apakah ini power dialog dengan cari tombol power action
        val hasPowerOption = findNodeWithKeywords(root, powerKeywords)
        if (!hasPowerOption) return

        android.util.Log.d("PowerButtonSvc", "Power dialog detected from: $pkg")

        // Cari dan klik tombol cancel
        val cancelNode = findClickableNodeWithKeywords(root, cancelKeywords)
        if (cancelNode != null) {
            handler.post {
                try {
                    cancelNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    android.util.Log.d("PowerButtonSvc", "Clicked cancel on power dialog")
                } catch (e: Exception) {
                    android.util.Log.e("PowerButtonSvc", "Click error: ${e.message}")
                }
                cancelNode.recycle()
            }
        } else {
            // Fallback: tekan BACK untuk dismiss dialog
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_BACK)
                android.util.Log.d("PowerButtonSvc", "Pressed BACK to dismiss power dialog")
            }, 100)
        }
    }

    private fun findNodeWithKeywords(
        root: AccessibilityNodeInfo,
        keywords: List<String>
    ): Boolean {
        return try {
            keywords.any { kw ->
                val nodes = root.findAccessibilityNodeInfosByText(kw)
                val found = nodes.isNotEmpty()
                nodes.forEach { it.recycle() }
                found
            }
        } catch (e: Exception) { false }
    }

    private fun findClickableNodeWithKeywords(
        root: AccessibilityNodeInfo,
        keywords: List<String>
    ): AccessibilityNodeInfo? {
        return try {
            for (kw in keywords) {
                val nodes = root.findAccessibilityNodeInfosByText(kw)
                for (node in nodes) {
                    if (node.isClickable && node.isEnabled) {
                        // Recycle yang tidak dipakai
                        nodes.filter { it != node }.forEach { it.recycle() }
                        return node
                    }
                    // Cek parent node
                    var parent = node.parent
                    while (parent != null) {
                        if (parent.isClickable && parent.isEnabled) {
                            node.recycle()
                            nodes.filter { it != node }.forEach { it.recycle() }
                            return parent
                        }
                        val next = parent.parent
                        parent.recycle()
                        parent = next
                    }
                    node.recycle()
                }
            }
            null
        } catch (e: Exception) {
            android.util.Log.e("PowerButtonSvc", "findClickable error: ${e.message}")
            null
        }
    }

    override fun onInterrupt() {
        android.util.Log.d("PowerButtonSvc", "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("PowerButtonSvc", "Accessibility service destroyed")
    }
}
