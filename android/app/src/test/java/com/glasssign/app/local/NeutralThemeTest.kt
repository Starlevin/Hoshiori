package com.glasssign.app.local

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.glasssign.app.ui.*
import org.junit.Assert.*
import org.junit.Test

class NeutralThemeTest {
    @Test fun defaultUsesWhiteInLightModeAndBlackInDarkMode() {
        val settings=AppearanceSettings()
        assertEquals("neutral",settings.colorStyle)
        assertEquals(Color.White,appearanceScheme(settings,false,null).background)
        assertEquals(Color.Black,appearanceScheme(settings,true,null).background)
    }

    @Test fun everyDefaultSurfaceAndAccentIsNeutralEvenWithBlueWallpaper() {
        for(dark in listOf(false,true))for(oled in listOf(false,true)) {
            val scheme=appearanceScheme(AppearanceSettings(pureBlack=oled,dynamicColor=true),dark,
                darkColorScheme(primary=Color.Blue,secondary=Color.Cyan))
            for(color in listOf(scheme.primary,scheme.secondary,scheme.tertiary,scheme.background,
                scheme.surface,scheme.surfaceVariant,scheme.surfaceContainerLowest,scheme.surfaceContainerLow,
                scheme.surfaceContainer,scheme.surfaceContainerHigh,scheme.surfaceContainerHighest,
                scheme.primaryContainer,scheme.secondaryContainer,scheme.outline,scheme.outlineVariant)) {
                assertEquals("red/green in $color",color.red,color.green,.001f)
                assertEquals("green/blue in $color",color.green,color.blue,.001f)
            }
        }
    }

    @Test fun neutralTextAndStatusColorsKeepAaContrast() {
        for(dark in listOf(false,true))for(oled in listOf(false,true)) {
            val c=appearanceScheme(AppearanceSettings(pureBlack=oled),dark,null)
            for((text,background) in listOf(c.onBackground to c.background,c.onSurface to c.surface,
                c.onSurfaceVariant to c.background,c.onSurfaceVariant to c.surface,
                c.onPrimary to c.primary,c.onSecondary to c.secondary,
                c.onPrimaryContainer to c.primaryContainer,c.onSecondaryContainer to c.secondaryContainer,
                c.error to c.surface,c.onError to c.error)) {
                assertTrue("$dark/$oled $text on $background",contrastRatio(text,background)>=4.5f)
            }
            assertTrue("Failure still carries red hue",c.error.red>c.error.green&&c.error.red>c.error.blue)
        }
    }

    @Test fun bluePaletteIsAvailableOnlyWhenExplicitlySelected() {
        val default=AppearanceSettings()
        val blue=appearanceScheme(default.copy(colorStyle="tinted",seed=0xFF4263C7.toInt()),true,null)
        assertTrue(blue.background.blue>blue.background.red)
        assertTrue(blue.primary.blue>blue.primary.red)
        assertEquals(Color.Black,appearanceScheme(default,true,null).background)
    }

    /** OLED 纯黑只压黑页面底层；卡片/框保留可区分的深灰（2026-11-07 产品决策）。 */
    @Test fun oledOnlyBlackensThePageBackgroundAndKeepsFramesDistinct() {
        val normal=appearanceScheme(AppearanceSettings(),true,null)
        val oled=appearanceScheme(AppearanceSettings(pureBlack=true),true,null)
        // 页面底层：深色默认就是纯黑，OLED 不改变它。
        assertEquals(Color.Black,oled.background)
        assertEquals(normal.background,oled.background)
        // 卡片与容器：无论是否开启 OLED 都保留层级，且彼此不同。
        for(c in listOf(normal,oled)) {
            assertNotEquals(Color.Black,c.surface)
            assertNotEquals(c.surface,c.surfaceContainerHigh)
            assertNotEquals(c.surfaceContainerHigh,c.surfaceContainerHighest)
        }
        // 开启 OLED 不把卡片抹黑：与未开启时完全一致。
        assertEquals(normal.surface.toArgb(),oled.surface.toArgb())
        assertEquals(normal.surfaceContainerHigh.toArgb(),oled.surfaceContainerHigh.toArgb())
        assertEquals(normal.surfaceContainerHighest.toArgb(),oled.surfaceContainerHighest.toArgb())
        // 浅色主题完全不受影响。
        assertEquals(appearanceScheme(AppearanceSettings(),false,null).surface.toArgb(),
            appearanceScheme(AppearanceSettings(pureBlack=true),false,null).surface.toArgb())
    }
}
