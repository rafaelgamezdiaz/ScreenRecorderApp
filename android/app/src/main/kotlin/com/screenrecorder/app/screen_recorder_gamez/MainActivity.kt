package com.screenrecorder.app.screen_recorder_gamez

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val methodChannelName = "com.screenrecorder.app/recorder"
    private val eventChannelName = "com.screenrecorder.app/recorder_events"
    private val requestMediaProjection = 1002

    private var pendingMethodResult: MethodChannel.Result? = null
    private var pendingConfig: Map<String, Any>? = null
    private var eventSink: EventChannel.EventSink? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, methodChannelName)
            .setMethodCallHandler { call, result ->
                handleMethodCall(call, result)
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, eventChannelName)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            })

        RecordService.listener = object : ScreenRecorder.RecordListener {
            override fun onStarted() {
                runOnUiThread {
                    eventSink?.success(mapOf("event" to "onStarted"))
                }
            }

            override fun onStopped(outputPath: String) {
                runOnUiThread {
                    eventSink?.success(mapOf("event" to "onStopped", "path" to outputPath))
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    eventSink?.success(mapOf("event" to "onError", "message" to message))
                }
            }

            override fun onDurationUpdate(seconds: Long) {
                runOnUiThread {
                    eventSink?.success(mapOf("event" to "onDuration", "seconds" to seconds))
                }
            }
        }
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startRecording" -> {
                if (RecordService.isRecordingActive) {
                    result.error("ALREADY_RECORDING", "Ya hay una grabación en curso", null)
                    return
                }

                val config = call.arguments as? Map<String, Any> ?: emptyMap()
                pendingConfig = config
                pendingMethodResult = result

                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val captureIntent = mpManager.createScreenCaptureIntent()
                startActivityForResult(captureIntent, requestMediaProjection)
            }
            "stopRecording" -> {
                if (!RecordService.isRecordingActive) {
                    result.success(true)
                    return
                }
                val stopIntent = Intent(this, RecordService::class.java).apply {
                    action = RecordService.ACTION_STOP
                }
                startService(stopIntent)
                result.success(true)
            }
            "isRecording" -> {
                result.success(RecordService.isRecordingActive)
            }
            else -> result.notImplemented()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == requestMediaProjection) {
            val result = pendingMethodResult
            pendingMethodResult = null

            if (resultCode == Activity.RESULT_OK && data != null) {
                val config = pendingConfig ?: emptyMap()

                val metrics = DisplayMetrics()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display?.getRealMetrics(metrics)
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.getRealMetrics(metrics)
                }

                val width = (config["width"] as? Number)?.toInt() ?: metrics.widthPixels
                val height = (config["height"] as? Number)?.toInt() ?: metrics.heightPixels
                val dpi = (config["dpi"] as? Number)?.toInt() ?: metrics.densityDpi
                val fps = (config["fps"] as? Number)?.toInt() ?: 30
                val bitrate = (config["bitrate"] as? Number)?.toInt() ?: 6000000
                val recordMic = (config["recordMic"] as? Boolean) ?: true
                val recordInternal = (config["recordInternal"] as? Boolean) ?: true
                val outputPath = config["outputPath"] as? String

                val serviceIntent = Intent(this, RecordService::class.java).apply {
                    action = RecordService.ACTION_START
                    putExtra(RecordService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(RecordService.EXTRA_DATA, data)
                    putExtra(RecordService.EXTRA_WIDTH, width)
                    putExtra(RecordService.EXTRA_HEIGHT, height)
                    putExtra(RecordService.EXTRA_DPI, dpi)
                    putExtra(RecordService.EXTRA_FPS, fps)
                    putExtra(RecordService.EXTRA_BITRATE, bitrate)
                    putExtra(RecordService.EXTRA_RECORD_MIC, recordMic)
                    putExtra(RecordService.EXTRA_RECORD_INTERNAL, recordInternal)
                    if (outputPath != null) {
                        putExtra(RecordService.EXTRA_OUTPUT_PATH, outputPath)
                    }
                }

                ContextCompat.startForegroundService(this, serviceIntent)
                result?.success(true)
            } else {
                result?.error("CANCELLED", "El usuario canceló el permiso de captura de pantalla", null)
            }
        }
    }

    override fun onDestroy() {
        RecordService.listener = null
        super.onDestroy()
    }
}
