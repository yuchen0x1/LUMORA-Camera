package com.lumora.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumora.camera.camera.DeviceCapabilities
import com.lumora.camera.data.FilmPreset
import com.lumora.camera.data.FilmPresets
import com.lumora.camera.data.PhotoRepository
import com.lumora.camera.update.UpdateDialog
import com.lumora.camera.update.UpdateViewModel

internal val Ink = Color(0xFFF4F1EB)
internal val Muted = Color(0xFFB1AEA8)
internal val Panel = Color(0xE0191918)
internal val Accent = Color(0xFFD6B27A)
internal val Background = Color(0xFF101110)

class CameraDeviceState : ViewModel() {
    var lastLens by mutableStateOf<DeviceCapabilities?>(null)
}

class MainActivity : ComponentActivity() {
    internal var volumeShutter: (() -> Unit)? = null
    private val shutterKeys = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Accent, onPrimary = Background, background = Background,
                surface = Background, onBackground = Ink, onSurface = Ink,
                surfaceVariant = Color(0xFF282927), onSurfaceVariant = Muted
            )) { LumoraApp() }
        }
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
        val openSettings = !granted && asked && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        LumoraNavigation(repository, presets, device, updates, granted, openSettings) {
            if (openSettings) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } else {
                permission.launch(Manifest.permission.CAMERA)
            }
        }
    }
}

private enum class AppScreen { CAMERA, GALLERY, SETTINGS }

@Composable
internal fun LumoraNavigation(
    repository: PhotoRepository, presets: List<FilmPreset>, device: CameraDeviceState, updates: UpdateViewModel,
    cameraGranted: Boolean, openSystemSettings: Boolean, onEnableCamera: () -> Unit
) {
    val savedScreens = rememberSaveableStateHolder()
    var screen by rememberSaveable { mutableStateOf(AppScreen.CAMERA) }
    val backToCamera = { screen = AppScreen.CAMERA }
    BackHandler(enabled = screen != AppScreen.CAMERA, onBack = backToCamera)
    Surface(Modifier.fillMaxSize()) {
        when (screen) {
            AppScreen.CAMERA -> {
                val openGallery = { screen = AppScreen.GALLERY }
                val openSettings = { screen = AppScreen.SETTINGS }
                if (cameraGranted) {
                    savedScreens.SaveableStateProvider("viewfinder") {
                        CameraScreen(repository, presets, openGallery, openSettings) { device.lastLens = it }
                    }
                } else {
                    CameraPermissionScreen(openSystemSettings, onEnableCamera, openGallery, openSettings)
                }
            }
            AppScreen.GALLERY -> GalleryScreen(repository, backToCamera)
            AppScreen.SETTINGS -> SettingsScreen(repository, device.lastLens, updates, backToCamera)
        }
        UpdateDialog(updates)
    }
}

@Composable
private fun CameraPermissionScreen(
    openSystemSettings: Boolean, onEnable: () -> Unit, onOpenGallery: () -> Unit, onOpenSettings: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(28.dp),
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
