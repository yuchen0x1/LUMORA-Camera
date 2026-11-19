package com.lumora.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lumora.camera.camera.DeviceCapabilities
import com.lumora.camera.data.OptimizationStrength
import com.lumora.camera.data.PhotoRepository
import com.lumora.camera.update.UpdateFrequency
import com.lumora.camera.update.UpdateState
import com.lumora.camera.update.UpdateViewModel
import kotlin.reflect.KMutableProperty0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    repository: PhotoRepository, lens: DeviceCapabilities?, updates: UpdateViewModel, onBack: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember(context) { CameraSettings(context) }
    val records = remember(repository) { repository.getRecords() }
    var previewQuality by remember { mutableStateOf(repository.previewQuality) }
    var strength by remember { mutableStateOf(repository.optimizationStrength) }
    var jpegQuality by remember { mutableIntStateOf(settings.jpegQuality) }
    Column(Modifier.fillMaxSize().background(Background).safeDrawingPadding()) {
        TopAppBar(title = { Text("设置") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }, windowInsets = WindowInsets(0))
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
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
            Toggle("黄金分割网格", settings::goldenGrid)
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
                Info("RAW / DNG", if (lens.supportsRaw) "硬件支持 · 当前输出 JPEG" else "当前镜头不可用")
            }
            Section("关于")
            Info("LUMORA Camera", updates.installedVersion)
            Choice("检查更新频率", updates.frequency.label, UpdateFrequency.entries.map { it.label }, updates::changeFrequency)
            TextButton(onClick = { updates.check(manual = true) }, enabled = updates.state != UpdateState.Checking) {
                Text(if (updates.state == UpdateState.Checking) "正在检查…" else "检查更新")
            }
            (updates.state as? UpdateState.Notice)?.let { Text(it.text, color = Muted) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, color = Accent, style = MaterialTheme.typography.titleSmall)
    HorizontalDivider(Modifier.padding(top = 7.dp), color = Muted.copy(alpha = .22f))
}

@Composable
private fun Toggle(title: String, setting: KMutableProperty0<Boolean>) {
    var checked by remember { mutableStateOf(setting.get()) }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(checked, role = Role.Switch) {
        checked = it
        setting.set(it)
    }, verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun Choice(title: String, value: String, options: List<String>, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { open = true },
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            Text(value, color = Accent)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { select(option); open = false })
            }
        }
    }
}

@Composable
private fun Info(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f))
        Text(value, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}
