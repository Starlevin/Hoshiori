package com.glasssign.app.local

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.glasssign.app.ui.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Contrast matrix for the colour roles the v0.7 UI actually paints text with.
 *
 * This extends (does not replace) the original 72-combination check from docs/v070-report.md
 * ("72组配色的对比度检查"): the original matrix was 2 themes x 9 seeds x 4 styles = 72 combinations for
 * primary/secondary/surface. Here the same 72 combinations (x2 for pureBlack = 144) are checked for nine
 * pairs instead of three, so the older claim is strictly strengthened, never weakened. The original test
 * method below is kept verbatim and unchanged.
 *
 * Every number below is computed with Compose's own `Color.luminance()`, which is the value the renderer
 * uses; the 4.5f target is WCAG 2.1 AA for normal-size text (the secondary text role is 11sp/17sp).
 */
class ThemeContrastV070Test {
    private fun contrast(a:Color,b:Color)=(maxOf(a.luminance(),b.luminance())+.05f)/(minOf(a.luminance(),b.luminance())+.05f)
    private val seeds=listOf(0xFF4263C7,0xFF7553A8,0xFFB4466D,0xFFB6533F,0xFF9D641F,0xFF24795D,0xFF19757A,0xFFFFFFFF,0xFF000000)
    private val styles=listOf("vivid","tinted","soft","monochrome")
    private val aa=4.5f

    /** Original assertion, kept verbatim so the pre-existing claim cannot silently regress. */
    @Test fun defaultPaletteAndColorStylesKeepButtonAndBodyContrastInBothThemes() {
        for(dark in listOf(false,true))for(seed in seeds)for(style in styles) {
            val c=appearanceScheme(AppearanceSettings(seed=seed.toInt(),colorStyle=style),dark,null)
            assertTrue("primary $dark $style",contrast(c.primary,c.onPrimary)>=4.5f)
            assertTrue("secondary $dark $style",contrast(c.secondary,c.onSecondary)>=4.5f)
            assertTrue("surface $dark $style",contrast(c.surface,c.onSurface)>=4.5f)
        }
    }

    /** Same 144 combinations, now covering every text role used by the UI, plus pureBlack. */
    @Test fun everyTextRoleClearsAaForAll144Combinations() {
        var checked=0
        for(dark in listOf(false,true))for(seed in seeds)for(style in styles)for(pureBlack in listOf(false,true)) {
            val s=AppearanceSettings(seed=seed.toInt(),colorStyle=style,pureBlack=pureBlack)
            val c=appearanceScheme(s,dark,null)
            val tag="$dark/$style/seed=${seed.toUInt().toString(16)}/pureBlack=$pureBlack"
            assertTrue("primary/onPrimary $tag ${contrast(c.primary,c.onPrimary)}",contrast(c.primary,c.onPrimary)>=aa)
            assertTrue("secondary/onSecondary $tag ${contrast(c.secondary,c.onSecondary)}",contrast(c.secondary,c.onSecondary)>=aa)
            assertTrue("surface/onSurface $tag ${contrast(c.surface,c.onSurface)}",contrast(c.surface,c.onSurface)>=aa)
            // bodySmall 11sp secondary text: on the card it sits on, and on the page gradient behind it.
            assertTrue("surfaceVariant text on surface $tag ${contrast(c.onSurfaceVariant,c.surface)}",contrast(c.onSurfaceVariant,c.surface)>=aa)
            assertTrue("surfaceVariant text on background $tag ${contrast(c.onSurfaceVariant,c.background)}",contrast(c.onSurfaceVariant,c.background)>=aa)
            // Destructive text on a card, and the label on a filled destructive button.
            assertTrue("error on surface $tag ${contrast(c.error,c.surface)}",contrast(c.error,c.surface)>=aa)
            assertTrue("onError on error $tag ${contrast(c.onError,c.error)}",contrast(c.onError,c.error)>=aa)
            // Title/body ink on the page background as well as on cards.
            assertTrue("onSurface on background $tag ${contrast(c.onSurface,c.background)}",contrast(c.onSurface,c.background)>=aa)
            // Header text on primaryContainer (GlassDialog's preview surface and QCard accents).
            assertTrue("onPrimaryContainer on primaryContainer $tag ${contrast(c.onPrimaryContainer,c.primaryContainer)}",contrast(c.onPrimaryContainer,c.primaryContainer)>=aa)
            checked++
        }
        assertEquals(144,checked)
    }

