package com.glasssign.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the handshake between the page's Haze capture and the experimental dock.
 *
 * The failure mode this guards against is silent: if the page never publishes a source, the glass still
 * draws a plausible surface over an empty state and nothing looks broken. So the wiring is asserted
 * directly - the state is constructible here without a graphics layer precisely so these can run on the JVM.
 */
class WaterDropHazeWiringTest {

    /**
     * The state is created on first ask and shared on later asks.
     *
     * Order matters at runtime: the page composes before the dock, so the state must exist before the page
     * can attach a source to it. Both sides route through one `hazeState()`, which is what makes the order
     * irrelevant and prevents two different states existing for one page.
     */
    @Test
    fun hazeStateIsSharedNotRecreated() {
        val state = newState()
        val first = state.hazeState()
        val second = state.hazeState()
        assertSame("one page must have exactly one Haze state", first, second)
        assertSame("the created state must be stored, or the page could never find it", first, state.haze)
    }

    /** A fresh backdrop has no Haze layer, so the shipped path never pays for one. */
    @Test
    fun freshBackdropHasNoHazeLayer() {
        assertTrue("a backdrop no experiment touched must have no Haze layer", newState().haze == null)
    }

    /**
     * Creating the state does NOT mean a source was published.
     *
     * This is the distinction the dock's honesty guard depends on. A state can exist because the dock asked
     * for it while the page has not attached anything yet; treating that as "wired up" would render glass
     * over nothing.
     */
    @Test
    fun stateExistingIsNotTheSameAsSourceAttached() {
        val state = newState()
        state.hazeState()
        assertFalse(
            "creating the state must not imply a source was published",
            state.hazeSourceAttached,
        )
    }

    /** Attaching flips the flag the dock reads, and marking twice stays true. */
    @Test
    fun markingAttachedIsWhatTheDockObserves() {
        val state = newState()
        assertFalse(state.hazeSourceAttached)
        state.markHazeSourceAttached()
        assertTrue("the page attaching a source must be observable by the dock", state.hazeSourceAttached)
        state.markHazeSourceAttached()
        assertTrue("recompositions must not have to check before marking", state.hazeSourceAttached)
    }

    /**
     * The dock's render path really consults the flag.
     *
     * Guards against the guard itself being dropped: if this check disappears the glass silently renders over
     * an empty state, which looks like success.
     */
    @Test
    fun dockConsultsTheAttachedFlag() {
        val source = readSource("WaterDropDock.kt")
        assertTrue(
            "the dock must check hazeSourceAttached before drawing glass",
            source.contains("hazeSourceAttached"),
        )
        assertTrue(
            "a missing source must route to the opaque fallback",
            source.contains("WaterDropFallback"),
        )
    }

    /**
     * The page attaches a real `hazeSource`, and only when the caller enables it.
     *
     * `attachHazeSource` is the single place that publishes the capture, and it is reached only through the
     * opt-in modifier. A user who never enables the experiment must not get a Haze capture layer.
     */
    @Test
    fun pagePathHasOneAttachmentPoint() {
        val source = readSource("LiquidDock.kt")
        assertTrue("the page must attach a real hazeSource", source.contains("hazeSource(haze)"))
        assertTrue("attachment must live in one function", source.contains("attachHazeSource"))
        assertTrue(
            "the opt-in modifier must gate attachment on its enabled flag",
            source.contains("if(enabled)captured.attachHazeSource(state) else captured"),
        )
    }

    /**
     * The keyed page really opts in, and it uses the experiment flag to decide.
     *
     * This is the end-to-end link that was missing: without it the dock could be correctly written and still
     * never receive a source. Asserting it here means a future refactor that drops the call is caught by a
     * test rather than by a screenshot that quietly shows no refraction.
     */
    @Test
    fun pageOptsInUsingTheExperimentFlag() {
        val source = readSource("HoshioriScreen.kt")
        assertTrue(
            "the page must call the Haze-publishing modifier",
            source.contains("Modifier.hazeSource(hazeState)"),
        )
        assertTrue(
            "the page must enable it from the experiment flag",
            source.contains("rememberDockRendering(rememberLabGlassState().first)"),
        )
    }

    /** The dock never creates its own state, because a sibling can never sample itself. */
    @Test
    fun dockDoesNotCreateItsOwnState() {
        val source = readSource("WaterDropDock.kt")
        assertTrue("the dock must take the page's state", source.contains("backdrop.hazeState()"))
        assertFalse(
            "the dock must never construct a private HazeState",
            source.contains("HazeState()"),
        )
    }

    /**
     * With the experiment off, nothing asks for a Haze state.
     *
     * The state is only created behind the flag, so a user who never enables the experiment never gets a
     * Haze capture layer attached to their page.
     */
    @Test
    fun stateCreationIsGatedOnTheExperimentFlag() {
        val source = readSource("LiquidDock.kt")
        assertTrue(
            "creating the state must be gated on waterDropEnabled",
            source.contains("if(waterDropEnabled&&Build.VERSION.SDK_INT>=33) remember(backdrop){backdrop.hazeState()}"),
        )
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** Only the state machinery is exercised here; none of these tests draw. */
    private fun newState() = PageBackdropState(cached = null)

    private fun readSource(name: String): String {
        val candidates = listOf(
            java.io.File("src/main/java/com/glasssign/app/ui/$name"),
            java.io.File("app/src/main/java/com/glasssign/app/ui/$name"),
        )
        val found = candidates.firstOrNull { it.exists() }
        assertTrue("could not locate $name from ${java.io.File(".").absolutePath}", found != null)
        return found!!.readText()
    }
}
