package com.glasssign.app.ui.update

/**
 * 下载到的 APK 的身份事实，由平台层测量后交给 [UpdateInstallVerifier] 判断。
 * `null` 一律按"读不到"处理，绝不当成"与期望一致"。
 */
data class UpdateApkFacts(
    val sizeBytes: Long,
    val sha256: String,
    val packageName: String?,
    val versionCode: Long?,
    val versionName: String?,
    /** APK 内签名证书的 SHA-256（多个签名者则多个）。空列表 = 读不到或未签名。 */
    val signingCertSha256: List<String>,
)

/** 当前已安装的星栞的事实，来自 `PackageManager`。 */
data class UpdateInstalledFacts(
    val packageName: String?,
    val versionCode: Long?,
    val versionName: String?,
    val signingCertSha256: List<String>,
)

/** 被拒绝的原因分类。文案在 [UpdateVerifyProblem.sentence] 里，一类一句话。 */
enum class UpdateVerifyIssue {
    /** 下载的文件读不出 APK 清单。 */
    UNREADABLE,

    /** 包名不是本次安装自己的 applicationId。 */
    PACKAGE_MISMATCH,

    /** versionCode 没有升高。 */
    VERSION_NOT_HIGHER,

    /** APK 的签名者集合与当前安装的签名者集合不同。 */
    SIGNATURE_MISMATCH,

    /** 文件大小或摘要与下载时、或与 GitHub 公布值不一致。 */
    SIZE_MISMATCH,
}

/** 一条可以原样显示给用户的拒绝理由。 */
data class UpdateVerifyProblem(
    val issue: UpdateVerifyIssue,
    val expected: String,
    val actual: String,
) {
    fun sentence(): String = when (issue) {
        UpdateVerifyIssue.UNREADABLE ->
            "安装包无法解析（$actual），已拒绝安装。"
        UpdateVerifyIssue.PACKAGE_MISMATCH ->
            "包名不符：期望 $expected，实际 $actual，这不是星栞的安装包。"
        UpdateVerifyIssue.VERSION_NOT_HIGHER ->
            "版本号没有升高：当前已安装 $expected，安装包是 $actual。"
        UpdateVerifyIssue.SIGNATURE_MISMATCH ->
            "签名不符：期望 $expected，实际 $actual。覆盖安装会被系统拒绝。"
        UpdateVerifyIssue.SIZE_MISMATCH ->
            "文件校验不符：期望 $expected，实际 $actual。可能下载不完整或文件已被替换。"
    }
}

/** 校验报告。[passed] 是安装入口唯一允许读取的门禁。 */
sealed class UpdateVerifyReport {
    abstract val passed: Boolean
    abstract val problems: List<UpdateVerifyProblem>

    val detail: String get() = problems.joinToString("\n") { it.sentence() }

    data object Passed : UpdateVerifyReport() {
        override val passed = true
        override val problems = emptyList<UpdateVerifyProblem>()
        override fun toString() = "Passed"
    }

    data class Failed(override val problems: List<UpdateVerifyProblem>) : UpdateVerifyReport() {
        init { require(problems.isNotEmpty()) { "Failed 至少要有一条理由" } }
        override val passed = false
        override fun toString() = "Failed(${problems.map { it.issue }})"
    }

    companion object {
        fun of(problems: List<UpdateVerifyProblem>): UpdateVerifyReport =
            if (problems.isEmpty()) Passed else Failed(problems)
    }
}

/**
 * 安装前校验：大小、摘要、包名、versionCode、签名，一条都不能少。
 *
 * 纯对象实现，每条规则都是"对字节与清单的判断"，所以能用普通 JVM 测试直接喂事实，不必真下载安装。
 * 平台测量由 `UpdateApkReader` 提供。
 *
 * 基准是"当前已安装的这份星栞"：包名必须等于 [AppInstallInfo.applicationId]；versionCode 必须严格升高；
 * 签名者集合必须与当前安装的完全相同——不是有交集就算。系统对多签名 APK 的覆盖安装要求签名者集一致，
 * 只要求交集会让一个"多带了别的签名者"的包装过本地检查、再去撞系统的拒绝。
 */
object UpdateInstallVerifier {

