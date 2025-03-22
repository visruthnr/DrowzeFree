package com.example.drowsinessdetection

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.widget.Toast
import android.telephony.SmsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class DetectionService : Service() {

    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "drowsiness_detection_channel"
    
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var drowsinessTask: ScheduledFuture<*>? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Drowsiness detection active"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Start drowsiness monitoring
        startDrowsinessMonitoring()
        
        return START_STICKY
    }

    private fun startDrowsinessMonitoring() {
        drowsinessTask = executor.scheduleAtFixedRate({
            // This is where we would check for drowsiness from the main activity
            // Since our actual detection happens in MainActivity, this service primarily
            // handles notifications and keeping the app running
            
            updateNotification("Monitoring for drowsiness...")
        }, 0, 1, TimeUnit.SECONDS)
    }

    private fun updateNotification(message: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(message))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Drowsiness Detection",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Channel for drowsiness detection alerts"
                enableLights(true)
                enableVibration(true)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, 
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val stopIntent = Intent(this, DetectionService::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Drowsiness Detection")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        drowsinessTask?.cancel(true)
        executor.shutdown()
        super.onDestroy()
    }
}
