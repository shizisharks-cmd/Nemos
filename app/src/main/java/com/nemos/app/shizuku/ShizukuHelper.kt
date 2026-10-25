package com.nemos.app.shizuku

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

enum class ShizukuState {
    ACTIVE_GRANTED,
    RUNNING_NO_PERMISSION,
    NOT_RUNNING
}

object ShizukuHelper {

    const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    const val TARGET_GAME_FILES_DIR = "/storage/emulated/0/Android/data/com.grandmobile.game/files"

    /**
     * Проверяет активность сервиса Shizuku и наличие выданного разрешения.
     */
    fun checkStatus(): ShizukuState {
        return try {
            if (!Shizuku.pingBinder()) {
                ShizukuState.NOT_RUNNING
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuState.ACTIVE_GRANTED
            } else {
                ShizukuState.RUNNING_NO_PERMISSION
            }
        } catch (e: Throwable) {
            ShizukuState.NOT_RUNNING
        }
    }

    /**
     * Запрашивает разрешение у пользователя через системное окно Shizuku.
     */
    fun requestPermissionIfNeeded(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) {
                false
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                true
            } else {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                true
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Выполняет горячую подмену папок (data, texdb, audio) из выбранного пресета
     * в защищенную директорию /storage/emulated/0/Android/data/com.grandmobile.game/files/
     * через привилегированный Shizuku Binder процесс (UID 2000 / shell).
     */
    suspend fun hotSwapModFolders(sourcePresetDir: File): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (checkStatus() != ShizukuState.ACTIVE_GRANTED) {
                return@withContext Result.failure(
                    IllegalStateException("Служба Shizuku не активна или доступ не предоставлен.")
                )
            }

            val allowedFolders = listOf("data", "texdb", "audio")
            val existingFolders = allowedFolders.map { File(sourcePresetDir, it) }.filter { it.exists() }

            if (existingFolders.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("В выбранной сборке нет папок data, texdb или audio.")
                )
            }

            val srcPath = sourcePresetDir.absolutePath
            val dstPath = TARGET_GAME_FILES_DIR

            // Формируем shell-команду для мгновенного копирования через привилегированный контекст Shizuku (shell UID 2000)
            val copyCommands = buildString {
                append("mkdir -p "$dstPath" && ")
                existingFolders.forEachIndexed { idx, folder ->
                    if (idx > 0) append(" && ")
                    append("cp -rf "$srcPath/${folder.name}" "$dstPath/"")
                }
            }

            val exitCode = execShizukuCommand(arrayOf("sh", "-c", copyCommands))
            if (exitCode == 0) {
                Result.success(existingFolders.size)
            } else {
                Result.failure(RuntimeException("Команда копирования Shizuku завершилась с кодом $exitCode"))
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Запускает привилегированный процесс через Shizuku Binder (Shizuku.newProcess).
     */
    @Suppress("DEPRECATION")
    private fun execShizukuCommand(cmd: Array<String>): Int {
        val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        newProcessMethod.isAccessible = true
        val process = newProcessMethod.invoke(null, cmd, null, null) as Process
        return process.waitFor()
    }
}
