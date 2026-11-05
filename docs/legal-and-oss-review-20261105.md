# 星栞法律与开源初审

日期：2026-11-05。对象：`fcc1daf` 及其签名 v0.7.9-preview.2 APK；正式通道仍为 v0.7.8。审查包含受追踪源码、APK 许可文件、上游许可证及官方法规；未读取用户数据库或凭据，未做真实登录、签到或兑换。本报告是技术与合规初审，不是律师出具的法律意见，也不证明零风险。

## 结论

仓库已有 MIT、第三方声明、移植文件署名和部分随 APK 分发的许可文本，不能说完全没写。但目前也不能作为“开源与对外发行合规已经完成”的依据。优先风险是平台授权与协议、隐私说明及数据保留，其次是完整许可清单、外部插件许可、素材与发行条件。

## 关键风险与已确认事实

| 项目 | 已确认事实 / 风险 | 处置 |
| --- | --- | --- |
| 平台协议与接口授权 | 本机官方页面登录后读取会话，并经适配器调用接口；仓库没有相关平台给予此工具自动签到或批量兑换许可的证据 | 用户同意处理本人账号，不等于运营方授予接口用途许可。逐平台核对条款；避免宣称官方合作、保证不封号或接口永远有效 |
| 隐私与保留范围 | 授权加密，但账号与记录在私有 SQLite；删除账号/记录不清理升级检查点；记录与提交保护没有统一 TTL | 已补[数据与权限实况](../DATA_HANDLING.md)。检查点过期、删除联动、最短必要保留期及正式政策仍待整改 |
| 代码许可 | 自有代码 MIT；实际移植的 Backdrop 为 Apache-2.0，有头部、修改说明与随包全文 | 保留第三方许可及署名；MIT 不覆盖第三方服务、商标或素材权利。完整运行时传递依赖清单尚未完成 |
| 领签插件 | 仓库与发布 APK 可公开访问，但未找到其自身标准许可证文件；当前宿主只链接、核验并启动独立 APP | 不推定为 MIT/GPL，不把上游源码或 APK 纳入星栞重打包；由权利人补充许可并核对参考来源。未修改领签仓库 |
| 素材与商标 | 插画/壁纸的生成提示词已有记录，矢量图标有项目实现；尚未进行商标检索或第三方相似性法律评估 | 保留来源记录，不把“AI 生成”当成排他权或不侵权保证；游戏名用于识别平台，不表示背书 |
| 发行与备案 | 目前公开通过 GitHub、夸克提供 APK；没有自建签到服务器 | 不据“免费、开源、本地执行”推定豁免 APP 备案或发行规则。实际是否构成相关互联网信息服务，需向发行渠道或所在地通信管理部门核对 |

