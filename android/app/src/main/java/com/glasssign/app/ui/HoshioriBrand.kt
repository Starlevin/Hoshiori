package com.glasssign.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.glasssign.app.R

/**
 * One hand-drawn 24-unit outline family, cached as `val`s so nothing is allocated while drawing.
 *
 * Shared rules (every glyph here follows them):
 * - one 24x24 canvas, `fill = null` (stroke only), 1.75-unit stroke, round caps and round joins,
 *   so the set reads as a single family next to native Material/QMUI icons;
 * - geometry sits on a whole or half unit and keeps ~3 units of padding from the canvas edge
 *   (2.5-3.5 for wide silhouettes), which leaves a clear optical margin at 20-24 dp;
 * - symmetry is either exact (mirrored about x = 12) or deliberately off-axis for directional
 *   glyphs (ChevronRight, Back); closed silhouettes close cleanly, and lines that should join do
 *   join on a shared point rather than crossing;
 * - no colour and no gradient: the single stroke is SolidColor.Black and the caller tints with
 *   MaterialTheme.colorScheme (LocalContentColor provides the default), so light/dark themes and
 *   the selected/unselected dock states keep working unchanged;
 * - no emoji, no decorative sparkles, no text - each mark should be recognisable at 18 dp.
 */
object HoshioriIcons {
    /** Stroke weight shared by the whole family; 24-unit canvas, so this is ~7% of the icon. */
    private const val Stroke=1.75f

    private fun glyph(name:String,draw:androidx.compose.ui.graphics.vector.PathBuilder.()->Unit):ImageVector =
        ImageVector.Builder(name,24.dp,24.dp,24f,24f).apply {
            path(fill=null,stroke=SolidColor(Color.Black),strokeLineWidth=Stroke,
                strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round,pathBuilder=draw)
        }.build()

    // ---- Primary navigation ----------------------------------------------------------------

    /** Home: rounded roof, round-footed body, single arched door. */
    val Home=glyph("Hoshiori.Home") {
        // Roof: one smooth curve from the left wall over the apex to the right wall.
        moveTo(4.5f,11f)
        curveTo(4.5f,11f,9.2f,4.4f,12f,3.25f)
        curveTo(14.8f,4.4f,19.5f,11f,19.5f,11f)
        // Right wall into the rounded foot.
        verticalLineTo(18.5f)
        curveTo(19.5f,19.74f,18.49f,20.75f,17.25f,20.75f);horizontalLineTo(6.75f)
        curveTo(5.51f,20.75f,4.5f,19.74f,4.5f,18.5f);close()
        // Door: real arch head on a 2.5-unit radius, jambs down into the floor line.
        moveTo(9.5f,20.5f)
        verticalLineTo(17.35f);curveTo(9.5f,15.97f,10.62f,14.85f,12f,14.85f)
        curveTo(13.38f,14.85f,14.5f,15.97f,14.5f,17.35f);verticalLineTo(20.5f)
    }

    /** Accounts: one centred identity card with a head-and-shoulders avatar and one short line. */
    val Accounts=glyph("Hoshiori.Accounts") {
        // Card: one closed rounded rectangle.
        moveTo(7f,3.25f);horizontalLineTo(17f)
        curveTo(18.38f,3.25f,19.5f,4.37f,19.5f,5.75f);verticalLineTo(18.25f)
        curveTo(19.5f,19.63f,18.38f,20.75f,17f,20.75f);horizontalLineTo(7f)
        curveTo(5.62f,20.75f,4.5f,19.63f,4.5f,18.25f);verticalLineTo(5.75f)
        curveTo(4.5f,4.37f,5.62f,3.25f,7f,3.25f);close()
        // Avatar: head, then shoulders.
        moveTo(9.3f,8.65f)
        curveTo(10.45f,8.65f,11.39f,9.59f,11.39f,10.74f)
        curveTo(11.39f,11.89f,10.45f,12.83f,9.3f,12.83f)
        curveTo(8.15f,12.83f,7.21f,11.89f,7.21f,10.74f)
        curveTo(7.21f,9.59f,8.15f,8.65f,9.3f,8.65f);close()
        moveTo(6.6f,16.8f)
        curveTo(6.6f,14.65f,7.8f,13.3f,9.3f,13.3f)
        curveTo(10.8f,13.3f,12f,14.65f,12f,16.8f)
        // One text line beside the avatar, on the card's centre line.
        moveTo(14.4f,12f);horizontalLineTo(17.6f)
    }

