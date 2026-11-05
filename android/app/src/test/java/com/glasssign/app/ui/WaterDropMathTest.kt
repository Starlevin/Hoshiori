package com.glasssign.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the water-drop dock's pure maths.
 *
 * These cannot prove the material looks right on a GPU - that still needs a device run - but they do pin the
 * decisions that would otherwise fail silently: whether two lens rects actually merge, where a lens lands,
 * which slot a release commits to, and that a failure is reported exactly once.
 */
class WaterDropMathTest {

    // ---- the merge decision: the behaviour this dock exists for -------------------------------------

    /**
     * Coincident lenses have no gap and are one highlight.
     *
     * The resting dock must read as a single capsule, not a capsule plus a floating duplicate. This is the
     * case that has to hold every frame the finger is not down.
     */
    @Test
    fun coincidentLensesHaveNoGapAndMerge() {
        val lensWidth = 260f
        val left = 300f
        val gap = waterDropLensGapPx(left, left, lensWidth)
        assertEquals(0f, gap, 1e-4f)
        assertTrue(isWaterDropLensMerged(gap, mergeDistancePx = 54f))
    }

    /** Overlapping rects report no clearance at all, and still merge rather than splitting. */
    @Test
    fun overlappingLensesStillMerge() {
        val lensWidth = 260f
        val gap = waterDropLensGapPx(300f, 340f, lensWidth)
        assertEquals("an overlap has no clearance, so it must clamp to 0", 0f, gap, 1e-4f)
        assertTrue(isWaterDropLensMerged(gap, mergeDistancePx = 54f))
    }

    /**
     * Pulling the follower away separates the lenses once the gap reaches the merge distance.
     *
     * Asserts the boundary from both sides, because an off-by-one here is the difference between a dock that
     * bridges and one that pops apart with no transition at all.
     */
    @Test
    fun gapAtTheMergeDistanceSeparates() {
        val lensWidth = 260f
        val mergeDistance = 54f
        val anchorLeft = 100f

        val justUnder = anchorLeft + lensWidth + mergeDistance - 0.5f
        val atBoundary = anchorLeft + lensWidth + mergeDistance
        val beyond = anchorLeft + lensWidth + mergeDistance + 0.5f

        assertTrue(
            "just under the merge distance must still be one highlight",
            isWaterDropLensMerged(waterDropLensGapPx(anchorLeft, justUnder, lensWidth), mergeDistance),
        )
        assertFalse(
            "at the merge distance the highlight must split",
            isWaterDropLensMerged(waterDropLensGapPx(anchorLeft, atBoundary, lensWidth), mergeDistance),
        )
        assertFalse(
            "beyond the merge distance the lenses must be separate",
            isWaterDropLensMerged(waterDropLensGapPx(anchorLeft, beyond, lensWidth), mergeDistance),
        )
    }

    /**
     * The gap grows one-for-one with travel once the rects stop overlapping.
     *
     * While the follower still overlaps the anchor the gap is clamped to 0, and it only starts growing after
     * one full lens width of travel. Asserting the exact relationship - rather than just "it grows" - is what
     * pins the split point to a predictable distance.
     */
    @Test
    fun gapGrowsOneForOneWithTravel() {
        val lensWidth = 260f
        val anchorLeft = 120f
        var travel = 0f
        while (travel <= 600f) {
            val gap = waterDropLensGapPx(anchorLeft, anchorLeft + travel, lensWidth)
            val expected = (travel - lensWidth).coerceAtLeast(0f)
            assertEquals("travel=$travel", expected, gap, 1e-3f)
            travel += 25f
        }
    }

    /**
     * The split is reachable within the dock's own drag range on a narrow phone.
     *
     * The lenses are one slot wide, so the gap only opens up after a full slot of travel. Separation therefore
     * needs `lensWidth + mergeDistance` of drag, and this asserts that the dock's maximum travel (three slots,
     * from the first tab to the last) is comfortably larger than that. If it were not, the two lenses could
     * never come apart on a compact device and the whole effect would be invisible exactly where it is most
     * needed.
     */
    @Test
    fun splitIsReachableWithinTheDockDragRange() {
        // A narrow phone: 360dp wide, 6dp padding either side, four slots, 2.75x density.
        val lensWidth = waterDropLensWidthPx(barWidthPx = 360f * 2.75f, padPx = WaterDropLensPad.value * 2.75f)
        assertTrue("lens width must be positive", lensWidth > 0f)

        // 18dp at 2.75x density, matching the dock's merge distance.
        val mergeDistance = 18f * 2.75f
        // The follower starts on the anchor's slot and can travel to the farthest slot: three slots of roam.
        val maxTravel = lensWidth * (WaterDropSlots - 1)
        val travelToSplit = lensWidth + mergeDistance

        assertTrue(
            "a slot must be wider than the merge distance, or the split would need less than one slot of travel",
            lensWidth > mergeDistance,
        )
        assertTrue(
            "the dock must allow more travel ($maxTravel) than a split needs ($travelToSplit), " +
                "or the lenses could never separate on a narrow phone",
            maxTravel > travelToSplit,
        )
    }

