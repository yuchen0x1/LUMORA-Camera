# 正式发布历史

以 GitHub Release 和签名资产的真实状态为准，不将标签存在、候选元数据、调试 APK 或未完成流水线称为正式发布。

| 版本 | 状态 | 证据 |
| --- | --- | --- |
| v2.0.0 | 历史调试签名预发布，非正式稳定版 | GitHub 公开资产只有 LUMORA-Camera-2.0.0-debug-test.apk；保留历史标签和资产。 |
| v2.1.0 | 历史标签；正式发布失败 | Actions 的 Publish signed Android APK 未成功；不移动标签、不创建空 Release。 |
| 2.1.1 / code 4 | 历史候选，未合并 PR #6 | 只含旧候选元数据及说明，不代表已发布。 |
| 本轮新版本 | 尚未发布 | 待最终源码验收、独立提交/PR/CI、合并主分支、新标签与签名资产公开验证。 |

## 本轮发布门禁

- 版本名必须高于已有稳定版本与历史标签，版本码须高于公开版和本地已使用的 5。
- 不包含原工作区未提交的功能变更，不接触发布密钥原文。
- PR 必须针对最终 head SHA，通过 Android verification 和 Dependency review。
- 新标签必须指向已进入 main 的提交；必须使用既有 release.yml，不手工上传未验签的替代 APK。
- 发布任务必须完成 sign、smoke、publish；APK、android-update.json、SHA256SUMS.txt 全部匹配且可公开下载。
- 不删除历史预发布，不声称调试签名安装可直接覆盖为正式签名，不自动卸载应用。
