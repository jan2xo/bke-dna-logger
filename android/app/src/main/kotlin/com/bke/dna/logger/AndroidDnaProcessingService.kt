package com.bke.dna.logger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Foreground lifetime for queued DNA work that must survive screen-off/background idle.
 *
 * The service does not own a second worker. AndroidDerivationScheduler remains the single
 * processing lane and keeps browser-first quiet/yield semantics. This service only gives
 * that durable queue an Android-sanctioned foreground lifetime while unfinished work exists.
 * A partial wake lock is held only while one derivation job is actually claimed, never while
 * the queue is empty.
 */
class AndroidDnaProcessingService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        AndroidDerivationScheduler.startFromProcessingService(applicationContext)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseActiveWorkWakeLock()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DNA background processing",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps queued conversation DNA processing alive while the screen is off."
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("BKE DNA Logger")
            .setContentText("Processing queued conversation DNA")
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "BkeDnaService"
        private const val CHANNEL_ID = "bke_dna_background_processing"
        private const val NOTIFICATION_ID = 41040
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1_000L
        private val wakeLockGuard = Any()
        private var activeWorkWakeLock: PowerManager.WakeLock? = null

        fun ensureRunning(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, AndroidDnaProcessingService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }.onFailure {
                // Queue/RAW are already durable. If an OEM temporarily refuses the FGS start,
                // keep the in-process scheduler as the fallback and retry on the next queue kick.
                Log.w(TAG, "Unable to start DNA foreground processor", it)
            }
        }

        fun stopWhenIdle(context: Context) {
            releaseActiveWorkWakeLock()
            context.applicationContext.stopService(
                Intent(context.applicationContext, AndroidDnaProcessingService::class.java),
            )
        }

        fun beginActiveWork(context: Context) {
            synchronized(wakeLockGuard) {
                if (activeWorkWakeLock?.isHeld == true) return
                val powerManager = context.applicationContext
                    .getSystemService(Context.POWER_SERVICE) as PowerManager
                activeWorkWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "BkeDnaLogger:derivation",
                ).apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
                Log.d(TAG, "BKE DNA service: active_work_wake_lock_acquired")
            }
        }

        fun endActiveWork() {
            synchronized(wakeLockGuard) {
                releaseActiveWorkWakeLock()
            }
        }

        private fun releaseActiveWorkWakeLock() {
            val wakeLock = activeWorkWakeLock
            activeWorkWakeLock = null
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
                Log.d(TAG, "BKE DNA service: active_work_wake_lock_released")
            }
        }
    }
}
