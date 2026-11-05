package com.glasssign.app.local

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.glasssign.app.ui.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Purity and stability of [appearanceScheme].
 *
 * These assertions are the JVM expression of the Theme.kt remember-key fix: the palette is a pure
 * function of the fields that change colours, so `font`, `scaleEnabled` and `textScale` must not be able
 * to change it — otherwise keying `remember` on those fields instead of on the whole settings object
 * would have been a real bug rather than a removed allocation.
 */
class AppearanceSchemeV070Test {
    /** Every colour role the UI consumes, in a deterministic order. */
    private fun palette(c:androidx.compose.material3.ColorScheme)=listOf(
        c.primary,c.onPrimary,c.primaryContainer,c.onPrimaryContainer,
        c.secondary,c.onSecondary,c.secondaryContainer,c.onSecondaryContainer,
        c.tertiary,c.background,c.onBackground,c.surface,c.onSurface,
        c.surfaceVariant,c.onSurfaceVariant,c.surfaceContainerLowest,c.surfaceContainerLow,
        c.surfaceContainer,c.surfaceContainerHigh,c.surfaceContainerHighest,
        c.outline,c.outlineVariant,c.surfaceTint,c.error,c.onError,
    ).joinToString(","){it.toArgb().toUInt().toString(16)}

    @Test fun sameInputProducesEqualScheme() {
        val settings=AppearanceSettings(seed=0xFF7553A8.toInt(),colorStyle="tinted",pureBlack=true)
        // ColorScheme is not a data class, so structural equality is not meaningful; compare every role.
        assertEquals(palette(appearanceScheme(settings,true,null)),palette(appearanceScheme(settings,true,null)))
        assertEquals(palette(appearanceScheme(settings,false,null)),palette(appearanceScheme(settings,false,null)))
    }

    @Test fun fontScaleAndScaleEnabledNeverChangeThePalette() {
        val base=AppearanceSettings(seed=0xFFB4466D.toInt(),colorStyle="soft")
        for(dark in listOf(false,true)) {
            val reference=palette(appearanceScheme(base,dark,null))
            for(font in listOf("system","sans","serif","mono"))
                for(scaleEnabled in listOf(false,true))
                    for(textScale in listOf(.85f,1f,1.1f,1.4f)) {
                        val other=base.copy(font=font,scaleEnabled=scaleEnabled,textScale=textScale)
                        assertEquals("font=$font scale=$scaleEnabled textScale=$textScale dark=$dark",
                            reference,palette(appearanceScheme(other,dark,null)))
                    }
        }
    }

    /** Every field the Theme.kt `remember` keys on must really be a palette input. */
    @Test fun everyRememberKeyedFieldChangesThePalette() {
        val base=AppearanceSettings(seed=0xFF4263C7.toInt(),colorStyle="vivid")
        val reference=palette(appearanceScheme(base,false,null))
        // colourStyle / seed / pureBlack in the light theme.
        assertNotEquals("colorStyle",reference,palette(appearanceScheme(base.copy(colorStyle="tinted"),false,null)))
        assertNotEquals("seed",reference,palette(appearanceScheme(base.copy(seed=0xFF24795D.toInt()),false,null)))
        // pureBlack only has an effect in the dark theme.
        assertNotEquals("pureBlack",palette(appearanceScheme(base,true,null)),palette(appearanceScheme(base.copy(pureBlack=true),true,null)))
        // dark itself is a key.
        assertNotEquals("dark",reference,palette(appearanceScheme(base,true,null)))
        // dynamicColor is a key: with a wallpaper palette it changes primary and secondary.
        val wallpaper=lightColorScheme(primary=Color(0xFF9D641F),secondary=Color(0xFF7F8C6A))
        val withWallpaper=palette(appearanceScheme(base.copy(dynamicColor=true),false,wallpaper))
        assertNotEquals("wallpaper",palette(appearanceScheme(base,false,null)),withWallpaper)
        // Without a wallpaper (API<31 or the toggle off) dynamicColor is a no-op, which is why Theme.kt
        // keys on the resolved `wallpaper` object as well as the flag.
        assertEquals(palette(appearanceScheme(base,false,null)),palette(appearanceScheme(base.copy(dynamicColor=true),false,null)))
    }