    /** OLED (2026-11-07 产品决策): 只把页面底层压黑，卡片/容器仍带层级且文字可读。 */
    @Test fun pureBlackKeepsSecondaryTextReadableOnTheOledSurface() {
        for(style in styles) {
            val c=appearanceScheme(AppearanceSettings(seed=0xFF4263C7.toInt(),colorStyle=style,pureBlack=true),true,null)
            assertEquals(Color.Black.toArgb(),c.background.toArgb())
            assertNotEquals("卡片不能和页面底层一样黑",Color.Black.toArgb(),c.surface.toArgb())
            assertNotEquals("卡片层级之间要有区分度",c.surface.toArgb(),c.surfaceContainerHigh.toArgb())
            assertTrue(contrast(c.onSurfaceVariant,c.surface)>=aa)
            assertTrue(contrast(c.onSurface,c.background)>=aa)
            assertTrue(contrast(c.error,c.surface)>=aa)
        }
    }

    @Test fun oledContainersKeepDistinctLevelsAndSelectedTextRemainsReadableForExtremeSeeds() {
        for(seed in listOf(0xFF000000.toInt(),0xFFFFFFFF.toInt(),0xFF00FF00.toInt()))for(style in styles) {
            val c=appearanceScheme(AppearanceSettings(seed=seed,colorStyle=style,pureBlack=true),true,null)
            // 只有页面底层是纯黑；卡片、各层容器都不是纯黑，且彼此不同。
            assertEquals(Color.Black.toArgb(),c.background.toArgb())
            for(surface in listOf(c.surface,c.surfaceVariant,c.primaryContainer,c.secondaryContainer,
                c.surfaceContainerLow,c.surfaceContainer,c.surfaceContainerHigh,c.surfaceContainerHighest)) {
                assertNotEquals("卡片/容器不能整块黑掉",Color.Black.toArgb(),surface.toArgb())
            }
            assertNotEquals(c.surface.toArgb(),c.surfaceContainerHigh.toArgb())
            assertNotEquals(c.surfaceContainerHigh.toArgb(),c.surfaceContainerHighest.toArgb())
            assertTrue(contrast(c.onPrimaryContainer,c.primaryContainer)>=aa)
            assertTrue(contrast(c.onSecondaryContainer,c.secondaryContainer)>=aa)
            assertTrue(contrast(c.primary,c.onPrimary)>=aa)
        }
    }

    @Test fun oledPreferenceDoesNotBlackenTheLightTheme() {
        val normal=appearanceScheme(AppearanceSettings(pureBlack=false),false,null)
        val oled=appearanceScheme(AppearanceSettings(pureBlack=true),false,null)
        assertEquals(normal.background.toArgb(),oled.background.toArgb())
        assertEquals(normal.surface.toArgb(),oled.surface.toArgb())
        assertEquals(normal.primaryContainer.toArgb(),oled.primaryContainer.toArgb())
    }

    /**
     * Wallpaper (dynamic) palettes are the one input the 72-combination matrix cannot enumerate, and they
     * are the case where `surface` is tinted by a mid-luminance colour. Synthesised mid-tone palettes: the
     * scheme must still hold every guarantee. Both polarities are checked (`lightColorScheme` with a dark
     * theme and vice versa is not reachable from Theme.kt, but it proves the repair steps are not relying
     * on the wallpaper and the theme agreeing).
     */
    @Test fun syntheticMidToneWallpaperPalettesStillClearAa() {
        val cases=listOf(
            "light-mid" to lightColorScheme(primary=Color(0xFF9A7B4F),secondary=Color(0xFF7F8C6A),surface=Color(0xFFD9D2C4),background=Color(0xFFE6E0D4),onSurface=Color(0xFF2A2620),onSurfaceVariant=Color(0xFF8C8375),error=Color(0xFF8E4A44)),
            "dark-mid" to darkColorScheme(primary=Color(0xFF8E9A6B),secondary=Color(0xFFB9A27A),surface=Color(0xFF4A4640),background=Color(0xFF3A3733),onSurface=Color(0xFFD8D4CC),onSurfaceVariant=Color(0xFF6E675E),error=Color(0xFFB46A62)),
        )
        for((name,wallpaper) in cases)for(style in styles)for(dark in listOf(false,true)) {
            val c=appearanceScheme(AppearanceSettings(seed=0xFF4263C7.toInt(),colorStyle=style,dynamicColor=true),dark,wallpaper)
            val tag="$name/$style/dark=$dark"
            assertTrue("primary $tag",contrast(c.primary,c.onPrimary)>=aa)
            assertTrue("secondary $tag",contrast(c.secondary,c.onSecondary)>=aa)
            assertTrue("onSurface/surface $tag",contrast(c.onSurface,c.surface)>=aa)
            assertTrue("onSurface/background $tag",contrast(c.onSurface,c.background)>=aa)
            assertTrue("variant/surface $tag",contrast(c.onSurfaceVariant,c.surface)>=aa)
            assertTrue("variant/background $tag",contrast(c.onSurfaceVariant,c.background)>=aa)
            assertTrue("error/surface $tag ${contrast(c.error,c.surface)}",contrast(c.error,c.surface)>=aa)
            assertTrue("onError/error $tag ${contrast(c.onError,c.error)}",contrast(c.onError,c.error)>=aa)
        }
    }

