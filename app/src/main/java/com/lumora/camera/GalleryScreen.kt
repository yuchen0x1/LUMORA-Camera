package com.lumora.camera

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumora.camera.data.*
import com.lumora.camera.image.EnhancementFailure
import com.lumora.camera.image.PhotoEditParameters
import com.lumora.camera.image.PhotoEnhancementEngine
import com.lumora.camera.ui.LumoraThemeTokens
import com.lumora.camera.ui.ViewfinderTokens
import com.lumora.camera.ui.safeAreaPadding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal data class Recipe(
    val styleId: String,
    val strength: OptimizationStrength,
    val intensity: Float,
    val edits: PhotoEditParameters
)

private fun PhotoRecord.recipe() = Recipe(styleId, strength, intensity, edits)

internal class GallerySession : ViewModel() {
    var selected by mutableStateOf<PhotoRecord?>(null)
        private set
    var draft by mutableStateOf<Recipe?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var recoveryOpen by mutableStateOf(false)
        private set
    var recoveryPreview by mutableStateOf<PhotoIndexRecoveryPreview?>(null)
        private set
    var recoveryResult by mutableStateOf<PhotoIndexRecoveryResult?>(null)
        private set
    var recoveryError by mutableStateOf<String?>(null)
        private set
    private val undo = mutableStateListOf<Recipe>()
    private val redo = mutableStateListOf<Recipe>()
    private var sliderStart: Recipe? = null
    private var initialRecipe: Recipe? = null
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    val hasUnsavedChanges get() = draft != initialRecipe

    fun open(record: PhotoRecord?) {
        selected = record
        finishEditing()
    }

    fun closeIfRemoved(record: PhotoRecord) {
        if (selected?.enhancedUri == record.enhancedUri && draft == null) open(null)
    }

    fun edit(record: PhotoRecord) {
        finishEditing()
        val recipe = record.recipe()
        initialRecipe = recipe
        draft = recipe
    }

    fun finishEditing() {
        initialRecipe = null
        draft = null
        undo.clear()
        redo.clear()
        sliderStart = null
    }

    fun change(next: Recipe, sliding: Boolean = false) {
        val previous = draft ?: return
        if (previous == next) return
        if (sliding) {
            if (sliderStart == null) sliderStart = previous
        } else {
            finishSlide()
            undo.add(previous)
        }
        redo.clear()
        draft = next
    }

    fun finishSlide() {
        sliderStart?.let { if (it != draft) undo.add(it) }
        sliderStart = null
    }

    fun undo() {
        finishSlide()
        val previous = undo.removeLastOrNull() ?: return
        draft?.let(redo::add)
        draft = previous
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        draft?.let(undo::add)
        draft = next
    }