    /** The split travel equals the merge distance; there is no hidden extra offset to tune. */
    @Test
    fun splitTravelEqualsTheMergeDistance() {
        assertEquals(54f, waterDropSplitTravelPx(54f), 1e-4f)
        assertTrue(waterDropSplitTravelPx(0f) == Float.POSITIVE_INFINITY)
        assertTrue(waterDropSplitTravelPx(Float.NaN) == Float.POSITIVE_INFINITY)
    }

    /** Broken inputs must never be read as "separated". */
    @Test
    fun nonFiniteInputsDoNotMerge() {
        assertFalse(isWaterDropLensMerged(Float.NaN, 54f))
        assertFalse(isWaterDropLensMerged(10f, Float.NaN))
        assertFalse("a non-positive merge distance cannot merge anything", isWaterDropLensMerged(0f, 0f))
        // Compared with `==`, not `assertEquals(.., delta)`: infinity minus infinity is NaN, so a delta
        // comparison would report a spurious failure on a value that is exactly right.
        assertTrue(
            "an unusable lens width must not report a finite gap",
            waterDropLensGapPx(100f, 200f, 0f) == Float.POSITIVE_INFINITY,
        )
    }

    /** The separation fraction is monotonic, bounded, and saturates exactly at the merge distance. */
    @Test
    fun separationFractionIsMonotonicAndBounded() {
        val mergeDistancePx = 54f
        var previous = -1f
        var gap = 0f
        while (gap <= mergeDistancePx + 20f) {
            val fraction = waterDropSeparationFraction(gap, mergeDistancePx)
            assertTrue("fraction must be within 0..1 at gap=$gap", fraction in 0f..1f)
            assertTrue("fraction must not decrease at gap=$gap", fraction >= previous)
            previous = fraction
            gap += 2f
        }
        assertEquals(0f, waterDropSeparationFraction(0f, mergeDistancePx), 1e-4f)
        assertEquals(1f, waterDropSeparationFraction(mergeDistancePx, mergeDistancePx), 1e-4f)
        assertEquals(1f, waterDropSeparationFraction(mergeDistancePx * 3f, mergeDistancePx), 1e-4f)
    }

    // ---- dock and lens geometry --------------------------------------------------------------------

    /** The base keeps the stable dock's full height, including at 2x accessibility text. */
    @Test
    fun baseKeepsTheStableDockHeight() {
        assertEquals(liquidDockHeight(1f).value, waterDropBaseHeight(1f).value, 1e-4f)
        assertEquals(liquidDockHeight(2f).value, waterDropBaseHeight(2f).value, 1e-4f)
        assertTrue(
            "2x font scale must grow the dock rather than squeeze its contents",
            waterDropBaseHeight(2f).value > waterDropBaseHeight(1f).value,
        )
    }

    /**
     * A lens always fits inside the base, with an inset at the bottom.
     *
     * This is the constraint that keeps the four nav items on a consistent background: a lens that overflowed
     * the capsule would make the selected item's silhouette differ from the others.
     */
    @Test
    fun lensFitsInsideTheBaseWithAnInset() {
        for (fontScale in listOf(1f, 1.3f, 1.5f, 2f)) {
            val base = waterDropBaseHeight(fontScale)
            val lens = waterDropLensHeight(base)
            val inset = waterDropLensInset(base)
            assertTrue("fontScale=$fontScale lens must be shorter than the base", lens.value < base.value)
            assertTrue("fontScale=$fontScale lens must be positive", lens.value > 0f)
            assertTrue("fontScale=$fontScale inset must be positive", inset.value > 0f)
            assertTrue(
                "fontScale=$fontScale lens plus its inset must fit in the base",
                lens.value + inset.value <= base.value + 1e-3f,
            )
        }
    }

    /** The lens fills an equal share of the dock's inner width, so one slot equals one lens. */
    @Test
    fun lensWidthSplitsTheInnerWidthEvenly() {
        val barWidth = 900f
        val pad = 18f
        assertEquals((barWidth - pad * 2f) / WaterDropSlots, waterDropLensWidthPx(barWidth, pad), 1e-3f)
    }

