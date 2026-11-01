# Hoshiori 0.6.0 前端与 FlClash 主题功能映射

日期：2026-11-02。受主代理委派，仅修改原生 Compose UI 与主题文件，保留首页／绑定／记录三页以及绑定在第二页。未操作真机、模拟器、账号或凭据。

## 参考范围与许可证

正式上游：[chen08209/FlClash](https://github.com/chen08209/FlClash)。核对 main 观察到的提交 `7fd417fb9a9a277c6a9339a8ac87f907df7c25f7`（2026-10-24T05:51:38Z）。正式项目许可证为 GPL-3.0。

本轮没有复制其 Dart 实现、Material 颜色算法、主题资源或代理代码，采用原生 Compose 独立实现对应的功能模式与分区。不能表述为原 Flutter 前端直接移植或像素级复刻。

主要核对源文件：

- [ThemeProps 配置](https://github.com/chen08209/FlClash/blob/7fd417fb9a9a277c6a9339a8ac87f907df7c25f7/lib/models/config.dart)
- [ColorStyle 定义](https://github.com/chen08209/FlClash/blob/7fd417fb9a9a277c6a9339a8ac87f907df7c25f7/lib/ui/color.dart)
- [LiquidGlassMode 定义](https://github.com/chen08209/FlClash/blob/7fd417fb9a9a277c6a9339a8ac87f907df7c25f7/lib/enum/enum.dart)
- [许可证](https://github.com/chen08209/FlClash/blob/7fd417fb9a9a277c6a9339a8ac87f907df7c25f7/LICENSE)

## 实现映射

| 上游主题能力 | 星栞（Hoshiori）对应实现 | 说明 |
| --- | --- | --- |
| ThemeMode.system / light / dark | 跟随系统／浅色／深色 | 保存在独立 appearance_v1 偏好中，立即生效 |
| pureBlack | 纯黑背景 | 仅深色模式采用 OLED 黑背景，浅色时保留偏好供切换使用 |
| primaryColor | 7 个预设主色＋自定义 #RRGGBB | 对自定义主色做背景对比保护 |
| primaryColors | 最近自定义颜色列表 | 去重保留最近 8 个颜色，可点击重选 |
| 系统动态配色 | Android 12+ 壁纸动态取色 | 原生 Material 动态主色／辅助色，低版本不展示此选项 |
| vivid / tinted / soft / monochrome | 鲜明／色调／柔和／单色 | 独立的原生颜色角色生成，非上游 HCT 算法移植；单色模式采用中性色 |
| LiquidGlass none / standard / ultra | 表面层次：简洁／标准／增强 | 明确标注为静态表面、边框层次，不启用实时模糊或液态玻璃着色器 |
| sidebarBlur | 导航色彩融合 | 本项目手机界面没有侧栏，以底部导航的静态淡主题色融合对应视觉意图；不宣称侧栏模糊已移植 |
| textScale.enable / scale | 自定义文字缩放，85%–140% | 关闭时跟随系统字号，开启时叠加用户缩放 |
| systemFontFamily | 系统／无衬线／衬线／等宽 | 使用 Android 自带字体族，不做系统外字体文件导入 |

每个选项都有保存、读取和实际颜色、密度、字体或表面角色接入。跟随系统模式观察系统明暗变化；设置通过 SharedPreferences 监听更新，未增加每帧轮询。

## 界面与性能

- 首页改为紧凑的签到概览、单个签到入口、每日计划与两个快捷模块。
- 第二页使用原生分组游戏卡片，已绑定账号使用紧凑卡片，重新登录与删除收进菜单，避免一排四个按钮挤压。
- 第三页独立显示当天任务结果与历史入口。
- 页头提供“外观主题”和“本地计划设置”两个入口；计划设置内也可打开主题弹窗。
- 使用 Material Surface、边框和文字角色；无全屏 blur、Canvas 背景、shader、常驻光效、无限动画或层层大阴影。
- 只组合当前页面；三页各自保留滚动位置；记录按账号分组和统计使用 remember，避免普通重组时重复扫描。
- 提示采用浮动 Snackbar，不插入列表，不挤动签到按钮。
- 字体与旧 White/Muted/Cyan 等 UI 色名都映射当前主题；输入框改用 surfaceContainerLow / outlineVariant；保留 GlassDialog 原有 secure 参数，未削弱敏感界面保护。
- 在主题 SideEffect 中同步状态栏、导航栏明暗图标，兼容 MainActivity 原先固定深色系统栏的问题。

## 实际修改

新增 `ui/Appearance.kt`；修改 `ui/Theme.kt`、`ui/Glass.kt`、`ui/HomeScreen.kt`、`ui/Dialogs.kt`。未修改 MainActivity、HomeViewModel、登录类、ProviderAdapter、数据库、Manifest 或版本号。主代理另外负责 LoginFlow 中选择网格与输入框的角色颜色衔接。

## 验证记录与建议

按主代理许可，仅运行过 `:app:compileDebugKotlin --no-daemon --max-workers=1`，结果 BUILD SUCCESSFUL，日志 `docs/evidence/tagged-login-20261102/frontend-compile-01.txt`。首次集成发现旧颜色帮助函数需要 @Composable、GlassDialog 的 secure 参数与 SectionHeader 类型声明不兼容，均已修复；未绕过编译检查。

完整单测、lint、APK、真机安装和截图由主代理统一进行，不将编译成功表述为真机主题验收通过。

建议主题 QA：

1. 首页打开“外观主题”，依次切浅色／深色／跟随系统，确认文字、系统栏图标、导航和输入框对比。
2. 深色中开关纯黑，确认页面背景变化；浅色时该选项只保存偏好。
3. 遍历鲜明／色调／柔和／单色四模式；单色会忽略主色色相，切回其他风格再验证主色。
4. 验证七种预设、自定义色输入校验、最近颜色重选、Android 12+ 动态取色与重启持久化。
5. 遍历简洁／标准／增强层次和导航色彩融合，确认有静态视觉变化且空闲无持续重绘。
6. 字号 85%／100%／140%、四字体族检查文字、对话框滚动和按钮可达性。
7. 三页切换保留各自位置；登录、角色确认、账号操作、签到任务状态与浮动提示仍可正常使用。
8. 真机与 AVD 的帧统计、PSS 另行采样，不以减少装饰直接宣称耗电或帧率改善百分比。


## 后续多游戏与品牌接入（2026-11-02）

用户最终命名中文“星栞”、英文“Hoshiori”。本轮只改 UI 对外文案，没有改 package、数据库、Keystore 或偏好键，以保留覆盖升级兼容。

登录前选择入口游戏，登录后 `PendingLoginDialog` 展示同账号体系的多游戏复选框和选中数量；可取消全部但确认禁用，列表不会因 `ready=false` 消失。已授权账号用同一对话框管理游戏，明确“使用现有授权，无需重新登录”。角色清单完整展示，并明确不等同全选；上方复选决定执行范围。

HomeScreen 增加 `onManage(Account)` 回调，与“重新登录”分开；绑定卡片标题、游戏计数使用 `Account.gameIds` 并兼容旧 `gameId`。游戏卡片匹配当前真实游戏任务显示已签到、部分已签到、待签到和需处理，不把社区签到当作游戏签到。GameCatalog 新增崩坏3后 UI 自动带入，内部 id 为 honkai3。`statusLabel` 中 success/already 均显示“已签到”，核心状态值不变。

最小核心接入：MainActivity 传 `HomeScreen.onManage`、`PendingLoginDialog.onGameSelection`；核心模型补齐 `Account.gameIds` 与 `PendingLogin.availableGameIds/selectedGameIds/existingAccount`。这批增量 UI 没有独立编译，等待主代理字段与回调同步后统一构建；前述编译通过只对应此前单游戏主题检查点。


## 最终集成验证

主代理已完成字段/回调与品牌接入，0.6.0 最终完整构建、49 项 JVM 测试、Lint 及专用 AVD 本地集成回归通过（43 passed / 2 条件 skipped）。另行完成两次 APK 覆盖升级场景。此处与上文早期主题编译检查点区分；详细范围及真机限制见 HANDOVER_0.6.0.md。
