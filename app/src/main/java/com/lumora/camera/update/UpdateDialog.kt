package com.lumora.camera.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.time.ZoneId

@Composable
internal fun UpdateDialog(model: UpdateViewModel) {
    val context = LocalContext.current
    val pendingInstallSession = rememberSaveable { mutableStateOf<String?>(null) }
    val installation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val sessionId = pendingInstallSession.value
        pendingInstallSession.value = null
        if (sessionId != null) model.installationReturned(sessionId, result)
    }
    fun startInstall(offer: UpdateOffer) {
        if (pendingInstallSession.value != null) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            val permission = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            if (runCatching { context.startActivity(permission) }.isSuccess) return
        }
        model.install(offer) { install ->
            pendingInstallSession.value = install.sessionId
            try {
                installation.launch(install.intent)
            } catch (error: Exception) {
                if (pendingInstallSession.value == install.sessionId) pendingInstallSession.value = null
                throw error
            }
        }
    }
    UpdateDialogContent(
        state = model.state,
        installedVersion = model.installedVersion,
        incompatibility = model::incompatibility,
        onLater = model::later,
        onDownload = model::download,
        onCancelDownload = model::cancelDownload,
        onInstall = ::startInstall,
        onClearNotice = model::clearNotice,
        onCheckAgain = { model.check(manual = true) }
    )
}

@Composable
internal fun UpdateDialogContent(
    state: UpdateState,
    installedVersion: String,
    incompatibility: (UpdateOffer) -> String?,
    onLater: (UpdateOffer) -> Unit,
    onDownload: (UpdateOffer) -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: (UpdateOffer) -> Unit,
    onClearNotice: () -> Unit = {},
    onCheckAgain: () -> Unit = {}
) {
    val context = LocalContext.current
    when (state) {
        is UpdateState.Available -> {
            val offer = state.offer
            val release = offer.release
            val incompatible = incompatibility(offer)
            AlertDialog(
                onDismissRequest = { onLater(offer) },
                title = { Text("LUMORA Camera ${release.name}") },
                text = {
                    Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val date = release.published.atZone(ZoneId.systemDefault()).toLocalDate()
                        Text("当前 $installedVersion · $date · ${Formatter.formatFileSize(context, release.apk.size)}")
                        if (release.notes.isNotBlank()) Text(release.notes)
                        if (incompatible != null) Text(incompatible)
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.details))) }
                        }) { Text("查看发布详情") }
                    }
                },
                confirmButton = {
                    if (incompatible == null) Button(onClick = { onDownload(offer) }) { Text("立即更新") }
                },
                dismissButton = { TextButton(onClick = { onLater(offer) }) { Text("稍后") } }
            )
        }
        is UpdateState.Downloading -> {
            val size = state.offer.release.apk.size
            AlertDialog(
                onDismissRequest = {},
                title = { Text("正在下载并校验") },
                text = {
                    Column(
                        Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { (state.received.toFloat() / size).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().semantics {
                                stateDescription = "已下载 ${Formatter.formatFileSize(context, state.received)}，共 ${Formatter.formatFileSize(context, size)}"
                            }
                        )
                        Text("${Formatter.formatFileSize(context, state.received)} / ${Formatter.formatFileSize(context, size)}")
                        if (state.bytesPerSecond > 0) Text("${Formatter.formatFileSize(context, state.bytesPerSecond)}/秒")
                    }
                },
                confirmButton = { TextButton(onClick = onCancelDownload) { Text("取消下载") } }
            )
        }
        is UpdateState.Ready -> AlertDialog(
            onDismissRequest = { onLater(state.offer) },
            title = { Text("更新包已验证") },
            text = {
                Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                    Text("通过 Android 安装器确认安装。若系统要求授权，请允许此来源安装，返回后再次点击安装。")
                }
            },
            confirmButton = { Button(onClick = { onInstall(state.offer) }) { Text("安装更新") } },
            dismissButton = { TextButton(onClick = { onLater(state.offer) }) { Text("稍后") } }
        )
        is UpdateState.Installing -> AlertDialog(
            onDismissRequest = {},
            title = {
                Text(when (state.phase) {
                    InstallPhase.VERIFYING -> "正在验证安装包"
                    InstallPhase.LAUNCHING -> "正在打开系统安装器"
                    InstallPhase.WAITING_FOR_RESULT -> "请完成系统安装"
                })
            },
            text = {
                Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                    Text(when (state.phase) {
                        InstallPhase.VERIFYING -> "验证完成后将打开 Android 系统安装器。"
                        InstallPhase.LAUNCHING -> "正在将已验证的安装包交给 Android 系统安装器。"
                        InstallPhase.WAITING_FOR_RESULT -> "完成或取消安装后，请返回 LUMORA；安装包会在系统安装器返回结果后清理。"
                    })
                }
            },
            confirmButton = {}
        )
        is UpdateState.Notice -> AlertDialog(
            onDismissRequest = onClearNotice,
            title = { Text("应用更新") },
            text = { Text(state.text) },
            confirmButton = {
                TextButton(onClick = onClearNotice) { Text("知道了") }
            },
            dismissButton = {
                if (state.retryable) TextButton(onClick = onCheckAgain) { Text("重新检查") }
            }
        )
        else -> Unit
    }
}
