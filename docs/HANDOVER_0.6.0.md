# Hoshiori 星栞 0.6.0 开发交接

日期：2026-11-02。由现有 GlassSign 增量开发。公开仓库名称为 `Starlevin/Hoshiori`。

## 产品与兼容边界

Android 纯本地签到，无自建服务器。应用显示名改为星栞，英文 Hoshiori。为覆盖升级保留 `com.glasssign.app`、debug `.debug`、SQLite 文件、SharedPreferences、Keystore 别名及 WorkManager 唯一任务名称。不能通过卸载、清数据或更换签名来执行迁移。

旧 APK、源码 ZIP、个人设备证据、原后端和开发任务书仍保留在本地目录。公开仓库仅纳入经过检查的 Android 源码、合成测试、公开文档和 CI；不含真实授权、手机截图、本机配置、构建缓存及签名私钥。

## 本次实现

1. 异环原生账号/短信登录：TaggedLoginClient → 官方账号接口 → 塔吉多授权交换 → 官方角色查询 → 用户确认。填写内容仅发送对应服务；不持久化密码、验证码或中间 SDK 登录令牌。界面始终使用 FLAG_SECURE，错误只返回安全的阶段/代码，不展示原始响应。验证码/风控不自动重试或绕过。
2. 登录一次选择多个游戏：新登录先查询同账号体系的游戏角色，确认页勾选实际读取到角色的游戏；共用一份加密授权。已经绑定的账号可使用「管理游戏」，只读查询后更新选择，不重新登录，也不因保存选择而自动触发签到。
3. 旧版迁移：SQLite schema 1/2/3 → 4；Account.gameIds 存储多个游戏；旧单游戏字段同步迁入。旧多游戏账号在 LocalServices 初始化时读取自身加密授权中的 games/gameIds，补齐元数据；缺省范围保留旧有游戏，不自动加入新崩坏3。迁移可重复执行，不改账号 ID、暂停状态和历史记录。已有密钥失效或授权缺失时保留账号并允许后续重新登录。
4. 崩坏3：GameCatalog 内部 ID `honkai3`，米游社 `bh3_cn`，活动 `e202306201626331`，`/event/luna/info` 与 `/event/luna/sign`。与其他米游社游戏共用授权；上线接口可能变动，尚未完成真实崩坏3账号验收。
5. 已签到：官方状态明确已签时返回 already；本机当天成功任务再次触发时改为 already 并说明来自本地记录，尝试次数不增加、不重复提交。结果未知的任务仍要求人工核对。游戏卡片排除社区任务，逐游戏汇总；success/already 均显示「已签到」。
6. 前端：原生 Compose 底部首页／绑定／记录三页。参考 FlClash 的主题功能组织，独立实现明暗、纯黑、主色、动态色、色彩风格、字体/字号等选项。表面层次和导航融合为静态效果，无实时模糊着色器。详见主题映射文档，不宣称直接移植其 Flutter 实现或完整视觉等同。

## 验证状态

- 最终源码：assembleDebug、testDebugUnitTest、assembleDebugAndroidTest、lintDebug 通过。
- JVM：49 项通过，0 失败。
- Android 专用空账号 AVD：43 项通过，2 项场景入口按条件跳过。两个被跳过入口不冒充通过。
- 额外覆盖安装场景：旧版 seed052、新版 verify060 各 1 项通过；0.5.2 → 0.6.0 覆盖安装保留首次安装时间、合成加密授权、账号 ID、暂停、游戏选择及历史记录。
- Lint：0 Error / Fatal，17 Warning、3 Information；包含同步持久化、废弃 API 等提示，未隐藏为零告警。
- UI：专用模拟器三页与浅色／深色／系统模式交互检查；未进行真机性能验收。
- 覆盖安装试验使用同版本运行代码的候选包，之后只规范化 APK 内许可证文本换行；最终包另行补跑本地回归。
- 新 APK SHA-256：`28301d6e4df5bcc28feeeb5179d23e4a32c58e55164fe82e4a36a9ddf0673d9b`。

真实账号的登录、角色绑定、签到三阶段应分别记录；以上自动化仅使用合成授权，不能替代四社区真实签到。此前用户报告米游社等可用、异环似乎已签到，保留为用户反馈。本次没有独立确认所有真实账号签到完成。

## 架构入口

- MainActivity / HomeViewModel：三页 UI、登录桥接、选择与确认。
- GameCatalog：游戏展示、账号体系、接口 ID 映射和范围裁剪。
- LocalServices / LocalAccountBindings：加密授权、自动迁移、独立暂存更新、确认/撤回及退休凭据清理。
- SignRepository：schema 4，账号与待确认绑定、按北京自然日去重的任务记录。
- LocalSignEngine：串行查询与执行、已签跳过、同账号选择读取。
- Providers / Protocol：四社区适配、官方 HTTPS 域名白名单、不跟随重定向、提交结果未知保护。
- SignScheduler：WorkManager 网络约束、重复点击合并、重启/包更新恢复计划。
- Appearance / Theme / HomeScreen：静态主题、当前页组合、缓存分组统计。

历史 CloudClient 与对应未使用设置函数保留兼容源码，但当前 MainActivity → HomeViewModel 不调用云端客户端。公开快照不含旧服务端。

## 构建与测试

JDK17、SDK35；`android/gradlew --no-daemon --max-workers=2 :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug`。

完整 Android 集成测试只应在专用空账号设备运行。`InstallationUpgradeTest` 需显式 scenario，分两次安装执行：旧 0.5.2 seed052 → 覆盖安装 0.6.0 → verify060。测试只写合成账号，并验证首次安装时间、原加密授权、暂停状态、游戏选择和历史记录；不能在用户主手机运行。

## 尚需真实设备验证

- 四社区全部真实账号/角色的签到；崩坏3真实角色和活动接口。
- 异环新版登录后的真实角色与官方签到状态闭环。用户反馈“似乎已签到”仅作为反馈记录，不能提升为独立验收通过。
- 真机覆盖安装后账户显示和实际使用；目前设备未连接。
- 不同厂商锁屏、长时间后台、电池限制和重启后的定时签到。WorkManager 不保证精确到点。
- 模拟器 UI 检查不能证明真机卡顿已彻底消除；需要在用户手机进行交互与性能采样。

## 下一位开发者注意

保持本地执行，不引入云服务器。不要修改存储命名空间或自动合并不同账号，不读取/导出用户密码和授权。处理不确定的签到提交时先核对官方状态，禁止盲目重发。发布签名需保持连续；GitHub Actions 默认 debug 签名与本地开发签名不同，CI APK 不能当作原手机的覆盖更新。
