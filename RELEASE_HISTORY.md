# 正式发布历史

以 GitHub Release 和签名资产的真实状态为准，不将标签存在、候选元数据、调试 APK 或未完成流水线称为正式发布。

| 版本 | 状态 | 证据 |
| --- | --- | --- |
| v2.0.0 | 历史调试签名预发布，非正式稳定版 | GitHub 公开资产只有 LUMORA-Camera-2.0.0-debug-test.apk；保留历史标签和资产。 |
| v2.1.0 | 历史标签；正式发布失败 | Actions 的 Publish signed Android APK 未成功；不移动标签、不创建空 Release。 |
| 2.1.1 / code 4 | 元数据已随 PR #6 合并进 main，未打标签、未发布 | 纯 version.properties/CHANGELOG/README/PLATFORMS 准备，被 2.1.2 取代。 |
| **v2.1.2 / code 6** | **正式发布成功（2026-11-21）** | 见下方“v2.1.2 发布证据”。 |
| **v2.1.3 / code 7** | **正式发布成功（2026-11-21）** | [稳定版下载](https://github.com/yuchen0x1/LUMORA-Camera/releases/tag/v2.1.3) · [发布流水线](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44799665199) |
| **v2.1.4 / code 8** | **正式发布成功（2026-11-21）** | [稳定版下载](https://github.com/yuchen0x1/LUMORA-Camera/releases/tag/v2.1.4) · [发布流水线](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44830235233) |

## 2.1.5 / code 9 候选（源码已入 main，未发布）

- 源码提交 [`592b7694abc265ad6e74c51587540f670fe207b3`](https://github.com/yuchen0x1/LUMORA-Camera/commit/592b7694abc265ad6e74c51587540f670fe207b3) 已快进进入并实际推送 main；`git ls-remote` 返回同一 SHA，没有强推。
- [主线 CI](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44837860635) 的构建/Lint、API 29/35 与 Android verification 全部 success；[Dependency security](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44837860645) success。
- 已显示精确目标、版本、标题和正式稳定发布摘要，请求最终发布确认，尚未收到回答；不视作批准或拒绝。**没有创建 v2.1.5 标签或 Release，没有触发签名发布，也没有正式 APK 可分发。** 当前公开稳定版仍为 v2.1.4。
- 纯文档提交 `334c7de` 的 [CI 44839582512](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44839582512) 随后暴露取消边界测试调度竞态：API 29 的 73 项中 1 项失败；未将该运行称为通过。仅修正测试同步，保留全部回滚断言；本地 API 29/API 35 独立重复分别 50/50、20/20，常规各 73/73、平板 18/18 通过，生产 APK 字节摘要不变。
- 后续测试修正提交 `2cda675` 的 [CI 44841433689](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44841433689) 又在 API 35 既有图库导航暴露 Compose 测试无 Looper 问题；构建/Lint、API 29 和依赖通过，但整体失败。仅显式约束该测试类调度并新增主 Looper 回归；在独占设备双 API 各 20 轮三项专项、常规各 74/74、平板 19/19 通过。生产 APK 字节未变，详见验收报告；不使用失败运行充当通过证据。
- 最终候选为 `e60a4ebabebb699ab1304d59f0ee4a901d633b05`，含两项测试修正，已快进并实际推送 main，远程 SHA 一致；[Android CI 44845826727](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44845826727) 构建/Lint、API 29/35 和汇总全成功，[依赖工作流 44845826747](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44845826747) 成功，两者 headSha 精确匹配。`592b769` 仅作原始修复证据，不作为最终发布目标。后续纯文档证据提交不改变该候选源码或测试。
- 对最终候选以 v2.1.5 / LUMORA Camera 2.1.5 正式稳定发布的最终确认仍未取得，不能因旧目标未获回答而推断批准。没有创建标签、Release 或正式签名资产，当前公开稳定版仍为 v2.1.4。
- 确认后才能新建标签触发既有受保护签名流水线；后续还必须验签、核对公开摘要并验证保留数据覆盖升级。不得重复打旧标签、移动已发布标签或上传本地未签名中间包。
- 真实本地测试、研究、已知问题及交付边界见 [2.1.5 报告](docs/EVOLUTION-2.1.5.md)。

## v2.1.4 发布证据

- 发布源码提交为 `cee74b11903eae0d8da63562e7898c14d3896925`，先快进进入并推送远程 `main`，再创建新注解标签 `v2.1.4`；标签对象为 `cf672109173daf428023b2302c0bf409a094388a`，未移动、复用旧标签。
- [主线 CI](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44830165253)、[依赖检查](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44830165297)和发布流水线全部成功；发布包含构建/Lint、API 29/35 仪器门禁、正式签名、签名包安装启动、草稿和公开资产核验。
- GitHub 确认为最新公开稳定版，`draft=false`、`prerelease=false`，发布时间 `2026-11-20T22:44:55Z`（UTC；本地报告日期为 2026-11-21）。资产均为 uploaded：APK 50,214,074 字节、`android-update.json` 439 字节、`SHA256SUMS.txt` 194 字节。
- 公开下载 APK 的 SHA-256 为 `cd046033b89cc9d5e453feb2900b35534f07ea6439a2accbe1c3a0bcb4c54d0e`，与 GitHub 资产摘要、更新元数据和摘要清单完全一致；元数据本身的 SHA-256 为 `c05ad17d418fad51869375a5bffa5300904baa855fa7df329a3bf610ebae488d`。独立执行 `scripts/release/android.py public --tag v2.1.4` 通过。
- `apksigner verify --verbose --print-certs` 验证 v3、单一 RSA 3072 位证书，SHA-256 指纹 `c5bec00b0721f580823a9bc9acda5285fa1c5e108ed3798d0ee3bc626c157985` 与 2.1.3 及元数据相同。`aapt` 确认 `com.lumora.camera`、versionName 2.1.4、versionCode 8、minSdk 29、targetSdk 35、非 debuggable；`zipalign -c -P 16 4` 成功。
- 独立新建 Android 10 测试环境，先安装公开正式 2.1.3，真实拍摄并收藏一项作品、关闭音量键快门，再以 `adb install -r` 覆盖到公开正式 2.1.4 并冷启动成功。作品仍为“全部 1、收藏 1”，原图/成片均可打开，关闭的偏好保留，两个 JPEG 的 SHA-256 逐字节一致；无卸载、无 `pm clear`。详见 [验收报告](docs/TEST_REPORT.md)。此覆盖安装不等同于真实系统安装器取消的验收。

## v2.1.3 发布证据

- `v2.1.3` 指向主线提交 `5ad9b46d01cd7f4c61883d735c2de1c8c02fe5ac`，未复用或移动旧标签。GitHub 确认为公开稳定版（非草稿、非预览），发布时间为 2026-11-20T19:19:08Z。
- 构建与 Lint、API 29／35 测试、正式签名、签名 APK 安装启动和公开资产校验全部通过。[主线 CI](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44798655565)与发布 run 各自验证了该提交。
- `LUMORA-Camera-2.1.3-Android-universal.apk` 大小为 50,197,690 字节，SHA-256 为 `93b2223637d282dcd6035cfae0727b079ba61ec8af46d6afa5b2f1c79cd53b50`。公开下载的 APK 和 `android-update.json` 匹配 `SHA256SUMS.txt`；apksigner 验证 v3、单一签名证书，指纹与 2.1.2 及更新元数据一致。包名为 `com.lumora.camera`，versionCode 7，minSdk 29，未标为 debuggable。
- 独立 Android 10 模拟器实测从正式 2.1.2 覆盖安装并启动 2.1.3，关闭的音量键快门偏好得到保留；未用历史调试包做覆盖升级。

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
