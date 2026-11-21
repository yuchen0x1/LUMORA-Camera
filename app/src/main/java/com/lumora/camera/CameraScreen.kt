package com.lumora.camera

import android.graphics.Bitmap
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.lumora.camera.camera.*
import com.lumora.camera.data.*
import com.lumora.camera.image.PhotoEnhancementEngine
import com.lumora.camera.ui.PhotoAids
import com.lumora.camera.ui.PhotographyOverlay
import com.lumora.camera.ui.ViewfinderSystemBarScrims
import com.lumora.camera.ui.ViewfinderTokens
import com.lumora.camera.ui.safeAreaPadding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class CameraPanel { PRO, TOOLS, FILM }

private fun Modifier.statusLiveRegion(enabled: Boolean): Modifier =
    if (enabled) semantics { liveRegion = LiveRegionMode.Polite } else this

@Composable
internal fun CameraScreen(
    repository: PhotoRepository,
    presets: List<FilmPreset>,
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    onCapabilities: (DeviceCapabilities) -> Unit = {}
) {
    val context = LocalContext.current
    val screenPanel = Panel
    val screenInk = Ink
    val screenAccent = Accent
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { CameraSettings(context) }
    val preview = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val controller = remember(context, repository) { CameraController(context, repository) }
    val activity = owner as? MainActivity ?: context as? MainActivity
    val fold by produceState<FoldingFeature?>(null, activity) {
        if (activity != null) {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { layout ->
                value = layout.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull {
                    it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL
                }
            }
        }
    }
    val latestOnCapabilities by rememberUpdatedState(onCapabilities)
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var streaming by remember { mutableStateOf(false) }
    var capabilities by remember { mutableStateOf<DeviceCapabilities?>(null) }
    var activeMode by remember { mutableStateOf(CaptureMode.AUTO) }
    var mode by rememberSaveable { mutableStateOf(CaptureMode.AUTO) }
    val rawMode = mode == CaptureMode.RAW
    var cameraId by rememberSaveable { mutableStateOf<String?>(null) }
    var wide by rememberSaveable { mutableStateOf(false) }
    var panel by rememberSaveable { mutableStateOf<CameraPanel?>(null) }
    var timer by rememberSaveable { mutableIntStateOf(0) }
    var burst by rememberSaveable { mutableIntStateOf(1) }
    var retry by remember { mutableIntStateOf(0) }
    var bindError by remember { mutableStateOf<String?>(null) }
    var focus by remember { mutableStateOf<FocusStatus?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var viewfinderSize by remember { mutableStateOf(IntSize.Zero) }
    var focusFeedbackSize by remember { mutableStateOf(IntSize.Zero) }
    var focusAttempt by remember { mutableIntStateOf(0) }
    var flash by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var evIndex by remember { mutableIntStateOf(0) }
    var manual by remember { mutableStateOf(ManualControls()) }
    var desiredManual by rememberSaveable(stateSaver = ManualControlsSaver) { mutableStateOf(ManualControls()) }
    var applying by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(repository.selectedStyleId) }
    val preset = presets.firstOrNull { it.id == selected } ?: presets.first()
    var intensity by remember { mutableFloatStateOf(repository.styleIntensity) }
    var strength by remember { mutableStateOf(repository.optimizationStrength) }
    var sample by remember(repository) { mutableStateOf<String?>(null) }
    var latestCapture by remember(repository) { mutableStateOf<String?>(null) }
    LaunchedEffect(repository) {
        repository.observeRecords().collect { records ->
            sample = repository.styleSampleUri ?: records.firstOrNull()?.originalUri
            // Rows arrive newest-version first. Among edits of the newest capture, keep that ordering.
            latestCapture = records.asSequence().filterNot { it.imported }.maxByOrNull { it.capturedAt }?.enhancedUri
        }
    }
    var aids by remember {
        mutableStateOf(PhotoAids(
            grid = settings.compositionGrid,
            level = settings.level, histogram = settings.histogram, clipping = settings.clipping, peaking = settings.peaking
        ))
    }
    var aidNotice by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeId by remember { mutableIntStateOf(0) }
    fun report(message: String) { notice = message; noticeId++ }
    var captureJob by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var remaining by remember { mutableIntStateOf(0) }
    var completed by remember { mutableIntStateOf(0) }
    val sound = remember(settings) {
        if (settings.shutterSound) runCatching { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }.getOrNull() else null
    }
    DisposableEffect(owner, preview, controller) {
        val stateObserver = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) captureJob?.cancel()
        }
        val streamObserver = Observer<PreviewView.StreamState> { streaming = it == PreviewView.StreamState.STREAMING }
        owner.lifecycle.addObserver(stateObserver)
        preview.previewStreamState.observe(owner, streamObserver)
        onDispose {
            owner.lifecycle.removeObserver(stateObserver)
            preview.previewStreamState.removeObserver(streamObserver)
            sound?.release()
        }
    }

    DisposableEffect(controller, owner, resumed, mode, cameraId, wide, retry) {
        var current = true
        capabilities = null
        bindError = null
        focus = null
        focusPoint = null
        focusAttempt++
        applying = false
        manual = ManualControls()
        flash = ImageCapture.FLASH_MODE_OFF
        if (resumed) {
            controller.bind(owner, preview, onReady = { camera ->
                if (current) {
                    val snapshot = controller.capabilities
                    capabilities = snapshot
                    activeMode = controller.activeMode
                    if (mode != activeMode) report("已切换至 ${activeMode.label}")
                    zoom = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                    evIndex = camera.cameraInfo.exposureState.exposureCompensationIndex
                    if (activeMode == CaptureMode.PRO && snapshot != null) {
                        val restored = desiredManual.forReboundCamera(snapshot)
                        applying = true
                        controller.setManualControls(restored) { error ->
                            if (current) {
                                applying = false
                                if (error == null) {
                                    manual = restored
                                    if (restored != desiredManual) report("已保留此镜头支持的专业参数；不支持的项目已恢复自动")
                                } else report("专业参数未能恢复，请重试设置")
                            }
                        }
                    }
                    snapshot?.let(latestOnCapabilities)
                }
            }, onError = { error ->
                if (current) {
                    capabilities = null
                    bindError = error.localizedMessage ?: "相机连接失败"
                }
            }, cameraId = cameraId, mode = mode, wide = wide, jpegQuality = settings.jpegQuality)
        }
        onDispose {
            current = false
            controller.release()
        }
    }

    LaunchedEffect(noticeId) {
        if (notice != null) {
            val manager = context.getSystemService(AccessibilityManager::class.java)
            val timeout = manager?.getRecommendedTimeoutMillis(
                4300, AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS
            ) ?: 4300
            delay(timeout.toLong())
            notice = null
        }
    }
    LaunchedEffect(focusAttempt, focus) {
        if (focus != null && focus != FocusStatus.FOCUSING) { delay(1800); focus = null; focusPoint = null }
    }
    fun selectMode(value: CaptureMode) {
        if (value != mode) { capabilities = null; mode = value }
        if (value == CaptureMode.LANDSCAPE_PROFILE) wide = false
        panel = if (value == CaptureMode.PRO) CameraPanel.PRO else null
    }
    fun selectLens(id: String) {
        capabilities = null
        cameraId = id
        if (mode != CaptureMode.PRO) mode = CaptureMode.AUTO
        panel = null
    }
    val ready = resumed && streaming && capabilities != null && !busy && !applying
    val shotCount = if (rawMode) 1 else burst
    val viewfinderWide = wide && !rawMode
    fun trigger() {
        val caps = capabilities ?: return
        if (!ready || busy) return
        busy = true
        completed = 0
        val count = shotCount
        val options = ShotOptions(preset, strength, intensity, settings.jpegQuality, caps, activeMode, manual)
        captureJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (tick in timer downTo 1) { remaining = tick; delay(1000) }
                remaining = 0
                var message = ""
                var unconfirmed = 0
                // Burst fires the next frame as soon as the previous source is on disk; renders
                // complete in the background and a failed render never stops the remaining shots.
                val renders = mutableListOf<Deferred<ShotResult>>()
                for (index in 1..count) {
                    var shutterFocus: FocusStatus? = null
                    if (count == 1) {
                        val result = CameraOutput.take(context, repository, controller, options,
                            onFocus = {
                                shutterFocus = it
                                if (resumed) focus = it
                            },
                            onSourceSaved = { if (resumed) sound?.play(MediaActionSound.SHUTTER_CLICK) })
                        completed = index
                        if (shutterFocus == FocusStatus.FAILED || shutterFocus == FocusStatus.TIMEOUT) unconfirmed++
                        if (rawMode) {
                            message = requireNotNull(result.notice)
                            break
                        }
                        if (!result.enhanced) {
                            message = (result.notice ?: "已保存原片 · 成片处理失败") + "（$index/$count）"
                            break
                        }
                        message = result.notice ?: "已保存 · ${preset.name}"
                    } else {
                        val shot = CameraOutput.captureSource(context, repository, controller, options,
                            onFocus = {
                                shutterFocus = it
                                if (resumed) focus = it
                            },
                            onSourceSaved = { if (resumed) sound?.play(MediaActionSound.SHUTTER_CLICK) })
                        completed = index
                        renders += shot.render
                        if (shot.focus == FocusStatus.FAILED || shot.focus == FocusStatus.TIMEOUT) unconfirmed++
                        message = "已保存 $index/$count 张"
                    }
                }
                if (renders.isNotEmpty()) {
                    val results = renders.awaitAll()
                    val failed = results.count { !it.enhanced }
                    val notices = results.mapNotNull { it.notice }.distinct()
                    message = if (failed == 0) "已保存 ${results.size} 张"
                    else "已保存 ${results.size} 张 · $failed 张仅有原片"
                    notices.firstOrNull()?.let { message += " · $it" }
                }
                if (unconfirmed > 0) message += if (count == 1) " · 未确认对焦" else " · $unconfirmed 张未确认对焦"
                report(message)
            } catch (cancelled: CancellationException) {
                report("拍摄已停止 · 已完成 $completed/$count 张")
                throw cancelled
            } catch (error: Exception) {
                report("拍摄失败 · 已完成 $completed/$count 张 · ${error.localizedMessage ?: "请重试"}")
            } finally {
                remaining = 0
                busy = false
            }
        }
    }
    val latestTrigger by rememberUpdatedState { trigger() }
    DisposableEffect(activity, ready, settings) {
        if (ready && settings.volumeShutter) activity?.volumeShutter = { latestTrigger() }
        onDispose { activity?.volumeShutter = null }
    }
    BackHandler(enabled = panel != null || remaining > 0) {
        if (remaining > 0) captureJob?.cancel() else panel = null
    }
    val canTapFocus = ready && (manual.focusDiopters == null || manual.iso == null && !manual.aeLock)
    val tapFocus = { point: Offset ->
        if (canTapFocus) {
            focusAttempt++
            val token = focusAttempt
            focusPoint = point
            controller.focus(preview, point.x, point.y) { if (token == focusAttempt) focus = it }
        }
    }
    val focusAction by rememberUpdatedState(tapFocus)
    val focusHalfPx = with(LocalDensity.current) { 26.dp.toPx() }
    val cameraState = capabilities?.let { "${it.lensFacing} · ${it.cameraId} · ${activeMode.name}" } ?: "连接中"

    BoxWithConstraints(Modifier.fillMaxSize().background(ViewfinderTokens.background)) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val hingePadding = with(LocalDensity.current) {
            val feature = fold
            when (feature?.orientation) {
                FoldingFeature.Orientation.VERTICAL -> {
                    val pane = unobstructedPane(width, feature.bounds.left, feature.bounds.right)
                    Modifier.absolutePadding(left = pane.start.toDp(), right = (width - pane.end).toDp())
                }
                FoldingFeature.Orientation.HORIZONTAL -> {
                    val pane = unobstructedPane(height, feature.bounds.top, feature.bounds.bottom)
                    Modifier.padding(top = pane.start.toDp(), bottom = (height - pane.end).toDp())
                }
                else -> Modifier
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().then(hingePadding)) {
        val longSideRatio = if (viewfinderWide) 16f / 9f else 4f / 3f
        val viewfinderRatio = if (maxWidth > maxHeight) longSideRatio else 1f / longSideRatio
        val roomy = maxWidth >= 600.dp && maxHeight >= 420.dp
        val rail = 96.dp
        val previewSpace = if (roomy) Modifier.padding(end = rail) else Modifier
        Box(Modifier.fillMaxSize().then(previewSpace), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(viewfinderRatio).clipToBounds().onSizeChanged { viewfinderSize = it }
            .onKeyEvent { event ->
                val activation = event.key == Key.Enter || event.key == Key.NumPadEnter ||
                    event.key == Key.Spacebar || event.key == Key.DirectionCenter
                if (activation && canTapFocus) {
                    if (event.type == KeyEventType.KeyUp) {
                        focusAction(Offset(viewfinderSize.width / 2f, viewfinderSize.height / 2f))
                    }
                    true
                } else false
            }
            .focusable(enabled = canTapFocus)
            .semantics {
                contentDescription = "取景器"
                stateDescription = when {
                    canTapFocus -> "可对焦"
                    ready && manual.focusDiopters != null -> "手动对焦已启用"
                    else -> "相机未就绪"
                }
                if (canTapFocus) {
                    role = Role.Button
                    onClick(label = "中心对焦") {
                        focusAction(Offset(viewfinderSize.width / 2f, viewfinderSize.height / 2f))
                        true
                    }
                } else disabled()
            }.testTag("camera-viewfinder")) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
            if (resumed && streaming) PhotographyOverlay(
                preview, if (rawMode) aids.copy(styledPreview = false) else aids,
                preset, strength, repository.previewQuality, Modifier.fillMaxSize(),
                onAvailability = { aidNotice = it }, intensity = intensity
            )
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { focusAction(it) } })
            focusPoint?.let { point ->
                val state = when (focus) {
                    FocusStatus.FOCUSED -> "已对焦"
                    FocusStatus.FAILED -> "对焦失败"
                    FocusStatus.TIMEOUT -> "对焦超时"
                    FocusStatus.UNAVAILABLE -> "对焦不可用"
                    else -> "正在对焦"
                }
                Column(Modifier.offset {
                    val maxX = (viewfinderSize.width - focusFeedbackSize.width).coerceAtLeast(0)
                    val maxY = (viewfinderSize.height - focusFeedbackSize.height).coerceAtLeast(0)
                    IntOffset(
                        (point.x - focusHalfPx).roundToInt().coerceIn(0, maxX),
                        (point.y - focusHalfPx).roundToInt().coerceIn(0, maxY)
                    )
                }.onSizeChanged { focusFeedbackSize = it }) {
                    Box(Modifier.size(52.dp).border(1.dp, when (focus) {
                        FocusStatus.FOCUSED -> ViewfinderTokens.focus
                        FocusStatus.FAILED, FocusStatus.TIMEOUT, FocusStatus.UNAVAILABLE -> ViewfinderTokens.failure
                        else -> ViewfinderTokens.accent
                    }, RoundedCornerShape(6.dp)))
                    Text(state, Modifier.background(screenPanel).padding(horizontal = 3.dp),
                        color = screenInk, fontSize = 12.sp)
                }
            }
        }
        }
        ViewfinderSystemBarScrims()
        BoxWithConstraints(Modifier.fillMaxSize().safeAreaPadding()) {
            val landscape = maxWidth > maxHeight
            Column(Modifier.fillMaxSize()) {
                CameraHeader(
                    activeMode = if (capabilities == null) mode else activeMode, capabilities = capabilities,
                    modeEnabled = !busy && !applying, flash = flash, manualExposure = manual.iso != null, panel = panel,
                    onFlash = {
                        val next = when (flash) {
                            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                            else -> ImageCapture.FLASH_MODE_OFF
                        }
                        controller.setFlashMode(next) { if (it == null) flash = next else report("闪光设置失败") }
                    },
                    onSelect = ::selectMode,
                    onPro = {
                        if (activeMode == CaptureMode.PRO) panel = if (panel == CameraPanel.PRO) null else CameraPanel.PRO
                        else selectMode(CaptureMode.PRO)
                    },
                    onTools = { panel = if (panel == CameraPanel.TOOLS) null else CameraPanel.TOOLS },
                    onSettings = onOpenSettings
                )
                val content: @Composable (Modifier) -> Unit = { modifier ->
                    BoxWithConstraints(modifier) {
                        val panelModifier = Modifier.align(Alignment.BottomCenter)
                            .widthIn(max = 600.dp).fillMaxWidth().heightIn(max = maxHeight)
                            .background(screenPanel, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                            .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 5.dp)
                        if (panel == null) {
                            val statusText = when {
                                bindError != null && rawMode -> "$bindError · RAW 已关闭，请从拍摄模式切换"
                                bindError != null -> "$bindError · 点按重连默认镜头"
                                remaining > 0 -> "$remaining"
                                busy && focus == FocusStatus.FOCUSING -> "正在对焦"
                                busy -> if (shotCount > 1 && completed >= shotCount) "正在完成成片 $completed/$shotCount"
                                    else if (shotCount > 1) "连拍 ${completed + 1}/$shotCount" else "正在保存"
                                notice != null -> notice
                                activeMode == CaptureMode.MACRO_ASSIST -> "点按近处主体对焦，缓慢调整拍摄距离"
                                activeMode == CaptureMode.LONG_EXPOSURE_ASSIST -> "固定相机后启用倒计时。需要延长曝光时，可在 PRO 中调节快门。"
                                else -> null
                            }
                            statusText?.let {
                                val action = when {
                                    bindError != null && !rawMode -> Modifier.clickable {
                                        cameraId = null
                                        if (mode != CaptureMode.PRO) mode = CaptureMode.AUTO
                                        retry++
                                    }
                                    remaining > 0 -> Modifier.clickable { captureJob?.cancel() }
                                    else -> Modifier
                                }
                                Text(it, modifier = Modifier.align(Alignment.Center)
                                    .background(screenPanel, RoundedCornerShape(12.dp)).then(action).padding(12.dp)
                                    .statusLiveRegion(bindError != null || !busy && remaining == 0 && notice != null),
                                    color = screenInk, fontSize = if (remaining > 0) 38.sp else 14.sp)
                            }
                        } else {
                            Column(panelModifier) {
                                CameraPanelHeading(when (panel) {
                                    CameraPanel.PRO -> "专业控制"
                                    CameraPanel.TOOLS -> "拍摄工具"
                                    else -> "胶片风格"
                                }) { panel = null }
                                when (panel) {
                                    CameraPanel.PRO -> ProfessionalPanel(capabilities, zoom, evIndex, manual, ready,
                                        onZoom = { value -> controller.setZoom(value) { if (it == null) zoom = value else report("变焦请求失败") } },
                                        onEv = { value -> controller.setExposure(value) { if (it == null) evIndex = value else report("曝光补偿失败") } },
                                        onManual = { value ->
                                            applying = true
                                            controller.setManualControls(value) {
                                                applying = false
                                                if (it == null) {
                                                    manual = value
                                                    desiredManual = value
                                                    if (value.iso != null) flash = ImageCapture.FLASH_MODE_OFF
                                                } else report("设备未接受手动参数")
                                            }
                                        })
                                    CameraPanel.TOOLS -> CameraTools(
                                        timer, burst, wide, aids, aidNotice, capabilities, activeMode, ready,
                                        onTimer = { timer = it }, onBurst = { burst = it },
                                        onWide = { if (wide != it) { capabilities = null; wide = it } },
                                        onAids = {
                                            if (it.grid != aids.grid) settings.compositionGrid = it.grid
                                            aids = it
                                            settings.level = it.level; settings.histogram = it.histogram
                                            settings.clipping = it.clipping; settings.peaking = it.peaking
                                        }, onLens = ::selectLens)
                                    CameraPanel.FILM -> FilmPanel(
                                        repository, presets, preset, sample, intensity, strength, aids.styledPreview, !busy,
                                        onSelect = { selected = it.id; repository.selectedStyleId = it.id },
                                        onIntensity = { intensity = it; repository.styleIntensity = it },
                                        onStrength = { strength = it; repository.optimizationStrength = it },
                                        onLive = { aids = aids.copy(styledPreview = !aids.styledPreview) })
                                    else -> Unit
                                }
                                if (bindError != null) Text(
                                    if (rawMode) "$bindError · RAW 已关闭，请从拍摄模式切换"
                                    else "$bindError · 收起面板后可重连默认镜头",
                                    modifier = Modifier.statusLiveRegion(true),
                                    color = screenAccent, fontSize = 12.sp)
                                if (notice != null || busy) Text(
                                    if (remaining > 0) "倒计时 $remaining 秒"
                                    else if (busy && completed >= shotCount) "正在完成成片 $completed/$shotCount"
                                    else if (busy) "正在拍摄 ${completed + 1}/$shotCount" else notice.orEmpty(),
                                    modifier = Modifier.statusLiveRegion(!busy && remaining == 0 && notice != null),
                                    color = screenAccent, fontSize = 12.sp)
                            }
                        }
                    }
                }
                val strip: @Composable () -> Unit = {
                    if (rawMode) {
                        Text("仅保存 DNG · $RAW_OUTPUT_DIRECTORY · 无胶片成片", Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp).background(screenPanel).padding(horizontal = 14.dp, vertical = 10.dp),
                            color = screenInk, fontSize = 12.sp)
                    } else CameraFilmSwitch(preset, capabilities, zoom, timer, burst, !busy, panel == CameraPanel.FILM) {
                        panel = if (panel == CameraPanel.FILM) null else CameraPanel.FILM
                    }
                }
                val actions: @Composable (Boolean) -> Unit = { vertical ->
                    val cameras = capabilities?.availableCameras.orEmpty()
                    CameraActions(vertical, if (roomy) rail else 86.dp, ready, !busy, latestCapture, cameraState,
                        !busy && !applying && cameras.size > 1, ::trigger,
                        onOpenGallery, {
                            val other = cameras.firstOrNull { it.facing != capabilities?.lensFacing && it.id != capabilities?.cameraId }
                                ?: cameras.firstOrNull { it.id != capabilities?.cameraId }
                            other?.let { selectLens(it.id) }
                        })
                }
                if (landscape || roomy) {
                    Row(Modifier.weight(1f)) {
                        Column(Modifier.weight(1f)) { content(Modifier.weight(1f).fillMaxWidth()); strip() }
                        actions(true)
                    }
                } else {
                    content(Modifier.weight(1f).fillMaxWidth())
                    strip()
                    actions(false)
                }
            }
        }
        }
    }
}