平台协议风险举例：[绝区零官方游戏使用许可及服务协议](https://webstatic.mihoyo.com/nap/event/puzzle/pz_96sslqhR_A/index.html?game_biz=nap_cn)包含第三方软件、异常使用、干扰服务等限制。其条款如何适用于本项目需要结合具体行为判断；不能把协议风险直接等同于已经构成犯罪或必然封号，也不能把登录成功当作已经取得运营方许可。

[2025 年修订反不正当竞争法](https://www.cnipa.gov.cn/art/2026/5/20/art_104_206437.html)涉及网络服务、技术管理措施、数据权益与混淆行为；未来扩大运营、自动抓取与批量调用需重新评估。该版本没有实施绕过技术管理措施的实验。

[个人信息保护法](https://www.cac.gov.cn/2021-08/20/c_1631050028355286.htm)涉及处理依据、告知、安全与删除等规则。无开发者云服务器有助减少集中收集，但适用主体及责任仍要按真实运营方式判断；现有界面零散说明不能替代完整政策。

[工信部 APP 备案通知](https://www.miit.gov.cn/zwgk/zcwj/wjfb/tz/art/2023/art_920db564162e4312916a01bed6540ad8.html)针对境内从事互联网信息服务的 APP 主办者。这里记录“适用性待确认”，没有虚构备案编号或断言当前项目已经违法。

## 许可证实际使用关系

| 项目 / 数据 | 核对的许可与来源 | 当前项目关系 |
| --- | --- | --- |
| Hoshiori 自有部分 | [MIT](../LICENSE) | 保留既有协议，不擅自更换 |
| Backdrop catalog / 历史光学改编 | [固定提交 LICENSE](https://github.com/Kyant0/AndroidLiquidGlass/blob/eada9a8600142c1bd6a5c5fc143d06f599cdfaef/LICENSE)，Apache-2.0 | 实际依赖与改编；五个上游文件保留头部，修改内容有说明 |
| Capsule 2.1.1 | [公开 POM](https://repo.maven.apache.org/maven2/io/github/kyant0/capsule/2.1.1/capsule-2.1.1.pom)，Apache-2.0 | 实际依赖；作者及许可在 NOTICE-backdrop 中，共用随包 Apache 全文；共用文本不等于许可缺失 |
| NTE-Auto-Sign 参考实现 | [固定来源](https://github.com/Candy-QAQ/NTE-Auto-Sign/tree/38d77e1f7dc784f2baeb49c41cd4352deadf2b24)，MIT | 有固定提交、版权人和随包 MIT 文本 |
| OkHttp / Okio 与 Public Suffix List | 代码 Apache-2.0；[PSL](https://publicsuffix.org/list/public_suffix_list.dat) 数据 [MPL-2.0](https://mozilla.org/MPL/2.0/) | APK 自带 PSL NOTICE 和来源/许可链接，已在根声明补列；不能把数据许可误标成 Apache |
| Miuix | [上游 LICENSE](https://github.com/YuKongA/miuix/blob/main/LICENSE)，Apache-2.0 | 当前 Gradle 无依赖、无该库 import；不是 GPL |
| FlClash | [上游 LICENSE](https://github.com/chen08209/FlClash/blob/main/LICENSE)，GPLv3 | 当前审查未发现其源码、Dart 或资源被纳入；功能与风格参考不自动等于代码衍生许可结论 |
| NexioSchedule | [上游 LICENSE](https://github.com/HaoZai000/NexioSchedule/blob/master/LICENSE)，AGPLv3 | 当前实际玻璃移植来自 Kyant Apache 上游；未发现 Nexio 应用源码纳入，不能因此保证完整历史来源永远无争议 |
| LingQian | [公开仓库](https://github.com/Starlevin/LingQian-Android)，自身许可未声明 | 独立外部应用，不能从 README“参考 MIT 项目”推导其自身许可 |

[Apache-2.0 第 4 条](https://www.apache.org/licenses/LICENSE-2.0)要求分发时附许可、保留相关声明并标注修改；第 6 条不授予一般商标使用权。将 GPL/AGPL 的具体源码或资源真正纳入时，须另行分析其分发与对应源码义务；翻译成 Kotlin 本身不会消除这些义务。

## APK 与发行证据

- APK 中确认有 `assets/NOTICE-backdrop.txt`、`assets/licenses/backdrop-apache-2.0.txt`、`assets/NOTICE-NTE-Auto-Sign.txt`。
- 还保留 `okhttp3/internal/publicsuffix/NOTICE`，明确指向 PSL 来源和 MPL-2.0。
- 当前 APK 没有可在应用内打开的统一开源许可/完整隐私政策入口，自有 MIT 全文也未放入 assets；需补充可发现性与发行材料覆盖。
- `META-INF/{AL2.0,LGPL2.1}` 等打包排除规则不等于实际使用了 LGPL，也不等于所有许可都已检查；应按实际运行依赖逐项盘点。
- 本地自动化与 CI 通过只证明相应构建和功能测试通过，不是法律合规认证。
- 对 v0.7.9-preview.2 源码包的 199 个文件进行了核对：原手工 zip 有 196 个文本文件的 Windows CRLF 差异，归一化后无缺失或无法解释的差异。另提供 `source-lf.zip`，其 199 个文件均与 `fcc1daf` 的 Git blob 逐字节一致，便于跨平台构建；原产物和校验值保留。
- 三份 APK assets 许可/声明与本地源码字节一致，PSL NOTICE 仍在包中。详细证据随 `COMPLIANCE_VERIFICATION.json` 分发，文件一致性不代表全部法律义务已满足。

## 该版本已补与仍待完成

已补：README 中的第三方身份与许可边界、[第三方声明](../THIRD_PARTY_NOTICES.md)中的 PSL/MPL、领签许可状态和素材来源，以及[数据处理实况](../DATA_HANDLING.md)。未改动既有 MIT，不改变已发布 APK，不删除用户数据。

仍待完成：检查点与提交保护的必要保留和删除机制；应用内政策与开源入口；完整运行依赖许可台账；运营主体及私密联系方式；领签权利人许可；素材与名称的进一步核对；发行渠道/备案适用性确认。未来加入自动兑换前应重新审查用途、授权和平台条款。

这些待办不能仅用“仅供学习”或免责条款替代。正式扩大公开推广、收费或上架前，建议由熟悉游戏与数据合规的律师复核。
