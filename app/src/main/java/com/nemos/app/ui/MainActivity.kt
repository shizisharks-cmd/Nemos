package com.nemos.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
    private var isShizukuMode = true

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { updateShizukuUi() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { updateShizukuUi() }
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { updateShizukuUi() }
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

        selectPreset(sessionManager.getSelectedPresetId().ifBlank { "karma" })

        binding.cardKarma.setOnClickListener { selectPreset("karma") }
        binding.cardMolodoy.setOnClickListener { selectPreset("molodoy") }
        binding.cardFps.setOnClickListener { selectPreset("fps_boost") }
        binding.cardCustom.setOnClickListener { selectPreset("custom") }

        binding.btnPickZip.setOnClickListener {
            pickZipLauncher.launch("application/zip")
        }

        binding.btnBuySub.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Nemos_Trade_Bot")))
            } catch (_: Exception) {}
        }

        binding.btnLogout.setOnClickListener {
            sessionManager.clearSession()
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
        }

        binding.btnTabShizuku.setOnClickListener { setMode(true) }
        binding.btnTabMt.setOnClickListener { setMode(false) }

        // Главная кнопка ПОЛНОГО АВТОМАТА
        binding.btnAutoStart.setOnClickListener {
            startFullAutoFlow()
        }

        // Вкладка MT Manager
        binding.btnMtPrepare.setOnClickListener {
            prepareFilesForMt()
        }
        binding.btnMtOpen.setOnClickListener {
            openMtManager()
        }

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (_: Throwable) {}

        setMode(true)
        updateShizukuUi()
    }

    override fun onResume() {
        super.onResume()
        updateShizukuUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Throwable) {}
    }

    private fun setMode(shizuku: Boolean) {
        isShizukuMode = shizuku
        if (shizuku) {
            binding.btnTabShizuku.setBackgroundResource(R.drawable.bg_card_selected)
            binding.btnTabShizuku.setTextColor(0xFFFFFFFF.toInt())
            binding.btnTabMt.setBackgroundResource(android.R.color.transparent)
            binding.btnTabMt.setTextColor(0xFFA1A1AA.toInt())
            binding.layoutShizukuView.visibility = View.VISIBLE
            binding.layoutMtView.visibility = View.GONE
            updateShizukuUi()
        } else {
            binding.btnTabMt.setBackgroundResource(R.drawable.bg_card_selected)
            binding.btnTabMt.setTextColor(0xFFFFFFFF.toInt())
            binding.btnTabShizuku.setBackgroundResource(android.R.color.transparent)
            binding.btnTabShizuku.setTextColor(0xFFA1A1AA.toInt())
            binding.layoutShizukuView.visibility = View.GONE
            binding.layoutMtView.visibility = View.VISIBLE
            binding.tvStatus.text = "Распакуйте файлы и скопируйте через MT Manager"
        }
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

    private fun updateShizukuUi() {
        if (!isShizukuMode) return

        if (isShizukuAvailable()) {
            if (hasShizukuPermission()) {
                binding.tvShizukuStatus.text = "🟢 Shizuku активен: всё готово к авто-входу"
                binding.tvShizukuStatus.setTextColor(0xFF2DD4BF.toInt())
            } else {
                binding.tvShizukuStatus.text = "🟡 Shizuku запущен: нажмите для выдачи доступа"
                binding.tvShizukuStatus.setTextColor(0xFFF59E0B.toInt())
                try { Shizuku.requestPermission(1001) } catch (_: Throwable) {}
            }
        } else {
            binding.tvShizukuStatus.text = "🔴 Shizuku не запущен (проверьте службу в приложении)"
            binding.tvShizukuStatus.setTextColor(0xFFEF4444.toInt())
        }
    }

    private fun selectPreset(presetId: String) {
        sessionManager.setSelectedPresetId(presetId)

        binding.cardKarma.setBackgroundResource(if (presetId == "karma") R.drawable.bg_card_selected else R.drawable.bg_card_unselected)
        binding.tvBadgeKarma.visibility = if (presetId == "karma") View.VISIBLE else View.GONE

        binding.cardMolodoy.setBackgroundResource(if (presetId == "molodoy") R.drawable.bg_card_selected else R.drawable.bg_card_unselected)
        binding.tvBadgeMolodoy.visibility = if (presetId == "molodoy") View.VISIBLE else View.GONE

        binding.cardFps.setBackgroundResource(if (presetId == "fps_boost") R.drawable.bg_card_selected else R.drawable.bg_card_unselected)
        binding.tvBadgeFps.visibility = if (presetId == "fps_boost") View.VISIBLE else View.GONE

        binding.cardCustom.setBackgroundResource(if (presetId == "custom") R.drawable.bg_card_selected else R.drawable.bg_card_unselected)
        binding.tvBadgeCustom.visibility = if (presetId == "custom") View.VISIBLE else View.GONE
    }

    private fun runShizukuShell(command: String): Pair<Int, String> {
        val method: Method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        val proc = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        val output = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        proc.waitFor()
        return Pair(proc.exitValue(), output.ifBlank { err })
    }

    private fun detectGrandPackage(): String {
        val list = listOf("com.grand.launcher", "ru.grandmobile.game", "com.grandmobile.game", "com.grand.mobile")
        for (pkg in list) {
            try {
                packageManager.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        return "com.grand.launcher"
    }

    // ПОЛНЫЙ АВТОМАТ В 1 КЛИК
    private fun startFullAutoFlow() {
        if (!isShizukuAvailable() || !hasShizukuPermission()) {
            Toast.makeText(this, "Служба Shizuku не готова!", Toast.LENGTH_SHORT).show()
            updateShizukuUi()
            return
        }

        val pkg = detectGrandPackage()
        binding.btnAutoStart.isEnabled = false
        binding.tvStatus.text = "⏳ Подготовка файлов сборки..."

        lifecycleScope.launch {
            val preset = sessionManager.getSelectedPresetId()

            // 1. Распаковываем файлы сборки во внутреннюю память
            val stagingDir = withContext(Dispatchers.IO) {
                val dir = File(getExternalFilesDir(null), "staging_auto").apply {
                    deleteRecursively()
                    mkdirs()
                }
                openModStream(preset)?.use { extractZipClean(it, dir) }
                dir
            }

            // 2. Закрываем старую игру для чистого старта
            withContext(Dispatchers.IO) {
                runShizukuShell("am force-stop $pkg 2>/dev/null; am force-stop com.grand.launcher 2>/dev/null")
            }

            // 3. Запускаем Grand Mobile
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                withContext(Dispatchers.IO) {
                    runShizukuShell("monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>/dev/null")
                }
            }

            Toast.makeText(this@MainActivity, "🚀 Игра запущена! Внедрение сборки произойдет автоматически...", Toast.LENGTH_LONG).show()
            binding.tvStatus.text = "⏳ Ожидание экрана «Подключение к игре» (авто-внедрение через 6 сек)..."

            // 4. Пауза 6.5 секунд (пока проходит проверка хэшей лаунчера и начинается подключение к серверу)
            delay(6500)

            // 5. В фоне без переключения экрана заменяем файлы через Shizuku
            val success = withContext(Dispatchers.IO) {
                val dest = "/storage/emulated/0/Android/data/$pkg/files"
                val cmd = """
                    mkdir -p "$dest/data" "$dest/texdb/mobile" "$dest/audio"
                    cp -rf "${stagingDir.absolutePath}/." "$dest/"
                    chmod 444 "$dest/data/"* "$dest/texdb/mobile/"* 2>/dev/null || true
                """.trimIndent()
                val (code, _) = runShizukuShell(cmd)
                code == 0
            }

            binding.btnAutoStart.isEnabled = true
            if (success) {
                binding.tvStatus.text = "🔥 Сборка автоматически внедрена! Играйте с модами."
                Toast.makeText(this@MainActivity, "🔥 Сборка успешно внедрена!", Toast.LENGTH_LONG).show()
            } else {
                binding.tvStatus.text = "❌ Ошибка фонового копирования"
            }
        }
    }

    private fun prepareFilesForMt() {
        binding.btnMtPrepare.isEnabled = false
        binding.tvStatus.text = "⏳ Распаковываем файлы сборки..."

        lifecycleScope.launch {
            val preset = sessionManager.getSelectedPresetId()
            val target = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Nemos_Mods/files").apply {
                deleteRecursively()
                mkdirs()
            }

            val count = withContext(Dispatchers.IO) {
                try {
                    openModStream(preset)?.use { extractZipClean(it, target) } ?: 0
                } catch (_: Exception) { 0 }
            }

            binding.btnMtPrepare.isEnabled = true
            if (count > 0) {
                val pkg = detectGrandPackage()
                val targetPath = "/storage/emulated/0/Android/data/$pkg/files/"
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("grand_path", targetPath))

                binding.tvStatus.text = "✅ Папки готовы в Download/Nemos_Mods/files! Путь скопирован."
                Toast.makeText(this@MainActivity, "✅ Готово! Путь скопирован в буфер", Toast.LENGTH_LONG).show()
            } else {
                binding.tvStatus.text = "❌ Не удалось распаковать архив"
            }
        }
    }

    private fun openMtManager() {
        val mtPackages = listOf("bin.mt.plus", "bin.mt.plus.canary", "bin.mt.mmp")
        for (p in mtPackages) {
            try {
                val i = packageManager.getLaunchIntentForPackage(p)
                if (i != null) {
                    startActivity(i)
                    return
                }
            } catch (_: Exception) {}
        }
        Toast.makeText(this, "MT Manager не установлен", Toast.LENGTH_SHORT).show()
    }

    private fun extractZipClean(input: InputStream, targetDir: File): Int {
        var count = 0
        val buffer = ByteArray(8192)
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val raw = entry.name.replace('\\', '/')
                    val relPath = when {
                        raw.contains("/data/") -> "data/" + raw.substringAfter("/data/")
                        raw.startsWith("data/") -> raw
                        raw.contains("/texdb/") -> "texdb/" + raw.substringAfter("/texdb/")
                        raw.startsWith("texdb/") -> raw
                        raw.contains("/audio/") -> "audio/" + raw.substringAfter("/audio/")
                        raw.startsWith("audio/") -> raw
                        raw.endsWith(".dat", true) || raw.endsWith(".cfg", true) -> "data/" + raw.substringAfterLast('/')
                        raw.endsWith(".osw", true) -> "audio/SFX/" + raw.substringAfterLast('/')
                        else -> null
                    }

                    if (relPath != null) {
                        val file = File(targetDir, relPath)
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { fos ->
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
            try { return contentResolver.openInputStream(customZipUri!!) } catch (_: Exception) {}
        }

        if (presetId == "karma") {
            try { return assets.open("karma.zip") } catch (_: Exception) {}
            val paths = listOf(
                File("/sdcard/Download/karma_pack.zip"),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "karma_pack.zip")
            )
            for (f in paths) {
                if (f.exists() && f.canRead()) return FileInputStream(f)
            }
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dl.listFiles()?.firstOrNull { it.name.contains("карм", true) || it.name.contains("чит", true) }?.let {
                if (it.exists() && it.canRead()) return FileInputStream(it)
            }
        }

        try { return assets.open("molodoy.zip") } catch (_: Exception) {}

        val candidates = listOf(
            File("/sdcard/Download/molodoy_pack.zip"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "molodoy_pack.zip")
        )
        for (f in candidates) {
            if (f.exists() && f.canRead()) return FileInputStream(f)
        }
        return null
    }
}
