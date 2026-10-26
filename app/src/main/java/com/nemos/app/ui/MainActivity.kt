package com.nemos.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
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
            binding.tvCustomZipName.text = "Выбран пользовательский .ZIP"
            selectPreset("custom")
        }
    }

    private val openFolderLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {}
            getSharedPreferences("nemos_prefs", MODE_PRIVATE).edit()
                .putString("saf_game_uri", uri.toString())
                .apply()
            Toast.makeText(this, "Папка игры привязана!", Toast.LENGTH_SHORT).show()
            executeInstallAndLaunch()
        } else {
            binding.btnPlay.isEnabled = true
            binding.tvStatus.text = "⚠️ Нажми «Использовать эту папку» внизу экрана"
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
            getSharedPreferences("nemos_prefs", MODE_PRIVATE).edit().remove("saf_game_uri").apply()
            Toast.makeText(this, "Сохранено (путь к папке сброшен для перепривязки)", Toast.LENGTH_SHORT).show()
        }

        binding.btnPlay.setOnClickListener {
            checkAccessAndStart()
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

    private fun detectGrandPackage(): String {
        val candidates = listOf(
            "com.grandmobile.game",
            "ru.grandmobile.game",
            "com.grand.mobile",
            "com.weiz.grandmobile"
        )
        for (pkg in candidates) {
            if (packageManager.getLaunchIntentForPackage(pkg) != null) return pkg
        }

        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = packageManager.queryIntentActivities(mainIntent, 0)
        for (info in apps) {
            val pkg = info.activityInfo.packageName
            val label = info.loadLabel(packageManager)?.toString().orEmpty()
            if ((pkg.contains("grand", ignoreCase = true) || label.contains("grand", ignoreCase = true)) && !pkg.contains("nemos", ignoreCase = true)) {
                return pkg
            }
        }
        return "com.grandmobile.game"
    }

    private fun checkAccessAndStart() {
        val savedUri = getSavedTreeUri()
        if (savedUri != null) {
            executeInstallAndLaunch()
        } else {
            promptFolderAccess()
        }
    }

    private fun getSavedTreeUri(): Uri? {
        val uriStr = getSharedPreferences("nemos_prefs", MODE_PRIVATE).getString("saf_game_uri", null) ?: return null
        if (!uriStr.contains("Android", ignoreCase = true) && !uriStr.contains("grand", ignoreCase = true)) {
            return null
        }
        val uri = Uri.parse(uriStr)
        val hasPerm = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return if (hasPerm) uri else null
    }

    private fun promptFolderAccess() {
        val pkg = detectGrandPackage()
        Toast.makeText(this, "Нажми внизу синюю кнопку «Использовать эту папку»", Toast.LENGTH_LONG).show()
        val encodedPath = Uri.encode("Android/data/$pkg/files")
        val initialUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3A$encodedPath")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        openFolderLauncher.launch(intent)
    }

    private fun executeInstallAndLaunch() {
        binding.btnPlay.isEnabled = false
        binding.tvStatus.text = "⏳ Установка сборки в Grand Mobile..."

        val preset = sessionManager.getSelectedPresetId()
        val delaySec = sessionManager.getSwapDelaySeconds()

        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                applyModsToGame(preset)
            }

            val launched = launchGrandMobile()
            if (launched) {
                binding.tvStatus.text = "✅ Сборка активна ($count файлов). Игра запущена!"
                lifecycleScope.launch(Dispatchers.IO) {
                    delay(delaySec * 1000L)
                    applyModsToGame(preset)
                }
            } else {
                binding.tvStatus.text = "⚠️ Файлы записаны ($count), но игра не найдена"
            }
            binding.btnPlay.isEnabled = true
        }
    }

    private fun applyModsToGame(presetId: String): Int {
        var count = 0
        val pkg = detectGrandPackage()
        val treeUri = getSavedTreeUri()

        if (treeUri != null) {
            val rootDoc = DocumentFile.fromTreeUri(this, treeUri)
            if (rootDoc != null) {
                val targetDoc = resolveFilesDir(rootDoc, pkg)
                openModStream(presetId)?.use { input ->
                    count = copyZipToDocumentTree(input, targetDoc)
                }
            }
        }

        if (count == 0) {
            val directDir = File(Environment.getExternalStorageDirectory(), "Android/data/$pkg/files")
            if (directDir.exists() && directDir.canWrite()) {
                openModStream(presetId)?.use { input ->
                    count = extractZipToDirectDir(input, directDir)
                }
            }
        }

        return count
    }

    private fun resolveFilesDir(rootDoc: DocumentFile, pkg: String): DocumentFile {
        val name = rootDoc.name.orEmpty()
        if (name.equals("files", ignoreCase = true)) return rootDoc
        if (name.contains("grand", ignoreCase = true)) {
            return rootDoc.findFile("files") ?: rootDoc.createDirectory("files") ?: rootDoc
        }
        if (name.equals("data", ignoreCase = true)) {
            val pkgDir = rootDoc.findFile(pkg) ?: rootDoc.listFiles().firstOrNull { it.name?.contains("grand", true) == true } ?: rootDoc.createDirectory(pkg) ?: rootDoc
            return pkgDir.findFile("files") ?: pkgDir.createDirectory("files") ?: pkgDir
        }
        return rootDoc
    }

    private fun copyZipToDocumentTree(input: InputStream, targetDir: DocumentFile): Int {
        var filesCopied = 0
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
                        val parts = relPath.split("/").filter { it.isNotEmpty() }
                        var curr = targetDir
                        for (i in 0 until parts.size - 1) {
                            val dirName = parts[i]
                            curr = curr.findFile(dirName) ?: curr.createDirectory(dirName) ?: curr
                        }
                        val fileName = parts.last()
                        curr.findFile(fileName)?.delete()
                        val newDoc = curr.createFile("application/octet-stream", fileName)
                        if (newDoc != null) {
                            contentResolver.openOutputStream(newDoc.uri)?.use { os ->
                                zis.copyTo(os)
                            }
                            filesCopied++
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return filesCopied
    }

    private fun extractZipToDirectDir(input: InputStream, targetDir: File): Int {
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

    private fun launchGrandMobile(): Boolean {
        val pkg = detectGrandPackage()
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return true
        }
        return false
    }
}
