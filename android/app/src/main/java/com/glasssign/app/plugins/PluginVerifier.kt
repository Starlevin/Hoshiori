package com.glasssign.app.plugins

/**
 * Facts about ONE file on disk, as measured by whoever can measure them.
 *
 * Signature/package/version are nullable because a file that is not a readable APK — or an
 * installed app that is missing or was signed by a different key — really has no value there. A
 * missing value must never be coerced to the expected value: [PluginVerifier] treats `null` as a
 * failure ("缺失"), never as "unknown, allow".
 */
data class ApkFacts(
    val sizeBytes: Long,
    val sha256: String,
    val packageName: String?,
    val versionCode: Long?,
    val versionName: String?,
    val signingCertSha256: List<String>,
) {
    companion object {
        /** Placeholder for "no file measured yet" — used only by tests and the empty state. */
        val EMPTY = ApkFacts(0L, "", null, null, null, emptyList())
    }
}

/** What the platform says about the *installed* copy of the plugin package. */
data class InstalledFacts(
    val packageName: String?,
    val versionCode: Long?,
    val versionName: String?,
    val signingCertSha256: List<String>,
)

/** Which check a failure belongs to; drives the UI wording and the tests. */
enum class VerifyField(val label: String) {
    SIZE("文件大小"),
    SHA256("SHA-256 摘要"),
    PACKAGE("应用包名"),
    VERSION_CODE("versionCode"),
    VERSION_NAME("versionName"),
    SIGNATURE("签名证书"),
}

/**
 * One failed check: which field, what was expected, what was actually there, and a ready-to-show
 * Chinese sentence.
 *
 * PRIVACY: [actual] is rendered with [PluginSpec.shortSha] when the field is a digest, so a full
 * hash or certificate never lands in a log, a screenshot or a support message. There is nothing
 * credential-shaped in an APK anyway, but the same rule as the login/session code applies: show
 * enough to identify, never enough to impersonate.
 */
data class VerifyFailure(val field: VerifyField, val expected: String, val actual: String) {
    /** Ready-to-show Chinese sentence. Kept as a function so it cannot be a constructor body. */
    fun sentence(): String {
        val shown = actual.ifBlank { "缺失" }
        return when (field) {
            VerifyField.SIZE -> "${field.label}不符：期望 $expected，实际 $actual。可能下载不完整或文件已被替换。"
            VerifyField.SHA256 -> "${field.label}不符：期望 $expected，实际 $actual。文件与固定版本不一致。"
            VerifyField.SIGNATURE -> "${field.label}不符：期望 $expected，实际 $shown。不是同一签名者，已拒绝安装与打开。"
            else -> "${field.label}不符：期望 $expected，实际 $shown。"
        }
    }
}

/** Outcome of a full verification. [installable] is the only gate the install path may read. */
sealed class VerifyReport {
    abstract val installable: Boolean
    abstract val reasons: List<VerifyFailure>

    /** Human-readable summary; empty for a pass. */
    val detail: String get() = reasons.joinToString("；") { it.sentence() }

    object Passed : VerifyReport() {
        override val installable = true
        override val reasons = emptyList<VerifyFailure>()
        override fun toString() = "VerifyReport.Passed"
    }

    data class Failed(override val reasons: List<VerifyFailure>) : VerifyReport() {
        override val installable = false
        init { require(reasons.isNotEmpty()) { "Failed needs at least one reason" } }
        override fun toString() = "VerifyReport.Failed(${reasons.map { it.field }})"
    }

    companion object {
        fun of(reasons: List<VerifyFailure>): VerifyReport = if (reasons.isEmpty()) Passed else Failed(reasons)
    }
}

/**
 * The size + digest + manifest + signature gate.
 *
 * WHY A PURE OBJECT: every rule below is a decision about bytes, not about Android. Keeping it out
 * of the platform layer is what lets the JVM tests feed real byte strings, real SHA-256 values and
 * one flipped bit, instead of asserting that a mock was called.
 *
 * The rules are all-or-nothing on purpose: one mismatching field means the file is NOT the pinned
 * artifact, and there is no "mostly right". Every comparison reports expected vs actual so the user
 * learns *which* item failed rather than a generic "校验失败".
 */
object PluginVerifier {

