package io.github.tsarubucreate.audioswitcher.debug

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.tsarubucreate.audioswitcher.transcription.Wav
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * [CaptureProbeActivity] の取り込みを行う前景サービス（Debug 版だけ）。
 *
 * 取り込み（MediaProjection）は、種類が mediaProjection の前景サービスの中でしか始められない。
 * 16kHz・16bit・モノラルで 20 秒取り込み、1 秒ごとの音の大きさ（dBFS）を出して、WAV に置く。
 */
class CaptureProbeService : Service() {
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 画面（CaptureProbeActivity）が Android 10 以降でだけ始める。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val onlyX = intent?.getBooleanExtra(EXTRA_ONLY_X, true) ?: true
        if (data == null) {
            line("取り込みの許可の中身が無い")
            stopSelf()
            return START_NOT_STICKY
        }
        val created = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        if (created == null) {
            line("取り込みを始められなかった（getMediaProjection が null）")
            stopSelf()
            return START_NOT_STICKY
        }
        created.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = line("取り込みの許可が終わった")
        }, Handler(Looper.getMainLooper()))
        projection = created
        Thread({ capture(created, onlyX) }, "capture-probe").start()
        return START_NOT_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission") // 録音の許可は画面で確かめてから、このサービスを始める。
    private fun capture(projection: MediaProjection, onlyX: Boolean) {
        val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
        if (onlyX) {
            val uid = try {
                packageManager.getApplicationInfo(X_PACKAGE, 0).uid
            } catch (e: PackageManager.NameNotFoundException) {
                line("X のアプリが見つからない（${e.message}）")
                finish()
                return
            }
            builder.addMatchingUid(uid)
            line("X だけを取り込む（uid=$uid）")
        } else {
            line("全部のアプリを取り込む")
        }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setAudioPlaybackCaptureConfig(builder.build())
            .build()
        val all = ShortArray(RATE * SECONDS)
        try {
            record.startRecording()
            line("取り込み開始（状態=${record.recordingState}）")
            for (second in 0 until SECONDS) {
                var read = 0
                while (read < RATE) {
                    val n = record.read(all, second * RATE + read, RATE - read)
                    if (n <= 0) {
                        line("読めなかった（$n）")
                        break
                    }
                    read += n
                }
                line("${second + 1} 秒目: ${"%.1f".format(dbfs(all, second * RATE, RATE))} dBFS")
            }
        } finally {
            record.stop()
            record.release()
        }
        val file = File(getExternalFilesDir(null), if (onlyX) "capture-x.wav" else "capture-all.wav")
        file.writeBytes(Wav.encode(all))
        line("置いた: ${file.path}")
        finish()
    }

    private fun finish() {
        projection?.stop()
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "取り込みの確かめ", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("端末の音を取り込んでいます（確かめ）")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun dbfs(samples: ShortArray, offset: Int, count: Int): Double {
        var sum = 0.0
        for (i in offset until offset + count) sum += samples[i].toDouble() * samples[i]
        val rms = sqrt(sum / count)
        return if (rms <= 0) -120.0 else 20 * log10(rms / Short.MAX_VALUE)
    }

    private fun line(text: String) {
        Log.i(TAG, text)
        onLine?.invoke(text)
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_ONLY_X = "only_x"
        private const val X_PACKAGE = "com.twitter.android"
        private const val TAG = "CaptureProbe"
        private const val CHANNEL = "capture_probe"
        private const val NOTIFICATION_ID = 2001
        private const val RATE = 16_000
        private const val SECONDS = 20

        /** 画面へ1行ずつ出す口（画面が開いている間だけ）。 */
        @Volatile var onLine: ((String) -> Unit)? = null
    }
}
