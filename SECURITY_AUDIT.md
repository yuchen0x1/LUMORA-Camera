# 安全审查

## 2.1.8 / code 12 正式发布审查（2026-11-26）

### 1. 总览

公开稳定版为 **2.1.8 / code 12**，不可变标签 `v2.1.8` 指向 `53029a89f6a06c943ffff5a01b9c8c5d04dea3e5`。正式发布运行 `45787235294` 的 11 个 job 全部成功；公开时间为 **2026-11-26 09:11:12 UTC**。独立下载的正式 APK 为 **50,492,714 B**，SHA-256 为 `bcd6516a8823400320da0c14a42428a3f9bde27473b225601630b366435683ed`。公开资产、升级与测试范围见 [RELEASE_HISTORY](RELEASE_HISTORY.md) 和 [TEST_REPORT](docs/TEST_REPORT.md)。下方历史审查和 2.1.7 证据保留，不挪作本版结果。

### 2. 本版范围与结论

- **变更范围**：删除 13 张从未被文档引用的早期测试截图和 5 份已完成的旧实施计划与设计过程稿，并补齐本地忽略规则；不改变应用运行时代码、依赖、照片格式、签名证书或数据迁移逻辑。因此本版不重复 2.1.7 的客户端行为与备份白名单审查结论，也不宣称重新完成全部静态暴露面扫描。
- **签名与来源**：源码 main push CI 45785378016、CodeQL 45785378221、依赖安全 45785378187 与标签发布 45787235294 均成功。独立下载的公开 APK 经 `apksigner verify --verbose --print-certs` 返回 0，v3=true，单一正式签名者证书 SHA-256 为 `c5bec00b0721f580823a9bc9acda5285fa1c5e108ed3798d0ee3bc626c157985`，与 v2.1.7 相同；`aapt` 确认 `com.lumora.camera`、2.1.8 / code 12 / min 29 / target 35、非 debuggable，`zipalign -c -P 16 4` 通过。
- **更新通道**：`android-update.json` 声明 versionCode 12、APK 50,492,714 B、同一 SHA-256 与同一签名证书；`SHA256SUMS.txt` 的 APK 与元数据条目均与独立下载一致。旧版客户端读取 `releases/latest` 时会遇到递增版本码并提示升级，安装前仍执行大小、摘要、包名、版本和同证书校验。
- **远端告警**：发布前后完整分页查询均为开放 Code Scanning 告警 0、开放 Dependabot 告警 0；这是时间点证据，不是未来无漏洞保证。已关闭的 5 个 Dependabot 升级 PR 均 CI 失败且未合入主线，远端最终只保留 `main`。
- **未覆盖边界**：本版未执行实体设备保留数据覆盖升级，未复用 2.1.7 的覆盖升级结论；实体 RAW/厂商 AF/扩展、12/48 MP 峰值内存、URI 授权回收、提供方阻塞、云备份迁移、UpdateDialog 接线及实体安装器/多窗口仍开放。

## 2.1.7 / code 11 正式发布审查（历史，2026-11-21）

### 1. 总览

公开稳定版为 **2.1.7 / code 11**，不可变标签 `v2.1.7` 指向 `7778eb8a5b43fe7f940ae2ec446bed6e0cc50c98`。正式发布运行 `44902474570` 的 11 个任务全部成功；公开时间为 **2026-11-21 12:27:00 UTC**。独立下载的正式 APK 为 **50,492,714 B**，SHA-256 为 `f34422e09712ec2b42e5b75e2b4f8fa764294a828c1d7ed6983c998f6befebbf`。公开资产、升级与测试范围见 [RELEASE_HISTORY](RELEASE_HISTORY.md) 和 [TEST_REPORT](docs/TEST_REPORT.md)。下方历史失败和 2.1.6 证据保留，不挪作本版结果。

针对这一正式包的 `android` 静态暴露面扫描为 **98/B，1 低、3 信息、0 高/严重**，`--fail-on high` 实际返回 **0**。这是扫描器的相对启发式评分，不是安全认证、秘密穷尽扫描或无漏洞证明；未检查的画像也不表示安全。报告在 `D:/BuildTemp/lumora217-public-verified-qq92g04r/evidence/exposure-scan.json`。

