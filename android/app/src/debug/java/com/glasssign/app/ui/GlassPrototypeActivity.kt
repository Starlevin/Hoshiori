package com.glasssign.app.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.glasssign.app.data.RoleChoice
import com.glasssign.app.local.LocalServices
import com.glasssign.app.local.SignRepository
import com.glasssign.app.local.SignResult
import com.glasssign.app.local.SignTarget
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeSourceRetention
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.RefractionProfile
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.glass.hazeGlassContainer
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 独立 debug 预览入口（仅存在于 src/debug，正式包不含此类）。
 * 桌面图标经 debug/AndroidManifest.xml 的 MAIN/LAUNCHER 直接打开本 Activity。
 * 不触碰账号、签到、网络、凭据；seed_records 分支是既有 QA 注入入口，保留不改。
 */
class GlassPrototypeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent.getBooleanExtra("seed_records", false) && packageName.endsWith(".glassqa")) {
            lifecycleScope.launch {
                val message = runCatching { withContext(Dispatchers.IO) { seedGlassQaRecords() } }
                    .fold({ "已准备 60 条本地演示记录" }, { "准备演示记录失败" })
                setContent { MaterialTheme { Surface { Text(message, Modifier.padding(42.dp)) } } }
            }
        } else {
            setContent {
                // 深浅色是预览包内的本地开关，不读写正式 App 的外观持久化设置。
                var dark by rememberSaveable { mutableStateOf(true) }
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    GlassPrototype(dark = dark, onToggleDark = { dark = !dark })
                }
            }
        }
    }

    private fun seedGlassQaRecords() {
        val repo = LocalServices.get(this).repository
        val ids = listOf("glass_qa_alpha", "glass_qa_beta")
        check(repo.accounts().all { it.id in ids })
        val today = LocalDate.now(SignRepository.TZ)
        ids.forEachIndexed { index, id ->
            val game = if (index == 0) "genshin" else "honkai_sr"
            val role = SignTarget("$game|fixture_$index|cn_gf01", "玻璃测试角色 ${index + 1}")
            if (repo.accounts().none { it.id == id }) {
                repo.add("miyoushe", "演示账号 ${index + 1}", id, game)
                repo.learnBindings(id, listOf(RoleChoice(role.id, game, role.name)))
                repo.auth(id, "valid", "仅本地演示数据")
                repo.autoEnabled(id, false)
            }
            repeat(30) { n ->
                val day = today.minusDays(n.toLong()).toString()
                if (repo.claim(day, id, role)) {
                    val status = if (n % 3 == 0) "accepted" else "uncertain"
                    repo.finish(day, id, role, SignResult(status, "演示记录 · 未调用平台"))
                }
            }
        }
        check(repo.accounts().map { it.id }.toSet() == ids.toSet())
        repeat(30) { n ->
            check(repo.records(today.minusDays(n.toLong()).toString()).count { it.accountId in ids } == ids.size)
        }
    }
}

private val DropSize = 52.dp

/**
 * 双水滴 Playground。
 *
 * 融合是真实几何：一个 hazeGlassContainer 内放两个独立 hazeGlass 成员，由容器按
 * mergeDistance 生成桥接/断裂。没有 alpha crossfade，没有渐变蒙层冒充融合，
 * 两滴始终是各自独立的可采样玻璃面。滚动标尺作为恒定采样基准（不随深浅色变化），
 * 便于肉眼核对折射与高光确实来自真实背景。
 */