    /** Records: clean sheet with a filed tick and two ruled rows, no binding rings. */
    val Records=glyph("Hoshiori.Records") {
        // Sheet outline, one closed rounded rectangle.
        moveTo(4.5f,5.25f)
        curveTo(4.5f,4.28f,5.28f,3.5f,6.25f,3.5f);horizontalLineTo(17.75f)
        curveTo(18.72f,3.5f,19.5f,4.28f,19.5f,5.25f);verticalLineTo(18.75f)
        curveTo(19.5f,19.72f,18.72f,20.5f,17.75f,20.5f);horizontalLineTo(6.25f)
        curveTo(5.28f,20.5f,4.5f,19.72f,4.5f,18.75f);close()
        // Filed: tick, long up-stroke.
        moveTo(8.7f,8.6f);lineTo(10.15f,10.05f);lineTo(14.05f,5.75f)
        // Two ruled rows.
        moveTo(7.9f,14f);horizontalLineTo(16.1f)
        moveTo(7.9f,17.2f);horizontalLineTo(12.9f)
    }

    /** Settings: three horizontal sliders with staggered hollow round handles. */
    val Settings=glyph("Hoshiori.Settings") {
        // Top track, broken either side of its handle at x 8.25.
        moveTo(4.5f,5.25f);horizontalLineTo(6.65f)
        moveTo(9.85f,5.25f);horizontalLineTo(19.5f)
        moveTo(8.25f,3.65f)
        curveTo(9.13f,3.65f,9.85f,4.37f,9.85f,5.25f)
        curveTo(9.85f,6.13f,9.13f,6.85f,8.25f,6.85f)
        curveTo(7.37f,6.85f,6.65f,6.13f,6.65f,5.25f)
        curveTo(6.65f,4.37f,7.37f,3.65f,8.25f,3.65f);close()
        // Middle track, broken either side of its handle at x 15.75.
        moveTo(4.5f,12f);horizontalLineTo(14.15f)
        moveTo(17.35f,12f);horizontalLineTo(19.5f)
        moveTo(15.75f,10.4f)
        curveTo(16.63f,10.4f,17.35f,11.12f,17.35f,12f)
        curveTo(17.35f,12.88f,16.63f,13.6f,15.75f,13.6f)
        curveTo(14.87f,13.6f,14.15f,12.88f,14.15f,12f)
        curveTo(14.15f,11.12f,14.87f,10.4f,15.75f,10.4f);close()
        // Bottom track, broken either side of its handle at x 8.25.
        moveTo(4.5f,18.75f);horizontalLineTo(6.65f)
        moveTo(9.85f,18.75f);horizontalLineTo(19.5f)
        moveTo(8.25f,17.15f)
        curveTo(9.13f,17.15f,9.85f,17.87f,9.85f,18.75f)
        curveTo(9.85f,19.63f,9.13f,20.35f,8.25f,20.35f)
        curveTo(7.37f,20.35f,6.65f,19.63f,6.65f,18.75f)
        curveTo(6.65f,17.87f,7.37f,17.15f,8.25f,17.15f);close()
    }

    // ---- Settings categories ---------------------------------------------------------------

