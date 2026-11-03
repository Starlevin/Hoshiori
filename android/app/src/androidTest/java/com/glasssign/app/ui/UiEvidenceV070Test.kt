package com.glasssign.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.glasssign.app.data.RoleChoice
import com.glasssign.app.local.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Seeds synthetic screenshots only. These records are never presented as real platform evidence. */
class UiEvidenceV070Test {
    @Test fun scenario() {
        val mode=InstrumentationRegistry.getArguments().getString("evidence070")
        assumeTrue("Explicit screenshot harness only",mode!=null)
        check(android.os.Build.HARDWARE in listOf("ranchu","goldfish"))
        val c=ApplicationProvider.getApplicationContext<Context>();val s=LocalServices.get(c)
        val ids=listOf("v070-ui-main","v070-ui-alt","v070-ui-skland")
        if(mode=="cleanup") {ids.forEach{s.repository.delete(it)};return}
        check(mode=="seed")
        check(s.repository.accounts().isEmpty()){ "Screenshot fixture requires an empty dedicated installation" }
        val choices=listOf(
            listOf(RoleChoice("genshin|fixture-main|server","genshin","原神 · 旅行者"),RoleChoice("zzz|fixture-main|server","zzz","绝区零 · 绳匠")),
            listOf(RoleChoice("genshin|fixture-alt|server","genshin","原神 · 小号角色")),
            listOf(RoleChoice("arknights|fixture-main|server","arknights","明日方舟 · 博士"),RoleChoice("endfield|fixture-main|server","endfield","终末地 · 管理员"))
        )
        ids.forEachIndexed{index,id->
            s.repository.add(if(index==2)"skland" else "miyoushe",listOf("主号 · 合成验证","小号 · 合成验证","日常 · 合成验证")[index],id,gameIds=choices[index].map{it.gameId})
            s.repository.identity(id,"fixture-${index}");s.repository.auth(id,if(index==1)"needs_action" else "valid","合成验收数据，不是真实授权或签到")
            s.repository.storeRoleChoices(id,choices[index]);s.repository.setBindings(id,choices[index],true)
            choices[index].forEachIndexed{j,r->
                val target=SignTarget(r.targetId,r.name);s.repository.claim(SignRepository.day(),id,target)
                s.repository.finish(SignRepository.day(),id,target,if(index==0&&j==0)SignResult("success","合成官方状态已确认") else if(index==0)SignResult("already","合成官方响应已签到") else if(index==1)SignResult("needs_action","合成授权过期，请重新授权") else if(j==0)SignResult("uncertain","合成提交结果不确定，未重发") else SignResult("failed","合成明确失败，可安全重试"))
            }
        }
    }
}
