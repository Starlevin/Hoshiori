# Hoshiori Android

Kotlin / Jetpack Compose 原生客户端。当前入口为 MainActivity → HomeViewModel → LocalServices，所有签到通过 LocalSignEngine 直连社区接口。

JDK 17、SDK 35，执行 `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`。完整说明见仓库根 README。

为兼容原 GlassSign 安装，包名 `com.glasssign.app`、debug 后缀 `.debug`、数据库/Keystore/WorkManager 名称保持不变。界面名称为星栞 Hoshiori。请勿以重命名为理由修改存储命名空间或卸载原应用。

测试代码仅使用合成授权。设备集成测试应在专用空账号设备运行，不能在用户主手机执行完整回归。
