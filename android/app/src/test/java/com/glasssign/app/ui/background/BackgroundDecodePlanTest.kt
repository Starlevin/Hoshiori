package com.glasssign.app.ui.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decode policy for a screen-sized wallpaper bitmap.
 *
 * These assertions are the JVM expression of the OOM guard: whatever the photo is (12 MP, 50 MP, a rotated
 * phone shot, a strip), the plan handed to `BitmapFactory` must stay inside
 * [BackgroundLimits.maxDecodePixels], never upscale, and never ask for a zero-sized bitmap.
 */
class BackgroundDecodePlanTest {
    private val portraitScreen = 1080 to 2400

    @Test fun twelveMegapixelPhotoStaysWithinThePageAndThePixelBudget() {
        val plan = planDecode(ImageInfo(4000, 3000), 0, portraitScreen.first, portraitScreen.second).getOrThrow()
        // A 4:3 photo cannot fill a 9:20 page without either decoding 4000 wide (clipping 83 % of it) or
        // cropping; keeping the source aspect and centre-cropping at draw time bounds the bitmap instead.
        assertEquals(4f / 3f, plan.scaledWidth.toFloat() / plan.scaledHeight, .01f)
        assertTrue("plan must shrink a 12 MP photo", plan.scaledWidth * plan.scaledHeight <= 4000 * 3000)
        assertTrue(plan.scaledWidth <= 4000 && plan.scaledHeight <= 3000)
        assertTrue(plan.inSampleSize in listOf(1, 2, 4))
        assertTrue(plan.decodeWidth.toLong() * plan.decodeHeight <= BackgroundLimits.maxDecodePixels)
    }

    @Test fun fiftyMegapixelPhotoStillPlansASmallIntermediate() {
        val plan = planDecode(ImageInfo(8160, 6120), 0, 1079, 2340).getOrThrow()
        assertTrue(plan.inSampleSize in listOf(1, 2, 4, 8, 16, 32, 64))
        assertTrue(plan.decodeWidth >= 1 && plan.decodeHeight >= 1)
        assertTrue(plan.scaledWidth >= 1 && plan.scaledHeight >= 1)
        assertTrue(
            "post-sample scale may only shrink: ${plan.scaledWidth}x${plan.scaledHeight} from ${plan.decodeWidth}x${plan.decodeHeight}",
            plan.scaledWidth <= plan.decodeWidth && plan.scaledHeight <= plan.decodeHeight,
        )
        assertTrue(plan.scaleAfterSample in 0.01f..1f)
    }

    @Test fun pathologicalPixelsAreCappedByThePixelBudget() {
        val plan = planDecode(ImageInfo(40000, 40000), 0, 1080, 2400).getOrThrow()
        assertTrue(
            "decode budget exceeded: ${plan.decodeWidth}x${plan.decodeHeight} = ${plan.decodeWidth.toLong() * plan.decodeHeight}",
            plan.decodeWidth.toLong() * plan.decodeHeight <= BackgroundLimits.maxDecodePixels,
        )
        assertTrue(plan.inSampleSize > 1)
    }

    @Test fun smallPhotoIsNeverUpscaled() {
        val plan = planDecode(ImageInfo(320, 240), 0, 1080, 2400).getOrThrow()
        assertEquals(1, plan.inSampleSize)
        assertEquals(320, plan.scaledWidth)
        assertEquals(240, plan.scaledHeight)
    }

    @Test fun rotatedPhotoIsPlannedAfterRotation() {
        // A 4000x3000 landscape file tagged EXIF 6 displays as 3000x4000 portrait.
        val rotated = planDecode(ImageInfo(4000, 3000), 1, 1080, 2400).getOrThrow()
        assertTrue("portrait screen wants portrait plan", rotated.scaledHeight > rotated.scaledWidth)
        val unrotated = planDecode(ImageInfo(4000, 3000), 0, 1080, 2400).getOrThrow()
        assertTrue("landscape screen plan stays landscape", unrotated.scaledWidth > unrotated.scaledHeight)
        // Same pixel budget for both: rotation changes the shape, not the cost.
        assertEquals(rotated.inSampleSize, unrotated.inSampleSize)
    }

