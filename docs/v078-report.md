# 星栞 v0.7.8：直接接入上游液态标签栏

日期：2026-11-03。versionCode 18，包名及正式签名不变。此文件记录该版本交接；具体构建、设备和上传证据随最终产物提供，不把尚未执行的验证记为通过。

## 来源与缺陷

用户提供的是 Nexio 课程表截图。核对其 `ui-shared/.../components/LiquidBottomTabs.kt`，滑块用 `rememberCombinedBackdrop(backdrop, tabsBackdrop)` 合成页面与图标层，再用 `lens`、高光、阴影和手势弹性渲染；不是只把背景变透明。星栞原先只保留部分光学 Shader，图标在独立前景层绘制，不能获得同样的整块透镜放大。

该版本不继续手调自制放大倍率。直接使用与该机制同源的 Apache-2.0 上游：

- `io.github.kyant0:backdrop:1.0.0` 正式 Maven 依赖。
- `io.github.kyant0:capsule:2.1.1`，上游 catalog 本来的连续曲率形状依赖。
- `Kyant0/AndroidLiquidGlass` tag `1.0.0`、固定提交 `eada9a8600142c1bd6a5c5fc143d06f599cdfaef` 的 catalog 标签栏及手势组件直接迁移，共五个文件，位于 `ui/upstream/`，保留原包名和逐文件版权来源。

没有复制 Nexio AGPL 应用代码，也没有迁移整个 miuix 或 Flutter 技术栈。上游库和迁移组件是实际运行路径，不是仅在声明中列为参考。完整许可证位于 APK 的 assets/licenses/backdrop-apache-2.0.txt，来源和修改列于 THIRD_PARTY_NOTICES.md、NOTICE-backdrop.txt。

## 最小适配

移植上游五个组件文件。仅 LiquidBottomTabs 增加主题颜色、深浅色与高度参数，64/56dp 原有高度关系改为外部高度及高度减8dp；效果和交互计算保持上游。UpstreamLiquidDock 适配器连接四页导航、Material3 色彩、字号高度及无障碍语义；页面使用上游 rememberLayerBackdrop/layerBackdrop 捕获。

标准、允许动效且 Android13+ 时使用上游组件；减少动效、辅助功能、省电、低内存、轻量/关闭或较老系统保留既有回退。冷启动先等待真实页面记录，避免对空 RenderNode 应用采样。底部留白仍使用整个悬浮层实测高度与 Navigation Bar Insets。

上游按住时捕获层使用1.2倍的图标文字缩放，滑块还会经过 lens 与78/56比例的弹性变形，释放后回到静止状态；这些叠加后的视觉倍率不是固定1.2。该行为区别于整条导航的布局缩放，点击目标仍由四个原槽位管理。

实际设备集成还定位并修复了适配问题：传入 getter 若随选中值重新创建，上游手势收集器可能继续观察旧状态对象；适配器使用 rememberUpdatedState 和稳定 getter，屏幕几何变化时重建手势几何。未改写上游透镜或手势算法。

构建工具链为满足直接依赖调整至 AGP8.13.0、Kotlin2.2.21、Compose BOM2025.11.01、compileSdk36；Gradle8.14.3、JDK17、minSdk26、targetSdk35保持。CI安装SDK36。没有新增云执行、账号权限或后台行为。

## 验证与交付

最终 Release assemble、398 项 JVM 测试、Lint 通过。测试失败/错误/跳过均0；Lint 0 Error、61 Warning、2 Hint，包含新工具链对既有代码和上游组件的检查，不声称零警告。本机 SDK local.properties 的冒号转义已修正，不提交机器路径。

MuMu Android15/API35 正式签名包覆盖安装成功，包内0账号，无真实签到请求。检查了720×1280手机尺寸和1920×1080横屏、200%系统字体、四页点击、连续快速点击、胶囊横向拖动与几何变化后的切换。临时网格背景用于观察真实采样，静止/按住/释放分别截图；按住时图标、文字与网格一起放大，释放恢复，扩大的胶囊未被旧外框裁切。随后恢复原无背景、字号和显示设置，保留其他应用及全部账号数据。默认背景下的按住/拖动录屏同附。

APK、SHA256、固定源码提交、签名证据见发布附件 BUILD_INFO.json、SIGNATURE.txt、SHA256SUMS.txt。截图及录屏是原生实际输出，不是静态设计稿。旧 Android 回退、低端真机长期帧率和电量该版本未实测，不宣称通过。v0.7.7 签到可靠性验收及真实 HTTP402 待证据的边界继续有效。

此前仅增加背景采样倍率的本地候选未发布到 GitHub 或夸克，保留在开发目录的 prototype 归档中；最终发布只能使用接入真实上游组件并通过验证的 APK。