    /** Case/whitespace tolerant digest comparison; digests are hex, so folding case is lossless. */
    private fun sameHex(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

    /** Checks a downloaded file against the pinned artifact. */
    fun verifyDownloaded(facts: ApkFacts, spec: PluginArtifact = PluginArtifact.of(PluginSpec)): VerifyReport =
        verifyDownloadedWith(facts, spec)

    /** Same contract with a required (non-defaulted) artifact; used by [DownloadCoordinator]. */
    fun verifyDownloadedWith(facts: ApkFacts, spec: PluginArtifact): VerifyReport {
        val reasons = mutableListOf<VerifyFailure>()

        if (facts.sizeBytes != spec.sizeBytes) {
            reasons += VerifyFailure(
                VerifyField.SIZE,
                PluginSpec.readableBytes(spec.sizeBytes) + "（${spec.sizeBytes} 字节）",
                PluginSpec.readableBytes(facts.sizeBytes) + "（${facts.sizeBytes} 字节）",
            )
        }
        if (!sameHex(facts.sha256, spec.sha256)) {
            reasons += VerifyFailure(
                VerifyField.SHA256,
                PluginSpec.shortSha(spec.sha256),
                if (facts.sha256.isBlank()) "缺失" else PluginSpec.shortSha(facts.sha256),
            )
        }
        if (spec.packageName != null && facts.packageName != spec.packageName) {
            reasons += VerifyFailure(VerifyField.PACKAGE, spec.packageName, facts.packageName.orEmpty())
        }
        if (spec.versionCode != null && facts.versionCode != spec.versionCode) {
            reasons += VerifyFailure(
                VerifyField.VERSION_CODE,
                spec.versionCode.toString(),
                facts.versionCode?.toString().orEmpty(),
            )
        }
        if (spec.versionName != null && facts.versionName != spec.versionName) {
            reasons += VerifyFailure(VerifyField.VERSION_NAME, spec.versionName, facts.versionName.orEmpty())
        }
        if (spec.signingCertSha256 != null && !signedByPinnedKey(facts.signingCertSha256, spec.signingCertSha256)) {
            reasons += VerifyFailure(
                VerifyField.SIGNATURE,
                PluginSpec.shortSha(spec.signingCertSha256),
                facts.signingCertSha256.joinToString("、") { PluginSpec.shortSha(it) }.ifBlank { "缺失" },
            )
        }
        // A file must carry at least one signer that IS the pinned key; `signedByPinnedKey` already
        // requires a non-empty list, so an unsigned APK (or one whose signing block could not be
        // read) fails here rather than being waved through.
        return VerifyReport.of(reasons)
    }

    /**
     * Checks the INSTALLED package before every launch (and before enabling).
     *
     * This is the one that matters most: the file we verified yesterday can be replaced by a
     * re-signed build through another installer, so the decision to call 领签 must be taken against
     * what PackageManager reports right now, not against what the download page remembers.
     */
    fun verifyInstalled(
        facts: InstalledFacts?,
        spec: PluginArtifact = PluginArtifact.of(PluginSpec),
    ): VerifyReport {
        // The installed-copy gate is ALWAYS strict: it runs in the app, never in a synthetic test,
        // so a null expectation here would be a bug rather than a fixture. Falling back to the
        // production constant makes that explicit instead of letting `null` widen the check.
        val pinnedPackage = spec.packageName ?: PluginSpec.EXPECTED_PACKAGE
        val pinnedVersionCode = spec.versionCode ?: PluginSpec.EXPECTED_VERSION_CODE
        val pinnedCert = spec.signingCertSha256 ?: PluginSpec.SIGNING_CERT_SHA256

        if (facts == null) {
            val reason = VerifyFailure(VerifyField.PACKAGE, pinnedPackage, "未安装")
            return VerifyReport.Failed(listOf(reason))
        }
        val reasons = mutableListOf<VerifyFailure>()
        if (facts.packageName != pinnedPackage) {
            reasons += VerifyFailure(VerifyField.PACKAGE, pinnedPackage, facts.packageName.orEmpty())
        }
        if (facts.versionCode != pinnedVersionCode) {
            reasons += VerifyFailure(
                VerifyField.VERSION_CODE,
                pinnedVersionCode.toString(),
                facts.versionCode?.toString().orEmpty(),
            )
        }
        if (!signedByPinnedKey(facts.signingCertSha256, pinnedCert)) {
            reasons += VerifyFailure(
                VerifyField.SIGNATURE,
                PluginSpec.shortSha(pinnedCert),
                facts.signingCertSha256.joinToString("、") { PluginSpec.shortSha(it) }.ifBlank { "缺失" },
            )
        }
        // versionName is informational: Android allows a different label for the same versionCode,
        // and refusing to open an app because of a display string would be a false alarm. The
        // pinned name is still required of the *artifact* (verifyDownloaded), where it is exact.
        return VerifyReport.of(reasons)
    }

    /**
     * A multi-signer APK is accepted only if the pinned certificate is among its signers; rotation
     * history is deliberately not consulted, because "signed by the pinned key at some point" is
     * weaker than "signed by the pinned key now".
     */
    private fun signedByPinnedKey(actual: List<String>, pinned: String): Boolean =
        actual.isNotEmpty() && actual.any { sameHex(it, pinned) }
}

/**
 * The pinned expectations, lifted out of [PluginSpec] so a test can describe a *different* artifact
 * (the same bytes with one field changed) without touching the production constants.
 *
 * [packageName]/[versionName]/[signingCertSha256] are nullable and `null` means "no expectation":
 * that is what lets the JVM tests exercise the size + digest gates against plain bytes, since a
 * synthetic fixture has no APK manifest and no signing block to read. Production passes
 * [of], which fills every field from [PluginSpec]; nothing in the app ever constructs a `null`
 * expectation, so the real gate is always the strict one.
 */
data class PluginArtifact(
    val sizeBytes: Long,
    val sha256: String,
    val packageName: String?,
    val versionCode: Long?,
    val versionName: String?,
    val signingCertSha256: String?,
    val downloadUrl: String = PluginSpec.DOWNLOAD_URL,
    val downloadHost: String = PluginSpec.DOWNLOAD_HOST,
    val downloadScheme: String = PluginSpec.DOWNLOAD_SCHEME,
    val downloadPort: Int = PluginSpec.DOWNLOAD_PORT,
) {
    companion object {
        fun of(spec: PluginSpec = PluginSpec): PluginArtifact = PluginArtifact(
            sizeBytes = PluginSpec.SIZE_BYTES,
            sha256 = PluginSpec.SHA256,
            packageName = PluginSpec.EXPECTED_PACKAGE,
            versionCode = PluginSpec.EXPECTED_VERSION_CODE,
            versionName = PluginSpec.EXPECTED_VERSION_NAME,
            signingCertSha256 = PluginSpec.SIGNING_CERT_SHA256,
        )
    }
}
