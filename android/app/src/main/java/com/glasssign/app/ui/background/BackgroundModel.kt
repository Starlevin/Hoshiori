package com.glasssign.app.ui.background

import kotlin.math.max
import kotlin.math.min

/**
 * Where the page wallpaper comes from.
 *
 * `custom` points at the private copy in [BackgroundFileStore]; `starfield` is the original bundled
 * artwork (`R.drawable.hoshiori_starfield_v076`) referenced only through [BundledBackgrounds], so this enum
 * and its persistence stay free of a resource id that the app owner may still rename.
 */
enum class BackgroundSource { none, custom, starfield, bundled }

/** User-tunable rendering of the page background. Mask/blur only touch the wallpaper layer, never text. */
data class BackgroundPreferences(
    val source: BackgroundSource = BackgroundSource.none,
    val maskStrength: Float = defaultMask,
    val blurDp: Float = defaultBlur,
) {
    /** True whenever anything is painted behind the page cards; keys the card-alpha consumer. */
    val enabled: Boolean get() = source != BackgroundSource.none

    fun sanitized() = copy(maskStrength = maskStrength.sanitized(MaskRange), blurDp = blurDp.sanitized(BlurRange))

    companion object {
        const val defaultMask = .22f
        const val defaultBlur = 0f
        val MaskRange = 0f..0.85f
        val BlurRange = 0f..30f

        /** Stored form: all three fields validated independently, so a corrupt file yields defaults. */
        fun decode(source: String?, mask: Float?, blur: Float?) = BackgroundPreferences(
            source = BackgroundSource.values().firstOrNull { it.name == source } ?: BackgroundSource.none,
            maskStrength = mask ?: defaultMask,
            blurDp = blur ?: defaultBlur,
        ).sanitized()
    }
}

internal fun Float.sanitized(range: ClosedFloatingPointRange<Float>) =
    if (isNaN()) range.start else coerceIn(range.start, range.endInclusive)

/**
 * The image the user picked, before it was copied. Not persisted: the store only keeps bytes, so a
 * temporary `content://` grant is never a dependency of the saved background.
 */
data class PickedImage(val uri: String, val sizeBytes: Long, val mimeType: String?)

/**
 * Decoded page image. [pixels] is the platform bitmap; the interface itself is Android-free so the decode
 * plan and the store stay runnable in the JVM tests.
 */
interface DecodedImage {
    val width: Int
    val height: Int
    val pixels: Any?
}

// ---------------------------------------------------------------------------------------------
// Limits
// ---------------------------------------------------------------------------------------------

object BackgroundLimits {
    /** Writes above this are rejected before any byte is copied: a 24 MP JPEG is ~8 MB, a RAW is not. */
    const val maxSourceBytes = 20L * 1024 * 1024

    /** Decoded page bitmap cap. 4096x4096 ARGB_8888 is 64 MB, which is already a lot for one background. */
    const val maxDecodePixels = 4096L * 4096L

    /** Photos below this are placeholder/icon sized; showing one full screen looks like a bug. */
    const val minDimension = 64
}

// ---------------------------------------------------------------------------------------------
// Decode planning (pure, so the bounds/deflation policy is unit-testable without a device)
// ---------------------------------------------------------------------------------------------

data class ImageInfo(val width: Int, val height: Int)

/**
 * One `inSampleSize` pass followed by an exact scale.
 *
 * [inSampleSize] is a power of two >= 1, which is what `BitmapFactory` honours. `decode` bounds the
 * intermediate bitmap, `target` bounds the final one.
 */
data class DecodePlan(
    val source: ImageInfo,
    val targetWidth: Int,
    val targetHeight: Int,
    val inSampleSize: Int,
    val decodeWidth: Int,
    val decodeHeight: Int,
    val scaledWidth: Int,
    val scaledHeight: Int,
) {
    val scaleAfterSample: Float get() = scaledWidth.toFloat() / decodeWidth
}

data class DecodeFailure(val reason: String)

/**
 * Plans a screen-sized decode.
 *
 * [rotationQuarterTurns] is the EXIF orientation expressed in clockwise quarter turns; the source is
 * planned after that rotation, so a portrait photo of a portrait screen is not sampled for landscape.
 * Deflation stops at [BackgroundLimits.maxDecodePixels]: an 8000x8000 photo still decodes, only smaller.
 */
