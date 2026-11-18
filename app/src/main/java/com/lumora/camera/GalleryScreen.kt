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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumora.camera.data.*
import com.lumora.camera.image.EnhancementFailure
import com.lumora.camera.image.PhotoEditParameters
import com.lumora.camera.image.PhotoEnhancementEngine
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
    private val undo = mutableStateListOf<Recipe>()
    private val redo = mutableStateListOf<Recipe>()
    private var sliderStart: Recipe? = null
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun open(record: PhotoRecord?) {
        selected = record
        finishEditing()
    }

    fun edit(record: PhotoRecord) {
        finishEditing()
        draft = record.recipe()
    }

    fun finishEditing() {
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
    val context = LocalContext.current
    val session: GallerySession = viewModel()
    val presets = remember(context) { FilmPresets.load(context) }
    val recordFlow = remember(repository) { repository.observeRecords() }
    val records by recordFlow.collectAsState(initial = emptyList())
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
    BackHandler(enabled = selected != null || session.busy) {
        if (!session.busy) {
            if (session.draft != null) session.finishEditing() else session.open(null)
        }
    }
    session.message?.let { error ->
        AlertDialog(onDismissRequest = { session.message = null }, title = { Text("处理照片") },
            text = { Text(error) }, confirmButton = { TextButton(onClick = { session.message = null }) { Text("好") } })
    }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Color(0xFF080808)).safeDrawingPadding()) {
        if (selected != null) {
            val draft = session.draft
            if (draft != null) {
                EditorPage(selected, presets, session, draft, repository)
            } else {
                DetailPage(selected, presets, records, session, repository,
                    onFavorite = {
                        scope.launch(Dispatchers.IO) { repository.setFavorite(selected.enhancedUri, !selected.favorite) }
                    },
                    onDelete = {
                        scope.launch {
                            val removal = withContext(Dispatchers.IO) { repository.removeRecord(selected) }
                            session.open(null)
                            if (removal == null) return@launch
                            val reply = snack.showSnackbar("作品已从相册移除", "撤销", duration = SnackbarDuration.Long)
                            if (reply == SnackbarResult.ActionPerformed) withContext(Dispatchers.IO) { repository.undoRemoval(removal) }
                        }
                    }
                )
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton(enabled = !session.busy, onClick = onBack)
                Text("作品", Modifier.weight(1f), color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                IconButton(enabled = !session.busy, onClick = ::pick) { Icon(Icons.Default.AddAPhoto, "导入") }
            }
            if (session.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在导入并处理照片…", Modifier.padding(12.dp), color = Accent)
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!favoritesOnly, { favoritesOnly = false }, label = { Text("全部 ${records.size}") })
                FilterChip(favoritesOnly, { favoritesOnly = true }, label = { Text("收藏 ${records.count { it.favorite }}") })
            }
            val visible = if (favoritesOnly) records.filter { it.favorite } else records
            Box(Modifier.fillMaxSize()) {
                if (visible.isEmpty()) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (favoritesOnly) Icons.Default.FavoriteBorder else Icons.Default.PhotoLibrary,
                            null, tint = Muted, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(if (favoritesOnly) "还没有收藏的作品" else "还没有作品", color = Ink)
                        if (!favoritesOnly) TextButton(enabled = !session.busy, onClick = ::pick) { Text("导入照片") }
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
                                .clickable(enabled = !session.busy) { session.open(record) }) {
                                Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                                    PhotoPreview(record.enhancedUri, crop = true)
                                    if (record.favorite) Icon(Icons.Default.Favorite, "已收藏",
                                        Modifier.align(Alignment.TopEnd).padding(6.dp).background(Panel).padding(4.dp).size(16.dp), tint = Accent)
                                }
                                Text(name, Modifier.padding(top = 5.dp), color = Ink, fontSize = 14.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(formatTime(record.createdAt), color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
                SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun BackButton(enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(enabled = enabled, onClick = onClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
}

private fun presetName(record: PhotoRecord, presets: List<FilmPreset>) =
    if (record.sourceOnly) "原图" else presets.find { it.id == record.styleId }?.name ?: record.styleId

private fun formatTime(time: Long) = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()).format(Date(time))

private val previewSlots = Semaphore(3)

@Composable
private fun PhotoPreview(
    uri: String, crop: Boolean = false, preset: FilmPreset? = null, recipe: Recipe? = null
) {
    val resolver = LocalContext.current.contentResolver
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF141414)), contentAlignment = Alignment.Center) {
        val edge = maxOf(constraints.maxWidth, constraints.maxHeight).coerceIn(1, 1440)
        key(uri, edge) {
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
            image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit) }
            if (loading && image != null) {
                LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth())
            }
            if (image == null) {
                if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text("预览不可用", color = Muted, fontSize = 13.sp)
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

@Composable
private fun DetailPage(
    record: PhotoRecord, presets: List<FilmPreset>, records: List<PhotoRecord>, session: GallerySession, repository: PhotoRepository,
    onFavorite: () -> Unit, onDelete: () -> Unit
) {
    val context = LocalContext.current
    var before by rememberSaveable(record.enhancedUri) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val sourceLabel = if (record.sourceIsRendered) "来源图" else "原图"
    val uri = if (before) record.originalUri else record.enhancedUri
    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("从作品中移除？") },
            text = { Text("照片文件会保留在原来的位置。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BackButton { session.open(null) }
        Text(if (record.sourceOnly || before) sourceLabel else "成片", Modifier.weight(1f), color = Ink, fontSize = 20.sp)
        IconButton(onClick = onFavorite) {
            Icon(if (record.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                if (record.favorite) "取消收藏作品" else "收藏作品", tint = if (record.favorite) Accent else Ink)
        }
        IconButton(onClick = {
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
        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "删除") }
    }
    PhotoLayout(preview = { PhotoPreview(uri) }) {
        if (record.originalUri != record.enhancedUri) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(before, { before = true }, label = { Text(sourceLabel) }, modifier = Modifier.testTag("detail-before"))
                FilterChip(!before, { before = false }, label = { Text("成片") }, modifier = Modifier.testTag("detail-after"))
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { session.edit(record) }) { Icon(Icons.Default.Tune, null); Spacer(Modifier.width(6.dp)); Text("编辑") }
            if (parent != null) TextButton(onClick = { session.open(parent) }) { Text("查看上一版本") }
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

@Composable
private fun EditorPage(
    record: PhotoRecord, presets: List<FilmPreset>, session: GallerySession, recipe: Recipe,
    repository: PhotoRepository
) {
    val context = LocalContext.current.applicationContext
    val selected = presets.find { it.id == recipe.styleId } ?: presets.find { it.id == "natural" } ?: presets.first()
    var before by rememberSaveable(record.enhancedUri) { mutableStateOf(false) }
    val interactive = !session.busy
    Row(Modifier.fillMaxWidth().padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(enabled = interactive, onClick = session::finishEditing) { Icon(Icons.Default.Close, "取消") }
        Spacer(Modifier.weight(1f))
        IconButton(enabled = interactive && session.canUndo, onClick = session::undo) {
            Icon(Icons.AutoMirrored.Filled.Undo, "撤销调整")
        }
        IconButton(enabled = interactive && session.canRedo, onClick = session::redo) {
            Icon(Icons.AutoMirrored.Filled.Redo, "重做调整")
        }
        Button(enabled = interactive, onClick = { session.save(context, repository, record, selected) }) {
            Text(if (session.busy) "正在保存…" else "保存新版本")
        }
    }
    if (session.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    PhotoLayout(preview = {
        PhotoPreview(record.originalUri, preset = selected.takeUnless { before }, recipe = recipe.takeUnless { before })
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(before, { before = true }, label = { Text(if (record.sourceIsRendered) "来源图" else "原图") })
            FilterChip(!before, { before = false }, label = { Text("调整后") })
        }
        var styleMenu by remember { mutableStateOf(false) }
        var favorites by remember { mutableStateOf(repository.favoriteStyleIds) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("风格", Modifier.weight(1f), color = Muted, fontSize = 14.sp)
            Box {
                TextButton(enabled = interactive, onClick = { styleMenu = true }) {
                    Text(selected.name, color = Ink)
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(styleMenu, { styleMenu = false }) {
                    presets.sortedByDescending { it.id in favorites }.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.name) },
                            leadingIcon = if (preset.id in favorites) { { Icon(Icons.Default.Star, null, tint = Accent) } } else null,
                            onClick = { styleMenu = false; session.change(recipe.copy(styleId = preset.id)); before = false }
                        )
                    }
                }
            }
            IconButton(enabled = interactive, onClick = {
                repository.setStyleFavorite(selected.id, selected.id !in favorites)
                favorites = repository.favoriteStyleIds
            }) {
                Icon(if (selected.id in favorites) Icons.Default.Star else Icons.Default.StarBorder,
                    if (selected.id in favorites) "取消收藏风格" else "收藏风格", tint = Accent)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("风格强度", Modifier.weight(1f), color = Muted, fontSize = 14.sp)
            Text("${(recipe.intensity * 100).roundToInt()}%", color = Ink)
        }
        Slider(value = recipe.intensity, enabled = interactive, onValueChange = {
            before = false
            session.change(recipe.copy(intensity = it), sliding = true)
        }, onValueChangeFinished = session::finishSlide,
            modifier = Modifier.testTag("editor-intensity").semantics { contentDescription = "风格强度" })
        Text("自动优化", color = Muted, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OptimizationStrength.entries.forEach { level ->
                FilterChip(recipe.strength == level, {
                    before = false
                    session.change(recipe.copy(strength = level))
                }, enabled = interactive, label = { Text(level.label) },
                    modifier = Modifier.testTag("editor-strength-${level.name}"))
            }
        }
        var adjustment by rememberSaveable { mutableStateOf(Adjustment.Exposure) }
        var adjustmentMenu by remember { mutableStateOf(false) }
        val value = adjustment.read(recipe.edits)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(enabled = interactive, onClick = { adjustmentMenu = true }) {
                    Text(adjustment.label); Icon(Icons.Default.ArrowDropDown, "选择调整项")
                }
                DropdownMenu(adjustmentMenu, { adjustmentMenu = false }) {
                    Adjustment.entries.forEach { item ->
                        DropdownMenuItem(text = { Text(item.label) }, onClick = { adjustment = item; adjustmentMenu = false })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(if (adjustment == Adjustment.Exposure) String.format(Locale.getDefault(), "%+.2f EV", value)
                else "${(value * 100).roundToInt()}", color = Ink)
        }
        Slider(value = value, enabled = interactive, valueRange = adjustment.range,
            onValueChange = {
                before = false
                session.change(recipe.copy(edits = adjustment.set(recipe.edits, it)), sliding = true)
            }, onValueChangeFinished = session::finishSlide,
            modifier = Modifier.semantics { contentDescription = adjustment.label })
    }
}
