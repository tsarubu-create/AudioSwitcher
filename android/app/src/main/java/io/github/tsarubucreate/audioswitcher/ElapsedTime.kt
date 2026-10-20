package io.github.tsarubucreate.audioswitcher

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Locale

/** 経過時間の書き方。Android に依らない形にして、単体テストで確かめる。 */
object ElapsedTime {
    /** 1時間未満は「分:秒」（23:45、5:07）、1時間以上は「時:分:秒」（1:23:45）。 */
    fun format(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs.coerceAtLeast(0) / 1000)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }
}

/**
 * 「再生」を押した時刻を覚えておく。経過時間はこの時刻から数える（繋ぎ直す間も数え続け、停止で消す）。
 *
 * 時刻は、端末が起動してからの単調な時計（`SystemClock.elapsedRealtime`）の値で持つ。
 * 壁時計を使うと、時刻合わせで経過時間がずれるため。
 */
class PlaybackStartStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 再生を押した時刻。止まっていれば null。 */
    fun startedAt(): Long? = if (prefs.contains(KEY_STARTED_AT)) prefs.getLong(KEY_STARTED_AT, 0) else null

    fun markStarted(nowElapsedRealtime: Long) = prefs.edit { putLong(KEY_STARTED_AT, nowElapsedRealtime) }

    fun clear() = prefs.edit { remove(KEY_STARTED_AT) }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private companion object {
        const val PREFS_NAME = "playback"
        const val KEY_STARTED_AT = "started_at_elapsed_realtime"
    }
}
