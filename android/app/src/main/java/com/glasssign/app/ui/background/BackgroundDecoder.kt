package com.glasssign.app.ui.background

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream

/**
 * Bounds-decodes one on-disk image for one screen size, and imports picker selections into the private copy.
 *
 * Page decode order: read bounds (no pixel allocation) -> [planDecode] -> decode with `inSampleSize` ->
 * re-scale to the exact planned size -> apply EXIF rotation. The bitmap that reaches the canvas is
 * therefore at most `min(screen, source, [BackgroundLimits.maxDecodePixels])`, and the intermediate stays
 * within one power of two of it, which is what keeps a 50 MP photo off the heap.
 *
 * Import order is the same but never unbounded and never in-place: the stream is staged to `page.tmp`,
 * validated, decoded through the planner at [ImportLimits.longestSide] (so the decode never exceeds
 * [BackgroundLimits.maxDecodePixels] regardless of the source), re-encoded upright without EXIF, and only
 * then swapped in with one rename. A bad or unsupported pick therefore leaves the previous wallpaper intact,
 * and no temporary file is ever promoted without validation.
 */
class BackgroundDecoder(private val store: BackgroundFileStore) {
    /** Decodes [file] for a [targetWidth] x [targetHeight] page. Call from a background thread. */
    fun decode(file: File, targetWidth: Int, targetHeight: Int): DecodedImage {
        val bounds = readBounds(file)
        // The stored copy is normally re-encoded upright on import, but an EXIF tag left over from an older
        // build (or a copy that failed to re-encode) must not show up sideways.
        val orientation = readOrientation(file)
        val plan = planDecode(bounds, orientation.turns, targetWidth, targetHeight)
            .getOrElse { throw BackgroundError(it.message ?: "图片尺寸无效") }
        return BitmapImage(decodeSampled(file, plan, orientation))
    }

