package com.glasssign.app.ui

/*
 * Pure, Android- and Compose-free maths for the water-drop dock.
 *
 * Everything here is deliberately free of framework types so it can be unit-tested on the JVM, which is the
 * only part of this experiment that can be verified without a GPU. Sizes that matter (dock height, lens
 * height, merge distance) are passed in rather than duplicated, so a change to the dock's geometry cannot
 * silently disagree with the tests.
 */

/** Number of dock slots. Re-exported so this file's maths and the dock cannot drift apart. */
internal val WaterDropSlots = DockSlots

/** The largest slot index, as a float, used for clamping every slot computation. */
internal val WaterDropMaxSlot: Float get() = (WaterDropSlots - 1).toFloat()

/** Clamps a slot coordinate into the dock's real slot range. */
internal fun clampWaterDropSlot(cells: Float): Float = when {
    !cells.isFinite() -> 0f
    cells < 0f -> 0f
    cells > WaterDropMaxSlot -> WaterDropMaxSlot
    else -> cells
}

/**
 * Clear horizontal space between the two lens rects, in px.
 *
 * This is the single input that decides whether Haze draws one highlight or two, so it is modelled
 * explicitly instead of being implied by layout code. Both lenses are the same width and sit at the same Y,
 * so the gap is the distance between their left edges minus one width.
 *
 * The result is CLAMPED at 0: coincident or overlapping rects report exactly 0 rather than a negative
 * number. That matches how Haze treats them - there is no negative clearance, the rects simply touch or
 * overlap - and it keeps the comparison against `mergeDistance` meaningful at the resting state, where the
 * two lenses sit on the same slot.
 *
 * @param anchorLeftPx left edge of the anchored lens (A).
 * @param travelLeftPx left edge of the follower (B).
 * @param lensWidthPx width of one lens.
 */
internal fun waterDropLensGapPx(anchorLeftPx: Float, travelLeftPx: Float, lensWidthPx: Float): Float {
    // A lens with no width has no meaningful rect, so it must not report a gap of 0 - that would read as
    // "merged" and could draw a highlight over nothing. Checked before the finite test below, because 0 is
    // perfectly finite and would otherwise slip through.
    if (lensWidthPx <= 0f || !lensWidthPx.isFinite()) return Float.POSITIVE_INFINITY
    if (!anchorLeftPx.isFinite() || !travelLeftPx.isFinite()) return 0f
    val left = minOf(anchorLeftPx, travelLeftPx)
    val right = maxOf(anchorLeftPx, travelLeftPx)
    return ((right - left) - lensWidthPx).coerceAtLeast(0f)
}

/**
 * Whether the two lenses are drawn as one merged highlight.
 *
 * Haze's rule is that members cluster while the gap between their rects is strictly less than the
 * container's `mergeDistance`. Overlapping or coincident rects have a gap <= 0, so they always merge -
 * which is why the resting dock must show a single highlight. Reproducing the strictness at exactly
 * `mergeDistance` means the test asserts the same boundary the library uses.
 */
internal fun isWaterDropLensMerged(gapPx: Float, mergeDistancePx: Float): Boolean = when {
    !gapPx.isFinite() || !mergeDistancePx.isFinite() -> false
    mergeDistancePx <= 0f -> false
    else -> gapPx < mergeDistancePx
}

/**
 * The travel, in px, at which the lenses stop merging.
 *
 * Derived rather than guessed: the gap starts at 0 when they coincide and grows one-for-one with travel, so
 * the split happens at `mergeDistance` of travel. Used by tests to assert that a drag can actually reach the
 * split inside one slot, and by nothing in the render path - the render path just lets Haze measure.
 */
internal fun waterDropSplitTravelPx(mergeDistancePx: Float): Float =
    if (!mergeDistancePx.isFinite() || mergeDistancePx <= 0f) Float.POSITIVE_INFINITY else mergeDistancePx

/**
 * Fraction of the way from "merged" to "split", in `0f..1f`.
 *
 * Saturates exactly at the merge distance rather than at the end of the travel, so any secondary cue driven
 * off it completes at the same moment the container splits.
 */
internal fun waterDropSeparationFraction(gapPx: Float, mergeDistancePx: Float): Float = when {
    !gapPx.isFinite() || !mergeDistancePx.isFinite() || mergeDistancePx <= 0f -> 0f
    gapPx <= 0f -> 0f
    gapPx >= mergeDistancePx -> 1f
    else -> gapPx / mergeDistancePx
}

// ---- geometry derived from the dock height ---------------------------------------------------------
//
// The dock's height is the only driver: `liquidDockHeight(fontScale)` returns 60dp at fontScale 1 and grows
// with accessibility text. Every other size is a fraction of it, so a 200% font scale produces a taller
// complete capsule rather than a stretched one.

/** Base capsule height. The dock's real height; never reduced by an advanced effect. */
internal fun waterDropBaseHeight(fontScale: Float) = liquidDockHeight(fontScale)

/**
 * The horizontal band a lens keeps from the dock's edge.
 *
 * Shared with the layout so the lens-width maths the tests drive and the rendered geometry cannot drift
 * apart. Changing it here moves the lenses and the tests together.
 */
