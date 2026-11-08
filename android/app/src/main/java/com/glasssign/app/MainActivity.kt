package com.glasssign.app

import android.os.Bundle
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.glasssign.app.login.*
import com.glasssign.app.ui.*
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.UUID

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        requestHighestRefreshRate()
        setContent {
            var ready by remember{mutableStateOf(!com.glasssign.app.local.Bootstrap.failed)}
            var retrying by remember{mutableStateOf(false)}
            if(!ready)GlassSignTheme(true){RecoveryScreen(busy=retrying) {
                retrying=true
                lifecycleScope.launch {
                    ready=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){runCatching{
                        com.glasssign.app.local.MigrationSnapshot.quarantineInvalid(this@MainActivity)
                        com.glasssign.app.local.LocalServices.get(this@MainActivity)
                        com.glasssign.app.local.SignScheduler(this@MainActivity).restore()
                    }.isSuccess}
                    com.glasssign.app.local.Bootstrap.failed=!ready
                    retrying=false
                }
            }} else {
                val vm:HomeViewModel=viewModel()
                GlassSignTheme(reduceMotion=vm.state.reduceMotion) {
                    // Inside the theme so the committed appearance/icon values are the ones actually shown;
                    // keyed on the theme mode and the icon mode only, so it does not re-run per frame.
                    val themeMode=LocalAppearance.current.mode
                    val iconMode=LocalLauncherIconMode.current
                    val systemDark=isSystemInDarkTheme()
                    LaunchedEffect(themeMode,iconMode,systemDark) {
                        RecentsLabel.update(this@MainActivity, themeMode, iconMode, systemDark, vm.state.overview?.accounts.orEmpty().count{!it.bindingPending&&it.authState=="valid"})
                    }
                    Root(vm)
                }
            }
        }
    }

    /**
     * 请求系统支持的最高刷新率，不锁帧、不写死 120。
     *
     * Android 会按窗口的 preferredDisplayModeId 选 mode；从当前 display 的 supportedModes 里挑
     * refreshRate 最大的一个。高刷设备（90/120/144Hz）自然跑满，60Hz 设备维持 60，30Hz 省电档
     * 由系统策略决定；Compose 本身没有帧率上限，所以 App 不会成为瓶颈。任何失败都静默忽略，
     * 不影响启动。
     */
    private fun requestHighestRefreshRate() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
        runCatching {
            val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
            val mode = display?.supportedModes?.maxByOrNull { it.refreshRate } ?: return
            val params = window.attributes
            params.preferredDisplayModeId = mode.modeId
            window.attributes = params
        }
    }
}

/**
 * Recent-tasks card.
 *
 * The launcher icon is switched by activity-alias, which the system's task snapshot does not follow, so the
 * card is redrawn from the mipmap the current choice resolves to (same pure mapping as the alias). Written
 * once per theme/icon change, never per frame: [last] short-circuits identical calls.
 */
private object RecentsLabel {
    private const val SIZE_PX = 192
    private const val DARK_PLATE = 0xFF101525.toInt()
    private const val LIGHT_PLATE = 0xFFF0F1F7.toInt()

    private var last: String? = null

    fun update(activity: ComponentActivity, pageMode: String, iconMode: LauncherIconMode, systemDark: Boolean, checkedIn: Int) {
        val entry = LauncherIconPlan.targetOf(iconMode, pageMode)
        val dark = LauncherIconPlan.effectiveDark(iconMode, pageMode, systemDark)
        val key = "${entry.name}|$dark|$checkedIn"
        if (key == last) return
        // Already on the UI thread: draw only the plate, never recycle a bitmap the system still shows.
        val bitmap = runCatching { render(activity, entry, dark) }.getOrNull() ?: return
        runCatching {
            activity.setTaskDescription(
                ActivityManager.TaskDescription("星栞", bitmap, if (dark) DARK_PLATE else LIGHT_PLATE),
            )
            last = key
        }
    }

    private fun render(activity: ComponentActivity, entry: LauncherEntry, dark: Boolean): Bitmap {
        val drawable: Drawable = ContextCompat.getDrawable(activity, iconResourceId(entry))!!
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (dark) DARK_PLATE else LIGHT_PLATE)
        if (drawable is AdaptiveIconDrawable) {
            // The adaptive drawable scales its 108-unit artboard itself; only the plate is added here.
            canvas.save()
            canvas.clipRect(Rect(0, 0, SIZE_PX, SIZE_PX))
            drawable.setBounds(0, 0, SIZE_PX, SIZE_PX)
            drawable.draw(canvas)
            canvas.restore()
        } else {
            // Non-adaptive fallback: centre the visible 72 of 108, matching the adaptive crop.
            val visible = (SIZE_PX * 72f / 108f).toInt()
            val inset = (SIZE_PX - visible) / 2
            drawable.setBounds(inset, inset, inset + visible, inset + visible)
            drawable.draw(canvas)
        }
        return bitmap
    }

    private fun iconResourceId(entry: LauncherEntry): Int = when (entry) {
        LauncherEntry.SYSTEM -> R.mipmap.ic_launcher
        LauncherEntry.DARK -> R.mipmap.ic_launcher_dark
        LauncherEntry.LIGHT -> R.mipmap.ic_launcher_light
    }
}

@Composable private fun Root(vm:HomeViewModel) {
    val context=LocalContext.current
    var replacing by rememberSaveable{mutableStateOf<String?>(null)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        val payload=LoginBridge.consume()
        if(result.resultCode==android.app.Activity.RESULT_OK&&payload!=null) {
            val c=runCatching{JSONObject(payload.credentials)}.getOrNull()
            if(c!=null)vm.beginLogin(payload.provider,payload.nickname,c,replacing)
            else vm.reportLoginProblem("授权格式无效，尚未绑定")
        }else{LoginBridge.clear();vm.reportLoginProblem(result.data?.getStringExtra(WebLoginActivity.EXTRA_ERROR) ?: "登录已取消，原有账号与记录保留")}
    }
    val message=vm.state.message
    LaunchedEffect(message?.at){if(message!=null){delay(6500);vm.dismissMessage(message.at)}}
    HoshioriScreen(vm){provider,alias,replace->
        replacing=replace
        launcher.launch(if(provider=="tagged")TaggedLoginActivity.intent(context,alias) else WebLoginActivity.intent(context,provider,alias,UUID.randomUUID().toString()))
    }
}
