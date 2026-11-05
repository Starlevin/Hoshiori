package com.glasssign.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.glasssign.app.MainActivity
import com.glasssign.app.UiState
import com.glasssign.app.local.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Tests the production controls using local state only; no community API or user credential is read. */
class LabsDeviceTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private lateinit var services:LocalServices
    private var originalProviders:Map<String,Boolean> = emptyMap()
    private var originalGlass=false

    @Before fun prepare() {
        services=LocalServices.get(rule.activity)
        originalProviders=services.registry.snapshot().associate{it.manifest.id to it.enabled}
        originalGlass=services.labs.isEnabled(LabStore.FLAG_WATER_DROP_GLASS)
        services.registry.setEnabled("miyoushe",true)
        services.labs.setEnabled(LabStore.FLAG_WATER_DROP_GLASS,false)
        showLabs()
    }
    @After fun restore() {
        originalProviders.forEach{(id,enabled)->services.registry.setEnabled(id,enabled)}
        services.labs.setEnabled(LabStore.FLAG_WATER_DROP_GLASS,originalGlass)
    }
    private fun showLabs() {
        rule.activityRule.scenario.onActivity { activity -> activity.setContent {
            GlassSignTheme(false) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                    LabsPage(UiState(),{}, {}, {})
                }
            }
        } }
        rule.waitForIdle()
    }

    @Test fun pauseRequiresConfirmationAndSurvivesAStoreReopen() {
        val beforeAccounts=services.repository.accounts()
        val beforeRecords=services.repository.history()
        rule.onNodeWithText("米游社",useUnmergedTree=true).performClick()
        rule.onNodeWithTag("labs-provider-miyoushe").performClick()
        rule.onNodeWithText("暂停米游社？").assertIsDisplayed()
        assertTrue(services.registry.isEnabled("miyoushe"))
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithTag("labs-provider-miyoushe").performClick()
        rule.onNodeWithText("暂停适配器").performClick()
        rule.waitForIdle()
        assertFalse(ProviderRegistry(rule.activity).isEnabled("miyoushe"))
        assertEquals(beforeAccounts,services.repository.accounts())
        assertEquals(beforeRecords,services.repository.history())
    }

    @Test fun enablingTheEffectDoesNotCreateValidationEvidence() {
        val before=services.labs.validation(LabStore.FLAG_WATER_DROP_GLASS)
        rule.onNodeWithText("FULL · 完整").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(com.glasssign.app.local.GlassQuality.FULL,LabStore(rule.activity).glassQuality())
        assertEquals(before,services.labs.validation(LabStore.FLAG_WATER_DROP_GLASS))
        rule.activityRule.scenario.recreate()
        showLabs()
        rule.onNodeWithText("LITE · 轻量").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(com.glasssign.app.local.GlassQuality.LITE,LabStore(rule.activity).glassQuality())
        rule.onNodeWithText("OFF · 关闭").performScrollTo().performClick()
        rule.activityRule.scenario.recreate()
        showLabs()
        rule.waitForIdle()
        assertEquals(com.glasssign.app.local.GlassQuality.OFF,LabStore(rule.activity).glassQuality())
    }
}
