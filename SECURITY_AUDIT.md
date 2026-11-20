# 安全审查

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