internal data class CameraPane(val start: Int, val end: Int)

internal fun unobstructedPane(size: Int, hingeStart: Int, hingeEnd: Int): CameraPane {
    if (size <= 0) return CameraPane(0, size.coerceAtLeast(0))
    val start = hingeStart.coerceIn(0, size)
    val end = hingeEnd.coerceIn(0, size)
    if (end < start || (start == end && (start == 0 || start == size))) return CameraPane(0, size)
    return if (start >= size - end) CameraPane(0, start) else CameraPane(end, size)
}

@Composable
private fun CameraHeader(
    activeMode: CaptureMode, capabilities: DeviceCapabilities?, modeEnabled: Boolean, flash: Int,
    manualExposure: Boolean, panel: CameraPanel?, onFlash: () -> Unit, onSelect: (CaptureMode) -> Unit,
    onPro: () -> Unit, onTools: () -> Unit, onSettings: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val panelColor = Panel
    val accent = Accent
    val flashAllowed = !manualExposure && (activeMode == CaptureMode.AUTO || activeMode == CaptureMode.PRO)
    val flashState = when (flash) {
        ImageCapture.FLASH_MODE_AUTO -> "自动闪光"
        ImageCapture.FLASH_MODE_ON -> "闪光灯已开启"
        else -> "闪光灯已关闭"
    }
    val flashButton: @Composable () -> Unit = {
        CameraIcon(
            if (flash == ImageCapture.FLASH_MODE_AUTO) Icons.Default.FlashAuto
            else if (flash == ImageCapture.FLASH_MODE_ON) Icons.Default.FlashOn else Icons.Default.FlashOff,
            "闪光灯", modeEnabled && capabilities?.supportsFlash == true && flashAllowed, onFlash,
            flash != ImageCapture.FLASH_MODE_OFF, stateDescription = flashState
        )
    }
    val modeControl: @Composable (Modifier) -> Unit = { controlModifier ->
        Box(controlModifier) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .clickable(enabled = modeEnabled) { menu = true }
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics {
                    contentDescription = "拍摄模式"
                    stateDescription = "${activeMode.label}；${if (menu) "已展开" else "已收起"}"
                    role = Role.Button
                }, verticalAlignment = Alignment.CenterVertically) {
                Text(activeMode.label, Modifier.weight(1f), color = accent,
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowDropDown, null, tint = accent)
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                val modes = listOf(
                    Triple(CaptureMode.AUTO, "自动", true), Triple(CaptureMode.PRO, "专业", true),
                    Triple(CaptureMode.RAW, CaptureMode.RAW.label, capabilities?.rawAvailability == RawAvailability.AVAILABLE),
                    Triple(CaptureMode.HDR_EXTENSION, "HDR 扩展", capabilities?.hdrExtensionAvailable == true),
                    Triple(CaptureMode.NIGHT_EXTENSION, "夜景扩展", capabilities?.nightExtensionAvailable == true),
                    Triple(CaptureMode.PORTRAIT_EXTENSION, "人像扩展", capabilities?.portraitExtensionAvailable == true),
                    Triple(CaptureMode.LANDSCAPE_PROFILE, "风景 · 4:3 高画质", true),
                    Triple(CaptureMode.STREET_PROFILE, "街拍 · 快速响应", true),
                    Triple(CaptureMode.MACRO_ASSIST, "近摄辅助 · 点按对焦", true),
                    Triple(CaptureMode.LONG_EXPOSURE_ASSIST, "长曝光辅助 · 持稳指引", true)
                )
                modes.forEach { (value, text, enabled) ->
                    DropdownMenuItem(
                        text = { Text(if (enabled) text else "$text · 镜头不可用") },
                        modifier = Modifier.semantics { selected = activeMode == value },
                        trailingIcon = if (activeMode == value) {
                            { Icon(Icons.Default.Check, contentDescription = "当前模式") }
                        } else null,
                        enabled = enabled, onClick = { menu = false; onSelect(value) }
                    )
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().background(panelColor)) {
        val compact = maxWidth < 420.dp || LocalDensity.current.fontScale >= 1.2f
        if (compact) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    flashButton()
                    modeControl(Modifier.weight(1f))
                    CameraIcon(Icons.Default.Tune, "专业模式", modeEnabled, onPro, activeMode == CaptureMode.PRO,
                        stateDescription = "${if (activeMode == CaptureMode.PRO) "当前专业模式" else "未选择专业模式"}；${if (panel == CameraPanel.PRO) "面板已展开" else "面板已收起"}")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    CameraIcon(Icons.Default.GridOn, "拍摄工具", modeEnabled, onTools,
                        stateDescription = if (panel == CameraPanel.TOOLS) "面板已展开" else "面板已收起")
                    CameraIcon(Icons.Default.Settings, "设置", modeEnabled, onSettings)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                flashButton()
                modeControl(Modifier.weight(1f))
                CameraIcon(Icons.Default.Tune, "专业模式", modeEnabled, onPro, activeMode == CaptureMode.PRO,
                    stateDescription = "${if (activeMode == CaptureMode.PRO) "当前专业模式" else "未选择专业模式"}；${if (panel == CameraPanel.PRO) "面板已展开" else "面板已收起"}")
                CameraIcon(Icons.Default.GridOn, "拍摄工具", modeEnabled, onTools,
                    stateDescription = if (panel == CameraPanel.TOOLS) "面板已展开" else "面板已收起")
                CameraIcon(Icons.Default.Settings, "设置", modeEnabled, onSettings)
            }
        }
    }
}

