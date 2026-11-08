package com.glasssign.app.ui.update

import android.content.Context

/**
 * 用户可开关的「自动检测更新」偏好。
 *
 * 独立偏好文件，不动任何既有用户设置（账号、授权、签到记录、外观、背景）。默认开启：
 * 打开应用时在后台静默检查（限频由 [UpdateAutoCheck] 负责），只有真的有新版本才弹窗；
 * 用户关闭后，冷启动不再发起检查，手动「检查更新」按钮仍然可用。
 */
class UpdatePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(): Boolean = runCatching { prefs.getBoolean(KEY_AUTO_CHECK, true) }.getOrDefault(true)

    fun setEnabled(value: Boolean) {
        runCatching { prefs.edit().putBoolean(KEY_AUTO_CHECK, value).apply() }
    }

    private companion object {
        const val PREFS = "update_preferences_v1"
        const val KEY_AUTO_CHECK = "auto_check_enabled"
    }
}