    /** Appearance / theme: a half-filled round swatch, i.e. light-and-dark in one mark. */
    val Appearance=glyph("Hoshiori.Appearance") {
        moveTo(12f,3.5f);curveTo(16.69f,3.5f,20.5f,7.31f,20.5f,12f)
        curveTo(20.5f,16.69f,16.69f,20.5f,12f,20.5f)
        curveTo(7.31f,20.5f,3.5f,16.69f,3.5f,12f)
        curveTo(3.5f,7.31f,7.31f,3.5f,12f,3.5f);close()
        moveTo(12f,3.5f);verticalLineTo(20.5f)
        moveTo(12f,3.5f);curveTo(16.69f,3.5f,20.5f,7.31f,20.5f,12f)
        curveTo(20.5f,16.69f,16.69f,20.5f,12f,20.5f);close()
    }

    /** Automation: a clock face (hands at 3:00, a clean radial read) with a check tucked beside it. */
    val Automation=glyph("Hoshiori.Automation") {
        moveTo(9.5f,3f);curveTo(13.09f,3f,16f,5.91f,16f,9.5f)
        curveTo(16f,13.09f,13.09f,16f,9.5f,16f)
        curveTo(5.91f,16f,3f,13.09f,3f,9.5f)
        curveTo(3f,5.91f,5.91f,3f,9.5f,3f);close()
        moveTo(9.5f,5.9f);verticalLineTo(9.6f);horizontalLineTo(12.6f)
        moveTo(15.9f,15.9f);lineTo(17.7f,17.7f);lineTo(21f,14.4f)
    }

    /** Activity / run diagnostics: crisp pulse trace through the centre band. */
    val Activity=glyph("Hoshiori.Activity") {
        moveTo(3.5f,12f);horizontalLineTo(6.5f);lineTo(9.5f,4.5f)
        lineTo(14.5f,19.5f);lineTo(17.5f,12f);horizontalLineTo(20.5f)
    }

    /** Puzzle: a plugin piece with a clean round knob on the top edge. */
    val Puzzle=glyph("Hoshiori.Puzzle") {
        moveTo(5f,4.5f);horizontalLineTo(9f)
        curveTo(8.3f,5.1f,7.85f,5.96f,7.85f,6.93f)
        curveTo(7.85f,8.62f,9.22f,9.99f,10.91f,9.99f)
        curveTo(12.6f,9.99f,13.97f,8.62f,13.97f,6.93f)
        curveTo(13.97f,5.96f,13.52f,5.1f,12.82f,4.5f);horizontalLineTo(19f)
        verticalLineTo(20.5f);horizontalLineTo(5f);close()
    }

    /** Privacy / shield: plain shield silhouette with a keyhole dot and stem. */
    val Shield=glyph("Hoshiori.Shield") {
        moveTo(12f,3.75f);lineTo(19.5f,6.5f);verticalLineTo(11.5f)
        curveTo(19.5f,16.2f,16.55f,19.2f,12f,20.5f)
        curveTo(7.45f,19.2f,4.5f,16.2f,4.5f,11.5f)
        verticalLineTo(6.5f);close()
        moveTo(12f,8.15f);curveTo(12.94f,8.15f,13.7f,8.91f,13.7f,9.85f)
        curveTo(13.7f,10.79f,12.94f,11.55f,12f,11.55f)
        curveTo(11.06f,11.55f,10.3f,10.79f,10.3f,9.85f)
        curveTo(10.3f,8.91f,11.06f,8.15f,12f,8.15f);close()
        moveTo(12f,11.55f);verticalLineTo(15.15f)
    }

    /** About: plain information circle with a dot and a stem. */
    val Info=glyph("Hoshiori.Info") {
        moveTo(12f,3.5f);curveTo(16.69f,3.5f,20.5f,7.31f,20.5f,12f)
        curveTo(20.5f,16.69f,16.69f,20.5f,12f,20.5f)
        curveTo(7.31f,20.5f,3.5f,16.69f,3.5f,12f)
        curveTo(3.5f,7.31f,7.31f,3.5f,12f,3.5f);close()
        moveTo(10.65f,7.75f);horizontalLineTo(13.35f)
        moveTo(12f,11.4f);verticalLineTo(16.3f)
    }

