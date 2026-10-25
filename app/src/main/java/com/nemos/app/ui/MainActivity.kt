package com.nemos.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nemos.app.R
import com.nemos.app.data.PresetRepository
import com.nemos.app.data.SessionManager
import com.nemos.app.databinding.ActivityMainBinding
import com.nemos.app.service.HotSwapService
import com.nemos.app.shizuku.ShizukuHelper
import com.nemos.app.shizuku.ShizukuState
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionManager: SessionManager

    private val gamePackages = listOf(
        "com.grandmobile.game",
        "com.grand.launcher",
        "com.grand.cr"
    )

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuHelper.SHIZUKU_PERMISSION_REQUEST_CODE) {
                refreshShizukuStatusUi()
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "✅ Доступ Shizuku включен!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "❌ Доступ Shizuku не выдан.", Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshShizukuStatusUi() }
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { refreshShizukuStatusUi() }
    }

    private val hotSwapStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(HotSwapService.EXTRA_STATUS_MESSAGE) ?: return
            binding.tvHotSwapLog.text = msg
        }
    }

    private val zipPickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                binding.tvPresetPathInfo.text = "⏳ Загружаем твою сборку..."
                lifecycleScope.launch {
                    val result = PresetRepository.importZipPreset(this@MainActivity, uri)
                    result.onSuccess { count ->
                        selectPresetCard("custom_zip")
                        binding.tvPresetPathInfo.text = "✅ Своя сборка загружена ($count файлов)"
                        Toast.makeText(
                            this@MainActivity,
                            "✅ Своя сборка готова к игре!",
                            Toast.LENGTH_LONG
                        ).show()
                    }.onFailure { err ->
                        binding.tvPresetPathInfo.text = "❌ Не удалось загрузить архив"
                        Toast.makeText(
                            this@MainActivity,
                            err.localizedMessage ?: "Ошибка чтения архива",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val key = sessionManager.getSavedKey()
        val exp = sessionManager.getExpiresAt().take(10)
        binding.tvLicenseInfo.text = "Ключ: $key • До: $exp"

        lifecycleScope.launch {
            PresetRepository.ensureBuiltInPresetsReady(this@MainActivity)
        }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)

        setupPresetCards()
        setupSettingsSpoiler()
        setupDelayControls()
        setupButtons()
        refreshShizukuStatusUi()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(HotSwapService.ACTION_HOTSWAP_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(hotSwapStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(hotSwapStatusReceiver, filter)
        }
        refreshShizukuStatusUi()
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(hotSwapStatusReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        super.onDestroy()
    }

    private fun refreshShizukuStatusUi() {
        when (ShizukuHelper.checkStatus()) {
            ShizukuState.ACTIVE_GRANTED -> {
                binding.tvShizukuStatus.text = "🟢 Shizuku подключен и готов"
                binding.tvShizukuStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                binding.btnRequestShizuku.isEnabled = false
                binding.btnRequestShizuku.text = "Shizuku активен"
            }
            ShizukuState.RUNNING_NO_PERMISSION -> {
                binding.tvShizukuStatus.text = "🟡 Нажми «Разрешить Shizuku»"
                binding.tvShizukuStatus.setTextColor(ContextCompat.getColor(this, R.color.status_yellow))
                binding.btnRequestShizuku.isEnabled = true
                binding.btnRequestShizuku.text = "Разрешить Shizuku"
            }
            ShizukuState.NOT_RUNNING -> {
                binding.tvShizukuStatus.text = "🔴 Включи приложение Shizuku"
                binding.tvShizukuStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                binding.btnRequestShizuku.isEnabled = true
                binding.btnRequestShizuku.text = "Подключить Shizuku"
            }
        }
    }

    private fun setupPresetCards() {
        selectPresetCard(sessionManager.getSelectedPresetId())

        binding.cardPresetFps.setOnClickListener {
            selectPresetCard("fps_boost")
        }
        binding.cardPresetGhetto.setOnClickListener {
            selectPresetCard("pvp_pack")
        }
        binding.cardPresetZip.setOnClickListener {
            selectPresetCard("custom_zip")
        }
    }

    private fun selectPresetCard(presetId: String) {
        sessionManager.setSelectedPresetId(presetId)

        val selectedBg = ContextCompat.getColor(this, R.color.surface_card_selected)
        val normalBg = ContextCompat.getColor(this, R.color.surface_card)
        val neonStroke = ContextCompat.getColor(this, R.color.accent_neon)
        val normalStroke = ContextCompat.getColor(this, R.color.surface_stroke)

        val d = resources.displayMetrics.density
        val strokeSelected = (2 * d).toInt()
        val strokeNormal = (1 * d).toInt()

        val isFps = presetId == "fps_boost"
        binding.cardPresetFps.setCardBackgroundColor(if (isFps) selectedBg else normalBg)
        binding.cardPresetFps.strokeColor = if (isFps) neonStroke else normalStroke
        binding.cardPresetFps.strokeWidth = if (isFps) strokeSelected else strokeNormal
        binding.tvBadgeFps.visibility = if (isFps) View.VISIBLE else View.GONE

        val isGhetto = presetId == "pvp_pack"
        binding.cardPresetGhetto.setCardBackgroundColor(if (isGhetto) selectedBg else normalBg)
        binding.cardPresetGhetto.strokeColor = if (isGhetto) neonStroke else normalStroke
        binding.cardPresetGhetto.strokeWidth = if (isGhetto) strokeSelected else strokeNormal
        binding.tvBadgeGhetto.visibility = if (isGhetto) View.VISIBLE else View.GONE

        val isZip = presetId == "custom_zip"
        binding.cardPresetZip.setCardBackgroundColor(if (isZip) selectedBg else normalBg)
        binding.cardPresetZip.strokeColor = if (isZip) neonStroke else normalStroke
        binding.cardPresetZip.strokeWidth = if (isZip) strokeSelected else strokeNormal
        binding.tvBadgeZip.visibility = if (isZip) View.VISIBLE else View.GONE
    }

    private fun setupSettingsSpoiler() {
        binding.layoutSettingsHeader.setOnClickListener {
            val isVisible = binding.layoutSettingsContent.visibility == View.VISIBLE
            if (isVisible) {
                binding.layoutSettingsContent.visibility = View.GONE
                binding.tvSettingsArrow.text = "Показать ▼"
            } else {
                binding.layoutSettingsContent.visibility = View.VISIBLE
                binding.tvSettingsArrow.text = "Скрыть ▲"
            }
        }
    }

    private fun setupDelayControls() {
        val savedDelay = sessionManager.getSwapDelaySeconds()
        binding.seekBarDelay.progress = savedDelay
        binding.tvDelayValue.text = "$savedDelay сек"
        binding.etDelaySeconds.setText(savedDelay.toString())

        binding.seekBarDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceAtLeast(5)
                binding.tvDelayValue.text = "$clamped сек"
                if (fromUser) {
                    binding.etDelaySeconds.setText(clamped.toString())
                    sessionManager.setSwapDelaySeconds(clamped)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.etDelaySeconds.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val parsed = s?.toString()?.toIntOrNull() ?: return
                val clamped = parsed.coerceIn(5, 60)
                sessionManager.setSwapDelaySeconds(clamped)
                binding.tvDelayValue.text = "$clamped сек"
                if (binding.seekBarDelay.progress != clamped && clamped <= binding.seekBarDelay.max) {
                    binding.seekBarDelay.progress = clamped
                }
            }
        })
    }

    private fun setupButtons() {
        binding.btnLogout.setOnClickListener {
            sessionManager.clearSession()
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
        }

        binding.btnRequestShizuku.setOnClickListener {
            val ok = ShizukuHelper.requestPermissionIfNeeded()
            if (!ok) {
                Toast.makeText(
                    this,
                    "Сначала запусти приложение Shizuku!",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.btnRefreshShizuku.setOnClickListener {
            refreshShizukuStatusUi()
        }

        binding.btnImportZip.setOnClickListener {
            zipPickerLauncher.launch("application/zip")
        }

        binding.btnLaunchHotSwap.setOnClickListener {
            startGameAndModsParallel()
        }
    }

    private fun findGrandMobileLaunchIntent(): Intent? {
        for (pkg in gamePackages) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) return intent
        }
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = packageManager.queryIntentActivities(mainIntent, 0)
        for (info in resolved) {
            val pkgName = info.activityInfo?.packageName?.lowercase().orEmpty()
            val label = info.loadLabel(packageManager)?.toString()?.lowercase().orEmpty()
            if ((pkgName.contains("grand") && pkgName != packageName.lowercase()) ||
                label.contains("grand mobile")
            ) {
                return packageManager.getLaunchIntentForPackage(info.activityInfo.packageName)
            }
        }
        return null
    }

    private fun startGameAndModsParallel() {
        val delaySeconds = sessionManager.getSwapDelaySeconds()
        val presetId = sessionManager.getSelectedPresetId()

        if (ShizukuHelper.checkStatus() == ShizukuState.ACTIVE_GRANTED) {
            val serviceIntent = Intent(this, HotSwapService::class.java).apply {
                putExtra(HotSwapService.EXTRA_DELAY_SECONDS, delaySeconds)
                putExtra(HotSwapService.EXTRA_PRESET_ID, presetId)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            ShizukuHelper.requestPermissionIfNeeded()
        }

        val launchIntent = findGrandMobileLaunchIntent()
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(launchIntent)
            binding.tvHotSwapLog.text = "🚀 Grand Mobile запущена! Моды включатся через $delaySeconds сек..."
        } else {
            lifecycleScope.launch {
                val launchedByShizuku = ShizukuHelper.launchGamePackage(gamePackages)
                if (launchedByShizuku) {
                    binding.tvHotSwapLog.text = "🚀 Игра запущена! Моды включатся через $delaySeconds сек..."
                } else {
                    binding.tvHotSwapLog.text = "⏳ Моды включатся через $delaySeconds сек! Открой Grand Mobile."
                    Toast.makeText(
                        this@MainActivity,
                        "Моды активируются через $delaySeconds сек! Открой Grand Mobile.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
