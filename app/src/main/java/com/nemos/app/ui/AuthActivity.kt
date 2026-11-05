package com.nemos.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nemos.app.data.SessionManager
import com.nemos.app.databinding.ActivityAuthBinding

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)

        if (sessionManager.isLoggedIn()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAuth.setOnClickListener {
            val key = binding.etKey.text.toString().trim()
            if (key.length >= 4) {
                sessionManager.saveLogin(key, "2027-01-01")
                Toast.makeText(this, "Ключ успешно активирован!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                binding.tvAuthStatus.visibility = View.VISIBLE
                binding.tvAuthStatus.text = "Введите корректный ключ активации"
            }
        }

        binding.btnBuyBot.setOnClickListener {
            val tgUrl = "https://t.me/Nemos_Trade_Bot"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tgUrl)))
            } catch (_: Exception) {
                Toast.makeText(this, "Бот: $tgUrl", Toast.LENGTH_LONG).show()
            }
        }
    }
}
