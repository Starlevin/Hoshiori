package com.glasssign.app.local

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object SchedulePolicy {
    fun due(now:ZonedDateTime,enabled:Boolean,time:String,lastDay:String):Boolean = enabled && now.withZoneSameInstant(SignRepository.TZ).let { it.toLocalTime()>=LocalTime.parse(time) && it.toLocalDate().toString()!=lastDay }
    fun canCompleteDay(executedDay:String,today:String,waiting:Boolean,retryPending:Boolean):Boolean = executedDay==today && !waiting && !retryPending
}
class SignScheduler(private val context:Context) {
    companion object { const val RUN="glasssign-local-run-v4";const val TICK="glasssign-local-plan-v4" }
    val manager=WorkManager.getInstance(context)
    fun run(ids:List<String>?=null,scheduled:Boolean=false):java.util.UUID {
        val data=Data.Builder().putBoolean("scheduled",scheduled).apply{ids?.let{putStringArray("accounts",it.toTypedArray())}}.build()
        val work=OneTimeWorkRequestBuilder<SignWorker>().setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,2,TimeUnit.MINUTES).addTag(RUN).build()
        // Coalesce repeated clicks/ticks instead of growing a blocked work chain.
        manager.enqueueUniqueWork(RUN,ExistingWorkPolicy.KEEP,work)
        return work.id
    }
    fun restore() {
        val prefs=LocalServices.get(context).prefs
        if(!prefs.getBoolean("schedule_enabled",false)){manager.cancelUniqueWork(TICK);return}
        val tick=PeriodicWorkRequestBuilder<ScheduleTickWorker>(15,TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        manager.enqueueUniquePeriodicWork(TICK,ExistingPeriodicWorkPolicy.KEEP,tick)
        if(due())run(scheduled=true)
    }
    fun due():Boolean {
        val p=LocalServices.get(context).prefs
        return SchedulePolicy.due(ZonedDateTime.now(SignRepository.TZ),p.getBoolean("schedule_enabled",false),p.getString("daily_time","08:10")!!,p.getString("last_scheduled_day","")!!)
    }
    fun save(enabled:Boolean,time:String) {
        LocalTime.parse(time)
        val p=LocalServices.get(context).prefs
        check(p.edit().putBoolean("schedule_enabled",enabled).putString("daily_time",time).commit())
        restore()
    }
}
class ScheduleTickWorker(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result { val scheduler=SignScheduler(applicationContext);if(scheduler.due())scheduler.run(scheduled=true);return Result.success() }
}
class SignWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val s=LocalServices.get(applicationContext)
        val scheduled=inputData.getBoolean("scheduled",false)
        if(scheduled&&!SignScheduler(applicationContext).due())return@withContext Result.success()
        val summary=try {
            kotlinx.coroutines.withTimeout(180_000L) { s.engine.run(inputData.getStringArray("accounts")?.toList()) }
        } catch (_:kotlinx.coroutines.TimeoutCancellationException) {
            LocalSignEngine.mutex.withLock { s.repository.recoverInterrupted() }
            s.prefs.edit().putString("last_finished",SignRepository.now()).putString("last_worker_error","任务等待超时，请查看记录；结果不确定的任务不会自动重发").apply()
            return@withContext Result.failure()
        }
        s.prefs.edit().putString("last_finished",SignRepository.now()).putString("last_trigger",if(scheduled)"scheduled" else "manual").apply()
        if(scheduled&&SchedulePolicy.canCompleteDay(summary.day,SignRepository.day(),summary.waiting,summary.retryPending))
            s.prefs.edit().putString("last_scheduled_day",summary.day).commit()
        notifyResult(applicationContext,summary)
        if(summary.waiting&&runAttemptCount<3)Result.retry() else Result.success()
    }
}
fun notifyResult(context:Context,summary:RunSummary) {
    val manager=context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("local_results_v4","本地签到结果",NotificationManager.IMPORTANCE_DEFAULT))
    if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
    val records=LocalServices.get(context).repository.records(summary.day)
    val ok=records.count{it.status in setOf("success","already")};val action=records.count{it.status=="needs_action"};val failed=records.count{it.status=="failed"}
    val notification=NotificationCompat.Builder(context,"local_results_v4").setSmallIcon(android.R.drawable.ic_menu_agenda).setContentTitle("星栞 · 本地执行记录")
        .setContentText("成功/已签 $ok 项，需操作 $action 项，失败 $failed 项"+if(summary.waiting)"，待网络恢复" else "").setAutoCancel(true).build()
    manager.notify(4,notification)
}
class BootReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action==Intent.ACTION_BOOT_COMPLETED || intent.action==Intent.ACTION_MY_PACKAGE_REPLACED)SignScheduler(context).restore()
    }
}
class GlassSignApplication:Application() {
    override fun onCreate() {super.onCreate();SignScheduler(this).restore()}
}
