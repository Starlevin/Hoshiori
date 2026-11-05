# 星栞正式版发布流程

用户已确认今后只构建、分发正式 Release APK，不再构建调试版 APK。运行单元测试、Lint 和必要设备验证仍是正式发布的一部分。

## 两个公开通道

- GitHub：https://github.com/Starlevin/Hoshiori/releases 。正式版查询正式 Release；版本名带 `preview.N` 的预览版查询发布列表，接受更高预览版或最终正式版，均忽略草稿。
- 夸克永久发布目录：https://pan.quark.cn/s/739482783865 ，公开链接，无提取码，已按永久有效创建。

GitHub 提供版本、发布说明和 APK 元数据，安装包在应用内直接下载并校验。夸克目录与浏览器链接作为外部备用入口，即使 GitHub 检查失败也保留。夸克网页不作为可执行更新配置，星栞不索取网盘账号。

## 每次发布

1. 完成源代码集成，使用 JDK17/compileSdk36、targetSdk35 构建 `:app:assembleRelease :app:testReleaseUnitTest :app:lintRelease`，保留测试与构建报告。
2. 使用既有正式签名证书，完成 zipalign、签名和 apksigner 校验。正式包名 `com.glasssign.app`。正式签名自 v0.7.5 固定，不能替换为 Android Debug 签名。
3. 验证版本、包名、调试标志及必要设备场景。应用数据和未知签到提交保护不得清除。未实测功能明确列出。
4. 固定源码提交；打包公开源码，不包含私钥、密码、Cookie、令牌、个人账号数据库、本机配置、缓存或私人截图。
5. 在 GitHub 对固定提交创建 Release，附件提供签名 APK、源码 ZIP、发布说明、构建信息和 SHA256SUMS；回读 GitHub 上传摘要确认 APK 哈希一致。预览版本标记 prerelease，设置 latest=false，不替换正式 latest。
6. 将相同附件复制到本地待上传目录 `Hoshiori/v<版本>/安装包`、`源码`、`报告与校验`。只上传该版本目录，不重复上传全部历史版本。保留原有分类，不覆盖旧版本产物。
7. 将版本目录同步到夸克 Hoshiori 发布目录，沿用固定分享链接。上传失败时在发布页注明通道状态，并续传失败文件。
8. 验证网盘实际目录与分类，向用户提供 GitHub 版链接和夸克永久链接，附测试摘要、安装与迁移限制。

CI 只生成 unsigned Release 构建验证产物，文件名明确为未签名，不能作为用户安装包发布。正式签名材料保存在维护者本机受保护目录，不上传到仓库、CI 日志或夸克。

## 安装与迁移

已有正式版只能由相同包名和证书覆盖更新，系统保留本机数据，应用执行既有数据库迁移。`.debug` 测试包与正式包是独立 Android 应用，不能自动共享其 Keystore 授权数据；保留测试版，不应卸载清数据来强行升级。

从 v0.7.9-preview.3 起，预览版也使用正式包名 `com.glasssign.app` 与既有证书，versionCode 持续递增。旧 `.preview` 包保留为独立安装，不能自动合并其数据。后续正式与预览的区别由版本名及 GitHub 发布标记表示，不再改 Android 包名。

夸克永久表示创建时设置无到期时间；实际可用性仍受网盘服务、审核、账号状态与文件保留情况影响。
# 补充：许可与源码一致性（2026-11-05）

新发行应按实际运行依赖核对许可、版权及必要 NOTICE，不能只看根 LICENSE。检查 APK 内的 `assets/NOTICE-*`、`assets/licenses/*` 与源码一致，同时保留依赖自身的数据许可声明，例如 OkHttp 的 Public Suffix List / MPL-2.0。自有 MIT 全文和第三方声明应作为分发材料提供；应用内统一查看入口仍需补充。

从明确的发行提交导出跨平台源码时，避免 Windows 自动转换行尾：

```shell
git -c core.autocrlf=false -c core.eol=lf archive --format=zip --prefix=Hoshiori/ --output=source-lf.zip <release-commit>
```

解压后核对文件集合与 Git blob 内容，记录实际提交、包体摘要与许可文件摘要。已发布产物保留，后续纠正或补充使用不同文件名和单独校验表。检查点/提交保护的真实保留范围按 DATA_HANDLING.md 说明，不能用“升级保留数据”掩盖删除副本未清理的问题。
