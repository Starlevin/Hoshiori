package com.glasssign.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
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
                GlassSignTheme(reduceMotion=vm.state.reduceMotion) {Root(vm)}
            }
        }
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
