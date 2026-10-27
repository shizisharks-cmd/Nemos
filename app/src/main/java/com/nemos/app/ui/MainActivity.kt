package com.nemos.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nemos.app.R
import com.nemos.app.data.SessionManager
import com.nemos.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionManager: SessionManager
    private var customZipUri: Uri? = null
    private var isShizukuMode = true

    private val pickZipLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            customZipUri = uri
            binding.tvCustomZipName.text = "Выбран пользовательский ZIP"
            selectPreset("custom")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvSubInfo.text = "Ключ: ${sessionManager.getSavedKey()} • До: ${sessionManager.getExpiresAt()}"

        setInstallMode(true)

        binding.btnTabShizuku.setOnClickListener { setInstallMode(true) }
        binding.btnTabMt.setOnClickListener { setInstallMode(false) }

        val initialPreset = sessionManager.getSelectedPresetId().ifBlank { "molodoy" }
        selectPreset(if (initialPreset == "fps_boost") "molodoy" else initialPreset)

        binding.cardMolodoy.setOnClickListener { selectPreset("molodoy") }
        binding.cardFps.setOnClickListener { selectPreset("fps_boost") }
        binding.cardGhetto.setOnClickListener { selectPreset("ghetto_pack") }
        binding.cardCustom.setOnClickListener { selectPreset("custom") }

        binding.btnPickZip.setOnClickListener {
            pickZipLauncher.launch("application/zip")
        }

        binding.btnLogout.setOnClickListener {
            sessionManager.clearSession()
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
        }

        binding.btnPrimaryAction.setOnClickListener {
            if (isShizukuMode) {
                handleShizukuInstallAndLaunch()
            } else {
                handleMtUnpack()
            }
        }

        binding.btnOpenMt.setOnClickListener {
            openMtManager()
        }

        binding.btnLaunchGameDirect.setOnClickListener {
            launchGrandMobile()
        }
    }

    private fun setInstallMode(shizuku: Boolean) {
        isShizukuMode = shizuku
        if (shizuku) {
            binding.btnTabShizuku.setBackgroundResource(R.drawable.bg_btn_primary)
            binding.btnTabShizuku.setTextColor(0xFFFFFFFF.toInt())
            binding.btnTabMt.setBackgroundColor(0)
            binding.btnTabMt.setTextColor(0xFFA1A1AA.toInt())
            binding.btnPrimaryAction.text = "🚀 ИГРАТЬ С МОДАМИ (SHIZUKU)"
            binding.layoutMtButtons.visibility = View.GONE
            binding.tvStatus.text = "Режим: Автоматическая установка через Shizuku"
        } else {
            binding.btnTabMt.setBackgroundResource(R.drawable.bg_btn_primary)
            binding.btnTabMt.setTextColor(0xFFFFFFFF.toInt())
            binding.btnTabShizuku.setBackgroundColor(0)
            binding.btnTabShizuku.setTextColor(0xFFA1A1AA.toInt())
            binding.btnPrimaryAction.text = "📦 РАСПАКОВАТЬ ДЛЯ MT MANAGER"
            binding.layoutMtButtons.visibility = View.VISIBLE
            binding.tvStatus.text = "Режим: Вставка через MT Manager"
        }
    }

    private fun selectPreset(presetId: String) {
        sessionManager.setSelectedPresetId(presetId)

        binding.cardMolodoy.setBackgroundResource(if (presetId == "molodoy") R.drawable.bg_card_selected else R.drawable.bg_card_unselected)
        binding.tvBadgeMolodoy.visibility = if (presetId == "molodoy") View.VISIBLE else View.GONE

        binding.cardFps.setBackgroundResource(if (presetId == "fps_boost") R.drawable.bg_card_selected else R.drawable.bg_card_unselected)
        binding.tvBadgeFps.visibility = if (presetId == "fps_boost") View.VISIBLE else View.GONE

        binding.cardGhetto.setBackgroundResource(if (presetId == "ghetto_pack") R.drawable.bg_card_selected else R.drawable.bg_card_unselected)
        binding.tvBadgeGhetto.visibility = if (presetId == "ghetto_pack") View.VISIBLE else View.GONE

        binding.cardCustom.setBackgroundResource(if (presetId == "custom") R.drawable.bg_card_selected else R.drawable.bg_card_unselected)
        binding.tvBadgeCustom.visibility = if (presetId == "custom") View.VISIBLE else View.GONE
    }

    private fun handleShizukuInstallAndLaunch() {
        var isAlive = false
        try {
            isAlive = Shizuku.pingBinder()
        } catch (_: Throwable) {}

        if (!isAlive) {
            AlertDialog.Builder(this)
                .setTitle("Shizuku не запущен")
                .setMessage("Сервис Shizuku не активен.\n\nВы можете запустить Shizuku через Беспроводную отладку или переключиться сверху на вкладку «MT Manager» без Shizuku.")
                .setPositiveButton("Понятно", null)
                .setNegativeButton("Переключить на MT") { _, _ -> setInstallMode(false) }
                .show()
            return
        }

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(1001)
            Toast.makeText(this, "Подтвердите разрешение для NEMOS APP в окне Shizuku", Toast.LENGTH_LONG).show()
            return
        }

        binding.btnPrimaryAction.isEnabled = false
        binding.tvStatus.text = "⏳ Shizuku: заменяем файлы сборки..."

        lifecycleScope.launch {
            val preset = sessionManager.getSelectedPresetId()
            val pkg = getGrandPackage()
            val success = withContext(Dispatchers.IO) {
                applyModsViaShizuku(preset, pkg)
            }

            if (success) {
                binding.tvStatus.text = "✅ Сборка установлена! Запуск игры..."
                launchGrandMobile()
            } else {
                binding.tvStatus.text = "❌ Ошибка копирования через Shizuku"
            }
            binding.btnPrimaryAction.isEnabled = true
        }
    }

    private fun applyModsViaShizuku(presetId: String, pkg: String): Boolean {
        try {
            val stagingDir = File(cacheDir, "shizuku_staging/files").apply {
                deleteRecursively()
                mkdirs()
            }
            openModStream(presetId)?.use { input ->
                extractZipToDir(input, stagingDir)
            }

            val gameFilesPath = "/storage/emulated/0/Android/data/$pkg/files"
            val cmd = "mkdir -p '$gameFilesPath' && cp -rf '${stagingDir.absolutePath}/.' '$gameFilesPath/'"
            val proc = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            proc.waitFor()
            return proc.exitValue() == 0
        } catch (_: Exception) {
            return false
        }
    }

    private fun handleMtUnpack() {
        binding.btnPrimaryAction.isEnabled = false
        binding.tvStatus.text = "⏳ Распаковываем файлы сборки в загрузки..."

        lifecycleScope.launch {
            val preset = sessionManager.getSelectedPresetId()
            val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Nemos_Mods/Molodoy/files")
            val count = withContext(Dispatchers.IO) {
                targetDir.mkdirs()
                openModStream(preset)?.use { input ->
                    extractZipToDir(input, targetDir)
                } ?: 0
            }

            binding.btnPrimaryAction.isEnabled = true
            binding.tvStatus.text = "✅ Распаковано $count файлов в Download/Nemos_Mods/"

            AlertDialog.Builder(this@MainActivity)
                .setTitle("✅ Сборка распакована!")
                .setMessage("Файлы готовы в папке:\nDownload/Nemos_Mods/Molodoy/files\n\nИнструкция для MT Manager:\n1. Нажмите «Открыть MT Manager» ниже.\n2. Слева откройте: Download/Nemos_Mods/Molodoy/\n3. Справа откройте: Android/data/com.grandmobile.game/\n4. Зажмите папку files и скопируйте с заменой.\n5. Вернитесь в лаунчер и нажмите «Запустить Grand Mobile».")
                .setPositiveButton("Открыть MT Manager") { _, _ -> openMtManager() }
                .setNegativeButton("Понятно", null)
                .show()
        }
    }

    private fun openMtManager() {
        val mtPackages = listOf("bin.mt.plus", "bin.mt.plus.canary", "bin.mt.mmp")
        for (pkg in mtPackages) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                startActivity(intent)
                return
            }
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path), "resource/folder")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "MT Manager не найден, откройте его вручную", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractZipToDir(input: InputStream, targetDir: File): Int {
        var count = 0
        val buffer = ByteArray(8192)
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val rawName = entry.name.replace('\\', '/')
                    val relPath = when {
                        rawName.contains("files/") -> rawName.substringAfter("files/")
                        rawName.startsWith("Molodoy/") -> rawName.removePrefix("Molodoy/")
                        else -> rawName
                    }.trimStart('/')

                    if (relPath.isNotEmpty()) {
                        val outFile = File(targetDir, relPath)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                        }
                        count++
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return count
    }

    private fun openModStream(presetId: String): InputStream? {
        if (presetId == "custom" && customZipUri != null) {
            try {
                return contentResolver.openInputStream(customZipUri!!)
            } catch (_: Exception) {}
        }
        try {
            return assets.open("molodoy.zip")
        } catch (_: Exception) {}
        val candidates = listOf(
            File("/sdcard/Download/molodoy_pack.zip"),
            File("/sdcard/Download/sb dlya Molodoy (1) - Копия - Копия.zip"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "molodoy_pack.zip")
        )
        for (f in candidates) {
            if (f.exists() && f.canRead()) {
                return FileInputStream(f)
            }
        }
        return null
    }

    private fun getGrandPackage(): String {
        val known = listOf("com.grandmobile.game", "ru.grandmobile.game", "com.grand.mobile", "com.weiz.grandmobile")
        for (k in known) {
            try {
                packageManager.getPackageInfo(k, 0)
                return k
            } catch (_: Exception) {}
        }
        return "com.grandmobile.game"
    }

    private fun launchGrandMobile(): Boolean {
        val pkg = getGrandPackage()
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return true
        }
        Toast.makeText(this, "Grand Mobile не найден на телефоне", Toast.LENGTH_SHORT).show()
        return false
    }
}