    /**
     * @param install 当前安装信息（含本次安装自己的 applicationId 与 versionCode）
     * @param installed 平台报告的本安装事实；`null` 表示读不到，此时签名比对无法完成，必须拒绝
     * @param apk 下载文件的测量结果
     * @param expectedSize 下载阶段记录的字节数；`<= 0` 表示未知，跳过这一条
     * @param expectedSha256 已核验过的整文件摘要；`null`/格式不对时跳过（下载阶段已按 GitHub 摘要比对）
     * @param signatureBaseline 签名比对基准；默认取 [installed] 的证书
     */
    fun verify(
        install: AppInstallInfo,
        installed: UpdateInstalledFacts?,
        apk: UpdateApkFacts?,
        expectedSize: Long = 0L,
        expectedSha256: String? = null,
        signatureBaseline: List<String> = installed?.signingCertSha256.orEmpty(),
    ): UpdateVerifyReport {
        if (apk == null) {
            return UpdateVerifyReport.Failed(
                listOf(UpdateVerifyProblem(UpdateVerifyIssue.UNREADABLE, install.applicationId, "无法解析 APK 清单")),
            )
        }
        val problems = mutableListOf<UpdateVerifyProblem>()

        if (expectedSize > 0 && apk.sizeBytes != expectedSize) {
            problems += UpdateVerifyProblem(
                UpdateVerifyIssue.SIZE_MISMATCH,
                readableSizeOf(expectedSize),
                readableSizeOf(apk.sizeBytes),
            )
        }
        val pinnedDigest = ReleaseDigest.hex(expectedSha256)
        if (pinnedDigest != null && !sameHex(apk.sha256, pinnedDigest)) {
            problems += UpdateVerifyProblem(
                UpdateVerifyIssue.SIZE_MISMATCH,
                "SHA-256 ${ReleaseDigest.short(pinnedDigest)}",
                "SHA-256 ${ReleaseDigest.short(apk.sha256)}",
            )
        }

        val actualPackage = apk.packageName?.trim().orEmpty()
        if (actualPackage.isEmpty()) {
            problems += UpdateVerifyProblem(UpdateVerifyIssue.UNREADABLE, install.applicationId, "APK 清单里没有包名")
        } else if (actualPackage != install.applicationId) {
            problems += UpdateVerifyProblem(UpdateVerifyIssue.PACKAGE_MISMATCH, install.applicationId, actualPackage)
        }

        val currentCode = installed?.versionCode ?: install.versionCode
        val apkCode = apk.versionCode
        if (apkCode == null) {
            problems += UpdateVerifyProblem(
                UpdateVerifyIssue.UNREADABLE,
                currentCode.toString(),
                "APK 清单里没有 versionCode",
            )
        } else if (apkCode <= currentCode) {
            problems += UpdateVerifyProblem(
                UpdateVerifyIssue.VERSION_NOT_HIGHER,
                currentCode.toString(),
                apkCode.toString(),
            )
        }

        val baseline = signatureBaseline.map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
        val apkSigners = apk.signingCertSha256.map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
        when {
            // 读不到当前安装的证书 = 无法证明"同一签名者"，不假设，直接拒绝。
            baseline.isEmpty() -> problems += UpdateVerifyProblem(
                UpdateVerifyIssue.SIGNATURE_MISMATCH,
                "当前安装的签名证书",
                "无法读取",
            )
            apkSigners.isEmpty() -> problems += UpdateVerifyProblem(
                UpdateVerifyIssue.SIGNATURE_MISMATCH,
                summary(baseline),
                "未签名或读不到签名",
            )
            // 严格集合相等：多一个或少一个签名者都不行。
            apkSigners != baseline -> problems += UpdateVerifyProblem(
                UpdateVerifyIssue.SIGNATURE_MISMATCH,
                summary(baseline),
                summary(apkSigners),
            )
        }

        return UpdateVerifyReport.of(problems)
    }

    /** 十六进制不区分大小写；空格与冒号（apksigner 的输出习惯）忽略。 */
    private fun sameHex(a: String, b: String): Boolean =
        normalize(a) == normalize(b) && normalize(a).isNotEmpty()

    private fun normalize(value: String): String =
        value.trim().replace(":", "").replace(" ", "").lowercase()

    /** `ab12cd34…` 形式，避免整段证书指纹出现在截图或日志里。 */
    private fun summary(values: Collection<String>): String =
        values.sorted().joinToString("、") { ReleaseDigest.shortCerts(it) }
}

/** 给用户看的签名说明：凭什么判定签名一致。 */
object UpdateSignatureBasis {
    const val BASIS_SENTENCE =
        "签名校验：安装包内的签名证书必须与当前已安装的星栞完全一致，不一致无法覆盖安装。"
}
