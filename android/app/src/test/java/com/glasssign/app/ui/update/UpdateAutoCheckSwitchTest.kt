package com.glasssign.app.ui.update

import org.junit.Assert.*
import org.junit.Test

/** 「自动检测更新」开关：关闭后冷启动不静默检查，手动 check() 仍可用。 */
class UpdateAutoCheckSwitchTest {

    private fun vm(autoEnabled: Boolean) = UpdateViewModel(
        build = UpdateBuildInfo("0.7.9-preview.3", 21, "com.glasssign.app", false),
        configLoader = { UpdateChannelConfig.EMPTY },
        service = { error("a disabled auto-check must not reach the network") },
        checkStore = MapUpdateCheckStore(),
        clock = { 2000L },
        autoCheckEnabled = { autoEnabled },
    )

    @Test fun disabledSwitchSkipsTheSilentStartupCheck() {
        val vm = vm(autoEnabled = false)
        // 关闭时 autoCheck 直接返回 false，且不占用 autoCheckAttempted，不触发网络。
        assertFalse(vm.autoCheck())
        assertNull(vm.state.value.result)
        assertFalse(vm.state.value.busy)
        assertFalse(vm.state.value.autoCheckAttempted)
    }

    @Test fun disabledSwitchStillAllowsTheManualCheckToBeRequested() {
        // 手动 check() 走的是另一条路径，不受自动开关限制；这里只验证开关本身不改变 releaseChannel。
        val vm = vm(autoEnabled = false)
        assertEquals(UpdateReleaseChannel.PREVIEW, vm.state.value.releaseChannel)
    }
}
