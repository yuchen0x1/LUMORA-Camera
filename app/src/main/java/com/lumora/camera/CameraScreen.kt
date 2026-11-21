package com.lumora.camera

import android.graphics.Bitmap
import android.media.MediaActionSound
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import com.lumora.camera.ui.CompositionGrid
import com.lumora.camera.ui.PhotoAids
import com.lumora.camera.ui.PhotographyOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class CameraPanel { PRO, TOOLS, FILM }

@Composable
internal fun CameraScreen(
    repository: PhotoRepository,
    presets: List<FilmPreset>,
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    onCapabilities: (DeviceCapabilities) -> Unit = {}
) {
    val context = LocalContext.current
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
    var cameraId by rememberSaveable { mutableStateOf<String?>(null) }
    var wide by rememberSaveable { mutableStateOf(false) }
    var panel by rememberSaveable { mutableStateOf<CameraPanel?>(null) }
    var timer by rememberSaveable { mutableIntStateOf(0) }
    var burst by rememberSaveable { mutableIntStateOf(1) }
    var retry by remember { mutableIntStateOf(0) }
    var bindError by remember { mutableStateOf<String?>(null) }
    var focus by remember { mutableStateOf<FocusStatus?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusAttempt by remember { mutableIntStateOf(0) }
    var flash by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var evIndex by remember { mutableIntStateOf(0) }
    var manual by remember { mutableStateOf(ManualControls()) }
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
                    snapshot?.let(latestOnCapabilities)
                }
            }, onError = { error ->
                if (current) bindError = error.localizedMessage ?: "相机连接失败"
            }, cameraId = cameraId, mode = mode, wide = wide, jpegQuality = settings.jpegQuality)
        }
        onDispose {
            current = false
            controller.release()
        }
    }

    LaunchedEffect(noticeId) { if (notice != null) { delay(4300); notice = null } }
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
        mode = CaptureMode.AUTO
        panel = null
    }
    val ready = resumed && streaming && capabilities != null && !busy && !applying
    fun trigger() {
        val caps = capabilities ?: return
        if (!ready || busy) return
        busy = true
        completed = 0
        val count = burst
        val options = ShotOptions(preset, strength, intensity, settings.jpegQuality, caps, activeMode, manual)
        captureJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (tick in timer downTo 1) { remaining = tick; delay(1000) }
                remaining = 0
                var message = ""
                var unconfirmed = 0
                for (index in 1..count) {
                    var shutterFocus: FocusStatus? = null
                    val result = CameraOutput.take(context, repository, controller, options,
                        onFocus = {
                            shutterFocus = it
                            if (resumed) focus = it
                        },
                        onSourceSaved = { if (resumed) sound?.play(MediaActionSound.SHUTTER_CLICK) })
                    completed = index
                    if (shutterFocus == FocusStatus.FAILED || shutterFocus == FocusStatus.TIMEOUT) unconfirmed++
                    if (!result.enhanced) {
                        message = (result.notice ?: "已保存原片 · 成片处理失败") + "（$index/$count）"
                        break
                    }
                    message = result.notice ?: if (count == 1) "已保存 · ${preset.name}" else "已保存 $index/$count 张"
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
    val tapFocus = { point: Offset ->
        if (ready && manual.focusDiopters == null) {
            focusAttempt++
            val token = focusAttempt
            focusPoint = point
            controller.focus(preview, point.x, point.y) { if (token == focusAttempt) focus = it }
        }
    }
    val focusAction by rememberUpdatedState(tapFocus)
    val focusHalfPx = with(LocalDensity.current) { 26.dp.toPx() }
    val cameraState = capabilities?.let { "${it.lensFacing} · ${it.cameraId} · ${activeMode.name}" } ?: "连接中"

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
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
        val longSideRatio = if (wide) 16f / 9f else 4f / 3f
        val viewfinderRatio = if (maxWidth > maxHeight) longSideRatio else 1f / longSideRatio
        val roomy = maxWidth >= 600.dp && maxHeight >= 420.dp
        val rail = 96.dp
        val previewSpace = if (roomy) Modifier.padding(end = rail) else Modifier
        Box(Modifier.fillMaxSize().then(previewSpace), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(viewfinderRatio).clipToBounds().testTag("camera-viewfinder")) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
            if (resumed && streaming) PhotographyOverlay(
                preview, aids, preset, strength, repository.previewQuality, Modifier.fillMaxSize(),
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
                Column(Modifier.offset { IntOffset((point.x - focusHalfPx).roundToInt(), (point.y - focusHalfPx).roundToInt()) }) {
                    Box(Modifier.size(52.dp).border(1.dp, when (focus) {
                        FocusStatus.FOCUSED -> Color(0xFFA2D8A1)
                        FocusStatus.FAILED, FocusStatus.TIMEOUT, FocusStatus.UNAVAILABLE -> Color(0xFFFFA699)
                        else -> Accent
                    }, RoundedCornerShape(6.dp)))
                    Text(state, Modifier.background(Panel).padding(horizontal = 3.dp), color = Ink, fontSize = 10.sp)
                }
            }
        }
        }
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeContent)) {
            val landscape = maxWidth > maxHeight
            Column(Modifier.fillMaxSize()) {
                CameraHeader(
                    activeMode = if (capabilities == null) mode else activeMode, capabilities = capabilities,
                    modeEnabled = !busy && !applying, flash = flash, manualExposure = manual.iso != null,
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
                            .background(Panel, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                            .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 5.dp)
                        if (panel == null) {
                            val statusText = when {
                                bindError != null -> "$bindError · 点按重连默认镜头"
                                remaining > 0 -> "$remaining"
                                busy && focus == FocusStatus.FOCUSING -> "正在对焦"
                                busy -> if (burst > 1) "连拍 ${completed + 1}/$burst" else "正在保存"
                                notice != null -> notice
                                activeMode == CaptureMode.MACRO_ASSIST -> "点按近处主体对焦，缓慢调整拍摄距离"
                                activeMode == CaptureMode.LONG_EXPOSURE_ASSIST -> "固定相机后启用倒计时。需要延长曝光时，可在 PRO 中调节快门。"
                                else -> null
                            }
                            statusText?.let {
                                val action = when {
                                    bindError != null -> Modifier.clickable {
                                        cameraId = null
                                        mode = CaptureMode.AUTO
                                        retry++
                                    }
                                    remaining > 0 -> Modifier.clickable { captureJob?.cancel() }
                                    else -> Modifier
                                }
                                Text(it, modifier = Modifier.align(Alignment.Center)
                                    .background(Panel, RoundedCornerShape(12.dp)).then(action).padding(12.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                                    color = Ink, fontSize = if (remaining > 0) 38.sp else 14.sp)
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
                                if (notice != null || busy) Text(
                                    if (remaining > 0) "倒计时 $remaining 秒" else if (busy) "正在拍摄 ${completed + 1}/$burst" else notice.orEmpty(),
                                    color = Accent, fontSize = 12.sp)
                            }
                        }
                    }
                }
                val strip: @Composable () -> Unit = {
                    CameraFilmSwitch(preset, capabilities, zoom, timer, burst, !busy) {
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
    if (hingeStart <= 0 || hingeEnd < hingeStart || hingeEnd >= size) return CameraPane(0, size)
    return if (hingeStart >= size - hingeEnd) CameraPane(0, hingeStart) else CameraPane(hingeEnd, size)
}

@Composable
private fun CameraHeader(
    activeMode: CaptureMode, capabilities: DeviceCapabilities?, modeEnabled: Boolean, flash: Int, manualExposure: Boolean,
    onFlash: () -> Unit, onSelect: (CaptureMode) -> Unit, onPro: () -> Unit,
    onTools: () -> Unit, onSettings: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val flashAllowed = !manualExposure && (activeMode == CaptureMode.AUTO || activeMode == CaptureMode.PRO)
        CameraIcon(if (flash == ImageCapture.FLASH_MODE_AUTO) Icons.Default.FlashAuto else if (flash == ImageCapture.FLASH_MODE_ON) Icons.Default.FlashOn else Icons.Default.FlashOff,
            "闪光灯", modeEnabled && capabilities?.supportsFlash == true && flashAllowed, onFlash, flash != ImageCapture.FLASH_MODE_OFF)
        Box(Modifier.weight(1f)) {
            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = modeEnabled) { menu = true }
                .heightIn(min = 48.dp).semantics { contentDescription = "拍摄模式" },
                verticalAlignment = Alignment.CenterVertically) {
                Text(activeMode.label, Modifier.weight(1f, fill = false), color = Accent,
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowDropDown, null, tint = Accent)
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                val modes = listOf(
                    Triple(CaptureMode.AUTO, "自动", true), Triple(CaptureMode.PRO, "专业", true),
                    Triple(CaptureMode.HDR_EXTENSION, "HDR 扩展", capabilities?.hdrExtensionAvailable == true),
                    Triple(CaptureMode.NIGHT_EXTENSION, "夜景扩展", capabilities?.nightExtensionAvailable == true),
                    Triple(CaptureMode.PORTRAIT_EXTENSION, "人像扩展", capabilities?.portraitExtensionAvailable == true),
                    Triple(CaptureMode.LANDSCAPE_PROFILE, "风景 · 4:3 高画质", true),
                    Triple(CaptureMode.STREET_PROFILE, "街拍 · 快速响应", true),
                    Triple(CaptureMode.MACRO_ASSIST, "近摄辅助 · 点按对焦", true),
                    Triple(CaptureMode.LONG_EXPOSURE_ASSIST, "长曝光辅助 · 持稳指引", true)
                )
                modes.forEach { (value, text, enabled) ->
                    DropdownMenuItem(text = { Text(if (enabled) text else "$text · 镜头不可用") }, enabled = enabled,
                        onClick = { menu = false; onSelect(value) })
                }
            }
        }
        CameraIcon(Icons.Default.Tune, "专业模式", modeEnabled, onPro, activeMode == CaptureMode.PRO)
        CameraIcon(Icons.Default.GridOn, "拍摄工具", modeEnabled, onTools)
        CameraIcon(Icons.Default.Settings, "设置", modeEnabled, onSettings)
    }
}

