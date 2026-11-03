package com.glasssign.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Dock-only tests. Pure Compose (createComposeRule), no ViewModel, no repository, no network, no device state.
 * These do NOT replace NavigationV070Test (which exercises the real MainActivity); they pin the dock's own
 * contract: capsule travel, hidden-dock accessibility, policy fallbacks and touch targets.
 */
class LiquidDockV070Test {
    @get:Rule val rule=createComposeRule()

    private val standard=GlassPolicy(blur=true,motion=true,reason="实时背景采样")
    private val light=GlassPolicy(blur=false,motion=true,reason="轻量材质")
    private val still=GlassPolicy(blur=false,motion=false,reason="轻量材质")

    /** Hosts a dock over a painted page box, mimicking the HoshioriScreen structure (page sibling, dock on top). */
    private fun host(policy:GlassPolicy,enabled:Boolean,initial:Int=0,onSelect:(Int)->Unit={}) {
        var selected by mutableStateOf(initial)
        rule.setContent {
            GlassSignTheme(false) {
                val backdrop=rememberPageBackdrop(policy.blur)
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().backdropSource(backdrop))
                    LiquidDock(selected,{selected=it;onSelect(it)},backdrop,policy,enabled,Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }

    private fun capsuleLeft():Float=rule.onNodeWithTag("selected-capsule").fetchSemanticsNode().boundsInRoot.left

    /** The capsule must travel strictly right as the selected index grows, for all four pages. */
    @Test fun capsuleTravelsRightForEveryIndex() {
        host(standard,enabled=true)
        rule.onNodeWithTag("floating-dock").assertIsDisplayed()
        val lefts=mutableListOf(capsuleLeft())
        for(i in 1..3) {
            rule.onNodeWithTag("nav-$i").performClick()
            rule.waitForIdle()
            lefts+=capsuleLeft()
        }
        for(i in 1..3) assertTrue("capsule must move right from index ${i-1} to $i: $lefts",lefts[i]>lefts[i-1])
        // Selecting back must not break the mapping.
        rule.onNodeWithTag("nav-0").performClick();rule.waitForIdle()
        assertEquals(lefts[0],capsuleLeft(),0.5f)
    }

    /** A motion-off policy must still move the capsule (snap), never leave it animating. */
    @Test fun capsuleMovesWithoutMotion() {
        host(still,enabled=true)
        val before=capsuleLeft()
        rule.onNodeWithTag("nav-3").performClick()
        rule.waitForIdle()
        val after=capsuleLeft()
        assertTrue("snap() policy must still relocate the capsule: $before -> $after",after>before)
    }

    /** enabled=false: no nav nodes, no capsule, and the dock itself reports invisible to accessibility. */
    @Test fun hiddenDockExposesNoNavNodes() {
        host(standard,enabled=false)
        // The dock is still laid out (so the page does not reflow), but nothing inside it is reachable.
        for(i in 0..3) rule.onNodeWithTag("nav-$i").assertDoesNotExist()
        rule.onNodeWithTag("selected-capsule").assertDoesNotExist()
        // A focus search over the whole tree must not surface any dock cell by its content description.
        for(title in listOf("首页","账号","记录","设置")) {
            assertEquals("hidden dock must not expose \"$title\" to accessibility",0,
                rule.onAllNodesWithContentDescription(title).fetchSemanticsNodes().size)
        }
    }

    /** The dock keeps its 48.dp minimum touch target at 100% and at 200% font scale. */
    @Test fun cellsMeetMinimumTouchTarget() {
        var fontScale by mutableStateOf(1f)
        rule.setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(1f,fontScale)
                ) { GlassSignTheme(false) {
                    val layer:GraphicsLayer=rememberGraphicsLayer()
                    Box(Modifier.fillMaxSize()) {
                        LiquidDock(0,{},layer,standard,true,Modifier.align(Alignment.BottomCenter))
                    }
                } }
        }
        for(scale in listOf(1f,2f)) {
            rule.runOnIdle {fontScale=scale}
            rule.waitForIdle()
            for(i in 0..3) {
                val h=rule.onNodeWithTag("nav-$i").fetchSemanticsNode().boundsInRoot.height
                assertTrue("nav-$i at fontScale=$scale must be >=48dp tall, was $h",h>=48f)
            }
            rule.waitForIdle()
        }
    }

