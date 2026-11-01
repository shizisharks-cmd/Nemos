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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.reflect.Method
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
            binding.tvStatus.text = "Нажмите кнопку, чтобы сразу открыть папку files в MT Manager"
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
                binding.tvStatus.text = "Нажмите на кнопку, чтобы подтвердить доступ в Shizuku"
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
        if (!isShizukuAvailable()) {
            AlertDialog.Builder(this)
                .setTitle("Shizuku не запущен")
                .setMessage("Служба Shizuku сейчас выключена.\n\n1. Откройте приложение Shizuku и нажмите «Запустить».\nИЛИ\n2. Переключите вкладку сверху на «📁 MT Manager».")
                .setPositiveButton("Открыть Shizuku") { _, _ ->
                    val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (intent != null) startActivity(intent)
                    else Toast.makeText(this, "Приложение Shizuku не найдено", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Понятно", null)
                .show()
            return
        }

        if (!hasShizukuPermission()) {
            try {
                Shizuku.requestPermission(1001)
                Toast.makeText(this, "Подтвердите доступ в появившемся окне Shizuku", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
                Toast.makeText(this, "Не удалось запросить доступ Shizuku", Toast.LENGTH_SHORT).show()
            }
            return
        }

        binding.btnShizukuInstall.isEnabled = false
        binding.tvStatus.text = "⏳ Устанавливаем файлы сборки через Shizuku..."

        lifecycleScope.launch {
            val preset = sessionManager.getSelectedPresetId()
            val pkg = getGrandPackage()
            val success = withContext(Dispatchers.IO) {
                applyModsViaShizuku(preset, pkg)
            }

            binding.btnShizukuInstall.isEnabled = true
            if (success) {
                binding.tvStatus.text = "✅ Сборка успешно установлена! Входим в игру..."
                Toast.makeText(this@MainActivity, "✅ Сборка установлена! Запускаем игру...", Toast.LENGTH_SHORT).show()
                launchGrandMobile()
            } else {
                binding.tvStatus.text = "❌ Ошибка: $lastShizukuError"
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Детали ошибки Shizuku")
                    .setMessage("Не удалось скопировать файлы напрямую:\n$lastShizukuError\n\nВы можете установить сборку через вкладку «📁 MT Manager».")
                    .setPositiveButton("Перейти к MT Manager") { _, _ -> setMode(false) }
                    .setNegativeButton("Закрыть", null)
                    .show()
            }
        }
    }

    private fun buildCleanZipBytes(presetId: String): ByteArray? {
        val input = openModStream(presetId) ?: return null
        val baos = ByteArrayOutputStream()
        var count = 0
        val buffer = ByteArray(8192)

        ZipInputStream(input.buffered()).use { zis ->
            ZipOutputStream(baos).use { zos ->
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
                            zos.putNextEntry(ZipEntry("files/$relPath"))
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                zos.write(buffer, 0, len)
                            }
                            zos.closeEntry()
                            count++
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return if (count > 0) baos.toByteArray() else null
    }

    private fun applyModsViaShizuku(presetId: String, pkg: String): Boolean {
        try {
            val cleanZip = buildCleanZipBytes(presetId)
            if (cleanZip == null) {
                lastShizukuError = "Архив сборки не найден"
                return false
            }

            // Дополнительно сохраняем во внешний кэш приложения (доступен для UID 2000)
            val extStaging = File(externalCacheDir ?: cacheDir, "shizuku_staging")
            extStaging.deleteRecursively()
            extStaging.mkdirs()
            extractZipBytesToDir(cleanZip, extStaging)

            val script = """
                set -e
                TMP_DIR="/data/local/tmp/nemos_work"
                rm -rf "${'$'}TMP_DIR"
                mkdir -p "${'$'}TMP_DIR"
                cat > "${'$'}TMP_DIR/pack.zip"
                
                if command -v unzip >/dev/null 2>&1; then
                    unzip -oq "${'$'}TMP_DIR/pack.zip" -d "${'$'}TMP_DIR/unpacked"
                    SRC_FILES="${'$'}TMP_DIR/unpacked/files"
                else
                    SRC_FILES="${extStaging.absolutePath}/files"
                fi

                 TARGET_BASE="/storage/emulated/0/Android/data/$pkg"
                for d in /storage/emulated/0/Android/data/*grand* /sdcard/Android/data/*grand*; do
                    if [ -d "${'$'}d" ]; then
                        TARGET_BASE="${'$'}d"
                        break
                    fi
                done

                mkdir -p "${'$'}TARGET_BASE/files"

                cd "${'$'}SRC_FILES"
                find . -type f | while read -r rel; do
                    clean_rel="${'$'}{rel#./}"
                    dest_file="${'$'}TARGET_BASE/files/${'$'}clean_rel"
                    dest_dir="${'$'}(dirname "${'$'}dest_file")"
                    mkdir -p "${'$'}dest_dir"
                    rm -f "${'$'}dest_file" 2>/dev/null || true
                    cp -f "${'$'}SRC_FILES/${'$'}clean_rel" "${'$'}dest_file" || cat "${'$'}SRC_FILES/${'$'}clean_rel" > "${'$'}dest_file"
                done

                rm -rf "${'$'}TMP_DIR"
                echo "OK"
            """.trimIndent()

            val method: Method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val proc = method.invoke(null, arrayOf("sh", "-c", script), null, null) as Process

            proc.outputStream.use { os ->
                os.write(cleanZip)
                os.flush()
            }

            val outText = proc.inputStream.bufferedReader().readText()
            val errText = proc.errorStream.bufferedReader().readText()
            val code = proc.waitFor()

            if (code == 0 && outText.contains("OK")) {
                return true
            } else {
                lastShizukuError = errText.ifBlank { "Код выхода: $code" }.take(200)
                return false
            }
        } catch (e: Throwable) {
            lastShizukuError = e.message ?: "Неизвестная ошибка Shizuku"
            return false
        }
    }

    private fun extractZipBytesToDir(zipBytes: ByteArray, destRoot: File): Int {
        var count = 0
        val buffer = ByteArray(8192)
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = File(destRoot, entry.name)
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                    count++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return count
    }

    private fun handleMtUnpackAndOpen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("Доступ к памяти")
                    .setMessage("Включите разрешение один раз, чтобы лаунчер сразу открывал готовую папку files в MT Manager.")
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

        binding.btnMtInstallAndOpen.isEnabled = false
        binding.tvStatus.text = "⏳ Готовим папку files и открываем MT Manager..."

        lifecycleScope.launch {
            val preset = sessionManager.getSelectedPresetId()
            // Папка с "!" всегда стоит первой в самом верху списка /storage/emulated/0/ рядом с Android
            val topDir = File(Environment.getExternalStorageDirectory(), "!_СБОРКА_NEMOS")
            val zipForMt = File(topDir, "ПЕРЕМЕСТИ_ПАПКУ_FILES.zip")

            val success = withContext(Dispatchers.IO) {
                try {
                    val cleanZip = buildCleanZipBytes(preset) ?: return@withContext false
                    topDir.deleteRecursively()
                    topDir.mkdirs()

                    // 1. Записываем готовый архив (MT Manager сразу откроет его внутри и покажет папку files)
                    FileOutputStream(zipForMt).use { it.write(cleanZip) }

                    // 2. Также распаковываем саму папку files рядом
                    extractZipBytesToDir(cleanZip, topDir)

                    // 3. Дублируем в Download/Nemos_Mods на всякий случай
                    val dlDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Nemos_Mods")
                    dlDir.deleteRecursively()
                    dlDir.mkdirs()
                    extractZipBytesToDir(cleanZip, dlDir)
                    true
                } catch (_: Exception) {
                    false
                }
            }

            binding.btnMtInstallAndOpen.isEnabled = true

            if (success) {
                val pkg = getGrandPackage()
                val targetPath = "/storage/emulated/0/Android/data/$pkg/"
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("grand_path", targetPath))

                binding.tvStatus.text = "✅ Папка files открыта в MT Manager! Скопируй её в Android/data/$pkg"
                Toast.makeText(
                    this@MainActivity,
                    "👉 Зажми папку «files» и скопируй её в Android → data → $pkg",
                    Toast.LENGTH_LONG
                ).show()

                openMtManagerDirectly(zipForMt, topDir)
            } else {
                binding.tvStatus.text = "❌ Ошибка подготовки файлов сборки"
                Toast.makeText(this@MainActivity, "Не удалось подготовить файлы", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openMtManagerDirectly(zipFile: File, folder: File) {
        try {
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        } catch (_: Throwable) {}

        val mtPackages = listOf("bin.mt.plus", "bin.mt.plus.canary", "bin.mt.mmp")
        for (pkg in mtPackages) {
            if (isPackageInstalled(pkg)) {
                // Открываем в MT Manager напрямую архив ПЕРЕМЕСТИ_ПАПКУ_FILES.zip, где лежит только папка files
                try {
                    val zipIntent = Intent(Intent.ACTION_VIEW).apply {
                        setPackage(pkg)
                        setDataAndType(Uri.fromFile(zipFile), "application/zip")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(zipIntent)
                    return
                } catch (_: Throwable) {}

                try {
                    val folderIntent = Intent(Intent.ACTION_VIEW).apply {
                        setPackage(pkg)
                        setDataAndType(Uri.fromFile(folder), "resource/folder")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(folderIntent)
                    return
                } catch (_: Throwable) {}

                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    return
                }
            }
        }
        Toast.makeText(this, "MT Manager не установлен на телефоне", Toast.LENGTH_SHORT).show()
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        return try {
            packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
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
            if (isPackageInstalled(pkg)) return pkg
        }
        try {
            val apps = packageManager.getInstalledApplications(0)
            for (app in apps) {
                val p = app.packageName.lowercase()
                if (p.contains("grand") && p != packageName.lowercase()) {
                    return app.packageName
                }
            }
        } catch (_: Exception) {}
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
