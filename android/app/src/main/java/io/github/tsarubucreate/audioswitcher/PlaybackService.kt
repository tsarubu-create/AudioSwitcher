package io.github.tsarubucreate.audioswitcher

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * PC の音を再生し続ける前景サービス。画面を消しても止まらない。
 *
 * 通知・ロック画面の操作・オーディオフォーカス・Wi-Fi ロックは Media3 に任せる（docs/構成.md の 2.2）。
 * 切れたら [ReconnectPolicy] の間をあけて繋ぎ直す。利用者が停止を押したら繋ぎ直さない。
 * PC には、ペアリングで受け取った鍵を `Authorization` ヘッダで渡す。PC が鍵を認めなければ（401）、
 * 鍵を捨てて止まる（PC 側でペアリングを解除したとき。繋ぎ直さない）。
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val reconnect = ReconnectPolicy()
    private val retry = Runnable { retryNow() }
    private val keyStorage by lazy { KeyStorage(this) }

    override fun onCreate() {
        super.onCreate()

        // ExoPlayer の既定は数十秒ためる。ライブの音なので 0.5〜1.5 秒に縮めて遅れを抑える。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, BUFFER_FOR_PLAYBACK_MS, BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
            .build()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(dataSourceWithKey()).setLoadErrorHandlingPolicy(NoResumeLoadErrorPolicy()),
            )
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(PlayerListener(player))

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .build()
    }

    /** 開くたびに、その時点で保存している鍵をヘッダに付ける（ペアリングし直したあとも作り直さずに済む）。 */
    private fun dataSourceWithKey(): ResolvingDataSource.Factory =
        ResolvingDataSource.Factory(DefaultHttpDataSource.Factory()) { dataSpec ->
            val key = keyStorage.load() ?: return@Factory dataSpec
            dataSpec.withAdditionalHeaders(mapOf("Authorization" to "Bearer $key"))
        }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(retry)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun scheduleRetry(reason: String) {
        val player = session?.player ?: return
        if (!player.playWhenReady) return
        handler.removeCallbacks(retry)
        val delay = reconnect.nextDelayMs()
        Log.i(TAG, "$reason。${delay}ms 後に繋ぎ直す")
        handler.postDelayed(retry, delay)
    }

    private fun retryNow() {
        val player = session?.player ?: return
        val item = player.currentMediaItem ?: return
        if (!player.playWhenReady) return
        // 終わったストリームも、失敗したストリームも、同じ URL を最初から開き直す。
        player.setMediaItem(item)
        player.prepare()
    }

    private inner class PlayerListener(private val player: Player) : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (isUnauthorized(error)) {
                Log.w(TAG, "PC が鍵を認めなかったので、鍵を捨てて止める")
                handler.removeCallbacks(retry)
                keyStorage.clear(KeyStorage.NOTICE_UNPAIRED_BY_PC)
                player.pause()
                player.stop()
                return
            }

            // 繋ぎ直しのたびに出るので、スタックトレースは書かず、いちばん奥の原因だけを残す。
            var cause: Throwable = error
            while (cause.cause != null) cause = cause.cause!!
            scheduleRetry("再生に失敗した（${error.errorCodeName}: ${cause.javaClass.simpleName}: ${cause.message}）")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> reconnect.reset()
                // PC がストリームを閉じた（アプリの終了など）。
                Player.STATE_ENDED -> scheduleRetry("ストリームが終わった")
                else -> Unit
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                handler.removeCallbacks(retry)
                reconnect.reset()
            }
        }
    }

    /**
     * 読み込みが途切れても ExoPlayer の中では再試行しない。
     *
     * ExoPlayer の再試行は HTTP の Range で「続きから」読み直そうとするが、PC の配信はライブなので
     * 続きは無く、新しいストリーム（新しい見出し）が先頭から届く。続きから読むと Ogg の途中に別の
     * ストリームが混ざる。そこでエラーをすぐ表に出し、[ReconnectPolicy] の間をあけて最初から開き直す。
     */
    private class NoResumeLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET
    }

    private fun isUnauthorized(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 401) return true
            cause = cause.cause
        }
        return false
    }

    private companion object {
        const val TAG = "PlaybackService"
        const val MIN_BUFFER_MS = 500
        const val MAX_BUFFER_MS = 1_500
        const val BUFFER_FOR_PLAYBACK_MS = 300
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 500
    }
}