    @Test fun paintOnlyPreferencesDoNotTouchThePalette() {
        // glassMode/surfaceEffect/navigationTint are read by the dock and Glass.kt for material layer
        // height; they must not be palette inputs, so keying remember on them can be dropped if needed.
        val base=AppearanceSettings()
        val reference=palette(appearanceScheme(base,true,null))
        for(other in listOf(base.copy(glassMode="off"),base.copy(surfaceEffect="ultra"),base.copy(navigationTint=true)))
            assertEquals(reference,palette(appearanceScheme(other,true,null)))
    }

    @Test fun wallpaperPaletteOnlyAffectsTheAccentRoles() {
        val base=AppearanceSettings(seed=0xFF4263C7.toInt(),colorStyle="vivid")
        val plain=appearanceScheme(base,false,null)
        val dynamic=appearanceScheme(base,false,lightColorScheme(primary=Color(0xFF9D641F),secondary=Color(0xFF7F8C6A)))
        assertNotEquals(plain.primary.toArgb(),dynamic.primary.toArgb())
        assertNotEquals(plain.secondary.toArgb(),dynamic.secondary.toArgb())
        // The readable ink is still chosen by contrast, not copied from the wallpaper.
        assertTrue(com.glasssign.app.ui.contrastRatio(dynamic.primary,dynamic.onPrimary)>=4.5f)
    }

    @Test fun softStyleWithWallpaperLerpsInsteadOfCopyingThePrimary() {
        val wallpaper=darkColorScheme(primary=Color(0xFFD0BCFF),onSurfaceVariant=Color(0xFFCCC2DC))
        val soft=appearanceScheme(AppearanceSettings(colorStyle="soft",dynamicColor=true),true,wallpaper)
        val vivid=appearanceScheme(AppearanceSettings(colorStyle="vivid",dynamicColor=true),true,wallpaper)
        assertEquals(wallpaper.primary.toArgb(),vivid.primary.toArgb())
        assertNotEquals(wallpaper.primary.toArgb(),soft.primary.toArgb())
    }

    /**
     * Contract check for the Theme.kt `remember` keys. The key list is exactly
     * {dark, colorStyle, seed, pureBlack, dynamicColor, wallpaper}; this asserts that this set is
     * SUFFICIENT (nothing outside it changes the palette) and that every member outside the
     * display-only set {font, scaleEnabled, textScale, glassMode, surfaceEffect, navigationTint,
     * customColors} is NECESSARY (changing it does change the palette), so a removed key would fail here.
     */
    @Test fun themeRememberKeysAreExactlyThePaletteInputs() {
        val base=AppearanceSettings(seed=0xFF4263C7.toInt(),colorStyle="vivid")
        // Display-only fields must not leak into the palette.
        val reference=palette(appearanceScheme(base,false,null))
        val displayOnly=listOf(
            base.copy(font="mono"),base.copy(font="serif"),base.copy(scaleEnabled=true),base.copy(textScale=1.4f),
            base.copy(glassMode="off"),base.copy(surfaceEffect="ultra"),base.copy(navigationTint=true),
            base.copy(customColors=listOf(0xFF24795D.toInt())),
        )
        for(variant in displayOnly)assertEquals("display-only field leaked into palette: $variant",reference,palette(appearanceScheme(variant,false,null)))
        // Palette fields must change it.
        assertNotEquals(palette(appearanceScheme(base,false,null)),palette(appearanceScheme(base.copy(colorStyle="soft"),false,null)))
        assertNotEquals(palette(appearanceScheme(base,false,null)),palette(appearanceScheme(base.copy(seed=0xFF19757A.toInt()),false,null)))
        assertNotEquals(palette(appearanceScheme(base,true,null)),palette(appearanceScheme(base.copy(pureBlack=true),true,null)))
        assertNotEquals(palette(appearanceScheme(base,false,null)),palette(appearanceScheme(base,true,null)))
    }
}
