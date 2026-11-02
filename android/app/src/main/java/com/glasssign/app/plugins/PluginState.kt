package com.glasssign.app.plugins

/** Download-lifecycle states. Pure Kotlin so the coordinator can be driven in a JVM test. */
object PluginState {

    sealed class Download {
        /** No bytes in flight and no remembered failure. */
        object Idle : Download() {
            override fun toString() = "Idle"
        }

        /**
         * Bytes are arriving. [bytes] is what has actually been written so far, not what the header
         * promised, so a stalled transfer cannot show a fake 100 %.
         */
        data class Running(val bytes: Long, val total: Long) : Download() {
            val percent: Int
                get() = when {
                    total <= 0L -> 0
                    else -> ((bytes * 100) / total).toInt().coerceIn(0, 100)
                }

            val label: String
                get() = if (total > 0L) {
                    "${percent}% · 已下载 ${PluginSpec.readableBytes(bytes)} / ${PluginSpec.readableBytes(total)}"
                } else {
                    "正在连接 · 已下载 ${PluginSpec.readableBytes(bytes)}"
                }
        }

        /** A transfer that ended without a usable file. The temporary file is already gone. */
        data class Failed(val reason: String) : Download()
    }

    /** Reasons that belong to the transfer; verification failures use [VerifyReport.detail]. */
    const val FAIL_CANCELLED = "下载已取消，临时文件已删除"
    const val FAIL_TIMEOUT = "下载超时，临时文件已删除"
    const val FAIL_IO = "网络连接中断，临时文件已删除"
    const val FAIL_OVERFLOW = "下载内容超过固定大小，已停止并删除临时文件"
    const val FAIL_HOST = "下载地址不符合固定主机 / HTTPS 要求，已停止"

    /** Prefix kept for tests and for the UI when a hash/size mismatch is reported after a transfer. */
    const val FAIL_VERIFY = "核验失败："
}
