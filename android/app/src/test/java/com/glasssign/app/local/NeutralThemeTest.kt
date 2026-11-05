package com.glasssign.app.local

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
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

    @Test fun oledOnlyFlattensDarkCards() {
        val normal=AppearanceSettings()
        assertNotEquals(Color.Black,appearanceScheme(normal,true,null).surface)
        assertEquals(Color.Black,appearanceScheme(normal.copy(pureBlack=true),true,null).surface)
        assertEquals(appearanceScheme(normal,false,null).surface,
            appearanceScheme(normal.copy(pureBlack=true),false,null).surface)
    }
}
