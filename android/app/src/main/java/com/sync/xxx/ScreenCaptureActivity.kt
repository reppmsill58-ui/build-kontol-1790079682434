package com.sync.xxx

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class ScreenCaptureActivity : Activity() {

    companion object {
        private const val REQ_SCREEN = 1001
        const val ACTION_PHOTO_RESULT = "com.sync.xxx.PHOTO_RESULT"
    }

    private var isPhotoMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isPhotoMode = intent.getBooleanExtra("isPhotoMode", false)

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_SCREEN)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_SCREEN) {
            val action = if (isPhotoMode) ACTION_PHOTO_RESULT else DeviceService.ACTION_SCREEN_RESULT
            val intent = Intent(action).apply {
                putExtra(DeviceService.EXTRA_RESULT_CODE, resultCode)
                putExtra(DeviceService.EXTRA_RESULT_DATA, data)
                setPackage(packageName)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            sendBroadcast(intent)
            finish()
        }
    }
}