package com.glasssign.app.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.glasssign.app.local.LocalServices
import com.glasssign.app.data.RoleChoice
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.roundToInt

/** Standalone debug-only test surface. No account data or network calls. */
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
            setContent { MaterialTheme { GlassPrototype() } }
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

@Composable
private fun GlassPrototype() {
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

    Box(Modifier.fillMaxSize().background(Color(0xFF122234))) {
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
                    .hazeGlassContainer(mergeDistance = if (merge) 24.dp else 0.dp),
            ) {
                val anchor = ((maxWidth - 140.dp) / 2).coerceAtLeast(0.dp)
                val maxGap = minOf(36f, (maxWidth - anchor - DropSize * 2).value.coerceAtLeast(0f))
                Box(
                    Modifier.offset(x = anchor).size(DropSize)
                        .hazeGlass(input, style, HazePerformanceMode.Default),
                )
                Box(
                    Modifier.offset { IntOffset((anchor + DropSize + gap.coerceAtMost(maxGap).dp).roundToPx(), 0) }
                        .size(DropSize)
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
        Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            Column(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("双水滴光学原型", style = MaterialTheme.typography.titleMedium)
                Text(if (hardware) "拖右侧水滴，或上下滚动标尺" else "此设备无法启用完整折射")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = showGlass && !running,
                        onClick = {
                            running = true
                            scope.launch {
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
                                }
                            }
                        },
                    ) { Text("演示融合") }
                    OutlinedButton(enabled = showGlass && !running, onClick = { merge = !merge }) {
                        Text(if (merge) "合并 24dp" else "合并 0dp")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = hardware && !running, onClick = { showGlass = !showGlass }) {
                        Text(if (showGlass) "查看原始标尺" else "显示玻璃")
                    }
                    OutlinedButton(onClick = {
                        scope.launch { listState.animateScrollToItem(listState.firstVisibleItemIndex + 6) }
                    }) { Text("滚动标尺") }
                }
                Text("边缘间隙 ${gap.roundToInt()} dp")
            }
        }
    }
}
