package io.github.tsarubucreate.audioswitcher

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** 画面の設定（接続先・画面の色・音の切り替え）。画面とホーム画面のウィジェットが同じ記録を見る。 */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 接続先（PC の IP:ポート）。未設定なら空。 */
    var address: String
        get() = prefs.getString(KEY_ADDRESS, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ADDRESS, value) }

    /** 画面の右上の切り替えで選んだ色。一度も選んでいなければ null（端末の設定に合わせる。docs/画面.md）。 */
    var darkTheme: Boolean?
        get() = if (prefs.contains(KEY_DARK_THEME)) prefs.getBoolean(KEY_DARK_THEME, false) else null
        set(value) = prefs.edit { if (value == null) remove(KEY_DARK_THEME) else putBoolean(KEY_DARK_THEME, value) }

    /** 音の切り替えで「端末の音」を選んでいる（docs/構成.md の 2.7）。 */
    var deviceSource: Boolean
        get() = prefs.getBoolean(KEY_DEVICE_SOURCE, false)
        set(value) = prefs.edit { putBoolean(KEY_DEVICE_SOURCE, value) }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_ADDRESS = "address"
        const val KEY_DARK_THEME = "dark_theme"
        const val KEY_DEVICE_SOURCE = "device_source"
    }
}
