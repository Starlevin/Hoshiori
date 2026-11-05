# Hoshiori 0.7.9-preview.5：紧凑 Dock 与应用内升级

日期：2026-11-05。基于 preview.2 增量开发，发行源码 `0a03646846870edc70163fdbf65db1e2ff447de0`。

## 改动

| 项目 | preview.2 | preview.5 |
| --- | --- | --- |
| 默认 Dock 高度 | 72dp | 60dp，减少约 17% |
| 横屏及宽屏最大宽度 | 520dp | 400dp |
| 两侧最小边距 / 底部悬浮间距 | 18dp / 12dp | 22dp / 8dp |
| 选中胶囊上下留白 | 4dp | 6dp |
| 按压胶囊缩放目标 | 约 1.39 倍 | 1.18 倍 |
| 图标容器 / 标签字号 | 23dp / 12sp | 22dp / 11sp |
| 标准玻璃横向拖动入口 | 当前选中的胶囊 | 整个 Dock |

默认尺寸下每项仍有 48dp 高的点击区域。字体放大时 Dock 连续增高，200% 字号时为 88dp，取消原先 130% 附近的高度突变。列表继续采用实测 Dock 占位和系统 Insets 计算底部间距。

拖动期间直接使用手指位置更新胶囊，松手后再通过弹簧吸附，并提交一次页面切换。纵向移动不抢占横向导航；手势取消回到原选项。新手势会取消旧的回弹和按压动画任务，避免快速操作时被旧动画拉回。

四个主导航使用同一套 24×24 缓存矢量路径和 1.75 描边：圆角房屋、单张账号卡、勾选记录、调节滑杆。清理了叠卡、装订环和齿轮细节，统一实际轮廓的视觉比例。应用启动图标与品牌插画沿用既有素材。

## 源码与兼容

- `LiquidDock.kt`、`UpstreamLiquidDock.kt`：尺寸、字号和现有降级路径。
- `upstream/LiquidBottomTabs.kt`、`DampedDragAnimation.kt`：全栏拖动、指针跟随、可中断的吸附。
- `HoshioriBrand.kt`：四个主导航图标。
- `DockMotionTest.kt`：跟随、边界、动画中断和字体缩放回归。

仍使用 Backdrop 1.0.0 / Capsule 2.1.1 的采样、折射和连续胶囊，不增加新的渲染库。修改处保留 Apache-2.0 署名，并更新 NOTICE。没有导入 FlClash 或 Nexio 应用代码。

minSdk 26，targetSdk 35，compileSdk 36。标准动态玻璃使用 Android 13+ 路径；低版本、省电、低内存、减少动态效果等条件继续使用现有降级。账号、数据库和签到适配器未修改。

## 应用内更新与覆盖安装

预览版恢复包名 `com.glasssign.app`，与正式版使用相同证书，应用名“星栞”。旧的独立 `.preview` 包仍保留为另一应用，数据不能跨包名自动合并。正式版、后续预览版均用递增 versionCode。

`HoshioriScreen → UpdateViewModel → UpdateReleaseService → ReleasePolicy` 在启动时限频检查：成功后间隔 24 小时，失败后冷却 10 分钟；升级应用后重新检查。正式版本查询 `/releases/latest`，预览版本查询发布列表，保留 `preview.N` 的比较顺序。限频期间可显示带时间的缓存结果，手动检查始终可用。304 会复用正文重新判定，不会把尚未安装的更新误报为最新。

`UpdateDownloader` 在私有 `cache/updates/` 下载，显示进度并支持取消与重试。临时文件按尝试隔离，取消后的旧请求不能删除新文件或恢复“可安装”状态。下载完成后核对大小、SHA-256、包名、versionCode 和完整签名者集合，再通过独立 `UpdateFileProvider` 交给系统安装器。下载、安装操作使用整块按钮，修正原先仅右侧热区可点击及文字对比度问题。GitHub 网页和夸克目录保留为外部备用入口。

没有增加渲染或下载依赖。权限沿用 `INTERNET` 与已有的 `REQUEST_INSTALL_PACKAGES`；未知来源授权与最终安装仍由 Android 确认。更新检查不携带游戏账号或社区授权。数据说明同步更新于 `DATA_HANDLING.md`。

