# Hoshiori 0.7.9-preview.2：默认黑白、设置分类与图标重绘

日期：2026-11-05。基于前端预览分支 `8cc4fc5` 增量修改，正式通道仍为 v0.7.8。

## 本轮范围与实际行为

用户的重点是整体风格与分类。现在默认使用经典黑白：深色页面为 `#000000`，卡片使用中性灰；浅色页面为 `#FFFFFF`，卡片使用白灰。页面默认渐变同时移除色相，避免主题配色已经改灰、背景仍然发蓝。

深蓝、柔紫、自选颜色和系统动态取色都是主动选择的选项。经典黑白下选用色板、HEX 或 RGB 会切换到彩色风格并保存；未启用的蓝色种子不再显示成“当前已选”。预设缩略图直接使用实际主题配色，深色模式下不会预览成浅蓝纸面。错误等业务状态仍保留语义颜色。

设置首页按三组收拢入口，详细内容进入相应子页：

| 分组 | 入口 |
| --- | --- |
| 使用偏好 | 外观与主题、自动签到 |
| 运行与扩展 | 运行与权限、插件与扩展 |
| 应用与数据 | 数据与隐私、版本与更新、关于星栞 |

主题页面分为主题预设、主题色、页面背景、玻璃与动效、字体与排版。固定顶栏提供返回与关闭；系统返回从子页回总览，再回设置。恢复默认外观会先确认，只写外观字段，保留背景、账号、授权与记录。全局清除授权移入数据与隐私，并单独确认；本轮验收只取消该确认，没有清除授权。

四个底部导航和设置分类共 22 个矢量图标重新绘制，统一 24×24 画布、1.75 描边与圆角端点，使用缓存的 ImageVector 并从主题取色。应用启动图标与既有品牌插画沿用当前版本，本轮没有宣称重绘它们。主题预设使用等宽双列，选中不再额外增加卡片高度。

四个主导航及绑定顺序不变。未修改平台适配器、账号数据库 schema、签到结果模型或提交逻辑；本轮没有真实平台签到请求。

## 外观升级兼容

`AppearanceController` 通过 `theme_catalog_version=1` 执行一次兼容迁移。旧版缺省外观，或 `vivid + #4263C7 + 未启用动态色`，切到经典黑白。其他颜色风格、自选种子、动态取色、系统明暗偏好、字体与背景保留。

旧版没有记录“是否手动选择过默认蓝”的字段，因此无法区分用户主动选择同一组默认值的情况；符合上述组合时会迁一次黑白，之后主动选蓝可持续保存，不会每次启动重置。背景与主题共用 `appearance_v1`，使用不同的 key；迁移不清空整个偏好文件。

预览包使用 `com.glasssign.app.preview`，可以覆盖旧 `preview.1` 并保留它的本机数据。正式包 `com.glasssign.app` 与旧 `.debug` 包各自独立，不自动共享授权。本轮模拟器预览中为 0 个账号，不能据此宣称真实账号迁移已验证。

## 修改位置

| 文件 | 作用 |
| --- | --- |
| `ui/Appearance.kt`、`ui/NeutralTheme.kt` | 中性配色、一次性偏好迁移、外观入口 |
| `ui/background/HoshioriBackground.kt` | 黑白主题使用无色相页面背景，保留自选图 |
| `ui/SettingsHub.kt`、`ui/HoshioriScreen.kt` | 设置分组、子页导航、返回定位、隐私确认 |
| `ui/Dialogs.kt` | 每日计划弹窗只保留自动签到选项 |
| `ui/AppearanceNavigation.kt` | 外观总览、固定顶栏与逐级返回 |
| `ui/AppearancePresetPages.kt` | 主题预设、实际配色缩略图、色板、HEX、RGB、动态色 |
| `ui/AppearanceMaterialPages.kt`、`ui/AppearanceCommon.kt` | 背景、玻璃、动效、字体及共用偏好组件 |
| `ui/HoshioriBrand.kt` | 导航和设置矢量图标 |
| `local/NeutralThemeTest.kt`、`local/AppearanceMigrationTest.kt` | 配色、对比度、旧偏好保留回归 |
| `local/AppearanceSchemeV070Test.kt` | 彩色主题测试显式使用 vivid，保留原覆盖目的 |

本轮将主题页主体与图标重绘交给 DSH；Codex 审查返回路径、编译问题、黑白色号反馈、卡片宽度与状态，完成接入、自动化验证、设备检查和发布。

## 构建与自动化结果

最终源代码执行：

