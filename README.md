# 星栞 Hoshiori

Android 游戏签到工具。一次登录，选择需要绑定的游戏与角色，之后可以手动签到，也可以交给手机的每日计划。账号授权和签到记录保存在本机，无需自建服务器、Root 或 Shizuku。

[下载正式版 v0.7.8](https://github.com/Starlevin/Hoshiori/releases/tag/v0.7.8) · [体验预览版 v0.7.9-preview.5](https://github.com/Starlevin/Hoshiori/releases/tag/v0.7.9-preview.5) · [夸克网盘](https://pan.quark.cn/s/739482783865)

preview.5 提供更紧凑的玻璃 Dock、整栏拖动和应用内下载安装更新，支持覆盖正式版。稳定通道仍为 v0.7.8。

## 功能

- **多账号、多角色**：同一平台可以绑定多个账号，一个账号下可以选择多个游戏和角色。增加游戏时使用已有授权。
- **本地自动签到**：支持每日计划、网络恢复后执行和重启恢复。后台执行时间受 Android 电池与网络策略影响。
- **签到记录**：分别显示成功、已签到、待确认和失败，并保留结果来源。已经完成的目标会跳过，结果不明时暂停重复提交。
- **外观设置**：支持深浅色、纯黑、主题色、自选背景和悬浮玻璃导航，低性能设备可选择轻量效果。

游戏目录包含原神、崩坏：星穹铁道、绝区零、崩坏3、明日方舟、终末地、异环、鸣潮和战双帕弥什。各平台的登录、角色读取和签到能力以实际运行结果为准，部分功能仍在验证。

## 使用

1. 进入「账号」，点击右上角 **+**。
2. 选择平台并登录，读取游戏角色。
3. 勾选需要绑定的游戏与角色，确认保存。
4. 在首页签到，或进入「设置 → 自动签到」安排每日计划。

签到遇到授权失效时，可以进入账号详情重新登录。显示待确认时，先查看官方记录或使用状态核对，避免重复提交。

## 安装与更新

Android 8.0 及以上。当前预览版与正式版使用相同包名 `com.glasssign.app` 和发布签名，覆盖安装保留本机数据。旧 `.preview` 包仍是独立应用，其数据不会跨包名自动合并。

同包名、同签名的安装包可以覆盖更新。请保留原安装，卸载或清除应用存储会丢失本机密钥，需要重新登录。旧 `.debug` 包的数据不会自动迁入正式版。

preview.5 可在「设置 → 版本与更新」检查、下载并校验 APK，再由 Android 确认安装。浏览器与夸克保留为备用入口。

## 开发

原生 Kotlin / Jetpack Compose，使用 WorkManager 调度后台任务，SQLite 保存记录，Android Keystore 保护授权。各版本源码见对应发布标签。

构建 v0.7.8 及当前预览版本需要 JDK 17、Android SDK 36：

```shell
git checkout v0.7.8
cd android
# Linux / macOS 首次构建前执行 chmod +x gradlew
./gradlew --no-daemon --max-workers=2 :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

Gradle 生成未签名 Release APK，分发前还需签名。覆盖更新必须使用原证书。

## 许可与数据

星栞是独立第三方项目。游戏与社区名称用于识别兼容平台，不表示相关运营方的认可或合作。

自有代码采用 [MIT](LICENSE)，第三方代码与数据保留各自许可，详见 [第三方声明](THIRD_PARTY_NOTICES.md)。[数据与权限说明](https://github.com/Starlevin/Hoshiori/blob/feature/frontend-miuix-preview/DATA_HANDLING.md)列出了授权存储、记录删除和升级检查点的保留范围。

当前仍需完善检查点清理、应用内隐私与开源许可入口、平台授权及发行条件核对。问题与建议可通过 GitHub Issues 反馈，请勿公开上传密码、验证码或授权内容。