## 定位与修复证据

按压截图曾出现只有 Dock、页面变黑的现象。单独删除局部 Clear 绘制不能解决；将可见页面与动画采样使用的图层隔离后，MuMu 按住与拖动时页面保持可见。回归测试使用真实 GPU 绘制和带滚动容器的页面检查中心像素。未据此推断所有芯片的 GPU 内部根因。

真实下载测试还定位到两个同类 FileProvider 被 Android 复用实例的问题：更新 authority 被交到插件 Provider，读取 URI 抛 SecurityException。更新改用独立组件类后，实际 URI 流读取与整文件哈希复核通过。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| Release APK、单元测试、lint | PASS |
| 单元与 Robolectric | PASS：46 个套件、503 项，0 failure / error / skipped |
| lint | PASS：0 error、64 warning、3 hint |
| GitHub CI | PASS：[42004276138](https://github.com/Starlevin/Hoshiori/actions/runs/42004276138) |
| MuMu / API 35 设备测试 | PASS：3 项，按压与拖动保持页面可见、真实 GitHub 下载及 URI 读取、按钮文字与边缘触摸各触发一次操作 |
| 原正式版覆盖 | PASS：0.7.8 / code 18 覆盖到验证用 Release / code 21；无卸载、无清数据 |
| 完整应用内升级 | PASS：code 21 检出 preview.5，应用内下载与校验，系统授权、确认安装，更新到 code 23；最终包未通过 adb install 安装 |
| 安装身份与外观保留 | PASS：UID 10018、首次安装时间 2026-11-02 22:32:30 保持不变，浅色设置跨升级保留 |
| 手动交互 | PASS：未选中项起拖、按住不提交、松手切换、取消回原页、纵向手势不切页、连续点击、200% 字号与横屏四项导航 |

研发中保留了失败记录：旧渲染测试的函数签名过时、Windows JVM 的 FileProvider 路径分隔差异、Provider 实例冲突、按钮热区与对比度；均按实际原因处理。一次 CI 的 Kotlin 编译器下载返回 504，最终发行提交的 CI 已通过。一次界面探针查找“夸克网盘”而实际标签为“夸克”，以及未退回设置首页就查找外观入口，属于探针路径问题，未为探针改动签到结果。

本轮模拟器原正式包为空账号，不能把覆盖安装结果说成已验证非空实号授权迁移。第三方真实登录与签到、物理手机、低端设备帧时间、TalkBack 全流程、Android 8–12 的本轮实机回归均为 NOT_RUN。没有发起真实游戏平台签到，也没有删除账号、历史或重置授权。

## 构建与发行

JDK 17、Gradle 8.14.3、Kotlin 2.2.21、AGP 8.13；minSdk 26 / targetSdk 35 / compileSdk 36。构建类型为签名 Release，versionCode `23`，versionName `0.7.9-preview.5`，无 debuggable 标志。

```powershell
$env:JAVA_HOME='C:/Program Files/Microsoft/jdk-17.0.19.10-hotspot'
./android/gradlew.bat -p android -g 'E:/Projects/GlassSign/GlassSign-v0.2/.gradle-home' --no-daemon --max-workers=2 :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease :app:assembleReleaseAndroidTest
./android/build-release.ps1 -SkipBuild
```

APK：`outputs/Hoshiori-v0.7.9-preview.5-release/hoshiori-v0.7.9-preview.5-release.apk`。

- APK SHA-256：`46a9549420265a1d952c9e52800bcb3f7b4b1c0155521e7f9d60a9ef4aa20f43`
- 证书 SHA-256：`6c4e3dcec3af2aa6c5ba4b69083f8c1ac56aeee1acdd60c2d57146725ef65369`
- [GitHub 预览版](https://github.com/Starlevin/Hoshiori/releases/tag/v0.7.9-preview.5)
- [夸克永久目录](https://pan.quark.cn/s/739482783865)，无提取码，按版本分为安装包、源码、报告与校验。

正式 latest 保持 v0.7.8。preview.4 产物保留，并注明已由 preview.5 替代。发行源码标签固定，后续报告与说明补充不覆盖旧 APK。
