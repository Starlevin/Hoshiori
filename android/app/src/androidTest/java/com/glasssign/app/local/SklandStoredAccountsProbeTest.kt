package com.glasssign.app.local

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit, read-only migration diagnosis. No account IDs, credentials or role names are logged. */
class SklandStoredAccountsProbeTest {
    @Test fun inspectStoredSklandCompatibilityWithoutSigningIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("skland_stored_probe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accounts = SignRepository(context).use { repo -> repo.accounts().filter { it.provider == "skland" && !it.bindingPending } }
        if(InstrumentationRegistry.getArguments().getString("skland_repair") == "true") {
            // Explicit operational mode: only the normal app's verification/reconciliation paths.
            // It persists renewed authorization and official status; neither path submits a check-in.
            val local=LocalServices.get(context)
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                for((index,account) in accounts.withIndex()) {
                    local.engine.verify(account.id)
                    val after=local.repository.accounts().first{it.id==account.id}
                    Log.i("SklandStoredProbe","repair-slot=${index+1} authValid=${after.authState=="valid"}")
                    if(after.authState=="valid")org.junit.Assert.assertFalse("a successful read-only verification clears the obsolete discovery claim",
                        local.repository.records(SignRepository.day()).any{it.accountId==account.id&&it.targetId=="discovery"})
                    for(binding in local.repository.bindings(account.id).filter{it.targetId.startsWith("arknights|")}) {
                        val exists=local.repository.records(SignRepository.day()).any{it.accountId==account.id&&it.targetId==binding.targetId}
                        if(exists) {
                            local.engine.reconcile(account.id,binding.targetId)
                            val result=local.repository.records(SignRepository.day()).first{it.accountId==account.id&&it.targetId==binding.targetId}
                            Log.i("SklandStoredProbe","repair-slot=${index+1} arknightsState=${result.status} source=${result.resultSource}")
                            org.junit.Assert.assertEquals("already",result.status)
                            org.junit.Assert.assertEquals("official",result.resultSource)
                        }
                    }
                }
            }
            return
        }
        accounts.forEachIndexed { index, account ->
            val c = LocalCredentialStore(context).read(account.id)
            val raw = c.optString("dId").trim()
            Log.i("SklandStoredProbe", "slot=${index + 1} objectDevice=${raw.startsWith("{")} webSource=${c.optString("source") == "web-login"}")
            fun query(label: String, credential: JSONObject): Boolean = try {
                val targets = SklandAdapter(OfficialTransport()).targets(credential) {}
                Log.i("SklandStoredProbe", "slot=${index + 1} $label success arknightsRoles=${targets.count { it.id.startsWith("arknights|") }}")
                true
            } catch (e: SignError) {
                Log.i("SklandStoredProbe", "slot=${index + 1} $label rejected kind=${e.kind}")
                false
            }
            if (!query("stored", c)) {
                val id = if(raw.startsWith("{"))runCatching { JSONObject(raw).opt("id") as? String }.getOrNull().orEmpty() else raw
                val normalized = JSONObject(c.toString()).put("dId", id)
                if (id.isNotBlank()) {
                    if(raw.startsWith("{"))query("normalized-device", normalized)
                    val time=Signing.now()
                    val path="/api/v1/auth/refresh"
                    val headers=mapOf("cred" to c.getString("cred"),"platform" to "1","timestamp" to time,
                        "dId" to id,"vName" to "1.5.1","sign" to Signing.skland(path,"",time,
                            Signing.sklandContext("1",time,id,"1.5.1"),c.getString("signToken")))
                    try {
                        val refreshed=OfficialTransport().call("GET","https://zonai.skland.com$path",headers)
                        Log.i("SklandStoredProbe","slot=${index+1} refresh-code=${refreshed.optInt("code",-1)}")
                        val token=refreshed.optJSONObject("data")?.optString("token").orEmpty()
                        if(refreshed.optInt("code",-1)==0&&token.isNotBlank()) {
                            normalized.put("signToken",token)
                            query("renewed-session",normalized)
                            c.put("signToken",token).put("dId",id)
                        }
                    } catch(e:SignError) {Log.i("SklandStoredProbe","slot=${index+1} refresh rejected kind=${e.kind}")}
                }
            }
            if (index == accounts.lastIndex && InstrumentationRegistry.getArguments().getString("skland_status_probe") == "true") {
                val transport = OfficialTransport()
                val target = SklandAdapter(transport).targets(c) {}.firstOrNull { it.id.startsWith("arknights|") }
                if (target != null) {
                    val parts = target.id.split('|')
                    val time = Signing.now()
                    val query = linkedMapOf("gameId" to parts[2], "uid" to parts[1])
                    val queryString = query.entries.joinToString("&") { "${it.key}=${it.value}" }
                    val path = "/api/v1/game/attendance"
                    val headers = mapOf("cred" to c.getString("cred"), "platform" to "1", "timestamp" to time,
                        "dId" to raw, "vName" to "1.5.1", "sign" to Signing.skland(path, queryString, time,
                            Signing.sklandContext("1", time, raw, "1.5.1"), c.getString("signToken")))
                    try {
                        val response = transport.call("GET", "https://zonai.skland.com$path", headers, query)
                        val data = response.optJSONObject("data")
                        val keys = data?.keys()?.asSequence()?.filter { it.matches(Regex("[A-Za-z_]{1,30}")) }?.toList().orEmpty()
                        Log.i("SklandStoredProbe", "readonly-attendance code=${response.optInt("code", -1)} dataKeys=$keys")
                        val record=data?.optJSONArray("records")?.optJSONObject(0)
                        val recordKeys=record?.keys()?.asSequence()?.filter{it.matches(Regex("[A-Za-z_]{1,30}"))}?.toList().orEmpty()
                        Log.i("SklandStoredProbe","attendance-record-keys=$recordKeys timestampType=${record?.opt("ts")?.javaClass?.simpleName} currentTimestampType=${data?.opt("currentTs")?.javaClass?.simpleName}")
                        val recordTs=record?.optLong("ts",0) ?: 0
                        val currentTs=data?.optLong("currentTs",0) ?: 0
                        if(recordTs>0&&currentTs>0)Log.i("SklandStoredProbe","attendance-record-date="+java.time.Instant.ofEpochSecond(recordTs).atZone(SignRepository.TZ).toLocalDate()+" server-date="+java.time.Instant.ofEpochSecond(currentTs).atZone(SignRepository.TZ).toLocalDate())
                        for (key in keys) {
                            val value = data?.opt(key)
                            if (value is Boolean) Log.i("SklandStoredProbe", "status-flag $key=$value")
                        }
                    } catch (e: SignError) { Log.i("SklandStoredProbe", "readonly-attendance unavailable kind=${e.kind}") }
                }
            }
        }
    }
}
