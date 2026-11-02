package com.glasssign.app.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class InstallationV070Test {
    @Test fun upgradeScenario() {
        val scenario=InstrumentationRegistry.getArguments().getString("upgrade070")
        assumeTrue("Explicit synthetic two-APK upgrade harness only",scenario!=null)
        check(android.os.Build.HARDWARE in listOf("ranchu","goldfish"))
        val c=ApplicationProvider.getApplicationContext<Context>();val id="hoshiori-synthetic-070-upgrade";val secrets=LocalCredentialStore(c)
        when(scenario) {
            "seed060" -> {
                assertTrue(c.packageManager.getPackageInfo(c.packageName,0).versionName!!.startsWith("0.6.0"))
                c.openOrCreateDatabase("local_sign_v4.db",Context.MODE_PRIVATE,null).use{db->
                    assertEquals(4,db.version)
                    db.rawQuery("SELECT COUNT(*) FROM accounts",null).use{q->q.moveToFirst();assertEquals(0,q.getInt(0))}
                    secrets.save(id,JSONObject("""{"cookie":"synthetic","stuid":"fixture-upgrade","games":["genshin","zzz"]}"""))
                    db.execSQL("INSERT INTO accounts VALUES(?,'miyoushe','合成升级账号',0,'date','valid','synthetic','','','genshin,zzz')",arrayOf(id))
                    db.execSQL("INSERT INTO records VALUES('2026-11-01',?,'zzz|fixture|server','合成升级记录','success','synthetic',1,'date',0)",arrayOf(id))
                }
            }
            "verify070" -> {
                assertTrue(c.packageManager.getPackageInfo(c.packageName,0).versionName!!.startsWith("0.7.0"))
                val s=LocalServices.get(c);val a=s.repository.accounts().single{it.id==id}
                assertEquals(5,s.repository.readableDatabase.version);assertFalse(a.enabled)
                assertEquals(listOf("genshin","zzz"),a.gameIds);assertEquals("fixture-upgrade",a.providerUserId)
                assertEquals("synthetic",secrets.read(id).getString("cookie"))
                assertEquals("success",s.repository.records("2026-11-01").single{it.accountId==id}.status)
                assertEquals("zzz",s.repository.bindings(id).single().gameId)
                assertTrue(java.io.File(c.noBackupFilesDir,"schema-checkpoints/local_sign_v4.db.v4.json").exists())
                s.repository.delete(id);secrets.delete(id)
            }
            else->error("Unknown scenario")
        }
    }
}