    fun importPhoto(context: Context, repository: PhotoRepository, uri: Uri, presets: List<FilmPreset>) {
        writeVersion {
            val resolver = context.contentResolver
            // A persisted grant is app-wide, not owned by this import. Never revoke it on
            // failure/removal: another version, sample, undo token or active render may use it.
            // Global cleanup requires reference leases shared with the camera/startup owners.
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                throw IOException("无法保留这张照片的读取权限，请重新选择照片。", e)
            }
            val preset = presets.find { it.id == repository.selectedStyleId } ?: presets.first()
            val source = PhotoRecord(
                uri.toString(), uri.toString(), preset.id, System.currentTimeMillis(),
                strength = repository.optimizationStrength, intensity = repository.styleIntensity,
                sourceOnly = true, imported = true
            )
            val existing = repository.getRecords().find { it.enhancedUri == source.enhancedUri }
            if (existing == null) repository.addRecord(source)
            val input = if (existing?.sourceOnly == true) source.copy(capturedAt = existing.capturedAt) else source
            val result = renderVersion(context, input, preset, input.recipe())
            if (existing == null || existing.sourceOnly) repository.replaceSourceRecord(result)
            else repository.addRecord(result)
            result
        }
    }

    fun save(context: Context, repository: PhotoRepository, record: PhotoRecord, preset: FilmPreset) {
        val recipe = draft ?: return
        writeVersion {
            val version = renderVersion(context, record, preset, recipe).copy(parentUri = record.enhancedUri)
            repository.addRecord(version)
            version
        }
    }

    fun previewRecovery(repository: PhotoRepository) {
        if (busy) return
        recoveryOpen = true
        recoveryPreview = null
        recoveryResult = null
        recoveryError = null
        busy = true
        viewModelScope.launch {
            try {
                recoveryPreview = withContext(Dispatchers.IO) { repository.previewIndexRecovery() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                recoveryError = "暂时无法检查恢复线索。原始索引和照片未改动，可稍后重试。"
            } finally {
                busy = false
            }
        }
    }

    fun restoreRecovery(repository: PhotoRepository) {
        val preview = recoveryPreview ?: return
        if (busy || recoveryResult != null || (preview.records.isEmpty() && !preview.writeUnconfirmed)) return
        recoveryError = null
        busy = true
        viewModelScope.launch {
            try {
                recoveryResult = withContext(Dispatchers.IO) { repository.restoreIndexRecovery(preview) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A disk-write exception is not proof that the in-memory map was unchanged.
                recoveryError = "恢复未能确认完成。请重新预览；原始备份和照片文件仍保留。"
            } finally {
                busy = false
            }
        }
    }

    fun closeRecovery() {
        if (busy) return
        recoveryOpen = false
        recoveryPreview = null
        recoveryResult = null
        recoveryError = null
    }

    private fun writeVersion(block: () -> PhotoRecord) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                open(withContext(Dispatchers.IO) { block() })
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                message = error.localizedMessage
            } catch (_: Exception) {
                message = "处理失败，请确认来源照片可读取后重试。"
            } finally {
                busy = false
            }
        }
    }

    private fun renderVersion(context: Context, record: PhotoRecord, preset: FilmPreset, recipe: Recipe): PhotoRecord {
        val timestamp = System.currentTimeMillis()
        val result = PhotoEnhancementEngine.process(
            context.contentResolver, Uri.parse(record.originalUri), preset, recipe.strength,
            "LUMORA_${timestamp}_${preset.id}.jpg", mode = if (record.imported) "IMPORT" else "EDIT",
            edits = recipe.edits, intensity = recipe.intensity, jpegQuality = CameraSettings(context).jpegQuality,
            memoryContext = context.applicationContext
        ).getOrElse { error ->
            val reason = (error as? EnhancementFailure)?.stage?.description ?: "无法生成全分辨率成片"
            throw IOException("${reason}。来源照片已保留，可在编辑中重试。", error)
        }
        return record.copy(enhancedUri = result.enhanced.toString(), styleId = preset.id,
            strength = recipe.strength, intensity = recipe.intensity, edits = recipe.edits,
            createdAt = timestamp, sourceOnly = false, favorite = false,
            diagnostics = result.diagnostics.toDiagnosticLine(), recipeKnown = true)
    }
}

