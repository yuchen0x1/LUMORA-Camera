# LUMORA Camera 验收报告

## 2.1.7 / code 11：正式发布与验收（2026-11-21）

**公开稳定版为 v2.1.7 / code 11 / minSdk 29 / targetSdk 35**，发布源码及轻量标签均绑定 `7778eb8a5b43fe7f940ae2ec446bed6e0cc50c98`。发布（ID `453675180`，`2026-11-21T12:27:00Z`）、公开签名资产复核和保留数据覆盖升级是独立结论。历史本地结果及失败不改写。原 E 盘工作树的 `git status -z` 与 binary diff 指纹在修正哈希序列化后均匹配；这不抹去下方设备安装 incident。

### 标签发布流水线与独立报告复核

- [源码 main push CI 44900113421](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44900113421) 成功；它不是 [标签发布 run 44902474570](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44902474570)。标签 run 第 1 次尝试 11 个 job 全部成功，包括复用 CI、CodeQL、受保护签名、签名 APK 安装启动、草稿及公开资产校验。独立证据入口：`D:/BuildTemp/lumora217-tag44902474570-evidence-ea6c694a29/tag-review-summary.json`；同目录保留 `security-review-summary.json`、`verification/instrumentation-review-summary.json`、`verification/build-reports-review-summary.json`。以下指标只属标签 run，不替换 main push 或本地快照。
- **实际 CodeQL 2.27.5**：分析键 `.github/workflows/release.yml:analyze`，ref `refs/tags/v2.1.7`，精确匹配发布 SHA；java-kotlin `2220686804`（76 条规则）、python `2220682858`（43）、actions `2220682811`（17）均 0 results、无 error/warning。2026-11-21 `13:31:28Z`–`13:31:32Z` 完整分页查询：仓库级、`main` 与标签 Code Scanning 开放告警均 0，开放 Dependabot 告警 0；这是时间点快照，不等于无漏洞或已处理依赖 PR。
- **三份 CI 工件**（构建报告 `14705988862`、API 29 `14706647327`、API 35 `14707175657`）均核对归档大小和 SHA-256；报告复核包含 215 个构建断言、117 个仪器协议断言。JVM HTML **53 通过、0 失败、0 ignored**；Debug 和 Release Lint **各 51 Warning、0 Error/Fatal**，不是本地的各 38 Warning。
- **七份原始仪器报告**：API 29 默认 150、真实安装器 1；API 35 默认 151、真实安装器 1、360dp/fontScale 200% 窄屏 1、耐久 1、平板 8。合计 313 次方法 start 与对应 passed status `0`；每份均有正的 `OK (N tests)` 及唯一、末行正常 `INSTRUMENTATION_CODE: -1`，未发出 error/failure/ignored/assumption 状态。该计数不排除用例内部条件提前返回，也不证明实体硬件路径已运行。两端真实安装器均记录 `RESULT_CANCELED` 后释放 retired APK；未在该 fixture 中实际安装。
- **标签耐久**：`previewMs=60814`，完成 100 张、保留 200 JPEG；`medianMs=2316`、`p95Ms=2438`、`maxMs=3066`、`largestSampledPssKb=127252`、`elapsedMs=234391`。PSS 为间隔采样，不是实体机峰值；不作跨版本速度、画质或内存改善声明。

### 独立公开资产与签名核验

