# v0.7.7 审查与根因边界

日期：2026-11-03。基线提交72aaade，工作区接手时无未提交修改。本轮按签到可靠性、布局、回归验证依次实施，保持原生Compose与手机本地执行。

## 已确认的调用路径

`SignScheduler.run`以WorkManager唯一KEEP任务合并点击，`SignWorker`调用`LocalSignEngine.run`，进程级Mutex串行保护；每账号先claim discovery，再adapter.targets，然后逐目标签到。数据库records和sign_guards以业务日期、账号ID、目标ID为联合键，账号ID固定关联平台。未发现多个账号共用缓存键的直接证据，仍须回归测试。

塔吉多targets先token，若本次JSON没有_accessToken，则POST `/usercenter/api/refreshToken`；LocalCredentialStore.save刻意移除_accessToken，下一次点击会再次续期。即使目标已有当天成功或受理记录，旧run也会先续期和角色检查，再进入目标claim。因而“重新点签到却新增授权与角色检查失败”可以从调用顺序解释；具体HTTP402的服务端含义尚不能由此证明。

## 已确认问题

1. 通用`result(code=0)`返回`success`，说明却只承诺接口受理；`SignRepository.finish`用消息是否含“接受”推断result_source。协议、落库与展示的事实层级不一致。
2. Tagged游戏提交后没有二次状态查询；社区尚无已验证独立只读核验接口。受理不能自动提升成功。
3. OfficialTransport遇非2xx直接抛异常，未保留响应体中的安全业务码。历史402响应体已经丢弃，不能事后还原。
4. HTTP402走一般protocol错误，但Tagged业务码402被直接视为auth；不能把这两个层面的402混为一谈，也不能无证据判断为收费或授权过期。
5. 本地重复确认沿用原官方来源，却没有独立记录本轮使用的是本机缓存；需要保留原始证据时间与本轮核验方法，不能伪装为新官方查询。
6. 列表底部留白固定160dp+navigationBars，未使用Dock实际测量高度；长状态单行省略。筛选器虽可横滚，但bleed布局的宽度算法与说明不一致。
7. Dock外框与选中项边框叠加方向性高光，画面偏硬。现有采样、缓存、降级与冷启动修复应保留，不重写整套渲染。

## 仍待证据

- 2026-11-03 10:40:54真实塔吉多HTTP402究竟对应续期、参数、频率还是自定义风控；当前记录仅有POST/host/status，不应宣称真实根因已修复。
- 平台当前是否存在可靠且可归属到指定角色/日期的社区签到查询；未证实的接口不增加。
- 各厂商真机与低版本Android的帧率和长期后台表现；MuMu检查不能推广为全设备性能结论。

本轮以Mock响应与隔离数据库复现协议/幂等问题，不通过重复真实POST试探。数据库增量迁移保留账号、授权、历史及未知提交保护；不清理个人数据。

## 参考核验

2026-11-03查询仓库元数据：AndroidLiquidGlass为Apache-2.0，kmp分支，未归档；现有光学核心固定提交沿用v0.7.6。`Kyant0/Backdrop`独立仓库地址本次返回404；Backdrop模块位于AndroidLiquidGlass中，不能当作第二个已确认可用的依赖。

用户给定YuKongA/miuix为compose-miuix-ui/miuix的Apache-2.0分支；当前源码使用AGP9.4.1/Kotlin2.4.21/CMP1.12.2，与星栞AGP8.8.2/Kotlin2.0.21/Compose1.7.8工具链不同，本轮不直接引入其最新构建。FlClash（GPL-3.0、Flutter）和NexioSchedule（AGPL-3.0、Compose）继续仅作交互参考，不复制其应用源码。没有新增生产视觉依赖。

既有协议参考[NTE-Auto-Sign固定源码](https://github.com/Candy-QAQ/NTE-Auto-Sign/blob/38d77e1f7dc784f2baeb49c41cd4352deadf2b24/nte.py)中，续期同样使用`debug-uid:3`兼容头，并把续期HTTP402直接解释为refreshToken失效；这只是第三方分支，不能替代本次实际响应体证据。因此不凭头字段名称就删改签名或用户身份参数，也不把第三方解释当已复现根因。其游戏状态查询只有gameId，需额外确认返回状态可归属到当前角色。
