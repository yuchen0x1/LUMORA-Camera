package com.lumora.camera

import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest as R
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumora.camera.camera.CaptureMode
import com.lumora.camera.camera.DeviceCapabilities
import com.lumora.camera.camera.ManualControls
import com.lumora.camera.data.*
import com.lumora.camera.image.PhotoEnhancementEngine
import com.lumora.camera.ui.CompositionGrid
import com.lumora.camera.ui.PhotoAids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
private fun Option(label: String, selected: Boolean, enabled: Boolean, choose: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(enabled = enabled, role = Role.RadioButton, onClick = choose)
        .semantics { this.selected = selected }.heightIn(min = 44.dp).padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (!enabled) Muted.copy(alpha = .65f) else if (selected) Accent else Ink,
            fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun Options(label: String? = null, content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        label?.let { Text(it, Modifier.widthIn(min = 54.dp), color = Muted, fontSize = 13.sp) }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun Adjust(
    label: String, position: Float, limits: ClosedFloatingPointRange<Float>, enabled: Boolean,
    format: (Float) -> String, commit: (Float) -> Unit
) {
    var sliding by remember(label, position) { mutableStateOf<Float?>(null) }
    val value = (sliding ?: position).coerceIn(limits.start, limits.endInclusive)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 13.sp)
        if (limits.endInclusive > limits.start) {
            Slider(value = value, valueRange = limits, enabled = enabled,
                onValueChange = { sliding = it }, onValueChangeFinished = {
                    sliding?.let(commit)
                    sliding = null
                }, modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { contentDescription = label },
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
        } else Spacer(Modifier.weight(1f))
        Text(format(value), color = Accent, fontSize = 12.sp)
    }
}

@Composable
internal fun ProfessionalPanel(
    caps: DeviceCapabilities?, zoom: Float, ev: Int, manual: ManualControls, enabled: Boolean,
    onZoom: (Float) -> Unit, onEv: (Int) -> Unit, onManual: (ManualControls) -> Unit
) {
    if (caps == null) { PanelHint("正在读取当前镜头能力…"); return }
    val controls = listOf("曝光", "ISO", "快门", "白平衡", "对焦", "变焦", "RAW")
    var tab by rememberSaveable { mutableStateOf("曝光") }
    Options { controls.forEach { Option(it, tab == it, true) { tab = it } } }
    when (tab) {
        "曝光" -> {
            val range = caps.exposureCompensationRange
            val step = caps.exposureCompensationStep
            if (range == null || step == null) PanelHint("当前镜头不支持曝光补偿")
            else if (manual.iso != null || manual.aeLock) PanelHint("使用自动曝光并解除 AE 锁定后可调节曝光补偿")
            else Adjust("曝光补偿", ev.toFloat(), range.min.toFloat()..range.max.toFloat(), enabled,
                { "%+.2f EV".format(Locale.US, it.roundToInt() * step) }, { onEv(it.roundToInt()) })
            if (caps.supportsAeLock) Options {
                Option("AE 锁定", manual.aeLock, enabled && manual.iso == null) { onManual(manual.copy(aeLock = !manual.aeLock)) }
            }
        }
        "ISO", "快门" -> {
            val isoRange = caps.isoRange
            val times = caps.exposureTimeRangeNs
            if (!caps.supportsManualExposure || isoRange == null || times == null) {
                PanelHint("当前镜头未提供可用的手动曝光请求")
            } else {
                Options {
                    Option("自动曝光", manual.iso == null, enabled) { onManual(manual.copy(iso = null, exposureTimeNs = null)) }
                    Option("手动曝光", manual.iso != null, enabled) { if (manual.iso == null)
                        onManual(manual.copy(iso = isoRange.min, exposureTimeNs = times.clamp(8_000_000), aeLock = false)) }
                }
                if (manual.iso != null && manual.exposureTimeNs != null) {
                    if (tab == "ISO") {
                        Adjust("感光度", ln(manual.iso.toFloat()), ln(isoRange.min.toFloat())..ln(isoRange.max.toFloat()), enabled,
                            { "ISO ${isoRange.clamp(exp(it).roundToInt())}" },
                            { onManual(manual.copy(iso = isoRange.clamp(exp(it).roundToInt()))) })
                    } else {
                        val from = ln(times.min.toDouble()).toFloat()
                        val to = ln(times.max.toDouble()).toFloat()
                        val toNs = { position: Float -> times.clamp(exp(position.toDouble()).roundToLong()) }
                        Adjust("曝光时间", ln(manual.exposureTimeNs.toDouble()).toFloat(), from..to, enabled,
                            { "%.3f ms".format(Locale.US, toNs(it) / 1e6) },
                            { onManual(manual.copy(exposureTimeNs = toNs(it))) })
                    }
                    PanelHint("滑杆显示请求值，拍摄后的实际曝光以原片 EXIF 为准")
                }
            }
        }
        "白平衡" -> {
            if (caps.whiteBalanceModes.isEmpty()) PanelHint("当前镜头未提供白平衡模式")
            else Options {
                Option("自动", manual.whiteBalanceMode == null, enabled) { onManual(manual.copy(whiteBalanceMode = null)) }
                caps.whiteBalanceModes.filter { it != R.CONTROL_AWB_MODE_AUTO }.forEach { mode ->
                    Option(awbName(mode), manual.whiteBalanceMode == mode, enabled) {
                        onManual(manual.copy(whiteBalanceMode = mode, awbLock = false))
                    }
                }
            }
            if (caps.supportsAwbLock) Options {
                Option("AWB 锁定", manual.awbLock, enabled && manual.whiteBalanceMode == null) {
                    onManual(manual.copy(awbLock = !manual.awbLock))
                }
            }
        }
        "对焦" -> {
            val max = caps.minimumFocusDistance
            if (!caps.supportsManualFocus || max == null) {
                PanelHint(if (caps.supportsAutoFocus) "支持点按自动对焦，手动对焦距离不可用" else "当前镜头未提供可调对焦距离")
            } else {
                Options {
                    Option("自动", manual.focusDiopters == null, enabled) { onManual(manual.copy(focusDiopters = null)) }
                    Option("手动", manual.focusDiopters != null, enabled) { onManual(manual.copy(focusDiopters = manual.focusDiopters ?: 0f)) }
                }
                manual.focusDiopters?.let { distance -> Adjust("对焦距离", distance, 0f..max, enabled,
                    {
                        when {
                            !caps.focusDistanceInDiopters -> "${(it / max * 100).roundToInt()}%（远 → 近）"
                            it == 0f -> "∞"
                            else -> "%.2f D".format(Locale.US, it)
                        }
                    }, { onManual(manual.copy(focusDiopters = it)) }) }
            }
        }
        "变焦" -> {
            val min = caps.zoomMin
            val max = caps.zoomMax
            if (min == null || max == null) PanelHint("当前镜头未报告变焦范围")
            else Adjust("变焦倍率", zoom, min..max, enabled, { "%.2f×".format(Locale.US, it) }, onZoom)
        }
        "RAW" -> PanelHint(if (caps.supportsRaw) "设备报告 RAW 能力，当前版本仅提供 JPEG 输出" else "当前镜头未报告 RAW 能力")
    }
}

private fun awbName(mode: Int): String = when (mode) {
    R.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "阴天"
    R.CONTROL_AWB_MODE_DAYLIGHT -> "日光"
    R.CONTROL_AWB_MODE_FLUORESCENT -> "荧光灯"
    R.CONTROL_AWB_MODE_INCANDESCENT -> "白炽灯"
    R.CONTROL_AWB_MODE_SHADE -> "阴影"
    R.CONTROL_AWB_MODE_TWILIGHT -> "黄昏"
    R.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "暖荧光灯"
    else -> "预设 $mode"
}

@Composable
private fun PanelHint(text: String) {
    Text(text, Modifier.padding(vertical = 6.dp), color = Muted, fontSize = 12.sp)
}

@Composable
internal fun CameraTools(
    timer: Int, burst: Int, wide: Boolean, aids: PhotoAids, availability: String?, caps: DeviceCapabilities?,
    mode: CaptureMode, enabled: Boolean, onTimer: (Int) -> Unit, onBurst: (Int) -> Unit,
    onWide: (Boolean) -> Unit, onAids: (PhotoAids) -> Unit, onLens: (String) -> Unit
) {
    Options("倒计时") {
        listOf(0, 3, 10).forEach { Option(if (it == 0) "关闭" else "$it 秒", it == timer, enabled) { onTimer(it) } }
    }
    Options("连拍") { listOf(1, 3, 5).forEach { Option(if (it == 1) "单张" else "$it 张", it == burst, enabled) { onBurst(it) } } }
    Options("画幅") {
        Option("4:3", !wide, enabled) { onWide(false) }
        Option("16:9", wide, enabled && mode != CaptureMode.LANDSCAPE_PROFILE) { onWide(true) }
    }
    if (caps != null) Options("镜头") {
        caps.availableCameras.forEach {
            val facing = when (it.facing) {
                "BACK" -> "后置"
                "FRONT" -> "前置"
                "EXTERNAL" -> "外接"
                else -> "镜头"
            }
            val lens = it.focalLengthsMm.joinToString("/") { mm -> "%.1f".format(Locale.US, mm) }
            Option("$facing ${it.id}" + if (lens.isEmpty()) "" else " · $lens mm", it.id == caps.cameraId, enabled) {
                if (it.id != caps.cameraId) onLens(it.id)
            }
        }
    }
    Options("构图") { CompositionGrid.entries.forEach { Option(it.label, aids.grid == it, enabled) { onAids(aids.copy(grid = it)) } } }
    Options("辅助") {
        Option("水平仪", aids.level, enabled) { onAids(aids.copy(level = !aids.level)) }
        Option("直方图", aids.histogram, enabled) { onAids(aids.copy(histogram = !aids.histogram)) }
        Option("溢出警告", aids.clipping, enabled) { onAids(aids.copy(clipping = !aids.clipping)) }
        Option("峰值对焦", aids.peaking, enabled) { onAids(aids.copy(peaking = !aids.peaking)) }
    }
    if (aids.clipping) PanelHint("琥珀色标记接近过曝的通道，蓝色标记接近全黑的区域")
    if (aids.peaking) PanelHint("绿色标记取景画面中的高对比边缘")
    availability?.let { PanelHint(it) }
}

private val sampleDispatcher = Dispatchers.IO.limitedParallelism(2)

@Composable
internal fun FilmPanel(
    repository: PhotoRepository, presets: List<FilmPreset>, selected: FilmPreset, sample: String?,
    intensity: Float, strength: OptimizationStrength, styled: Boolean, enabled: Boolean,
    onSelect: (FilmPreset) -> Unit, onIntensity: (Float) -> Unit, onStrength: (OptimizationStrength) -> Unit, onLive: () -> Unit
) {
    var favorites by remember(repository) { mutableStateOf(repository.favoriteStyleIds) }
    var onlyFavorites by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Option("全部", !onlyFavorites, true) { onlyFavorites = false }
        Option("已收藏", onlyFavorites, true) { onlyFavorites = true }
        Spacer(Modifier.weight(1f))
        val favorite = selected.id in favorites
        CameraIcon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (favorite) "取消收藏风格" else "收藏当前风格", enabled, {
            repository.setStyleFavorite(selected.id, !favorite)
            favorites = repository.favoriteStyleIds
        }, favorite)
    }
    val visible = if (onlyFavorites) presets.filter { it.id in favorites } else presets
    if (sample == null) PanelHint("拍摄或导入一张照片后可预览胶片效果")
    if (visible.isEmpty()) PanelHint("点按爱心，收藏当前胶片")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
        items(visible, key = { it.id }) { preset ->
            val chosen = preset.id == selected.id
            Column(Modifier.width(100.dp).clip(RoundedCornerShape(8.dp)).border(
                1.dp, if (chosen) Accent else Color.Transparent, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(preset) }
                .semantics { this.selected = chosen }.padding(5.dp)) {
                if (sample != null) FilmSample(sample, preset, strength, intensity)
                Text(preset.name, color = if (chosen) Accent else Ink, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 6.dp))
            }
        }
    }
    Adjust("风格浓度", intensity, 0f..1f, enabled, { "${(it * 100).roundToInt()}%" }, onIntensity)
    Options("优化") { OptimizationStrength.entries.forEach { Option(it.label, strength == it, enabled) { onStrength(it) } } }
    Option("实时风格预览（采样）", styled, enabled, onLive)
}

@Composable
private fun FilmSample(sample: String, preset: FilmPreset, strength: OptimizationStrength, intensity: Float) {
    val resolver = LocalContext.current.contentResolver
    var done by remember(sample, preset, strength, intensity) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, sample, preset, strength, intensity) {
        value = null
        value = withContext(sampleDispatcher) {
            PhotoEnhancementEngine.renderPreview(resolver, Uri.parse(sample), 240, preset, strength, intensity = intensity)
        }
        done = true
    }
    Box(Modifier.fillMaxWidth().height(54.dp).background(Color.Black), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(if (done) "预览不可用" else "读取中", color = Muted, fontSize = 10.sp)
    }
}