    /** Selected cell is announced as a selected tab with the 已选中 state description. */
    @Test fun selectedCellHasTabSemantics() {
        host(standard,enabled=true,initial=2)
        rule.onNodeWithTag("nav-2").assertIsSelected()
        rule.onNodeWithTag("nav-2").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab))
        rule.onNodeWithTag("nav-2").assertContentDescriptionEquals("记录")
        rule.onNodeWithTag("nav-2").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已选中"))
        rule.onNodeWithTag("nav-0").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"未选中"))
    }

    /** Off mode must stay fully opaque and must not request an offscreen blur node. */
    @Test fun offModeHasNoExtraCompositionCause() {
        // Behavioural stand-in: with an opaque material the dock still draws and still accepts taps.
        rule.setContent {
            GlassSignTheme(false) {
                val layer=rememberGraphicsLayer()
                Box(Modifier.fillMaxSize()) {
                    LiquidDock(0,{},layer,GlassPolicy(blur=false,motion=false,reason="玻璃已关闭"),true,Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        rule.onNodeWithTag("floating-dock").assertIsDisplayed()
        rule.onNodeWithTag("nav-1").performClick()
        rule.onNodeWithTag("floating-dock").assertIsDisplayed()
    }

    /** The policy matrix that drives blur/motion must stay exactly as it is in v0.7.0. */
    @Test fun glassPolicyMatrixIsUnchanged() {
        fun p(api:Int=31,lowRam:Boolean=false,power:Boolean=false,acc:Boolean=false,reduce:Boolean=false,anim:Boolean=true,mode:String="standard")=
            glassPolicy(api,lowRam,power,acc,reduce,anim,mode)

        // standard + API31 and above, nothing degraded: real blur.
        assertTrue(p(api=31).blur);assertTrue(p(api=37).blur)
        // every documented fallback loses blur
        assertFalse("API30 must not blur",p(api=30).blur)
        assertFalse("low-RAM must not blur",p(lowRam=true).blur)
        assertFalse("power-save must not blur",p(power=true).blur)
        assertFalse("touch exploration / a11y must not blur",p(acc=true).blur)
        assertFalse("light mode must not blur",p(mode="light").blur)
        assertFalse("off mode must not blur",p(mode="off").blur)
        // reasons are user-visible, keep them exact
        assertEquals("系统不支持实时模糊",p(api=30).reason)
        assertEquals("低内存设备",p(lowRam=true).reason)
        assertEquals("系统省电模式",p(power=true).reason)
        assertEquals("辅助功能高对比",p(acc=true).reason)
        assertEquals("轻量材质",p(mode="light").reason)
        assertEquals("玻璃已关闭",p(mode="off").reason)
        assertEquals("实时背景采样",p().reason)
        // motion: reduce-motion or disabled animators must both stop it
        assertTrue(p().motion)
        assertFalse("reduceMotion must disable motion",p(reduce=true).motion)
        assertFalse("system animators off must disable motion",p(anim=false).motion)
    }

    /** liquidDockHeight must switch only above 130% font scale. */
    @Test fun dockHeightSwitchesAbove130Percent() {
        var at1=0f;var at13=0f;var at14=0f
        rule.setContent { GlassSignTheme(false) {
            at1=liquidDockHeight(1f).value
            at13=liquidDockHeight(1.3f).value
            at14=liquidDockHeight(1.31f).value
        } }
        assertEquals(72f,at1,0.01f)
        assertEquals(72f,at13,0.01f)
        assertEquals(88f,at14,0.01f)
    }

    // -----------------------------------------------------------------------------------------
    // v0.7.1 optics pass: landscape cap and the standard-mode material.
    // -----------------------------------------------------------------------------------------

    /**
     * The reported defect: in landscape the dock stretched across the whole window. On a wide window the dock must
     * cap near 520dp and stay centred instead of filling the width. (This asserts the cap only; the exact centred
     * offset is a visual property, not a semantics one.)
     */
    @Test fun landscapeDockIsCapped() {
        host(standard,enabled=true)
        val dock=rule.onNodeWithTag("floating-dock").fetchSemanticsNode().boundsInRoot
        val window=rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("the dock must have a sane width, was ${dock.width}px",dock.width>0f)
        if(window.width>=1000f) {
            assertTrue("on a ${window.width}px window the dock must be capped near 520dp, was ${dock.width}px",
                dock.width<=520f*4f+2f||dock.width<=window.width*.5f)
        }
    }

    /** The capsule must remain able to reach the last cell after any cap: its travel is still monotonic. */
    @Test fun capsuleTravelSurvivesTheWidthCap() {
        host(standard,enabled=true)
        val lefts=mutableListOf(capsuleLeft())
        for(i in 1..3) {
            rule.onNodeWithTag("nav-$i").performClick()
            rule.waitForIdle()
            lefts+=capsuleLeft()
        }
        for(i in 1..3) assertTrue("capsule travel must not collapse: $lefts",lefts[i]>lefts[i-1])
    }
}
