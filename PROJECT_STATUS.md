# 项目状态

最后更新：2026-11-21（v2.1.2 发布后）。

## 当前状态

- 仓库：`yuchen0x1/LUMORA-Camera`，`main` 位于 `8b5c6a2`（含 PR #6、#7、#8、#9）。
- 正式版本：**v2.1.2 / versionCode 6** 已发布，公开资产（APK、android-update.json、SHA256SUMS.txt）经独立复核，签名证书指纹与仓库变量一致；见 RELEASE_HISTORY.md。
- 开放 Dependabot 告警 0（此前 55）；Dependency review 与 Android CI 在 main 上均为 success。
- 原工作区 `maintenance/release-2.1.1` 的未提交相机、RAW、主题、图库等改动仍属用户未提交工作，未合并、未覆盖、未发布。

## 本轮交付摘要

- 修复损坏作品索引导致图库、设置及观察流异常；读操作不改写索引，重新写入时在同一偏好设置编辑中备份原始损坏文本。
- 将作品和风格设置的非有限强度归一化（NaN/无穷回退 1，有限值限定 0–1）。
- 已知漏洞依赖按公告设置修复下限（Guava、Bouncy Castle、Netty、jose4j、JDOM、commons-lang3、protobuf、httpclient），Kotlin/Compose 插件升级至 2.4.21。
- 修复发布流水线两处首次暴露的缺陷（签名方案门禁、未认证 API 限流）。
- 建立自主维护、安全审查及发布状态记录（本轮引入的 6 个 .md 文件）。

## 验证基线

- JVM 17 用例 0 失败；API 29 仪器 33 用例、API 35 仪器 33 用例 + 平板安全区 5 用例全部通过（本机与 CI 双重验证）。
- lintDebug / lintRelease / assembleDebug / assembleDebugAndroidTest / assembleRelease 通过。
- 依赖解析 22 个受约束模块 0 个低于公告修复版本；负向对照有效。
- 下一步任务清单与遗留风险见 AUTONOMOUS_TASKS.md 和 AUTONOMOUS_ISSUES.md。