@Composable
internal fun CameraIcon(
    icon: ImageVector, name: String, enabled: Boolean, click: () -> Unit,
    active: Boolean = false, stateDescription: String? = null
) {
    val ink = Ink
    val muted = Muted
    val accent = Accent
    val iconState = stateDescription
    IconButton(onClick = click, enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { iconState?.let { this.stateDescription = it } }) {
        Icon(icon, name, tint = when { !enabled -> muted.copy(alpha = .65f); active -> accent; else -> ink })
    }
}

@Composable
private fun CameraPanelHeading(title: String, close: () -> Unit) {
    val ink = Ink
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        CameraIcon(Icons.Default.Close, "收起面板", true, close)
    }
}

@Composable
private fun CameraFilmSwitch(
    preset: FilmPreset, caps: DeviceCapabilities?, zoom: Float, timer: Int, burst: Int,
    enabled: Boolean, expanded: Boolean, click: () -> Unit
) {
    val panelColor = Panel
    val ink = Ink
    val muted = Muted
    val accent = Accent
    val summary = buildList {
        caps?.focalLengthsMm?.singleOrNull()?.let { add("${"%.1f".format(it)} mm") }
        caps?.let { add("${"%.1f".format(zoom)}×") }
        if (timer > 0) add("${timer}s")
        if (burst > 1) add("$burst 张")
    }.joinToString("  ")
    BoxWithConstraints(Modifier.fillMaxWidth().background(panelColor).padding(horizontal = 14.dp)) {
        val compact = maxWidth < 420.dp || LocalDensity.current.fontScale >= 1.2f
        val summaryMaxWidth = maxWidth * .5f
        val styleControl: @Composable (Modifier) -> Unit = { controlModifier ->
            Row(controlModifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = click)
                .semantics {
                    contentDescription = "胶片风格"
                    stateDescription = "${preset.name}；${if (expanded) "面板已展开" else "面板已收起"}"
                }.alpha(if (enabled) 1f else .55f), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Filter, null, tint = accent, modifier = Modifier.size(19.dp))
                Text(preset.name, Modifier.weight(1f).padding(horizontal = 7.dp), color = ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            }
        }
        if (compact) {
            Column(Modifier.fillMaxWidth()) {
                styleControl(Modifier.fillMaxWidth())
                if (summary.isNotEmpty()) Text(summary, Modifier.fillMaxWidth()
                    .padding(start = 7.dp, bottom = 6.dp)
                    .semantics { contentDescription = "拍摄信息"; stateDescription = summary },
                    color = muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                styleControl(Modifier.weight(1f))
                if (summary.isNotEmpty()) Text(summary, Modifier.widthIn(max = summaryMaxWidth)
                    .padding(start = 6.dp)
                    .semantics { contentDescription = "拍摄信息"; stateDescription = summary },
                    color = muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun CameraActions(
    vertical: Boolean, railWidth: androidx.compose.ui.unit.Dp, ready: Boolean,
    canOpenGallery: Boolean, latestCapture: String?, cameraState: String,
    canSwitch: Boolean, capture: () -> Unit, gallery: () -> Unit, switchLens: () -> Unit
) {
    val panelColor = Panel
    val ink = Ink
    val muted = Muted
    val galleryButton: @Composable () -> Unit = { GalleryShortcut(latestCapture, canOpenGallery, gallery) }
    val switchButton: @Composable () -> Unit = { CameraIcon(Icons.Default.Cameraswitch, "切换镜头", canSwitch, switchLens) }
    val shutter: @Composable () -> Unit = {
        Box(Modifier.size(68.dp).clip(CircleShape).border(2.dp, if (ready) ink else muted, CircleShape)
            .clickable(enabled = ready, role = Role.Button, onClick = capture)
            .semantics {
                contentDescription = "拍照"
                stateDescription = if (ready) "可拍摄；$cameraState" else "相机未就绪；$cameraState"
            }
            .padding(6.dp).background(if (ready) ink else muted, CircleShape))
    }
    if (vertical) {
        BoxWithConstraints(Modifier.fillMaxHeight().background(panelColor)) {
            if (maxHeight < 176.dp) {
                Column(Modifier.fillMaxHeight().width(104.dp).verticalScroll(rememberScrollState()),
                    Arrangement.SpaceEvenly, Alignment.CenterHorizontally) {
                    Row { galleryButton(); switchButton() }
                    shutter()
                }
            } else Column(Modifier.fillMaxHeight().width(railWidth), Arrangement.SpaceEvenly, Alignment.CenterHorizontally) {
                galleryButton(); shutter(); switchButton()
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().background(panelColor).heightIn(min = 80.dp),
            Arrangement.SpaceAround, Alignment.CenterVertically) { galleryButton(); shutter(); switchButton() }
    }
}

@Composable
private fun GalleryShortcut(uri: String?, enabled: Boolean, open: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val ink = Ink
    val muted = Muted
    val edge = with(LocalDensity.current) { 38.dp.roundToPx().coerceAtLeast(1) }
    IconButton(onClick = open, enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { contentDescription = "作品" }) {
        key(uri, edge) {
            val thumbnail by produceState<Bitmap?>(null, resolver) {
                if (uri != null) value = withContext(Dispatchers.IO) {
                    PhotoEnhancementEngine.renderPreview(resolver, Uri.parse(uri), edge)
                }
            }
            val image = thumbnail
            if (image == null) {
                Icon(Icons.Default.PhotoLibrary, null, tint = if (enabled) ink else muted)
            } else {
                Image(image.asImageBitmap(), null,
                    Modifier.size(38.dp).clip(RoundedCornerShape(7.dp)).alpha(if (enabled) 1f else .55f),
                    contentScale = ContentScale.Crop)
            }
        }
    }
}
