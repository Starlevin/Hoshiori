package com.glasssign.app.local

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process

/** Recovery is limited to the optional renderer. Ordinary app kills never reset an experiment. */
internal object ExperimentalUiRecovery {
    private const val PREFS="experimental_ui_recovery_v1"

    fun install(context:Context,labs:LabStore) {
        val app=context.applicationContext
        val prefs=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val oldPid=prefs.getInt("rendering_pid",0)
        val activeSince=prefs.getLong("active_since",Long.MAX_VALUE)
        if(Build.VERSION.SDK_INT>=30&&oldPid!=0&&oldPid!=Process.myPid())runCatching {
            val exit=app.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(app.packageName,oldPid,1).firstOrNull()
            if(exit?.reason==ApplicationExitInfo.REASON_CRASH_NATIVE&&exit.timestamp>=activeSince&&
                labs.isEnabled(LabStore.FLAG_WATER_DROP_GLASS))labs.recoverRecordedUiCrash(true,SignRepository.now())
        }
        prefs.edit().remove("rendering_pid").remove("active_since").apply()
        val previous=Thread.getDefaultUncaughtExceptionHandler()?:return
        if(previous is GlassFailureHandler)return
        Thread.setDefaultUncaughtExceptionHandler(GlassFailureHandler(labs,previous))
    }

    fun rendering(context:Context,active:Boolean) {
        context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().apply {
            if(active){putInt("rendering_pid",Process.myPid());putLong("active_since",System.currentTimeMillis())}
            else {remove("rendering_pid");remove("active_since")}
        }.apply()
    }

    internal fun isGlassFailure(error:Throwable):Boolean=generateSequence(error){it.cause}.take(8).any { cause ->
        cause.stackTrace.any { frame ->
            frame.className.startsWith("com.glasssign.app.ui.WaterDrop")||
                frame.className.startsWith("com.glasssign.app.ui.DockGlass")||
                frame.className.startsWith("dev.chrisbanes.haze.")
        }
    }

    private class GlassFailureHandler(val labs:LabStore,val delegate:Thread.UncaughtExceptionHandler):Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread:Thread,error:Throwable) {
            try {
                if(labs.isEnabled(LabStore.FLAG_WATER_DROP_GLASS)&&isGlassFailure(error))
                    runCatching{labs.recoverRecordedUiCrash(true,SignRepository.now())}
            } finally {delegate.uncaughtException(thread,error)}
        }
    }
}