@Composable
internal fun GalleryScreen(repository: PhotoRepository, onBack: () -> Unit) {
    val session: GallerySession = viewModel()
    GalleryScreen(repository, onBack, session)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GalleryScreen(
    repository: PhotoRepository, onBack: () -> Unit, session: GallerySession
) {
    val context = LocalContext.current
    val presets = remember(context) { FilmPresets.load(context) }
    val recordFlow = remember(repository) { repository.observeRecords() }
    val records by recordFlow.collectAsState(initial = emptyList())
    val recoveryFlow = remember(repository) { repository.observeRecoveryStatus() }
    val recoveryStatus by recoveryFlow.collectAsState(initial = PhotoIndexRecoveryStatus())
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    val selected = session.selected?.let { item ->
        records.find { it.enhancedUri == item.enhancedUri } ?: if (item.sourceOnly) {
            records.find { it.originalUri == item.originalUri && it.parentUri == null } ?: item
        } else item
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) session.importPhoto(context.applicationContext, repository, uri, presets)
    }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = session.draft == null && (selected != null || session.busy)) {
        if (!session.busy) session.open(null)
    }
    session.message?.let { error ->
        AlertDialog(onDismissRequest = { session.message = null }, title = { Text("处理照片") },
            text = { Text(error) }, confirmButton = { TextButton(onClick = { session.message = null }) { Text("好") } })
    }
    if (showPrivacy) GalleryPrivacyDialog(onClose = { showPrivacy = false })
    if (session.recoveryOpen) PhotoIndexRecoveryDialog(session, repository, presets)
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeAreaPadding()) {
        Column(Modifier.weight(1f).fillMaxWidth()) {
            if (selected != null) {
                val draft = session.draft
                if (draft != null) {
                    EditorPage(selected, presets, session, draft, repository)
                } else {
                    DetailPage(selected, presets, records, session, repository,
                        onFavorite = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { repository.setFavorite(selected.enhancedUri, !selected.favorite) }
                                } catch (error: IOException) {
                                    session.message = error.localizedMessage
                                }
                            }
                        },
                        onRemove = {
                            scope.launch {
                                try {
                                    val removal = withContext(Dispatchers.IO) { repository.removeRecord(selected) }
                                    session.closeIfRemoved(selected)
                                    if (removal == null) return@launch
                                    val reply = snack.showSnackbar("已从作品中移除", "撤销", duration = SnackbarDuration.Long)
                                    if (reply == SnackbarResult.ActionPerformed) withContext(Dispatchers.IO) { repository.undoRemoval(removal) }
                                } catch (error: IOException) {
                                    session.message = error.localizedMessage
                                }
                            }
                        }
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BackButton(enabled = !session.busy, onClick = onBack)
                    Text("作品", Modifier.weight(1f), color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                    IconButton(modifier = Modifier.size(48.dp), onClick = { showPrivacy = true }) {
                        Icon(Icons.Default.PrivacyTip, "照片与隐私")
                    }
                    IconButton(modifier = Modifier.size(48.dp), enabled = !session.busy, onClick = ::pick) { Icon(Icons.Default.AddAPhoto, "导入") }
                }
                if (session.busy) GalleryProgress(if (session.recoveryOpen) "正在检查或恢复索引…" else "正在导入并处理照片…")
                if (recoveryStatus.hasEvidence) {
                    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("index-recovery-notice")) {
                        Column(Modifier.padding(12.dp)) {
                            Text("发现作品索引恢复线索", color = Ink, fontWeight = FontWeight.Medium)
                            Text(if (recoveryStatus.writeUnconfirmed) "上次索引写入尚未确认成功，可预览并重试写入确认。"
                                else if (recoveryStatus.currentIndexDamaged) "当前索引有无法完整读取的内容，照片文件不会因此删除。"
                                else "保留了 ${recoveryStatus.backupCount} 份原始索引快照；备份不代表照片一定能恢复。",
                                color = Muted, fontSize = 13.sp)
                            TextButton(enabled = !session.busy, onClick = { session.previewRecovery(repository) },
                                modifier = Modifier.testTag("index-recovery-preview")) { Text("预览恢复") }
                        }
                    }
                }
                FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(!favoritesOnly, { favoritesOnly = false }, label = { Text("全部 ${records.size}") },
                        modifier = Modifier.heightIn(min = 48.dp))
                    FilterChip(favoritesOnly, { favoritesOnly = true }, label = { Text("收藏 ${records.count { it.favorite }}") },
                        modifier = Modifier.heightIn(min = 48.dp))
                }
                val visible = if (favoritesOnly) records.filter { it.favorite } else records
                if (visible.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(if (favoritesOnly) Icons.Default.FavoriteBorder else Icons.Default.PhotoLibrary,
                                null, tint = Muted, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(if (favoritesOnly) "还没有收藏的作品" else "还没有作品", color = Ink)
                            if (!favoritesOnly) TextButton(enabled = !session.busy, onClick = ::pick) { Text("导入照片") }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        GridCells.Adaptive(142.dp), Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(visible, key = { it.enhancedUri }) { record ->
                            val name = presetName(record, presets)
                            Column(Modifier.testTag("photo-${record.enhancedUri}")
                                .clickable(enabled = !session.busy, onClickLabel = "打开作品", role = Role.Button) {
                                    session.open(record)
                                }) {
                                Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                                    PhotoPreview(record.enhancedUri, crop = true)
                                    if (record.favorite) Icon(Icons.Default.Favorite, "已收藏",
                                        Modifier.align(Alignment.TopEnd).padding(6.dp).background(ViewfinderTokens.panel)
                                            .padding(4.dp).size(16.dp), tint = ViewfinderTokens.accent)
                                }
                                Text(name, Modifier.padding(top = 5.dp), color = Ink, fontSize = 14.sp)
                                Text(formatTime(record.createdAt), color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snack)
    }
}

@Composable
private fun GalleryPrivacyDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("照片与隐私") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("LUMORA 在应用内处理照片。导入仅访问你选择的照片，并保留读取权限供再次编辑；不会向 LUMORA 服务器上传照片。云相册提供方可能下载所选照片。")
                Text("作品索引及恢复快照包含照片 URI、时间、风格和编辑参数，可能随系统应用数据备份保存。恢复只检查已有读取权限，不申请整个相册权限。")
                Text("从作品中移除只移除索引，不删除照片。读取权限可能仍被其他版本、风格样片、撤销操作或正在进行的编辑使用，目前不会在移除或导入失败时自动撤销共享权限。")
                Text("如需撤销读取权限，可在系统设置中管理应用权限；不同系统的照片访问管理方式不同。撤销后，相关作品或原图可能无法预览和编辑。恢复快照不会绕过权限，也不保证修复损坏或已删除的照片。")
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("知道了") } }
    )
}

