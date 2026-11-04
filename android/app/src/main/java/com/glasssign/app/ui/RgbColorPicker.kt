package com.glasssign.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Draft changes stay inside this control; preferences are written only on explicit Apply. */
@Composable
internal fun RgbColorPicker(seed:Int,onApply:(Int)->Unit) {
    var draft by rememberSaveable(seed){mutableIntStateOf(seed)}
    val red=(draft ushr 16) and 255
    val green=(draft ushr 8) and 255
    val blue=draft and 255
    Text("颜色选择器",style=MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth().padding(vertical=8.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).background(Color(draft),CircleShape)
            .border(1.dp,MaterialTheme.colorScheme.outlineVariant,CircleShape))
        Text("#%02X%02X%02X".format(red,green,blue),modifier=Modifier.weight(1f),
            style=MaterialTheme.typography.bodyLarge)
    }
    ChannelSlider("红色",red){draft=(draft and 0xFF00FFFF.toInt()) or (it shl 16)}
    ChannelSlider("绿色",green){draft=(draft and 0xFFFF00FF.toInt()) or (it shl 8)}
    ChannelSlider("蓝色",blue){draft=(draft and 0xFFFFFF00.toInt()) or it}
    Text("拖动预览色块，点击应用后保存主题色。",style=MaterialTheme.typography.bodySmall,color=Muted)
    TextButton(onClick={onApply(Color(red,green,blue).toArgb())},modifier=Modifier.heightIn(min=48.dp)) {Text("应用此颜色")}
}

@Composable
private fun ChannelSlider(label:String,value:Int,onValue:(Int)->Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(label,style=MaterialTheme.typography.bodyMedium)
            Text(value.toString(),style=MaterialTheme.typography.bodyMedium)
        }
        Slider(value=value.toFloat(),onValueChange={onValue(it.roundToInt().coerceIn(0,255))},
            valueRange=0f..255f,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).semantics {
                contentDescription="$label 通道"
                stateDescription="$value / 255"
            })
    }
}
