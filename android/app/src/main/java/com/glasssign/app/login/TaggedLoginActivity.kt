package com.glasssign.app.login

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.glasssign.app.local.OfficialTransport
import com.glasssign.app.local.SignError
import com.glasssign.app.ui.GlassSignTheme
import kotlinx.coroutines.*
import java.util.UUID

/** GlassSign's clearly labelled account form, never an imitation of an official webpage. */
class TaggedLoginActivity:ComponentActivity() {
    companion object {
        fun intent(context:Context,nickname:String)=Intent(context,TaggedLoginActivity::class.java).putExtra("nickname",nickname)
    }
    private var phone by mutableStateOf("")
    private var secret by mutableStateOf("")
    private var sms by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var cooldown by mutableStateOf(0)
    private lateinit var client:TaggedLoginClient
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE)
        val preferences=getSharedPreferences("local_settings_v4",MODE_PRIVATE)
        val id=preferences.getString("tagged_auth_device_id",null) ?: UUID.randomUUID().toString().replace("-", "").also {
            check(preferences.edit().putString("tagged_auth_device_id",it).commit())
        }
        client=TaggedLoginClient(OfficialTransport(),TaggedLoginClient.Device(id,Build.MODEL,Build.VERSION.RELEASE,Build.MANUFACTURER))
        setContent {
            GlassSignTheme(reduceMotion=true) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                        .verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text("星栞 · 绑定异环",style=MaterialTheme.typography.headlineSmall)
                        Text("账号信息仅发送至完美世界账号服务与塔吉多 HTTPS 接口。密码、验证码不保存；登录后读取角色，再由你确认绑定。",style=MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected=!sms,onClick={if(!busy){sms=false;secret="";error=null}},label={Text("密码登录")},enabled=!busy)
                            FilterChip(selected=sms,onClick={if(!busy){sms=true;secret="";error=null}},label={Text("短信登录")},enabled=!busy)
                        }
                        OutlinedTextField(value=phone,onValueChange={phone=it.filter(Char::isDigit).take(11)},label={Text("手机号")},
                            singleLine=true,enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(value=secret,onValueChange={secret=it.take(if(sms)8 else 128)},label={Text(if(sms)"短信验证码" else "账号密码")},
                            singleLine=true,enabled=!busy,visualTransformation=PasswordVisualTransformation(),
                            keyboardOptions=KeyboardOptions(keyboardType=if(sms)KeyboardType.NumberPassword else KeyboardType.Password),modifier=Modifier.fillMaxWidth())
                        if(sms) OutlinedButton(onClick={sendSms()},enabled=!busy&&cooldown==0&&phone.length==11,modifier=Modifier.fillMaxWidth()) {
                            Text(if(cooldown>0)"${cooldown} 秒后可重发" else "发送验证码")
                        }
                        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                        Button(onClick={login()},enabled=!busy&&phone.length==11&&secret.isNotEmpty(),modifier=Modifier.fillMaxWidth()) {
                            if(busy){CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Spacer(Modifier.width(8.dp))}
                            Text(if(busy)"正在登录并获取授权…" else "登录并读取角色")
                        }
                        TextButton(onClick={finish()},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("取消，返回绑定页")}
                        Text("若平台要求人机验证，将显示错误并停止，不自动重试或绕过。",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    private fun action(block:suspend()->Unit) {
        if(busy)return
        busy=true;error=null
        lifecycleScope.launch {
            try {withTimeout(90_000L){block()}}
            catch(_:TimeoutCancellationException){error="登录请求超时，尚未完成绑定；请检查网络后重试"}
            catch(e:CancellationException){throw e}
            catch(e:SignError){error=e.message ?: "登录未完成，请稍后重试"}
            catch(e:IllegalArgumentException){error=e.message ?: "请检查填写的信息"}
            catch(_:Exception){error="本地登录未完成，请检查网络或稍后重试"}
            finally {busy=false}
        }
    }
    private fun sendSms()=action {
        val submittedPhone=phone
        withContext(Dispatchers.IO){client.sendSms(submittedPhone)}
        cooldown=60
        lifecycleScope.launch {while(cooldown>0){delay(1_000);cooldown--}}
    }
    private fun login()=action {
        val submittedPhone=phone;val submittedSecret=secret;val useSms=sms
        try {
            val credential=withContext(Dispatchers.IO){if(useSms)client.loginSms(submittedPhone,submittedSecret) else client.loginPassword(submittedPhone,submittedSecret)}
            secret="";phone=""
            LoginBridge.deliver(LoginPayload("tagged",intent.getStringExtra("nickname") ?: "异环",credential.toString()))
            setResult(RESULT_OK);finish()
        } finally {secret=""}
    }
}
