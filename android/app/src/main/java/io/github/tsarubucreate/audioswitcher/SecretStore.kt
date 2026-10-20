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
 * 秘密（文字起こしの API キーなど）を、Android Keystore の AES 鍵（端末の外に出せない）で暗号化して保存する。
 * 秘密そのものはログに出さない。
 *
 * @param name 保存の名前。保存先と Keystore の鍵の名前に使う（秘密ごとに分ける）。
 */
class SecretStore(context: Context, private val name: String) {
    private val prefs: SharedPreferences = context.getSharedPreferences("secret_$name", Context.MODE_PRIVATE)

    fun save(secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        prefs.edit {
            putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString(KEY_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
    }

    /** 保存した秘密。無いか、読めなければ null。 */
    fun load(): String? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val data = prefs.getString(KEY_DATA, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "保存した秘密（$name）を読めなかったので捨てる", e)
            clear()
            null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "保存した秘密（$name）の形が壊れていたので捨てる", e)
            clear()
            null
        }
    }

    fun has(): Boolean = prefs.contains(KEY_DATA)

    fun clear() = prefs.edit {
        remove(KEY_IV)
        remove(KEY_DATA)
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private fun secretKey(): SecretKey {
        val alias = "audioswitcher_secret_$name"
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "SecretStore"
        const val KEY_IV = "iv"
        const val KEY_DATA = "data"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
