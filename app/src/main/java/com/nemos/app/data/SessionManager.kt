package com.nemos.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.nemos.app.BuildConfig

class SessionManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @SuppressLint("HardwareIds")
    fun getDeviceHwid(): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "UNKNOWN_ANDROID_ID"
        return androidId.uppercase()
    }

    fun saveSession(licenseKey: String, expiresAt: String?, serverUrl: String) {
        prefs.edit()
            .putString(KEY_LICENSE, licenseKey.trim().uppercase())
            .putString(KEY_EXPIRES_AT, expiresAt ?: "Активна")
            .putString(KEY_SERVER_URL, serverUrl.trim().trimEnd('/'))
            .putBoolean(KEY_IS_AUTHORIZED, true)
            .apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_LICENSE)
            .remove(KEY_EXPIRES_AT)
            .putBoolean(KEY_IS_AUTHORIZED, false)
            .apply()
    }

    fun isAuthorized(): Boolean = prefs.getBoolean(KEY_IS_AUTHORIZED, false)

    fun getSavedKey(): String = prefs.getString(KEY_LICENSE, "") ?: ""

    fun getExpiresAt(): String = prefs.getString(KEY_EXPIRES_AT, "Активна") ?: "Активна"

    fun getServerUrl(): String =
        prefs.getString(KEY_SERVER_URL, BuildConfig.DEFAULT_BASE_URL) ?: BuildConfig.DEFAULT_BASE_URL

    fun getSwapDelaySeconds(): Int = prefs.getInt(KEY_SWAP_DELAY, DEFAULT_DELAY_SECONDS)

    fun setSwapDelaySeconds(seconds: Int) {
        val clamped = seconds.coerceIn(5, 60)
        prefs.edit().putInt(KEY_SWAP_DELAY, clamped).apply()
    }

    fun getSelectedPresetId(): String = prefs.getString(KEY_SELECTED_PRESET, "fps_boost") ?: "fps_boost"

    fun setSelectedPresetId(presetId: String) {
        prefs.edit().putString(KEY_SELECTED_PRESET, presetId).apply()
    }

    companion object {
        private const val PREFS_NAME = "nemos_app_session"
        private const val KEY_LICENSE = "license_key"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_IS_AUTHORIZED = "is_authorized"
        private const val KEY_SWAP_DELAY = "swap_delay_seconds"
        private const val KEY_SELECTED_PRESET = "selected_preset_id"
        const val DEFAULT_DELAY_SECONDS = 17
    }
}