internal val WaterDropLensPad = androidx.compose.ui.unit.Dp(6f)

/** Lens height as a fraction of the base, leaving a visible inset above and below. */
internal const val WaterDropLensHeightRatio = 52f / 60f

/** Bottom inset of a lens inside the base, as a fraction of the base height. */
internal const val WaterDropLensInsetRatio = 4f / 60f

/** Lens height for a given dock height. */
internal fun waterDropLensHeight(base: androidx.compose.ui.unit.Dp) = base * WaterDropLensHeightRatio

/** Bottom inset for a given dock height. */
internal fun waterDropLensInset(base: androidx.compose.ui.unit.Dp) = base * WaterDropLensInsetRatio

/** Width of one lens, in px: the dock's inner width split across the slots. */
internal fun waterDropLensWidthPx(barWidthPx: Float, padPx: Float): Float {
    if (!barWidthPx.isFinite() || !padPx.isFinite()) return 0f
    val inner = barWidthPx - padPx * 2f
    if (inner <= 0f) return 0f
    return inner / WaterDropSlots
}

/** Left edge of a lens for [slot], in px, measured from the dock's left edge. */
internal fun waterDropLensLeftPx(slot: Int, barWidthPx: Float, padPx: Float): Float {
    if (!barWidthPx.isFinite() || !padPx.isFinite()) return 0f
    val width = waterDropLensWidthPx(barWidthPx, padPx)
    val clamped = slot.coerceIn(0, WaterDropSlots - 1)
    return padPx + width * clamped
}

/** Left edge of a lens for a fractional slot coordinate, in px, clamped to the dock. */
internal fun waterDropLensLeftPxLerp(cells: Float, barWidthPx: Float, padPx: Float): Float {
    if (!barWidthPx.isFinite() || !padPx.isFinite()) return 0f
    val width = waterDropLensWidthPx(barWidthPx, padPx)
    return padPx + width * clampWaterDropSlot(cells)
}

/**
 * Converts the finger's horizontal offset from the press point into a slot coordinate.
 *
 * The follower keeps its offset from the finger, so it moves by the same number of slots the finger
 * travelled. Clamped, so a drag past the end parks on the last slot instead of running off the dock.
 */
internal fun waterDropTravelCells(anchorSlot: Int, pointerCellOffset: Float): Float {
    val anchor = anchorSlot.coerceIn(0, WaterDropSlots - 1).toFloat()
    if (!pointerCellOffset.isFinite()) return anchor
    return clampWaterDropSlot(anchor + pointerCellOffset)
}

/** The slot the lenses snap to for a fractional slot coordinate. */
internal fun waterDropRestSlot(positionCells: Float): Int {
    if (!positionCells.isFinite()) return 0
    return positionCells.roundToIntSafe().coerceIn(0, WaterDropSlots - 1)
}

/** Which slot a release commits to: the nearest slot, or the anchor when the gesture never left it. */
internal fun waterDropReleaseSlot(anchorSlot: Int, positionCells: Float): Int {
    val safeAnchor = anchorSlot.coerceIn(0, WaterDropSlots - 1)
    val rest = waterDropRestSlot(positionCells)
    return if (rest == safeAnchor) safeAnchor else rest
}

/** `kotlin.math.round` without the throw: the drag maths can hand us a NaN mid-gesture. */
internal fun Float.roundToIntSafe(): Int = when {
    !this.isFinite() -> 0
    this <= Int.MIN_VALUE.toFloat() -> Int.MIN_VALUE
    this >= Int.MAX_VALUE.toFloat() -> Int.MAX_VALUE
    else -> kotlin.math.round(this).toInt()
}

/** Why the GPU path could not be used. Each maps to one actionable message. */
internal enum class WaterDropFailure {
    /** API level below the experiment's floor. */
    Unsupported,

    /** The material could not be built at all. */
    MaterialUnavailable,

    /** Building the effect for this frame threw. */
    EffectFailed,
}

/** A single, user-visible explanation of a failure. Never used for business exceptions. */
internal fun describeWaterDropFailure(failure: WaterDropFailure, detail: String? = null): String {
    val base = when (failure) {
        WaterDropFailure.Unsupported -> "当前系统版本不支持水滴玻璃实验（需要 Android 13 及以上）"
        WaterDropFailure.MaterialUnavailable -> "水滴玻璃材质无法创建，已回退到稳定底栏"
        WaterDropFailure.EffectFailed -> "水滴玻璃渲染失败，已回退到稳定底栏"
    }
    val trimmed = detail?.trim()
    return if (trimmed.isNullOrEmpty()) base else "$base（$trimmed）"
}

/**
 * Reports a given message at most once until [reset].
 *
 * A render failure is reported from the draw phase, which runs every frame; without this latch a single
 * broken material would call back sixty times a second and flood the caller's own error handling.
 */
internal class WaterDropErrorLatch {
    var lastMessage: String? = null
        private set

    /** True when [message] differs from the last one reported, and records it. */
    fun shouldReport(message: String): Boolean {
        if (message == lastMessage) return false
        lastMessage = message
        return true
    }

    /** Allows the same message to be reported again after an explicit recovery. */
    fun reset() {
        lastMessage = null
    }
}
