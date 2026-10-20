package io.github.tsarubucreate.audioswitcher.transcription

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit

/**
 * 文字起こしの設定（入・切、保存先のフォルダ）と、再生サービスから画面へ知らせる状態。
 * 画面と再生サービスが同じ記録を見る。
 */
class TranscriptionSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** 保存先のフォルダ（ACTION_OPEN_DOCUMENT_TREE で選んだもの）。未設定なら null。 */
    var folder: Uri?
        get() = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)
        set(value) = prefs.edit { if (value == null) remove(KEY_FOLDER) else putString(KEY_FOLDER, value.toString()) }

    /** 画面に出す今の状態（「文字起こし中: …」など）。止まっていれば null。 */
    var status: String?
        get() = prefs.getString(KEY_STATUS, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_STATUS) else putString(KEY_STATUS, value) }

    /** 状態に問題があるか（画面で赤にする）。 */
    var statusIsProblem: Boolean
        get() = prefs.getBoolean(KEY_STATUS_PROBLEM, false)
        set(value) = prefs.edit { putBoolean(KEY_STATUS_PROBLEM, value) }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val KEY_ENABLED = "enabled"
        private const val PREFS_NAME = "transcription"
        const val KEY_FOLDER = "folder"
        private const val KEY_STATUS = "status"
        private const val KEY_STATUS_PROBLEM = "status_problem"

        /** 画面に出すフォルダの名前（例: `Documents/SyncVault/30_Projects/Spaceまとめ`）。 */
        fun folderLabel(folder: Uri): String {
            val id = DocumentsContract.getTreeDocumentId(folder)
            return id.substringAfter(':', id)
        }
    }
}
