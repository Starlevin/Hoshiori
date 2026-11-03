package com.glasssign.app.local

import com.glasssign.app.login.CookieJar
import org.json.JSONObject

/** Only an explicit platform identity is usable for deduplication; never nickname or a token. */
object AccountIdentity {
    fun read(provider:String,c:JSONObject):String {
        val id=when(provider) {
            "miyoushe" -> c.optString("stuid").ifBlank {
                val jar=CookieJar.parse(c.optString("cookie"),listOf("account_id_v2","account_id","ltuid_v2","ltuid"))
                CookieJar.pick(jar,"account_id_v2","account_id","ltuid_v2","ltuid")
            }
            "tagged" -> c.optString("uid")
            "kurobbs" -> c.optString("userId")
            // The current Skland adapter has no stable community user ID response. Keep separate.
            else -> ""
        }
        return id.takeIf{it!="0" && it.matches(Regex("[A-Za-z0-9_-]{1,100}"))}.orEmpty()
    }
}
