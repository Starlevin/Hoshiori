package com.glasssign.app.plugins

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Production [PluginFileSystem] over `java.io`. It lives in the `plugins` package but imports no
 * android.* symbol — the only Android-specific part is *which* directory the caller passes in
 * (`cacheDir/plugins`), which [PluginFiles] supplies.
 *
 * Kept importable from the JVM test source set so [DownloadCoordinatorTest] exercises the real
 * rename/delete/write behaviour instead of a handwritten double.
 */
class RealPluginFileSystem : PluginFileSystem {
    override fun file(name: String) = File(name)
    override fun exists(file: File) = file.exists()
    override fun mkdirs(dir: File) = dir.mkdirs() || dir.isDirectory
    override fun openWrite(file: File, append: Boolean): OutputStream = FileOutputStream(file, append)
    override fun openRead(file: File): InputStream = FileInputStream(file)
    override fun length(file: File) = file.length()
    override fun delete(file: File) = !file.exists() || file.delete()
    override fun rename(from: File, to: File) = from.renameTo(to)
}
