# LUMORA Camera 验收报告

## 2.1.6 / code 10 候选：最终本地验证（2026-11-21）

下表针对本地合并提交 `6497ad1` 中包含 KTX setter、点按 AF 竞态封堵、同步回滚失败隔离及未知网格值保护的**准确最终源码快照**执行。该提交尚未推送、未触发最终远程 CI，也未发布签名资产。

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

精确提交 `ed5ddc4` 的远程 Android CI 44851133527（构建/Lint、API 29、API 35、汇总）和依赖检查 44851133546 已通过。**仍待验收：** 独立核验正式签名、身份、大小、摘要和公开三资产；从公开 v2.1.4/code 8 进行保留数据的签名覆盖升级。未运行独立 100 张耐久；未验证真实硬件的厂商扩展或物理清晰度，无 RAW 输出、无实测速率提升。[完整候选文档](EVOLUTION-2.1.6.md)记录范围与安全边界。

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
