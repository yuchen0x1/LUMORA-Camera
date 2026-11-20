# 竞品与技术调研

## 2026-11-21：拍摄、权限与作品库对照

用仓库公开 API、源码和官方文档复核以下项目。没有用同一台设备、镜头、分辨率做横向基准，因而不对速度、功耗或画质排序；本轮未导入任何第三方源码、预设、着色器或依赖。

| 项目 | 已核实许可证／活动 | 与 LUMORA 的差异及取舍 |
| --- | --- | --- |
| [Open Camera](https://sourceforge.net/p/opencamera/code/ci/master/tree/) | [GPL-3.0-or-later](https://opencamera.sourceforge.io/)；[1.56.2](https://opencamera.sourceforge.io/history.html) 发布于 2026-05-27 | [官方帮助](https://opencamera.sourceforge.io/help.html)覆盖 RAW + JPEG、包围曝光和取景直方图，区分取景测量与实际成片；LUMORA 正式主线仍只输出 JPEG，先建立构图与实际输出的同场景测试。 |
| [PhotonCamera](https://github.com/eszdman/PhotonCamera) | GPL-3.0-or-later；`dev` 于 2026-10-06 更新 | Camera2 RAW 连拍和 OpenGL 对齐、合并、显影分阶段处理；LUMORA 的单帧 CPU 管线更简单，先将原片采集与有界后处理队列解耦，并测量真实快门间隔。 |
| [MotionCam 历史 Android 源码](https://github.com/TadiT7/motioncam) | 归档镜像的 `main` 和 `mm/fast_fuse` 有 GPL v3 根许可证；`main` 最后提交于 2022-03-22 | NDK Camera2、C++／Halide RAW 缓冲和多帧处理只作历史参考，不等同于当前 MotionCam Pro 开源。多帧 RAW 不进入 LUMORA 默认 JPEG 路径。 |
| [Android Camera Samples](https://github.com/android/camera-samples) | 核验的 CameraX／Camera2 控制器有 Apache-2.0 文件标头，不推定全仓同一许可证；`main` 于 2026-11-03 更新 | [CameraX RAW 样例](https://github.com/android/camera-samples/blob/main/samples/camerax-rawcapture/src/main/java/com/android/camerax/rawcapture/CameraXRawCaptureController.kt)按回调格式区分 RAW／JPEG；后续独立验收按镜头绑定及部分成功处理，不将硬件声明当作文件真实保存。 |
| [Aves](https://github.com/deckerst/aves) | BSD-3-Clause；`develop` 于 2026-11-19 更新 | Flutter 作品库和 Android 原生媒体观察器、不依赖拍摄链，按请求尺寸取缩略图。LUMORA 优先保留系统选择器的逐张访问，不为修复图库引入全盘媒体读取。 |
| [GrapheneOS Camera](https://github.com/GrapheneOS/Camera) | MIT；`main` 于 2026-11-20 更新 | CameraX 采集与有限权限、可选 EXIF 清理、写完再发布媒体；本轮采用最小权限原则，独立实现相机授权和图库／设置解耦。分享原图的元数据提示留作后续。 |

安装与工程取舍：正式继续沿用 GitHub 签名通用 APK 与双 API CI；没有替换 CameraX、引入 NDK/GPU 链或云端索引。新增功能需区分设备声明、真实绑定和实测文件；图库需区分 URI 权限失效、文件移除和仍可恢复的记录。

## 2026-11-18：历史基线

2026-11-18 的 GitHub 快照。通过 GitHub GraphQL 读取仓库描述、默认分支目录及提交历史、公开发布、Issues 和 README（存在 Markdown README 时）。链接直达项目和最近活动。30/90/180 是各窗口内的默认分支提交次数，Star / Fork 为快照。最新一列优先采用 GitHub 稳定 Release，其次取可见的最近 Release。未识别表示 GitHub 没有给出明确 SPDX，需要逐文件核查。项目代码、LUT 和 UI 素材均未引入。

| 项目与类别 | 平台 / 语言 | SPDX | Star / Fork | 最近提交 · 30/90/180 天 | Release | 源码入口与参考 |
| --- | --- | --- | --- | --- | --- | --- |
| [c52e/filcamera](https://github.com/c52e/filcamera) · 直接 | Android / C++ | GPL-3.0 | 6 / 1 | [2026-11-17](https://github.com/c52e/filcamera/commit/4e4123d623e654b7939773f923d74e6ba1369b4a) · 30/31/31 | [v0.29.0](https://github.com/c52e/filcamera/releases/tag/v0.29.0) | app, camera, docs, gpu-runtime, gradle, imaging, licenses, preview。Vulkan RAW 渲染、DNG 非破坏性显影，预览与最终效果区分空间阶段。 |
| [ryuheiyokokawa/FilmFrame](https://github.com/ryuheiyokokawa/FilmFrame) · 直接 | Android / Kotlin | MIT | 2 / 0 | [2026-05-07](https://github.com/ryuheiyokokawa/FilmFrame/commit/13f74c3ba8146406ece96127eefe3657476f00e9) · 0/0/0 | [v1.0.0](https://github.com/ryuheiyokokawa/FilmFrame/releases/tag/v1.0.0) | app, docs, gradle, tools。离线 LUT、自定义配方和 XPan 裁切，Pixel 10 Pro 单机验证的限制清晰。 |
| [bjzhou/PhotonCamera](https://github.com/bjzhou/PhotonCamera) · 直接 | Android / Kotlin | Apache-2.0 | 675 / 70 | [2026-11-18](https://github.com/bjzhou/PhotonCamera/commit/fb1e04864803242f04fe389fbb368d375854ce5f) · 97/394/1092 | [1.28.7](https://github.com/bjzhou/PhotonCamera/releases/tag/1.28.7) | app, doc, docs, gradle, research, scripts, tools。静态摄影、LUT、配方、连拍与动态照片。现场调整和风格可复用。 [近期 Issue](https://github.com/bjzhou/PhotonCamera/issues/95) |
| [noisystyle/NXT-CAM](https://github.com/noisystyle/NXT-CAM) · 直接 | Android / Kotlin | MIT | 3 / 0 | [2026-10-26](https://github.com/noisystyle/NXT-CAM/commit/3b6cc11e932952ab72c8717c5097fa4947ffa5f0) · 11/11/11 | [v2.6.0](https://github.com/noisystyle/NXT-CAM/releases/tag/v2.6.0) | app, gradle, mockups。把捕获与显影拆成步骤，提醒用户保存原片和显影结果。 |
| [FossifyOrg/Camera](https://github.com/FossifyOrg/Camera) · 相邻 | Android / Kotlin | GPL-3.0 | 495 / 63 | [2026-11-18](https://github.com/FossifyOrg/Camera/commit/11162931c2fb1a87682619816ae047911e85c758) · 10/27/56 | [1.5.0](https://github.com/FossifyOrg/Camera/releases/tag/1.5.0) | app, fastlane, gradle, graphics。简单的快门、闪光与比例，低学习成本的控件层级。 [近期 Issue](https://github.com/FossifyOrg/Camera/issues/294) |
| [GrapheneOS/Camera](https://github.com/GrapheneOS/Camera) · 相邻 | Android / Kotlin | MIT | 1430 / 156 | [2026-11-17](https://github.com/GrapheneOS/Camera/commit/5ddbe5a65c8928b4a0ccc126c4a50ff9d0707340) · 23/32/110 | [93](https://github.com/GrapheneOS/Camera/releases/tag/93) | app, gradle。隐私向相机、模式及 QR；适合对照权限的最小化。 [近期 Issue](https://github.com/GrapheneOS/Camera/issues/823) |
| [android/compose-samples](https://github.com/android/compose-samples) · 技术 | Android / Kotlin | Apache-2.0 | 23573 / 5493 | [2026-11-16](https://github.com/android/compose-samples/commit/09aae2013b3e5897f9679e2b7539f505f4f6f7f6) · 12/165/187 | [v2026.08.00](https://github.com/android/compose-samples/releases/tag/v2026.08.00) | JetLagged, JetNews, Jetcaster, Jetchat, Jetsnack, Reply, readme, scripts。Reply 等示例展示窗口适配和状态保持，供大屏编辑布局参考。 [近期 Issue](https://github.com/android/compose-samples/issues/865) |
| [androidx/androidx](https://github.com/androidx/androidx) · 技术 | Android / Kotlin | Apache-2.0 | 6140 / 1413 | [2026-11-18](https://github.com/androidx/androidx/commit/2d78fd6ed70cf0f37d5494c87c7f2cf0bd2e04e9) · 4247/13375/24800 | — | a2ui, activity, androidx-settings-plugins, annotation, appcompat, appfunctions, appsearch, appstate。Camera / Activity / Compose 基础实现和 API 测试；采用已使用的依赖边界。 |
| [natario1/CameraView](https://github.com/natario1/CameraView) · 技术 | Android / Java | 未识别 | 5132 / 986 | [2023-04-05](https://github.com/natario1/CameraView/commit/b279ffaf57919b7dffccc8879a0cb1eeb542c1ca) · 0/0/0 | [v2.7.2](https://github.com/natario1/CameraView/releases/tag/v2.7.2) | cameraview, demo, docs, gradle。抽象拍摄和取景，旧活动状态提示避免替换现有 CameraX。 [近期 Issue](https://github.com/natario1/CameraView/issues/1299) |
| [google/grafika](https://github.com/google/grafika) · 技术 | Android / Java | Apache-2.0 | 6146 / 1469 | [2020-01-17](https://github.com/google/grafika/commit/b1df331e89cffeab621f02b102d4c2c25eb6088a) · 0/0/0 | [v1.0-33](https://github.com/google/grafika/releases/tag/v1.0-33) | app, gradle。旧示例覆盖 GL/纹理与 Surface 链路，仅用于拆解预览渲染成本。 [近期 Issue](https://github.com/google/grafika/issues/135) |
| [Exiv2/exiv2](https://github.com/Exiv2/exiv2) · 技术 | Windows / Linux / macOS / C++ | 未识别 | 1173 / 349 | [2026-11-09](https://github.com/Exiv2/exiv2/commit/dc867091784010e3a4507775ed82ef36c4770b4d) · 19/105/229 | [v0.28.9](https://github.com/Exiv2/exiv2/releases/tag/v0.28.9) | app, ci, cmake, contrib, doc, fuzz, include, man。EXIF、XMP 及元数据处理边界。有助于规划分享时的元数据选择。 [近期 Issue](https://github.com/Exiv2/exiv2/issues/9562) |
| [LibRaw/LibRaw](https://github.com/LibRaw/LibRaw) · 技术 | 跨平台 C/C++ / C++ | 未识别 | 1634 / 396 | [2026-11-17](https://github.com/LibRaw/LibRaw/commit/70513b5fd839614626f665265487e6856bed3c92) · 9/42/71 | [0.22.3](https://github.com/LibRaw/LibRaw/releases/tag/0.22.3) | GoPro, RawSpeed, RawSpeed3, bin, buildfiles, doc, internal, lib。负责解码不同厂商 RAW；采集和颜色校准仍需要应用实现。 [近期 Issue](https://github.com/LibRaw/LibRaw/issues/896) |
| [drewnoakes/metadata-extractor](https://github.com/drewnoakes/metadata-extractor) · 技术 | JVM / Java | Apache-2.0 | 2844 / 510 | [2026-11-05](https://github.com/drewnoakes/metadata-extractor/commit/4d5cd46935c1987d8b19bfb49f3a24eb1d832e53) · 67/70/84 | [2.22.0](https://github.com/drewnoakes/metadata-extractor/releases/tag/2.22.0) | Resources, Samples, Source, Tests, docs。读取 EXIF、ICC 与 XMP，适合对照导出/迁移测试的元数据读取结果。 [近期 Issue](https://github.com/drewnoakes/metadata-extractor/issues/627) |
| [ImageMagick/ImageMagick](https://github.com/ImageMagick/ImageMagick) · 技术 | Windows / Linux / macOS / C | 未识别 | 17879 / 1713 | [2026-11-18](https://github.com/ImageMagick/ImageMagick/commit/1d72249fccbdd41df0a6970cdff03a7a60ad6b0c) · 241/640/1085 | [7.1.2-36](https://github.com/ImageMagick/ImageMagick/releases/tag/7.1.2-36) | Magick++, MagickCore, MagickWand, PerlMagick, api_examples, app-image, coders, config。CLI 工具和多格式转换。批量显影可参考命令模型与资源约束。 [近期 Issue](https://github.com/ImageMagick/ImageMagick/issues/9124) |
| [strukturag/libheif](https://github.com/strukturag/libheif) · 技术 | 跨平台 C/C++ / C++ | 未识别 | 2424 / 435 | [2026-10-26](https://github.com/strukturag/libheif/commit/12a259a5882dd99d45ec5fc77e296a3b620d1e2e) · 44/331/419 | [v1.23.7](https://github.com/strukturag/libheif/releases/tag/v1.23.7) | cmake, examples, extra, fuzzing, gdk-pixbuf, gnome, go, heifio。HEIF/AVIF 编解码主要受编解码器依赖及许可证约束。 [近期 Issue](https://github.com/strukturag/libheif/issues/1973) |
| [google/libultrahdr](https://github.com/google/libultrahdr) · 技术 | 跨平台 C/C++ / Android / C++ | 未识别 | 358 / 79 | [2026-11-18](https://github.com/google/libultrahdr/commit/0c86be73780f284f71910bfd3e564b2e0b007a95) · 4/43/91 | [v2.0.2](https://github.com/google/libultrahdr/releases/tag/v2.0.2) | adobe-hdr-gain-map-license, benchmark, cmake, docs, examples, fuzzer, java, lib。增益图向后兼容，适合后续为 HDR 编辑保留 SDR 回退。 [近期 Issue](https://github.com/google/libultrahdr/issues/501) |
| [libjxl/libjxl](https://github.com/libjxl/libjxl) · 技术 | 跨平台 C/C++ / C++ | BSD-3-Clause | 3864 / 458 | [2026-11-16](https://github.com/libjxl/libjxl/commit/a14f4e0ff32c07cbd19eae144c850e8bbfee2261) · 66/81/166 | [v0.13.0](https://github.com/libjxl/libjxl/releases/tag/v0.13.0) | cmake, debian, doc, examples, lib, plugins, third_party, tools。JPEG XL 编解码与基准工具，现阶段分享兼容性需先测。 [近期 Issue](https://github.com/libjxl/libjxl/issues/5143) |
| [photoprism/photoprism](https://github.com/photoprism/photoprism) · 相邻 | Web / 自托管 / Go | 未识别 | 40461 / 2338 | [2026-11-18](https://github.com/photoprism/photoprism/commit/a600880a2f385a850e9d55f5cab13b38e5085d30) · 237/1838/2623 | [261029-963b58c50](https://github.com/photoprism/photoprism/releases/tag/261029-963b58c50) | assets, cmd, docker, frontend, internal, pkg, scripts, setup。索引、同步和搜索适合大型库；本地相机无需服务端架构。 [近期 Issue](https://github.com/photoprism/photoprism/issues/5945) |
| [immich-app/immich](https://github.com/immich-app/immich) · 相邻 | Android / iOS / Web / 自托管 / TypeScript | AGPL-3.0 | 119543 / 7649 | [2026-11-18](https://github.com/immich-app/immich/commit/f005e0ce44ed3c3d9a18bc8b6dbb15a22af29205) · 378/830/1559 | [v3.4.1](https://github.com/immich-app/immich/releases/tag/v3.4.1) | deployment, design, docker, docs, e2e, fastlane, i18n, machine-learning。端与存储服务分离，照片备份与设备相册关联有价值。 [近期 Issue](https://github.com/immich-app/immich/issues/2794) |
| [T8RIN/ImageToolbox](https://github.com/T8RIN/ImageToolbox) · 相邻 | Android / Kotlin | Apache-2.0 | 15635 / 680 | [2026-11-17](https://github.com/T8RIN/ImageToolbox/commit/bded8038ccf8db15f15135c05aab077c0cd8d6c6) · 408/908/2151 | [4.2.0](https://github.com/T8RIN/ImageToolbox/releases/tag/4.2.0) | app, benchmark, build-logic, core, fastlane, feature, gradle, instrumentation-tests。Compose 图片编辑、批量操作和独立 feature 模块，可参考编辑任务边界。 |
| [coil-kt/coil](https://github.com/coil-kt/coil) · 技术 | Android / Compose Multiplatform / Kotlin | Apache-2.0 | 11959 / 801 | [2026-11-16](https://github.com/coil-kt/coil/commit/cdb1db4959003c8dd5d4a14b361ea71b453502ae) · 34/105/181 | [3.6.3](https://github.com/coil-kt/coil/releases/tag/3.6.3) | buildSrc, coil-bom, coil-compose-core, coil-compose, coil-core, coil-gif, coil-lint, coil-network-cache-control。按显示尺寸解码、缓存与生命周期；与当前有界预览策略比较。 [近期 Issue](https://github.com/coil-kt/coil/issues/2953) |
| [f-droid/fdroidclient](https://github.com/f-droid/fdroidclient) · 技术 | Android / Kotlin | GPL-3.0 | 3299 / 247 | [2026-11-09](https://github.com/f-droid/fdroidclient/commit/9dd8b2f94e03f0ffe4d9238461caa128fef094e0) · 59/203/390 | [1.18.0](https://github.com/f-droid/fdroidclient/releases/tag/1.18.0) | app, config, gradle, hooks, libs, metadata, src, tools。仓库元数据、签名和独立安装操作。启发校验流程，继续用系统确认安装。 |
| [Aefyr/SAI](https://github.com/Aefyr/SAI) · 技术 | Android / Java | GPL-3.0 | 3994 / 348 | [2021-02-25](https://github.com/Aefyr/SAI/commit/55505d231b1390e824d1cc0c8f4fa35fd4677105) · 0/0/0 | [4.5](https://github.com/Aefyr/SAI/releases/tag/4.5) | app, assets, fastlane, gradle。拆分 APK 安装器；低活跃，通用单 APK 足以覆盖本轮。 [近期 Issue](https://github.com/Aefyr/SAI/issues/323) |
| [google/bundletool](https://github.com/google/bundletool) · 技术 | JVM / Android / Java | Apache-2.0 | 4053 / 432 | [2025-12-15](https://github.com/google/bundletool/commit/586a43a450712a1067f3d92cf7574dee68226302) · 0/0/0 | [1.18.3](https://github.com/google/bundletool/releases/tag/1.18.3) | gradle, src。面向商店 AAB 和设备拆分，GitHub 分发本轮使用通用 APK。 [近期 Issue](https://github.com/google/bundletool/issues/400) |
| [mobile-dev-inc/Maestro](https://github.com/mobile-dev-inc/Maestro) · 技术 | Android / iOS / Web / Kotlin | Apache-2.0 | 16447 / 1026 | [2026-11-17](https://github.com/mobile-dev-inc/Maestro/commit/4413a3325bef192044c0a9a094364dafe1f285a0) · 21/68/158 | [cli-2.12.1](https://github.com/mobile-dev-inc/Maestro/releases/tag/cli-2.12.1) | assets, docs, e2e, gradle, maestro-ai, maestro-android, maestro-cli, maestro-client。流程式跨设备 UI 测试，可用于后续系统安装器 UI 回归。 [近期 Issue](https://github.com/mobile-dev-inc/Maestro/issues/3706) |
| [ReactiveCircus/android-emulator-runner](https://github.com/ReactiveCircus/android-emulator-runner) · 技术 | GitHub Runner / Android AVD / TypeScript | Apache-2.0 | 1317 / 264 | [2026-08-26](https://github.com/ReactiveCircus/android-emulator-runner/commit/ed009f5318f15b1cf93a191b856c4f1748a2d4c1) · 0/2/7 | [v2.38.0](https://github.com/ReactiveCircus/android-emulator-runner/releases/tag/v2.38.0) | __tests__, lib, src, test-fixture。Linux KVM 模拟器矩阵，实际接入最低 API 与 API 35。 [近期 Issue](https://github.com/ReactiveCircus/android-emulator-runner/issues/431) |
| [gradle/actions](https://github.com/gradle/actions) · 技术 | GitHub Runner / TypeScript | 未识别 | 353 / 133 | [2026-11-12](https://github.com/gradle/actions/commit/445a164ce8dd7af4e36840ca2f5aa4e1faf842f2) · 28/48/102 | [v6.4.1](https://github.com/gradle/actions/releases/tag/v6.4.1) | dependency-submission, dist, docs, licenses, setup-gradle, sources, wrapper-validation。固定提交、Gradle 缓存与依赖图提交分离，实际接入 CI。 [近期 Issue](https://github.com/gradle/actions/issues/1088) |
| [andreavolpato/spektrafilm](https://github.com/andreavolpato/spektrafilm) · 技术 | Python / Python | GPL-3.0 | 2320 / 338 | [2026-06-06](https://github.com/andreavolpato/spektrafilm/commit/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc) · 0/0/40 | [v0.3.3](https://github.com/andreavolpato/spektrafilm/releases/tag/v0.3.3) | examples, img, scripts, src, tests。以光谱与化学过程描述胶片响应，参考测试色卡，保留 LUMORA 参数模型。 [近期 Issue](https://github.com/andreavolpato/spektrafilm/issues/78) |
| [LibreStatic/opencinecam](https://github.com/LibreStatic/opencinecam) · 相邻 | Android / Kotlin | Apache-2.0 | 11 / 0 | [2026-11-16](https://github.com/LibreStatic/opencinecam/commit/b92f400e461e3ac345ed9b6af585d15ef7bd8ba9) · 326/713/713 | — | app, baselineprofile, camera, config, core, desktop-tools, docs, evidence。将硬件声明、请求值和成品证据分开，契合 LUMORA 的 EXIF 验证。 |
| [ParticlesDevs/PhotonCamera](https://github.com/ParticlesDevs/PhotonCamera) · 相邻 | Android / C++ | 未识别 | 19 / 1 | [2025-06-05](https://github.com/ParticlesDevs/PhotonCamera/commit/9e30c9c4021f4541d292703eaf1a26ecd6d027fd) · 0/0/0 | — | app, gradle, key。静态照片 Camera2 和后处理；移植原生模块意味着更多设备维护。 |
| [andrasulthan-alt/OmniCam](https://github.com/andrasulthan-alt/OmniCam) · 相邻 | Android / Kotlin | GPL-3.0 | 3 / 0 | [2026-11-08](https://github.com/andrasulthan-alt/OmniCam/commit/7cb08cc17b12292762e3451e7ce52afc6e0c21d5) · 9/139/141 | [v0.5.0](https://github.com/andrasulthan-alt/OmniCam/releases/tag/v0.5.0) | app, docs, gradle, licenses。按设备能力解锁控制、限制权限，记录厂商实现差异。 |
| [android/camera-samples](https://github.com/android/camera-samples) · 技术 | Android / Kotlin | 未识别 | 5469 / 2416 | [2026-11-03](https://github.com/android/camera-samples/commit/154c954abc5c80dddf07d264319cf89a3e1ccda9) · 4/4/25 | — | app, buildSrc, core-camera, core-theme, core-ui, docs, gradle, samples。官方 Compose 样本按 CameraX / Camera2 能力拆分，参考方向和取景变换。 [近期 Issue](https://github.com/android/camera-samples/issues/645) |
| [darktable-org/darktable](https://github.com/darktable-org/darktable) · 相邻 | Windows / macOS / Linux / C | GPL-3.0 | 13472 / 1530 | [2026-11-17](https://github.com/darktable-org/darktable/commit/1c4eaea9df6dbe8336449ca297acbb0120d8e968) · 879/1724/2589 | [release-5.6.2](https://github.com/darktable-org/darktable/releases/tag/release-5.6.2) | cmake, data, dev-doc, doc, packaging, po, src, tools。非破坏性历史与可复制样式，RAW 工作流支持深度编辑。 [近期 Issue](https://github.com/darktable-org/darktable/issues/23184) |
| [RawTherapee/RawTherapee](https://github.com/RawTherapee/RawTherapee) · 相邻 | Windows / macOS / Linux / C++ | GPL-3.0 | 4298 / 431 | [2026-11-09](https://github.com/RawTherapee/RawTherapee/commit/64c2d90a3248c7ad41842d9162368858b61aac6a) · 6/18/88 | [5.13](https://github.com/RawTherapee/RawTherapee/releases/tag/5.13) | cmake, devnotes, doc, external, licenses, rtdata, rtengine, rtgui。批处理、参数配置文件和可重放开发链路。 [近期 Issue](https://github.com/RawTherapee/RawTherapee/issues/7778) |
| [artraweditor/ART](https://github.com/artraweditor/ART) · 相邻 | Windows / macOS / Linux / C++ | 未识别 | 521 / 36 | [2026-11-13](https://github.com/artraweditor/ART/commit/40ec1cd38a11e13883d8f6159ce7017aa97e988a) · 85/156/214 | [1.26.11](https://github.com/artraweditor/ART/releases/tag/1.26.11) | cmake, data, doc, licenses, src, tools。派生自 RawTherapee 的离线显影，局部编辑和紧凑工具层级。 [近期 Issue](https://github.com/artraweditor/ART/issues/568) |
| [PhotoFlare/photoflare](https://github.com/PhotoFlare/photoflare) · 相邻 | Windows / macOS / Linux / C++ | GPL-3.0 | 475 / 81 | [2026-10-31](https://github.com/PhotoFlare/photoflare/commit/539465266a837084c5c03fba28711edd73fa1d3c) · 8/55/121 | [v1.7.5](https://github.com/PhotoFlare/photoflare/releases/tag/v1.7.5) | assets, docs, examples, external, flatpak, installers, languages, packaging。Qt 桌面传统图片编辑，拖放和文件选择依赖桌面交互。 [近期 Issue](https://github.com/PhotoFlare/photoflare/issues/515) |
| [ImranR98/Obtainium](https://github.com/ImranR98/Obtainium) · 技术 | Android / Dart | GPL-3.0 | 21658 / 706 | [2026-11-01](https://github.com/ImranR98/Obtainium/commit/35c3e7fea4f341a716eca4bb3fc625319ce965ab) · 115/534/1105 | [v1.7.4](https://github.com/ImranR98/Obtainium/releases/tag/v1.7.4) | android, assets, docker, docs, fastlane, integration_test, lib, test。直接跟源仓库版本，识别资产并交给 Android 安装系统。 [近期 Issue](https://github.com/ImranR98/Obtainium/issues/3528) |

## 功能差距与选择

| 能力 | LUMORA 2.0 | 同类实现与取舍 |
| --- | --- | --- |
| 手动拍摄 / 曝光取证 | 镜头能力判断，请求值及 JPEG EXIF | OpenCineCam 和 OmniCam 同样区分能力、请求与结果。实体镜头矩阵先于新模式。 |
| RAW、色彩、取景 | JPEG 即拍即编辑，CPU LUT，采样预览 | FILCamera 的 Vulkan DNG 与原片工作流是中期研究。FilmFrame 在指定 Pixel 上用用户 LUT，先为 LUMORA 设定配方验证与设备覆盖指标。 |
| 版本和开发配方 | 20 种内置预设，可撤销和另存版本 | darktable 和 RawTherapee 将显影配置可重复应用。优先探索只导出编辑参数的版本化配方。 |
| 照片管理 | 设备媒体库和本地记录 | Immich 支持备份和跨端浏览。LUMORA 的近期需求是安全保留照片和恢复索引，服务端的隐私及运维成本较高。 |
| 取景界面 | 主操作可直接触达，模式和调节按需展开 | Fossify 的快门层级与 Compose Reply 的大屏主从结构有参考价值。大屏给取景留独立空间，滑杆仍按需显示。 |
| 更新 | v2.0.0 为人工下载预览版 | Obtainium 与 F-Droid 区分发现和系统安装。采用固定来源、摘要、同一 APK 证书和系统安装确认。 |
| 验证与分发 | 本机手工跑模拟器，公开资产为调试包 | 使用 Gradle Actions、Linux KVM 矩阵和稳定标签分发。同一 Android APK 覆盖支持的窗口形态。 |

近期重点查看 FILCamera 的 Vulkan / DNG 架构、Android Camera Samples 的 CameraX/Camera2 取景样本、libultrahdr 的增益图和 GitHub Release 资产摘要。大屏走独立的控件布局，保留读数、快门和状态信息的可见性。桌面原生显影对照 darktable、RawTherapee 和 ART；Web 参考 Immich / PhotoPrism 的图库。两类产品的主工作流区别见 [平台评估](PLATFORMS.md)。

## 升级候选

| # | 候选及价值 | 依据 | 优先级 / 难度 | 决定 |
| --- | --- | --- | --- | --- |
| 01 | 固定 Android 构建、Lint 和单测 | Gradle Actions | 高 / 中 | 实施 |
| 02 | API 29/35 仪器测试矩阵 | Android Emulator Runner | 高 / 中 | 实施 |
| 03 | 平板尺寸安全区测试 | Compose Samples | 高 / 低 | 实施 |
| 04 | 展开后让快门和取景相邻 | Android Camera Samples | 高 / 中 | 实施 |
| 05 | 实体折叠铰链、封面屏切换 | Android 平台文档 | 高 / 高 | 待设备 |
| 06 | tag 与 Gradle 版本单源 | Gradle Actions | 高 / 低 | 实施 |
| 07 | 生产证书签名及指纹固定 | Android Signing | 高 / 中 | 实施 |
| 08 | 草稿验证后发布三种资产 | GitHub Releases | 高 / 中 | 实施 |
| 09 | 定时验证 Release 下载和摘要 | GitHub Releases | 高 / 低 | 实施 |
| 10 | 应用内稳定版检查 | Obtainium | 高 / 中 | 实施 |
| 11 | 可选检查频率与按版稍后提醒 | Obtainium | 中 / 低 | 实施 |
| 12 | 进度、速率、有界下载和取消 | F-Droid | 高 / 中 | 实施 |
| 13 | 安装前匹配已安装 APK 签名 | Android Signing | 高 / 中 | 实施 |
| 14 | 私密漏洞入口、PR 和故障表单 | GitHub Security | 高 / 低 | 实施 |
| 15 | 依赖图和依赖 PR 风险检查 | Gradle Actions | 高 / 中 | 实施 |
| 16 | 备份与恢复作品索引 | Immich | 高 / 高 | 待 URI 重绑定及隐私设计 |
| 17 | 从作品导出和导入胶片配方 | RawTherapee | 中 / 中 | 排期 |
| 18 | 导入自有 LUT，限制文件及尺寸 | FilmFrame | 中 / 中 | 排期 |
| 19 | 从前景提醒断点下载 | F-Droid | 中 / 中 | 看包大小和失败率 |
| 20 | 使用新版本码发布修复回退 | Android Signing | 高 / 中 | 记录流程 |
| 21 | 系统安装器取消回归 | Maestro | 高 / 中 | 随第二个签名版本验收 |
| 22 | 来源照片保留 EXIF，成片可选择去除位置 | Exiv2 | 高 / 中 | 排期 |
| 23 | 基于媒体 URI 读取失败的恢复入口 | Immich | 中 / 中 | 排期 |
| 24 | 全尺寸 JPEG 内存性能多机型采样 | ImageMagick / Coil | 高 / 中 | 待真机 |
| 25 | RAW/DNG 捕获及 JPEG 配对 | FILCamera / OpenCineCam | 中 / 高 | 先验证实体摄像头 |
| 26 | GPU 可选渲染，CPU 结果对照 | FILCamera | 中 / 高 | 有性能基线后评估 |
| 27 | 支持 Ultra HDR 导出 | libultrahdr | 中 / 高 | 有色彩回归图后评估 |
| 28 | HEIF、JPEG XL 输出 | libheif / libjxl | 低 / 高 | 暂缓，系统分享兼容待确认 |
| 29 | 对可见缩略图做有界缓存 | Coil | 中 / 中 | 先压测 500 个版本 |
| 30 | 批量套配方与可取消导出 | Image Toolbox / ART | 中 / 高 | 排期 |
| 31 | NAS 或云端同步 | PhotoPrism | 低 / 高 | 暂缓，超出本地拍摄范围 |
| 32 | 原生桌面显影 | darktable / RawTherapee | 低 / 高 | 暂缓，待共享色彩测试 |
| 33 | iPhone 原生相机 | AVFoundation | 低 / 高 | 暂缓，待 Apple 硬件与维护能力 |
| 34 | 周期跟踪直接竞品的发布差异 | GitHub Releases | 中 / 低 | 每月以本表重采样重点项目 |

在主要升级前重查 FILCamera、PhotonCamera、Android Camera Samples 和 Obtainium 的 30/90/180 天更新、稳定发布、设备限制与许可证，按最新证据更新本表和候选。
