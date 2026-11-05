package com.glasssign.app.ui.update

/**
 * 更新通道。与 applicationId 无关：现在正式版与预览版都装成 `com.glasssign.app`、用同一张签名证书，
 * 所以「该查哪条发布通道」由构建的版本标记决定（见 [UpdateBuildInfo.channel]），而不是包名。
 *
 * 包名只用于一件事：安装时校验 APK 里的包名 == 本次安装自己的 id。
 */
enum class UpdateReleaseChannel(
    /** 给用户看的名字。 */
    val label: String,
    /** 该通道发布的 APK 文件名标记；null 表示不要求标记。 */
    val assetMarker: String?,
) {
    /** 正式通道：只认正式 Release，资产文件名不带 `preview`。 */
    OFFICIAL("正式版", null),

    /** 预览通道：认预览 Release，也接受比当前预览更高的最终正式版（预览用户可前进到正式）。 */
    PREVIEW("预览版", "preview"),
    ;

    companion object {
        /**
         * 从版本名推断通道：`0.7.9-preview.3` 是预览构建，其余是正式构建。
         *
         * 只看版本名，不看包名：同一包名的预览构建也要查预览通道，否则用户装到的是「正式通道的
         * 最新版本」，而预览构建的 versionCode 更高时会被判成「已是最新」而永远收不到预览更新。
         */
        fun ofVersionName(versionName: String): UpdateReleaseChannel =
            if (UpdateVersion.isPreviewVersionName(versionName)) PREVIEW else OFFICIAL
    }
}

/** 旧版独立 preview 安装（`com.glasssign.app.preview`）的事实判定，只用于给提示，不参与安装决策。 */
object LegacyPreviewInstall {
    const val PACKAGE_NAME = "com.glasssign.app.preview"

    /** 旧 preview 包名与现在的统一包名不同，因此不能被自动覆盖，需要用户手动换成新包。 */
    fun isLegacyPreview(applicationId: String?): Boolean =
        applicationId?.trim() == PACKAGE_NAME
}

/** 下载入口的选择结果，[reason] 用于说明为什么不能直接下载。 */
sealed class UpdateDownloadTarget {
    /** 应用内下载这个 GitHub 资源。 */
    data class InApp(val url: String, val assetName: String, val sha256: String?) : UpdateDownloadTarget()

    /** 应用内下载不可用：给出明确的外部渠道，不假装能直下。 */
    data class ExternalOnly(val reason: String, val quarkUrl: String?) : UpdateDownloadTarget()
}

/**
 * 决定这次点击走应用内下载还是只能外部渠道。
 *
 * 不校验包名：包名是否匹配是安装阶段的事（APK 里的包名必须等于本次安装自己的 id）。
 */
object UpdateChannelPolicy {

    fun downloadTarget(
        check: UpdateCheck?,
        quarkUrl: String?,
    ): UpdateDownloadTarget {
        val quark = quarkUrl?.takeIf { UpdateUrls.isQuarkLink(it) }
        val candidate = (check as? UpdateCheck.Available)?.candidate
            ?: return UpdateDownloadTarget.ExternalOnly(
                reason = "本次检查没有可下载的新版本。",
                quarkUrl = quark,
            )
        val url = candidate.apk.browserDownloadUrl
        if (!UpdateUrls.isReleaseApkUrl(url)) {
            return UpdateDownloadTarget.ExternalOnly(
                reason = "该版本的下载地址不是 GitHub 官方发布地址，已拒绝下载。",
                quarkUrl = quark,
            )
        }
        if (!ReleasePolicy.isReleaseApk(candidate.apk.name) || candidate.apk.sizeBytes <= 0L) {
            return UpdateDownloadTarget.ExternalOnly(
                reason = "该版本的安装包信息不完整，已拒绝下载。",
                quarkUrl = quark,
            )
        }
        return UpdateDownloadTarget.InApp(
            url = url,
            assetName = candidate.apk.name,
            sha256 = candidate.apk.sha256,
        )
    }
}
