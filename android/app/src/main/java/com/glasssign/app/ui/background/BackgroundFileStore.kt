package com.glasssign.app.ui.background

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The private copy of the chosen photo: `filesDir/background/` and nothing else.
 *
 * Two-stage write, so a crash or a full disk can never leave a half image behind the stored preference:
 * bytes go to `<name>.tmp` first, are fsynced, then renamed over the target. Any leftover `.tmp` from a
 * killed process is deleted on the next [recover]; a target whose header no longer parses is removed so the
 * page falls back to the bundled gradient instead of repeating a decode failure every cold start.
 *
 * `java.io.File` only, so the whole write/recover path runs in the JVM unit tests.
 */
class BackgroundFileStore(private val directory: File) {
    val customFile: File get() = File(directory, "$customName.$extension")
    private val tempFile: File get() = File(directory, "$customName.tmp")

    /**
     * Streams [input] into `page.tmp` without touching the current image.
     *
     * The caller then validates/decodes that temp file and only calls [commit] once it is known good, so a
     * bad picker result (garbage bytes, a truncated stream, a corrupt EXIF block) can never destroy the
     * wallpaper the user already had. Returns the number of bytes written.
     */
    fun stage(input: InputStream, length: Long = -1L): File {
        if (length > BackgroundLimits.maxSourceBytes) throw BackgroundError("图片超过 ${BackgroundLimits.maxSourceBytes / (1024 * 1024)} MB 限制")
        if (!directory.isDirectory && !directory.mkdirs()) throw BackgroundError("无法创建背景目录")
        val temp = tempFile
        temp.delete()
        try {
            var copied = 0L
            temp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    // The picker-reported size is only a hint; the cap is enforced on the bytes written.
                    if (copied > BackgroundLimits.maxSourceBytes) throw BackgroundError("图片大小超出限制")
                    out.write(buffer, 0, read)
                }
                out.flush()
                out.fd.sync()
            }
            if (copied == 0L) throw BackgroundError("图片为空")
            return temp
        } catch (e: BackgroundError) {
            temp.delete()
            throw e
        } catch (e: IOException) {
            temp.delete()
            throw BackgroundError("无法读取所选图片")
        } catch (e: SecurityException) {
            temp.delete()
            throw BackgroundError("没有读取所选图片的权限")
        }
    }

    /** Renames a staged file over the current image in one step. The staged file must already be validated. */
    fun commit(staged: File) {
        if (!staged.isFile || staged.length() !in 1..BackgroundLimits.maxSourceBytes) {
            staged.delete()
            throw BackgroundError("无法写入背景文件")
        }
        if (staged.renameTo(customFile)) return
        // Never delete the active wallpaper to emulate replacement: a failed write must preserve it.
        throw BackgroundError("无法替换背景图片，已保留原背景")
    }

    /** Stage + validate + commit for callers that already hold verified bytes. */
    fun save(input: InputStream, length: Long): Long {
        val staged = stage(input, length)
        if (!looksLikeImage(staged)) {
            staged.delete()
            throw BackgroundError("不是可识别的图片文件")
        }
        val lengthWritten = staged.length()
        commit(staged)
        return lengthWritten
    }

    /** True when the stored copy still parses as an image header; used before trusting a cold start. */
    fun isValid(): Boolean = customFile.isFile && customFile.length() in 1..BackgroundLimits.maxSourceBytes && looksLikeImage(customFile)

    fun delete() {
        customFile.delete()
        deleteStaged()
    }

    fun deleteStaged() {
        tempFile.delete()
    }

    /**
     * Cleans up after an interrupted/aborted write and after a corrupt stored file. Returns true when the
     * stored image survived, so the caller knows whether to keep `source = custom` in the preferences.
     */
    fun recover(): Boolean {
        tempFile.delete()
        if (!customFile.exists()) return false
        if (!isValid()) {
            delete()
            return false
        }
        return true
    }

    /**
     * Header sniff for JPEG (including EXIF APP1), PNG, WebP, GIF, HEIF/AVIF and BMP. This is what makes
     * "not an image" a copy-time error instead of a doomed decode on every launch.
     */
    fun looksLikeImage(file: File): Boolean {
        val header = ByteArray(32)
        val read = try {
            file.inputStream().use { it.read(header) }
        } catch (e: IOException) {
            return false
        }
        return isImageHeader(header, read)
    }

    companion object {
        const val directoryName = "background"
        const val customName = "page"
        const val extension = "img"

        /** Owner of this store inside the app's private files directory. */
        fun inFilesDir(filesDir: File) = BackgroundFileStore(File(filesDir, directoryName))

        /** Path of the private copy inside the app's files directory. */
        fun customPath(filesDir: File): File = File(File(filesDir, directoryName), "$customName.$extension")

        /** JPEG/PNG/WebP/GIF/BMP/HEIF magic bytes. Kept here so the JVM tests can drive it with byte arrays. */
        fun isImageHeader(header: ByteArray, length: Int): Boolean {
            if (length < 12) return false
            fun ascii(from: Int, count: Int) = header.copyOfRange(from, from + count).decodeToString()
            fun at(index: Int) = header[index].toInt() and 0xFF
            val jpeg = at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF
            val png = at(0) == 0x89 && ascii(1, 3) == "PNG" && at(4) == 0x0D
            val gif = ascii(0, 3) == "GIF"
            val bmp = ascii(0, 2) == "BM"
            val webp = ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP"
            val heif = ascii(4, 4) == "ftyp"
            return jpeg || png || gif || bmp || webp || heif
        }
    }
}
