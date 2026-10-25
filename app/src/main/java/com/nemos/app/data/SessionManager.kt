package com.nemos.app.data

import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("nemos_prefs", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_SERVER = "http://78.154.103.46:11903"
        private const val KEY_LICENSE = "license_key"
        private const val KEY_EXPIRES = "expires_at"
        private const val KEY_SERVER = "server_url"
        private const val KEY_PRESET = "selected_preset"
        private const val KEY_DELAY = "swap_delay_seconds"
    }

    fun isLoggedIn(): Boolean = getSavedKey().isNotBlank()

    fun getSavedKey(): String = prefs.getString(KEY_LICENSE, "") ?: ""

    fun getExpiresAt(): String = prefs.getString(KEY_EXPIRES, "Активна") ?: "Активна"

    fun getServerUrl(): String = prefs.getString(KEY_SERVER, DEFAULT_SERVER)?.ifBlank { DEFAULT_SERVER } ?: DEFAULT_SERVER

    fun saveSession(key: String, expiresAt: String, serverUrl: String = DEFAULT_SERVER) {
        prefs.edit()
            .putString(KEY_LICENSE, key)
            .putString(KEY_EXPIRES, expiresAt)
            .putString(KEY_SERVER, serverUrl.ifBlank { DEFAULT_SERVER })
            .apply()
    }

    fun getSelectedPresetId(): String = prefs.getString(KEY_PRESET, "fps_boost") ?: "fps_boost"

    fun setSelectedPresetId(presetId: String) {
        prefs.edit().putString(KEY_PRESET, presetId).apply()
    }

    fun getSwapDelaySeconds(): Int = prefs.getInt(KEY_DELAY, 17)

    fun setSwapDelaySeconds(seconds: Int) {
        prefs.edit().putInt(KEY_DELAY, seconds).apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_LICENSE)
            .remove(KEY_EXPIRES)
            .apply()
    }
}
