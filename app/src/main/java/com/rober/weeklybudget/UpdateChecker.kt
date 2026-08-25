package com.rober.weeklybudget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the GitHub release manifest for a newer build (at most once every
 * 6 hours) and offers to download it. Fails silently offline.
 */
object UpdateChecker {

    private const val MANIFEST_URL =
        "https://github.com/Strongpaw/weekly-budget/releases/latest/download/latest.json"
    private const val ALLOWED_APK_PREFIX = "https://github.com/Strongpaw/"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    fun checkAsync(activity: Activity) {
        val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_update_check", 0) < CHECK_INTERVAL_MS) return

        val myCode = PackageInfoCompat.getLongVersionCode(
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        )

        Thread {
            try {
                val conn = URL(MANIFEST_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val raw = conn.inputStream.bufferedReader().use { it.readText() }
                // strip a UTF-8 BOM if the manifest was written with one
                val text = raw.filterNot { ch -> ch.code == 0xFEFF }
                conn.disconnect()
                val json = JSONObject(text)
                val remoteCode = json.optLong("versionCode", 0)
                val remoteName = json.optString("versionName", "?")
                val apkUrl = json.optString("apk", "")
                val notes = json.optString("notes", "")
                prefs.edit().putLong("last_update_check", now).apply()
                if (remoteCode > myCode && apkUrl.startsWith(ALLOWED_APK_PREFIX)) {
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            showDialog(activity, remoteName, notes, apkUrl)
                        }
                    }
                }
            } catch (e: Exception) {
                // offline or manifest unreachable — try again next launch
            }
        }.start()
    }

    private fun showDialog(activity: Activity, version: String, notes: String, apkUrl: String) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_title, version))
            .setMessage(notes.ifBlank { activity.getString(R.string.update_msg) })
            .setPositiveButton(R.string.update_now) { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)))
                } catch (e: Exception) {
                }
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }
}
