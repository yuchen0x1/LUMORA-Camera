# 平台与分发

LUMORA 的核心是按镜头能力取景和拍摄、保留来源 JPEG、在设备上做胶片渲染、非破坏性编辑。平台选择由这些能力共同决定。色彩配方和测试图可以跨平台复用。当前 CPU 引擎直接依赖 Android Bitmap，取景、媒体 URI、权限及 EXIF 也有平台依赖。

| 平台 | 结论 | 核心能力、体验与成本 | 分发和更新 |
| --- | --- | --- | --- |
| Android 手机（API 29+） | A | CameraX 和 Camera2 读取镜头能力，Compose 取景和控制，MediaStore 保留来源。相机权限与 ISP 差异须按镜头验证。 | GitHub APK，同证书经系统确认覆盖安装。 |
| Android 平板 | A | 复用拍摄和媒体层，按可用窗口约束布局。平板摄像头及传感器能力可能较少，实体适配仍需要设备。 | 同一 Android APK 和版本号。 |
| Android 折叠屏 | B | 取景需要处理折展、旋转、多窗口和铰链遮挡。先补窗口尺寸及重建回归，专用桌面姿态交互待实体或折叠 AVD 验证。 | 同一 Android APK 和版本号。 |
| iOS | C | AVFoundation 可独立实现采集及部分手动控制，PhotoKit/iOS 沙盒代替 MediaStore。重写控件、色彩桥接与媒体模型，并验证 iPhone 镜头。 | App Store 签名与分发，当前缺少 Apple 设备、构建与验证条件。 |
| iPadOS | C | 具备拍摄和编辑基础，另需为多窗口、外设、大屏编辑维护原生布局。 | App Store，同样需要 Apple 工具链和设备。 |
| Windows 10 | C | Media Foundation 能访问支持的摄像头，笔记本/USB 摄像头的静态画质和控制差异大。照片编辑有价值，需独立桌面架构。 | 独立签名及安装更新，兼容成本高。 |
| Windows 11 | C | 与 Windows 10 类似，主要价值是已有照片的离线开发与批量处理。另需键鼠、拖放和文件管理。 | 独立签名及安装更新。 |
| Linux | C | V4L2/libcamera 与文件系统可支持独立编辑器。桌面摄像硬件能力不一，维护设备规则、驱动和沙盒授权成本高。 | AppImage 或 Flatpak 需要独立适配和维护。 |
| Ubuntu | C | 可基于 Linux 方案实现，额外覆盖 LTS 运行库和摄像头沙盒。 | DEB 或 Flatpak。 |
| Debian | C | Linux 基础可行，稳定版依赖基线与打包测试会扩大矩阵。 | DEB 或 Flatpak。 |
| Fedora | C | Linux 基础可行，需覆盖新运行库、沙盒摄像头及文件门户。 | RPM 或 Flatpak。 |
| Arch Linux | C | Linux 基础可行，滚动更新要求更频繁的依赖和驱动验证。 | 独立包或 AppImage。 |
| macOS Intel | C | AVFoundation 和 Metal 技术可行，独立编辑器、采集权限及 Intel 架构测试的维护成本高。 | Developer ID 签名、公证和桌面更新。 |
| macOS Apple Silicon | C | 色彩开发和批量编辑是潜在用途，需另建原生界面和文件权限适配。内置摄像头的摄影用途有限。 | Developer ID 签名、公证和桌面更新。 |
| 桌面 Web | C | 可在 HTTPS 使用摄像头和本地文件，浏览器设备控制、静态拍摄和厂商扩展能力有限。编辑器需移植渲染器。 | Web 部署与缓存失效。 |
| 移动 Web | C | 摄像机授权、镜头选择和图像捕获能力随浏览器变化。图库持久访问和内存上限增加维护成本。 | Web 部署与缓存失效。 |
| 响应式 Web | C | 可提供跨端照片编辑，仍需同时覆盖移动 Web 和桌面 Web 的兼容约束。 | Web 部署与缓存失效。 |
| PWA | C | 离线编辑可行，安装形态不会增加设备相机控制或系统相册访问能力。需维护缓存和数据恢复。 | Service Worker 更新与用户刷新。 |
| Chrome 扩展 | D | 插件权限不会补齐 Camera2 或厂商 ISP。摄影工作流没有需要扩展权限的入口。 | 商店独立审核和更新。 |
| Edge 扩展 | D | 同类 Chromium 限制，用户价值低。 | Edge 扩展商店审核和更新。 |
| Firefox 扩展 | D | 相机由浏览器权限和设备能力约束，额外维护扩展兼容性。 | Firefox 附加组件签名及更新。 |
| Docker | D | 容器可用于 CI，实际用户拍摄依赖本机界面、相机 HAL 与权限。 | 镜像可更新，缺少独立产品用途。 |
| NAS | D | 可以同步已导出的照片，部署到 NAS 无法提供设备拍摄体验。 | 由用户现有的软件同步。 |
| 私有化部署 | D | 摄影和编辑均在设备上完成。另建服务会引入鉴权和照片隐私成本。 | 使用本地 APK。 |
| 云服务器 | D | 云端适合可选的文件服务，无法访问本地传感器，上传引入成本和隐私风险。 | 由 GitHub 提供下载。 |
| CLI | C | 批量套用配方有潜在价值，需要脱离 Bitmap、文件选择及 MediaStore。相机取景和触控无法移植到 CLI。 | 若需求成立，独立发布命令行工具。 |

大屏和可折叠设备属于 Android 的形态适配。发布时按同一版本记录手机、平板尺寸、折展和实体硬件的独立验证结果。厂商扩展、手动范围、RAW 能力以当前镜头声明及实测为准。

## Android 版本与分发

- 当前准备版本为 2.1.2 / versionCode 6 / minSdk 29，尚未发布；目前公开的是 `v2.0.0` 调试签名测试版。`v2.1.0` 与 2.1.1 元数据均保留为未发布记录，应用内更新等候选功能随 2.1.2 首次交付。
- `version.properties` 是版本来源，标签须与 `versionName` 一致；新稳定版的版本号、版本码须递增，并沿用已公开稳定版的签名证书。2.1.2 的版本码 6 高于公开测试版的 2、候选版的 3 与本地调试构建的 5。
- 标签触发的发布流程先验证源码与 API 29/35 测试，再通过受保护环境签名，校验 APK 身份、版本、最低 API、v2/v3 签名与证书，运行签名包安装启动检查。草稿资产验证通过后才公开 APK、`android-update.json` 与 `SHA256SUMS.txt`，并复核公开下载摘要。
- 应用内更新仅接受 GitHub 稳定版并在安装前核验已安装包与候选包证书。公开调试测试版无法覆盖安装不同签名的正式版；勿为升级直接卸载，以免丢失本地作品索引和设置。
- 已知漏洞依赖已按公告设置修复下限（Guava、Bouncy Castle、Netty、Kotlin 构建插件等，见 SECURITY_AUDIT.md）；版本文档和维护 PR 仍不代表已签名、安装验收或公开发布。

参考 [Camera preview](https://developer.android.com/media/camera/camera2/camera-preview)、[CameraX configuration](https://developer.android.com/media/camera/camerax/configuration)、[AVCaptureDevice](https://developer.apple.com/documentation/avfoundation/avcapturedevice)、[Web Image Capture](https://w3c.github.io/mediacapture-image/)。
