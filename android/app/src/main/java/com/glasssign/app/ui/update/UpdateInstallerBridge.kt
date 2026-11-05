package com.glasssign.app.ui.update

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 「把已校验的文件交给系统安装器」这一件事的接口。
 *
 * 抽成接口的理由有两个，都是实打实的：
 *  1. [UpdateViewModel] 的下载/校验状态机要在普通 JVM 测试里跑，那里没有 `Context`；
 *  2. 这个能力是唯一会离开应用的动作，集中在一处才审得干净——它能做的最多是把一个 `content://`
 *     URI 交给系统安装页，不能静默安装，也不能读别的应用。
 */
interface UpdateInstallerBridge {
    /** 本应用当前是否被允许把 APK 交给系统安装器（API 26+ 的「安装未知应用」开关）。 */
    fun canInstallPackages(): Boolean

    /** 打开系统「安装未知应用」设置页；返回是否成功打开。 */
    fun openUnknownSourcesSettings(): Boolean

    /**
     * 启动系统安装页。返回给用户的一次性提示，或 `null` 表示不需要额外提示。
     * 无论成功与否，安装都必须由用户在系统界面里确认。
     */
    fun launchInstall(apk: File): String?
}

/**
 * 生产实现：先 `ACTION_INSTALL_PACKAGE`，不行再退到 `ACTION_VIEW` + APK MIME。
 *
 * 两个入口都带 `FLAG_GRANT_READ_URI_PERMISSION`，授权只对这一个 URI 有效、只在这次 Intent 有效。
 * URI 由本功能自己的 provider（`${packageName}.updates.fileprovider`）签发，只暴露
 * `cache/updates/`，与 Manifest 的 authority 一致。
 */
class SystemUpdateInstallerBridge(private val context: Context) : UpdateInstallerBridge {

    private val intents = UpdateIntents(context)

    override fun canInstallPackages(): Boolean = intents.canInstallPackages()

    override fun openUnknownSourcesSettings(): Boolean {
        val intent = intents.unknownSourcesSettings()
        return runCatching {
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    override fun launchInstall(apk: File): String? {
        val primary = runCatching { intents.installPackage(apk) }.getOrNull()
        if (primary != null && start(primary)) return null
        val fallback = runCatching { intents.installView(apk) }.getOrNull()
        if (fallback != null && start(fallback)) return null
        Log.w(TAG, "no package installer answered the install intent")
        return "系统没有可用的安装页面，请从系统文件管理器打开已下载的安装包。"
    }

    private fun start(intent: android.content.Intent): Boolean = runCatching {
        context.startActivity(intent)
        true
    }.getOrElse { e ->
        // ActivityNotFoundException 是"这台设备没有这个页面"，SecurityException 是"系统拒绝了"，
        // IllegalArgumentException 多半是 provider 没声明（authority 不匹配）。只记录异常类型，
        // 不记录任何路径或用户数据；任何失败都只是"这条路不通"，交给下一个入口。
        Log.w(TAG, "install intent failed: ${e.javaClass.simpleName}")
        false
    }

    private companion object {
        const val TAG = "HoshioriUpdate"
    }
}
