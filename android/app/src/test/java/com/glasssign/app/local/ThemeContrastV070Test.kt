package com.glasssign.app.local

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.glasssign.app.ui.*
import org.junit.Assert.*
import org.junit.Test

class ThemeContrastV070Test {
    private fun contrast(a:Color,b:Color)=(maxOf(a.luminance(),b.luminance())+.05f)/(minOf(a.luminance(),b.luminance())+.05f)
    @Test fun defaultPaletteAndColorStylesKeepButtonAndBodyContrastInBothThemes() {
        val seeds=listOf(0xFF4263C7,0xFF7553A8,0xFFB4466D,0xFFB6533F,0xFF9D641F,0xFF24795D,0xFF19757A,0xFFFFFFFF,0xFF000000)
        for(dark in listOf(false,true))for(seed in seeds)for(style in listOf("vivid","tinted","soft","monochrome")) {
            val c=appearanceScheme(AppearanceSettings(seed=seed.toInt(),colorStyle=style),dark,null)
            assertTrue("primary $dark $style",contrast(c.primary,c.onPrimary)>=4.5f)
            assertTrue("secondary $dark $style",contrast(c.secondary,c.onSecondary)>=4.5f)
            assertTrue("surface $dark $style",contrast(c.surface,c.onSurface)>=4.5f)
        }
    }
}