    /** EXIF orientation as clockwise quarter turns plus a horizontal mirror; missing EXIF reads as upright. */
    private fun readOrientation(file: File): Orientation {
        val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull() ?: return Orientation()
        // `getAttributeInt(TAG_ORIENTATION, ...)` is the 1.2+/1.3.x API; the `orientation` property is newer.
        val raw = runCatching {
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val flipped = runCatching { exif.isFlipped }.getOrDefault(false)
        return Orientation(orientationQuarterTurns(raw), flipped)
    }

    /** Bounds only: no pixels are allocated. Rejects anything BitmapFactory or the planner will not accept. */
    private fun readBounds(file: File): ImageInfo {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
            .onFailure { throw BackgroundError("图片无法解码") }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw BackgroundError("图片无法解码")
        if (bounds.outMimeType != null && !bounds.outMimeType.startsWith("image/")) throw BackgroundError("不是图片文件")
        return ImageInfo(bounds.outWidth, bounds.outHeight)
    }

    /** Samples to the planned intermediate, then scales/rotates down to the planned final size. */
    private fun decodeSampled(file: File, plan: DecodePlan, orientation: Orientation): Bitmap {
        val sampled = try {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = plan.inSampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                },
            ) ?: decodeWithAlpha(file, plan.inSampleSize)
        } catch (e: OutOfMemoryError) {
            throw BackgroundError("设备内存不足，无法显示此图片")
        }
        val scaled = scale(sampled, plan.scaledWidth, plan.scaledHeight)
        return orient(scaled, orientation.turns, orientation.flipped)
    }

    /** RGB_565 is refused by some decoders (transparent PNG/WebP); retry once in full ARGB. */
    private fun decodeWithAlpha(file: File, inSampleSize: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeFile(file.absolutePath, options) ?: throw BackgroundError("图片无法解码")
        } catch (e: OutOfMemoryError) {
            throw BackgroundError("设备内存不足，无法显示此图片")
        }
    }

    private fun scale(source: Bitmap, width: Int, height: Int): Bitmap {
        if (source.width == width && source.height == height) return source
        val result = Bitmap.createScaledBitmap(source, width, height, true)
        if (result !== source) source.recycle()
        return result
    }

    private fun orient(source: Bitmap, quarterTurns: Int, flipped: Boolean): Bitmap {
        if (quarterTurns == 0 && !flipped) return source
        val matrix = Matrix().apply {
            if (flipped) postScale(-1f, 1f)
            if (quarterTurns != 0) postRotate(quarterTurnsToDegrees(quarterTurns).toFloat())
        }
        val result = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (result !== source) source.recycle()
        return result
    }

    /**
     * Copies the picker selection into the private store without risking the current image.
     *
     * 1. stream to `page.tmp` (bounded by [BackgroundLimits.maxSourceBytes]);
     * 2. read its bounds + EXIF orientation, which validates it as a decodable image;
     * 3. decode it through [planDecode] capped at [ImportLimits.longestSide] and
     *    [BackgroundLimits.maxDecodePixels] — a 50 MP photo is sampled down, not decoded whole;
     * 4. rotate/flip the small result, re-encode it as JPEG (orientation 1, no EXIF at all), fsync;
     * 5. rename it over `page.img` in one step, and only if that write is verifiable.
     *
     * Any failure up to step 5 deletes the temporary file and leaves `page.img` untouched, so a broken pick
     * never breaks the wallpaper the user already had. No storage permission is needed: the picker grants a
     * temporary read on this one URI, and the bytes are copied out before it can expire.
     */
    fun importPickerImage(context: Context, uri: Uri): ImportedImage {
        val declared = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
        if (declared > BackgroundLimits.maxSourceBytes) throw oversizedMessage()
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (mime != null && !mime.startsWith("image/")) throw BackgroundError("不是图片文件")
        val input: InputStream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: throw BackgroundError("无法打开所选图片")
        val staged = input.use { store.stage(it, declared) }
        try {
            val upright = readUpright(staged)
            try {
                writeUprightJpeg(upright.bitmap, staged)
            } finally {
                if (!upright.bitmap.isRecycled) upright.bitmap.recycle()
            }
            val bytes = staged.length()
            // `commit` re-checks the staged file and throws before touching `page.img` if anything is off.
            store.commit(staged)
            return ImportedImage(bytes, mime)
        } catch (e: BackgroundError) {
            store.deleteStaged()
            throw e
        } catch (e: Throwable) {
            store.deleteStaged()
            throw BackgroundError(e.message ?: "无法保存背景图片")
        }
    }

    /** Bounded decode of the staged pick at the import size; also proves the file really decodes. */
    private fun readUpright(staged: File): UprightImage {
        val bounds = readBounds(staged)
        val orientation = readOrientation(staged)
        val plan = planDecode(bounds, orientation.turns, ImportLimits.longestSide, ImportLimits.longestSide)
            .getOrElse { throw BackgroundError(it.message ?: "图片尺寸无效") }
        // The bitmap comes out already rotated/flipped, so the re-encoded copy needs no EXIF at all.
        return UprightImage(decodeSampled(staged, plan, orientation))
    }

    /** Writes the bounded, already-upright bitmap over [target] as JPEG q95 with no metadata and fsyncs it. */
    private fun writeUprightJpeg(bitmap: Bitmap, target: File) {
        try {
            target.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw BackgroundError("无法保存背景图片")
                out.flush()
                out.fd.sync()
            }
        } catch (e: BackgroundError) {
            throw e
        } catch (e: java.io.IOException) {
            throw BackgroundError("无法保存背景图片")
        }
        if (target.length() <= 0L) throw BackgroundError("无法保存背景图片")
    }

    private fun oversizedMessage() =
        BackgroundError("图片超过 ${BackgroundLimits.maxSourceBytes / (1024 * 1024)} MB 限制")

    /** EXIF orientation in the form the decoder needs: clockwise quarter turns plus a horizontal mirror. */
    private data class Orientation(val turns: Int = 0, val flipped: Boolean = false)

    private class UprightImage(val bitmap: Bitmap)
}

/** Result of a successful picker import: the stored byte count and the MIME type the resolver reported. */
data class ImportedImage(val sizeBytes: Long, val mimeType: String?)

/** Import sizes: a wallpaper never needs more than this on the longest side, and it stays under the pixel cap. */
object ImportLimits {
    /**
     * 2048 is above every phone's short side (so a page decode still has pixels to crop) and 2048² ≈ 4.2 MP,
     * well inside [BackgroundLimits.maxDecodePixels]; the JPEG copy lands around 200-500 KB.
     */
    const val longestSide: Int = 2048
}

/** [DecodedImage] over a platform bitmap. The caller owns the bitmap. */
class BitmapImage(val bitmap: Bitmap) : DecodedImage {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
    override val pixels: Any get() = bitmap
}