```powershell
$env:JAVA_HOME='C:/Program Files/Microsoft/jdk-17.0.19.10-hotspot'
./android/gradlew.bat -p android -g 'E:/Projects/GlassSign/GlassSign-v0.2/.gradle-home' --no-daemon --max-workers=2 :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
./android/build-release.ps1 -SkipBuild
```

| 项目 | 结果 |
| --- | --- |
| Release assemble / tests / lint | PASS，最终组合命令退出 0 |
| 单元与 Robolectric 测试 | PASS，35 个 suite、410 项、0 failure / error / skipped |
| lint | PASS，0 error、61 warning、2 hint；不宣称无警告 |
| APK 签名与 zipalign | PASS，使用既有固定证书 |
| MuMu 覆盖安装 | PASS，`adb install -r`，无卸载或清数据 |

新增测试覆盖默认黑白、系统蓝色壁纸不污染中性主题、可选蓝色、OLED、文字与语义错误颜色的 AA 对比度、全新安装、旧默认迁移、自选颜色与动态色保留、背景 key 不被删除，以及后续选择不会被反复迁移。原有数据库与签到可靠性测试继续执行，但它们是本地合成夹具验证。

## 本机界面验收与边界

MuMu `game-1`，Android 15 / API 35。使用签名 Release，默认字号；临时 720×1280 竖屏检查，并检查横屏恢复。原显示、旋转与字体配置在测试后恢复。

已执行并通过的界面流程：预览覆盖启动、设置分组入口、外观五分类、黑白与深蓝切换、浅色模式、系统逐级返回、自动签到计划编辑入口、运行权限分类、双更新通道、隐私清除确认取消、HEX 实际保存、自定义背景入口、恢复外观保留内置背景，以及动效开关分类。最终截图和逐项日志随发布包提供。

一次 HEX 检查脚本最初少退一级：输入法先消费返回键，脚本仍停在外观总览而去断言设置首页。检查记录保留为 FAIL；实际总览已显示保存的 `#24795D`，补齐返回后同项 PASS。这是验收脚本路径问题，未伪造通过或为它修改签到结果。

另一次开关探针最初读取到命名子节点，误以为开关未保存；实际 Switch 父节点已经 checked=true。改为读取父节点后，开关启用和恢复原值均 PASS，未为该探针改动产品状态。

列表顶部安全区移到滚动容器，由 safeDrawing 的顶部与水平 Insets 统一提供，并裁剪滚出边界的内容，避免滚动文字进入系统状态栏；取消原页头的重复状态栏留白。底部安全距离仍使用已有实际 Dock 测量值和 Insets；设置末项可完整滚动到 Dock 上方。当前初始视口允许内容在悬浮材质后经过，用户向下滚动即可完整查看，不将它描述为底部永远没有内容。

以下仍为 NOT_RUN：当前真机的全流程回归、所有第三方实号登录与签到、跨设备授权迁移、低端真机帧时间、TalkBack 完整测试、自选照片实际导入。模拟器检查不能证明所有设备流畅或与 FlClash 完全一致。

## 包信息与分发

- 版本 `0.7.9-preview.2`，versionCode `20`，签名 Release，无 `application-debuggable`。
- 包名 `com.glasssign.app.preview`，应用名“星栞 · 前端预览”。
- minSdk 26，targetSdk 35，compileSdk 36。原生 Jetpack Compose，未增加 UI 技术栈或新的渲染依赖。
- 原有 Backdrop / Capsule 依赖及 Apache-2.0 上游声明沿用；本轮图标为本项目实现，没有移植 Flutter 或 Nexio 应用源码。
- APK：`outputs/Hoshiori-v0.7.9-preview.2-release/hoshiori-v0.7.9-preview.2-release.apk`。
- SHA-256：`3a3045687de9b22fddd0433c70b9f6a350a53d4c2ec66c2e96dd585c88aa95c3`。
- 证书 SHA-256：`6c4e3dcec3af2aa6c5ba4b69083f8c1ac56aeee1acdd60c2d57146725ef65369`。
- [GitHub 预览发布](https://github.com/Starlevin/Hoshiori/releases/tag/v0.7.9-preview.2)。正式 latest 仍为 v0.7.8。
- [夸克永久发布目录](https://pan.quark.cn/s/739482783865)，按版本归档安装包、源码、报告与校验；无提取码。

本轮继续维护预览分支，不把尚未完成真机回归的风格改动直接替换正式通道。后续接手应先查看本报告、随包 BUILDINFO 和实际截图，再处理新的具体问题，不重置用户数据来制造干净状态。
