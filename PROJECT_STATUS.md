# 项目状态

最后更新：2026-11-21（本地时间）。

## 本轮范围

- 基线：远程 `main` 的 `d9689bd`；独立工作树 `D:/BuildTemp/lumora-autonomous-20261121`，分支 `autonomous/20261121-update-reliability`。
- 原工作区 `maintenance/release-2.1.1` 的未提交相机、RAW、主题、图库等改动不属于本轮，不暂存、不覆盖、不混入发布。
- 原架构保持：Kotlin / Compose / CameraX / 本地 SharedPreferences 作品索引 / GitHub Actions 签名发布。

## 当前交付

- 修复损坏作品索引导致图库、设置及观察流异常；读操作不改写索引，重新写入时在同一偏好设置编辑中备份原始损坏文本，备份不覆盖旧备份。不会删除媒体文件；没有宣称自动恢复损坏记录。
- 将作品和风格设置的非有限强度归一化为默认值 1，有限值限定为 0–1。
- 增加损坏、重复损坏、观察流、旧记录兼容和强度读写回归测试。
- 依赖安全修复及正式发布门禁正在验证；不得将旧基线构建成功当作安全修复构建成功。

## 外部状态

- 仓库：`yuchen0x1/LUMORA-Camera`，默认分支 `main`。巡检时没有开放 Issue，有 PR #1、#2、#3、#4、#6。
- 现有每小时自主任务已启用，不重复创建。
- 公开资产基线：`v2.0.0` 调试签名预发布。`v2.1.0` 标签已有，但签名发布工作流失败，不能重写标签。
- `android-release` 环境存在签名密钥配置名称及证书指纹变量；未读取秘密值。存在配置不代表已成功签名，须由流水线验证。
- 完整结果与后续事项见 `AUTONOMOUS_TASKS.md`、`AUTONOMOUS_ISSUES.md`、`SECURITY_AUDIT.md` 和 `RELEASE_HISTORY.md`。
