package com.glasssign.app.data

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.OffsetDateTime

/** An allowlisted local report. Deliberately has no credential, account identity or free-form log fields. */
internal data class LabDiagnosticInput(
    val versionName:String,
    val versionCode:Int,
    val androidApi:Int,
    val accountCount:Int,
    val bindingCount:Int,
    val recordStatuses:List<String>,
    val providers:Map<String,Boolean>,
    val waterDropRequested:Boolean,
    val waterDropAvailable:Boolean,
    val running:Boolean,
    val queued:Boolean,
    val scheduleEnabled:Boolean,
    val lastStarted:String?=null,
    val lastFinished:String?=null,
    val workerReportedError:Boolean=false,
)

/** Unknown status strings and raw adapter exceptions never reach an exported file. */
internal fun labDiagnosticJson(input:LabDiagnosticInput):String=JSONObject().apply {
    put("schema",1)
    put("application","Hoshiori")
    put("version",input.versionName)
    put("versionCode",input.versionCode)
    put("androidApi",input.androidApi)
    put("accounts",input.accountCount.coerceAtLeast(0))
    put("bindings",input.bindingCount.coerceAtLeast(0))
    put("providers",JSONObject().apply {
        listOf("miyoushe","skland","tagged","kurobbs").forEach { id ->
            put(id,input.providers[id]?:true)
        }
    })
    put("graphics",JSONObject().apply {
        put("waterDropRequested",input.waterDropRequested)
        put("waterDropAvailable",input.waterDropAvailable)
    })
    put("scheduler",JSONObject().apply {
        put("running",input.running)
        put("queued",input.queued)
        put("enabled",input.scheduleEnabled)
        put("workerReportedError",input.workerReportedError)
        diagnosticTime(input.lastStarted)?.let{put("lastStarted",it)}
        diagnosticTime(input.lastFinished)?.let{put("lastFinished",it)}
        put("precision","system_scheduled_not_exact")
    })
    put("today",JSONObject().apply {
        val counts=input.recordStatuses.map { status ->
            status.takeIf { it in setOf("success","already","accepted","verifying","failed","needs_action","uncertain","waiting_network","running") } ?: "unknown"
        }.groupingBy{it}.eachCount()
        counts.forEach{(key,value)->put(key,value)}
    })
    put("privacy","counts_and_states_only_no_credentials_or_account_identifiers")
}.toString(2)

private fun diagnosticTime(raw:String?):String? {
    if(raw==null||raw.length !in 16..40)return null
    return runCatching{OffsetDateTime.parse(raw);raw}.getOrNull()
        ?: runCatching{LocalDateTime.parse(raw);raw}.getOrNull()
}
