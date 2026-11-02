package com.glasssign.app.plugins

import java.io.File

/**
 * Reads the manifest/signature facts out of an APK **file on disk**.
 *
 * WHY AN INTERFACE WITH ONE METHOD: the platform answer is
 * `PackageManager.getPackageArchiveInfo(path, flags)`, which needs `android.*` and therefore cannot
 * run in this project's JVM test suite. Everything that *decides* something (size, digest, package,
 * version, certificate) lives in [PluginVerifier] and is pure Kotlin; this interface only supplies
 * the measurements, so a test can inject a fake and a production run uses [AndroidApkMetadataReader]
 * (see `ApkMetadataReaderAndroid.kt`).
 *
 * Returning `null` means "the archive could not be read". [PluginVerifier] treats `null` as a
 * FAILURE for every manifest field — never as "unknown, allow".
 */
interface ApkMetadataReader {
    fun read(file: File): ApkFacts?
}
