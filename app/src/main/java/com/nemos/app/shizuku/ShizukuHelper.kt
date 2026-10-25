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
    private val CANDIDATE_GAME_PACKAGES = listOf(
        "com.grandmobile.game",
        "com.grand.launcher",
        "com.grand.cr"
    )

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

    suspend fun launchGamePackage(packages: List<String> = CANDIDATE_GAME_PACKAGES): Boolean =
        withContext(Dispatchers.IO) {
            if (checkStatus() != ShizukuState.ACTIVE_GRANTED) return@withContext false
            for (pkg in packages) {
                try {
                    val code = execShizukuCommand(
                        arrayOf("sh", "-c", "monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
                    )
                    if (code == 0) return@withContext true
                } catch (_: Throwable) {
                }
            }
            false
        }

    suspend fun hotSwapModFolders(sourcePresetDir: File): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (checkStatus() != ShizukuState.ACTIVE_GRANTED) {
                return@withContext Result.failure(
                    IllegalStateException("Включи доступ Shizuku в настройках.")
                )
            }

            val allowedFolders = listOf("data", "texdb", "audio")
            val existingFolders = allowedFolders.map { File(sourcePresetDir, it) }.filter { it.exists() }

            if (existingFolders.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Файлы сборки не найдены.")
                )
            }

            val srcPath = sourcePresetDir.absolutePath
            val copyCommands = buildString {
                CANDIDATE_GAME_PACKAGES.forEachIndexed { pkgIdx, pkg ->
                    val dstPath = "/storage/emulated/0/Android/data/$pkg/files"
                    if (pkgIdx > 0) append(" ; ")
                    append("mkdir -p '").append(dstPath).append("'")
                    existingFolders.forEach { folder ->
                        append(" && cp -rf '").append(srcPath).append("/").append(folder.name)
                        append("' '").append(dstPath).append("/'")
                    }
                }
            }

            val exitCode = execShizukuCommand(arrayOf("sh", "-c", copyCommands))
            if (exitCode == 0) {
                Result.success(existingFolders.size)
            } else {
                Result.failure(RuntimeException("Код ошибки Shizuku: $exitCode"))
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

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