    /** Colour palette: a disc with one paint well, so it reads as colour rather than as a dial. */
    val Palette=glyph("Hoshiori.Palette") {
        moveTo(12f,3.5f);curveTo(16.69f,3.5f,20.5f,7.31f,20.5f,12f)
        curveTo(20.5f,16.69f,16.69f,20.5f,12f,20.5f)
        curveTo(7.31f,20.5f,3.5f,16.69f,3.5f,12f)
        curveTo(3.5f,7.31f,7.31f,3.5f,12f,3.5f);close()
        moveTo(14.4f,13f);curveTo(15.53f,13f,16.45f,13.92f,16.45f,15.05f)
        curveTo(16.45f,16.18f,15.53f,17.1f,14.4f,17.1f)
        curveTo(13.27f,17.1f,12.35f,16.18f,12.35f,15.05f)
        curveTo(12.35f,13.92f,13.27f,13f,14.4f,13f);close()
    }

    /** Wallpaper: two overlapping photos, the front one moon-over-hills. */
    val Wallpaper=glyph("Hoshiori.Wallpaper") {
        moveTo(3.5f,4.5f);horizontalLineTo(14.5f)
        curveTo(15.6f,4.5f,16.5f,5.4f,16.5f,6.5f);verticalLineTo(8.5f)
        moveTo(6f,8.5f);horizontalLineTo(17.5f)
        curveTo(18.6f,8.5f,19.5f,9.4f,19.5f,10.5f);verticalLineTo(18.5f)
        curveTo(19.5f,19.6f,18.6f,20.5f,17.5f,20.5f);horizontalLineTo(6f)
        curveTo(4.9f,20.5f,4f,19.6f,4f,18.5f);verticalLineTo(10.5f)
        curveTo(4f,9.4f,4.9f,8.5f,6f,8.5f);close()
        moveTo(7.6f,13.9f);lineTo(10.4f,11.1f);lineTo(16.4f,17.1f)
        moveTo(14.4f,11.8f);curveTo(15.06f,11.8f,15.6f,12.34f,15.6f,13f)
        curveTo(15.6f,13.66f,15.06f,14.2f,14.4f,14.2f)
        curveTo(13.74f,14.2f,13.2f,13.66f,13.2f,13f)
        curveTo(13.2f,12.34f,13.74f,11.8f,14.4f,11.8f);close()
    }

    /** Typography: a serif capital "A" with its bar, the usual glyph for fonts and text size. */
    val Typography=glyph("Hoshiori.Typography") {
        moveTo(7.25f,20f);lineTo(12f,4.5f);lineTo(16.75f,20f)
        moveTo(9.1f,14.6f);horizontalLineTo(14.9f)
    }

    /** Glass: two offset rounded panes, the back one peeking out top-left and bottom-right. */
    val Glass=glyph("Hoshiori.Glass") {
        moveTo(4.25f,3.5f);horizontalLineTo(15.25f)
        curveTo(16.35f,3.5f,17.25f,4.4f,17.25f,5.5f);verticalLineTo(8.5f)
        moveTo(8.75f,7.5f);horizontalLineTo(18.75f)
        curveTo(19.85f,7.5f,20.75f,8.4f,20.75f,9.5f);verticalLineTo(18.5f)
        curveTo(20.75f,19.6f,19.85f,20.5f,18.75f,20.5f);horizontalLineTo(8.75f)
        curveTo(7.65f,20.5f,6.75f,19.6f,6.75f,18.5f);verticalLineTo(9.5f)
        curveTo(6.75f,8.4f,7.65f,7.5f,8.75f,7.5f);close()
        moveTo(13.6f,10.4f);lineTo(20.75f,10.4f)
    }