### 2. 发现清单

| 编号 | 严重度 | 证据与人工判读 | 对应分析能力 |
| --- | --- | --- | --- |
| APK-ALLOWBACKUP | 低 | Manifest 的 `allowBackup=true`；受限白名单是有意策略，不等于已证明任意 ADB 数据导出 | 备份配置/数据提取审查 |
| APK-SIGOK | 信息 | 扫描器识别签名块；实际方案和签名有效性以 `apksigner verify` 为准 | APK 签名块检查 |
| APK-MANIFEST | 信息 | 已解析 `com.lumora.camera` Manifest，37 个元素 | Manifest/组件审查 |
| APK-DEX | 信息 | 3 个 DEX 文件，客户端实现可反编译分析 | DEX 静态分析；不是已发现授权绕过 |

### 3. 逐项证据与修复

- **签名、来源与更新通道：** 独立验证的 `apksigner` 返回 0、`Verifies`、单一正式签名者，证书 SHA-256 为 `c5bec00b0721f580823a9bc9acda5285fa1c5e108ed3798d0ee3bc626c157985`，与既有正式版一致；v3 验证为 true。扫描器的签名块存在性提示写作 v2/v3，但本次密码学验证输出为 v2=false、v3=true，不能混淆两者；既有 minSdk 29 的 v3 策略未降低。`aapt` 身份、minSdk 29 / targetSdk 35、non-debuggable 及 `zipalign -c -P 16 4` 均通过。三个公开资产与 API digest、元数据、SHA256SUMS 精确一致，证据在同目录 `verification.json`。最初证书正则因 Windows CRLF 解析失败，随后从原下载和原成功验签输出以 `splitlines()` 精确核对，未放宽证书要求、重下包或重发版本；原失败保留。
- **备份（APK-ALLOWBACKUP）：** `app/src/main/res/xml/backup_rules.xml` 和 `data_extraction_rules.xml` 的云备份/设备迁移仅允许 `lumora_preferences.xml`、`lumora_updates.xml`。捕获日志未入白名单而隐式排除；宽泛 FullBackupContent suppression 已移除。偏好含作品索引与恢复引用，仍有隐私含义；真实云备份迁移未验收，规则不迁移 MediaStore 照片，也不重建 URI grants。不为消除扫描提示而关闭已约定的受限备份功能。
- **DEX/Manifest：** 开源客户端不把胶片算法当作秘密；DEX 可分析本身不是缺陷，不为抬高分数引入加壳、反调试或声称字符串加密能够保密。既有更新 FileProvider 不导出且只覆盖更新缓存；`AndroidUpdater.kt:94–111` 核对字节大小、SHA-256、包名、递增版本码、minSdk 和候选/已安装证书；`UpdateProtocol.kt` 限定仓库与 HTTPS 下载来源。这是有界源码审查，不等于已完成全部网络/归档/授权场景测试。
- **依赖与远端安全门禁：** DocumentFile 1.1.0、可信 Gradle 9.1.0 Wrapper、STRICT 锁/校验已接入。官方 SHA-256 来源审计 **919/919、0 mismatches/bypasses**，校验元数据 `SHA800c2a465d9b37c1495a35347c9e92662d85113995b0939c8f54702d740c4b5e` 保留原始 Windows-CRLF 快照；Git-LF 摘要 `cd23b0bdc4638ff9235e931085591f7d9a9a350581448f30bd0d56dd44209352`，仅换行差异，919 条 XML 条目未变。标签运行实际 CodeQL 2.27.5 分析为 java-kotlin `2220686804`（76 rules）、python `2220682858`（43）、actions `2220682811`（17），各 0 results、空 errors/warnings，匹配 `refs/tags/v2.1.7` 与 `.github/workflows/release.yml:analyze`。2026-11-21 13:31:28–32 UTC 完整分页快照中仓库/main/tag 开放 code-scanning 和 Dependabot 各 0；这是时间点证据，不是未来无漏洞保证。独立审查根为 `D:/BuildTemp/lumora217-tag44902474570-evidence-ea6c694a29`。
- **回归与历史失败：** 标签阶段 JVM 53 通过，双变体 Lint 各 51 Warning、0 Error/Fatal；7 份 instrumentation 报告共 313 个成功方法终态，默认集 API 29/35 为 150/151，另含真实安装器取消、360dp/fontScale 200%、100 张/200 JPEG 耐久和平板 8 项。报告只证明发出的测试结果，不证明内部条件分支没有提前返回，也不覆盖实体硬件或全部 UpdateDialog/网络场景。旧未签名中间包 `232983df7f9ef902a1565e870b59c50ad2610c55fa051b9c10e32495803f9c8f`（50,442,871 B）扫描 **86/B**、唯一高项 APK-SIG、门禁返回 1 的记录仍保留在 `D:/BuildTemp/lumora217-staged-exposure-_5dc_veo/verification-report.txt`；该包始终不可分发，不能把其分数或失败套用到正式包。