    /** A dock with no usable inner width yields no lens rather than a negative one. */
    @Test
    fun degenerateWidthYieldsNoLens() {
        assertEquals(0f, waterDropLensWidthPx(0f, 18f), 1e-4f)
        assertEquals(0f, waterDropLensWidthPx(10f, 18f), 1e-4f)
        assertEquals(0f, waterDropLensWidthPx(Float.NaN, 18f), 1e-4f)
    }

    /** Slot 0 starts after the padding, and the last slot ends flush with the far padding. */
    @Test
    fun slotEdgesLandOnThePaddedDockEnds() {
        val barWidth = 900f
        val pad = 18f
        val width = waterDropLensWidthPx(barWidth, pad)

        assertEquals(pad, waterDropLensLeftPx(0, barWidth, pad), 1e-3f)
        assertEquals(
            pad + width * (WaterDropSlots - 1),
            waterDropLensLeftPx(WaterDropSlots - 1, barWidth, pad),
            1e-3f,
        )
        assertEquals(barWidth - pad, waterDropLensLeftPx(WaterDropSlots - 1, barWidth, pad) + width, 1e-2f)
    }

    /** An out-of-range slot is clamped instead of drawing the lens off the dock. */
    @Test
    fun outOfRangeSlotIsClamped() {
        val barWidth = 900f
        val pad = 18f
        assertEquals(waterDropLensLeftPx(0, barWidth, pad), waterDropLensLeftPx(-7, barWidth, pad), 1e-3f)
        assertEquals(
            waterDropLensLeftPx(WaterDropSlots - 1, barWidth, pad),
            waterDropLensLeftPx(WaterDropSlots + 99, barWidth, pad),
            1e-3f,
        )
    }

    /** The fractional variant agrees with the integer variant on whole slots, and never leaves the dock. */
    @Test
    fun lerpAgreesWithSlotsAndStaysInside() {
        val barWidth = 900f
        val pad = 18f
        val width = waterDropLensWidthPx(barWidth, pad)
        val min = pad
        val max = pad + width * (WaterDropSlots - 1)

        for (slot in 0 until WaterDropSlots) {
            assertEquals(
                waterDropLensLeftPx(slot, barWidth, pad),
                waterDropLensLeftPxLerp(slot.toFloat(), barWidth, pad),
                1e-3f,
            )
        }
        for (cells in listOf(-100f, -1f, 0f, 1.5f, 99f, Float.NaN)) {
            val left = waterDropLensLeftPxLerp(cells, barWidth, pad)
            assertTrue("left=$left out of range for cells=$cells", left >= min - 1e-3f && left <= max + 1e-3f)
        }
    }

    /** The resting lenses really do coincide for every slot: that is what makes one highlight. */
    @Test
    fun everySlotIsACoincidentMerge() {
        val barWidth = 900f
        val pad = 18f
        val width = waterDropLensWidthPx(barWidth, pad)
        val mergeDistance = 54f
        for (slot in 0 until WaterDropSlots) {
            val left = waterDropLensLeftPx(slot, barWidth, pad)
            val gap = waterDropLensGapPx(left, left, width)
            assertTrue("slot=$slot must rest merged", isWaterDropLensMerged(gap, mergeDistance))
        }
    }

    // ---- gesture behaviour -------------------------------------------------------------------------

    /** Dragging by exactly one cell moves the follower by one slot from where the gesture started. */
    @Test
    fun travelIsRelativeToTheAnchorSlot() {
        assertEquals(1f, waterDropTravelCells(anchorSlot = 0, pointerCellOffset = 1f), 1e-4f)
        assertEquals(3f, waterDropTravelCells(anchorSlot = 2, pointerCellOffset = 1f), 1e-4f)
        assertEquals(2f, waterDropTravelCells(anchorSlot = 3, pointerCellOffset = -1f), 1e-4f)
    }

    /** Dragging past either end parks the follower on the last slot instead of running off the dock. */
    @Test
    fun travelClampsAtBothEnds() {
        assertEquals(WaterDropMaxSlot, waterDropTravelCells(0, 99f), 1e-4f)
        assertEquals(0f, waterDropTravelCells(WaterDropSlots - 1, -99f), 1e-4f)
    }

    /** A non-finite pointer offset leaves the follower on its anchor rather than throwing. */
    @Test
    fun travelHandlesNonFiniteOffset() {
        assertEquals(2f, waterDropTravelCells(2, Float.NaN), 1e-4f)
        assertEquals(2f, waterDropTravelCells(2, Float.POSITIVE_INFINITY), 1e-4f)
    }

