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
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nemos.app.BuildConfig
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

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuHelper.SHIZUKU_PERMISSION_REQUEST_CODE) {
                refreshShizukuStatusUi()
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "✅ Доступ Shizuku предоставлен!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "❌ В разрешении Shizuku отказано.", Toast.LENGTH_SHORT).show()
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
                binding.tvPresetPathInfo.text = "⏳ Распаковка ZIP-архива сборки..."
                lifecycleScope.launch {
                    val result = PresetRepository.importZipPreset(this@MainActivity, uri)
                    result.onSuccess { count ->
                        binding.rbPresetCustomZip.isChecked = true
                        sessionManager.setSelectedPresetId("custom_zip")
                        binding.tvPresetPathInfo.text =
                            "✅ Импортировано файлов: $count (папки data/texdb/audio готовы)"
                        Toast.makeText(
                            this@MainActivity,
                            "Сборка успешно импортирована ($count файлов)",
                            Toast.LENGTH_LONG
                        ).show()
                    }.onFailure { err ->
                        binding.tvPresetPathInfo.text = "❌ Ошибка импорта: ${err.localizedMessage}"
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

        // Регистрируем слушатели Shizuku
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)

        setupPresetsSelection()
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
                binding.tvShizukuStatus.text = "🟢 Активен (Доступ к Android/data предоставлен)"
                binding.tvShizukuStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                binding.btnRequestShizuku.isEnabled = false
                binding.btnRequestShizuku.text = "Shizuku подключен"
            }
            ShizukuState.RUNNING_NO_PERMISSION -> {
                binding.tvShizukuStatus.text = "🟡 Служба запущена (Требуется разрешение)"
                binding.tvShizukuStatus.setTextColor(ContextCompat.getColor(this, R.color.status_yellow))
                binding.btnRequestShizuku.isEnabled = true
                binding.btnRequestShizuku.text = "Разрешить доступ Shizuku"
            }
            ShizukuState.NOT_RUNNING -> {
                binding.tvShizukuStatus.text = "🔴 Не запущен (Запустите Shizuku перед игрой)"
                binding.tvShizukuStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                binding.btnRequestShizuku.isEnabled = true
                binding.btnRequestShizuku.text = "Запросить доступ Shizuku"
            }
        }
    }

    private fun setupPresetsSelection() {
        when (sessionManager.getSelectedPresetId()) {
            "fps_boost" -> binding.rbPresetFpsBoost.isChecked = true
            "pvp_pack" -> binding.rbPresetPvpPack.isChecked = true
            "custom_zip" -> binding.rbPresetCustomZip.isChecked = true
        }

        binding.rgPresets.setOnCheckedChangeListener { _, checkedId ->
            val presetId = when (checkedId) {
                R.id.rbPresetFpsBoost -> "fps_boost"
                R.id.rbPresetPvpPack -> "pvp_pack"
                R.id.rbPresetCustomZip -> "custom_zip"
                else -> "fps_boost"
            }
            sessionManager.setSelectedPresetId(presetId)
            val dir = PresetRepository.getPresetDirectory(this, presetId)
            binding.tvPresetPathInfo.text = "Директория сборки: ${dir.name} (data, texdb, audio)"
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
                    "Сначала запустите службу Shizuku на устройстве!",
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
            startGameWithHotSwap()
        }
    }

    private fun startGameWithHotSwap() {
        if (ShizukuHelper.checkStatus() != ShizukuState.ACTIVE_GRANTED) {
            Toast.makeText(
                this,
                "⚠️ Для горячей подмены необходимо активировать Shizuku!",
                Toast.LENGTH_LONG
            ).show()
            ShizukuHelper.requestPermissionIfNeeded()
            return
        }

        val delaySeconds = sessionManager.getSwapDelaySeconds()
        val presetId = sessionManager.getSelectedPresetId()

        // 1. Запускаем фоновую службу отсчета таймера и подмены файлов через Shizuku
        val serviceIntent = Intent(this, HotSwapService::class.java).apply {
            putExtra(HotSwapService.EXTRA_DELAY_SECONDS, delaySeconds)
            putExtra(HotSwapService.EXTRA_PRESET_ID, presetId)
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        // 2. Запускаем игру com.grandmobile.game через Intent
        val targetPackage = BuildConfig.TARGET_GAME_PACKAGE
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            binding.tvHotSwapLog.text =
                "🚀 Игра $targetPackage запущена! Подмена через $delaySeconds сек..."
        } else {
            binding.tvHotSwapLog.text =
                "⏳ Таймер ($delaySeconds сек) запущен! (Пакет $targetPackage не установлен, подмена выполнится по таймеру)"
            Toast.makeText(
                this,
                "Таймер Hot-Swap ($delaySeconds сек) запущен! Откройте Grand Mobile.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