### 4. 客户端无法解决的问题

- 混淆、native 下沉或加密字符串不能让随客户端分发的长期秘密真正保密；不得把最终授权/计费判断仅放在客户端。此次静态扫描不是服务端接口或付费授权审计，也不能证明所有字节均无秘密。
- 正式签名私钥和发布权限仍须由受保护 CI、秘密存储与审计流程保护，不能随 APK/源码下发；客户端证书核对无法替代私钥保护。
- 本地保留数据覆盖升级不等于跨设备备份迁移，也不证明提供方权限永久有效。实体硬件、厂商 AF/RAW/扩展、12/48 MP 峰值内存、URI 授权回收、阻塞提供方、实体安装器及多窗口等仍有开放验证边界。

### 5. 修复顺序与保留项

1. P0：若发现真实凭证泄漏，先吊销/轮换并移出客户端；本扫描未确认此类命中，不能据此保证不存在。
2. P1：保留来源/签名/版本/摘要和受保护发布门禁；本版源码 CI、实际安全分析、签名包启动与公开资产独立验证均已完成，不复用或移动旧标签。
3. P2：维持受限备份白名单并评估索引隐私；按上述开放边界补做实体/提供方/真实迁移测试，不以模拟器通过、相对扫描分数或本版发布声称积压全关。

## 历史安全审查（保留原证据）

审查日期：2026-11-21（本地时间）。对象：本仓库源码、自有 APK 及 GitHub 构建/发布链。无第三方渗透或漏洞利用。以下是范围化证据，不是“没有任何漏洞”的保证；未生成量化安全评分。

## 1. 总览

- 巡检通过 GitHub Dependabot API 分页读取全部开放记录，共 55 条；记录中既有 APK 运行库，也有 Gradle 插件和 UTP 测试工具传递依赖，不能混为同一攻击面。
- 本轮仅采用公告修复版本、解析版本下限和兼容性回归；不关闭扫描，不忽略告警，不读取签名秘密值。
- `settings.gradle.kts` 在项目评估之前对构建插件及项目配置应用安全版本下限，保留比下限更新的依赖；Guava 区分 Android/JRE 变体，Netty 只约束 4.1 系列，不将 tcnative 2.x 或未来 4.2 强降级。
- 原有 Dependency Review、依赖图提交、双 API 测试和受环境保护的正式签名流水线均保留。

## 2. 发现清单

| 范围 | 证据 / 公告 | 原版本 | 目标修复下限 |
| --- | --- | --- | --- |
| APK 与构建 Guava | GHSA-xxph-c9ww-hj94；CameraX 运行依赖及 AGP 构建依赖 | 33.3.1-android / jre | 33.7.2-android / jre |
| 构建 Bouncy Castle | GHSA-9pwp-9qqc-pr26、GHSA-qp49-qgx5-5m26 等；插件 classpath | 1.79 | bcprov/bcpkix/bcutil 统一 1.85 |
| UTP 工具 Netty | GHSA-c4c3-7fpv-j4q5 及其余 4.1 公告；内部 UTP 配置 | 4.1.93 / 4.1.110.Final | 4.1.137.Final |
| Kotlin 构建缓存 | GHSA-r937-wjx7-w2jp | Kotlin Gradle Plugin 2.2.20 | Compose/Kotlin 插件 2.4.21 |
| 其他构建/测试传递库 | GHSA-3677-xxcr-wjqv、GHSA-2363-cqg2-863c、GHSA-j288-q9x7-2f5v、GHSA-735f-pc8j-v9w8 | jose4j 0.9.5、jdom2 2.0.6、commons-lang3 3.16.0、protobuf-kotlin 3.24.4 | 0.9.6、2.0.6.1、3.18.0、3.25.5 |
| 历史/图中版本差异 | GHSA-7r82-7xv7-xcpj、protobuf-java 公告 | 源依赖可能声明旧版本，实际可能已升级 | httpclient 至少 4.5.14，protobuf-java 至少 3.25.5 |

