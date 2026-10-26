package com.nemos.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nemos.app.R
import com.nemos.app.data.SessionManager
import com.nemos.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
            binding.tvCustomZipName.text = "Выбран пользовательский .ZIP архив"
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
        binding.etSwapDelay.setText(sessionManager.getSwapDelaySeconds().toString())

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

        binding.btnToggleSettings.setOnClickListener {
            val isVisible = binding.layoutSettingsContent.visibility == View.VISIBLE
            binding.layoutSettingsContent.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.tvSettingsToggle.text = if (isVisible) "Показать ▼" else "Скрыть ▲"
        }

        binding.btnSaveSettings.setOnClickListener {
            val sec = binding.etSwapDelay.text?.toString()?.toIntOrNull() ?: 17
            sessionManager.setSwapDelaySeconds(sec.coerceIn(1, 120))
            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
        }

        binding.btnPlay.setOnClickListener {
            val preset = sessionManager.getSelectedPresetId()
            val delaySec = sessionManager.getSwapDelaySeconds()
            applyModsAndLaunch(preset, delaySec)
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

        val title = when (presetId) {
            "molodoy" -> "Сборка Molodoy"
            "fps_boost" -> "FPS Boost"
            "ghetto_pack" -> "Ghetto Pack"
            else -> "Своя сборка (.ZIP)"
        }
        binding.tvStatus.text = "Выбрано: $title. Нажми «Играть с модами»"
    }

    private fun applyModsAndLaunch(presetId: String, delaySec: Int) {
        binding.btnPlay.isEnabled = false
        binding.tvStatus.text = "⏳ Установка сборки и запуск Grand Mobile..."

        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                installPresetFiles(presetId)
            }

            val launched = launchGrandMobile()
            if (launched) {
                binding.tvStatus.text = "✅ Сборка активна (файлов: $count). Приятной игры!"
                lifecycleScope.launch(Dispatchers.IO) {
                    delay(delaySec * 1000L)
                    installPresetFiles(presetId)
                }
            } else {
                binding.tvStatus.text = "⚠️ Сборка подготовлена (файлов: $count), но Grand Mobile не найден"
                Toast.makeText(this@MainActivity, "Установи Grand Mobile на телефон", Toast.LENGTH_LONG).show()
            }
            binding.btnPlay.isEnabled = true
        }
    }

    private fun installPresetFiles(presetId: String): Int {
        val targets = getTargetFilesDirs()
        var extractedCount = 0

        try {
            val stream: InputStream? = when (presetId) {
                "molodoy" -> openMolodoyStream()
                "custom" -> customZipUri?.let { contentResolver.openInputStream(it) } ?: openMolodoyStream()
                else -> openMolodoyStream()
            }

            if (stream != null) {
                val stagingDir = File(getExternalFilesDir(null), "active_mods/files").apply { mkdirs() }
                extractedCount = extractZipToDir(stream, stagingDir)
                for (target in targets) {
                    try {
                        stagingDir.copyRecursively(target, overwrite = true)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        return extractedCount
    }

    private fun openMolodoyStream(): InputStream? {
        try {
            return assets.open("molodoy.zip")
        } catch (_: Exception) {}

        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val candidates = mutableListOf(
            File(downloadDir, "molodoy_pack.zip"),
            File(downloadDir, "sb dlya Molodoy (1) - Копия - Копия.zip"),
            File("/sdcard/Download/molodoy_pack.zip"),
            File("/sdcard/Download/sb dlya Molodoy (1) - Копия - Копия.zip")
        )

        downloadDir?.listFiles()?.firstOrNull {
            it.isFile && it.name.contains("molodoy", ignoreCase = true) && it.name.endsWith(".zip", ignoreCase = true)
        }?.let { candidates.add(0, it) }

        for (file in candidates) {
            if (file.exists() && file.canRead()) {
                return FileInputStream(file)
            }
        }
        return null
    }

    private fun extractZipToDir(input: InputStream, destRoot: File): Int {
        var filesExtracted = 0
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(8192)
            while (entry != null) {
                if (!entry.isDirectory) {
                    val rawName = entry.name.replace('\\', '/')
                    val relPath = when {
                        rawName.contains("files/") -> rawName.substringAfter("files/")
                        rawName.startsWith("Molodoy/") -> rawName.removePrefix("Molodoy/")
                        else -> rawName
                    }.trimStart('/')

                    if (relPath.isNotEmpty()) {
                        val outFile = File(destRoot, relPath)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                        }
                        filesExtracted++
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return filesExtracted
    }

    private fun getTargetFilesDirs(): List<File> {
        val packages = listOf(
            "com.grandmobile.game",
            "ru.grandmobile.game",
            "com.grand.mobile",
            "com.weiz.grandmobile"
        )
        val dirs = mutableListOf<File>()
        val extRoot = Environment.getExternalStorageDirectory()
        for (pkg in packages) {
            dirs.add(File(extRoot, "Android/data/$pkg/files"))
        }
        return dirs
    }

    private fun launchGrandMobile(): Boolean {
        val knownPackages = listOf(
            "com.grandmobile.game",
            "ru.grandmobile.game",
            "com.grand.mobile",
            "com.weiz.grandmobile"
        )

        for (pkg in knownPackages) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return true
            }
        }

        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = packageManager.queryIntentActivities(mainIntent, 0)
        for (info in apps) {
            val pkg = info.activityInfo.packageName
            val label = info.loadLabel(packageManager)?.toString().orEmpty()
            if (pkg.contains("grand", ignoreCase = true) || label.contains("grand", ignoreCase = true)) {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    return true
                }
            }
        }
        return false
    }
}
