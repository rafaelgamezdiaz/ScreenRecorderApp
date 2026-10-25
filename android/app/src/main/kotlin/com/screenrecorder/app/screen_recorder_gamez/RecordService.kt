package com.screenrecorder.app.screen_recorder_gamez

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File

class RecordService : Service() {

    companion object {
        const val ACTION_START = "com.screenrecorder.app.ACTION_START"
        const val ACTION_STOP = "com.screenrecorder.app.ACTION_STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DPI = "extra_dpi"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_BITRATE = "extra_bitrate"
        const val EXTRA_RECORD_MIC = "extra_record_mic"
        const val EXTRA_RECORD_INTERNAL = "extra_record_internal"
        const val EXTRA_OUTPUT_PATH = "extra_output_path"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_record_channel"

        var isRecordingActive = false
            private set

        var listener: ScreenRecorder.RecordListener? = null
    }

    private var mediaProjection: MediaProjection? = null
    private var screenRecorder: ScreenRecorder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }

        if (action == ACTION_START && !isRecordingActive) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA)
            }

            if (resultCode != Activity.RESULT_OK || data == null) {
                listener?.onError("Credenciales de proyección de pantalla inválidas")
                stopSelf()
                return START_NOT_STICKY
            }

            val notification = buildNotification("Grabación en curso...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)

            val width = intent.getIntExtra(EXTRA_WIDTH, 1080)
            val height = intent.getIntExtra(EXTRA_HEIGHT, 1920)
            val dpi = intent.getIntExtra(EXTRA_DPI, 400)
            val fps = intent.getIntExtra(EXTRA_FPS, 30)
            val bitrate = intent.getIntExtra(EXTRA_BITRATE, 6000000)
            val recordMic = intent.getBooleanExtra(EXTRA_RECORD_MIC, true)
            val recordInternal = intent.getBooleanExtra(EXTRA_RECORD_INTERNAL, true)
            val outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH)
                ?: "${cacheDir.absolutePath}/rec_${System.currentTimeMillis()}.mp4"

            val outputFile = File(outputPath)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val mp = mediaProjection
                if (mp == null) {
                    listener?.onError("No se pudo inicializar MediaProjection")
                    stopSelf()
                    return START_NOT_STICKY
                }

                screenRecorder = ScreenRecorder(
                    context = this,
                    mediaProjection = mp,
                    width = width,
                    height = height,
                    dpi = dpi,
                    fps = fps,
                    videoBitrate = bitrate,
                    recordMic = recordMic,
                    recordInternal = recordInternal,
                    outputFile = outputFile,
                    listener = object : ScreenRecorder.RecordListener {
                        override fun onStarted() {
                            isRecordingActive = true
                            listener?.onStarted()
                        }

                        override fun onStopped(path: String) {
                            isRecordingActive = false
                            listener?.onStopped(path)
                            stopSelf()
                        }

                        override fun onError(message: String) {
                            isRecordingActive = false
                            listener?.onError(message)
                            stopSelf()
                        }

                        override fun onDurationUpdate(seconds: Long) {
                            listener?.onDurationUpdate(seconds)
                        }
                    }
                )
                screenRecorder?.start()
            } else {
                listener?.onError("Se requiere Android 10 (API 29) o superior")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_NOT_STICKY
    }

    private fun stopRecording() {
        if (!isRecordingActive) {
            stopSelf()
            return
        }
        screenRecorder?.stop()
        mediaProjection?.stop()
        mediaProjection = null
        isRecordingActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Recorder Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificación persistente de grabación de pantalla y audio"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Detener", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        if (isRecordingActive) {
            stopRecording()
        }
        super.onDestroy()
    }
}
