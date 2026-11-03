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
 * Original, cached 24-unit glyphs. Colour comes from the active Material role.
 * MIUix-style redraw (original paths, not copied from MIUix): a slightly heavier
 * 2.0 stroke, a softened roof peak, a rounded file corner, and round slider knobs
 * instead of square ones. Names, size and call sites are unchanged, so the Dock
 * and screens need no other change.
 */
object HoshioriIcons {
    private fun glyph(name:String,draw:androidx.compose.ui.graphics.vector.PathBuilder.()->Unit):ImageVector =
        ImageVector.Builder(name,24.dp,24.dp,24f,24f).apply {
            path(fill=null,stroke=SolidColor(Color.Black),strokeLineWidth=2.0f,
                strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round,pathBuilder=draw)
        }.build()

    val Home=glyph("Hoshiori.Home") {
        moveTo(4f,11f);curveTo(4f,10f,11f,4f,12f,3.5f);curveTo(13f,4f,20f,10f,20f,11f)
        moveTo(6f,9f);verticalLineTo(20f);horizontalLineTo(18f);verticalLineTo(9f)
        moveTo(10f,20f);verticalLineTo(14f);horizontalLineTo(14f);verticalLineTo(20f)
    }
    val Accounts=glyph("Hoshiori.Accounts") {
        moveTo(15.2f,7.3f);curveTo(15.2f,9.1f,13.8f,10.5f,12f,10.5f)
        curveTo(10.2f,10.5f,8.8f,9.1f,8.8f,7.3f);curveTo(8.8f,5.5f,10.2f,4.1f,12f,4.1f)
        curveTo(13.8f,4.1f,15.2f,5.5f,15.2f,7.3f);close()
        moveTo(5.5f,20f);verticalLineTo(18.3f);curveTo(5.5f,14.1f,18.5f,14.1f,18.5f,18.3f);verticalLineTo(20f)
        moveTo(19f,5.7f);curveTo(21.2f,6.2f,21.4f,9.5f,19.2f,10.4f)
        moveTo(3f,5.7f);curveTo(.8f,6.2f,.6f,9.5f,2.8f,10.4f)
    }
    val Records=glyph("Hoshiori.Records") {
        moveTo(5f,4f);horizontalLineTo(14f);curveTo(17f,4f,19f,6f,19f,8f);verticalLineTo(20f);horizontalLineTo(5f);close()
        moveTo(14f,4f);verticalLineTo(8f);horizontalLineTo(19f)
        moveTo(8.5f,12f);lineTo(10f,13.5f);lineTo(13.5f,10f)
        moveTo(8.5f,17f);horizontalLineTo(15.5f)
    }
    val Settings=glyph("Hoshiori.Settings") {
        // Tracks, with a round knob on each row instead of a square slider block.
        moveTo(4f,6f);horizontalLineTo(7f);moveTo(11f,6f);horizontalLineTo(20f)
        moveTo(4f,12f);horizontalLineTo(13f);moveTo(17f,12f);horizontalLineTo(20f)
        moveTo(4f,18f);horizontalLineTo(7f);moveTo(11f,18f);horizontalLineTo(20f)
        moveTo(9f,4f);curveTo(10.1f,4f,11f,4.9f,11f,6f);curveTo(11f,7.1f,10.1f,8f,9f,8f);curveTo(7.9f,8f,7f,7.1f,7f,6f);curveTo(7f,4.9f,7.9f,4f,9f,4f);close()
        moveTo(15f,10f);curveTo(16.1f,10f,17f,10.9f,17f,12f);curveTo(17f,13.1f,16.1f,14f,15f,14f);curveTo(13.9f,14f,13f,13.1f,13f,12f);curveTo(13f,10.9f,13.9f,10f,15f,10f);close()
        moveTo(9f,16f);curveTo(10.1f,16f,11f,16.9f,11f,18f);curveTo(11f,19.1f,10.1f,20f,9f,20f);curveTo(7.9f,20f,7f,19.1f,7f,18f);curveTo(7f,16.9f,7.9f,16f,9f,16f);close()
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
