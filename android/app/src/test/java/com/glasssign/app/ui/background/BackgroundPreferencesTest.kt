package com.glasssign.app.ui.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Settings compatibility for the three new keys.
 *
 * A 0.7.5 preference file has no background keys at all; every read of `null`/missing must produce "no
 * background" with the documented defaults, and every value that can come back from a hand-edited or
 * partially-written XML must be clamped instead of crashing the theme. The stored key names are asserted
 * here because they are the only contract between this module and the preferences file.
 */
class BackgroundPreferencesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun missingKeysReadAsNoBackground() {
        val decoded = BackgroundPreferences.decode(source = null, mask = null, blur = null)
        assertEquals(BackgroundSource.none, decoded.source)
        assertEquals(BackgroundPreferences.defaultMask, decoded.maskStrength, 0f)
        assertEquals(BackgroundPreferences.defaultBlur, decoded.blurDp, 0f)
        // Defaults still have to be valid render inputs (no background => no dimming).
        assertTrue(decoded.maskStrength <= .35f)
        assertEquals(0f, decoded.blurDp, 0f)
    }

    @Test fun unknownSourceFallsBackToNoBackground() {
        for (value in listOf("", "Custom", "PHOTO", "bundled_v2", "null")) {
            assertEquals("unexpected source for '$value'", BackgroundSource.none, BackgroundPreferences.decode(value, null, null).source)
        }
        assertEquals(BackgroundSource.custom, BackgroundPreferences.decode("custom", null, null).source)
        assertEquals(BackgroundSource.none, BackgroundPreferences.decode("starfield", null, null).source)
        assertEquals(BackgroundSource.none, BackgroundPreferences.decode("bundled", null, null).source)
    }

    @Test fun enabledTracksTheSourceOnly() {
        // The card-alpha consumer keys on this flag, so it must be false for every "no wallpaper" path
        // and true only when a custom photo is selected.
        assertFalse(BackgroundPreferences().enabled)
        assertFalse(BackgroundPreferences(maskStrength = .8f, blurDp = 20f).enabled)
        assertTrue(BackgroundPreferences(source = BackgroundSource.custom).enabled)
    }

    @Test fun removedBundledBackgroundsMigrateToNoBackground() {
        // Existing installs may still have the old bundled source saved. Removing that option must not
        // leave an unsupported source selected or disturb custom photos.
        assertEquals(BackgroundSource.none, BackgroundPreferences.decode("starfield", .22f, 0f).source)
        assertEquals(BackgroundSource.none, BackgroundPreferences.decode("bundled", .22f, 0f).source)
        assertEquals(BackgroundSource.custom, BackgroundPreferences.decode("custom", .22f, 0f).source)
    }

    @Test fun migrationKeepsTheUsersMaskAndBlurAndCustomPhoto() {
        // A legacy "starfield" install may have tuned the sliders. The source must migrate to `none`
        // while the two render values survive, so re-selecting a custom photo later keeps its look.
        val migrated = BackgroundPreferences.decode("starfield", mask = .4f, blur = 12f)
        assertEquals(BackgroundSource.none, migrated.source)
        assertEquals(.4f, migrated.maskStrength, 0f)
        assertEquals(12f, migrated.blurDp, 0f)
        assertFalse(migrated.enabled)
        val custom = BackgroundPreferences.decode("custom", mask = .6f, blur = 18f)
        assertEquals(BackgroundSource.custom, custom.source)
        assertEquals(.6f, custom.maskStrength, 0f)
        assertEquals(18f, custom.blurDp, 0f)
        assertTrue(custom.enabled)
        assertEquals("custom", custom.source.name)
    }

    @Test fun onlyTheRemovedEnumValuesAreGone() {
        // The enum is the persisted contract: any stored string other than "custom" is now `none`.
        assertEquals(2, BackgroundSource.values().size)
        assertEquals(setOf("none", "custom"), BackgroundSource.values().map { it.name }.toSet())
    }

    @Test fun oneDraftKeepsBothSliderValuesAcrossTwoGestures() {
        // The dialog holds a single draft while a slider moves: dragging mask then blur must not reset
        // the other value to the committed one. `copy` is the exact operation the sliders use.
        val committed = BackgroundPreferences(source = BackgroundSource.custom, maskStrength = .22f, blurDp = 0f)
        val afterMask = committed.copy(maskStrength = .5f)
        val afterBlur = afterMask.copy(blurDp = 14f)
        assertEquals(.5f, afterBlur.maskStrength, 0f)
        assertEquals(14f, afterBlur.blurDp, 0f)
        assertEquals(BackgroundSource.custom, afterBlur.source)
        // The reverse order is equally safe.
        val blurFirst = committed.copy(blurDp = 9f).copy(maskStrength = .7f)
        assertEquals(9f, blurFirst.blurDp, 0f)
        assertEquals(.7f, blurFirst.maskStrength, 0f)
    }

    @Test fun outOfRangeValuesAreClampedNotTrusted() {
        val high = BackgroundPreferences.decode("custom", mask = 4f, blur = 400f).sanitized()
        assertEquals(BackgroundPreferences.MaskRange.endInclusive, high.maskStrength, 0f)
        assertEquals(BackgroundPreferences.BlurRange.endInclusive, high.blurDp, 0f)
        val low = BackgroundPreferences.decode("custom", mask = -3f, blur = -1f).sanitized()
        assertEquals(0f, low.maskStrength, 0f)
        assertEquals(0f, low.blurDp, 0f)
        val nan = BackgroundPreferences(maskStrength = Float.NaN, blurDp = Float.NaN).sanitized()
        assertEquals(0f, nan.maskStrength, 0f)
        assertEquals(0f, nan.blurDp, 0f)
    }

    @Test fun preferenceKeysDoNotCollideWithTheExistingOnes() {
        val existing = setOf(
            "glass_mode", "mode", "pure_black", "color_style", "seed", "dynamic", "surface_effect",
            "navigation_tint", "scale_enabled", "text_scale", "font", "custom_colors",
        )
        val added = setOf(BackgroundPreferencesStore.keySource, BackgroundPreferencesStore.keyMask, BackgroundPreferencesStore.keyBlur)
        assertEquals(3, added.size)
        assertTrue("new keys must not overwrite v0.7.5 appearance keys", (added intersect existing).isEmpty())
        // Stable names: renaming one silently loses every user's choice on upgrade.
        assertEquals("background_source", BackgroundPreferencesStore.keySource)
        assertEquals("background_mask", BackgroundPreferencesStore.keyMask)
        assertEquals("background_blur", BackgroundPreferencesStore.keyBlur)
    }

    @Test fun defaultPreferencesHaveNoBackgroundAndNoEffect() {
        val defaults = BackgroundPreferences()
        assertEquals(BackgroundSource.none, defaults.source)
        assertFalse("default must stay pixel-compatible with v0.7.5", defaults.blurDp > 0f)
        assertNotEquals(BackgroundPreferences.defaultMask, 0f)
        assertTrue(BackgroundPreferences.MaskRange.endInclusive < 1f)
    }

    @Test fun customWithoutAStoredFileDemotesToNone() {
        val missing = BackgroundFileStore(temp.newFolder("empty-files"))
        assertEquals(BackgroundSource.none, BackgroundPreferences(source = BackgroundSource.custom).withoutMissingCustomImage(missing).source)
        assertEquals(BackgroundSource.none, BackgroundPreferences().withoutMissingCustomImage(missing).source)
        val present = BackgroundFileStore(temp.newFolder("filled-files"))
        val jpeg = jpegBytes()
        present.save(jpeg.inputStream(), jpeg.size.toLong())
        assertEquals(BackgroundSource.custom, BackgroundPreferences(source = BackgroundSource.custom).withoutMissingCustomImage(present).source)
    }

    @Test fun maskAndBlurAreIndependentOfTheStoredSource() {
        // Moving a slider must not silently switch the wallpaper back on or delete the chosen photo.
        val chosen = BackgroundPreferences(source = BackgroundSource.custom, maskStrength = .5f, blurDp = 8f).sanitized()
        assertEquals(BackgroundSource.custom, chosen.copy(maskStrength = .1f).source)
        assertEquals(8f, chosen.copy(maskStrength = .1f).blurDp, 0f)
        assertEquals(.5f, chosen.copy(blurDp = 0f).maskStrength, 0f)
    }

    private fun jpegBytes() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 5, 6, 7, 8)
}
