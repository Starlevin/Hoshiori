package com.glasssign.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs

/** App-only adapter. Optics, combined icon capture and drag live in the attributed upstream component. */
@Composable
internal fun UpstreamLiquidDock(selected:Int,onSelect:(Int)->Unit,backdrop:Backdrop,
    modifier:Modifier=Modifier,onOverlapChange:(Float)->Unit={}) {
    val report by rememberUpdatedState(onOverlapChange)
    val previous=remember{intArrayOf(-1)}
    val height=liquidDockHeight(LocalDensity.current.fontScale)
    val colors=MaterialTheme.colorScheme
    // Upstream remembers gesture collectors by getter identity. Keep that getter stable while its
    // snapshot value changes, otherwise a tap recreates the state holder observed by the collector.
    val selection=rememberUpdatedState(selected)
    val callback=rememberUpdatedState(onSelect)
    val getSelection=remember{ {selection.value} }
    val commitSelection=remember{ {index:Int->callback.value(index)} }
    val labels=listOf("首页","账号","记录","设置")
    val icons=listOf(HoshioriIcons.Home,HoshioriIcons.Accounts,HoshioriIcons.Records,HoshioriIcons.Settings)
    BoxWithConstraints(modifier.zIndex(1f).fillMaxWidth().onSizeChanged {
        if(it.height!=previous[0]) {previous[0]=it.height;report(it.height.toFloat())}
    }.navigationBarsPadding().padding(horizontal=GlassTokens.margin).padding(bottom=GlassTokens.lift),
        contentAlignment=Alignment.Center) {
        // Upstream gesture closures remember their cell width; rebuild only when geometry changes.
        key(constraints.maxWidth,constraints.maxHeight,height) {
        LiquidBottomTabs(selectedTabIndex=getSelection,onTabSelected=commitSelection,backdrop=backdrop,tabsCount=4,
            modifier=Modifier.widthIn(max=GlassTokens.maxWidth).fillMaxWidth().height(height).testTag("floating-dock"),
            barHeight=height,isLightTheme=colors.background.luminance()>.5f,
            accentColor=colors.primary,containerColor=colors.surface.copy(alpha=.4f)) {
            labels.forEachIndexed {index,label ->
                LiquidBottomTab(onClick={onSelect(index)},modifier=Modifier.testTag("nav-$index").semantics {
                    contentDescription=label
                    stateDescription=if(selected==index)"已选中" else "未选中"
                    this.selected=selected==index
                }) {
                    Icon(icons[index],null,Modifier.size(22.dp),tint=colors.onSurfaceVariant)
                    Text(label,style=MaterialTheme.typography.labelMedium.copy(fontSize=11.sp),color=colors.onSurfaceVariant,maxLines=1)
                }
            }
        }
        }
    }
}