    // ---- Theme / system glyphs -------------------------------------------------------------

    /**
     * Moon: a crescent built from two real arcs, so both horns are pointed and the shape is mirrored
     * about y = 12. Outer rim = circle r 8.25 at (12,12); the bite is a circle r 7.0 at (19.5,12),
     * which meets the rim at (17.021, 5.454) and (17.021, 18.546). That leaves the waxing crescent on
     * the left with the family's usual 2-3 unit margin.
     */
    val Moon=glyph("Hoshiori.Moon") {
        moveTo(17.021f,5.454f)
        // outer rim: up over the top, round the left, down to the bottom horn
        curveTo(17.021f,4.35f,13.021f,3.75f,12f,3.75f)
        curveTo(7.44f,3.75f,3.75f,7.44f,3.75f,12f)
        curveTo(3.75f,16.56f,7.44f,20.25f,12f,20.25f)
        curveTo(13.021f,20.25f,17.021f,19.65f,17.021f,18.546f)
        // bite: back up along the inner arc to the top horn
        curveTo(16.19f,17.69f,12.5f,14.18f,12.5f,12f)
        curveTo(12.5f,9.82f,16.19f,6.31f,17.021f,5.454f)
        close()
    }

    /** Sun: a disc inside a halo gap with eight evenly spaced rays, mirrored on both axes. */
    val Sun=glyph("Hoshiori.Sun") {
        moveTo(12f,7f);curveTo(14.76f,7f,17f,9.24f,17f,12f)
        curveTo(17f,14.76f,14.76f,17f,12f,17f)
        curveTo(9.24f,17f,7f,14.76f,7f,12f)
        curveTo(7f,9.24f,9.24f,7f,12f,7f);close()
        moveTo(12f,2.9f);verticalLineTo(4.9f)
        moveTo(12f,19.1f);verticalLineTo(21.1f)
        moveTo(2.9f,12f);horizontalLineTo(4.9f)
        moveTo(19.1f,12f);horizontalLineTo(21.1f)
        moveTo(5.56f,5.56f);lineTo(6.98f,6.98f)
        moveTo(17.02f,17.02f);lineTo(18.44f,18.44f)
        moveTo(5.56f,18.44f);lineTo(6.98f,17.02f)
        moveTo(17.02f,6.98f);lineTo(18.44f,5.56f)
    }

    /** System: a screen on a stand, symmetric about x = 12. */
    val System=glyph("Hoshiori.System") {
        moveTo(5f,3.9f);horizontalLineTo(19f)
        curveTo(20.1f,3.9f,21f,4.8f,21f,5.9f);verticalLineTo(14.5f)
        curveTo(21f,15.6f,20.1f,16.5f,19f,16.5f);horizontalLineTo(5f)
        curveTo(3.9f,16.5f,3f,15.6f,3f,14.5f);verticalLineTo(5.9f)
        curveTo(3f,4.8f,3.9f,3.9f,5f,3.9f);close()
        moveTo(12f,16.5f);verticalLineTo(20.1f)
        moveTo(9f,20.1f);horizontalLineTo(15f)
    }

    // ---- Small utility glyphs used by cards, rows and headers -------------------------------

    /** Right chevron, the "open" affordance on rows. */
    val ChevronRight=glyph("Hoshiori.ChevronRight") {
        moveTo(9.5f,4.5f);lineTo(17f,12f);lineTo(9.5f,19.5f)
    }

    /** Left arrow, the toolbar back affordance: shaft plus a closed arrow head. */
    val Back=glyph("Hoshiori.Back") {
        moveTo(19.5f,12f);horizontalLineTo(4.5f)
        moveTo(10.75f,5.75f);lineTo(4.5f,12f);lineTo(10.75f,18.25f)
    }

