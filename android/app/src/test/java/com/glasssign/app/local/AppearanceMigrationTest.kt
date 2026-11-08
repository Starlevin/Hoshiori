package com.glasssign.app.local

import android.app.Application
import android.content.Context
import com.glasssign.app.ui.AppearanceController
import com.glasssign.app.ui.AppearanceSettings
import com.glasssign.app.ui.background.BackgroundPreferencesStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Isolated preference fixtures; never opens a user account database or a network connection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
class AppearanceMigrationTest {
    private lateinit var context:Context
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("appearance_v1",Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun preferences()=context.getSharedPreferences("appearance_v1",Context.MODE_PRIVATE)

    @Test fun freshInstallStartsNeutral() {
        assertEquals("neutral",AppearanceController(context).read().colorStyle)
    }

    /** P2: Enhanced glass is the fresh-install default; an explicit saved choice is never overwritten. */
    @Test fun freshInstallUsesEnhancedSurfaceAndAnExplicitChoiceSurvives() {
        assertEquals("ultra",AppearanceController(context).read().surfaceEffect)
        assertEquals("ultra",AppearanceSettings().surfaceEffect)
        for(effect in listOf("none","standard","ultra")) {
            preferences().edit().clear().putString("surface_effect",effect).commit()
            assertEquals(effect,AppearanceController(context).read().surfaceEffect)
        }
    }

    @Test fun oldDefaultBlueChangesOnlyTheDefaultStyle() {
        preferences().edit().putString("color_style","vivid").putInt("seed",0xFF4263C7.toInt())
            .putString("mode","dark").putBoolean("pure_black",true)
            .putString("font","serif").putBoolean("scale_enabled",true).putFloat("text_scale",1.2f)
            .putString("custom_colors","ff24795d").commit()
        val result=AppearanceController(context).read()
        assertEquals("neutral",result.colorStyle)
        assertEquals("dark",result.mode)
        assertTrue(result.pureBlack)
        assertEquals("serif",result.font)
        assertTrue(result.scaleEnabled)
        assertEquals(1.2f,result.textScale,0f)
        assertEquals(listOf(0xFF24795D.toInt()),result.customColors)
    }

    @Test fun explicitColoredOrWallpaperChoicesArePreserved() {
        for((style,seed,dynamic) in listOf(Triple("tinted",0xFF4263C7.toInt(),false),
            Triple("soft",0xFF7553A8.toInt(),false),Triple("vivid",0xFF24795D.toInt(),false),
            Triple("vivid",0xFF4263C7.toInt(),true))) {
            preferences().edit().clear().putString("color_style",style).putInt("seed",seed)
                .putBoolean("dynamic",dynamic).commit()
            val result=AppearanceController(context).read()
            assertEquals(style,result.colorStyle)
            assertEquals(seed,result.seed)
            assertEquals(dynamic,result.dynamicColor)
        }
    }

    @Test fun migrationDoesNotChangeBackgroundOrOtherPreferenceFiles() {
        preferences().edit().putString(BackgroundPreferencesStore.keySource,"custom")
            .putFloat(BackgroundPreferencesStore.keyMask,.65f).putFloat(BackgroundPreferencesStore.keyBlur,12f).commit()
        val sentinel=context.getSharedPreferences("migration-fixture",Context.MODE_PRIVATE)
        sentinel.edit().clear().putString("untouched","fixture-only").commit()
        AppearanceController(context)
        assertEquals("custom",preferences().getString(BackgroundPreferencesStore.keySource,null))
        assertEquals(.65f,preferences().getFloat(BackgroundPreferencesStore.keyMask,-1f),0f)
        assertEquals(12f,preferences().getFloat(BackgroundPreferencesStore.keyBlur,-1f),0f)
        assertEquals(mapOf("untouched" to "fixture-only"),sentinel.all)
    }

    @Test fun laterBlueSelectionSurvivesNextLaunch() {
        val controller=AppearanceController(context)
        controller.save(AppearanceSettings(colorStyle="vivid"))
        assertEquals("vivid",AppearanceController(context).read().colorStyle)
        assertEquals(1,preferences().getInt("theme_catalog_version",0))
    }
}
