package com.lumora.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.lumora.camera.camera.DeviceCapabilities
import com.lumora.camera.data.OptimizationStrength
import com.lumora.camera.data.PhotoRepository
import com.lumora.camera.ui.AppThemeMode
import com.lumora.camera.ui.CompositionGrid
import com.lumora.camera.ui.LumoraSpacing
import com.lumora.camera.ui.safeAreaPadding
import com.lumora.camera.update.UpdateFrequency
import com.lumora.camera.update.UpdateState
import com.lumora.camera.update.UpdateViewModel
import kotlin.reflect.KMutableProperty0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    repository: PhotoRepository, lens: DeviceCapabilities?, updates: UpdateViewModel,
    themeMode: AppThemeMode = AppThemeMode.SYSTEM, onThemeChange: (AppThemeMode) -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember(context) { CameraSettings(context) }
    val records = remember(repository) { repository.getRecords() }
    var previewQuality by remember { mutableStateOf(repository.previewQuality) }
    var strength by remember { mutableStateOf(repository.optimizationStrength) }
    var jpegQuality by remember { mutableIntStateOf(settings.jpegQuality) }
    var compositionGrid by remember { mutableStateOf(settings.compositionGrid) }
    Column(Modifier.fillMaxSize().background(Background).safeAreaPadding().testTag("settings-screen")) {
        TopAppBar(title = { Text("设置") }, navigationIcon = {
            IconButton(modifier = Modifier.size(48.dp), onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            }
        }, windowInsets = WindowInsets(0))
        Column(Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
            .verticalScroll(rememberScrollState()).padding(horizontal = LumoraSpacing.page)) {
            Section("外观")
            Choice("应用主题", themeMode.label, AppThemeMode.entries.map { it.label }) { label ->
                onThemeChange(AppThemeMode.entries.first { it.label == label })
            }
            Text("取景器保留深色控制层，以适应明暗变化的拍摄场景。", color = Muted, style = MaterialTheme.typography.bodySmall)
            Section("拍摄")
            Toggle("音量键快门", settings::volumeShutter)
            Toggle("拍摄提示音", settings::shutterSound)
            Choice("预览分辨率", previewQuality, listOf("省电", "标准", "高质量")) {
                repository.previewQuality = it
                previewQuality = it
            }
            Section("摄影辅助")
            Toggle("实时直方图", settings::histogram)
            Toggle("高光与暗部警告", settings::clipping)
            Toggle("对焦峰值", settings::peaking)
            Toggle("水平仪", settings::level)
            Choice("构图网格", compositionGrid.label, CompositionGrid.entries.map { it.label }) { label ->
                compositionGrid = CompositionGrid.entries.first { it.label == label }
                settings.compositionGrid = compositionGrid
            }
            Section("成片")
            Choice("默认优化强度", strength.label, OptimizationStrength.entries.map { it.label }) { label ->
                strength = OptimizationStrength.entries.first { it.label == label }
                repository.optimizationStrength = strength
            }
            Choice("JPEG 保存质量", jpegQuality.toString(), listOf("90", "95", "100")) {
                jpegQuality = it.toInt()
                settings.jpegQuality = jpegQuality
            }
            Section("存储")
            Info("保存目录", "Pictures/LUMORA")
            Info("作品库", "${records.size} 个版本")
            Text("每次拍摄保留原图。编辑会另存一张成片。", color = Muted, style = MaterialTheme.typography.bodySmall)
            Section("设备能力")
            if (lens == null) {
                Text("等待镜头就绪", color = Muted)
            } else {
                Info("最近使用的镜头", "${lens.lensFacing} · ID ${lens.cameraId}")
                Info("可选择的相机", lens.availableCameras.joinToString(" / ") { it.id }.ifEmpty { "未报告" })
                if (lens.physicalCameraIds.isNotEmpty()) Info("逻辑镜头包含的物理 ID", lens.physicalCameraIds.joinToString(" / "))
                Info("Camera2 硬件级别", lens.hardwareLevel ?: "未报告")
                Info("物理焦距", lens.focalLengthsMm.joinToString(" / ") { "$it mm" }.ifEmpty { "未报告" })
                Info("最大 JPEG 规格", lens.jpegSizes.firstOrNull()?.let { "${it.first} × ${it.second}" } ?: "未报告")
                Info("ISO 范围", lens.isoSummary())
                Info("曝光时间", lens.exposureSummary())
                Info("光学防抖", if (lens.supportsOpticalStabilization) "硬件支持" else "未报告支持")
                Info("厂商扩展", listOfNotNull("HDR".takeIf { lens.hdrExtensionAvailable },
                    "夜景".takeIf { lens.nightExtensionAvailable }, "人像".takeIf { lens.portraitExtensionAvailable })
                    .joinToString(" / ").ifEmpty { "无" })
                Info("RAW / DNG", lens.rawAvailability.unavailableReason ?: "可用 · RAW 模式独立保存 DNG")
            }
            Section("关于")
            Info("LUMORA Camera", updates.installedVersion)
            Choice("检查更新频率", updates.frequency.label, UpdateFrequency.entries.map { it.label }, updates::changeFrequency)
            TextButton(onClick = { updates.check(manual = true) }, enabled = updates.state != UpdateState.Checking) {
                Text(if (updates.state == UpdateState.Checking) "正在检查…" else "检查更新")
            }
            (updates.state as? UpdateState.Notice)?.let {
                Text(it.text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Muted)
            }
            Spacer(Modifier.height(LumoraSpacing.section))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(LumoraSpacing.section))
    Text(title, Modifier.semantics { heading() }, color = Accent, style = MaterialTheme.typography.titleSmall)
    HorizontalDivider(Modifier.padding(top = LumoraSpacing.small), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun Toggle(title: String, setting: KMutableProperty0<Boolean>) {
    var checked by remember { mutableStateOf(setting.get()) }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch) {
        checked = it
        setting.set(it)
    }.padding(vertical = LumoraSpacing.small), horizontalArrangement = Arrangement.spacedBy(LumoraSpacing.medium),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun Choice(title: String, value: String, options: List<String>, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("settings-choice-$title")
            .semantics { stateDescription = value }
            .clickable(role = Role.Button, onClickLabel = "选择$title") { open = true }
            .padding(vertical = LumoraSpacing.small),
            horizontalArrangement = Arrangement.spacedBy(LumoraSpacing.medium),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            Text(value, Modifier.weight(1f), color = Accent, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            Icon(Icons.Default.ExpandMore, null)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { select(option); open = false },
                    trailingIcon = { if (option == value) Icon(Icons.Default.Check, null) },
                    modifier = Modifier.testTag("settings-$title-$option").semantics { selected = option == value })
            }
        }
    }
}

@Composable
private fun Info(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = LumoraSpacing.medium).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(LumoraSpacing.xs)) {
        Text(title, color = Muted, style = MaterialTheme.typography.labelLarge)
        Text(value, Modifier.fillMaxWidth(), color = Ink, style = MaterialTheme.typography.bodyMedium)
    }
}
