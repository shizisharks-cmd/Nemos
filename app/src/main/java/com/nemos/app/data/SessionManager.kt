package com.nemos.app.data

import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("nemos_session_prefs", Context.MODE_PRIVATE)

    fun saveLogin(key: String, expiresAt: String = "2027-01-01") {
        prefs.edit()
            .putBoolean("is_logged_in", true)
            .putString("saved_key", key)
            .putString("expires_at", expiresAt)
            .apply()
    }

    fun saveSession(key: String, expiresAt: String = "2027-01-01") {
        saveLogin(key, expiresAt)
    }

    fun isLoggedIn(): Boolean {
        return prefs.getBoolean("is_logged_in", false) && !getSavedKey().isNullOrBlank()
    }

    fun getSavedKey(): String {
        return prefs.getString("saved_key", "") ?: ""
    }

    fun getExpiresAt(): String {
        return prefs.getString("expires_at", "2027-01-01") ?: "2027-01-01"
    }

    fun getSelectedPresetId(): String {
        return prefs.getString("selected_preset_id", "karma") ?: "karma"
    }

    fun setSelectedPresetId(presetId: String) {
        prefs.edit().putString("selected_preset_id", presetId).apply()
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }
}
