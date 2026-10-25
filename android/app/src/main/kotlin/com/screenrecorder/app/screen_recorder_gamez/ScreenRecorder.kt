package com.screenrecorder.app.screen_recorder_gamez

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.math.sqrt

@RequiresApi(Build.VERSION_CODES.Q)
class ScreenRecorder(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val fps: Int,
    private val videoBitrate: Int,
    private val recordMic: Boolean,
    private val recordInternal: Boolean,
    private val outputFile: File,
    private val listener: RecordListener?
) {
    interface RecordListener {
        fun onStarted()
        fun onStopped(outputPath: String)
        fun onError(message: String)
        fun onDurationUpdate(seconds: Long)
    }

    private val tag = "ScreenRecorder"

    @Volatile
    private var isRecording = false

    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private val muxerLock = Object()

    private var micRecord: AudioRecord? = null
    private var internalRecord: AudioRecord? = null

    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var aec: AcousticEchoCanceler? = null

    private val micQueue = ConcurrentLinkedQueue<ShortArray>()
    private val internalQueue = ConcurrentLinkedQueue<ShortArray>()

    private var videoThread: Thread? = null
    private var micCaptureThread: Thread? = null
    private var internalCaptureThread: Thread? = null
    private var audioMixerThread: Thread? = null
    private var audioDrainThread: Thread? = null
    private var timerThread: Thread? = null

    private val hasAudio = recordMic || recordInternal
    private val sampleRate = 44100
    private val channelCount = 2
    private val audioBitrate = 128000

    private var firstVideoPts = -1L
    private var lastVideoPts = 0L

    fun start() {
        if (isRecording) return
        isRecording = true

        try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) {
                outputFile.delete()
            }

            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            setupVideoEncoder()

            if (hasAudio) {
                setupAudio()
            }

            startDrainThreads()
            startTimer()

            listener?.onStarted()
        } catch (e: Exception) {
            Log.e(tag, "Error starting recorder: ${e.message}", e)
            listener?.onError("Error al iniciar grabación: ${e.message}")
            stop()
        }
    }

    private fun setupVideoEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_CAPTURE_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()

        // El codificador DEBE iniciarse antes de enlazarlo a la pantalla virtual
        encoder.start()
        videoEncoder = encoder

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenRecorder-VD",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        )
    }

    private fun setupAudio() {
        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        audioEncoder = encoder

        if (recordMic) {
            try {
                val micMinBuffer = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = (micMinBuffer * 2).coerceAtLeast(4096)

                // Priorizar VOICE_RECOGNITION para habilitar procesamiento direccional y de voz del dispositivo
                var record: AudioRecord? = null
                try {
                    val voiceRecord = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                    if (voiceRecord.state == AudioRecord.STATE_INITIALIZED) {
                        record = voiceRecord
                        Log.i(tag, "AudioRecord inicializado con VOICE_RECOGNITION")
                    } else {
                        voiceRecord.release()
                    }
                } catch (e: Exception) {
                    Log.w(tag, "VOICE_RECOGNITION no disponible, usando fallback: ${e.message}")
                }

                // Fallback a fuente MIC estándar si VOICE_RECOGNITION no estuviese disponible
                if (record == null) {
                    val standardRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                    if (standardRecord.state == AudioRecord.STATE_INITIALIZED) {
                        record = standardRecord
                        Log.i(tag, "AudioRecord inicializado con MIC estándar")
                    } else {
                        standardRecord.release()
                    }
                }

                if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                    micRecord = record
                    val sessionId = record.audioSessionId

                    // Habilitar cancelación y supresión de ruido por hardware
                    if (NoiseSuppressor.isAvailable()) {
                        try {
                            noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                                enabled = true
                            }
                            Log.i(tag, "Hardware NoiseSuppressor habilitado")
                        } catch (e: Exception) {
                            Log.w(tag, "Error activando NoiseSuppressor: ${e.message}")
                        }
                    }

                    // Habilitar control automático de ganancia por hardware
                    if (AutomaticGainControl.isAvailable()) {
                        try {
                            agc = AutomaticGainControl.create(sessionId)?.apply {
                                enabled = true
                            }
                            Log.i(tag, "Hardware AutomaticGainControl habilitado")
                        } catch (e: Exception) {
                            Log.w(tag, "Error activando AutomaticGainControl: ${e.message}")
                        }
                    }

                    // Habilitar cancelador de eco acústico si está disponible
                    if (AcousticEchoCanceler.isAvailable()) {
                        try {
                            aec = AcousticEchoCanceler.create(sessionId)?.apply {
                                enabled = true
                            }
                            Log.i(tag, "Hardware AcousticEchoCanceler habilitado")
                        } catch (e: Exception) {
                            Log.w(tag, "Error activando AcousticEchoCanceler: ${e.message}")
                        }
                    }
                } else {
                    Log.w(tag, "Mic AudioRecord no pudo ser inicializado")
                }
            } catch (e: Exception) {
                Log.w(tag, "Fallo al inicializar micrófono: ${e.message}")
            }
        }

        if (recordInternal) {
            try {
                val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val intMinBuffer = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = (intMinBuffer * 2).coerceAtLeast(8192)

                val record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(playbackConfig)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    internalRecord = record
                } else {
                    record.release()
                    Log.w(tag, "Internal AudioRecord no pudo ser inicializado")
                }
            } catch (e: Exception) {
                Log.w(tag, "Fallo al inicializar audio interno: ${e.message}")
            }
        }
    }

    private fun startDrainThreads() {
        videoThread = thread(name = "VideoDrainThread") {
            drainVideo()
        }

        if (hasAudio) {
            audioDrainThread = thread(name = "AudioDrainThread") {
                drainAudio()
            }

            if (micRecord != null) {
                micCaptureThread = thread(name = "MicCaptureThread") {
                    captureMicAudio()
                }
            }

            if (internalRecord != null) {
                internalCaptureThread = thread(name = "InternalCaptureThread") {
                    captureInternalAudio()
                }
            }

            audioMixerThread = thread(name = "AudioMixerThread") {
                mixAndFeedAudio()
            }
        }
    }

    private fun checkStartMuxer() {
        synchronized(muxerLock) {
            if (!muxerStarted) {
                val videoReady = videoTrackIndex >= 0
                val audioReady = !hasAudio || audioTrackIndex >= 0
                if (videoReady && audioReady) {
                    try {
                        mediaMuxer?.start()
                        muxerStarted = true
                        Log.i(tag, "MediaMuxer iniciado correctamente con pistas: video=$videoTrackIndex, audio=$audioTrackIndex")
                        muxerLock.notifyAll()
                    } catch (e: Exception) {
                        Log.e(tag, "Fallo al iniciar MediaMuxer: ${e.message}", e)
                    }
                }
            }
        }
    }

    private fun drainVideo() {
        val encoder = videoEncoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRecording) {
            val outputBufferIndex = try {
                encoder.dequeueOutputBuffer(bufferInfo, 10000)
            } catch (e: Exception) {
                break
            }

            when (outputBufferIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = encoder.outputFormat
                    synchronized(muxerLock) {
                        videoTrackIndex = mediaMuxer?.addTrack(newFormat) ?: -1
                        checkStartMuxer()
                    }
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                else -> {
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            synchronized(muxerLock) {
                                while (!muxerStarted && isRecording) {
                                    try {
                                        muxerLock.wait(50)
                                    } catch (_: InterruptedException) {}
                                }
                                if (muxerStarted && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    if (firstVideoPts < 0) {
                                        firstVideoPts = bufferInfo.presentationTimeUs
                                    }
                                    var normalizedPts = bufferInfo.presentationTimeUs - firstVideoPts
                                    if (normalizedPts <= lastVideoPts && lastVideoPts > 0) {
                                        normalizedPts = lastVideoPts + 1000
                                    }
                                    lastVideoPts = normalizedPts
                                    bufferInfo.presentationTimeUs = normalizedPts

                                    try {
                                        mediaMuxer?.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                                    } catch (e: Exception) {
                                        Log.e(tag, "Error escribiendo muestra de video: ${e.message}")
                                    }
                                }
                            }
                        }
                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            }
        }
    }

    private fun captureMicAudio() {
        val record = micRecord ?: return
        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.e(tag, "Error iniciando captura de micrófono: ${e.message}")
            return
        }

        val chunkSize = 1024 // 1024 muestras mono
        while (isRecording) {
            val buffer = ShortArray(chunkSize)
            val read = record.read(buffer, 0, chunkSize)
            if (read > 0) {
                if (micQueue.size > 20) {
                    micQueue.poll() // Descartar muestras viejas para evitar latencia
                }
                micQueue.offer(buffer)
            } else if (read < 0) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {}
            }
        }

        try {
            record.stop()
            releaseAudioFx()
            record.release()
        } catch (_: Exception) {}
    }

    private fun captureInternalAudio() {
        val record = internalRecord ?: return
        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.e(tag, "Error iniciando captura interna: ${e.message}")
            return
        }

        val chunkSize = 2048 // 1024 frames estéreo = 2048 muestras
        while (isRecording) {
            val buffer = ShortArray(chunkSize)
            val read = record.read(buffer, 0, chunkSize)
            if (read > 0) {
                if (internalQueue.size > 20) {
                    internalQueue.poll()
                }
                internalQueue.offer(buffer)
            } else if (read < 0) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {}
            }
        }

        try {
            record.stop()
            record.release()
        } catch (_: Exception) {}
    }

    private fun mixAndFeedAudio() {
        val encoder = audioEncoder ?: return
        val framesPerChunk = 1024
        val samplesPerChunk = framesPerChunk * channelCount // 2048 shorts
        val byteBuffer = ByteBuffer.allocateDirect(samplesPerChunk * 2).order(ByteOrder.LITTLE_ENDIAN)

        var totalFramesWritten = 0L
        val frameDurationMs = (framesPerChunk * 1000L) / sampleRate // ~23 ms

        // Procesamiento DSP de voz en tiempo real
        var gateGain = 0.0f
        val gateThresholdRms = 350.0 // Atenúa ruido de fondo ambiental y respiración
        val voiceGainBoost = 1.6f    // Ganancia limpia para voz clara y potente

        while (isRecording) {
            val startTime = SystemClock.elapsedRealtime()

            val micBuffer = micQueue.poll()
            val intBuffer = internalQueue.poll()

            // Detección de energía RMS de voz para compuerta dinámica suave
            if (micBuffer != null && micBuffer.isNotEmpty()) {
                var sumSq = 0.0
                for (s in micBuffer) {
                    sumSq += s * s
                }
                val rms = sqrt(sumSq / micBuffer.size)

                if (rms >= gateThresholdRms) {
                    gateGain += (1.0f - gateGain) * 0.45f // Ataque ágil al comenzar a hablar
                } else {
                    gateGain += (0.05f - gateGain) * 0.08f // Expansor suave hacia silencio de fondo (-26 dB)
                }
            } else {
                gateGain += (0.0f - gateGain) * 0.1f
            }

            // Auto-ducking: atenuación de audio interno mientras se habla para priorizar la locución
            val duckingFactor = 1.0f - (gateGain * 0.35f)

            byteBuffer.clear()
            for (i in 0 until framesPerChunk) {
                val rawMic = if (micBuffer != null && i < micBuffer.size) micBuffer[i].toInt() else 0
                val processedMic = (rawMic * gateGain * voiceGainBoost).toFloat()

                val rawLeft = if (intBuffer != null && (i * 2) < intBuffer.size) intBuffer[i * 2].toInt() else 0
                val rawRight = if (intBuffer != null && (i * 2 + 1) < intBuffer.size) intBuffer[i * 2 + 1].toInt() else 0

                val intLeft = rawLeft * duckingFactor
                val intRight = rawRight * duckingFactor

                // Limitador no lineal para evitar saturación y distorsión digital
                val mixedLeft = softLimit(intLeft + processedMic)
                val mixedRight = softLimit(intRight + processedMic)

                byteBuffer.putShort(mixedLeft.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                byteBuffer.putShort(mixedRight.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
            byteBuffer.flip()

            val bytesToWrite = samplesPerChunk * 2
            val inputBufferIndex = try {
                encoder.dequeueInputBuffer(10000)
            } catch (e: Exception) {
                break
            }

            if (inputBufferIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(byteBuffer)
                    val ptsUs = (totalFramesWritten * 1_000_000L) / sampleRate
                    totalFramesWritten += framesPerChunk
                    encoder.queueInputBuffer(inputBufferIndex, 0, bytesToWrite, ptsUs, 0)
                }
            }

            val elapsed = SystemClock.elapsedRealtime() - startTime
            val sleepTime = frameDurationMs - elapsed
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime)
                } catch (_: InterruptedException) {}
            }
        }
    }

    private fun drainAudio() {
        val encoder = audioEncoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRecording) {
            val outputBufferIndex = try {
                encoder.dequeueOutputBuffer(bufferInfo, 10000)
            } catch (e: Exception) {
                break
            }

            when (outputBufferIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = encoder.outputFormat
                    synchronized(muxerLock) {
                        audioTrackIndex = mediaMuxer?.addTrack(newFormat) ?: -1
                        checkStartMuxer()
                    }
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                else -> {
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            synchronized(muxerLock) {
                                while (!muxerStarted && isRecording) {
                                    try {
                                        muxerLock.wait(50)
                                    } catch (_: InterruptedException) {}
                                }
                                if (muxerStarted && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    try {
                                        mediaMuxer?.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
                                    } catch (e: Exception) {
                                        Log.e(tag, "Error escribiendo muestra de audio: ${e.message}")
                                    }
                                }
                            }
                        }
                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            }
        }
    }

    private fun startTimer() {
        timerThread = thread(name = "RecordTimerThread") {
            var seconds = 0L
            while (isRecording) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
                if (isRecording) {
                    seconds++
                    listener?.onDurationUpdate(seconds)
                }
            }
        }
    }

    private fun softLimit(sample: Float): Float {
        val threshold = 28000f
        return if (sample > threshold) {
            threshold + (sample - threshold) / (1f + (sample - threshold) / 5000f)
        } else if (sample < -threshold) {
            -threshold + (sample + threshold) / (1f + (-sample - threshold) / 5000f)
        } else {
            sample
        }
    }

    private fun releaseAudioFx() {
        try {
            noiseSuppressor?.release()
            noiseSuppressor = null
        } catch (_: Exception) {}
        try {
            agc?.release()
            agc = null
        } catch (_: Exception) {}
        try {
            aec?.release()
            aec = null
        } catch (_: Exception) {}
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false

        timerThread?.interrupt()
        releaseAudioFx()

        try {
            micCaptureThread?.join(500)
        } catch (_: Exception) {}

        try {
            internalCaptureThread?.join(500)
        } catch (_: Exception) {}

        try {
            audioMixerThread?.join(500)
        } catch (_: Exception) {}

        try {
            videoThread?.join(500)
        } catch (_: Exception) {}

        try {
            audioDrainThread?.join(500)
        } catch (_: Exception) {}

        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (e: Exception) {
            Log.w(tag, "Error liberando virtualDisplay: ${e.message}")
        }

        try {
            videoEncoder?.stop()
            videoEncoder?.release()
            videoEncoder = null
        } catch (e: Exception) {
            Log.w(tag, "Error liberando videoEncoder: ${e.message}")
        }

        try {
            audioEncoder?.stop()
            audioEncoder?.release()
            audioEncoder = null
        } catch (e: Exception) {
            Log.w(tag, "Error liberando audioEncoder: ${e.message}")
        }

        synchronized(muxerLock) {
            try {
                if (muxerStarted) {
                    mediaMuxer?.stop()
                }
                mediaMuxer?.release()
                mediaMuxer = null
                muxerStarted = false
            } catch (e: Exception) {
                Log.w(tag, "Error deteniendo mediaMuxer: ${e.message}")
            }
        }

        listener?.onStopped(outputFile.absolutePath)
    }
}
