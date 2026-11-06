# Liquid Glass 2.0

星栞的四栏 Dock 由实际路由驱动。完整模式使用页面作为 Haze 采样源。一颗水珠在底栏上随手指移动并拉伸，松手后弹到选中的页面并恢复尺寸。图标和文字在玻璃之上单独绘制。

## 源码起点

GitHub 的 `v0.7.9-preview.5` 指向 `0a03646846870edc70163fdbf65db1e2ff447de0`。该版已有 Backdrop 采样、AGSL 折射和滑动胶囊。它使用 Kotlin 2.2.21、AGP 8.13.0、Gradle 8.14.3、Compose UI 1.9.5、Material 3 1.4.0，以及 compile / min / target SDK 36 / 26 / 35。

重构从后续文档提交 `51c3b78615120dc2f612a6de324bcfd9b3564791` 上的 0.8.0 预览工作树接续。开工时共有 32 个已修改路径和 29 个未跟踪路径，包括此前的账号适配器管理、图标和工具链工作。这里的玻璃重构只使用已有的四栏路由、页面与本地实验室偏好。当前使用 Kotlin 2.4.21、AGP 9.5.0、Gradle 9.8.2、Compose UI 1.12.1、Material 3 1.4.0，以及 compile / min / target SDK 37 / 26 / 35。

## 渲染与模式

| 文件 | 职责 |
| --- | --- |
| `ui/HoshioriScreen.kt` | 页面捕获、玻璃输入、真实四栏选中态、弹层和键盘避让 |
| `ui/DockGlass.kt` | 外壳、单颗透镜的布局变形、弹簧位移及独立标签 |
| `ui/DockGlassPolicy.kt` | 设备能力、对比度、动画和透明度偏好 |
| `local/LabStore.kt`、`ui/LabsPage.kt` | 三档持久化、从旧开关读取、显示请求值与生效值 |
| `src/debug/…/GlassPrototypeActivity.kt` | 独立双滴、滚动标尺、0dp 对照及隔离 QA 数据 |

在设置的实验室中打开 Liquid Glass 2.0。FULL 需要 Android 13 及以上和硬件加速。LITE 是新装默认值，使用低开销半透明材质。OFF 使用不透明底栏。系统动画关闭时，FULL 可以保留静止折射。系统高对比度或外观中的“减少透明效果”会使用不透明底栏。窗口不支持完整渲染、低内存或省电时回退。记录到的相关渲染崩溃会在下次启动切到 LITE。

FULL 的页面采样使用 `rememberHazeState`、`hazeSource` 与 `HazeInput.Sources`。源不可用时清除旧画面。外壳与单颗透镜使用 `HazePerformanceMode.Default`。拖动改变透镜的实际宽高和位置，边缘折射实时跟随。运行模式只在页面需要采样时连接源，账号卡片和普通列表保持低开销。

## 调试与复现

在仓库根目录用 JDK 17 和 SDK 37 构建。设置 `glassQa` 会生成独立包名 `com.glasssign.app.glassqa`，便于在有日常数据的设备上并行安装。

```powershell
./android/gradlew.bat -p android -PglassQa=true :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
adb shell am start -n com.glasssign.app.glassqa/com.glasssign.app.ui.GlassPrototypeActivity
```

点击“演示融合”。正间隙 3dp 时对比 24dp 和 0dp 两种融合距离，再滚动标尺核对实时折射。可在该隔离包中加 `--ez seed_records true` 启动原型 Activity，生成两组角色和 60 条本地演示记录。测试过程中只使用该隔离包的数据。

单透镜与双滴方案已在前序候选中对照。正式 Dock 采用单颗水珠，双滴几何对照保留在独立调试原型。性能对比用 Android `gfxinfo framestats`、`meminfo` 和 `am start -W`，记录刷新率、帧时分位数、Jank、PSS 与冷启动显示耗时。

Haze 核心和玻璃库统一锁定 2.1.0。许可证副本随 APK 打包，见 [第三方声明](../THIRD_PARTY_NOTICES.md) 和 [Haze 2.1.0 发布记录](https://github.com/chrisbanes/haze/releases/tag/2.1.0)。
