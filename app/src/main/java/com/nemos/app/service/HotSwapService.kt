package com.nemos.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.nemos.app.R
import com.nemos.app.data.PresetRepository
import com.nemos.app.shizuku.ShizukuHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HotSwapService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var countdownJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val delaySeconds = intent?.getIntExtra(EXTRA_DELAY_SECONDS, 17) ?: 17
        val presetId = intent?.getStringExtra(EXTRA_PRESET_ID) ?: "fps_boost"

        startForeground(NOTIFICATION_ID, buildNotification("Таймер Hot-Swap: $delaySeconds сек..."))

        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            for (remaining in delaySeconds downTo 1) {
                updateNotification("До горячей подмены файлов: $remaining сек...")
                sendBroadcastStatus("⏳ Обратный отсчет Hot-Swap: $remaining сек...")
                delay(1_000L)
            }

            updateNotification("Копирование data, texdb, audio через Shizuku...")
            sendBroadcastStatus("⚡️ Выполняется подмена файлов в com.grandmobile.game...")

            val presetDir = PresetRepository.getPresetDirectory(applicationContext, presetId)
            val result = ShizukuHelper.hotSwapModFolders(presetDir)

            withContext(Dispatchers.Main) {
                result.onSuccess { count ->
                    val msg = "✅ Модификация успешно применена ($count папок перезаписано)!"
                    Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                    sendBroadcastStatus(msg)
                }.onFailure { err ->
                    val msg = "❌ Ошибка Hot-Swap: ${err.localizedMessage}"
                    Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                    sendBroadcastStatus(msg)
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun sendBroadcastStatus(message: String) {
        val broadcast = Intent(ACTION_HOTSWAP_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS_MESSAGE, message)
        }
        sendBroadcast(broadcast)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NEMOS Hot-Swap Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Обратный отсчет и горячая замена файлов Grand Mobile"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("NEMOS APP • Hot-Swap Engine")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    override fun onDestroy() {
        countdownJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "nemos_hotswap_channel"
        const val NOTIFICATION_ID = 7001
        const val EXTRA_DELAY_SECONDS = "extra_delay_seconds"
        const val EXTRA_PRESET_ID = "extra_preset_id"
        const val ACTION_HOTSWAP_STATUS = "com.nemos.app.ACTION_HOTSWAP_STATUS"
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"
    }
}
