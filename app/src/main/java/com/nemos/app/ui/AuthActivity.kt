package com.nemos.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nemos.app.R
import com.nemos.app.data.SessionManager
import com.nemos.app.databinding.ActivityAuthBinding
import com.nemos.app.network.LicenseApiClient
import com.nemos.app.network.VerifyStatus
import kotlinx.coroutines.launch
import java.util.regex.Pattern

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var sessionManager: SessionManager

    private val keyPattern = Pattern.compile("^GRND-[A-Z0-9]{4}-[A-Z0-9]{4}$")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)

        // Если сессия уже активна — сразу открываем главный экран
        if (sessionManager.isAuthorized() && sessionManager.getSavedKey().isNotEmpty()) {
            openMainScreen()
            return
        }

        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val hwid = sessionManager.getDeviceHwid()
        binding.tvHwidValue.text = hwid
        binding.etServerUrl.setText(sessionManager.getServerUrl())
        binding.etLicenseKey.setText(sessionManager.getSavedKey())

        binding.btnVerifyLicense.setOnClickListener {
            performLicenseVerification(hwid)
        }
    }

    private fun performLicenseVerification(hwid: String) {
        val rawKey = binding.etLicenseKey.text?.toString()?.trim()?.uppercase().orEmpty()
        val serverUrl = binding.etServerUrl.text?.toString()?.trim().orEmpty()

        if (serverUrl.isEmpty() || !serverUrl.startsWith("http")) {
            showStatus("Укажите корректный URL сервера (http://... или https://...)", R.color.status_red)
            return
        }

        if (!keyPattern.matcher(rawKey).matches()) {
            showStatus("Введите ключ в формате GRND-XXXX-XXXX", R.color.status_red)
            return
        }

        binding.btnVerifyLicense.isEnabled = false
        binding.progressAuth.visibility = View.VISIBLE
        showStatus("Проверка лицензии и привязки HWID на сервере...", R.color.text_secondary)

        lifecycleScope.launch {
            val result = LicenseApiClient.verifyLicense(
                baseUrl = serverUrl,
                licenseKey = rawKey,
                hwid = hwid
            )

            binding.btnVerifyLicense.isEnabled = true
            binding.progressAuth.visibility = View.GONE

            when (result.status) {
                VerifyStatus.ACTIVE -> {
                    sessionManager.saveSession(
                        licenseKey = rawKey,
                        expiresAt = result.expiresAt,
                        serverUrl = serverUrl
                    )
                    showStatus("✅ Лицензия активна! Вход выполнен.", R.color.status_green)
                    openMainScreen()
                }
                VerifyStatus.INVALID_HWID -> {
                    showStatus(
                        "⛔ Ошибка INVALID_HWID: этот ключ уже привязан к другому устройству!",
                        R.color.status_red
                    )
                }
                VerifyStatus.EXPIRED -> {
                    showStatus(
                        "⌛️ Срок действия ключа истек (EXPIRED). Продлите подписку в Telegram-боте.",
                        R.color.status_yellow
                    )
                }
                VerifyStatus.NOT_FOUND -> {
                    showStatus(
                        "❌ Ключ не найден в базе данных (NOT_FOUND). Проверьте правильность ввода.",
                        R.color.status_red
                    )
                }
                VerifyStatus.NETWORK_ERROR -> {
                    showStatus(
                        "⚠️ Ошибка сети: ${result.errorMessage ?: "Не удалось связаться с сервером"}",
                        R.color.status_red
                    )
                }
            }
        }
    }

    private fun showStatus(message: String, colorRes: Int) {
        binding.tvAuthStatus.text = message
        binding.tvAuthStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun openMainScreen() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