## 3. 证据、修复及验收

- 来源为 `gh api --paginate repos/yuchen0x1/LUMORA-Camera/dependabot/alerts?state=open&per_page=100` 的漏洞版本区间及 first_patched_version，辅以公告 API 与官方 Maven POM。没有运行公告中的攻击示例。
- 上游 POM 已核对 `com.google.guava:guava:33.7.2-android`、`org.bouncycastle:bcprov-jdk18on:1.85`、`io.netty:netty-bom:4.1.137.Final` 存在；仅确认存在不等于兼容测试通过。
- 对插件 `buildEnvironment` 与应用全部配置的依赖报告完成实际解析检查：22 个受约束模块、0 个低于修复下限或解析失败的条目；包含 Guava Android/JRE、全部已出现的 Netty 4.1 模块、Bouncy Castle 三个模块和 Kotlin Gradle Plugin。检查器对旧报告的负向对照如预期失败。最终 APK 构建与 API 29/35 回归仍须通过后才能发布。
- APK 未包含 Netty 或 Bouncy Castle 的运行依赖，不代表构建链可不修复；Guava 确在 APK 运行依赖中，不能仅以未见反序列化调用关闭告警。
- Manifest 使用 HTTPS-only 网络策略；更新 FileProvider 不导出且仅覆盖更新缓存目录。更新实现核对固定仓库来源、声明大小、SHA-256、包名、递增版本码、minSdk 和已安装证书，再交系统安装器确认。本轮不降低这些校验。
- 本地作品和配置使用 JSON/SharedPreferences，未引入 SQL、WebView、上传接口、任意命令执行或服务端授权；没有据此声称所有传递库都安全。
- 原始路径、照片 URI、故障索引均不写入公开日志或 GitHub；损坏索引备份留在应用原有私有偏好设置中，沿用原备份策略。

公告可在 GitHub Advisory Database 按上表 GHSA 编号查询。依赖下限不替代后续公告巡检；正式结果以最终源码 CI、依赖图、安全告警和签名产物验证为准。

### 结果回写（2026-11-21）

- 依赖下限随 PR #7 合入 main 后，GitHub Dependabot 全部 55 条开放告警转为 fixed，开放计数为 0（API 查询确认）；未使用任何忽略或降级扫描手段。
- 签名方案门禁与发布 API 认证由 PR #8、#9 修复；发布 run 44795882120 全部通过，公开 APK 摘要、证书指纹与仓库 `ANDROID_SIGNER_SHA256` 一致（详见 RELEASE_HISTORY.md）。

## 4. 客户端加固无法解决的问题

- MIT 开源客户端不将胶片实现当作秘密；不以混淆承诺防复制或绝对安全。
- 正式签名私钥不得进入 APK/仓库。客户端可验证证书，但无法替代服务端/CI 对私钥、发布权限和审计日志的保护。
- 当前没有付费授权或云端计费接口。本轮不新增长期客户端秘密，也不引入反调试或妨碍正常研究的手段。
- 调试签名预发布与正式签名 APK 不能保证覆盖安装。不得为安装正式版自动卸载旧应用；应保留照片与索引，明确告知签名差异。

## 5. 修复与发布顺序

1. 保留所有用户未提交修改，隔离本轮改动；不导出秘密。
2. 修复作品索引数据安全和非有限参数，补充回归。
3. 验证全部依赖安全下限的实际解析；构建、Lint、单测与 API 29/35 不得跳过。
4. CI 通过且严重适用风险已处理后才合并、打新标签；签名、安装启动、资产和公开下载全部验证后才称正式发布成功。
5. 如兼容性或签名/发布门禁未通过，保留准确进度并阻止发布，不创建空 Release。
