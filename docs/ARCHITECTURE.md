# LUMORA Camera V2 架构

## 从快门到文件

1. MainActivity 管理相机授权、音量键与系统栏；LumoraNavigation 只在进入取景页且已授权时组合 CameraScreen。拒绝或撤销相机权限不阻塞作品库、系统照片选择器或设置。应用采用深色表面和浅色系统图标，取景器及普通页面分别消费所需的系统边距。
2. CameraController 根据 ProcessCameraProvider 枚举的实际镜头、Camera2 characteristics 和 ExtensionsManager 生成能力快照。取景与静态捕获分别配置尺寸选择策略；扩展或首选尺寸组合绑定失败时会回退。CameraFocus 跟踪点按对焦和拍摄前测光结果，并设有超时。
3. CameraX ImageCapture 将传感器经 ISP 编码的原始 JPEG 写入 MediaStore。CameraOutput 登记来源 URI，并在后台启动后处理。定时、连拍、音量键共用屏幕快门的捕获路径。
4. PhotoEnhancementEngine 测量输入文件并检查内存预算，将全尺寸图像解码为可修改的 sRGB 位图。FilmColorEngine 根据图像样本估计亮度与色偏，按行更新像素。RGB 曲线和分色 HSL 形成 17³ 查找表，使用四面体插值；暗部样本和邻域边缘参与降噪与细节处理。行缓冲由 Java 堆分配，像素缓冲占用原生内存。导出分别检查 Java 堆余量和系统可用内存，大图任务串行运行，内存不足时保留来源。
5. 成片通过独立的 pending 媒体行写入 JPEG，复制方向与拍摄 EXIF 后，复核文件头、长度、尺寸并发布。源 URI、结果 URI、参数和可用的实测数据保存在作品记录中。

## 取景、库与编辑

- PhotographyOverlay 从相机 TextureView 读取小尺寸帧，在后台计算风格预览、直方图、明暗警告及边缘提示。水平仪使用重力传感器或低通加速度计。拍照时直接使用 ImageCapture 输出。
- GalleryScreen 使用有尺寸上限且处理 EXIF 方向的预览。编辑预览与导出共用色彩引擎。每次保存会写入另一个结果并关联上一版本。
- PhotoRepository 延续原有 SharedPreferences 数据，旧字段可读，缺失字段使用兼容值。导入采用系统照片选择器及持久读取授权。图库记录与系统媒体文件各有独立生命周期。
- 质量日志含实际输入输出尺寸、文件长度、MIME、JPEG 质量设置、处理时长，以及镜头、模式和可读 EXIF。设备未写入的字段显示为不可用。

## 平台依据

- Android 15 的 edge-to-edge 与安全区行为见 [Android Developers – Window insets](https://developer.android.com/develop/ui/compose/system/insets)。
- CameraX 在镜头、比例和并发 use case 约束下选择可用尺寸。最终交付报告以导出文件测量值为准。[Android Developers – CameraX configuration](https://developer.android.com/media/camera/camerax/configuration)
