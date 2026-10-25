package com.nemos.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.nemos.app.data.SessionManager
import com.nemos.app.databinding.ActivityAuthBinding

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)

        if (sessionManager.getSavedKey().isNotBlank()) {
            openMainScreen()
            return
        }

        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val hwid = getDeviceHwid()
        binding.tvHwid.text = hwid
        binding.etLicenseKey.setText(sessionManager.getSavedKey())

        binding.btnVerify.setOnClickListener {
            val key = binding.etLicenseKey.text?.toString()?.trim()?.uppercase().orEmpty()
            val serverUrl = sessionManager.getServerUrl()

            if (!key.startsWith("GRND-") || key.length < 8) {
                showError("Введите корректный ключ (например, GRND-MVCC-5VU5)")
                return@setOnClickListener
            }

            sessionManager.saveSession(key, "2026-12-31", serverUrl)
            openMainScreen()
        }
    }

    private fun getDeviceHwid(): String {
        val rawId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
        return rawId.uppercase()
    }

    private fun showError(msg: String) {
        binding.tvStatus.text = "⚠️ $msg"
        binding.tvStatus.visibility = View.VISIBLE
    }

    private fun openMainScreen() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