恰好三项公开资产的下载大小、SHA-256、GitHub API 摘要及更新元数据/`SHA256SUMS.txt` 的对应条目一致；摘要、证书和发布身份见[正式发布历史](../RELEASE_HISTORY.md#v217-发布证据当前公开稳定版)。独立证据：`D:/BuildTemp/lumora217-public-verified-qq92g04r/evidence/verification.json`。APK 的 apksigner 退出 0、v3=true、单一签名者且证书与 v2.1.6 相同；aapt 身份/非 debuggable 和 16 KiB zipalign 检查通过。独立核验首次以严格证书正则解析时被 Windows CRLF 输出阻断；保存的 apksigner 输出实际为成功，按 `splitlines()` 逐行精确核对指纹后继续，未重新下载、签名或发布。签名包扫描为 98/B，`--fail-on high` 退出 0，只有 `allowBackup=true` 低风险和 3 项信息提示，无高危/严重项；下方 86/B 的 unsigned `APK-SIG` 是历史中间包记录，见[安全审查](../SECURITY_AUDIT.md)。

### 正式包保留数据覆盖升级

- **限定范围通过**（`PASS_SCOPED_PRESERVE_DATA_UPGRADE`，`2026-11-21T13:39:07Z`）：仅在独立 API 35 AVD `lumora_release217_upgrade_722cd4ec` / `emulator-5564` 以一次成功的 `adb install -r` 将公开同证书 v2.1.6 / code 10 覆盖到 v2.1.7 / code 11。安装后 APK 为 50,492,714 B，SHA-256 与独立公开下载一致；`firstInstallTime` 保持 `2026-11-21 10:31:26`，`lastUpdateTime` 更新为 `2026-11-21 13:36:13`（设备记录）。无卸载、清数据、wipe 或重新种入样本；升级意向/结果 marker 均已保留，helper 与 seed seal 未变。发布 SHA 的独立证明不来自旧 seed 中的 `3474251` 意向记录。
- 4 个种入的 `Pictures/LUMORA/` JPEG 的 MediaStore URI/记录元数据及文件 SHA-256 全部一致；图库仍为两张 NATURAL 作品（11:08、11:09），11:08 作品仍为唯一收藏，音量键快门仍为关闭，已检查应用再次启动后的持久性。图库与设置截图 `upgrade-0144-gallery-verified.png`、`upgrade-0107-preferences-volume-false-theme-not-covered.png` 已人工复核。v2.1.6 无主题选择 UI、未种入 LIGHT，`themeMode=null`，主题保留不在验收范围。
- 证据目录 `D:/BuildTemp/lumora217-formal-upgrade-yzesl5wu/harness-evidence/`：`result.json` 为升级结论与边界；`post-success-restoration.json` 记录恢复飞行模式 0、Wi-Fi 1，并回读移动数据 1 与窗口/转场/Animator 1.0/1.0/null（后者原已匹配，无需重写）。初次停止前检查把 PID 0 的 TCP TIME_WAIT 计为外部占用，未发停止命令；复核活动端口所有者后仅停止该专用 AVD。`owned-avd-stop.json` 记录保留 userdata、宿主进程退出、ADB 序列号消失及 5564/5565 无活动占用。下方封存基线与早期失败续接记录保留。
- 不覆盖私有/孤立文件、主题、RAW、导入、编辑、恢复、设备重启、备份恢复、其他 API、实体设备或实体光学画质；也不等同于真实安装器交互、生产下载或多窗口验收。

### 发布前远端阻断与图库测试夹具修正（历史）

- 原提交 `3474251d5e95d0aa1c92d241c9b264c86ac9e3c5` 已快进推送至 main。Android CI `44892891363` 的构建/JVM/Lint job 成功，下载报告为 JVM 53 项、0 失败/错误/跳过，Debug/Release Lint 各 51 Warning、0 Error/Fatal；API 35 默认集在 `GalleryRecoveryAndExitTest` 中报告 `Process crashed`，没有正 OK、方法名或崩溃栈，按失败阻断发布。API 29 原 job 最终 cancelled；精确 job `136813957385` 的 check annotations 明确为超出 1h0m0s 最大执行时间，汇总 Android verification 失败。终态证据保存在 `D:/BuildTemp/lumora217-remote-api29-terminal-hllj8c7z`；没有据此证明具体阻塞方法或把超时当作通过。不能用同期 ColorBuffer 警告断言 API 35 崩溃根因是环境。
- 原提交的 CodeQL `44892891383` 已完成实际 java-kotlin、python、actions 分析，精确 head SHA 均匹配且各 0 results；当轮分页代码扫描与 Dependabot open alerts 均为空。这些安全结果只绑定原提交，不替代新修正的适用门禁。
- 本轮生产源码和八项图库业务断言不变。图库 fixture 使用命名 Standard scheduler、主线程 16ms 帧驱动、Activity 所有的 `GallerySession`，并先关闭 owner、主线程 drain，再清理隔离数据；这修正明确的调度/生命周期缺口，**尚不能证明是远端进程退出的根因**。关闭 owner 不保证非协作 IO 已终止，该清理边界仍保留。
- CI 仪器调用增加 `-w -r`、25 分钟命令超时及有界失败诊断（logcat、exit-info、ANR、内存和窗口）。必须取得正的 `OK (N tests)`，且报告只含一个正常 `INSTRUMENTATION_CODE: -1`、位于最后一条非空输出；命令非零、超时或成功协议不完整仍失败，诊断失败不覆盖原状态。最终实际提取函数的隔离 mock 为 **18/18 场景、307 assertions 通过**，包含真实 awk 的 14 次执行、原 13 项和额外的重复/逆序/失败 terminal、terminal 后输出、末尾纯空白；`bash -n` 与 scoped `git diff --check` 通过。当前脚本 SHA-256 `3ae8f1d9e01d05bd5ec79cd90f511c0afe3be97288086c6b8e78f4f2ed9c2d10`，证据 `D:/BuildTemp/lumora217-emulator-awk-harness-462036c300/results.json`；此前 13/172 证据保留但不冒充当前谓词验证。mock 不等于 Android 执行、实际 GNU timeout 进程终止或远端 CI 通过；每次命令超时不保证整个 job 的累计预算，诊断输出未按字节限额。
- 本轮 STRICT/offline 构建 **成功，2m19s / 129 tasks（9 executed、120 up-to-date）**：`D:/BuildTemp/lumora217-fixture-ci-fix-build-20261121.log`。JVM 随后以 task `--rerun` 独立实际重跑，**53/53、0 failures/errors/skips**，37s / 25 tasks（1 executed）；日志 `D:/BuildTemp/lumora217-fixture-ci-fix-jvm-rerun-20261121.log`。当前本地 Debug/Release Lint XML 各 38 Warning、0 Error/Fatal；不把本地离线与远端 51 条警告的差异宣称为修复。
- 新测试 APK **2,969,211 B**，SHA-256 `1203b55c0ef5b1def7d450d27e836d464a8279c80bc020ca06f1f36e85995313`。Debug 与未签名 Release 的字节数/摘要独立确认均与下表旧生产 APK 完全相同；两台已安装 Debug APK 的设备端摘要也匹配，因此只以显式 serial 覆盖更新测试 APK，未重装生产 app、卸载、清数据或 wipe。新冻结副本及结果：`D:/BuildTemp/lumora217-fixture-ci-fix-qa-n60qg4nj`。
- 新测试包 API 35：图库 **8/8**、完整默认集 **151/151**（JUnit 405.761s）通过，报告分别在 `api35/gallery/` 与 `api35/default/`。API 29：图库 **8/8** 通过；正常重启并确认 SystemUI 状态栏恢复后，完整默认集 **150/150**（JUnit 134.54s、host 136.218s）通过，报告 `api29/default-after-systemui-reboot/instrumentation.txt` 的 SHA-256 为 `7aaf700a6050c244a91237135bb848f43a6c359745705d8c0a89aa887190bd26`。两份完整报告均逐方法核对 start/status 0，分别 151/151 与 150/150，且各有唯一正常 terminal `-1`；不从这些计数推断实体硬件分支零跳过。
- API 29 首次图库运行保留 **6 通过、2 个 RootViewWithoutFocus 失败**。保存的 logcat/window/XML 证明当时有 SystemUI 的 `BOOT_COMPLETED` ANR 弹窗抢焦点；只点击一次实际 Wait 后图库 8/8，但后续完整集仍有 **1 个 LIGHT 时钟像素失败**。截图确认系统时钟完全未绘制，窗口转储的 SystemUI StatusBar 为 `mHasSurface=false`，不是通过降低像素断言解决。仅对准确匹配的本任务 API 29 正常重启、保留 userdata，确认真实状态栏 surface、可见时钟及无 ANR 弹窗后再跑完整集；以上失败原样保留。

- 新包 QA 收尾：API 35/29 均仅停止本任务准确匹配的 AVD，保留 userdata；两宿主最终 exit 0。API 29 停止前读回 fontScale 1.0、物理 1080×2400 / density 420 且无 override、窗口/转场动画 1.0、animator 缺省 null、安装 app-op default；本轮没有父任务几何/动画/网络改写，不虚构执行过额外恢复。此前历史轮 API 35 宿主 exit 1 的记录仍保留，不与本轮混用。
- 正式升级前的独立 2.1.6 基线已封存（此处记录升级前的状态）：`lumora_release217_upgrade_722cd4ec` / `emulator-5564`，正式 2.1.6/code 10、50,230,458 B、APK SHA-256 `e972446e77e35fdfc7c5da60d626fc4ee5c38797efb2f320dd70b011e706fdb6`。两次实际 UI 拍摄保留 4 个原图/成片 JPEG，收藏 1、音量键快门关闭，经应用停止/重启及媒体 URI/文件哈希复核。**正式 2.1.6 没有主题选择 UI，未种入 LIGHT，也不覆盖主题偏好保留。** helper 与 seed seal 已独立核验，manifest SHA-256 `b079b8ff2ad820293ca6b0a916ccb4b602452be639582dfecf61650cbb373f27`；没有 candidate 或 upgrade intent。先前权限/预览 selector 失败与各续接意向记录保留，未重放安装、拍摄或收藏。旧 `3474251` 只作原意向 provenance，不是新候选源码证明。父任务再核设备身份、正式 2.1.6 安装摘要及无活动网络后，仅停止该专用 AVD，保留 userdata 与离线隔离；宿主 exit 0。暂停证据 `D:/BuildTemp/lumora217-sealed-baseline-pause-7fo8uup5`，seed seal 未变。封存时暂不恢复原网络设置，以保持升级前的离线条件；升级后恢复和回读结果见上方。

这是发布前修正与 QA 的证据；此阶段尚无新提交的远端门禁、标签发布或覆盖升级结果。此后的门禁和正式发布见本报告开头。原 E 盘工作树的 binary diff 与 `status -z` 哈希本轮再次匹配保护快照。

### 原提交本地 APK 绑定门禁（旧测试 APK 历史快照）

下文的“当前/最终”仅指该历史快照，不把旧 AndroidTest APK 的结果冒充本轮新测试包的验收。

最终仅清理 `ShutterDiagnostics.kt` 的 EOF 多余空行后，`D:/BuildTemp/lumora217-final-staged-rebuild.log` **BUILD SUCCESS，1m52s / 129 tasks（24 executed、105 up-to-date）**；构建/Lint 通过，`:testDebugUnitTest` 实际重新执行而非 UP-TO-DATE，fresh XML 为 **53 tests / 0 failures / 0 errors / 0 skips**。生产逻辑未改，但 APK 字节比较失败，因此不声称 APK 自此前快照未变；重建前设备结果单独保留，未套用为当前包的通过证据。

不可变副本位于 `D:/BuildTemp/lumora217-final-artifacts-p02m0pif`，身份记录为 `D:/BuildTemp/lumora217-final-artifacts-p02m0pif/artifact-identities.json`：

| 当前产物 | 字节数 | SHA-256 |
| --- | ---: | --- |
| Debug APK | 70,052,474 | `cacfad80438471288c56b2210f6cbc3684a13683fddbd04a24f0ab6c5e6ecd59` |
| 未签名 Release APK | 50,442,871 | `232983df7f9ef902a1565e870b59c50ad2610c55fa051b9c10e32495803f9c8f` |
| AndroidTest APK（字节未变） | 2,965,635 | `ad270be76f7f3d35a8b36787691ec5c7417b8bda68330f503acd06e72886d81e` |

所有以下运行均使用这份精确 Debug APK 与表列 AndroidTest APK；双 API 安装时用 `adb install -r` 保留 userdata，安装后再次以设备端 SHA-256 匹配 Debug APK；仪器报告均检查正的 `OK (N tests)`：

- API 29 默认矩阵：**OK (150 tests)**，119.394s；`D:/BuildTemp/lumora217-staged-final29-default.txt`。
- API 35 默认矩阵：**OK (151 tests)**，192.085s；`D:/BuildTemp/lumora217-staged-final35-default.txt`。
- 真实系统安装器取消：API 29 **OK (1 test)** / 2.256s；API 35 **OK (1 test)** / 3.759s。返回取消后才释放 retired APK，未执行安装；运行前后 `REQUEST_INSTALL_PACKAGES` 均为 `default`。报告分别为 `D:/BuildTemp/lumora217-staged-final29-realinstaller.txt` 与 `D:/BuildTemp/lumora217-staged-final35-realinstaller.txt`；新 API 29/35 系统安装器截图分别保存在 `D:/BuildTemp/lumora217-staged-installer29-b465ujg2` 和 `D:/BuildTemp/lumora217-staged-runtime35-2zgofy_y`，均已读图核实。
- API 35 真实窄屏：720×1920 / density 320（360dp）、fontScale 200%，**OK (1 test)** / 30.856s；LIGHT/DARK 状态栏像素、48dp 和页面操作断言通过。当前产物 8 张 720×1920 截图均已人工复核（设置、设备信息、图库、编辑器各 LIGHT/DARK），保存在 `D:/BuildTemp/lumora217-staged-runtime35-2zgofy_y`；报告 `D:/BuildTemp/lumora217-staged-final35-real-narrow.txt`。
- API 35 耐久：**OK (1 test)** / 323.345s（JUnit）；100 次拍摄保留 200 个 JPEG。fresh metrics 为 `previewMs=61090`、`medianMs=2589`、`p95Ms=2917`、`maxMs=3292`、`largestSampledPssKb=141379`、`elapsedMs=257894`、`thermalStatus=0`；按同一生产进程 PID、真实 `ActivityManager` 启动 epoch 与 junit 运行时长关联，排除了历史 logcat。PSS 是间隔采样值；设备为 1856×1392 模拟器，不证明实体 12/48 MP 峰值。报告与指标分别为 `D:/BuildTemp/lumora217-staged-final35-endurance.txt`、`D:/BuildTemp/lumora217-staged-final35-endurance-metrics.txt`，关联方法见 `D:/BuildTemp/lumora217-staged-runtime35-2zgofy_y/endurance-metrics-provenance.json`。
- API 35 平板：**OK (8 tests)** / 22.579s；1600×2560 / density 280，结束后恢复物理 1080×2400 / density 420。报告：`D:/BuildTemp/lumora217-staged-final35-tablet.txt`。
- Release APK 有界静态复核绑定 SHA-256 `232983df7f9ef902a1565e870b59c50ad2610c55fa051b9c10e32495803f9c8f`、50,442,871 B：`D:/BuildTemp/lumora217-staged-exposure-_5dc_veo/verification-report.txt`。身份 `com.lumora.camera` 2.1.7/code 11/min 29/target 35/compile 36，非 debuggable；FileProvider 仅共享 `cache/verified-update/`，导出接收器要求 `android.permission.DUMP`，备份资源仅列两份偏好 XML。APK 内无签名块/签名成员，故静态扫描唯一高危项为预期 unsigned APK-SIG，`--fail-on high` 返回 1；这是明确的不可分发阻断，**不代表扫描通过或正式包可发布**。原始分数 86/B 为启发式相对值。解压归档 CRC 全项读取，未发现匹配的发布凭证模式或打包私钥/映射文件；pattern scan 不证明绝无编码/未匹配秘密，未执行密码学验签，也未审计服务端信任/运行时行为。最终受保护签名包是不同摘要，必须另行校验、验签与复扫。

两台本任务 AVD 分别以显式 serial 5560/5562 验证 AVD 身份与 API，并在覆盖安装前确认 `adb devices` 无其他设备。运行后恢复本任务原始窗口/转场动画 1.0、animator 缺省（原为 null）、fontScale 1.0、物理分辨率/密度及安装 app-op default；未声称恢复重启前未记录的 night mode。仅停止两个准确匹配的本任务 AVD；未 wipe、卸载、`pm clear`、删除照片或媒体。设备数据保留。API 35 真窄屏新截图及安装器截图均已从本轮新产物设备拉取，未引用重建前图片。


本轮工具恢复与收尾边界：耐久测试先取得 OK，随后采集中文 logcat 时因 Python/Windows 默认 GBK 解码失败，组合命令退出 1，平板步骤尚未开始。修正为显式 UTF-8 后，只重新读取日志，以同一 PID/进程启动时间及 JUnit 时长关联当前指标，再执行平板；没有重拍 100 张或重复已成功的门禁。GitHub 只读认证检查也曾遇到相同默认编码问题，已仅修正该读取。一次版本检查误从原工作区执行而被版本门禁拒绝，改为 ASCII 候选脚本绝对路径后得到 2.1.7/11/29，未修改原工作区。

设备均在设置回读完成后收到明确的 `emu kill` / bye 响应，最终 `adb devices` 为空。API 29 宿主进程 exit 0；API 35 宿主后台任务报告 exit 1，其末段记录 20 秒 graceful-wait 与 snapshot/client 警告，不能宣称两个宿主均正常 exit 0。该宿主退出发生于全部仪器门禁与设备设置回读之后，不抹去已完成的 APK 绑定报告，也未因该警告重启或清除设备。

### 重建前确认结果（历史快照，不是当前新 APK 验收）

- 重建前生产窗口背景同步已修复深色 backing，正常全宽 LIGHT 截图可读；人工固定宽度 floating LIGHT 白图标仍作为独立 caveat 保留。此后除测试/CI 调整外，还清理了生产文件的 EOF 空行；生产逻辑未改不等于源码或 APK 字节未变。
- JVM **53/53、0 失败/错误/跳过实际重新执行**，Debug/Release 构建成功：`D:/BuildTemp/lumora217-final-windowbacking-app.log`。无 FullBackupContent suppression 的严格 Lint 已成功 **2m47s / 71 tasks**：`D:/BuildTemp/lumora217-final-windowbacking-tests-lint.log`；后续 contrast / MATCH_PARENT 测试包构建亦成功。
- 同步修正后专项：API 35 **OK 32 / 81.255s**、API 29 **OK 31 / 52.742s**，包含新增 3 项持久化回归（仓库 1、重建协调 2）。shared Standard rule 尝试前的 API 29 默认集已有两次有效历史通过：`lumora217-candidate-final-default-29.txt` **OK 150 / 104.117s**、`lumora217-final-matchparent-default-29.txt` **OK 150 / 100.561s**；它们不属于最终 frameclock harness；当前重建 APK 的准确最终矩阵现列于本报告开头。下方 148/147 为历史快照，不称为最终结果。
- 真实 Android 安装器取消：`D:/BuildTemp/lumora217-realinstaller-35.txt` **OK 1 / 2.701s**，`D:/BuildTemp/lumora217-realinstaller-29.txt` **OK 1 / 1.859s**。两端均收到同一 UUID 的真实取消 resultCode 0；清理期间 APK 的 FileProvider 读取哈希仍保持，收到结果后才释放并删除，未实际安装，app-ops 均恢复 default。
- 安装器 fixture 使用同版本 APK，经 test client 绕过网络、archive/递增版本门禁；覆盖生产 ViewModel、缓存与真实 ActivityResult 链路，**不覆盖 UpdateDialog 接线、实体设备或多窗口**，不能外推为完整更新验收。该专项使用独立 ActivityScenario，不继承有问题的 shared rule，既有双 API 通过仍独立有效。
- 重建前未签名 APK **50,442,871 B**，SHA-256 `d9fe44e282decf228db4bd2c8fb5727210105e74cbd5033ab06819afe1c486a4`；当时 aapt 确认为 2.1.7 / code 11 / min 29 / target 35 / compile 36、非 debuggable；manifest 为 allowBackup=true、两项备份 XML 引用、cleartext=false。暴露面 86 仅为该历史产物的启发式提示，不是当前新摘要产物重新扫描、可分发或绝对安全证明。
- 共享 `CameraUiTest` 的早期 `StandardTestDispatcher` 尝试曾暴露 `removeObserver` 主线程违规并停滞，失败日志均保留；当轮仅调整共享 fixture 的 frame-clock 驱动。重建前 frameclock 产物的 API 35 真实 360dp、API 29 **150/150**、API 35 **151/151**、平板 **8/8**、100 张耐久及双 API 安装器取消已完成，详细指标见下方历史段。**这些通过不能代替重建后当前新 APK 的独立设备验收；其对应完整重验现记录于上方当前产物章节，也不等于远程 CI、签名或正式发布。**

CI 现在默认排除 opt-in 真实安装器用例，在两种 API 上独立执行并要求 canceled-result 日志标记；API 35 顺序为真实 360dp profile、100 张耐久、平板。新增检查使用实际时钟像素，不只断言系统栏 flags；这些配置变更不等于远程 CI 已运行。

安装器截图仅就上述 fixture 完成独立复核：父任务已拉取并读图确认 API 35 `D:/BuildTemp/lumora217-installer-emulator-5560-3i7tfxmv/real-installer-3cb2a202-1f5b-492b-9d84-5f4088572ed4.png` 与 API 29 `D:/BuildTemp/lumora217-installer-emulator-5562-bm1zchka/real-installer-3ddc1d28-5bfd-4ac0-8c34-04389f64b1b0.png`，均为真实 LUMORA 系统安装器更新确认，Cancel 与 Update/Install 按钮可读。没有点击 Install；测试通过 Back 取消。此截图复核不是生产下载/校验器、完整 UpdateDialog 或 2.1.7 正式签名覆盖升级证明。

### 历史 shared CameraUiTest lifecycle 修正尝试（该轮未通过）

- 本轮只保留命名的 `TestCoroutineScheduler` 与 `StandardTestDispatcher`：base `@Before` 先推进 `mainClock` 一帧，base `@After` 在 `try/finally` 中关闭 scenario 后再由 UI 线程执行 `scheduler.runCurrent`；`awaitPhoto` 仅在 `mainClock.autoAdvance=true` 时推进帧，原有 60 秒超时和断言不变。构建前已移除中间的错误 `+Dispatchers.Main` 方案，未把未验证快照当作证据；没有生产或依赖改动。
- 离线 `assembleAndroidTest` 与双 Lint 构建通过：`D:/BuildTemp/lumora217-lifecycle-tests-lint.log`，**1m46s / 71 tasks（14 executed、57 up-to-date）**，仅有既存的两条 deprecated `barColor` 警告。Debug app SHA-256 `13ce4f413005f4023425b962658dabcb4a40069361f77dc6013cb1efbf267746` 与两台设备已安装字节独立匹配；测试 APK SHA-256 `b5ad86a4d4709d41a5d7347c016cd7ce6c0f73e1dac092f4ab1ef4e3c140bacc` 已显式安装到两台设备。最终未签名 APK 身份哈希仍未改变。
- 运行仍未通过：API 29 仅通过 CreativeState 与两个 Grid 用例（未再出现先前的 `removeObserver` 错误），随后在纯 `SystemClock.sleep` 轮询 burst 停滞；API 35 在真实 360dp、fontScale 200 的 LIGHT 设置页达到可读的深色状态栏时钟，随后停在 `performScrollTo`。诊断截图 `D:/BuildTemp/lumora217-running-narrow-5ze7juqp/screen.png` 仅是该中间状态证据，不是完整窄屏通过。
- 只读 JDWP 栈显示主线程将 `ScrollableNode` 滚动语义排入 `StandardTestDispatcher`，runner 阻塞于 `ActionsKt.scrollToNode -> ThemeTest.font200`。辅助脚本曾先因 Python 语法错误而未执行，修正后取得 main/runner 栈，但短暂第三线程查询遇到 JDWP error 504（不是 HTTP 状态）；随后恢复 VM 并移除 forward。该诊断过程不宣称工具全程成功。
- 两次无效运行的报告 `lifecycle-real-narrow-35.txt` 与 `lifecycle-default-29.txt` 均以 Process crashed / `INSTRUMENTATION_CODE 0` 结束；仅停止本次 QA 的两个应用进程，恢复 `fontScale=1.0`，未清数据、卸载或 wipe。后续帧时钟方案仅在 `mainClock.autoAdvance=true` 时启动/停止 16 ms Android Handler frame pump，并保留 close/drain 与 `autoAdvance=false` 的手动倒计时检查；该方案的最终结果见下节。

### 重建前 frameclock fixture 完成结果（历史产物快照）

以下只对应 EOF 清理前的 Debug `13ce4f413005f4023425b962658dabcb4a40069361f77dc6013cb1efbf267746` 与未签名 Release `d9fe44e282decf228db4bd2c8fb5727210105e74cbd5033ab06819afe1c486a4`；该轮完成后 APK 字节已变化，以下“最终/当前”均仅指当轮，不是当前新产物验收。

- 最终测试 APK 构建：`D:/BuildTemp/lumora217-frameclock-androidtest.log` **BUILD SUCCESS，40s / 49 tasks（5 executed、44 up-to-date）**；双 Lint `D:/BuildTemp/lumora217-frameclock-final-lint.log` **success，44s / 40 tasks（4 executed、36 up-to-date）**，无生产源码改动。测试 APK SHA-256 为 `ad270be76f7f3d35a8b36787691ec5c7417b8bda68330f503acd06e72886d81e`，已显式安装到两台专用设备；生产 Debug/未签名 APK 摘要保持不变。
- 真实窄屏：`D:/BuildTemp/lumora217-frameclock-real-narrow-35.txt` **OK 1 / 38.371s**；实际 720×1920、density 320（360dp）、fontScale 200%，保留 LIGHT/DARK 背景 RGB 与时钟像素、48dp、设置、设备信息、图库、编辑器和保存版本断言。8 张 720×1920 PNG 位于 `D:/BuildTemp/lumora217-frameclock-real360-fdm_5awz`，均已逐张复核，状态栏/对比度/可读性及操作控件正常。
- 当前 API 29：`D:/BuildTemp/lumora217-frameclock-default-29.txt` **OK 150 / 137.728s**，150 次 start 与 150 次 code 0；输出没有 assumption code，仍不外推实体硬件。最终 API 29 安装器取消 `D:/BuildTemp/lumora217-frameclock-final29-realinstaller.txt` **OK 1 / 2.179s**，app-op trap 前后均为 default。
- 当前 API 35：`D:/BuildTemp/lumora217-frameclock-final35-default.txt` **OK 151 / 214.106s**（151 start、151 code 0）；`D:/BuildTemp/lumora217-frameclock-final35-realinstaller.txt` **OK 1 / 3.221s**；`D:/BuildTemp/lumora217-frameclock-final35-endurance.txt` **OK 1 / 352.27s**；`D:/BuildTemp/lumora217-frameclock-final35-tablet.txt` **OK 8 / 28.45s**。这些结果与 API 29、真实 360dp 结果共同构成当前本地 frameclock 验证，不替代实体硬件验收。
- 当前耐久指标来自按本次运行开始时间过滤的 `D:/BuildTemp/lumora217-frameclock-final35-endurance-metrics.txt`，不是旧 PASS 快照：`previewMs=61504`、`completed=100`、`retainedJpegs=200`、`medianMs=2793`、`p95Ms=3164`、`maxMs=3476`、`largestSampledPssKb=138742`、`elapsedMs=286774`、`thermal sampled=0`。仅在 1856×1392 模拟器采样；PSS 不是真实峰值，本 fixture 含受控时钟驱动，不作跨版本性能、画质或内存改善声明。
- 失败轮次仍按原样保留；本轮仅停止名称精确匹配的任务专用 5560/5562 AVD，恢复 `window_animation_scale` / `transition_animation_scale=1.0`，删除原本为 null 的临时 animator 全局键，确认 1080×2400、density 420、无 override、fontScale 1.0 与 app-op default。未声称 night mode 被恢复；未 wipe、卸载或 `pm clear`，样本和 userdata 保留。两台设备随后仅被安全停止。
- 当轮本地门禁已取得结果；之后 EOF 清理重建导致 Debug/未签名 APK 摘要变化，故重新验收新产物。该历史快照早于远程最终 CI、实际 CodeQL/告警、正式签名及公开资产和覆盖升级验收；后续结果见本报告开头，不回填到本轮。

### 历史阶段结果（保留失败与已有通过）

| 检查 | 该阶段实际结果 | 当时范围与边界 |
| --- | --- | --- |
| 发布脚本 / 静态检查 | 最终离线 **19/19**；此前 CI 脚本 `bash -n`、`git diff --check` 通过 | `D:/BuildTemp/lumora217-release-script-final.log`；早期 Python 3.14 同组 19/19 保留，不冒充远程工作流执行 |
| 系统栏改动前 STRICT no-write 全量构建 | **129/129 任务通过** | 属于 pre-bar-fix 快照，不能冒充后续系统栏改动后的全量通过 |
| 系统栏改动后全量 STRICT | **运行时中断，exit 1073807364** | 不是编译器诊断，也不计为成功；随后分段验证如下 |
| 分段 STRICT：JVM / AndroidTest 打包 | **BUILD SUCCESSFUL，56 tasks，1m48s；JVM 报告 53/53、0 失败/错误/跳过** | `D:/BuildTemp/lumora217-final-jvm-androidtest.log`；其中 `testDebugUnitTest` 为 UP-TO-DATE，不冒称该分段重新执行了 53 项 |
| 分段 STRICT：Debug / Release Lint | **BUILD SUCCESSFUL，40 tasks，22s** | `D:/BuildTemp/lumora217-final-lint.log`；不声称零警告或全部分析重新执行 |
| 分段 STRICT：Debug / Release 打包 | **BUILD SUCCESSFUL，83 tasks，33s** | `D:/BuildTemp/lumora217-final-apk.log`；Release 仍为本地未签名中间包，不可当作正式分发 |
| 最终官方来源 SHA-256 审计 | **919/919 匹配，0 mismatches / 0 bypasses** | `D:/BuildTemp/lumora217-verification-tools/official-checksum-audit.final.json`：526 sidecars + 28 GMM + 365 fresh downloads；校验元数据 SHA-256：`SHA800c2a465d9b37c1495a35347c9e92662d85113995b0939c8f54702d740c4b5e`（原始 Windows-CRLF 审计快照）；提交 Git-LF 归一化摘要 `cd23b0bdc4638ff9235e931085591f7d9a9a350581448f30bd0d56dd44209352`，仅换行差异且 919 条 XML 条目未变；不是 APK 摘要、漏洞扫描或 CodeQL 结论 |
| 备份 / 网络规则与 Release manifest 范围 | **已有产物范围核验通过；规则清理结构断言通过；修改后最终 Lint/产物待复核** | `allowBackup=true`、两项备份 XML 属性、HTTPS-only；三种模式均仅白名单允许 `lumora_preferences.xml` / `lumora_updates.xml`，日志隐式排除。宽泛 FullBackupContent suppression 与三条冗余 exclude 已移除，无 suppression 的结构断言通过，不宣称实际迁移已验收 |
| 历史 API 35 常规仪器 | **OK，148 tests，178.016s** | 专用 `emulator-5560`；`D:/BuildTemp/lumora217-final-instrumentation-35-default.txt`，默认集排除独立耐久测试 |
| API 35 平板 SafeArea | **此前快照 OK，8 tests** | 模拟器平板安全区子集，不替代仍在修正的窄窗口系统栏视觉验收 |
| API 29 首次常规仪器 | **147 tests / 1 failure** | 专用 `emulator-5562`；唯一失败为 `CameraThemeTest.font200…`（用例名缩写），ActivityScenario teardown 时 Activity 为 `DESTROYED`；保留首次生命周期 flake，不记作首轮通过 |
| API 29 主题隔离重放 | **OK，3 tests** | 实际重放 `CameraThemeTest`，随后完整重跑亦 OK 147；不抹去首次失败 |
| 历史 API 29 常规仪器 | **OK，147 tests，113.364s** | `D:/BuildTemp/lumora217-final-instrumentation-29-default.txt`；完整运行，不把隔离重放冒充完整通过 |
| 历史 API 35 显式耐久 | **OK，1 test，299.108s；metrics PASS** | `D:/BuildTemp/lumora217-final-endurance-35.txt`；`endurance=true`、`previewSeconds=60`，实际 `previewMs=60380`；完成 100 张并保留 200 个 JPEG，详见下表 |
| UI 视觉复核 | **42 个截图文件已拉取并复核；系统栏问题仍开放** | 本机 `D:/BuildTemp/lumora217-uiux-35`；浅色/深色、大字体、设置均已查看；窄窗口白色状态栏仍可见，不能宣称已修复 |
| 专用模拟器状态 | API 35 `emulator-5560` / API 29 `emulator-5562` 均已安装 Debug APK 与测试 APK | 已用于上述实际仪器测试，不代表正式签名覆盖升级；OK 总数不能证明硬件分支零跳过 |

前一轮耐久指标已只读核对 `D:/BuildTemp/lumora217-final-endurance-metrics-35.txt`；文件名虽含 final，以下仅属该历史快照，不能与上方当前重建 APK 的耐久结果/指标混用。保留日志字段与单位，PSS 为间隔采样最大值，非瞬时峰值或实体机结论：

| 字段 | 该轮实际结果 |
| --- | --- |
| metrics | PASS |
| completed | 100 |
| retainedJpegs | 200 |
| medianMs | 2311 |
| p95Ms | 2526 |
| maxMs | 2546 |
| largestSampledPssKb | 134458 |

此前另一轮次报告的 median 1981 / p95 2175 / max 2193 / largestPss 135472 为早期数据，不与上述最终文件混用，也不据此主张跨版本提速。

### 窗口背景修正与真实窄屏验证经过

最初深色 backing 的根因为 Compose `#171918` 与窗口背景 `#F8F9F5` 不同步，生产同步修正已解决这一 backing 问题；正常全宽 LIGHT 截图可读。人工固定宽度 floating LIGHT 截图仍有白色图标，是独立的窗口形态 caveat，不能据此否定已确认的 backing 修正，也不能宣称所有系统栏已完美。

1. `D:/BuildTemp/lumora217-real-narrow-35.txt` 首轮真实窄屏失败于 LIGHT 时钟像素对比度。测试将原本等宽的 MATCH_PARENT 窗口强行改为 fixed px；保留此失败，不计为通过。
2. 修正测试为已有宽度 <=361dp 时保留 MATCH_PARENT，几何断言不变。`D:/BuildTemp/lumora217-real-narrow-matchparent-35.txt` 第二轮已到达图库，随后因 Compose 测试 Unconfined 在 IO 线程重组、无 Looper 而失败；**这是测试调度问题，不是生产 dispatcher 变更**。
3. 共享 CameraUiTest 参照既有 CameraPermissionNavigationTest 惯例尝试 StandardTestDispatcher，随后 API 29 运行暴露 Activity lifecycle 的 removeObserver 必须主线程调用，API 35 真实窄屏运行停滞；该尝试不计为已验证修复，正在审查 Compose 1.7.6 rule 实现。
4. 仅停止专用 5560/5562 上本次 QA 应用进程（am force-stop），恢复原 fontScale=1.0；没有清数据、卸载或 wipe。`final-scheduler-default-29` 在已记录 removeObserver 违规之后，经此显式停止以 Process crashed / INSTRUMENTATION_CODE 0 结束，不是通过。后续独立查询两台 QA 设备：应用无 PID、1080×2400 / density 420 且无 override、font 1.0、night no、安装 app-op default；这些仅是当时环境基线，不抹去失败日志。

上述失败与后续修正及当前人工 fixed-width caveat 分开记录；准确重建后门禁已在本报告开头独立列示，不能将早期 148/147 或历史耐久作为当前新 APK 的证据。

设备安装 incident：完整读取 `D:/BuildTemp/lumora217-install35.log` 后确认，第 44–47 行的未限定设备 Gradle `installDebug` 曾向既有 `lumora_release216_upgrade(AVD) - 15` 尝试安装；Android 因 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（证书不匹配）拒绝，第 224–239 行为任务失败及 BUILD FAILED。没有成功替换旧安装，**不能声称旧设备从未被尝试操作，也不能仅凭拒绝就证明所有旧设备状态均未改变**。之后安装均显式指定专用 `-s emulator-5560` / `-s emulator-5562`；这不是正式签名覆盖升级证据。

早期失败与阶段性证据仍保留：首次 STRICT 构建因延迟配置 `androidApis` 缺锁失败；首次 bootstrap 在两处更新器 API 调用点编译失败。修正后第二次 bootstrap 实际 JVM 53/53（15 suites，XML `2026-11-21T04:25:52Z`，含 CaptureWorkQueue 4、CaptureRecovery 6、ManualRestore 3、BitmapBudget 6）通过；该阶段不是最终 STRICT，最终结果见上表。早期来源审计为 879/879（500 sidecars + 28 GMM + 351 次新鲜 HTTPS 下载），当时校验元数据 SHA-256 为 `492088dba5e7435969b91c951ca13c32be5dea4cee4692319f67399f58339cb3`；最终审计已扩展至 919/919，不混用两个快照。

候选范围：迁入 RAW/主题/安全区、仅预览 OOM 降级、PRO 可保存状态、进程所有的有界拍摄/渲染队列与持久日志（检查写入、移除墓碑、重建协调）、显式且仅新增的索引恢复预览/隐私提示/错误处理；DocumentFile 1.1.0、可信来源的新 Gradle 9.1.0 Wrapper、STRICT 锁与依赖校验、两份偏好 XML 备份白名单及云备份/设备迁移排除拍摄日志。上述本地通过只证明所测范围，不保证完整损坏恢复、任意异常终止或设备迁移行为。

原提交 `3474251` 的远端构建/JVM/Lint 和实际三语言 CodeQL 通过，但 Android CI 因 API 35 进程退出及 API 29 一小时超时而失败。发布前图库 fixture / CI 诊断修正后，本地生产 APK 字节未变，新测试 APK 的双 API 图库与完整默认集通过；360dp、平板、100 张耐久与安装器取消是旧测试 APK 的独立历史通过，不冒充新测试包的本地重跑。最终标签流水线另有七份独立复核报告，见本报告开头。历史未签名 APK 的 86/B 与预期 `APK-SIG` 高项只绑定该不可分发中间产物；公开签名包结论见上方与[安全审查](../SECURITY_AUDIT.md)。

UpdateDialog 接线、真实云备份迁移、实体安装器与多窗口、持久 URI 授权引用回收、外部提供方阻塞、实体 RAW/厂商 AF/扩展和 12/48 MP 峰值内存继续开放。安装器取消 fixture 不覆盖真实下载及递增版本门禁；人工 fixed-width floating LIGHT 白图标 caveat 仍保留。不得从模拟器 OK 总数推断零硬件跳过，不声称全部积压关闭或跨版本性能/画质改善。`scripts/build-local.ps1` 仅过 PowerShell 语法解析，未运行脚本的完整 Wrapper 流程。

## 2.1.6 / code 10：最终本地验证与正式发布（2026-11-21）

下表针对本地合并提交 `6497ad1` 中包含 KTX setter、点按 AF 竞态封堵、同步回滚失败隔离及未知网格值保护的**准确最终源码快照**执行；该提交随后进入 main 并作为 2.1.6 的发布基线。

| 检查 | 最终本地结果 | 范围 |
| --- | --- | --- |
| JVM | **29/29**，0 失败、错误、跳过 | 本地 `testDebugUnitTest` |
| 发布元数据 Python | Python 3.14 **19/19**；Python 3.11.18 早期同组 **19/19** | 离线，不发起发布或网络验收 |
| 构建 | Debug、AndroidTest、未签名 Release 成功 | 最终重跑，未签名 APK 不可分发 |
| Lint | Debug、Release 均 0 错误、**32 警告** | 最终重跑；未压制既有警告 |
| 常规仪器测试 | API 29 **94/94**，API 35 **94/94** | 最终 Debug/test APK；不含单独启用的 100 张耐久测试 |
| 平板子集 | API 35 **11/11** | 1600×2560 / 280 dpi，结束后恢复 1080×2400 / 420 dpi |
| 既往不稳定类重复 | **5 轮，每轮 14/14** | 早期候选的专项证据，不将有限重复当作长稳证明 |

旧实现负向对照：旧网格实现的回归 1 项失败；旧 Compose 规则在主 Looper 与实际 `null` 的断言上确定性失败；旧 Python 校验器产生 28 个失败子断言（不是 28 个独立测试）。对应修正后的最终本地快照通过。真实 API 29 Debug [设置下拉截图](screenshots/v2.1.6-grid-settings-api29.png)只证明界面显示，不证明签名升级通过。

精确提交 `ed5ddc4` 的远程 Android CI 44851133527（构建/Lint、API 29、API 35、汇总）和依赖检查 44851133546 已通过；最终发布提交 `0bfcead` 的 [Android CI 44851899943](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44851899943)、[依赖检查 44851899849](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44851899849) 与[发布流水线 44852568794](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44852568794) 同样全部成功，v2.1.6 已公开为非草稿稳定版。签名/身份/大小/摘要核验及从公开 v2.1.4 的保留数据覆盖升级已完成，见下节。未运行独立 100 张耐久；未验证真实硬件的厂商扩展或物理清晰度，无 RAW 输出、无实测速率提升。[完整交付文档](EVOLUTION-2.1.6.md)记录范围与安全边界。

### 2.1.6 公开资产与保留数据覆盖升级（独立核验）

| 检查 | 实际结果 |
| --- | --- |
| 公开 Release | `v2.1.6`，`draft=false`、`prerelease=false`，发布时间 `2026-11-21T02:00:37Z`，目标提交 `0bfcead473a850f7319fbf98193ed8e8cae96fd3` |
| 公开资产 | APK 50,230,458 B（`e972446e…e706fdb6`）、`android-update.json` 440 B（`4256b15c…e85a26e`）、`SHA256SUMS.txt` 194 B（`27dfeb3f…c629dc`）；`sha256sum -c` 与 `android.py public --tag v2.1.6` 通过 |
| 签名 | `apksigner` v3=true、v2=false；单一 RSA 3072 位 `CN=LUMORA Camera Release`，证书 SHA-256 `c5bec00b0721f580823a9bc9acda5285fa1c5e108ed3798d0ee3bc626c157985` |
| 身份 | `com.lumora.camera`、versionCode 10、versionName 2.1.6、minSdk 29、targetSdk 35、compileSdk 36，无 `application-debuggable`；`zipalign -c -P 16 4` 通过 |
| 暴露面扫描 | 公开签名包 98/100（B），仅 `allowBackup=true` 低风险；相对评分，不是绝对安全等级 |
| 覆盖升级 | 独立 API 35 AVD `lumora_release216_upgrade`/`emulator-5558`；v2.1.4 → v2.1.6 `adb install -r`，作品“全部 2、收藏 1”保留，原图/成片可打开，音量键快门关闭与网格关闭保留 |
| 媒体字节 | 4 个 JPEG 升级前后 SHA-256 一致（`99e0f7de…`、`cf4d6fa0…`、`03bfbbd6…`、`0218fbd0…`）；未卸载、未 `pm clear` |

ADB 覆盖安装不等同于真实系统安装器交互/取消或多窗口生命周期验收；该 AVD 与用户其他设备相互独立，未清理任何既有 QA 设备。

## 2.1.5：局部索引保全与更新缓存隔离（2026-11-21 本地 / 2026-11-20 UTC）

| 检查 | 实际结果 | 范围 |
| --- | --- | --- |
| 主线基线 JVM | 23/23 | 修改前运行 |
| 旧实现负向对照 | 四个新用例全部如预期失败 | 部分合法数组中的不可读条目在重写后未保留原文；失败原因均为恢复快照缺失 |
| 最终 JVM | 23/23，0 失败、错误、跳过 | 同一 2.1.5 / code 9 候选 |
| 构建 | Debug、AndroidTest、未签名 Release 全部成功 | JDK 21，离线缓存，单 worker、1 GiB Gradle 堆；3 分 2 秒 |
| Lint | Debug/Release 各 0 错误、32 警告 | Debug 警告 ID/消息集合与上版一致；未压制门禁 |
| API 29 专项 | 25/25，0.629 秒 | 照片仓库 14 项及真实缓存文件事务 11 项 |
| 最终 API 29 常规集 | 74/74，27.175 秒 | 隔离 AVD；原 58 项、新增 15 项及 1 项主 Looper 回归 |
| 最终 API 35 常规集 | 74/74，49.756 秒 | 同一生产 APK 与最终测试包，独立端口 |
| 最终 API 35 平板 | 19/19，21.882 秒 | 1600×2560 / 280 dpi；安全区、三项权限/线程测试及更新恢复；恢复隔离设备原 320×640 / 160 dpi |
| 启动与运行 | API 29 冷启动 Status: ok，versionName 2.1.5 / code 9 | 取景已进入可拍状态；[真实截图](screenshots/v2.1.5-camera-api29.png)；两台设备本轮 crash buffer 未见 app 崩溃，不是长稳证明 |
| 依赖巡检 | 开放 Dependabot 告警 0 | 当前快照不等于全部库绝对安全 |
| 远程源码 CI | 构建/Lint、API 29/35 与汇总门禁全部 success | [主线 run 44837860635](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44837860635)，headSha 为源码提交 592b7694abc265ad6e74c51587540f670fe207b3 |
| 远程依赖检查 | success | [Dependency security run 44837860645](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44837860645)，同一源码提交 |
| 正式包/公开资产/保留数据升级 | 未执行，最终发布确认尚无回答 | 未创建 v2.1.5 标签或触发签名发布；未签名中间包不得作为正式安装资产 |

2.1.5 初次生产修复验收的历史结果：API 29 常规 73/73（33.065 秒）、API 35 常规 73/73（57.424 秒）、API 35 平板 18/18（22.683 秒，验收后恢复 1080×2400 / 420 dpi）。下方分别记录后续两项测试同步修正。

新增索引回归覆盖完整原文、只读/无变化不写、收藏、移除撤销、重复损坏、跨仓库并发且只备份一次。文件用例调用生产 `UpdateCacheFiles`、真实内部存储及 FileProvider，覆盖跨客户端 Ready 文件与已发 URI、部分文件不可暴露、提升交错、重复下载、失败/取消/迟到交付清理、目标碰撞不覆盖、孤儿文件不认领/不清扫。测试 fixture 是文本，不是已签名 APK；未将它们当作真实联网下载、密码学负例或系统安装器交互验收。

完整构建日志与仪器原始输出保留在本机 `D:/BuildTemp/lumora-215-*.log` / `lumora-215-*.txt`；测试和 Lint XML 在本轮工作树 `app/build`。旧实现四失败是预先设计的负向对照，不是被隐藏的最终失败；最终本地轮次全部通过。上游审查代理曾遭遇连接中断/502，恢复后完成，不将失败调用算成功。

静态扫描先对公开 2.1.4 留存基线，再扫描未签名候选；新增 APK-SIG 高风险是未签名中间产物属性，禁止分发。allowBackup 仍为低风险策略候选，具体导出能力取决于 Android 版本与策略；扫描器不代替 apksigner。正式签名需走既有受保护环境和公开核验。详见 [完整交付与安全报告](EVOLUTION-2.1.5.md)。

本轮未执行独立 100 张耐久、真机传感器/ISP/扩展、12/48 MP 峰值内存、真实网络断流即时取消、系统安装器取消/多窗口会话和正式覆盖升级，不声称速度/画质提升。后续正式发布结果单独追加，不复用旧版证据。

### 文档提交 CI 暴露的测试调度竞态及修正

- 文档提交 `334c7de8022fed577af826a69908739a7bb12459` 的 [Android CI 44839582512](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44839582512) 首次失败：API 29 共 73 项中 1 项失败，`cancellationAfterPromotionBeforeIoReturnRollsBackOnlyThatAttempt` 在等待返回任务时得到 null。构建/Lint、API 35 和独立依赖检查通过；没有将整个运行称为通过，也没有直接重跑碰运气。
- 原测试假定 `withContext(IO)` 必然挂起并分发返回任务；IO 若在外层提交挂起前完成，可以直接返回。现用 `finishVerification` 屏障阻止过早完成，待 `launch(UNDISPATCHED)` 返回后才放行，并以 `returnQueued` 协作等待任务入队，保留外层 10 秒超时。提升可见、取消后删除本次文件、保留旧路径与字节的断言全部保留，另增加任务确实取消的断言。生产实现、依赖、版本及安全校验均未改变。
- 修正后 API 29 取消边界测试独立运行 **50/50**，API 35 **20/20**；随后完整常规集分别 **73/73（30.269 秒）**、**73/73（56.093 秒）**，API 35 平板 **18/18（28.309 秒）**。独立静态审查未发现同步、取消窗口或清理遗漏；屏幕设置随后恢复。
- 本地构建命令再次成功（57 秒，8 项执行、121 项 up-to-date）；JVM 23 项及双 Lint 0 错误/32 警告为未变化任务的既有报告，不冒充再次实际执行。Debug 与未签名 Release 的 SHA-256 均与初次验收逐字节一致；新测试 APK 为 2,501,719 字节、SHA-256 `41983715b7d3c7819d925a13d250ba0c5fcbc55cdb5731625186c616941fbcbe`。
- 原始输出为本机 `D:/BuildTemp/lumora-215-test-gate-*`。最终候选须包含本次测试同步修正，并独立通过其精确提交的远程 CI，不能用原始源码提交的成功替代。正式发布仍未执行。

### 后续 API 35 CI 的 Compose 测试线程约束

- 测试同步修正提交 `2cda675` 的 [CI 44841433689](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44841433689) 中，构建/Lint、API 29、取消边界测试及依赖检查通过；API 35 常规 73 项中 1 项失败。失败为既有无相机权限图库导航：`Choreographer.getInstance()` 报当前线程没有 Looper，调用栈含测试帧时钟、测试 interceptor 及仓库流的 IO worker。没有将该运行写成通过，也未直接重跑成功步骤。
- 核对 [Compose UI Test 1.7.6 官方源码包](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test-android/1.7.6/ui-test-android-1.7.6-sources.jar) 的 `ComposeUiTest.android.kt:233–236`、`MainTestClockImpl.android.kt:23–30` 及 `AbstractMainTestClock.jvm.kt:64–73`：默认 Unconfined 测试调度器可在唤醒线程继续；可传入的 Standard 测试调度器由 Compose 时钟在 UI 线程推进。仅为 `CameraPermissionNavigationTest` 显式传入 `effectContext = StandardTestDispatcher()`，不改生产 flowOn、依赖或线程模型，不关闭 LazyGrid 预取，不提高原超时或弱化导航断言。
- 新增真实仓库流回归：初始及更新回调必须在主 Looper；写入动作明确在没有 Looper 的 IO 线程。原两个导航测试方法体与断言不变；该类由 2 项增至 3 项，常规总数由 73 增至 74，平板子集由 18 增至 19。
- 首次共享 API 29 设备的第 10 轮返回“Process crashed”，未获得对应应用崩溃栈；同期日志出现本轮未构建的 AF/网格及另一名称的线程回归，存在测试包/设备并发使用证据。这轮不计作通过，也不归因为补丁崩溃。停止使用共享设备后，仅用已安装镜像创建独占 AVD `lumora215_compose29_6f39` / `lumora215_compose35_6f39`，端口 5560 / 5562；不再停止、安装或清理其他会话使用的设备。
- 两个隔离环境各完成 **20/20 轮三项导航/线程测试（各 60 次执行）**，再完成 **API 29 74/74（27.175 秒）**、**API 35 74/74（49.756 秒）**、**平板 19/19（21.882 秒）**。原始日志前缀为 `D:/BuildTemp/lumora-215-compose-gate-`。构建成功（1 分钟，9 项执行、120 项 up-to-date），生产两个 APK 摘要不变；最终测试 APK 为 2,619,627 字节、SHA-256 `ced8458b6962fa5d91c7cb1e62adafa11c9f20e7ee664e8995305df4fc537fc4`。
- 没有证据要求改动生产实现，也未将只在 API 35 命中等同于已证明平台缺陷。最终远程门禁仍须关联包含两项测试修正的精确提交；正式发布、签名及覆盖升级仍未执行。

### 最终主线远程门禁

最终候选提交为 `e60a4ebabebb699ab1304d59f0ee4a901d633b05`，已安全快进并实际推送 main，远程 SHA 精确一致。[Android CI 44845826727](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44845826727) 的构建/Lint、API 29、API 35 与 Android verification 全部 success；[Dependency security 44845826747](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44845826747) success（push 事件中的 Dependency review 按既有条件跳过，依赖图提交成功，不冒称 PR 审查已运行）。两项工作流 headSha 均精确匹配该候选。正式发布、签名公开资产及保留数据覆盖升级仍未执行；后续纯文档证据提交不改变上述候选的应用源码或测试。

## 2.1.4：更新失败恢复（2026-11-21）

| 检查 | 最终结果 | 范围 |
| --- | --- | --- |
| JVM | 23 项，0 失败 | 新增 6 项调度策略：成功周期、15 分钟失败退避、边界、首次/手动/时钟回退。 |
| 构建 | 全部成功 | Debug、AndroidTest、未签名 Release；JDK 21，离线缓存，单 worker、1 GiB Gradle 堆。 |
| Lint | Debug/Release 均 0 错误、32 警告 | 未删除或降级门禁；新增偏好写入带来一条 KTX 建议，其余含依赖/现代 API 建议。 |
| Android 10 / API 29 | 常规仪器 58/58 | 包含原 35 项与新增 12 项协议、11 项状态/UI；39.440 秒。 |
| Android 15 / API 35 | 常规仪器 58/58 | 同一最终候选包；67.168 秒。 |
| API 35 大窗口 | 18/18 | 1600 × 2560、280 dpi；安全区、无相机权限导航、更新恢复；28.812 秒，随后恢复屏幕配置。 |
| 真实网络手测 | 通过 | API 29 真正断开 Wi-Fi/移动网络，点击检查显示错误，恢复两种网络后立即点击“重新检查”得到无新版本提示。[真实运行截图](screenshots/v2-update-recovery.png)。 |
| 依赖巡检 | 开放 Dependabot 告警 0 | 2026-11-21 查询；未声称所有传递库绝对安全。 |

### 首次运行故障与重试

- API 29 首次完整运行：58 项中 1 项失败。音量键测试遇到 `INJECT_EVENTS` SecurityException；`dumpsys window` 明确显示 `Application Not Responding: com.android.systemui` 抢占焦点。保留首次失败日志，重启模拟器但不清除应用数据，完整重跑 58 项通过。
- API 35 首次运行在第 50 项期间进程崩溃，crash buffer 为系统 `libhwui.so` 的 `AnimatorManager::pushStaging` / RenderThread SIGSEGV，不能计作通过。将测试动画与 CI 的关闭配置对齐，更新专项 23 项通过，再完整重跑 58 项及平板 18 项通过。没有通过禁用生产硬件加速或改动应用功能掩盖异常。
- 完整原始日志位于本轮独立工作树 `build/instrumentation-214-*.txt`，APK 与 JVM/Lint 报告位于 `app/build`；这些机器生成文件不纳入源码提交。[主线 CI](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44830165253) 与 [正式发布流水线](https://github.com/yuchen0x1/LUMORA-Camera/actions/runs/44830235233) 已再次通过构建、Lint 和双 API 质量门禁。
- 公开资产复核首次使用 PATH 中的 Python 3.8.6，在 `hashlib.file_digest` 处失败；定位为解释器版本不兼容，改用已安装 Python 3.14 后原脚本通过。未改动脚本、重复发布或重新上传资产。

### 安全与范围边界

- 协议测试覆盖稳定版选择、数值版本排序、重复/未上传资产、固定 URL、大小/摘要、包名/协议/版本码/SDK/证书格式及 HTTPS 重定向负向输入。ViewModel 测试使用独立偏好命名空间和伪更新客户端，不把它等同于真实 APK 证书或生产阻塞 I/O 的完整验收。
- 初始静态暴露扫描针对本地未签名 Release，未签名高风险是该中间产物的预期属性，未发布此文件。另对公开正式 APK 复测并与该基线比较，签名缺失项消失、allowBackup 低风险策略候选仍在、无新增项。扫描器仅识别签名块，其 v2/v3 摘要不作密码学结论；以 apksigner 的 v3 验证成功、v2=false 为准。MIT 开源 DEX 可读性不应被包装为可保密或绝对安全。
- 本轮未重跑 100 张独立耐久套件，未取得厂商扩展、实体画质/峰值内存、真实安装器取消和生产断流取消的完整实测证据。不声称拍摄、显影性能提升。

### 正式包公开下载与保留数据升级

- 公开稳定版 `v2.1.4`，发布提交 `cee74b11903eae0d8da63562e7898c14d3896925`。发布时间 `2026-11-20T22:44:55Z` 为 UTC，本地记录日期为 2026-11-21。发布 run `44830235233` 全部门禁通过，未手工上传替代 APK。
- 下载到全新空目录后，APK 实测 50,214,074 字节、SHA-256 `cd046033b89cc9d5e453feb2900b35534f07ea6439a2accbe1c3a0bcb4c54d0e`，与 GitHub 摘要、更新元数据和 `SHA256SUMS.txt` 一致；独立执行公开资产检查脚本通过。apksigner 验证单一 v3 正式证书，与 2.1.3 相同；aapt 确认为 code 8 / minSdk 29 / 非 debuggable，zipalign 检查通过。完整证书指纹见 [发布历史](../RELEASE_HISTORY.md)。
- 使用独立新建的 API 29 AVD `lumora_release214_verify`，先装公开 2.1.3，拍摄一组真实模拟镜头 JPEG、收藏作品并关闭音量键快门，再以 `adb install -r` 覆盖安装公开 2.1.4。冷启动 `Status: ok`、versionCode 8、versionName 2.1.4；作品页“全部 1、收藏 1”，原图/成片均实际显示，关闭的音量键快门保持 false。未卸载、未清除数据、未使用调试签名包冒充正式升级。
- 原图 SHA-256 升级前后均为 `ca8621fc074aa60e8b047dab9be41a42e911c892957da13f2a839bc1e21a16f8`；成片均为 `59774224016dc124e531a4b9b54320e4c28f05ef55117d9166dc2cfb7cbcaf1c`。本机 `build/release-upgrade-before-sha256.txt` 与 `build/release-upgrade-after-sha256.txt` 的 diff 为空；相关 UI 层级及运行截图保留于 `build/release214-*`，不含用户真实照片。
- 升级 AVD 的 crash buffer 仅看到安装应用前的 `com.android.sdksetup` Unknown/Unknown 时区异常，本次检查未看到 `com.lumora.camera` 崩溃；这不是长时间稳定性证明。ADB 覆盖安装亦不等同于真实系统安装器交互/取消验收。

## 2.1.3：相机权限与作品库导航（2026-11-21）

- 在独立 ASCII 路径工作树构建：Debug、AndroidTest 与未签名 Release 全部成功；JVM 单测 17 项通过。Debug／Release Lint 各 0 错误、31 警告。
- Android 10（API 29）和 Android 15（API 35）安装候选 Debug APK，按现有 CI 常规集各运行 35 项，无失败。API 35 在 1600 × 2560、280 dpi 下另外运行安全区及权限导航共 7 项，无失败。
- 新用例覆盖未授权时无快门、作品库和编辑入口可用、导入按钮可用、设置可访问，以及永久拒绝时显示系统设置入口。API 35 实际撤销相机权限后冷启动成功，保留了[真实界面截图](screenshots/v2-camera-permission.png)。
- 未在 2.1.3 重跑独立的 100 张耐久套件或实体厂商扩展测试；未主张渲染性能变化。正式签名、覆盖安装与公开下载不能以本地未签名 Release 的构建替代。

## 2.0.0：历史验收

2026-11-18，经本机 Windows、JDK 21、Gradle 9.1.0、AGP 9.0.1 构建。应用包名为 `com.lumora.camera`，版本码 2，最低 API 29，目标 API 35。

## 构建与自动化结果

| 核查 | 结果 | 证据 |
| --- | --- | --- |
| Debug / Release 构建 | PASS | `:app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease`；Release 恢复默认 lintVital 发布检查 |
| 主机单元测试 | PASS | `:app:testDebugUnitTest`，17 项，含负 EV 区间、陈旧镜头 ID 回退、旋转裁切、预设契约、RGB/HSL 和分析工具 |
| Android Debug / Release Lint | PASS | `:app:lintDebug :app:lintRelease`，均为 0 错误、27 个警告；包括可用依赖升级与 Kotlin 扩展建议 |
| Android 10 / API 29 | PASS | 单后摄、16 MiB Java 堆的 x86_64 模拟器。最终包安装成功；常规仪器测试 28/28，37.558 秒 |
| Android 15 / API 35 | PASS | 后摄和前摄均启用的 x86_64 模拟器。最终包覆盖安装成功；常规仪器测试 28/28，72.306 秒 |
| Android 15 长时间拍摄 | PASS | 下节列出 100 次实际快门、回读、耗时与内存数据 |
| 实体手机与厂商 ISP | BLOCKED | 本次只有模拟器；实体传感器、镜头和厂商扩展需要实机 |

从源码另建了无构建目录和项目缓存的目录，离线运行单元测试与 Debug 构建成功（43 项任务全部执行）。两个模拟器分别安装 Debug 和仪器测试 APK。常规测试用以下命令排除需要单独运行的耐久套件；每个普通用例都收到了成功结束码。详细用例名、两台设备的拍摄数据和 EXIF 可见 [逐项结果](verification/device-results.json)。

```sh
adb -s SERIAL shell am instrument -w -r -e notClass com.lumora.camera.CameraEnduranceTest com.lumora.camera.test/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL shell am instrument -w -r -e class com.lumora.camera.CameraEnduranceTest -e endurance true com.lumora.camera.test/androidx.test.runner.AndroidJUnitRunner
```

## UI 与设备兼容性

| 分辨率 | 密度 | 模拟缺口与导航 | 覆盖 |
| --- | --- | --- | --- |
| 720 × 1280 | 320 dpi | 高刘海、三键导航 | 5 项安全区测试 |
| 1080 × 2400 | 420 dpi | 挖孔、手势导航 | 5 项安全区测试 |
| 1440 × 2560 | 560 dpi | 边角缺口、三键导航 | 5 项安全区测试 |
| 1600 × 2560 | 280 dpi | 平板尺寸、手势导航 | 5 项安全区测试 |

上述 4 组配置均在最终 Debug 包上重跑，每组 5 项全部通过。通过 Android 系统 overlay 和 wm 调整配置。实际状态栏、导航栏、挖孔及系统手势边距与 Compose 控件边界使用同一像素坐标比较。测试包含竖屏、横屏、PRO 面板、快门和真实触摸点击。另在 API 29 将横屏从 90° 翻转到 270° 后拍摄，两个 640 × 480 原始 JPEG 的 EXIF 方向分别为 1 和 3，标记相差 180°。单后摄上没有第二枚可选镜头；双摄模拟器上执行了反复切换与前后台恢复。

拍摄辅助、胶片选择、采样风格预览和系统照片选择器也在 API 35 手动操作。选择器导入产生独立结果 URI，日志为 `mode=IMPORT`，输入与输出均为 1856 × 1392。切换 16:9 后得到 1280 × 720 原片与成片。通过 `am send-trim-memory … RUNNING_LOW` 后仍可拍摄；该命令模拟内存通知，超预算图片另有专门测试。

**模拟器运行截图**　[取景器](screenshots/v2-camera.png) · [横屏](screenshots/v2-landscape.png) · [PRO](screenshots/v2-pro.png) · [构图与曝光辅助](screenshots/v2-aids.png) · [风格样片](screenshots/v2-film.png) · [采样风格预览](screenshots/v2-live-film.png) · [作品库](screenshots/v2-library.png) · [编辑器](screenshots/v2-editor.png) · [设置](screenshots/v2-settings.png)

## 成像诊断与数据兼容

| 场景 | 原始输出 | 优化输出 | 实测结果 |
| --- | --- | --- | --- |
| V1 基线，同一 API 35 后摄 | 1856 × 1392，59,109 B | 1856 × 1392，50,816 B | 旧 APK 来自工程历史 Release 产物，临时用同一调试证书签名进行覆盖升级 |
| V2，API 35 手动曝光 | 1856 × 1392 | 1856 × 1392 | 请求 ISO 320、11,999,997 ns；重新读取原片 EXIF 为 ISO 320、12,000,000 ns |
| V2，API 29 手动曝光 | 640 × 480 | 640 × 480 | 请求 ISO 320、11,999,997 ns；原片 EXIF 为 ISO 320、11,999,997 ns |
| 超过 V1 的 2400 px 限制，仪器测试源 | 3024 × 909，方向标签和 EXIF 已知 | 3024 × 909 | 两个 API 都保留像素尺寸、方向与 EXIF，并验证来源字节不变 |
| 超出预算，32768 × 32768 PNG 测试源 | 原图可重新读取 | 返回 MEMORY 失败 | 两个 API 都在分配全尺寸位图前拒绝处理；单独注入写入失败，确认 pending 项回收 |

V1 与 V2 在同一模拟镜头上均受 1856 × 1392 输出规格限制。该对照证明在此配置下两版实际分辨率相同。超过旧上限的文件用例验证了 V2 导出链路。模拟场景无法判定实体照片是否因 AF、抖动或厂商降噪而模糊。

覆盖安装后打开了旧作品。原图 SHA-256 为 `22e54c73bd0b448e3994570120161a34e1cb81b3c68884a219c7bd6d70c74bbb`，成片为 `5e6208fdaaf7eb77e048d58ebc3e814cfd0fa954578b794e56913ef6095fc574`。另外测试了收藏、旧字段缺省、撤销、来源登记与后台替换竞争、导出新版本以及重复回调。

## 性能与资源

最终 API 35 Debug 包在持续取景 60.8 秒后执行 100 次独立快门。测试逐一确认原图与成片的 URI、JPEG 文件头、文件长度及尺寸，并在第 100 次拍摄后重新打开全部 200 个文件。测试期间启用了取景辅助，使用 VIVID FILM 风格。设备为虚拟后摄，输出均为 1856 × 1392。

| 指标 | 实测 |
| --- | --- |
| 成功拍摄 / 回读 | 100 / 100 次，200 个 JPEG |
| 100 次快门阶段间隔 | 总计 308.4 秒 |
| 单次快门至结果可回读，含测试轮询 | 中位 2969 ms，P95 3590 ms，最长 3866 ms |
| 单次后台处理，应用日志 | 中位 1166 ms，P95 1544 ms |
| 进程 PSS | 每 10 张采样，最高样本 128.3 MiB |

模拟器与主机负载都会影响上述时间。PSS 为间隔采样，不能据此推算瞬时内存峰值或实体机续航。API 29 另测得 16 MiB Java 堆下 3024 × 909 文件成功处理，耗时 507 ms。

## 已修复问题

- 移除固定竖屏约束，校正状态栏和导航边距；短横屏打开 PRO 时仍可点击快门。
- 默认静态捕获启用质量优先和可用尺寸选择；取消成片的固定缩小步骤。街拍模式按用户选择优先低延迟。
- 原图在后处理前登记；编码、写入或内存失败保留来源，并在作品中保留失败原因。
- 修正负曝光补偿范围导致的异常、固定示例参数、失效镜头标识、重绑和 180° 旋转后的方向更新。
- 修正只保留成片的旧数据被展示为可恢复原图、后台成片使已移除作品重新出现，以及缩略图全尺寸解码的问题。
- Android 10 上实测发现用 Java 堆余量估算全部位图成本会拒绝正常的 640 × 480 导出。现在区分原生像素内存和 Java 行缓冲预算；修复后在同一 16 MiB Java 堆设备上重跑全套通过。

## 尚待实测与后续建议

- **实体画质。** 在同一手机、镜头、取景位置和照明下，保留 V1/V2 原片与成片。场景包括日间户外、室内自然光、暗光室内、城市夜景、人像、近物、远景、高反差、逆光和复杂色彩。逐对比较细节、皮肤色偏、高光边界、暗部噪声、运动模糊与锐化光晕。
- **专有硬件。** 厂商 HDR、Bokeh、Night、OIS、独立物理镜头、动态 AF 命中率、实体温升及长时续航仍需有对应能力的手机。应用报告可用扩展，提供明确标注的拍摄辅助；当前导出提供 JPEG，RAW/DNG 输出仍待开发和实机验证。
- **性能。** 取景通路使用 CameraX；成片和采样风格预览在后台 CPU 色彩引擎中处理。真机应记录预览帧时间、启动至首个有效画面、12/48 MP 导出耗时及内存，再决定是否迁移像素内核到 GPU。超出设备预算的处理保留原片，可以重新编辑。
- **适配与签名。** 当前测试覆盖 API 29 和 35 的模拟设备。曲面屏误触、厂商系统手势、外接镜头和其他系统版本仍有待扩充。工程未配置发布密钥，Release 文件为未签名产物。

## 安装包

| 文件 | 大小（字节） | SHA-256 |
| --- | ---: | --- |
| debug | 69,912,144 | `99a28fbfbc8a41c89c0836c1cb0893db7d6bd4bdd125c11107b26ec90d51be18` |
| unsignedRelease | 49,586,011 | `3850c71db7af24bc1146f3cf02318fd1ca24ba8e0a780521f61ecf5edb667d00` |

已用 aapt 检查版本号，用 apksigner 核对 Debug APK 的 v2 签名。Release APK 保留未签名状态。
