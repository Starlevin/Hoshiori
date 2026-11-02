package com.glasssign.app.data

data class RecordPresentation(val label:String,val group:String,val source:String)
fun present(record:CheckRecord):RecordPresentation {
    val unknown=record.status=="uncertain" || (record.status=="needs_action"&&listOf("不确定","未确认","中断").any{record.message.contains(it)})
    return when {
        unknown -> RecordPresentation("结果待确认","uncertain","先前提交结果未知；不会自动重发")
        record.status=="already"&&record.resultSource=="legacy_local" -> RecordPresentation("本机已有完成记录","submitted","旧版本地完成记录；原始官方确认来源未知")
        record.status=="already" -> RecordPresentation("已签到","success",if(record.message.contains("本机")||record.message.contains("保护"))"本机当天完成记录" else "官方明确已签到")
        record.status=="success" -> if(record.resultSource=="accepted" || record.message.contains("接受"))RecordPresentation("提交已接受","submitted","接口接受提交；尚无独立状态复核") else RecordPresentation("已签到","success","官方成功响应 / 状态确认")
        record.status=="running" -> RecordPresentation("正在签到","running","本地执行")
        record.status=="waiting_network" -> RecordPresentation("等待网络","attention","尚未成功发起查询，网络恢复后可重试")
        record.status=="needs_action" -> RecordPresentation("需要处理","attention","需要授权或用户操作")
        record.status=="failed" -> RecordPresentation("签到失败","attention","官方未确认成功，请查看原因")
        else -> RecordPresentation("待签到","pending","尚无完成记录")
    }
}
