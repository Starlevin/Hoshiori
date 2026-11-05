# 液态玻璃上游溯源、许可与 v0.7.6 原生实现

日期：2026-11-02。基线：`e2d8f5f`（v0.7.5 / versionCode 15）。本次只读溯源 + 在自有文件内实现。
user 参考：本地录屏 `Screenrecorder-2026-11-02-21-46-37-179.mp4`（1220×2656，9.75s）与 NexioSchedule。

## 1. 结论先行

| 问题 | 结论 |
| --- | --- |
| FlClash 与 NexioSchedule 是否同一个开源玻璃项目？ | **不是。** 两者是各自独立编写的玻璃实现，无共享玻璃代码。 |
| 各自实际用什么？ | FlClash：**Flutter/Dart 自研**（`lib/ui/liquid_glass.dart` + `shaders/liquid_glass.frag`），无第三方玻璃库依赖。NexioSchedule：**Compose Multiplatform，vendored fork** 自 `io.github.kyant0:backdrop:2.0.1`。 |
| 真正的上游是什么？ | [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，`io.github.kyant0:backdrop`，**Apache-2.0**。 |
| 能否合法接入？ | **能**（Apache-2.0 与 MIT 兼容，无需改本项目许可），但需保留版权/NOTICE/修改说明。本次选择不引入依赖，只适配其光学核心。 |
| Nexio / FlClash 的应用代码能用吗？ | **不能。** AGPL-3.0 / GPL-3.0 应用代码与 MIT 不兼容，未复制一行。 |

## 2. 固定 commit 与证据

所有结论来自固定 ref 的原始文件，未使用 `master` 漂移内容。

### 2.1 NexioSchedule（AGPL-3.0）

- 仓库：<https://github.com/HaoZai000/NexioSchedule>，default `master`
- 溯源 commit：`d6f01fb4f645e700c558939bd77fc4f818d97e4d`（2026-11-02T11:04:40Z）
  - `LICENSE`（该 commit）：GNU **AGPL-3.0** 全文
  - `README.md` L14 `license-AGPL--3.0`；L191 致谢表 `Backdrop → https://github.com/Kyant0/AndroidLiquidGlass → Kyant0`
  - `settings.gradle.kts`：`include(":backdrop")` — 玻璃库是**源码模块**，不是 Maven 依赖
  - `backdrop/build.gradle.kts` 首行注释：`// 液态玻璃效果库（fork 自 io.github.kyant0:backdrop 2.0.1）`
  - `ui-shared/build.gradle.kts`：`api(project(":backdrop"))`；另有 `top.yukonga.miuix.kmp:miuix-blur:0.9.4`（MiUiX，用于模糊组件）
  - `gradle/libs.versions.toml`：`composeMultiplatform = 1.12.0`、`kotlin = 2.4.10`、`agp = 9.2.1`、`kyantShapes = 1.2.1`；并注明「Backdrop 已 fork 到本地源码，不再声明远程依赖」（目录里无 `io.github.kyant0:backdrop` 条目，只有 `kyant-shapes`）
  - 应用侧光学调用点：`ui-shared/.../PageGlass.android.kt`（页面级合并路径折射）、`ui/effects/liquidglass/*`、`ui/components/LiquidBottomTabs.kt`

### 2.2 真实上游 backdrop（Apache-2.0）

- 仓库：<https://github.com/Kyant0/AndroidLiquidGlass>，default `kmp`
- 溯源 commit：`65ab177e90e5c1d8c62e70cf7755841982da65f6`（2026-08-26T03:57:56Z）
  - `LICENSE`：`Apache License Version 2.0`
  - `backdrop/build.gradle.kts`：`mavenPublishing { coordinates("io.github.kyant0","backdrop","2.0.1"); licenses { name = "The Apache License, Version 2.0" } }`，android `minSdk = 21`，`compileSdk = 37`
  - `gradle/libs.versions.toml`：`compose = 1.12.0`（`org.jetbrains.compose.*` 多平台坐标）、`kotlin = 2.4.10`、`kyantShapes = 1.2.1`
  - 光学核心：`backdrop/src/commonMain/kotlin/com/kyant/backdrop/internal/Shaders.kt`
    （`RoundedRectSDF` / `RoundedRectRefractionShaderString` / `...WithDispersion...` / `DefaultHighlightShaderString` / `AmbientHighlightShaderString`，文件头即 `Copyright 2025 Kyant / Apache 2.0`）
- Maven Central 已发布（可核）：<https://repo1.maven.org/maven2/io/github/kyant0/backdrop/maven-metadata.xml>，`latest = 2.0.1`，产物 `backdrop-android-2.0.1.aar`。
- 发布目标：android / desktop(jvm) / js / wasmJs / macosArm64 / iosArm64 / iosSimulatorArm64；依赖均用 `requires`（非 `strictly`），走常规 Gradle 冲突解析。
- 注意：Nexio 的 fork 模块自身 `minSdk = 26`（namespace `com.haooz.chedule.backdrop`），而**发布版 artifact 是 `minSdk 21`** —— 两者的最低 API 不是同一个数。

### 2.3 FlClash（GPL-3.0，Flutter）

- 仓库：<https://github.com/chen08209/FlClash>，default `main`
- 溯源 commit：`7fd417fb9a9a277c6a9339a8ac87f907df7c25f7`（release v0.9.2）
  - `LICENSE`：GNU GPL-3.0
  - `pubspec.yaml`：`flutter: sdk: flutter`，依赖里**没有** backdrop / kyant / 任何玻璃库；`shaders: [shaders/liquid_glass.frag]`
  - 玻璃实现：`lib/ui/liquid_glass.dart`（1665 行）、`lib/ui/liquid_glass_capture.dart`、`shaders/liquid_glass.frag`（22 KB，`#version 460 core` + `flutter/runtime_effect.glsl`），自研 uniform 体系（`uShape`/`uCorners`/`uCurves[12]`/`uOptics`/`uTint`…），无 kyant 派生痕迹与第三方归属声明。
  - 独立复核（第二人只读核验，全部 CONFIRMED）：三份文件内 `kyant` / `com.kyant` / `AndroidLiquidGlass` / `androidx.compose` / `RenderEffect` / `AGSL` / `RuntimeEffect` / `license` / `copyright` / `Apache` / URL 命中数**均为 0**；`lib/**/*.dart` 全 425 个文件扫 `kyant|androidliquidglass|com\.kyant` 亦为 0。`shaders/` 目录只有一个文件。上游没有任何 `*.frag`，FlClash 没有任何 Kotlin —— 连 shader 语言与运行时都不同。
  - 说明：该「未复制」结论由标识符/署名扫描 + 结构与语言分析得出，不是字节级克隆检测 diff；同一视觉效果的数学常数在独立实现中重复出现是正常现象。

**因此「FlClash 与 Nexio 是同一个开源玻璃项目」不成立**；唯一共同点是二者都在追求 iOS 26 液态玻璃外观。Nexio 的外观能力来自 Kyant0 的 Apache-2.0 库，FlClash 的是自研 Dart。

## 3. 许可分析

- 本项目 MIT（`LICENSE`，Copyright 2026 Starlevin）。
- **Apache-2.0 → MIT 项目**：兼容。以 Maven 依赖方式引入无需改变本项目许可；需要保留版权声明、许可文本与 NOTICE（若上游提供）、标注修改。
- **AGPL-3.0 / GPL-3.0 应用代码 → MIT 项目**：不兼容。复制 Nexio 的 `PageGlass`/`LiquidBottomTabs`/`LiquidFusion`，或 FlClash 的 Dart/shader，会使相应衍生部分必须按 AGPL/GPL 发布，等于改变本项目协议。**本次未复制任何一行。**
- 本次采取的方式：**只适配 Apache-2.0 上游的 AGSL 光学核心**，自行重写为本项目 `RuntimeShader` 与既有的已录制页面 `GraphicsLayer` 管线；保留 `Copyright 2025 Kyant`、Apache-2.0 全文与修改清单。
  - `android/app/src/main/assets/licenses/backdrop-apache-2.0.txt`（全文，随 APK 分发）
  - `android/app/src/main/assets/NOTICE-backdrop.txt`（版权、固定 commit、逐条修改说明）
  - `THIRD_PARTY_NOTICES.md`（新增「Backdrop — adapted optics」一节）

## 4. 为什么不直接引入 `io.github.kyant0:backdrop:2.0.1`

`backdrop-2.0.1.module` / `backdrop-android-2.0.1.module` 的已发布依赖（实测）：

```
org.jetbrains.kotlin:kotlin-stdlib:2.4.10
org.jetbrains.compose.foundation:foundation:1.12.0
org.jetbrains.compose.ui:ui:1.12.0
org.jetbrains.compose.ui:ui-graphics:1.12.0
io.github.kyant0:shapes:1.2.1
org.jetbrains:annotations:26.1.0
```

与本项目现状的差距：本项目 `compileSdk 35 / minSdk 26`，`compose-bom 2025.02.00`（AndroidX Compose 1.7.x 世代）、Kotlin/AGP 由 `android/build.gradle.kts` 决定。

**最小兼容方案（供根代理决策，本次未执行）**

| 项 | 现状 | 引入 backdrop 所需（最小） | 风险 |
| --- | --- | --- | --- |
| Compose | `compose-bom 2025.02.00` | `org.jetbrains.compose.ui:ui:1.12.0`（多平台坐标，Android 上映射到 AndroidX Compose **1.9/1.10 世代**） | 需整体抬 BOM；Material3/`ui-test` 需同步 |
| Kotlin | 项目当前值 | `kotlin-stdlib 2.4.10` 与 K233 兼容时可不抬；否则抬到 2.4.x | 抬 Kotlin 会连带 Compose 编译插件与 AGP |
| 最低 API | `minSdk 26` | 库 `minSdk 21`，**不是**瓶颈；真实门槛是 `RuntimeShader`（API 33） | 与现有 `>=33` 分支一致，不需要改 minSdk |
| 新依赖 | — | `io.github.kyant0:shapes:1.2.1` | 新增一个第三方坐标 |

判断：仅为一个 Dock 抬整条 Compose/Kotlin 工具链，代价与回归面远大于收益，且本项目已有等效的「已录制层 + 原生 AGSL」管线。**建议保持不引入**，采用本次的小范围原生适配；若根代理仍要接入，建议在同一轮里单独做依赖升级与全量回归。

## 5. 本次实现（`ui/LiquidDock.kt`，唯一改动的既有源码文件）

参考录屏的视觉要点（根代理解析）：深色烟熏透明、背后文字仍清晰；边缘有明显放大/翻转折射而非均匀模糊；移动/按压胶囊有厚度与**明显彩色分光边**；手指下鼓起；中间面平静、边缘弯曲集中；选中项是深色小胶囊 + 主题强调色，不是白色半透明块。

| 上游能力 | 适配为 | 关键差异 |
| --- | --- | --- |
| `RoundedRectRefractionShaderString`（SDF + `circleMap` + SDF 梯度折射） | `OpticalPass` | 采样源是本项目已录制页面 `GraphicsLayer`；`refractionHeight` 由 lens 高度按 dp 推导并上下限夹取；`refractionAmount` 由该 bevel 派生 |
| `...WithDispersion...`（7 次采样光谱） | `OpticalPass` 内的单 pass 5 tap | 不再需要第二个 shader 变体；桶子按「原色」权重归一保留 alpha |
| `DefaultHighlightShaderString` + `AmbientHighlightShaderString` | `EdgeLightPass(ambient)` | 两条独立 effect；`BlendMode.Plus` 等价物由透明层叠加表达 |
| 上游 catalog `lens(24.dp, 24.dp)` / `Highlight.Default(45°, falloff 1, white .5)` / `Ambient(.38)` | `GlassOptics` 常量 | 不照抄 demo 值，按录屏调；全部 dp→px，密度无关 |

新增纯函数（可在无设备环境断言，已用桌面数值复核）：

- `glassBandPx(height, drag, density)`：`ratio 0.16`，静态上限 `9dp`、拖动上限 `6dp`、下限 `3dp`，并夹在 `height/2` 内 → 任意高度下 bevel 都是「边缘一圈」。
- `glassBendPx(...)`：`band × 2.4`（拖动 `×2.0`），始终由 bevel 派生，永不超过 lens 的 40%。
- `glassCircleMap(x)`：上游圆剖面（非 sin）。

保留不动的既有防线（本次零改动）：

- `backdropSource` 的 `captureRevision` 首帧后重录 + `key(sampledFrameReady)` 材质层重建（冷启动整层隐形修复）。
- `DockDrag` / `dockReleaseSlot` / `dockGestureKey` / `dockDrag` 的手势语义：普通点击、连续跟手、松手只切一次、取消复位。
- 四个 Tab、`nav-0..3` / `floating-dock` / `selected-capsule` / `dock-gesture-layer` testTag、`selectable` + `Role.Tab` 语义。
- `glassPolicy` 矩阵（API<31 / 低内存 / 省电 / 辅助功能 / 减少动效 / light / off）。
- 无无限动画、无每帧整页重组：新增的 `bulge` 是普通 `@Volatile` 字段，只在 `graphicsLayer` lambda 里读；按压集合只翻一次，配 160 ms 帧时钟有界 ramp（`policy.motion` 为 false 时直接取 1）。

透明度策略（回应「不要只提高白色透明度冒充玻璃」）：配色改为降低 tint（`BarTintAlpha 0.22`、`CapsuleTintAlpha 0.075`），厚度由折射/色散/高光产生；带模糊时那条 `1dp` 白描边降到 α 0.22–0.10，边缘光交给 `EdgeLightPass`。

## 6. 未做与不确定

- **未编译、未上机、未做截图对比**（本次授权范围）。因此**不能声称效果与录屏一致**；只能说实现已换成上游同类光学管线，并把可测量的量用测试钉住。
- 已做两轮静态核对（第二轮对 `.gradle-home` 缓存里的真实 jar 做了 `javap` 反编译，Compose UI 1.7.8 / BOM 2025.02.00）。修掉的编译期与运行期缺陷：
  1. `rememberGraphicsLayer` 缺 import —— `graphics.*` 与 `graphics.layer.*` 都不覆盖；HEAD 里就是潜在问题，现显式声明。
  2. `CompositingStrategy.Offscreen` 是 `@RequiresApi(31)`，而 `graphicsLayer{}` / `record{}` 是 **inline**，lambda 被内联进未标注的函数体，lint 不接受写在 lambda 内的 `SDK_INT` 判断 → 在**具名 `@RequiresApi(33)` 辅助函数**里使用；`CapsuleGlass`/`SampledGlass` 直接不用 offscreen（`renderEffect` 自带 RenderNode）。
  3. `PointerEventType` 是 `@JvmInline value class`，**不能**当 `when` 分支条件，改为 `when { type==... }`。
  4. `alpha=alpha` 在 `graphicsLayer` lambda 内解析成 `GraphicsLayerScope.alpha`（自赋值无效，边缘光亮度永远不生效）→ 改 `this.alpha=alpha`；ambient 层改用 `ModulateAlpha` 以避开与 lit 层都走 Offscreen 的 compositing 不一致。
  5. 测试 `createBlurEffect(0f,…)` 在 API33+ 抛 `IllegalArgumentException("radius > 0")`（生产端被 `runCatching` 吞，测试直调会 ERROR）→ 改正数；`lit===ambient` 比较两个新建匿名 handle 恒真 → 改为比较真实像素的上下边缘亮度差。
  6. 确认成立：`List(4){r}` → `List<Float>` 推断；`setFloatUniform` 的 1/2/4 参调用绑定到 API33 **公开定长重载**（**勿**改成 vararg，那个重载是隐藏的）；`GlassEdgeLight` 超前 `return` + 两个兄弟 `Box` 合法；外部 5 处 `LiquidDock` 调用都绑定到已声明重载。
- **已修**：`ui-test` 的 `setContent` 每个测试只能调一次。原 `ambientAndLitHalvesDiffer` 内部调用两次 host 会运行期失败；现合并为单个测试 `litAndAmbientRimsAreNotTheSameLayer()`，把 lit 与 ambient 两层**并排挂进同一 composition**，一次截图取两组上下边缘亮度，既满足单次 `setContent`，输出也从恒真的 `===` 变成真实像素断言。
- **已修**：`alpha=alpha` 的 rhs 在 `graphicsLayer` lambda 内仍会解析到 `GraphicsLayerScope.alpha`（与 lhs 同一个接收者，等于自赋值），故把外部参数改名 `edgeAlpha`，现在是 `alpha=edgeAlpha`，不存在 shadow。
- `EdgeLightPass` 输出白色高光，`BlendMode.Plus` 在 `RuntimeShader` 里无法表达，改为透明层叠加；最终观感需设备确认。
- 颜色桶（chromatic）权重是按上游 7-tap 归一后重排的近似，不是逐行照搬。
- 视频帧含私人网络配置，未纳入仓库。
- 背景照片对比需要等照片背景落地后再评估；本次不预判。

## 7. 测试

新增 `android/app/src/androidTest/java/com/glasssign/app/ui/LiquidGlassOpticsV076Test.kt`：

- 纯数学（所有 API 均可跑）：bevel 恒为边缘一圈、拖动上限更紧、下限存在、bend 由 bevel 派生且不越界、退化输入安全、`circleMap` 单调且端点正确、色散强度分级、tint 仍是薄纱且白色不越界。
- 真实像素（API 33+）：`OpticalPass` 折射后整行保持不透明、不被 black out、内部保留页面颜色；`EdgeLightPass` 边缘发亮而内部明显更暗；lit/ambient 两个变体确实不同。
- 不引用 `HoshioriScreen`/`Theme`（他人范围），不依赖设备状态。

## 8. 需要根代理处理的发布事项

- `.gitignore` 把 `/docs/*` 整体排除，只逐个 `!` 放行 `v070-report.md` 等。本文件 `docs/glass-research-v076.md` 因此**当前不会被 git 跟踪**（未修改 `.gitignore`，那是根代理的范围）。若要把溯源证据纳入公开快照，需追加 `!/docs/glass-research-v076.md`。
- 已随 APK 分发的许可文件不受影响：`android/app/src/main/assets/licenses/backdrop-apache-2.0.txt` 与 `android/app/src/main/assets/NOTICE-backdrop.txt` 在 `/android/` 白名单内，会被跟踪。

## 9. v0.7.7 P1：Dock 尺寸/内边距实测化与描边收敛

该版本**只改** `ui/LiquidDock.kt`、`ui/HoshioriScreen.kt` 和两个新增纯 JVM 测试；未改 data/Models/RecordPresentation/Theme/Gradle/Git，未编译、未上机、未做 FPS 测量。

### 9.1 Dock 实际占位改为实测回传

固定 `160.dp + navigationBars` 的 `contentPadding` 有三处错配：手势导航（inset 很小却多留）、200% 字号（88dp 的 bar 却留不够，末卡被压在 dock 下）、横屏（dock 宽度封顶但高度不变）。

- 新增纯函数 `dockOverlapPx(barHeightPx, liftPx, navBottomPx)` = bar + lift + navInset，**nav inset 只加一次**（dock 自身用 `navigationBarsPadding()`，调用方不再叠加）。
- `Dock` 用 `onSizeChanged` 上报实测 overlay，**只在整数 px 变化时**写状态，拖动/弹簧/按压帧不写。
- `dockContentPaddingPx(overlap, gap, min)` + `dockBottomPaddingPx(px, density)`：末卡比 dock 顶部再高 `8.dp`，且有 `96.dp` 下限（首帧 dock 未测量、或 modal 打开时 dock 不挂载，列表底距仍在）。
- 冷启动链未动：`captureRevision`、`sampledFrameReady`、`key(sampledFrameReady)`；dock 仍是 `backdropSource` 的**兄弟节点**，永不进入采样源。

### 9.2 bleed 的数学错误已修正

原实现 `measure(maxWidth-2*inset)` 后又 `layout(width+2*inset)`，**返回宽度恰等于原约束**，所以 row 从未真正出血，只有内容 padding 变大。现改为按**原约束**测量、汇报 `width+2*inset`、内容置于 `x=inset`，出血由视口真正扩展到屏幕两侧。

### 9.3 状态不再省略

`QResultRow` 去掉 `maxLines=1 + Ellipsis`，改为 `maxLines=SUBLABEL_MAX_LINES(3)` + `overflow=Visible`：完整可读、有上限、不挤掉卡片元数据。左侧目标名 `weight(1f)` 先让位。新增纯函数 `statusTone(group)` 映射五档语义色（greenSuccess/tealAlready/amberAccepted/grayUnknown/redFailed → 复用 `colorScheme` 角色，不硬编码、跟随主题），并保留文字本身表达含义（色盲可读）。

### 9.4 白色双层描边收敛

原 bar：`border(1.dp, 全周白)` **叠加** SDF `RenderEffect` 高光，两道全周白环 = 生硬白描边。现两者互斥：有模糊时只有 SDF 单向高光（亮度随 `|dot(grad,lightDir)|`，一侧亮一侧暗），无模糊时才用弱 hairline；顶部 sheen 降为 `dark .07 / light .16`，胶囊仅 `.5.dp` 弱边。圆角、弹簧、快击与拖动均未改动。

### 9.5 新增测试（纯 JVM，无设备）

- `DockLayoutV077Test`：nav inset 只加一次、手势 vs 三键、inset=0、200% 字号增高、负数/NaN 不产生负值、末卡净空 = overlay+gap、首帧地板值、Dp 往返与密度无关、退化 density 不崩。
- `StatusToneV077Test`：五档全部可达且互不塌陷（already≠success、attention≠failed）、大小写/空白容错、未知组回落灰而非红色、`SUBLABEL_MAX_LINES` 是真实上限。

### 9.6 限制

未编译、未上机、**不声称真实 FPS 或与录屏观感一致**；实测 overlay 的设备级验证（旋转、手势导航、IME、200% 字号）留给 root 发布前实际验证。

## 10. 参考

- NexioSchedule `d6f01fb4f645e700c558939bd77fc4f818d97e4d`：<https://github.com/HaoZai000/NexioSchedule/tree/d6f01fb4f645e700c558939bd77fc4f818d97e4d>
- 上游 backdrop `65ab177e90e5c1d8c62e70cf7755841982da65f6`：<https://github.com/Kyant0/AndroidLiquidGlass/tree/65ab177e90e5c1d8c62e70cf7755841982da65f6>
- 上游 Shaders.kt：<https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/internal/Shaders.kt>
- Maven Central：<https://repo1.maven.org/maven2/io/github/kyant0/backdrop/maven-metadata.xml>
- FlClash `7fd417fb9a9a277c6a9339a8ac87f907df7c25f7`：<https://github.com/chen08209/FlClash/tree/7fd417fb9a9a277c6a9339a8ac87f907df7c25f7>
