package com.glasssign.app.local

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * 插件平台「领签」的本地模拟适配器：不联网、不读第三方数据、结果明确标注，且未安装时拒绝签到。
 */
class LingqianAdapterTest {

    private val target = SignTarget("task|daily", "领签 · 每日任务")

    @Test fun refusesWhenThePluginIsNotInstalled() {
        val adapter = LingqianAdapter(ready = { false })
        val r = adapter.checkin(JSONObject(), target) {}
        assertEquals("needs_action", r.status)
        assertTrue("必须引导去插件页安装", r.message.contains("插件"))
        assertFalse(r.isOfficial)
        // 未安装时没有发生任何提交，装好插件后允许再试一次，因此这一行是可重试的。
        assertTrue(r.retryable)
    }

    @Test fun installedPluginCompletesALocallyLabelledSimulation() {
        val adapter = LingqianAdapter(ready = { true })
        val r = adapter.checkin(JSONObject(), target) {}
        assertEquals("success", r.status)
        assertEquals(LingqianPlugin.SOURCE, r.resultSource)
        assertTrue("消息必须写明是本地模拟", r.message.contains("本地模拟"))
        assertTrue("不得冒充官方结论", r.message.contains("不代表官方"))
        assertFalse("本地模拟绝不能算官方确认", r.isOfficial)
        assertTrue("本地模拟没有官方验证时间", r.verifiedAt.isBlank())
    }

    @Test fun targetsExposeExactlyOneSimulatedTask() {
        val adapter = LingqianAdapter(ready = { true })
        val targets = adapter.targets(JSONObject()) {}
        assertEquals(1, targets.size)
        assertEquals("task|daily", targets.single().id)
    }

    @Test fun statusQueryNeverClaimsAnOfficialResult() {
        val adapter = LingqianAdapter(ready = { true })
        val r = adapter.queryStatus(JSONObject(), target) {}
        assertNotEquals("success", r.status)
        assertFalse(r.isOfficial)
        assertEquals(LingqianPlugin.SOURCE, r.resultSource)
    }
}
