package com.glasssign.app.login

import com.glasssign.app.local.*
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Direct, user-initiated account authentication. Does not inspect browser/app sessions.
 * Protocol reference: Candy-QAQ/NTE-Auto-Sign (MIT); see third-party notices.
 */
class TaggedLoginClient(private val http:Transport,private val device:Device) {
    data class Device(val id:String,val model:String,val android:String,val manufacturer:String)
    companion object {
        private const val ACCOUNT="https://user.laohu.com"
        private const val COMMUNITY="https://bbs-api.tajiduo.com"
        // Public client protocol configuration, not an account credential.
        private const val APP_SECRET="89155cc4e8634ec5b1b6364013b23e3e"
        internal fun encrypted(value:String):String {
            val cipher=Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(APP_SECRET.takeLast(16).toByteArray(Charsets.UTF_8),"AES"))
            return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
        }
        internal fun signed(values:Map<String,String>):Map<String,String> = values+mapOf("sign" to Signing.md5(values.toSortedMap().values.joinToString("")+APP_SECRET))
    }
    private fun validatePhone(phone:String) { require(phone.matches(Regex("1[3-9][0-9]{9}"))){"请输入正确的大陆手机号"} }
    private fun params(milliseconds:Boolean=false)=linkedMapOf(
        "deviceType" to device.model,"deviceId" to device.id,"deviceName" to device.model,
        "deviceSys" to device.android,"deviceModel" to device.model,"appId" to "10550",
        "sdkVersion" to "4.129.0","bid" to "com.pwrd.htassistant","channelId" to "1",
        "t" to if(milliseconds)System.currentTimeMillis().toString() else Signing.now(),
    )
    private fun account(path:String,form:Map<String,String>,stage:String):JSONObject {
        val response=http.call("POST",ACCOUNT+path,headers=mapOf("platform" to "android","User-Agent" to "LaohuSDK/${form["sdkVersion"] ?: "4.129.0"} (android os ${device.android};mobile manufacturer ${device.manufacturer}; model ${device.model})"),form=signed(form))
        checked(response,stage)
        return response
    }
    private fun checked(response:JSONObject,stage:String) {
        if(response.optString("code")=="0")return
        val code=response.optString("code").takeIf{it.matches(Regex("[A-Za-z0-9_-]{1,12}"))} ?: "unknown"
        // Never surface raw server bodies: they may include user data or credentials.
        val text=response.optString("message",response.optString("msg"))
        val hint=when {
            listOf("人机","风控","滑块","robot","risk","验证页面").any{text.contains(it,true)} -> "需要本人在官方页面完成安全验证；未绕过验证"
            listOf("频繁","次数","限制","rate").any{text.contains(it,true)} -> "请求受限，请稍后重试"
            else -> "请核对账号信息；如仍失败，请记录此错误码"
        }
        throw SignError("auth","$stage 未完成（$code）：$hint")
    }
    fun sendSms(phone:String) {
        validatePhone(phone)
        account("/m/newApi/sendPhoneCaptchaWithOutLogin",params().apply {
            put("versionCode","1");put("type","16");put("areaCodeId","1");put("cellphone",phone)
        },"发送验证码")
    }
    fun loginSms(phone:String,code:String):JSONObject {
        validatePhone(phone);require(code.matches(Regex("[0-9]{4,8}"))){"请输入短信验证码"}
        val response=account("/openApi/sms/new/login",params(true).apply {
            put("version","1");put("type","16");put("areaCodeId","1");put("idfa","");put("adm","");put("mac","")
            put("cellphone",encrypted(phone));put("captcha",encrypted(code))
        },"短信登录")
        return exchange(response)
    }
    fun loginPassword(phone:String,password:String):JSONObject {
        validatePhone(phone);require(password.isNotEmpty()&&password.length<=128){"请输入账号密码"}
        val response=account("/openApi/secureLogin",params(true).apply {
            put("version","17");put("sdkVersion","4.327.0");put("idfa","");put("adm","");put("mac","")
            put("username",encrypted(phone));put("password",encrypted(password))
        },"账号登录")
        return exchange(response)
    }
    private fun exchange(response:JSONObject):JSONObject {
        val result=response.optJSONObject("result") ?: throw SignError("protocol","账号服务未返回有效授权，尚未绑定")
        val token=result.optString("token");val userId=result.optString("userId")
        if(token.isBlank()||userId.isBlank())throw SignError("protocol","账号服务授权不完整，尚未绑定")
        val reply=http.call("POST",COMMUNITY+"/usercenter/api/login",
            headers=mapOf("platform" to "android","deviceId" to device.id,"appVersion" to "1.2.5","uid" to "0","ds" to Signing.tagged(Signing.now(),Signing.nonce(8)),"User-Agent" to "okhttp/4.12.0"),
            form=mapOf("token" to token,"userIdentity" to userId,"appId" to "10551"))
        checked(reply,"塔吉多授权交换")
        val data=reply.optJSONObject("data") ?: throw SignError("protocol","塔吉多授权响应不完整，尚未绑定")
        if(data.optString("refreshToken").isBlank()||data.optString("accessToken").isBlank())throw SignError("protocol","塔吉多未签发完整授权，尚未绑定")
        return JSONObject().put("refreshToken",data.getString("refreshToken")).put("uid",data.optString("uid",userId))
            .put("deviceId",device.id).put("gameIds",org.json.JSONArray().put("1289")).put("communityCheckin",true).put("source","account-login")
    }
}