    @Test fun exactScreenSizedPhotoNeedsNoSampling() {
        val plan = planDecode(ImageInfo(1080, 2400), 0, 1080, 2400).getOrThrow()
        assertEquals(1, plan.inSampleSize)
        assertEquals(1080, plan.scaledWidth)
        assertEquals(2400, plan.scaledHeight)
        assertEquals(1f, plan.scaleAfterSample, 0.001f)
    }

    @Test fun aPhotoNarrowerThanThePageIsNotBlownUpOrCropped() {
        // 1080x4000 on a 1080x2400 page: the photo is already <= one page axis, so it is decoded 1:1 and
        // `ContentScale.Crop` handles the few percent of clipped sides at draw time.
        val plan = planDecode(ImageInfo(1080, 4000), 0, 1080, 2400).getOrThrow()
        assertEquals(1, plan.inSampleSize)
        assertEquals(1080, plan.scaledWidth)
        assertEquals(4000, plan.scaledHeight)
        assertEquals(1f, plan.scaleAfterSample, .01f)
    }

    @Test fun unusableImagesAreRejectedInsteadOfDecoded() {
        assertTrue(planDecode(ImageInfo(0, 0), 0, 1080, 2400).isFailure)
        assertTrue(planDecode(ImageInfo(-5, 100), 0, 1080, 2400).isFailure)
        assertTrue(planDecode(ImageInfo(32, 32), 0, 1080, 2400).isFailure)
    }

    @Test fun zeroPageSizeIsClampedNotCrashed() {
        val plan = planDecode(ImageInfo(1000, 1000), 0, 0, 0).getOrThrow()
        assertTrue(plan.scaledWidth >= 1 && plan.scaledHeight >= 1)
        assertTrue(plan.decodeWidth >= 1 && plan.decodeHeight >= 1)
    }

    @Test fun sampleSizeIsAlwaysAPowerOfTwoAndScalingOnlyShrinks() {
        for (width in listOf(200, 1500, 4000, 4001, 8192, 12345)) {
            for (height in listOf(200, 1500, 3000, 9999)) {
                val plan = planDecode(ImageInfo(width, height), 0, 1080, 2400).getOrThrow()
                val sample = plan.inSampleSize
                assertTrue("not a power of two: $sample", sample > 0 && sample and (sample - 1) == 0)
                // The re-scale may never allocate a bigger bitmap than the sampled one.
                assertTrue(
                    "upscaling plan for $width x $height: ${plan.scaledWidth}x${plan.scaledHeight} from ${plan.decodeWidth}x${plan.decodeHeight}",
                    plan.scaledWidth <= plan.decodeWidth && plan.scaledHeight <= plan.decodeHeight,
                )
                assertTrue("budget for $width x $height", plan.decodeWidth.toLong() * plan.decodeHeight <= BackgroundLimits.maxDecodePixels)
                assertTrue("never upscales the source", plan.scaledWidth <= width && plan.scaledHeight <= height)
            }
        }
    }

    @Test fun exifOrientationMapsToClockwiseTurns() {
        assertEquals(0, orientationQuarterTurns(1))
        assertEquals(2, orientationQuarterTurns(3))
        assertEquals(1, orientationQuarterTurns(6))
        assertEquals(3, orientationQuarterTurns(8))
        assertEquals(0, orientationQuarterTurns(0))
        assertEquals(0, quarterTurnsToDegrees(4))
        assertEquals(270, quarterTurnsToDegrees(3))
    }

    @Test fun sourceLimitsAreFiniteAndSmall() {
        assertTrue(BackgroundLimits.maxSourceBytes in 1..(64L * 1024 * 1024))
        assertTrue(BackgroundLimits.maxDecodePixels in 1..(64L * 1024 * 1024))
        assertFalse(BackgroundLimits.maxDecodePixels >= 4096L * 4096L * 2)
    }
}
