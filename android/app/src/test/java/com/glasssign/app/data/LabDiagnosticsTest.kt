package com.glasssign.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LabDiagnosticsTest {
    private fun input()=LabDiagnosticInput("0.8.0-preview.1",24,35,2,3,
        listOf("accepted","success","uncertain"),mapOf("skland" to false),true,true,
        false,true,true,lastStarted="2026-11-05T21:00:00+08:00")

    @Test fun realCountersKeepUnconfirmedResultsSeparate() {
        val report=JSONObject(labDiagnosticJson(input()))
        assertEquals(2,report.getInt("accounts"))
        assertEquals(1,report.getJSONObject("today").getInt("accepted"))
        assertEquals(1,report.getJSONObject("today").getInt("success"))
        assertEquals(1,report.getJSONObject("today").getInt("uncertain"))
        assertFalse(report.getJSONObject("providers").getBoolean("skland"))
        assertTrue(report.getJSONObject("providers").getBoolean("miyoushe"))
        assertTrue(report.getJSONObject("scheduler").getBoolean("queued"))
    }

    @Test fun unknownStringsCannotSmuggleCredentialsIntoTheReport() {
        val secret="Cookie=stoken=private; phone=13900000000"
        val report=labDiagnosticJson(input().copy(recordStatuses=listOf(secret),
            providers=mapOf(secret to true),lastStarted=secret,lastFinished=secret))
        assertFalse(report.contains(secret))
        assertFalse(report.contains("13900000000"))
        val json=JSONObject(report)
        assertEquals(1,json.getJSONObject("today").getInt("unknown"))
        assertFalse(json.getJSONObject("scheduler").has("lastStarted"))
        assertEquals(4,json.getJSONObject("providers").length())
    }

    @Test fun requestedAndSupportedAreDifferentFacts() {
        val report=JSONObject(labDiagnosticJson(input().copy(waterDropAvailable=false)))
        val graphics=report.getJSONObject("graphics")
        assertTrue(graphics.getBoolean("waterDropRequested"))
        assertFalse(graphics.getBoolean("waterDropAvailable"))
        assertFalse(report.has("validationPassed"))
    }
}
