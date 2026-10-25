package com.nemos.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object PresetRepository {

    private val ALLOWED_ROOT_FOLDERS = setOf("data", "texdb", "audio")

    /**
     * Возвращает корневую директорию выбранного пресета внутри хранилища приложения,
     * доступную для чтения процессу Shizuku (shell).
     */
    fun getPresetDirectory(context: Context, presetId: String): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(baseDir, "presets/$presetId")
    }

    /**
     * Создает базовую структуру папок data, texdb, audio для встроенных пресетов,
     * чтобы сборка сразу работала из коробки.
     */
    suspend fun ensureBuiltInPresetsReady(context: Context) = withContext(Dispatchers.IO) {
        listOf("fps_boost", "pvp_pack", "custom_zip").forEach { presetId ->
            val presetDir = getPresetDirectory(context, presetId)
            ALLOWED_ROOT_FOLDERS.forEach { folderName ->
                val subDir = File(presetDir, folderName)
                if (!subDir.exists()) {
                    subDir.mkdirs()
                }
            }
            val marker = File(presetDir, "data/nemos_preset_info.txt")
            if (!marker.exists()) {
                marker.writeText("NEMOS APP Preset: $presetId\nTarget: com.grandmobile.game\n")
            }
        }
    }

    /**
     * Импортирует пользовательский .zip архив сборки в пресет custom_zip,
     * извлекая папки data, texdb и audio.
     */
    suspend fun importZipPreset(context: Context, zipUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val targetDir = getPresetDirectory(context, "custom_zip")
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            var extractedFilesCount = 0
            val resolver = context.contentResolver

            resolver.openInputStream(zipUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val normalized = entry.name.replace("\\", "/").trimStart('/')
                            val parts = normalized.split("/")
                            // Ищем вхождение одной из папок data, texdb, audio на любом уровне архива
                            val folderIndex = parts.indexOfFirst { it.lowercase() in ALLOWED_ROOT_FOLDERS }
                            if (folderIndex != -1 && folderIndex < parts.size - 1) {
                                val relPath = parts.subList(folderIndex, parts.size).joinToString("/")
                                val outFile = File(targetDir, relPath).canonicalFile
                                if (outFile.path.startsWith(targetDir.canonicalPath)) {
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { fos ->
                                        zis.copyTo(fos)
                                    }
                                    extractedFilesCount++
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            if (extractedFilesCount == 0) {
                // Если в архиве файлы лежали без папок, создаем дефолтные папки, чтобы не падать
                ensureBuiltInPresetsReady(context)
                Result.failure(
                    IllegalArgumentException("В ZIP-архиве не найдены папки data/, texdb/ или audio/.")
                )
            } else {
                Result.success(extractedFilesCount)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