    /** A fractional position snaps to the nearest slot. */
    @Test
    fun restSlotRoundsToNearest() {
        assertEquals(0, waterDropRestSlot(0.2f))
        assertEquals(1, waterDropRestSlot(0.6f))
        assertEquals(2, waterDropRestSlot(2.49f))
        assertEquals(3, waterDropRestSlot(2.9f))
    }

    /** A non-finite position falls back to slot 0 instead of throwing out of the gesture. */
    @Test
    fun restSlotHandlesNonFinite() {
        assertEquals(0, waterDropRestSlot(Float.NaN))
        assertEquals(0, waterDropRestSlot(Float.NEGATIVE_INFINITY))
    }

    /** A release inside the anchor's own slot commits nothing, which keeps a tap from navigating. */
    @Test
    fun releaseOnTheAnchorSlotCommitsTheSameSlot() {
        assertEquals(2, waterDropReleaseSlot(anchorSlot = 2, positionCells = 2.4f))
        assertEquals(2, waterDropReleaseSlot(anchorSlot = 2, positionCells = 1.8f))
    }

    /** A release on a different slot commits that slot, so one flick selects one page. */
    @Test
    fun releaseOnAnotherSlotCommitsIt() {
        assertEquals(3, waterDropReleaseSlot(anchorSlot = 2, positionCells = 2.7f))
        assertEquals(0, waterDropReleaseSlot(anchorSlot = 1, positionCells = 0.1f))
    }

    /** A release never skips a neighbour: a half-cell nudge lands on the adjacent slot. */
    @Test
    fun releaseNeverSkipsANeighbour() {
        val committed = waterDropReleaseSlot(anchorSlot = 1, positionCells = 1.5f)
        assertNotEquals(1, committed)
        assertTrue("a half-cell nudge must land on a neighbour", committed == 0 || committed == 2)
    }

    /** Clamping a slot coordinate protects every downstream layout call. */
    @Test
    fun clampHandlesNonFiniteAndOutOfRange() {
        assertEquals(0f, clampWaterDropSlot(Float.NaN), 1e-4f)
        assertEquals(0f, clampWaterDropSlot(-3f), 1e-4f)
        assertEquals(WaterDropMaxSlot, clampWaterDropSlot(99f), 1e-4f)
        assertEquals(2f, clampWaterDropSlot(2f), 1e-4f)
    }

    /** `roundToIntSafe` must not throw on the extremes a mid-gesture frame can produce. */
    @Test
    fun roundToIntSafeHandlesExtremes() {
        assertEquals(0, Float.NaN.roundToIntSafe())
        assertEquals(0, Float.NEGATIVE_INFINITY.roundToIntSafe())
        assertEquals(Int.MAX_VALUE, (Int.MAX_VALUE.toFloat() * 2f).roundToIntSafe())
        assertEquals(Int.MIN_VALUE, (Int.MIN_VALUE.toFloat() * 2f).roundToIntSafe())
        assertEquals(3, 2.6f.roundToIntSafe())
    }

    // ---- failure reporting -------------------------------------------------------------------------

    /** Each failure reason produces a distinct, non-empty explanation. */
    @Test
    fun everyFailureHasItsOwnMessage() {
        val messages = WaterDropFailure.entries.map { describeWaterDropFailure(it) }
        assertEquals(WaterDropFailure.entries.size, messages.toSet().size)
        assertTrue(messages.all { it.isNotBlank() })
    }

    /** A detail is appended so logs can carry the underlying cause, and blank details are ignored. */
    @Test
    fun failureDetailIsAppended() {
        val message = describeWaterDropFailure(WaterDropFailure.EffectFailed, "boom")
        assertTrue(message.contains("boom"))
        assertEquals(
            describeWaterDropFailure(WaterDropFailure.EffectFailed),
            describeWaterDropFailure(WaterDropFailure.EffectFailed, "   "),
        )
    }

    /**
     * A failure raised from the draw phase is reported once per distinct message, not once per frame.
     *
     * Without this the caller's error handling would be invoked ~60 times a second by a single broken
     * material, which is exactly the kind of self-inflicted storm that hides the real failure.
     */
    @Test
    fun errorLatchReportsOnceUntilReset() {
        val latch = WaterDropErrorLatch()
        assertTrue(latch.shouldReport("a"))
        assertFalse(latch.shouldReport("a"))
        assertFalse(latch.shouldReport("a"))
        assertTrue("a different message must still be reported", latch.shouldReport("b"))
        latch.reset()
        assertTrue("after a reset the same message may be reported again", latch.shouldReport("b"))
        assertEquals("b", latch.lastMessage)
    }
}
