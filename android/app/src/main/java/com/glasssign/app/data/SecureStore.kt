package com.glasssign.app.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores a secret encrypted with an account-scoped Android Keystore AES key.
 *
 * The key stays in Android Keystore; hardware backing depends on the device. If the key is invalidated
 * (factory reset, lock-screen change on some devices) the ciphertext is dropped and
 * the owner is asked to enter the key again, instead of silently sending garbage.
 */
class SecureStore(private val prefs: SharedPreferences, private val alias: String = ALIAS) {

    companion object {
        private const val ALIAS = "glasssign_api_auth_v3"
        private const val PREF_SECRET = "api_key_ciphertext_v3"
        private const val PREF_SERVER = "server_v3"
        private const val PREF_REDUCE_MOTION = "reduce_motion_v3"
    }

    fun server(): String = prefs.getString(PREF_SERVER, "").orEmpty()

    fun saveServer(value: String) {
        prefs.edit().putString(PREF_SERVER, value).apply()
    }

    fun hasToken(): Boolean = prefs.contains(PREF_SECRET)

    fun saveToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(PREF_SECRET, Base64.encodeToString(payload, Base64.NO_WRAP)).commit()) { "加密授权保存失败" }
    }

    fun readToken(): String? {
        val encoded = prefs.getString(PREF_SECRET, null) ?: return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size <= 12) throw GeneralSecurityException("ciphertext too short")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
            String(cipher.doFinal(payload.copyOfRange(12, payload.size)), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            clearToken()
            null
        } catch (_: IllegalArgumentException) {
            clearToken()
            null
        }
    }

    fun clearToken() {
        prefs.edit().remove(PREF_SECRET).commit()
        try {
            keyStore().deleteEntry(alias)
        } catch (_: GeneralSecurityException) {
            // Keystore unavailable; nothing else to clean.
        }
    }

    fun reduceMotion(): Boolean = prefs.getBoolean(PREF_REDUCE_MOTION, false)

    fun setReduceMotion(value: Boolean) {
        prefs.edit().putBoolean(PREF_REDUCE_MOTION, value).apply()
    }

    fun clearAll() {
        clearToken()
        prefs.edit().clear().apply()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        keyStore().getKey(alias, null)?.let { return it as SecretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
