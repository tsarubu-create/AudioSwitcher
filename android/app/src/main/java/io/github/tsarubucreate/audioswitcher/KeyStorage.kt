package io.github.tsarubucreate.audioswitcher

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * PC から受け取った接続の鍵を保存する。Android Keystore の AES 鍵（端末の外に出せない）で暗号化して持つ。
 *
 * 鍵そのものはログに出さない。
 */
class KeyStorage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(key: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        prefs.edit {
            putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString(KEY_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            remove(KEY_NOTICE)
        }
    }

    /** 保存した鍵。無いか、読めなければ null。 */
    fun load(): String? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val data = prefs.getString(KEY_DATA, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            // 端末の初期化などで Keystore の鍵が失われると、もう読めない。捨ててペアリングし直してもらう。
            Log.e(TAG, "保存した鍵を読めなかったので捨てる", e)
            clear(notice = null)
            null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "保存した鍵の形が壊れていたので捨てる", e)
            clear(notice = null)
            null
        }
    }

    fun hasKey(): Boolean = prefs.contains(KEY_DATA)

    /** 鍵を捨てる。[notice] は、次に画面を開いたときに出す知らせ。 */
    fun clear(notice: String?) {
        prefs.edit {
            remove(KEY_IV)
            remove(KEY_DATA)
            if (notice != null) putString(KEY_NOTICE, notice) else remove(KEY_NOTICE)
        }
    }

    fun notice(): String? = prefs.getString(KEY_NOTICE, null)

    /** 知らせだけを消す（読んで次の操作に進んだとき）。 */
    fun clearNotice() = prefs.edit { remove(KEY_NOTICE) }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val NOTICE_UNPAIRED_BY_PC = "PC とのペアリングが解除されました。もう一度ペアリングしてください。"

        private const val TAG = "KeyStorage"
        private const val PREFS_NAME = "pairing"
        private const val KEY_IV = "iv"
        private const val KEY_DATA = "data"
        private const val KEY_NOTICE = "notice"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "audioswitcher_pairing"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
