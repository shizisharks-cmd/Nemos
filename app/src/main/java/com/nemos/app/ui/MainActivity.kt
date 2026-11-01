package com.nemos.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
import java.lang.reflect.Method
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionManager: SessionManager
    private var customZipUri: Uri? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { updateUiMode() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { updateUiMode() }
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { updateUiMode() }
    }

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

        binding.btnPrimaryAction.setOnClickListener {
            if (isShizukuAvailable()) {
                handleShizukuInstall()
            } else {
                handleMtUnpack()
            }
        }

        binding.btnOpenMt.setOnClickListener {
            openMtManager()
        }

        binding.btnLaunchGame.setOnClickListener {
            launchGrandMobile()
        }

        binding.tvShizukuBadge.setOnClickListener {
            if (!isShizukuAvailable()) {
                AlertDialog.Builder(this)
                    .setTitle("⚡ Как включить Турбо-режим?")
                    .setMessage("В Турбо-режиме моды устанавливаются в 1 клик без MT Manager.\n\nДля включения:\n1. Подключите телефон к Wi-Fi.\n2. Откройте приложение Shizuku.\n3. Нажмите «Запустить» через отладку по Wi-Fi.\n\nЕсли Wi-Fi нет — пользуйтесь режимом MT Manager.")
                    .setPositiveButton("Понятно", null)
                    .show()
            }
        }

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (_: Throwable) {}

        updateUiMode()
    }

    override fun onResume() {
        super.onResume()
        updateUiMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Throwable) {}
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasShizukuPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private fun updateUiMode() {
        val alive = isShizukuAvailable()
        if (alive) {
            val perm = hasShizukuPermission()
            binding.tvShizukuBadge.text = if (perm) "🟢 1-клик (Турбо активен)" else "🟡 1-клик (Требуется разрешение)"
            binding.tvShizukuBadge.setTextColor(if (perm) 0xFF2DD4BF.toInt() else 0xFFF59E0B.toInt())
            binding.btnPrimaryAction.text = "🚀 УСТАНОВИТЬ И ИГРАТЬ (1 КЛИК)"
            binding.layoutMtOptions.visibility = View.GONE
            binding.tvStatus.text = "Турбо-режим: установка модов в 1 клик через Shizuku"
        } else {
            binding.tvShizukuBadge.text = "⚪ Режим MT Manager (без Wi-Fi)"
            binding.tvShizukuBadge.setTextColor(0xFFA1A1AA.toInt())
            binding.btnPrimaryAction.text = "📦 РАСПАКОВАТЬ ДЛЯ MT MANAGER"
            binding.layoutMtOptions.visibility = View.VISIBLE
            binding.tvStatus.text = "Режим без Wi-Fi: распаковка в Загрузки для вставки в игре"
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

    private fun handleShizukuInstall() {
        if (!hasShizukuPermission()) {
            try {
                Shizuku.requestPermission(1001)
                Toast.makeText(this, "Подтвердите доступ в окне Shizuku", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
                Toast.makeText(this, "Не удалось запросить доступ Shizuku", Toast.LENGTH_SHORT).show()
            }
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

            binding.btnPrimaryAction.isEnabled = true
            if (success) {
                binding.tvStatus.text = "✅ Сборка установлена! Запуск игры..."
                launchGrandMobile()
            } else {
                binding.tvStatus.text = "❌ Ошибка копирования через Shizuku"
                Toast.makeText(this@MainActivity, "Ошибка копирования файлов через Shizuku", Toast.LENGTH_SHORT).show()
            }
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

            val method: Method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val proc = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            proc.waitFor()
            return proc.exitValue() == 0
        } catch (_: Exception) {
            return false
        }
    }

    private fun handleMtUnpack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("Доступ к памяти")
                    .setMessage("Для распаковки файлов в папку Загрузки необходимо предоставить доступ.")
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

        binding.btnPrimaryAction.isEnabled = false
        binding.tvStatus.text = "⏳ Распаковываем файлы сборки в Загрузки..."

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

            binding.btnPrimaryAction.isEnabled = true

            if (count > 0) {
                binding.tvStatus.text = "✅ Распаковано $count файлов в Download/Nemos_Mods/"
                showMtGuideDialog()
            } else {
                binding.tvStatus.text = "❌ Ошибка распаковки архива"
                Toast.makeText(this@MainActivity, "Не удалось извлечь файлы", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showMtGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("✅ Сборка готова в Загрузках!")
            .setMessage("Папка с модами создана:\n📁 Download/Nemos_Mods/files\n\nИнструкция:\n1. Нажмите «Открыть MT Manager» ниже.\n2. Слева откройте: Download/Nemos_Mods/\n3. Справа откройте: Android/data/com.grandmobile.game/\n4. Зажмите папку files слева и нажмите «Копировать» (с заменой).\n5. Нажмите «В игру».")
            .setPositiveButton("Открыть MT Manager") { _, _ -> openMtManager() }
            .setNeutralButton("🎮 В игру") { _, _ -> launchGrandMobile() }
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
            Toast.makeText(this, "MT Manager не установлен", Toast.LENGTH_SHORT).show()
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
        for (pkg in known) {
            try {
                packageManager.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        return "com.grandmobile.game"
    }

    private fun launchGrandMobile() {
        val pkg = getGrandPackage()
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return
        }
        Toast.makeText(this, "Grand Mobile не найден на телефоне", Toast.LENGTH_SHORT).show()
    }
}