@Composable
private fun GlassPrototype(dark: Boolean, onToggleDark: () -> Unit) {
    val haze = rememberHazeState()
    val input = remember(haze) { HazeInput.Sources(haze, retention = HazeSourceRetention.ClearWhenUnavailable) }
    val hardware = Build.VERSION.SDK_INT >= 33 && LocalView.current.isHardwareAccelerated
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = 20)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    var gap by remember { mutableFloatStateOf(36f) }
    var merge by remember { mutableStateOf(true) }
    var showGlass by remember { mutableStateOf(hardware) }
    var running by remember { mutableStateOf(false) }
    var demoJob by remember { mutableStateOf<Job?>(null) }
    val plate = if (dark) Color(0xFF0E1A2A) else Color(0xFFDCE4F0)
    val style = remember {
        GlassStyle.regular.then {
            shape(CircleShape)
            tint(Color(0xFFCEDAFF).copy(alpha = .11f))
            optics {
                copy(
                    blurRadius = OpticalSizeValue.Fixed(0.dp),
                    refractionDisplacement = 22.dp,
                    refractionStrength = .92f,
                    refractionProfile = RefractionProfile.Edge(13.dp),
                    refractionHeightFraction = .32f,
                    depth = OpticalSizeValue.Fixed(.84f),
                )
            }
            ambientResponse(.18f)
            specularIntensity(.66f)
            edgeSoftness(1.dp)
        }
    }

    /** 重置到出厂状态；取消仍在写 gap 的演示动画，避免旧协程覆盖新状态。 */
    fun reset() {
        demoJob?.cancel()
        demoJob = null
        running = false
        gap = 36f
        merge = true
        showGlass = hardware
        scope.launch { listState.scrollToItem(20) }
    }

    Box(Modifier.fillMaxSize().background(plate).testTag("dual-droplet-playground")) {
        LazyColumn(Modifier.fillMaxSize().hazeSource(haze), state = listState) {
            items(120) { row ->
                Box(
                    Modifier.fillMaxWidth().height(42.dp)
                        .background(if (row % 4 < 2) Color(0xFFE4E7EF) else Color(0xFF19283C))
                        .drawBehind {
                            val step = 13.dp.toPx()
                            val line = if (row % 4 < 2) Color(0xFF20334D) else Color(0xFFC1D0E2)
                            for (column in 0..(size.width / step).toInt()) {
                                val x = column * step
                                drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                            }
                            drawLine(line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
                            drawLine(Color(0xFFEE8357), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5.dp.toPx())
                        },
                ) {
                    Text("滚动标尺  $row", Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                        color = if (row % 4 < 2) Color(0xFF142030) else Color.White)
                }
            }
        }
        if (showGlass) {
            BoxWithConstraints(
                Modifier.align(Alignment.Center).fillMaxWidth().height(DropSize)
                    .hazeGlassContainer(mergeDistance = if (merge) 24.dp else 0.dp)
                    .testTag("droplet-container"),
            ) {
                val anchor = ((maxWidth - 140.dp) / 2).coerceAtLeast(0.dp)
                val maxGap = minOf(36f, (maxWidth - anchor - DropSize * 2).value.coerceAtLeast(0f))
                Box(
                    Modifier.offset(x = anchor).size(DropSize)
                        .testTag("droplet-a")
                        .hazeGlass(input, style, HazePerformanceMode.Default),
                )
                Box(
                    Modifier.offset { IntOffset((anchor + DropSize + gap.coerceAtMost(maxGap).dp).roundToPx(), 0) }
                        .size(DropSize)
                        .testTag("droplet-b")
                        .draggable(
                            state = rememberDraggableState { dx ->
                                gap = (gap + dx / density).coerceIn(-38f, maxGap)
                            },
                            orientation = Orientation.Horizontal,
                            enabled = !running,
                        )
                        .hazeGlass(input, style, HazePerformanceMode.Default),
                )
            }
        }
        Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), tonalElevation = 3.dp) {
            Column(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("双水滴 Liquid Glass 预览", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (!hardware) "此设备不支持完整折射（需 Android 13+ 且硬件加速）"
                    else "拖动右侧水滴靠近 / 远离，观察桥接、拉伸与断裂",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewButton(
                        label = "自动演示融合",
                        primary = true,
                        enabled = showGlass && !running,
                        modifier = Modifier.testTag("demo-merge"),
                        onClick = {
                            running = true
                            demoJob?.cancel()
                            demoJob = scope.launch {
                                try {
                                    gap = 36f
                                    delay(800)
                                    animate(gap, 3f, animationSpec = tween(1900, easing = LinearEasing)) { v, _ -> gap = v }
                                    delay(1150)
                                    animate(gap, -32f, animationSpec = tween(1250, easing = LinearEasing)) { v, _ -> gap = v }
                                    delay(600)
                                    animate(gap, 36f, animationSpec = tween(2800, easing = LinearEasing)) { v, _ -> gap = v }
                                } finally {
                                    running = false
                                    demoJob = null
                                }
                            }
                        },
                    )
                    PreviewButton(
                        label = "重置",
                        enabled = showGlass && !running,
                        modifier = Modifier.testTag("reset"),
                        onClick = { reset() },
                    )
                    PreviewButton(
                        label = if (dark) "切到浅色" else "切到深色",
                        modifier = Modifier.testTag("toggle-dark"),
                        onClick = onToggleDark,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewButton(
                        label = if (merge) "融合 24dp" else "融合 0dp（对照）",
                        enabled = showGlass && !running,
                        modifier = Modifier.testTag("toggle-merge"),
                        onClick = { merge = !merge },
                    )
                    PreviewButton(
                        label = if (showGlass) "隐藏玻璃" else "显示玻璃",
                        enabled = hardware && !running,
                        modifier = Modifier.testTag("toggle-glass"),
                        onClick = { showGlass = !showGlass },
                    )
                    PreviewButton(
                        label = "滚动标尺",
                        enabled = !running,
                        modifier = Modifier.testTag("scroll-ruler"),
                        onClick = { scope.launch { listState.animateScrollToItem(listState.firstVisibleItemIndex + 6) } },
                    )
                }
                Text(
                    "边缘间隙 ${gap.roundToInt()} dp · ${if (dark) "深色" else "浅色"} · " +
                        if (showGlass) "玻璃开启（真实背景采样）" else "仅原始标尺",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("status"),
                )
            }
        }
    }
}

/**
 * 预览包控制按钮：文字清楚、按下轻微回弹（弹簧缩放，不用 ripple / alpha crossfade），
 * 深浅色下自动取 MaterialTheme 的 surface / onSurface，保证对比度。
 */
@Composable
private fun PreviewButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "press-rebound",
    )
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(12.dp),
        color = when {
            !enabled -> cs.surfaceVariant.copy(alpha = .5f)
            primary -> cs.primary
            else -> cs.surface
        },
        contentColor = when {
            !enabled -> cs.onSurface.copy(alpha = .38f)
            primary -> cs.onPrimary
            else -> cs.onSurface
        },
        border = if (primary || !enabled) null else BorderStroke(1.dp, cs.outline.copy(alpha = .6f)),
    ) {
        Box(
            Modifier.clickable(
                enabled = enabled,
                interactionSource = source,
                indication = null,
                onClick = onClick,
            ).padding(horizontal = 12.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
