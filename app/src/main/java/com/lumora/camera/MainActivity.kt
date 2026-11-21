package com.lumora.camera

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumora.camera.camera.DeviceCapabilities
import com.lumora.camera.data.CaptureJournal
import com.lumora.camera.data.CaptureReconciliation
import com.lumora.camera.data.FilmPreset
import com.lumora.camera.data.FilmPresets
import com.lumora.camera.data.PhotoRepository
import com.lumora.camera.ui.AppThemeMode
import com.lumora.camera.ui.DarkColors
import com.lumora.camera.ui.LightColors
import com.lumora.camera.ui.LumoraTheme
import com.lumora.camera.ui.safeAreaPadding
import com.lumora.camera.update.UpdateDialog
import com.lumora.camera.update.UpdateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val Panel: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .94f)
internal val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val Background: Color @Composable get() = MaterialTheme.colorScheme.background

class CameraDeviceState : ViewModel() {
    var lastLens by mutableStateOf<DeviceCapabilities?>(null)
}

class MainActivity : ComponentActivity() {
    internal var volumeShutter: (() -> Unit)? = null
    private val shutterKeys = mutableSetOf<Int>()

    private var darkSystemBars = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateSystemBars(dark = true)
        setContent { LumoraApp() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Keep the transparent bars' backing and foreground in sync after focus/resize changes.
        if (hasFocus) updateSystemBars(darkSystemBars)
    }

    internal fun updateSystemBars(dark: Boolean) {
        darkSystemBars = dark
        applySystemBarAppearance(dark)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount > 0 && keyCode in shutterKeys) return true
            val capture = volumeShutter
            if (event.repeatCount == 0) {
                shutterKeys.remove(keyCode)
                if (hasWindowFocus() && capture != null) {
                    shutterKeys.add(keyCode)
                    capture()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (shutterKeys.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun cameraAllowed() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    @Composable
    private fun LumoraApp() {
        val settings = remember { CameraSettings(applicationContext) }
        var themeMode by remember { mutableStateOf(settings.themeMode) }
        val repository = remember { PhotoRepository(applicationContext) }
        val presets = remember { FilmPresets.load(applicationContext) }
        val device: CameraDeviceState = viewModel()
        val updates: UpdateViewModel = viewModel()
        var granted by remember { mutableStateOf(cameraAllowed()) }
        var asked by rememberSaveable { mutableStateOf(false) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
            asked = true
        }
        DisposableEffect(lifecycle, updates) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    granted = cameraAllowed()
                    updates.check(manual = false)
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        // Reconcile captures interrupted by process death without blocking app navigation.
        LaunchedEffect(repository) {
            withContext(Dispatchers.IO) {
                val outcome = runCatching {
                    CaptureReconciliation.run(
                        applicationContext, CaptureJournal(applicationContext), repository,
                        isActive = CameraOutput::isCaptureActive
                    )
                }.getOrElse { "unavailable" }
                Log.i("LumoraCapture", "startupReconciliation $outcome")
            }
        }
        val openSettings = !granted && asked && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        LumoraNavigation(
            repository, presets, device, updates,
            cameraGranted = granted,
            openSystemSettings = openSettings,
            themeMode = themeMode,
            onThemeChange = {
                settings.themeMode = it
                themeMode = it
            }
        ) {
            if (openSettings) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } else {
                permission.launch(Manifest.permission.CAMERA)
            }
        }
    }
}

internal enum class AppScreen { CAMERA, GALLERY, SETTINGS }

private fun ComponentActivity.applySystemBarAppearance(dark: Boolean) {
    // A resized window can expose this backing beneath transparent system bars. XML day/night
    // resources follow the device theme, not Lumora's explicit Compose theme selection.
    val colors = if (dark) DarkColors else LightColors
    window.setBackgroundDrawable(ColorDrawable(colors.background.toArgb()))
    val transparent = android.graphics.Color.TRANSPARENT
    val bars = if (dark) SystemBarStyle.dark(transparent)
    else SystemBarStyle.light(transparent, transparent)
    enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
}

/** 沿 ContextWrapper 链找到宿主 ComponentActivity，用于按主题刷新系统栏。 */
private fun Context.findComponentActivity(): ComponentActivity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is ComponentActivity) return context
        context = context.baseContext
    }
    return null
}

@Composable
internal fun LumoraNavigation(
    repository: PhotoRepository, presets: List<FilmPreset>, device: CameraDeviceState, updates: UpdateViewModel,
    cameraGranted: Boolean, openSystemSettings: Boolean,
    themeMode: AppThemeMode = AppThemeMode.SYSTEM, onThemeChange: (AppThemeMode) -> Unit = {},
    onEnableCamera: () -> Unit
) {
    val savedScreens = rememberSaveableStateHolder()
    var screen by rememberSaveable { mutableStateOf(AppScreen.CAMERA) }
    val backToCamera = { screen = AppScreen.CAMERA }
    val activity = LocalContext.current.findComponentActivity()
    val darkBars = themeMode.isDark() || (cameraGranted && screen == AppScreen.CAMERA)
    SideEffect {
        when (activity) {
            is MainActivity -> activity.updateSystemBars(darkBars)
            null -> Unit
            else -> activity.applySystemBarAppearance(darkBars)
        }
    }
    BackHandler(enabled = screen != AppScreen.CAMERA, onBack = backToCamera)
    LumoraTheme(themeMode) {
        Surface(Modifier.fillMaxSize()) {
            when (screen) {
                AppScreen.CAMERA -> {
                    val openGallery = { screen = AppScreen.GALLERY }
                    val openSettings = { screen = AppScreen.SETTINGS }
                    if (cameraGranted) {
                        savedScreens.SaveableStateProvider("viewfinder") {
                            LumoraTheme(AppThemeMode.DARK) {
                                CameraScreen(repository, presets, openGallery, openSettings) { device.lastLens = it }
                            }
                        }
                    } else {
                        CameraPermissionScreen(openSystemSettings, onEnableCamera, openGallery, openSettings)
                    }
                }
                AppScreen.GALLERY -> GalleryScreen(repository, backToCamera)
                AppScreen.SETTINGS -> SettingsScreen(
                    repository, device.lastLens, updates,
                    themeMode = themeMode, onThemeChange = onThemeChange, onBack = backToCamera
                )
            }
            UpdateDialog(updates)
        }
    }
}

@Composable
private fun CameraPermissionScreen(
    openSystemSettings: Boolean, onEnable: () -> Unit, onOpenGallery: () -> Unit, onOpenSettings: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().safeAreaPadding().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Camera, null, Modifier.size(56.dp), tint = Accent)
        Spacer(Modifier.height(16.dp))
        Text("LUMORA Camera", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text("相机权限仅用于取景和拍摄。不授权也可以浏览作品和导入照片。", color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onEnable) { Text(if (openSystemSettings) "前往系统设置" else "开启相机") }
        TextButton(onClick = onOpenGallery) { Text("打开作品库") }
        TextButton(onClick = onOpenSettings) { Text("打开应用设置") }
    }
}
