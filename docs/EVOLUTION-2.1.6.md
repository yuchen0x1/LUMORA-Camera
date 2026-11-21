# LUMORA Camera 2.1.6 / code 10 发布交付文档

## 1. 概览

2026-11-21 发布记录。**2.1.6 已正式公开**（`versionName=2.1.6`、`versionCode=10`、`minSdk=29`），并包含未单独发布的 2.1.5 / code 9 索引保全与更新缓存隔离修复。公开 Release 为 [v2.1.6](https://github.com/yuchen0x1/LUMORA-Camera/releases/tag/v2.1.6)，发布目标为 `0bfcead473a850f7319fbf98193ed8e8cae96fd3`。

## 2. 功能

- 取景器的「关闭」「三分网格」「黄金分割」使用稳定名称持久化，同步写入旧 Boolean 供旧格式兼容；兼容字段不能表达三分网格。设置页提供同样三种选择。
- 相机仍输出 JPEG，RAW 仅为能力提示，不生成 RAW/DNG。未对实体设备的厂商扩展作验收。

## 3. 修复

- 手动对焦请求失效近期 AF 成功缓存；应用 Camera2 选项前主动取消旧测光，避免继续依赖请求前的成功结果。
- 收紧发布元数据的数值类型和范围，新增 19 项离线 Python 回归并接入 CI。保留原有签名与资产校验，未替换更新信任根。
- 针对 Compose UI Test 1.7.6，将相关测试类限定为 `StandardTestDispatcher`，显式等待 IO 完成并检查主 Looper；不改生产调度。
- 复审发现并修复两项边界：进行中点按 AF 的迟到结果，以及其他设置写入覆盖未知网格值；另封堵了同步回滚抛错后迟到原请求成功的路径。最终对焦协调器回归覆盖这些状态。

## 4. 性能与架构

保留短期有效 AF 缓存，仅在请求影响其有效性时失效；没有测得拍摄/显影提速，也没有实体清晰度提升的证据。沿用单 app 模块、CameraX/Camera2、Compose 及现有照片处理管线；测试调度变更不改变生产线程模型。

## 5. 界面

设置 → 摄影辅助 → 构图网格，旧黄金分割开关改为下拉。[API 29 Debug 实际运行截图](screenshots/v2.1.6-grid-settings-api29.png)显示三个选项；截图不是持久化、真机画质或签名升级的证据。

## 6. 调研

[调研记录](RESEARCH.md)保存 JCA、Android camera-samples、GrapheneOS Camera、Fossify Camera 与 Aves 的五个固定源码快照、许可证证据与六维对照。其中 [ImageUtils.kt](https://github.com/android/camera-samples/blob/154c954abc5c80dddf07d264319cf89a3e1ccda9/core-camera/src/main/java/com/android/camera/core/image/ImageUtils.kt#L37-L45) 用 `try`/`finally` 关闭图像。只参考设计，无上游代码或素材移入，无横向性能或画质测试。

## 7. 测试

**准确最终本地快照**：JVM 29/29；Python 3.14 19/19（Python 3.11.18 早期同组也为 19/19）；Debug、AndroidTest、未签名 Release 构建与双 Lint 成功，Lint 双变体均 0 错误、32 警告；API 29/35 常规集各 94/94（不含单独启用的 100 张耐久）；API 35 平板子集 11/11，完成后恢复 1080×2400 / 420 dpi；既往不稳定类重复 5 轮，每轮 14/14。

旧网格实现有 1 项回归失败；旧 Compose 规则在主 Looper 与实际 `null` 的断言中确定性失败；旧 Python 校验器有 28 个失败子断言（不是 28 项测试）。对应修正后的最终本地快照通过。发布前精确提交的 Android CI、依赖检查以及发布流水线也全部成功，详见[验收记录](TEST_REPORT.md) 和[正式发布历史](../RELEASE_HISTORY.md)。

## 8. Git 状态

工作开始时的 `origin/main` 基线是 `2cda675`；隔离工作树先提交实现，再安全合并到 `9b0ae24`，最终发布提交为 `0bfcead473a850f7319fbf98193ed8e8cae96fd3`，已进入并推送远程 `main`，没有强制推送或移动历史标签。原脏工作区未触碰；合并保留了 `e60a4eb` 的定点测试修正和 `9b0ae24` 的 2.1.5 证据。

## 9. 发布与安全

v2.1.6 已进入远程 `main` 并公开发布。发布目标为 `0bfcead473a850f7319fbf98193ed8e8cae96fd3`；[主线 Android CI 44851899943](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44851899943) 与[依赖检查 44851899849](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44851899849) 成功，随后[发布流水线 44852568794](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44852568794) 的所有 job 成功。Release 为非草稿、非预发布，公开资产与独立升级证据见[正式发布历史](../RELEASE_HISTORY.md)。

### 公开资产与签名证据

- APK `LUMORA-Camera-2.1.6-Android-universal.apk`：50,230,458 字节，GitHub SHA-256 `e972446e77e35fdfc7c5da60d626fc4ee5c38797efb2f320dd70b011e706fdb6`。
- `android-update.json`：440 字节，GitHub SHA-256 `4256b15c238b14235face9f148733f4eec1e44ed83b04caeaaf8fa364e85a26e`；`SHA256SUMS.txt`：194 字节，GitHub SHA-256 `27dfeb3ffefe49d64429c4b2a4ef378d29ff54fcfd11c6eac763d839f0c629dc`。`sha256sum -c` 与 `scripts/release/android.py public --tag v2.1.6` 均通过。
- `apksigner` 确认 v3=true、v2=false，单一 RSA 3072 位 `CN=LUMORA Camera Release`，证书 SHA-256 `c5bec00b0721f580823a9bc9acda5285fa1c5e108ed3798d0ee3bc626c157985`。Badging 确认为 `com.lumora.camera`、versionCode 10、versionName 2.1.6、minSdk 29、targetSdk 35、compileSdk 36，未标记 debuggable；`zipalign` 通过。
- 公开签名包的静态暴露面复测为 **98/100（B）**，仅剩 `allowBackup=true` 低风险提示。该扫描是相对排序，不替代威胁建模、密码学验签或真实设备安全评估；DEX 可读性是 MIT 开源客户端的正常属性。

### 覆盖升级证据

独立 API 35 AVD `lumora_release216_upgrade`（`emulator-5558`）先安装公开 v2.1.4，拍摄两张作品并收藏一张、关闭音量键快门，再使用 `adb install -r` 覆盖安装公开 v2.1.6 并冷启动。升级后 versionCode/versionName 为 10/2.1.6，作品库仍显示“全部 2、收藏 1”；第一张作品仍显示已收藏，原图与成片切换均实际打开。设置页仍显示音量键快门关闭、构图网格关闭。四个 JPEG 文件的名称、大小及 SHA-256 升级前后完全一致；未卸载、未执行 `pm clear`。

### 威胁与未解决边界

本轮未新增秘密、未更换签名证书或信任根，也未更改备份策略。`allowBackup=true` 仍是低风险加固候选，是否关闭需评估用户备份照片的预期。独立 100 张耐久、真实硬件厂商扩展/实体清晰度、外部媒体提供方阻塞、系统安装器取消/多窗口生命周期、预览 OOM、EXIF 方向专项、`SharedPreferences.apply()` 异常终止耐久、URI 授权回收与依赖锁定仍未完成，不写成已验证。

## 10. 后续

1. 保留独立的 100 张耐久、实体镜头/厂商扩展、12/48 MP 峰值内存和实体清晰度验收；本轮模拟器与 CI 证据不替代真实硬件。
2. 继续处理[问题清单](../AUTONOMOUS_ISSUES.md)中的相机异步错误、外部提供方阻塞、`SharedPreferences.apply()` 落盘耐久性、预览 OOM、EXIF 元数据与方向、安装器文件租约、URI 授权回收及依赖锁定。
3. 评估 `allowBackup=true` 的备份策略和用户隐私预期；在没有明确数据迁移方案前不改变已发布行为。
