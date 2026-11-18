package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.meta.wearable.dat.externalsampleapps.cameraaccess.MainActivity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Foreground service that keeps VisionClaw working while the phone is locked or in a pocket.
 *
 * - Type "connectedDevice": keeps the link to the glasses alive.
 * - Type "microphone": Android only lets a background app hear the mic through a foreground
 *   service of this type; without it your voice is silenced as soon as the screen goes off.
 * - Partial wake lock: keeps the processor running while the screen is off (released on stop).
 * - Notification with a "Stop" button that ends the glasses stream and the Gemini session
 *   without opening the app.
 */
class StreamingService : Service() {

  companion object {
    private const val TAG = "StreamingService"
    private const val CHANNEL_ID = "streaming_channel"
    private const val CHANNEL_NAME = "VisionClaw running"
    private const val NOTIFICATION_ID = 1001
    private const val WAKELOCK_TAG = "VisionClaw::StreamingWakeLock"
    private const val ACTION_STOP = "com.visionclaw.action.STOP"

    // Safety cap so a forgotten session cannot keep the phone awake forever.
    private const val WAKELOCK_MAX_MS = 4 * 60 * 60 * 1000L // 4 hours

    private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when the user taps "Stop" in the notification. */
    val stopRequests: SharedFlow<Unit> = _stopRequests.asSharedFlow()

    fun start(context: Context) {
      val intent =
          Intent(context, StreamingService::class.java).apply { `package` = context.packageName }
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      val intent =
          Intent(context, StreamingService::class.java).apply { `package` = context.packageName }
      context.stopService(intent)
    }
  }

  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    Log.d(TAG, "Service created")
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      Log.d(TAG, "Stop tapped in notification")
      _stopRequests.tryEmit(Unit)
      stopSelf()
      return START_NOT_STICKY
    }

    Log.d(TAG, "Service started")
    val notification = createNotification()
    try {
      startForeground(
          NOTIFICATION_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
              ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
      )
    } catch (e: Exception) {
      // E.g. microphone permission missing: keep at least the glasses connection alive.
      Log.w(TAG, "Foreground with microphone type refused, retrying without it", e)
      startForeground(
          NOTIFICATION_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
      )
    }

    acquireWakeLock()

    // Not sticky: if Android ever kills the app, do not bring back an empty notification.
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    Log.d(TAG, "Service destroyed")
    releaseWakeLock()
    super.onDestroy()
  }

  private fun createNotificationChannel() {
    val channel =
        NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            )
            .apply {
              description = "Shown while VisionClaw is listening and watching through your glasses"
              setShowBadge(false)
            }
    val notificationManager = getSystemService(NotificationManager::class.java)
    notificationManager.createNotificationChannel(channel)
  }

  private fun createNotification(): Notification {
    val openAppIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
              flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    val stopIntent =
        PendingIntent.getService(
            this,
            1,
            Intent(this, StreamingService::class.java).apply {
              action = ACTION_STOP
              `package` = packageName
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("VisionClaw is running")
        .setContentText("Glasses connected. Tap to open, or Stop to end.")
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .setContentIntent(openAppIntent)
        .addAction(0, "Stop", stopIntent)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()
  }

  private fun acquireWakeLock() {
    if (wakeLock == null) {
      val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock =
          powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            acquire(WAKELOCK_MAX_MS)
          }
      Log.d(TAG, "WakeLock acquired")
    }
  }

  private fun releaseWakeLock() {
    wakeLock?.let {
      if (it.isHeld) {
        it.release()
        Log.d(TAG, "WakeLock released")
      }
    }
    wakeLock = null
  }
}
