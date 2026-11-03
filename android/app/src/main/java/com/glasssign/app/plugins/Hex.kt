package com.glasssign.app.plugins

/** Hex helpers shared by the platform layer and the tests. Pure Kotlin, no android.* import. */
object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0F]
        }
        return String(out)
    }

    /** Tolerant reader: accepts upper/lower case and optional ':' separators (`apksigner` prints those). */
    fun decode(hex: String): ByteArray? {
        val cleaned = hex.trim().replace(":", "").replace(" ", "")
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        val out = ByteArray(cleaned.length / 2)
        for (i in out.indices) {
            val hi = DIGITS.indexOf(cleaned[i * 2].lowercaseChar())
            val lo = DIGITS.indexOf(cleaned[i * 2 + 1].lowercaseChar())
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
