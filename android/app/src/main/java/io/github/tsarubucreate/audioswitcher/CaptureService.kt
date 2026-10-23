package io.github.tsarubucreate.audioswitcher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import io.github.tsarubucreate.audioswitcher.transcription.TranscriptionController
import io.github.tsarubucreate.audioswitcher.transcription.TranscriptionSettings

/**
 * 端末の中で鳴っている X の音を取り込み、文字に起こす前景サービス（docs/構成.md の 2.7）。
 *
 * 画面で「端末の音」を選んで「再生」を押し、Android の画面のキャプチャの確かめで許可すると、[start] で始まる。
 * 取り込み（MediaProjection）は、種類が mediaProjection の前景サービスの中でしか始められない。
 * X のアプリの番号（uid）で絞り、X の音だけを 16kHz・モノラルで受け取って [TranscriptionController] へ渡す。
 * 画面を消しても続けるため、CPU と Wi-Fi のロックを持つ。止まるのは、画面か通知の「停止」と、
 * Android の画面共有を止めたとき（取り込みの許可が終わる）。
 */
class CaptureService : Service() {
    private val state by lazy { CaptureState(this) }
    private val transcriptionSettings by lazy { TranscriptionSettings(this) }
    private val transcription by lazy { TranscriptionController(this, transcriptionSettings) }
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var reader: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        // 画面は Android 10 以降でだけ「端末の音」を選ばせる。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            stopCapture()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        startInForeground()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) return fail("取り込みの許可を受け取れませんでした")
        val created = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
            ?: return fail("取り込みを始められませんでした")
        created.registerCallback(object : MediaProjection.Callback() {
            // Android の画面共有を止めたとき（通知や上の帯の「共有を停止」など）。
            override fun onStop() {
                Log.i(TAG, "取り込みの許可が終わったので止める")
                stopCapture()
            }
        }, Handler(Looper.getMainLooper()))
        projection = created
        if (!transcription.start()) return fail("文字起こしを始められませんでした（入・保存先・方式を確かめてください）")
        val built = buildRecord(created) ?: return START_NOT_STICKY
        record = built
        holdLocks()
        running = true
        state.markStarted(SystemClock.elapsedRealtime())
        AudioWidget.update(this)
        reader = Thread({ readLoop(built) }, "capture-read").apply { start() }
        Log.i(TAG, "端末の音の取り込みを始めた")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        transcription.release()
        super.onDestroy()
    }

    /** X の音だけを 16kHz・モノラルで受け取る形を作る。作れなければ知らせて止め、null。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission") // 録音の許可は、画面で確かめてからこのサービスを始める。
    private fun buildRecord(projection: MediaProjection): AudioRecord? {
        val uid = try {
            packageManager.getApplicationInfo(X_PACKAGE, 0).uid
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "X のアプリが見つからない", e)
            fail("X のアプリが見つかりません")
            return null
        }
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUid(uid)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        return try {
            AudioRecord.Builder().setAudioFormat(format).setAudioPlaybackCaptureConfig(config).build()
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "取り込みの形を作れなかった", e)
            fail("端末の音を取り込めませんでした")
            null
        }
    }

    /** 取り込んだ音を 0.1 秒ずつ読み、文字起こしへ渡す（取り込み専用のスレッドで）。 */
    private fun readLoop(record: AudioRecord) {
        val buffer = ShortArray(RATE / 10)
        try {
            record.startRecording()
            while (running) {
                val n = record.read(buffer, 0, buffer.size)
                if (n < 0) {
                    Log.w(TAG, "取り込んだ音を読めなかった（$n）。止める")
                    Handler(Looper.getMainLooper()).post { fail("端末の音を読めなくなりました") }
                    return
                }
                if (n > 0) transcription.feedPcm(buffer.copyOf(n), RATE, 1)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "取り込みを続けられなかった", e)
            Handler(Looper.getMainLooper()).post { fail("端末の音の取り込みが止まりました") }
        }
    }

    /** 止める。何度呼んでもよい（画面・通知の停止と、取り込みの許可の終わりが重なっても）。 */
    private fun stopCapture() {
        val wasRunning = running
        running = false
        reader?.join(READ_JOIN_MS)
        reader = null
        record?.run {
            try {
                stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "取り込みを止めるときに失敗した（止めたものとして続ける）", e)
            }
            release()
        }
        record = null
        projection?.stop()
        projection = null
        transcription.stop()
        releaseLocks()
        state.clear()
        AudioWidget.update(this)
        if (wasRunning) Log.i(TAG, "端末の音の取り込みを止めた")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String): Int {
        Log.w(TAG, message)
        state.problem = message
        stopCapture()
        return START_NOT_STICKY
    }

    private fun holdLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioSwitcher:capture")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        @Suppress("DEPRECATION") // 画面を消しても送り続けるため（Android 10 以降は LOW_LATENCY もあるが、送りには高い性能を選ぶ）。
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AudioSwitcher:capture")
            .apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "端末の音の文字起こし", NotificationManager.IMPORTANCE_LOW))
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("端末の音を文字起こし中")
            .setContentText("X の音を取り込んでいます")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "io.github.tsarubucreate.audioswitcher.STOP_CAPTURE"
        const val X_PACKAGE = "com.twitter.android"
        private const val TAG = "CaptureService"
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 2002
        private const val RATE = 16_000
        private const val READ_JOIN_MS = 1_000L

        // 記録は最大6時間（docs/構成.md の 2.5）。止め忘れてもロックが残り続けないよう、余裕を見て7時間で切れるようにする。
        private const val WAKE_LOCK_TIMEOUT_MS = 7 * 60 * 60 * 1000L

        /** 画面の「停止」から止める。 */
        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}

/**
 * 端末の音の取り込みの状態（取り込みを始めた時刻と、止まった理由）。取り込みサービスが書き、画面が読む。
 * 時刻は単調な時計（`SystemClock.elapsedRealtime`）。
 */
class CaptureState(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 取り込みを始めた時刻。止まっていれば null。 */
    fun startedAt(): Long? = if (prefs.contains(KEY_STARTED_AT)) prefs.getLong(KEY_STARTED_AT, 0) else null

    fun markStarted(nowElapsedRealtime: Long) = prefs.edit {
        putLong(KEY_STARTED_AT, nowElapsedRealtime)
        remove(KEY_PROBLEM)
    }

    fun clear() = prefs.edit { remove(KEY_STARTED_AT) }

    /** 取り込みが止まった理由（画面に赤で出す）。無ければ null。 */
    var problem: String?
        get() = prefs.getString(KEY_PROBLEM, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_PROBLEM) else putString(KEY_PROBLEM, value) }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private companion object {
        const val PREFS_NAME = "capture"
        const val KEY_STARTED_AT = "started_at_elapsed_realtime"
        const val KEY_PROBLEM = "problem"
    }
}
