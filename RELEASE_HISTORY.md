# 正式发布历史

以 GitHub Release 和签名资产的真实状态为准，不将标签存在、候选元数据、调试 APK 或未完成流水线称为正式发布。

| 版本 | 状态 | 证据 |
| --- | --- | --- |
| v2.0.0 | 历史调试签名预发布，非正式稳定版 | GitHub 公开资产只有 LUMORA-Camera-2.0.0-debug-test.apk；保留历史标签和资产。 |
| v2.1.0 | 历史标签；正式发布失败 | Actions 的 Publish signed Android APK 未成功；不移动标签、不创建空 Release。 |
| 2.1.1 / code 4 | 元数据已随 PR #6 合并进 main，未打标签、未发布 | 纯 version.properties/CHANGELOG/README/PLATFORMS 准备，被 2.1.2 取代。 |
| **v2.1.2 / code 6** | **正式发布成功（2026-11-21）** | 见下方“v2.1.2 发布证据”。 |

## v2.1.2 发布证据

- Release 页面：<https://github.com/yuchen0x1/LUMORA-Camera/releases/tag/v2.1.2>。`isDraft=false`、`isPrerelease=false`，targetCommitish=main（合并提交 `8b5c6a2`），发布时间 2026-11-20T18:54:35Z。
- 流水线 run 44795882120 全部通过：源检查、verify（构建/Lint、API 29/35 模拟器、汇总门禁）、Build and sign、Install and launch signed APK（smoke）、Publish verified release assets。
- 公开资产三项：`LUMORA-Camera-2.1.2-Android-universal.apk`（50,197,690 字节）、`android-update.json`、`SHA256SUMS.txt`，均带 GitHub 计算的 SHA-256 摘要。
- 独立复核：下载公开 APK，实测 SHA-256 `82ff9a3d3d4ad34b43fc9c214c466b32e5ab23918e5af62585d9c90df0f143dc` 与资产摘要及 `android-update.json` 元数据一致；apksigner 显示签名证书 SHA-256 `c5bec00b0721f580823a9bc9acda5285fa1c5e108ed3798d0ee3bc626c157985` 与仓库 `ANDROID_SIGNER_SHA256` 变量一致；badging 确认 `com.lumora.camera` versionCode 6 / versionName 2.1.2 / minSdk 29 / 非 debuggable。

## 标签移动说明

v2.1.2 标签在本轮内移动过两次后才首次发布成功：第 1 次失败于签名方案门禁（PR #8 修复），第 2 次失败于未认证 GitHub API 限流（PR #9 修复）。三次流水线均未产生或发布任何产物，标签移动不涉及版本重复或产物回退；v2.0.0 / v2.1.0 历史标签始终未动。

## 发布门禁（现行）

- 版本名与版本码必须高于已发布版本；`version.properties` 为版本单源。
- PR 针对最终 head SHA，通过 Android verification 和 Dependency review 后才合并。
- 新标签必须指向已进入 main 的提交；只使用既有 release.yml，不手工上传未验签的替代 APK。
- 发布必须完成 sign、smoke、publish；APK、android-update.json、SHA256SUMS.txt 全部匹配且可公开下载。
- 不删除历史预发布，不声称调试签名安装可直接覆盖为正式签名，不自动卸载应用。