@Composable
private fun PhotoIndexRecoveryDialog(session: GallerySession, repository: PhotoRepository, presets: List<FilmPreset>) {
    val preview = session.recoveryPreview
    val result = session.recoveryResult
    AlertDialog(
        onDismissRequest = session::closeRecovery,
        title = { Text(if (result == null) "预览索引恢复" else "索引恢复结果") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp).testTag("index-recovery-review"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("只补充通过校验且仍可读取的记录，不覆盖现有作品，不删除照片或任何原始快照。备份存在不等于已经修复。")
                }
                if (session.busy) item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (preview == null) "正在检查 JSON 与照片读取状态…" else "正在重新校验并写入…",
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                session.recoveryError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                if (result != null) {
                    item {
                        val text = when (result.outcome) {
                            PhotoIndexRecoveryOutcome.RESTORED -> "已确认补充 ${result.restoredCount} 项记录。未通过校验的内容仍留在原始快照中；这不是完整修复。"
                            PhotoIndexRecoveryOutcome.NOTHING_NEW -> "没有新增记录。已存在 ${result.alreadyPresentCount} 项，重新检查时不可读取 ${result.unreadableMediaCount} 项。原始快照仍保留。"
                            PhotoIndexRecoveryOutcome.STALE_PREVIEW -> "索引或备份已经变化，未按旧预览写入。请重新预览后再确认。"
                            PhotoIndexRecoveryOutcome.WRITE_NOT_CONFIRMED -> "尚未确认写入成功，不能报告为已恢复。内存中可能已出现新记录，请检查可用空间并重新预览。照片和原始快照未删除。"
                            PhotoIndexRecoveryOutcome.WRITE_CONFIRMED -> "已确认现有索引写入磁盘。本次未新增记录，不代表所有损坏内容已经修复。原始快照仍保留。"
                        }
                        Text(text, Modifier.testTag("index-recovery-result").semantics { liveRegion = LiveRegionMode.Polite })
                        if (result.outcome == PhotoIndexRecoveryOutcome.RESTORED) {
                            Text("确认前已存在 ${result.alreadyPresentCount} 项；读取状态变化而跳过 ${result.unreadableMediaCount} 项。", color = Muted)
                        }
                    }
                } else if (preview != null) {
                    item {
                        Text("可补充 ${preview.records.size} 项记录", Modifier.testTag("index-recovery-count"), fontWeight = FontWeight.Medium)
                        Text("检查 ${preview.backupCount} 份备份${if (preview.currentIndexDamaged) "及当前受损索引" else ""}。", color = Muted)
                        Text("已存在 ${preview.alreadyPresentCount} 项 · 重复 ${preview.duplicateEntryCount} 项 · 相互冲突 ${preview.conflictingRecordCount} 项", color = Muted)
                        Text("JSON 无法解析 ${preview.malformedSnapshotCount} 份 · 条目无效 ${preview.invalidEntryCount} 项 · 媒体不可读取 ${preview.unreadableMediaCount} 项", color = Muted)
                        if (preview.records.isEmpty()) Text("本次没有可安全补充的记录。不会猜测残缺 JSON、覆盖旧版本或把不可读取的照片标为恢复成功。")
                        if (preview.writeUnconfirmed) Text("上次写入尚未确认。本次可重试把当前内存中的索引写入磁盘，不会把它算成新增恢复。")
                    }
                    items(preview.records, key = { it.enhancedUri }) { record ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(56.dp)) { PhotoPreview(record.enhancedUri, crop = true) }
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(presetName(record, presets), fontWeight = FontWeight.Medium)
                                Text(formatTime(record.createdAt), color = Muted, fontSize = 12.sp)
                                Text(if (record.parentUri == null) "来源与成片可读取" else "另存版本 · 来源与成片可读取", color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
                item {
                    Text("此检查在应用内读取 URI 与小尺寸图像，不上传照片；云提供方可能下载媒体。恢复只补充索引，不重新生成照片，也不保证之后的全分辨率编辑一定成功。", color = Muted, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            if (result == null && session.recoveryError == null) {
                TextButton(enabled = !session.busy && preview != null && (preview.records.isNotEmpty() || preview.writeUnconfirmed),
                    modifier = Modifier.testTag("index-recovery-confirm"), onClick = { session.restoreRecovery(repository) }) {
                    Text(if (preview == null) "正在检查" else if (preview.records.isEmpty() && preview.writeUnconfirmed) "重试写入确认"
                        else "补充 ${preview.records.size} 项记录")
                }
            } else {
                TextButton(enabled = !session.busy, onClick = session::closeRecovery) { Text("关闭") }
            }
        },
        dismissButton = {
            if (result != null || session.recoveryError != null) {
                TextButton(enabled = !session.busy, onClick = { session.previewRecovery(repository) }) { Text("重新预览") }
            } else {
                TextButton(enabled = !session.busy, onClick = session::closeRecovery) { Text("暂不恢复") }
            }
        }
    )
}

@Composable
private fun GalleryProgress(label: String) {
    LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(label, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }, color = Accent)
}

@Composable
private fun BackButton(enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(modifier = Modifier.size(48.dp), enabled = enabled, onClick = onClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
}

private fun presetName(record: PhotoRecord, presets: List<FilmPreset>) =
    if (record.sourceOnly) "原图" else presets.find { it.id == record.styleId }?.name ?: record.styleId

private fun formatTime(time: Long) = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()).format(Date(time))

private val previewSlots = Semaphore(3)

@Composable
private fun PhotoPreview(
    uri: String, crop: Boolean = false, preset: FilmPreset? = null, recipe: Recipe? = null,
    description: String? = null
) {
    val resolver = LocalContext.current.contentResolver
    BoxWithConstraints(Modifier.fillMaxSize().background(LumoraThemeTokens.photoMatte), contentAlignment = Alignment.Center) {
        val edge = maxOf(constraints.maxWidth, constraints.maxHeight).coerceIn(1, 1440)
        // Keep the last frame during adjustments, but never show it on the other side of the comparison.
        key(uri, edge, recipe != null) {
            var image by remember { mutableStateOf<Bitmap?>(null) }
            var loading by remember { mutableStateOf(true) }
            LaunchedEffect(preset, recipe) {
                loading = true
                if (recipe != null) delay(160)
                image = withContext(Dispatchers.IO) {
                    previewSlots.withPermit {
                        PhotoEnhancementEngine.renderPreview(resolver, Uri.parse(uri), edge, preset,
                            recipe?.strength ?: OptimizationStrength.STANDARD, recipe?.edits ?: PhotoEditParameters.NATURAL,
                            recipe?.intensity ?: 1f)
                    }
                }
                loading = false
            }
            image?.let { Image(it.asImageBitmap(), description, Modifier.fillMaxSize(),
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit) }
            val loadingSemantics = if (description == null) Modifier else Modifier.semantics {
                contentDescription = "正在加载$description"
                liveRegion = LiveRegionMode.Polite
            }
            if (loading && image != null) {
                LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().then(loadingSemantics))
            }
            if (image == null) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(22.dp).then(loadingSemantics), strokeWidth = 2.dp)
                } else {
                    Text("预览不可用", Modifier.background(ViewfinderTokens.panel).padding(horizontal = 8.dp, vertical = 4.dp)
                        .semantics { if (description != null) liveRegion = LiveRegionMode.Polite },
                        color = ViewfinderTokens.ink, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun PhotoLayout(preview: @Composable () -> Unit, tools: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        if (maxWidth >= 520.dp && maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1.25f).fillMaxHeight()) { preview() }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).padding(12.dp), content = tools)
            }
        } else {
            val controlsHeight = maxHeight * 0.53f
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { preview() }
                Column(Modifier.fillMaxWidth().heightIn(max = controlsHeight).verticalScroll(scroll).padding(14.dp), content = tools)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailPage(
    record: PhotoRecord, presets: List<FilmPreset>, records: List<PhotoRecord>, session: GallerySession, repository: PhotoRepository,
    onFavorite: () -> Unit, onRemove: () -> Unit
) {
    val context = LocalContext.current
    var before by rememberSaveable(record.enhancedUri) { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val sourceLabel = if (record.sourceIsRendered) "来源图" else "原图"
    val previewLabel = if (record.sourceOnly || before) sourceLabel else "成片"
    val uri = if (before) record.originalUri else record.enhancedUri
    if (confirmRemove) {
        AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("从作品中移除？") },
            text = { Text("照片文件会保留在原来的位置。") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove() }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("取消") } })
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BackButton { session.open(null) }
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(previewLabel, Modifier.align(Alignment.CenterVertically).heightIn(min = 48.dp)
                .wrapContentHeight(Alignment.CenterVertically), color = Ink, fontSize = 20.sp)
            Row(Modifier.align(Alignment.CenterVertically)) {
                IconButton(modifier = Modifier.size(48.dp), onClick = onFavorite) {
                    Icon(if (record.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        if (record.favorite) "取消收藏作品" else "收藏作品", tint = if (record.favorite) Accent else Ink)
                }
                IconButton(modifier = Modifier.size(48.dp), onClick = {
                    try {
                        val photo = Uri.parse(uri)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = context.contentResolver.getType(photo) ?: "image/*"
                            putExtra(Intent.EXTRA_STREAM, photo)
                            clipData = ClipData.newRawUri("照片", photo)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "分享照片"))
                    } catch (_: Exception) { session.message = "无法分享这张照片，请检查读取权限。" }
                }) { Icon(Icons.Default.Share, "分享") }
                IconButton(modifier = Modifier.size(48.dp), onClick = { confirmRemove = true }) {
                    Icon(Icons.Default.Delete, "删除")
                }
            }
        }
    }
    PhotoLayout(preview = { PhotoPreview(uri, description = previewLabel) }) {
        if (record.originalUri != record.enhancedUri) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(before, { before = true }, label = { Text(sourceLabel) }, modifier = Modifier.heightIn(min = 48.dp).testTag("detail-before"))
                FilterChip(!before, { before = false }, label = { Text("成片") }, modifier = Modifier.heightIn(min = 48.dp).testTag("detail-after"))
            }
        }
        Text(presetName(record, presets), color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        val dateLabel = if (record.imported) "导入" else "拍摄"
        Text("$dateLabel  ${formatTime(record.capturedAt)}", color = Muted, fontSize = 13.sp)
        if (record.parentUri != null) Text("另存  ${formatTime(record.createdAt)}", color = Muted, fontSize = 13.sp)
        if (!record.sourceOnly && record.recipeKnown) Text("${record.strength.label}优化  ·  风格 ${(record.intensity * 100).roundToInt()}%",
            color = Muted, fontSize = 13.sp)
        if (record.sourceIsRendered) Text("旧版本仅保留了成片，继续编辑将以它为来源。", color = Muted, fontSize = 12.sp)
        val parent = records.find { it.enhancedUri == record.parentUri }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { session.edit(record) }, modifier = Modifier.align(Alignment.CenterVertically)) {
                Icon(Icons.Default.Tune, null); Spacer(Modifier.width(6.dp)); Text("编辑")
            }
            if (parent != null) TextButton(onClick = { session.open(parent) },
                modifier = Modifier.align(Alignment.CenterVertically)) { Text("查看上一版本") }
        }
        var customSample by remember(record.originalUri) { mutableStateOf(repository.styleSampleUri == record.originalUri) }
        TextButton(onClick = {
            customSample = !customSample
            repository.styleSampleUri = if (customSample) record.originalUri else null
        }) { Text(if (customSample) "取消用作风格样片" else "用作相机风格样片") }
        record.diagnostics?.let { details ->
            var expanded by rememberSaveable(record.enhancedUri) { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起拍摄信息" else "拍摄信息") }
            if (expanded) Text(details, color = Muted, fontSize = 12.sp)
        }
    }
}

