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

/** Original, cached 24-unit glyphs. Colour comes from the active Material role. */
object HoshioriIcons {
    private fun glyph(name:String,draw:androidx.compose.ui.graphics.vector.PathBuilder.()->Unit):ImageVector =
        ImageVector.Builder(name,24.dp,24.dp,24f,24f).apply {
            path(fill=null,stroke=SolidColor(Color.Black),strokeLineWidth=1.8f,
                strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round,pathBuilder=draw)
        }.build()

    val Home=glyph("Hoshiori.Home") {
        moveTo(4f,10f);lineTo(12f,3.5f);lineTo(20f,10f)
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
        moveTo(5f,4f);horizontalLineTo(15f);lineTo(19f,8f);verticalLineTo(20f);horizontalLineTo(5f);close()
        moveTo(15f,4f);verticalLineTo(8f);horizontalLineTo(19f)
        moveTo(8.5f,12f);lineTo(10f,13.5f);lineTo(13.5f,10f)
        moveTo(8.5f,17f);horizontalLineTo(15.5f)
    }
    val Settings=glyph("Hoshiori.Settings") {
        moveTo(4f,6f);horizontalLineTo(7f);moveTo(11f,6f);horizontalLineTo(20f)
        moveTo(4f,12f);horizontalLineTo(13f);moveTo(17f,12f);horizontalLineTo(20f)
        moveTo(4f,18f);horizontalLineTo(7f);moveTo(11f,18f);horizontalLineTo(20f)
        moveTo(7f,4f);horizontalLineTo(11f);verticalLineTo(8f);horizontalLineTo(7f);close()
        moveTo(13f,10f);horizontalLineTo(17f);verticalLineTo(14f);horizontalLineTo(13f);close()
        moveTo(7f,16f);horizontalLineTo(11f);verticalLineTo(20f);horizontalLineTo(7f);close()
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
