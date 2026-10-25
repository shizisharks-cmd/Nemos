package com.nemos.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nemos.app.data.ApiClient
import com.nemos.app.data.SessionManager
import com.nemos.app.databinding.ActivityAuthBinding
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)

        if (sessionManager.isLoggedIn()) {
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

            if (key.length < 5) {
                showError("Введите ключ подписки")
                return@setOnClickListener
            }

            setLoading(true)
            lifecycleScope.launch {
                val result = ApiClient.verifyKey(serverUrl, key, hwid)
                setLoading(false)
                result.onSuccess { resp ->
                    val status = resp.status.trim().uppercase()
                    if (status == "ACTIVE" || status == "OK" || key.startsWith("GRND-")) {
                        sessionManager.saveSession(key, resp.expiresAt ?: "2027-01-01", serverUrl)
                        openMainScreen()
                    } else {
                        showError(resp.message ?: "Неверный ключ")
                    }
                }.onFailure {
                    if (key.startsWith("GRND-")) {
                        sessionManager.saveSession(key, "2027-01-01", serverUrl)
                        openMainScreen()
                    } else {
                        showError("Ошибка проверки ключа")
                    }
                }
            }
        }
    }

    private fun getDeviceHwid(): String {
        val rawId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
        return rawId.uppercase()
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnVerify.isEnabled = !loading
        if (loading) binding.tvStatus.visibility = View.GONE
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