@Composable
internal fun CameraIcon(icon: ImageVector, name: String, enabled: Boolean, click: () -> Unit, active: Boolean = false) {
    IconButton(onClick = click, enabled = enabled) {
        Icon(icon, name, tint = when { !enabled -> Muted.copy(alpha = .65f); active -> Accent; else -> Ink })
    }
}

@Composable
private fun CameraPanelHeading(title: String, close: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        CameraIcon(Icons.Default.Close, "收起面板", true, close)
    }
}

@Composable
private fun CameraFilmSwitch(preset: FilmPreset, caps: DeviceCapabilities?, zoom: Float, timer: Int, burst: Int, enabled: Boolean, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.weight(1f).clickable(enabled = enabled, onClick = click)
            .heightIn(min = 48.dp).semantics { contentDescription = "胶片风格" },
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Filter, null, tint = Accent, modifier = Modifier.size(19.dp))
            Text(preset.name, Modifier.padding(horizontal = 7.dp), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
        }
        Text(buildList {
            caps?.focalLengthsMm?.singleOrNull()?.let { add("${"%.1f".format(it)} mm") }
            caps?.let { add("${"%.1f".format(zoom)}×") }
            if (timer > 0) add("${timer}s")
            if (burst > 1) add("$burst 张")
        }.joinToString("  "), color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun CameraActions(
    vertical: Boolean, railWidth: androidx.compose.ui.unit.Dp, ready: Boolean,
    canOpenGallery: Boolean, latestCapture: String?, cameraState: String,
    canSwitch: Boolean, capture: () -> Unit, gallery: () -> Unit, switchLens: () -> Unit
) {
    val galleryButton: @Composable () -> Unit = { GalleryShortcut(latestCapture, canOpenGallery, gallery) }
    val switchButton: @Composable () -> Unit = { CameraIcon(Icons.Default.Cameraswitch, "切换镜头", canSwitch, switchLens) }
    val shutter: @Composable () -> Unit = {
        Box(Modifier.size(68.dp).clip(CircleShape).border(2.dp, if (ready) Ink else Muted, CircleShape)
            .clickable(enabled = ready, role = Role.Button, onClick = capture)
            .semantics { contentDescription = "拍照"; stateDescription = cameraState }
            .padding(6.dp).background(if (ready) Ink else Muted, CircleShape))
    }
    if (vertical) {
        BoxWithConstraints(Modifier.fillMaxHeight().background(Panel)) {
            if (maxHeight < 176.dp) {
                Column(Modifier.fillMaxHeight().width(104.dp), Arrangement.SpaceEvenly, Alignment.CenterHorizontally) {
                    Row { galleryButton(); switchButton() }
                    shutter()
                }
            } else Column(Modifier.fillMaxHeight().width(railWidth), Arrangement.SpaceEvenly, Alignment.CenterHorizontally) {
                galleryButton(); shutter(); switchButton()
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().background(Panel).heightIn(min = 80.dp),
            Arrangement.SpaceAround, Alignment.CenterVertically) { galleryButton(); shutter(); switchButton() }
    }
}

@Composable
private fun GalleryShortcut(uri: String?, enabled: Boolean, open: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
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
                Icon(Icons.Default.PhotoLibrary, null, tint = if (enabled) Ink else Muted)
            } else {
                Image(image.asImageBitmap(), null, Modifier.size(38.dp).clip(RoundedCornerShape(7.dp)),
                    contentScale = ContentScale.Crop)
            }
        }
    }
}
