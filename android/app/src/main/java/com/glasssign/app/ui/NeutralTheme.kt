package com.glasssign.app.ui

import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color

/** Default surfaces have no hue. Coloured themes remain explicit presets; semantic errors stay red. */
internal fun neutralAppearanceScheme(dark:Boolean,pureBlack:Boolean):ColorScheme {
    val base=if(dark)darkColorScheme() else lightColorScheme()
    val background=if(dark)Color.Black else Color.White
    // OLED 纯黑只把"页面底部"压成纯黑；卡片/框仍用有区分度的深灰，避免整屏糊成一片。
    // (2026-11-07 产品决策：不再把 surface / containers 全部抹黑。)
    val surface=if(dark)Color(0xFF151515) else Color(0xFFF7F7F7)
    val ink=if(dark)Color(0xFFF2F2F2) else Color(0xFF191919)
    val secondary=if(dark)Color(0xFFB8B8B8) else Color(0xFF595959)
    val container=if(dark)Color(0xFF242424) else Color(0xFFEDEDED)
    // pureBlack 不再改变卡片配色：只保留明暗/主题差异，卡片层级始终可见。
    return base.copy(
        primary=ink,onPrimary=background,primaryContainer=container,onPrimaryContainer=ink,
        secondary=secondary,onSecondary=background,secondaryContainer=container,onSecondaryContainer=ink,
        tertiary=secondary,onTertiary=background,tertiaryContainer=container,onTertiaryContainer=ink,
        background=background,onBackground=ink,surface=surface,onSurface=ink,
        surfaceVariant=container,onSurfaceVariant=secondary,
        surfaceContainerLowest=background,surfaceContainerLow=surface,
        surfaceContainer=if(dark)Color(0xFF1B1B1B) else Color(0xFFF2F2F2),
        surfaceContainerHigh=if(dark)Color(0xFF252525) else Color(0xFFEAEAEA),
        surfaceContainerHighest=if(dark)Color(0xFF303030) else Color(0xFFE2E2E2),
        inverseSurface=if(dark)Color(0xFFEAEAEA) else Color(0xFF242424),
        inverseOnSurface=if(dark)Color(0xFF191919) else Color(0xFFF2F2F2),
        inversePrimary=if(dark)Color(0xFF191919) else Color(0xFFF2F2F2),
        outline=if(dark)Color(0xFF777777) else Color(0xFF777777),
        outlineVariant=if(dark)Color(0xFF333333) else Color(0xFFDDDDDD),
        surfaceTint=Color.Transparent,
    )
}
