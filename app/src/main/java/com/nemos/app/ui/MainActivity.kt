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
import android.os.StrictMode
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
    private var isShizukuMode = true
    private var lastShizukuError = ""

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

        val initialPreset = sessionManager.getSelectedPresetId().ifBlank { "karma" }
        selectPreset(initialPreset)

        binding.cardKarma.setOnClickListener { selectPreset("karma") }
        binding.cardMolodoy.setOnClickListener { selectPreset("molodoy") }
        binding.cardFps.setOnClickListener { selectPreset("fps_boost") }
        binding.cardCustom.setOnClickListener { selectPreset("custom") }

        binding.btnPickZip.setOnClickListener {
            pickZipLauncher.launch("application/zip")
        }

        binding.btnLogout.setOnClickListener {
            sessionManager.clearSession()
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
        }

        binding.btnTabShizuku.setOnClickListener { setMode(true) }
        binding.btnTabMt.setOnClickListener { setMode(false) }

        binding.btnShizukuInstall.setOnClickListener {
            handleShizukuInstall()
        }

        binding.btnMtInstallAndOpen.setOnClickListener {
            handleMtUnpackAndOpen()
        }

        binding.btnMtLaunchGame.setOnClickListener {
            launchGrandMobile()
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
            binding.btnTabMt.setBackgroundResource(R.drawable.bg_card_unselected)
            binding.btnTabMt.setTextColor(0xFFA1A1AA.toInt())
            binding.layoutShizukuView.visibility = View.VISIBLE
            binding.layoutMtView.visibility = View.GONE
            updateShizukuUi()
        } else {
            binding.btnTabMt.setBackgroundResource(R.drawable.bg_card_selected)
            binding.btnTabMt.setTextColor(0xFFFFFFFF.toInt())
            binding.btnTabShizuku.setBackgroundResource(R.drawable.bg_card_unselected)
            binding.btnTabShizuku.setTextColor(0xFFA1A1AA.toInt())
            binding.layoutShizukuView.visibility = View.GONE
            binding.layoutMtView.visibility = View.VISIBLE
            binding.tvStatus.text = "Нажмите кнопку, чтобы распаковать файлы для MT Manager"
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

        val alive = isShizukuAvailable()
        if (alive) {
            val perm = hasShizukuPermission()
            if (perm) {
                binding.tvShizukuStatus.text = "🟢 Shizuku активен: всё готово к 1 клику"
                binding.tvShizukuStatus.setTextColor(0xFF2DD4BF.toInt())
                binding.btnShizukuInstall.text = "🚀 УСТАНОВИТЬ И ВОЙТИ В ИГРУ (1 КЛИК)"
                binding.tvStatus.text = "Нажмите кнопку для авто-установки и запуска Grand Mobile"
            } else {
                binding.tvShizukuStatus.text = "🟡 Shizuku запущен, нужно выдать разрешение"
                binding.tvShizukuStatus.setTextColor(0xFFF59E0B.toInt())
                binding.btnShizukuInstall.text = "🔑 РАЗРЕШИТЬ ДОСТУП В SHIZUKU"
                binding.tvStatus.text = "Подтвердите доступ во всплывающем окне Shizuku"
            }
        } else {
            binding.tvShizukuStatus.text = "🔴 Shizuku не запущен (проверьте службу)"
            binding.tvShizukuStatus.setTextColor(0xFFEF4444.toInt())
            binding.btnShizukuInstall.text = "🚀 УСТАНОВИТЬ И ВОЙТИ В ИГРУ"
            binding.tvStatus.text = "Служба Shizuku выключена. Запустите её или выберите «MT Manager»"
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
        val (_, out) = runShizukuShell("pm list packages | grep -i grand | cut -d: -f2 | tr -d '\r'")
        val found = out.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        if (!found.isNullOrEmpty()) return found

        val known = listOf("com.grandmobile.game", "ru.grandmobile.game", "com.grand.mobile", "com.weiz.grandmobile")
        for (pkg in known) {
            try {
                packageManager.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        return "com.grandmobile.game"
    }

    private fun streamFileToGameViaShizuku(pkg: String, relPath: String, input: InputStream): Boolean {
        return try {
            val dest = "/storage/emulated/0/Android/data/$pkg/files/$relPath"
            val cmd = "mkdir -p \"\$(dirname '$dest')\" && cat > '$dest'"
            val method: Method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val proc = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process

            proc.outputStream.use { out ->
                input.copyTo(out)
                out.flush()
            }
            val code = proc.waitFor()
            code == 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun handleShizukuInstall() {
        if (!isShizukuAvailable()) {
            AlertDialog.Builder(this)
                .setTitle("Shizuku не запущен")
                .setMessage("Запустите службу в приложении Shizuku или переключитесь на «MT Manager».")
                .setPositiveButton("Открыть Shizuku") { _, _ ->
                    val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (intent != null) startActivity(intent)
                }
                .setNegativeButton("Понятно", null)
                .show()
            return
        }

        if (!hasShizukuPermission()) {
            try {
                Shizuku.requestPermission(1001)
                Toast.makeText(this, "Подтвердите доступ в Shizuku", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
            return
        }

        binding.btnShizukuInstall.isEnabled = false
        binding.tvStatus.text = "⏳ Остановка игры и замена файлов..."

        lifecycleScope.launch {
            val preset = sessionManager.getSelectedPresetId()
            val pkg = detectGrandPackage()

            val success = withContext(Dispatchers.IO) {
                // 1. Принудительно выгружаем процесс игры
                runShizukuShell("am force-stop $pkg 2>/dev/null; am force-stop com.grandmobile.game 2>/dev/null; am force-stop ru.grandmobile.game 2>/dev/null")

                // 2. Прямая пофайловая передача через системный поток Shizuku
                var copiedCount = 0
                val stream = openModStream(preset)
                if (stream == null) {
                    lastShizukuError = "Архив сборки не найден на телефоне"
                    return@withContext false
                }

                stream.use { rawInput ->
                    ZipInputStream(rawInput.buffered()).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val rawName = entry.name.replace('\\', '/')
                                val relPath = when {
                                    rawName.contains("/data/") -> "data/" + rawName.substringAfter("/data/")
                                    rawName.startsWith("data/") -> rawName
                                    rawName.contains("/texdb/") -> "texdb/" + rawName.substringAfter("/texdb/")
                                    rawName.startsWith("texdb/") -> rawName
                                    rawName.contains("files/") -> rawName.substringAfter("files/")
                                    rawName.startsWith("Molodoy/") -> rawName.removePrefix("Molodoy/")
                                    rawName.endsWith(".dat", true) || rawName.endsWith(".cfg", true) -> "data/$rawName"
                                    else -> rawName
                                }.trimStart('/')

                                if (relPath.isNotEmpty() && !relPath.endsWith("/")) {
                                    val ok = streamFileToGameViaShizuku(pkg, relPath, zis)
                                    if (ok) copiedCount++
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }

                if (copiedCount == 0) {
                    lastShizukuError = "Не удалось извлечь файлы из архива"
                    false
                } else {
                    true
                }
            }

            binding.btnShizukuInstall.isEnabled = true
            if (success) {
                binding.tvStatus.text = "✅ Сборка успешно установлена! Запускаем Grand Mobile..."
                Toast.makeText(this@MainActivity, "✅ Сборка установлена! Входим в игру...", Toast.LENGTH_SHORT).show()
                launchGrandMobile()
            } else {
                binding.tvStatus.text = "❌ Ошибка: $lastShizukuError"
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Ошибка установки")
                    .setMessage("$lastShizukuError\n\nВы можете распаковать файлы и установить их через MT Manager.")
                    .setPositiveButton("К MT Manager") { _, _ -> setMode(false) }
                    .setNegativeButton("Закрыть", null)
                    .show()
            }
        }
    }

    private fun handleMtUnpackAndOpen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            AlertDialog.Builder(this)
                .setTitle("Доступ ко всем файлам")
                .setMessage("Для работы через MT Manager необходимо выдать разрешение на доступ к файлам.")
                .setPositiveButton("Включить") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }

        binding.btnMtInstallAndOpen.isEnabled = false
        binding.tvStatus.text = "⏳ Распаковываем файлы сборки в Загрузки..."

        lifecycleScope.launch {
            val preset = sessionManager.getSelectedPresetId()
            val targetFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Nemos_Mods/files")

            val success = withContext(Dispatchers.IO) {
                try {
                    targetFolder.deleteRecursively()
                    targetFolder.mkdirs()

                    var count = 0
                    val buffer = ByteArray(8192)
                    openModStream(preset)?.use { input ->
                        ZipInputStream(input.buffered()).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory) {
                                    val rawName = entry.name.replace('\\', '/')
                                    val relPath = when {
                                        rawName.contains("/data/") -> "data/" + rawName.substringAfter("/data/")
                                        rawName.startsWith("data/") -> rawName
                                        rawName.contains("/texdb/") -> "texdb/" + rawName.substringAfter("/texdb/")
                                        rawName.startsWith("texdb/") -> rawName
                                        rawName.contains("files/") -> rawName.substringAfter("files/")
                                        rawName.startsWith("Molodoy/") -> rawName.removePrefix("Molodoy/")
                                        rawName.endsWith(".dat", true) || rawName.endsWith(".cfg", true) -> "data/$rawName"
                                        else -> rawName
                                    }.trimStart('/')

                                    if (relPath.isNotEmpty() && !relPath.endsWith("/")) {
                                        val outFile = File(targetFolder, relPath)
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
                    }
                    count > 0
                } catch (_: Exception) {
                    false
                }
            }

            binding.btnMtInstallAndOpen.isEnabled = true

            if (success) {
                val pkg = detectGrandPackage()
                val targetPath = "/storage/emulated/0/Android/data/$pkg/"
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("grand_path", targetPath))

                binding.tvStatus.text = "✅ Папка files создана в Download/Nemos_Mods/"
                Toast.makeText(this@MainActivity, "👉 Зажмите папку files и скопируйте в Android → data → $pkg", Toast.LENGTH_LONG).show()

                openMtManagerDirectly(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Nemos_Mods"))
            } else {
                binding.tvStatus.text = "❌ Ошибка извлечения файлов"
                Toast.makeText(this@MainActivity, "Не удалось распаковать архив", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openMtManagerDirectly(folder: File) {
        try {
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        } catch (_: Throwable) {}

        val mtPackages = listOf("bin.mt.plus", "bin.mt.plus.canary", "bin.mt.mmp")
        for (pkg in mtPackages) {
            try {
                val folderIntent = Intent(Intent.ACTION_VIEW).apply {
                    setPackage(pkg)
                    setDataAndType(Uri.fromFile(folder), "resource/folder")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(folderIntent)
                return
            } catch (_: Throwable) {}
        }
        Toast.makeText(this, "MT Manager не установлен", Toast.LENGTH_SHORT).show()
    }

    private fun openModStream(presetId: String): InputStream? {
        if (presetId == "custom" && customZipUri != null) {
            try {
                return contentResolver.openInputStream(customZipUri!!)
            } catch (_: Exception) {}
        }

        if (presetId == "karma") {
            try { return assets.open("karma.zip") } catch (_: Exception) {}
            val candidates = listOf(
                File("/sdcard/Download/karma_pack.zip"),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "karma_pack.zip")
            )
            for (f in candidates) {
                if (f.exists() && f.canRead()) return FileInputStream(f)
            }
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dl.listFiles()?.firstOrNull { it.name.contains("карм", true) || it.name.contains("чит", true) || it.name.contains("karma", true) }?.let {
                if (it.exists() && it.canRead()) return FileInputStream(it)
            }
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
        val pkg = detectGrandPackage()
        
        // Запуск через системный shell Shizuku без ограничений Android
        if (isShizukuAvailable() && hasShizukuPermission()) {
            runShizukuShell("monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>/dev/null || am start -n $pkg/com.grandmobile.game.MainActivity 2>/dev/null")
            return
        }

        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            return
        }
        Toast.makeText(this, "Запустите Grand Mobile вручную", Toast.LENGTH_SHORT).show()
    }
}
