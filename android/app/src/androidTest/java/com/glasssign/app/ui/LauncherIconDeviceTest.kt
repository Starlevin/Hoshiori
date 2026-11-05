package com.glasssign.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glasssign.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherIconDeviceTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()

    @Test fun packagedIconsHaveBothPalettesAndThemedMonochrome() {
        val dark=context.getDrawable(R.mipmap.ic_launcher_dark) as AdaptiveIconDrawable
        val light=context.getDrawable(R.mipmap.ic_launcher_light) as AdaptiveIconDrawable
        assertEquals(0xFF101525.toInt(),(dark.background as ColorDrawable).color)
        assertEquals(0xFFF0F1F7.toInt(),(light.background as ColorDrawable).color)
        assertNotNull(dark.foreground)
        assertNotNull(light.foreground)
        if(Build.VERSION.SDK_INT>=33) {
            assertNotNull("Android themed icons need a real monochrome layer",dark.monochrome)
            assertNotNull(light.monochrome)
        }
        assertNotNull(context.packageManager.getApplicationIcon(context.packageName))
    }

    @Test fun realPackageManagerSwitchesOneLauncherAndRetainsTheChoice() {
        val store=LauncherIconStore.open(context)
        val original=store.read()
        val pageMode=AppearanceController(context).read().mode
        val systemDark=context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES
        val manager=LauncherIconManager(PackageManagerSwitcher(context),atomicAliasSwitch(Build.VERSION.SDK_INT))
        try {
            listOf(LauncherIconMode.DARK,LauncherIconMode.LIGHT,LauncherIconMode.FOLLOW).forEach { mode ->
                assertTrue(store.save(mode))
                manager.invalidate()
                val outcome=manager.apply(mode,pageMode,systemDark)
                assertTrue(outcome==null||outcome.applied)
                val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
                val entries=context.packageManager.queryIntentActivities(intent,0)
                assertEquals("Exactly one launcher entry must remain enabled",1,entries.size)
                assertEquals(LauncherIconPlan.targetOf(mode,pageMode).className,entries.single().activityInfo.name)
                assertEquals(mode,LauncherIconStore.open(context).read())
                assertNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            }
        } finally {
            store.save(original)
            manager.invalidate()
            manager.apply(original,pageMode,systemDark)
        }
    }
}
