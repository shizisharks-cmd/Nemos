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
        val candidates = listOf("com.grand.launcher", "ru.grandmobile.game", "com.grandmobile.game", "com.grand.mobile")
        for (pkg in candidates) {
            try {
                packageManager.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }

        val (_, out) = runShizukuShell("pm list packages | grep -E 'grand|launcher' | cut -d: -f2 | tr -d '\r'")
        val found = out.lines().map { it.trim() }.firstOrNull { it.contains("grand") }
        if (!found.isNullOrEmpty()) return found

        return "com.grand.launcher"
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
            proc.waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun handleShizukuInstall() {
        if (!isShizukuAvailable()) {
            Toast.makeText(this, "Shizuku не запущен", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnShizukuInstall.isEnabled = false
        binding.tvStatus.text = "⏳ Остановка игры и замена файлов..."

        lifecycleScope.launch {
            val preset = sessionManager.getSelectedPresetId()
            val pkg = detectGrandPackage()

            val success = withContext(Dispatchers.IO) {
                runShizukuShell("am force-stop $pkg 2>/dev/null; am force-stop com.grand.launcher 2>/dev/null")

                var copiedCount = 0
                val stream = openModStream(preset) ?: return@withContext false

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
                copiedCount > 0
            }

            binding.btnShizukuInstall.isEnabled = true
            if (success) {
                binding.tvStatus.text = "✅ Сборка установлена в $pkg!"
                Toast.makeText(this@MainActivity, "✅ Сборка установлена! Запускаем...", Toast.LENGTH_SHORT).show()
                launchGrandMobile()
            } else {
                binding.tvStatus.text = "❌ Ошибка записи файлов"
            }
        }
    }

    private fun handleMtUnpackAndOpen() {
        binding.btnMtInstallAndOpen.isEnabled = false
        binding.tvStatus.text = "⏳ Распаковываем сборку в Загрузки..."

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

                binding.tvStatus.text = "✅ Скопируйте папку files в Android/data/$pkg"
                Toast.makeText(this@MainActivity, "👉 Скопируйте files в Android → data → $pkg", Toast.LENGTH_LONG).show()

                openMtManagerDirectly(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Nemos_Mods"))
            } else {
                binding.tvStatus.text = "❌ Ошибка распаковки"
            }
        }
    }

    private fun openMtManagerDirectly(folder: File) {
        val mtPackages = listOf("bin.mt.plus", "bin.mt.plus.canary", "bin.mt.mmp")
        for (pkg in mtPackages) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    startActivity(intent)
                    return
                }
            } catch (_: Exception) {}
        }
        Toast.makeText(this, "MT Manager не установлен", Toast.LENGTH_SHORT).show()
    }

    private fun openModStream(presetId: String): InputStream? {
        if (presetId == "custom" && customZipUri != null) {
            try { return contentResolver.openInputStream(customZipUri!!) } catch (_: Exception) {}
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
        }
        try { return assets.open("molodoy.zip") } catch (_: Exception) {}
        return null
    }

    private fun launchGrandMobile() {
        val pkg = detectGrandPackage()
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            return
        }

        if (isShizukuAvailable() && hasShizukuPermission()) {
            runShizukuShell("monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>/dev/null")
            return
        }
        Toast.makeText(this, "Запустите игру вручную", Toast.LENGTH_SHORT).show()
    }
}
