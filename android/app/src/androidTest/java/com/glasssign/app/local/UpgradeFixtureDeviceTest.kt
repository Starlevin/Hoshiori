package com.glasssign.app.local

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.glasssign.app.data.RoleChoice
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Explicitly staged synthetic-data upgrade check. Each stage is selected by an instrumentation argument. */
@RunWith(AndroidJUnit4::class)
class UpgradeFixtureDeviceTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val services=LocalServices.get(context)
    private val marker=context.getSharedPreferences("upgrade_fixture_v080",Context.MODE_PRIVATE)
    private val accounts=listOf("migration_fixture_v080_a","migration_fixture_v080_b")
    private val targets=listOf(SignTarget("genshin|fixture_a|cn_gf01","模拟迁移 · A"),SignTarget("genshin|fixture_b|cn_gf01","模拟迁移 · B"))
    private fun selected(stage:String) {
        assumeTrue("Only run with explicit migration_fixture_stage=$stage",
            InstrumentationRegistry.getArguments().getString("migration_fixture_stage")==stage)
    }

    @Test fun seedBeforeUpgrade()=runBlocking {
        selected("seed")
        assertTrue("Use an empty dedicated QA installation",services.repository.accounts().isEmpty())
        assertFalse("An existing fixture must be verified first",marker.getBoolean("seeded",false))
        val manager=WorkManager.getInstance(context)
        manager.cancelUniqueWork(SignScheduler.RUN).result.get(10,TimeUnit.SECONDS)
        manager.cancelUniqueWork(SignScheduler.TICK).result.get(10,TimeUnit.SECONDS)
        val daily=services.prefs.getBoolean("schedule_enabled",false)
        services.prefs.edit().putBoolean("schedule_enabled",false).commit()
        val day=SignRepository.day()
        accounts.forEachIndexed { index,id ->
            services.credentials.save(id,JSONObject().put("fixture_only",true).put("communityCheckin",false))
            services.repository.add("miyoushe","模拟迁移验证 ${index+1}（非真实账号）",id,"genshin")
            services.repository.learnBindings(id,listOf(RoleChoice(targets[index].id,"genshin",targets[index].name)))
            services.repository.auth(id,"valid","仅模拟数据，不调用官方平台")
            services.repository.autoEnabled(id,false)
            assertTrue(services.repository.claim(day,id,targets[index]))
            val result=if(index==0)SignResult("accepted","模拟受理记录，非真实签到",resultSource="accepted")
                else SignResult("uncertain","模拟未知结果，非真实签到")
            services.repository.finish(day,id,targets[index],result)
        }
        marker.edit().putBoolean("seeded",true).putString("day",day)
            .putBoolean("scheduleWasEnabled",daily).putInt("uid",Process.myUid())
            .putLong("firstInstallTime",context.packageManager.getPackageInfo(context.packageName,0).firstInstallTime).commit()
        Unit
    }

    @Test fun verifyAfterUpgrade() {
        selected("verify")
        assertTrue(marker.getBoolean("seeded",false))
        assertEquals(marker.getInt("uid",-1),Process.myUid())
        assertEquals(marker.getLong("firstInstallTime",-1),context.packageManager.getPackageInfo(context.packageName,0).firstInstallTime)
        val day=requireNotNull(marker.getString("day",null))
        accounts.forEachIndexed { index,id ->
            assertTrue(services.repository.accounts().any{it.id==id})
            assertTrue(services.credentials.read(id).getBoolean("fixture_only"))
            assertEquals(targets[index].id,services.repository.bindings(id).single().targetId)
            val row=services.repository.records(day).single{it.accountId==id}
            assertEquals(if(index==0)"accepted" else "uncertain",row.status)
            assertFalse("Upgrade must preserve duplicate-submission protection",services.repository.claim(day,id,targets[index],System.currentTimeMillis()+86_400_000))
        }
    }

    @Test fun removeOnlyTheOwnedSyntheticFixture()=runBlocking {
        selected("cleanup")
        assertTrue(marker.getBoolean("seeded",false))
        accounts.forEach { id ->
            val account=services.repository.accounts().single{it.id==id}
            assertTrue(account.nickname.startsWith("模拟迁移验证"))
            services.bindings.deleteAccount(id)
        }
        services.prefs.edit().putBoolean("schedule_enabled",marker.getBoolean("scheduleWasEnabled",false)).commit()
        marker.edit().clear().commit()
        SignScheduler(context).restore()
        assertFalse(services.repository.accounts().any{it.id in accounts})
    }
}