private enum class Adjustment(val label: String, val range: ClosedFloatingPointRange<Float>,
    val read: (PhotoEditParameters) -> Float, val set: (PhotoEditParameters, Float) -> PhotoEditParameters) {
    Exposure("曝光", -2f..2f, { it.exposure }, { p, v -> p.copy(exposure = v) }),
    Contrast("对比度", -1f..1f, { it.contrast }, { p, v -> p.copy(contrast = v) }),
    Highlights("高光", -1f..1f, { it.highlights }, { p, v -> p.copy(highlights = v) }),
    Shadows("阴影", -1f..1f, { it.shadows }, { p, v -> p.copy(shadows = v) }),
    Temperature("色温", -1f..1f, { it.temperature }, { p, v -> p.copy(temperature = v) }),
    Tint("色调", -1f..1f, { it.tint }, { p, v -> p.copy(tint = v) }),
    Saturation("饱和度", -1f..1f, { it.saturation }, { p, v -> p.copy(saturation = v) }),
    Detail("清晰度", 0f..1f, { it.detail }, { p, v -> p.copy(detail = v) }),
    Sharpening("锐化", 0f..1f, { it.sharpening }, { p, v -> p.copy(sharpening = v) }),
    Denoise("降噪", 0f..1f, { it.denoise }, { p, v -> p.copy(denoise = v) }),
    Grain("颗粒", 0f..1f, { it.grain }, { p, v -> p.copy(grain = v) }),
    Vignette("暗角", 0f..1f, { it.vignette }, { p, v -> p.copy(vignette = v) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorPage(
    record: PhotoRecord, presets: List<FilmPreset>, session: GallerySession, recipe: Recipe,
    repository: PhotoRepository
) {
    val context = LocalContext.current.applicationContext
    val selected = presets.find { it.id == recipe.styleId } ?: presets.find { it.id == "natural" } ?: presets.first()
    var before by rememberSaveable(record.enhancedUri) { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable(record.enhancedUri) { mutableStateOf(false) }
    val interactive = !session.busy
    fun requestClose() {
        if (session.busy) return
        if (session.hasUnsavedChanges) confirmDiscard = true else session.finishEditing()
    }
    BackHandler { requestClose() }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("尚未保存为新版本的修改将丢失，原作品不受影响。") },
            confirmButton = { TextButton(enabled = interactive, modifier = Modifier.testTag("editor-discard"),
                onClick = { confirmDiscard = false; session.finishEditing() }) { Text("放弃修改") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }, modifier = Modifier.testTag("editor-keep-editing")) { Text("继续编辑") } }
        )
    }
    Row(Modifier.fillMaxWidth().padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(modifier = Modifier.size(48.dp), enabled = interactive, onClick = ::requestClose) { Icon(Icons.Default.Close, "取消") }
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.align(Alignment.CenterVertically)) {
                IconButton(modifier = Modifier.size(48.dp), enabled = interactive && session.canUndo, onClick = { session.undo(); before = false }) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "撤销调整")
                }
                IconButton(modifier = Modifier.size(48.dp), enabled = interactive && session.canRedo, onClick = { session.redo(); before = false }) {
                    Icon(Icons.AutoMirrored.Filled.Redo, "重做调整")
                }
            }
            Button(enabled = interactive, onClick = { session.save(context, repository, record, selected) },
                modifier = Modifier.align(Alignment.CenterVertically)) {
                Text("保存新版本")
            }
        }
    }
    if (session.busy) GalleryProgress("正在保存…")
    val sourceLabel = if (record.sourceIsRendered) "来源图" else "原图"
    PhotoLayout(preview = {
        PhotoPreview(record.originalUri, preset = selected.takeUnless { before }, recipe = recipe.takeUnless { before },
            description = if (before) sourceLabel else "调整后")
    }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(before, { before = true }, enabled = interactive, label = { Text(sourceLabel) },
                modifier = Modifier.heightIn(min = 48.dp))
            FilterChip(!before, { before = false }, enabled = interactive, label = { Text("调整后") },
                modifier = Modifier.heightIn(min = 48.dp))
        }
        if (record.sourceIsRendered) {
            Text("此来源已包含旧版处理。继续编辑是在它上面叠加，无法用风格强度 0% 撤销之前的处理。", color = Muted, fontSize = 12.sp)
        } else if (record.sourceOnly) {
            Text("正在预览本次自动优化与风格；保存新版本才会生成成片，来源照片保持不变。", color = Muted, fontSize = 12.sp)
        }
        var styleMenu by remember { mutableStateOf(false) }
        var favorites by remember { mutableStateOf(repository.favoriteStyleIds) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("风格", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(end = 4.dp))
            Box(Modifier.weight(1f)) {
                TextButton(enabled = interactive, onClick = { styleMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(selected.name, color = if (interactive) Ink else LocalContentColor.current,
                            modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                }
                DropdownMenu(styleMenu, { styleMenu = false }) {
                    presets.sortedByDescending { it.id in favorites }.forEach { preset ->
                        val isSelected = preset.id == selected.id
                        DropdownMenuItem(
                            text = { Text(preset.name) },
                            leadingIcon = if (preset.id in favorites) { { Icon(Icons.Default.Star, null, tint = Accent) } } else null,
                            trailingIcon = if (isSelected) { { Icon(Icons.Default.Check, null) } } else null,
                            modifier = Modifier.semantics { this.selected = isSelected },
                            onClick = { styleMenu = false; session.change(recipe.copy(styleId = preset.id)); before = false }
                        )
                    }
                }
            }
            IconButton(modifier = Modifier.size(48.dp), enabled = interactive, onClick = {
                repository.setStyleFavorite(selected.id, selected.id !in favorites)
                favorites = repository.favoriteStyleIds
            }) {
                Icon(if (selected.id in favorites) Icons.Default.Star else Icons.Default.StarBorder,
                    if (selected.id in favorites) "取消收藏风格" else "收藏风格",
                    tint = if (interactive) Accent else LocalContentColor.current)
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("风格强度", color = Muted, fontSize = 14.sp)
            Text("${(recipe.intensity * 100).roundToInt()}%", color = Ink)
        }
        Slider(value = recipe.intensity, enabled = interactive, onValueChange = {
            before = false
            session.change(recipe.copy(intensity = it), sliding = true)
        }, onValueChangeFinished = session::finishSlide,
            modifier = Modifier.heightIn(min = 48.dp).testTag("editor-intensity").semantics { contentDescription = "风格强度" })
        Text("0% 仅关闭本次风格；自动优化和手动调整仍会应用。", color = Muted, fontSize = 12.sp)
        Text("自动优化", color = Muted, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OptimizationStrength.entries.forEach { level ->
                FilterChip(recipe.strength == level, {
                    before = false
                    session.change(recipe.copy(strength = level))
                }, enabled = interactive, label = { Text(level.label) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("editor-strength-${level.name}"))
            }
        }
        var adjustment by rememberSaveable { mutableStateOf(Adjustment.Exposure) }
        var adjustmentMenu by remember { mutableStateOf(false) }
        val value = adjustment.read(recipe.edits)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1f).align(Alignment.CenterVertically)) {
                TextButton(enabled = interactive, onClick = { adjustmentMenu = true }) {
                    Text(adjustment.label)
                    Icon(Icons.Default.ArrowDropDown, "选择调整项")
                }
                DropdownMenu(adjustmentMenu, { adjustmentMenu = false }) {
                    Adjustment.entries.forEach { item ->
                        val isSelected = item == adjustment
                        DropdownMenuItem(
                            text = { Text(item.label) },
                            trailingIcon = if (isSelected) { { Icon(Icons.Default.Check, null) } } else null,
                            modifier = Modifier.semantics { this.selected = isSelected },
                            onClick = { adjustment = item; adjustmentMenu = false }
                        )
                    }
                }
            }
            Text(if (adjustment == Adjustment.Exposure) String.format(Locale.getDefault(), "%+.2f EV", value)
                else "${(value * 100).roundToInt()}", Modifier.align(Alignment.CenterVertically), color = Ink)
        }
        Slider(value = value, enabled = interactive, valueRange = adjustment.range,
            onValueChange = {
                before = false
                session.change(recipe.copy(edits = adjustment.set(recipe.edits, it)), sliding = true)
            }, onValueChangeFinished = session::finishSlide,
            modifier = Modifier.semantics { contentDescription = adjustment.label })
    }
}
