package com.nemos.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import android.view.View
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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionManager: SessionManager
    private var customZipUri: Uri? = null

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

        binding.btnUnpack.setOnClickListener {
            checkStoragePermissionAndUnpack()
        }

        binding.btnOpenMt.setOnClickListener {
            openMtManager()
        }

        binding.btnLaunchGame.setOnClickListener {
            launchGrandMobile()
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

    private fun checkStoragePermissionAndUnpack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("Разрешение на файлы")
                    .setMessage("Для распаковки сборки в папку Загрузки необходимо предоставить доступ к файлам.")
                    .setPositiveButton("Включить") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
                return
            }
        }
        startUnpackingProcess()
    }

    private fun startUnpackingProcess() {
        binding.btnUnpack.isEnabled = false
        binding.tvStatus.text = "⏳ Распаковываем файлы сборки..."

        lifecycleScope.launch {
            val preset = sessionManager.getSelectedPresetId()
            val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Nemos_Mods/files")

            val count = withContext(Dispatchers.IO) {
                try {
                    targetDir.deleteRecursively()
                    targetDir.mkdirs()
                    openModStream(preset)?.use { input ->
                        extractZipToDir(input, targetDir)
                    } ?: 0
                } catch (_: Exception) {
                    -1
                }
            }

            binding.btnUnpack.isEnabled = true

            if (count > 0) {
                binding.tvStatus.text = "✅ Успешно распаковано $count файлов!"
                showInstructionDialog()
            } else {
                binding.tvStatus.text = "❌ Ошибка распаковки архива"
                Toast.makeText(this@MainActivity, "Не удалось извлечь файлы", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showInstructionDialog() {
        AlertDialog.Builder(this)
            .setTitle("✅ Сборка готова в Загрузках!")
            .setMessage("Папка files создана:\n📁 Download/Nemos_Mods/files\n\nКак вставить за 15 секунд:\n1. Нажмите «Открыть MT Manager» ниже.\n2. Слева откройте: Download/Nemos_Mods/\n3. Справа откройте: Android/data/com.grandmobile.game/\n4. Зажмите папку files слева и нажмите «Копировать» с заменой.\n5. Нажмите «В ИГРУ».")
            .setPositiveButton("Открыть MT Manager") { _, _ -> openMtManager() }
            .setNegativeButton("Понятно", null)
            .show()
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
            Toast.makeText(this, "MT Manager не установлен, откройте папку Загрузки вручную", Toast.LENGTH_SHORT).show()
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

    private fun launchGrandMobile() {
        val known = listOf("com.grandmobile.game", "ru.grandmobile.game", "com.grand.mobile", "com.weiz.grandmobile")
        for (pkg in known) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            }
        }
        Toast.makeText(this, "Grand Mobile не найден на телефоне", Toast.LENGTH_SHORT).show()
    }
}
