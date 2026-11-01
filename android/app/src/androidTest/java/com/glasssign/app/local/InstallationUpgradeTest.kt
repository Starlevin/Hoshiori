package com.glasssign.app.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit two-install harness only; never seed a user's phone or an occupied database. */
@RunWith(AndroidJUnit4::class)
class InstallationUpgradeTest {
    @Test fun installationUpgradeScenario() {
        val args=InstrumentationRegistry.getArguments()
        val scenario=args.getString("installationUpgrade")
        assumeTrue("Run only in the dedicated two-APK upgrade harness",scenario!=null)
        check(android.os.Build.HARDWARE in listOf("ranchu","goldfish")) { "Dedicated emulator required" }
        val context=ApplicationProvider.getApplicationContext<Context>()
        val id="hoshiori-synthetic-upgrade-fixture"
        val secrets=LocalCredentialStore(context)
        when(scenario) {
            "seed052" -> {
                assertTrue(context.packageManager.getPackageInfo(context.packageName,0).versionName!!.startsWith("0.5.2"))
                context.openOrCreateDatabase("local_sign_v4.db",Context.MODE_PRIVATE,null).use { db ->
                    assertEquals(3,db.version)
                    db.rawQuery("SELECT COUNT(*) FROM accounts",null).use{c->c.moveToFirst();assertEquals(0,c.getInt(0))}
                    secrets.save(id,JSONObject("""{"cookie":"synthetic-only","games":["genshin","zzz"]}"""))
                    db.execSQL("INSERT INTO accounts VALUES(?,'miyoushe','合成升级账号',0,'date','valid','synthetic','','')",arrayOf(id))
                    db.execSQL("INSERT INTO records VALUES('2026-11-01',?,'zzz|fixture|server','合成升级记录','success','synthetic',1,'date',0)",arrayOf(id))
                }
            }
            "verify060" -> {
                assertTrue(context.packageManager.getPackageInfo(context.packageName,0).versionName!!.startsWith("0.6.0"))
                val s=LocalServices.get(context)
                val a=s.repository.accounts().single{it.id==id}
                assertEquals(4,s.repository.readableDatabase.version)
                assertEquals(listOf("genshin","zzz"),a.gameIds)
                assertFalse(a.enabled)
                assertEquals("success",s.repository.records("2026-11-01").single{it.accountId==id}.status)
                assertEquals("synthetic-only",secrets.read(id).getString("cookie"))
                assertEquals(2,secrets.read(id).getJSONArray("games").length())
                // Fixture only. Real user data never enters this harness.
                s.repository.delete(id);secrets.delete(id)
            }
            else -> error("Unknown installation upgrade scenario")
        }
    }
}
