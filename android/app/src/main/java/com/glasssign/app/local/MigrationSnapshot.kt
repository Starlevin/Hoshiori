package com.glasssign.app.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Private logical checkpoint before schema changes; encrypted credentials stay in their original store. */
object Bootstrap {@Volatile var failed=false}
object MigrationSnapshot {
    /** Retry only quarantines an invalid checkpoint; never deletes or replaces the user's DB. */
    fun quarantineInvalid(context:Context,name:String="local_sign_v4.db",version:Int=4) {
        val checkpoint=File(context.noBackupFilesDir,"schema-checkpoints/${File(name).name}.v$version.json")
        if(checkpoint.exists() && runCatching{validate(checkpoint,version)}.isFailure) {
            check(checkpoint.renameTo(File(checkpoint.parentFile,checkpoint.name+".invalid-"+System.currentTimeMillis())))
        }
    }
    private fun validate(file:File,version:Int) {
        val root=JSONObject(file.readText())
        check(root.getInt("schema")==version)
        val tables=root.getJSONObject("tables");tables.getJSONArray("accounts");tables.getJSONArray("records")
    }
    fun capture(context:Context,name:String,db:SQLiteDatabase,version:Int) {
        val folder=File(context.noBackupFilesDir,"schema-checkpoints").apply{check(exists()||mkdirs())}
        val destination=File(folder,"${File(name).name}.v$version.json")
        if(destination.exists()) {validate(destination,version);return}
        val snapshot=JSONObject().put("schema",version).put("tables",JSONObject())
        for(table in listOf("accounts","records","pending_bindings","retired_credentials","sign_guards","game_bindings","discovered_roles")) {
            val exists=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use{it.moveToFirst()}
            if(!exists)continue
            val rows=JSONArray()
            db.rawQuery("SELECT * FROM $table",null).use{c->while(c.moveToNext()) {
                val row=JSONObject();c.columnNames.forEachIndexed{i,key->row.put(key,if(c.isNull(i))JSONObject.NULL else c.getString(i))};rows.put(row)
            }}
            snapshot.getJSONObject("tables").put(table,rows)
        }
        val temporary=File(folder,destination.name+".tmp")
        temporary.outputStream().use { stream->stream.write(snapshot.toString().toByteArray());stream.fd.sync() }
        check(temporary.renameTo(destination)){"无法保存升级检查点；已停止迁移以保留原数据"}
    }
}
