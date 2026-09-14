package com.vertil.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vertil.R
import com.vertil.core.log.VertilLog

/**
 * Servicio de monitoring en primer plano.
 *
 * v1.0: estructura básica. Se inicia bajo demanda cuando hay una tarea activa.
 * v1.1: integrará WorkManager + observer de MediaStore para automatizaciones.
 */
class MonitoringService : Service() {

    companion object {
        private const val CHANNEL_ID = "vertil_monitoring"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            val intent = Intent(ctx, MonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitoringService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("VERTIL CORE", "Monitorización activa"))
        VertilLog.i("MonitoringService", "Servicio iniciado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        VertilLog.i("MonitoringService", "Servicio detenido")
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, "VERTIL Monitoring",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notificaciones de tareas en segundo plano de VERTIL"
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_splash_icon)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