fun planDecode(
    source: ImageInfo,
    rotationQuarterTurns: Int,
    targetWidth: Int,
    targetHeight: Int,
): Result<DecodePlan> {
    if (source.width < 1 || source.height < 1) return Result.failure(BackgroundError("图片尺寸无效"))
    if (min(source.width, source.height) < BackgroundLimits.minDimension) return Result.failure(BackgroundError("图片尺寸过小"))
    val turns = ((rotationQuarterTurns % 4) + 4) % 4
    val swapped = turns % 2 == 1
    val orientedWidth = if (swapped) source.height else source.width
    val orientedHeight = if (swapped) source.width else source.height

    val wantedWidth = max(1, targetWidth)
    val wantedHeight = max(1, targetHeight)

    // Plan in "cover space": one shared factor shrinks the still-too-big photo (cover both axes, never more
    // than 1:1), so a 4:3 photo on a tall page stays a 4:3 bitmap that is centre-cropped at draw time.
    // Scaling only one axis would decode a much larger intermediate for the same visible result.
    val k = max(wantedWidth.toFloat() / orientedWidth, wantedHeight.toFloat() / orientedHeight).coerceAtMost(1f)
    val targetWidthInSource = max(1, (orientedWidth * k + .5f).toInt())
    val targetHeightInSource = max(1, (orientedHeight * k + .5f).toInt())

    val inSampleSize = max(1, floorPowerOfTwo(max(orientedWidth / targetWidthInSource, orientedHeight / targetHeightInSource)))
    // `BitmapFactory` returns ceil(dimension / inSampleSize) *independent of aspect ratio*, so the decoded
    // buffer can be narrower than the source aspect (8160/4 = 2040 while 6120/4 = 1530 is a 4:3 image).
    // Deriving the decode size from the turned source keeps the plan self-consistent; the final scale is
    // clamped to the intermediate, so the re-scale can only shrink pixels, never allocate more.
    var finalWidth = targetWidthInSource
    var finalHeight = targetHeightInSource
    var decodeWidth = max(1, (orientedWidth + inSampleSize - 1) / inSampleSize)
    var decodeHeight = max(1, (orientedHeight + inSampleSize - 1) / inSampleSize)
    finalWidth = min(finalWidth, decodeWidth)
    finalHeight = min(finalHeight, decodeHeight)
    val pixels = decodeWidth.toLong() * decodeHeight
    if (pixels > BackgroundLimits.maxDecodePixels) {
        // Only reachable for absurd page sizes: halving keeps the guarantee without rounding past the budget.
        while (decodeWidth.toLong() * decodeHeight > BackgroundLimits.maxDecodePixels && decodeWidth > 1 && decodeHeight > 1) {
            decodeWidth = max(1, decodeWidth / 2)
            decodeHeight = max(1, decodeHeight / 2)
        }
        finalWidth = min(finalWidth, decodeWidth)
        finalHeight = min(finalHeight, decodeHeight)
    }
    return Result.success(
        DecodePlan(
            source = source, targetWidth = wantedWidth, targetHeight = wantedHeight, inSampleSize = inSampleSize,
            decodeWidth = decodeWidth, decodeHeight = decodeHeight, scaledWidth = finalWidth, scaledHeight = finalHeight,
        )
    )
}

internal fun floorPowerOfTwo(value: Int): Int {
    var result = 1
    while (result < value && result shl 1 <= value) result = result shl 1
    return result
}

/** EXIF orientation (1..8) to clockwise quarter turns for display. Unknown values display unchanged. */
fun orientationQuarterTurns(exifOrientation: Int): Int = when (exifOrientation) {
    3, 4 -> 2
    5, 6 -> 1
    7, 8 -> 3
    else -> 0
}

/** Clockwise quarter turns to degrees, for `BitmapShader`/matrix users. */
fun quarterTurnsToDegrees(turns: Int) = ((turns % 4) + 4) % 4 * 90

class BackgroundError(message: String) : Exception(message)
