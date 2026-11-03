package com.glasssign.app.plugins

import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 over a stream or a byte array, hex-encoded. Pure Java, so it is unit-testable. */
object Crypto {
    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
        }
        return Hex.encode(digest.digest())
    }

    fun sha256(bytes: ByteArray): String = Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes))
}
