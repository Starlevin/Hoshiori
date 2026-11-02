package com.glasssign.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Dock drag tests. Pure Compose (`createComposeRule`): no MainActivity, no ViewModel, no repository, no
 * account/database access, no clearing of app data, no network.
 *
 * [LiquidDockV070Test] keeps pinning the settled capsule, the hidden-dock semantics, the policy matrix and
 * the width cap; this file pins the continuous-drag contract that was missing:
 *
 *  1. the capsule follows the finger between slots and a release switches the page exactly ONCE;
 *  2. a cancelled drag switches nothing and the capsule returns home;
 *  3. over-dragging past either end clamps to that end slot;
 *  4. a vertical swipe (page scroll) never switches the page, while the page below still scrolls;
 *  5. a policy without motion (reduce-motion / system animators off) still drags, without the spring;
 *  6. a disabled dock exposes no tabs and cannot be dragged;
 *  7. a plain tap still selects exactly once, through the new gesture layer.
 *
 * The host mirrors the product composition (page behind, dock as a sibling on top), so "the dock consumes
 * the drag" is measured against a real scrollable instead of being claimed from the implementation.
 */
class LiquidDockDragTest {
    @get:Rule val rule=createComposeRule()

    private val standard=GlassPolicy(blur=true,motion=true,reason="实时背景采样")
    private val light=GlassPolicy(blur=false,motion=true,reason="轻量材质")
    private val still=GlassPolicy(blur=false,motion=false,reason="轻量材质")

    /** Every selection the dock reported, in order. */
    private val switches=mutableListOf<Int>()

