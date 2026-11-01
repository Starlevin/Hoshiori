package com.glasssign.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Small single-owner client. Never log or expose authorization credentials. */
class CloudClient(private val context: Context) {
    private val prefs = context.getSharedPreferences("glasssign", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "glasssign_api_auth"
        ks.getKey(alias, null)?.let { return it as SecretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    fun server() = prefs.getString("server", "") ?: ""

    fun ready() = server().startsWith("https://") && prefs.contains("secret")

    fun configure(server: String, apiKey: String) {
        val normalized = server.trim().trimEnd('/')
        require(normalized.startsWith("https://")) { "云端地址必须为 HTTPS" }
        require(apiKey.length >= 32) { "密钥至少 32 个字符" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("server", normalized)
            .putString("secret", Base64.encodeToString(cipher.iv + data, Base64.NO_WRAP))
            .apply()
    }

    private fun token(): String {
        val raw = prefs.getString("secret", "") ?: ""
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        require(bytes.size > 12) { "未保存授权信息" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    suspend fun request(path: String, post: Boolean = false, payload: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(server() + path).openConnection() as HttpsURLConnection)
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer ${token()}")
            if (post) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write((payload ?: JSONObject()).toString().toByteArray(Charsets.UTF_8))
                }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("云端返回 HTTP $status")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