    /**
     * `readableOn` regression: the old implementation compared two differently-built numbers and could
     * return white for a light background. Black-on-white / white-on-black must resolve correctly, and a
     * luminance sweep must always reach 4.5 whenever one of the candidate inks can.
     */
    @Test fun readableOnChoosesTheHigherMeasuredRatio() {
        val white=readableOn(Color.White)
        assertTrue("white background must get dark ink, got ${white.toArgb().toUInt().toString(16)}",contrast(white,Color.White)>=aa)
        assertTrue("white background must not get white ink",white.luminance()<.5f)
        assertEquals(Color.White.toArgb(),readableOn(Color.Black).toArgb())
        // A mid grey where the pale ink used to win by mistake: #808080 needs black (5.3:1) not white (3.9:1).
        val gray=readableOn(Color(0xFF808080))
        assertTrue("mid grey ${contrast(gray,Color(0xFF808080))}",contrast(gray,Color(0xFF808080))>=aa)
    }

    @Test fun readableOnSweepAlwaysReachesAaWhenAchievable() {
        var worst=Float.MAX_VALUE;var failures=0
        for(step in 0..255) {
            val value=step/255f
            val background=Color(value,value,value)
            val ink=readableOn(background)
            val ratio=contrast(ink,background)
            val achievable=arrayOf(Color(0xFF10131B),Color(0xFF757575),Color.Black,Color.White).maxOf{contrast(it,background)}
            if(achievable>=aa) {
                if(ratio<aa)failures++
                worst=minOf(worst,ratio)
            }
        }
        assertEquals("readableOn must reach 4.5 wherever 4.5 is achievable",0,failures)
        assertTrue("worst achievable ratio $worst",worst>=aa)
    }

    /** Coloured backgrounds (the accent roles), not just greys. */
    @Test fun readableOnSweepCoversSaturatedColours() {
        for(r in 0..5)for(g in 0..5)for(b in 0..5) {
            val background=Color(r/5f*0.85f+0.05f,g/5f*0.85f+0.1f,b/5f*0.85f+0.15f)
            val ink=readableOn(background)
            val achievable=arrayOf(Color(0xFF10131B),Color(0xFF757575),Color.Black,Color.White).maxOf{contrast(it,background)}
            if(achievable>=aa) assertTrue("$background ink ${contrast(ink,background)} achievable $achievable",contrast(ink,background)>=aa)
        }
    }

    /** The page ink is chosen for background AND surface at once, so it must clear both. */
    @Test fun readableOnInCoversAllGivenSurfaces() {
        val backgrounds=listOf(Color(0xFFF7F8FC),Color.White)
        val ink=readableOnIn(backgrounds)
        for(b in backgrounds)assertTrue("$b",contrast(ink,b)>=aa)
        assertEquals(Color.White.toArgb(),readableOnIn(listOf(Color.Black,Color(0xFF101010))).toArgb())
    }

    /** `failed` must stay the theme `error` role; the repaired role, not a hand-picked literal. */
    @Test fun errorRoleKeepsItsHueAndIsOnlyRepairedAwayFromTheSurface() {
        for(dark in listOf(false,true))for(style in styles) {
            val c=appearanceScheme(AppearanceSettings(colorStyle=style),dark,null)
            val base=if(dark)darkColorScheme() else lightColorScheme()
            assertTrue("error $dark/$style keeps a red hue",c.error.red>c.error.green&&c.error.red>c.error.blue)
            // Repair either returns the default untouched or moves it away from the surface; it never
            // moves the destructive colour toward the surface it is painted on.
            if(c.error.toArgb()!=base.error.toArgb())assertTrue("repaired error $dark/$style must improve its ratio",
                contrast(c.error,c.surface)>=contrast(base.error,c.surface))
        }
    }
}