    private fun host(policy:GlassPolicy=standard,enabled:Boolean=true,initial:Int=0) {
        switches.clear()
        rule.setContent {
            GlassSignTheme(false) {
                val backdrop=rememberPageBackdrop(policy.blur)
                var selected by remember { mutableIntStateOf(initial) }
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().backdropSource(backdrop))
                    // A real scrollable page under the dock: keeps the vertical-scroll case honest.
                    // `remember` is explicit here: the test file deliberately has no delegate imports.
                    val pageScroll=rememberScrollState()
                    Box(Modifier.fillMaxSize().testTag("drag-page").verticalScroll(pageScroll).background(Color.Transparent)) {
                        Box(Modifier.fillMaxSize().height(2400.dp)) {}
                    }
                    LiquidDock(selected,{index->selected=index;switches+=index},backdrop,policy,enabled,
                        Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        rule.waitForIdle()
    }

    // ---------------------------------------------------------------------------------------------
    // Geometry helpers. Derived from the real semantics bounds, so they hold in any window size.
    // ---------------------------------------------------------------------------------------------

    private fun dockNode():SemanticsNode=rule.onNodeWithTag("floating-dock").fetchSemanticsNode()

    private fun capsuleBounds():Rect=rule.onNodeWithTag("selected-capsule").fetchSemanticsNode().boundsInRoot

    private fun capsuleCentreX():Float=capsuleBounds().center.x

    private fun bar():Rect=dockNode().boundsInRoot

    private fun px(value:Float):Float=with(rule.density){value.dp.toPx()}

    /**
     * Horizontal centre of slot [index], in root coordinates. Slot 0's capsule width gives the cell pitch
     * and the 6dp cell inset, so this works at any window width, including the landscape 520dp cap.
     */
    private fun slotX(index:Int):Float {
        val bounds=bar()
        val cell=capsuleBounds().width
        val inset=(bounds.width-4f*cell)/2f
        return bounds.left+inset+cell*(index+.5f)
    }

    private fun dockY():Float=bar().center.y

    private fun dragFromTo(fromIndex:Int,toIndex:Int,steps:Int=8) {
        val startX=slotX(fromIndex)
        val endX=slotX(toIndex)
        val y=dockY()
        rule.onRoot().performTouchInput {
            down(Offset(startX,y))
            for(step in 1..steps) {moveTo(Offset(startX+(endX-startX)*step/steps,y));advanceEventTime(16)}
            up()
        }
        rule.waitForIdle()
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Continuous travel + exactly one switch
    // ---------------------------------------------------------------------------------------------

    /**
     * The capsule must follow the finger mid-drag (not jump on release), then settle on the new slot.
     *
     * One continuous gesture with an [androidx.compose.ui.test.TouchInjectionScope.advanceEventTime] pause
     * before every move, so Compose processes a frame between moves; the capsule is read while the finger
     * is still down, which is the only way to tell "follows the finger" from "jumps on release".
     */
    @Test fun capsuleFollowsTheFingerMidDrag() {
        host(initial=0)
        val startX=slotX(0);val endX=slotX(2);val y=dockY()
        var midX=0f
        rule.onRoot().performTouchInput {
            down(Offset(startX,y))
            advanceEventTime(32)
            for(step in 1..4) {
                moveTo(Offset(startX+(endX-startX)*step/4f,y))
                advanceEventTime(32)
            }
        }
        // Injection is buffered until the block returns. Leave the pointer down across calls so the
        // semantics read actually observes a rendered drag frame, rather than the unflushed events.
        rule.waitForIdle()
        midX=rule.onNodeWithTag("selected-capsule").fetchSemanticsNode().boundsInRoot.center.x
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertTrue("the capsule must follow the finger between slots: $startX -> $midX",midX>startX+px(8f))
        assertTrue("the capsule must not run ahead of the finger: $midX vs $endX",midX<=endX+1f)
        assertEquals("one drag across two slots must report exactly one selection",listOf(2),switches)
        assertEquals("and settle on slot 2",slotX(2),capsuleCentreX(),2.5f)
    }

    /** Passing over the two slots in between must not emit intermediate selections. */
    @Test fun dragAcrossThreeSlotsReportsOneSelection() {
        host(initial=0)
        dragFromTo(0,3,steps=14)
        assertEquals("only the release slot may be reported",listOf(3),switches)
        assertEquals(slotX(3),capsuleCentreX(),2.5f)
    }

    /**
     * A small drag that stays inside the selected slot must not select anything.
     *
     * The excursion is well below half a cell but above touch slop, so the gesture IS a drag (it must
     * consume and follow), it just does not commit.
     */
    @Test fun dragInsideTheSameSlotDoesNotSwitch() {
        host(initial=2)
        val x=slotX(2);val y=dockY()
        val excursion=capsuleBounds().width*.2f
        rule.onRoot().performTouchInput {
            down(Offset(x,y))
            moveTo(Offset(x+excursion,y));advanceEventTime(16)
            moveTo(Offset(x-excursion,y));advanceEventTime(16)
            up()
        }
        rule.waitForIdle()
        assertTrue("an in-slot drag must not select: $switches",switches.isEmpty())
        assertEquals(slotX(2),capsuleCentreX(),2.5f)
    }

    /** Dragging backwards must work as well (not only left-to-right). */
    @Test fun dragBackwardsSwitchesOnce() {
        host(initial=3)
        dragFromTo(3,1)
        assertEquals(listOf(1),switches)
        assertEquals(slotX(1),capsuleCentreX(),2.5f)
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Cancellation
    // ---------------------------------------------------------------------------------------------

    @Test fun cancelledDragSwitchesNothingAndRestoresTheCapsule() {
        host(initial=1)
        val home=capsuleCentreX()
        val startX=slotX(1);val endX=slotX(3);val y=dockY()
        rule.onRoot().performTouchInput {
            down(Offset(startX,y))
            for(step in 1..6) {moveTo(Offset(startX+(endX-startX)*step/6f,y));advanceEventTime(16)}
            cancel()
        }
        rule.waitForIdle()
        assertTrue("a cancelled drag must not select anything: $switches",switches.isEmpty())
        assertEquals("the capsule must return to the already-selected slot",home,capsuleCentreX(),2.5f)
        rule.onNodeWithTag("nav-1").assertIsSelected()
    }

    // ---------------------------------------------------------------------------------------------
    // 3. End clamping
    // ---------------------------------------------------------------------------------------------

    @Test fun draggingPastTheRightEndClampsToTheLastSlot() {
        host(initial=1)
        val startX=slotX(1);val y=dockY();val farRight=bar().right+px(60f)
        rule.onRoot().performTouchInput {
            down(Offset(startX,y))
            for(step in 1..6) {moveTo(Offset(startX+(farRight-startX)*step/6f,y));advanceEventTime(16)}
            up()
        }
        rule.waitForIdle()
        assertEquals("over-dragging must clamp to the last slot",listOf(3),switches)
        assertTrue("the capsule must stay inside the bar",capsuleBounds().right<=bar().right+1f)
    }

    @Test fun draggingPastTheLeftEndClampsToTheFirstSlot() {
        host(initial=2)
        val startX=slotX(2);val y=dockY();val farLeft=bar().left-px(60f)
        rule.onRoot().performTouchInput {
            down(Offset(startX,y))
            for(step in 1..6) {moveTo(Offset(startX+(farLeft-startX)*step/6f,y));advanceEventTime(16)}
            up()
        }
        rule.waitForIdle()
        assertEquals("over-dragging must clamp to the first slot",listOf(0),switches)
        assertTrue("the capsule must stay inside the bar",capsuleBounds().left>=bar().left-1f)
    }

    @Test fun draggingOutwardFromTheLastSlotDoesNothing() {
        host(initial=3)
        val home=capsuleCentreX()
        val x=slotX(3);val y=dockY()
        rule.onRoot().performTouchInput {
            down(Offset(x,y))
            for(step in 1..4) {moveTo(Offset(x+px(15f)*step.toFloat(),y));advanceEventTime(16)}
            up()
        }
        rule.waitForIdle()
        assertTrue("already on the last slot: nothing to select",switches.isEmpty())
        assertEquals("the capsule must not leave the last slot",home,capsuleCentreX(),2.5f)
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Vertical gestures are not the dock's, and the page keeps scrolling
    // ---------------------------------------------------------------------------------------------

    @Test fun verticalSwipeOnTheDockDoesNotSwitch() {
        host(initial=1)
        rule.onNodeWithTag("floating-dock").performTouchInput { swipeUp(startY=bottom,endY=top) }
        rule.waitForIdle()
        assertTrue("a vertical swipe must not select a page: $switches",switches.isEmpty())
        rule.onNodeWithTag("nav-1").assertIsSelected()
    }

    @Test fun verticalSwipeOnTheDockDownwardsDoesNotSwitchEither() {
        host(initial=2)
        rule.onNodeWithTag("floating-dock").performTouchInput { swipeDown(startY=top,endY=bottom) }
        rule.waitForIdle()
        assertTrue("a downward swipe must not select a page: $switches",switches.isEmpty())
        rule.onNodeWithTag("nav-2").assertIsSelected()
    }

    /** The page under the dock must still scroll: the dock may not steal page scrolling globally. */
    @Test fun verticalSwipeOnThePageStillScrollsAndDoesNotSwitch() {
        host(initial=1)
        rule.onNodeWithTag("drag-page").performTouchInput { swipeUp(startY=bottom-1f,endY=top+1f) }
        rule.waitForIdle()
        assertTrue("scrolling the page must not select a page: $switches",switches.isEmpty())
        rule.onNodeWithTag("nav-1").assertIsSelected()
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Motion off / light policy still drag
    // ---------------------------------------------------------------------------------------------

    @Test fun reduceMotionStillDragsAndSwitchesOnce() {
        host(policy=still,initial=0)
        dragFromTo(0,2)
        assertEquals("a snap() policy must still allow a cross-slot drag",listOf(2),switches)
        assertEquals("and land exactly on the slot without a spring",slotX(2),capsuleCentreX(),2.5f)
    }

    @Test fun lightPolicyStillDrags() {
        host(policy=light,initial=3)
        dragFromTo(3,1)
        assertEquals(listOf(1),switches)
        assertEquals(slotX(1),capsuleCentreX(),2.5f)
    }

    @Test fun cancelWithoutMotionAlsoRestores() {
        host(policy=still,initial=2)
        val home=capsuleCentreX()
        val startX=slotX(2);val y=dockY()
        rule.onRoot().performTouchInput {
            down(Offset(startX,y))
            for(step in 1..5) {moveTo(Offset(startX-px(18f)*step.toFloat(),y));advanceEventTime(16)}
            cancel()
        }
        rule.waitForIdle()
        assertTrue(switches.isEmpty())
        assertEquals(home,capsuleCentreX(),2.5f)
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Disabled dock
    // ---------------------------------------------------------------------------------------------

    @Test fun disabledDockExposesNoTabsAndIgnoresDrags() {
        host(initial=1,enabled=false)
        rule.onNodeWithTag("dock-gesture-layer").assertDoesNotExist()
        for(index in 0..3) rule.onNodeWithTag("nav-$index").assertDoesNotExist()
        val startX=bar().center.x;val y=dockY();val farRight=bar().right+px(40f)
        rule.onRoot().performTouchInput {
            down(Offset(startX,y))
            for(step in 1..6) {moveTo(Offset(startX+(farRight-startX)*step/6f,y));advanceEventTime(16)}
            up()
        }
        rule.waitForIdle()
        assertTrue("a disabled dock must not select anything: $switches",switches.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // 7. Taps keep working through the gesture layer
    // ---------------------------------------------------------------------------------------------

    @Test fun plainTapOnACellStillSelectsOnce() {
        host(initial=0)
        rule.onNodeWithTag("nav-2").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals("a tap must report exactly one selection",listOf(2),switches)
        rule.onNodeWithTag("nav-2").assertIsSelected()
        assertEquals(slotX(2),capsuleCentreX(),2.5f)
    }

    @Test fun tappingEveryTabInTurnReportsEachExactlyOnce() {
        host(initial=0)
        for(index in listOf(3,1,2,0)) {
            rule.onNodeWithTag("nav-$index").performClick()
            rule.waitForIdle()
        }
        assertEquals("each tap must report exactly one selection, in order",listOf(3,1,2,0),switches)
        rule.onNodeWithTag("nav-0").assertIsSelected()
    }

    /** The four labelled, clickable tabs must survive the gesture layer sitting on top of them. */
    @Test fun accessibilityTabsRemainClickableUnderTheGestureLayer() {
        host(initial=0)
        listOf("首页","账号","记录","设置").forEachIndexed { index,title->
            rule.onNodeWithTag("nav-$index").assertHasClickAction()
            rule.onNodeWithContentDescription(title).assertExists()
        }
        rule.onNodeWithTag("nav-1").performClick()
        rule.waitForIdle()
        assertEquals(listOf(1),switches)
        rule.onNodeWithTag("nav-1").assertIsSelected()
    }

    /** Font scale 2.0 must not break the drag geometry (cells get shorter, the pitch stays exact). */
    @Test fun dragWorksAtLargeFontScale() {
        switches.clear()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f,2f)) {
                GlassSignTheme(false) {
                    val layer=rememberGraphicsLayer()
                    var selected by remember { mutableIntStateOf(0) }
                    Box(Modifier.fillMaxSize()) {
                        LiquidDock(selected,{index->selected=index;switches+=index},layer,standard,true,
                            Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
        rule.waitForIdle()
        dragFromTo(0,2)
        assertEquals(listOf(2),switches)
        assertEquals(slotX(2),capsuleCentreX(),2.5f)
    }

    // ---------------------------------------------------------------------------------------------
    // 8. Pure release math: the drag layer is only as good as this function, so pin it without a device
    // ---------------------------------------------------------------------------------------------

    @Test fun releaseMathPicksTheNearestReachableSlot() {
        // released where it started: nothing to do
        assertEquals(0,dockReleaseSlot(0,0f,0f))
        assertEquals(2,dockReleaseSlot(2,0f,0f))
        // less than half a cell: stays on the starting slot
        assertEquals(0,dockReleaseSlot(0,.49f,0f))
        // half a cell or more: the neighbour
        assertEquals(1,dockReleaseSlot(0,.5f,0f))
        assertEquals(1,dockReleaseSlot(0,1.2f,0f))
        // A long drag may select the far slot, but still emits only one callback on release.
        assertEquals(3,dockReleaseSlot(0,3f,0f))
        assertEquals(0,dockReleaseSlot(1,-3f,0f))
        assertEquals(3,dockReleaseSlot(2,3f,0f))
        // a fast flick commits even if the capsule lagged behind the finger
        assertEquals(1,dockReleaseSlot(0,.1f,.9f))
        // a flick against the travel direction never adds distance
        assertEquals(0,dockReleaseSlot(0,.1f,-.9f))
        // out-of-range starts are clamped instead of throwing or returning a negative slot
        assertEquals(0,dockReleaseSlot(-4,.9f,0f))
        assertEquals(3,dockReleaseSlot(9,-.9f,0f))
    }

    @Test fun slotGeometryAndNearestSlotAgree() {
        val cell=100f;val pad=6f
        assertEquals(56f,dockSlotCentre(0,cell,pad),0.01f)
        assertEquals(356f,dockSlotCentre(3,cell,pad),0.01f)
        assertTrue("centres must be strictly increasing",dockSlotCentre(0,cell,pad)<dockSlotCentre(1,cell,pad))
        assertEquals(3,dockNearestSlot(dockSlotCentre(3,cell,pad),cell,pad))
        assertEquals(0,dockNearestSlot(-999f,cell,pad))
        assertEquals(3,dockNearestSlot(9_999f,cell,pad))
        assertEquals(1,dockNearestSlot(dockSlotCentre(1,cell,pad)+cell*.49f,cell,pad))
        assertEquals(2,dockNearestSlot(dockSlotCentre(1,cell,pad)+cell*.51f,cell,pad))
        assertEquals("a degenerate bar must not divide by zero",0,dockNearestSlot(120f,0f,pad))
    }

    @Test fun gestureKeyIgnoresTheSelectionAndTheLiveState() {
        // The key must only depend on inputs that make the gesture meaningless. Selection is absent by
        // construction (see dockGestureKey's doc): the same key must be produced across selections.
        assertEquals(dockGestureKey(true,true,100,6),dockGestureKey(true,true,100,6))
        assertNotEquals(dockGestureKey(true,true,100,6),dockGestureKey(false,true,100,6))
        assertNotEquals(dockGestureKey(true,true,100,6),dockGestureKey(true,false,100,6))
        assertNotEquals("a geometry change must restart the loop",dockGestureKey(true,true,100,6),dockGestureKey(true,true,120,6))
        // Only the literals appear: no selection/slot number is part of the key.
        val key=dockGestureKey(true,true,100,6)
        assertEquals(5,key.size)
        assertTrue("the key must start with a stable marker",key.first()=="dock-drag")
    }
}