    /** Bell: one closed outline (dome, flared skirt, flat bottom) with a centred clapper below it. */
    val Bell=glyph("Hoshiori.Bell") {
        moveTo(5.05f,18.25f);horizontalLineTo(18.95f)
        curveTo(17.45f,17.55f,19.5f,15.6f,19.5f,13.55f)
        curveTo(19.5f,11.4f,18.65f,10.85f,18f,9.2f)
        curveTo(17.45f,7.8f,17.5f,7.3f,16.15f,5.8f)
        curveTo(15f,4.55f,13.6f,3.75f,12f,3.75f)
        curveTo(10.4f,3.75f,9f,4.55f,7.85f,5.8f)
        curveTo(6.5f,7.3f,6.55f,7.8f,6f,9.2f)
        curveTo(5.35f,10.85f,4.5f,11.4f,4.5f,13.55f)
        curveTo(4.5f,15.6f,6.55f,17.55f,5.05f,18.25f)
        close()
        moveTo(10.2f,18.25f)
        curveTo(10.2f,19.25f,11f,20.1f,12f,20.1f)
        curveTo(13f,20.1f,13.8f,19.25f,13.8f,18.25f)
    }

    /**
     * Battery: rounded shell with an end nub on the right and one charge segment inside.
     * Drawn to the same 24-unit rules as the rest of the family (1.75 stroke, ~2.5 units of edge
     * padding), and deliberately distinct from [Clock] so the two read apart at 18 dp.
     */
    val Battery=glyph("Hoshiori.Battery") {
        moveTo(5f,7.5f);horizontalLineTo(15f)
        curveTo(16.1f,7.5f,17f,8.4f,17f,9.5f);verticalLineTo(14.5f)
        curveTo(17f,15.6f,16.1f,16.5f,15f,16.5f);horizontalLineTo(5f)
        curveTo(3.9f,16.5f,3f,15.6f,3f,14.5f);verticalLineTo(9.5f)
        curveTo(3f,8.4f,3.9f,7.5f,5f,7.5f);close()
        moveTo(19f,10.4f);horizontalLineTo(20.2f)
        curveTo(20.75f,10.4f,21.2f,10.85f,21.2f,11.4f);verticalLineTo(12.6f)
        curveTo(21.2f,13.15f,20.75f,13.6f,20.2f,13.6f);horizontalLineTo(19f);close()
        moveTo(6.4f,10.2f);horizontalLineTo(11.2f)
    }

    /** Clock face with hands at three o'clock, the "time" affordance for the schedule row. */
    val Clock=glyph("Hoshiori.Clock") {
        moveTo(12f,3.5f);curveTo(16.69f,3.5f,20.5f,7.31f,20.5f,12f)
        curveTo(20.5f,16.69f,16.69f,20.5f,12f,20.5f)
        curveTo(7.31f,20.5f,3.5f,16.69f,3.5f,12f)
        curveTo(3.5f,7.31f,7.31f,3.5f,12f,3.5f);close()
        moveTo(12f,7f);verticalLineTo(12f);horizontalLineTo(15.9f)
    }
}

@Composable
internal fun HoshioriBrandMark(modifier:Modifier=Modifier) {
    Image(painterResource(R.drawable.hoshiori_mark),null,modifier,contentScale=ContentScale.Fit)
}

@Composable
internal fun HoshioriBrandCard() {
    QCard {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                HoshioriBrandMark(Modifier.size(36.dp))
                Text("星栞 Hoshiori",style=MaterialTheme.typography.titleLarge)
                Text("一枚留住日常的星海书签",style=MaterialTheme.typography.bodyMedium,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("原创形象 · 星海书签",style=MaterialTheme.typography.labelMedium,
                    color=MaterialTheme.colorScheme.primary)
            }
            Image(painterResource(R.drawable.hoshiori_mascot_v076),"星栞的星海书签形象",
                Modifier.widthIn(max=136.dp).height(164.dp),contentScale=ContentScale.Fit)
        }
    }
}
