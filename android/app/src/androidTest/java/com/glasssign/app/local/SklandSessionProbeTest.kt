package com.glasssign.app.local

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Explicit opt-in diagnosis: pending Skland draft only; no sign-in submission or local writes. */
class SklandSessionProbeTest {
    @Test fun comparePendingSessionContexts() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("skland_probe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = SignRepository(context)
        val draft = repository.use { it.accounts().lastOrNull { a -> a.provider == "skland" && a.bindingPending } }
        assumeTrue("No pending Skland draft", draft != null)
        val credential = LocalCredentialStore(context).read(draft!!.id)
        if (InstrumentationRegistry.getArguments().getString("production_adapter") == "true") {
            val targets = SklandAdapter(OfficialTransport()).targets(credential) {}
            val ak = targets.count { it.id.startsWith("arknights|") }
            val ef = targets.count { it.id.startsWith("endfield|") }
            Log.i("SklandProbe", "production-adapter success arknightsRoles=$ak endfieldRoles=$ef")
            org.junit.Assert.assertTrue("Official role response must contain the expected Arknights role", ak > 0)
            return
        }
        val did = credential.getString("dId")
        Log.i("SklandProbe", "encodingCheck slashEscaped=${JSONObject.quote(did).contains("\\/")} objectValue=${did.startsWith("{")}")
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(20, TimeUnit.SECONDS).build()
        fun probe(label: String, platform: String, version: String, token: String, path: String): JSONObject {
            val time = (System.currentTimeMillis() / 1000).toString()
            val signedContext = "{\"platform\":\"$platform\",\"timestamp\":\"$time\",\"dId\":" + JSONObject.quote(did).replace("\\/", "/") + ",\"vName\":\"$version\"}"
            val request = Request.Builder().url("https://zonai.skland.com$path")
                .header("cred", credential.getString("cred"))
                .header("platform", platform).header("timestamp", time)
                .header("dId", credential.getString("dId")).header("vName", version)
                .header("sign", Signing.skland(path, "", time, signedContext, token))
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
                .header("Origin", "https://www.skland.com").header("Referer", "https://www.skland.com/").get().build()
            return client.newCall(request).execute().use { response ->
                val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrDefault(JSONObject())
                val code = json.optString("code").takeIf { it.matches(Regex("[0-9-]{1,10}")) } ?: "absent"
                val delta = json.optLong("timestamp", 0).takeIf { it > 0 }?.minus(time.toLong())
                val count = json.optJSONObject("data")?.optJSONArray("list")?.length() ?: 0
                Log.i("SklandProbe", "$label HTTP=${response.code} business=$code serverDelta=$delta gameGroups=$count")
                json
            }
        }
        val token = credential.getString("signToken")
        probe("app-context", "1", "1.5.1", token, "/api/v1/game/player/binding")
        val web = probe("web-context", "3", "1.0.0", token, "/api/v1/game/player/binding")
        if (web.optString("code") != "0") {
            val refreshed = probe("web-refresh", "3", "1.0.0", token, "/api/v1/auth/refresh")
            val freshToken = refreshed.optJSONObject("data")?.optString("token").orEmpty()
            if (refreshed.optString("code") == "0" && freshToken.isNotBlank())
                probe("web-refreshed-binding", "3", "1.0.0", freshToken, "/api/v1/game/player/binding")
        }
    }
}
