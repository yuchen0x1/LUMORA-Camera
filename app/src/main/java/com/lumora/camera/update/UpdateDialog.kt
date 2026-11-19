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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.ZoneId

@Composable
internal fun UpdateDialog(model: UpdateViewModel) {
    val context = LocalContext.current
    val installation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.installationReturned()
    }
    fun startInstall(offer: UpdateOffer) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val permission = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            if (runCatching { context.startActivity(permission) }.isSuccess) return
        }
        model.install(offer, installation::launch)
    }
    when (val state = model.state) {
        is UpdateState.Available -> {
            val offer = state.offer
            val release = offer.release
            val incompatible = model.incompatibility(offer)
            AlertDialog(
                onDismissRequest = { model.later(offer) },
                title = { Text("LUMORA Camera ${release.name}") },
                text = {
                    Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val date = release.published.atZone(ZoneId.systemDefault()).toLocalDate()
                        Text("当前 ${model.installedVersion} · $date · ${Formatter.formatFileSize(context, release.apk.size)}")
                        if (release.notes.isNotBlank()) Text(release.notes)
                        if (incompatible != null) Text(incompatible)
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.details))) }
                        }) { Text("查看发布详情") }
                    }
                },
                confirmButton = {
                    if (incompatible == null) Button(onClick = { model.download(offer) }) { Text("立即更新") }
                },
                dismissButton = { TextButton(onClick = { model.later(offer) }) { Text("稍后") } }
            )
        }
        is UpdateState.Downloading -> {
            val size = state.offer.release.apk.size
            AlertDialog(
                onDismissRequest = {},
                title = { Text("正在下载并校验") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(progress = { state.received.toFloat() / size }, Modifier.fillMaxWidth())
                        Text("${Formatter.formatFileSize(context, state.received)} / ${Formatter.formatFileSize(context, size)}")
                        if (state.bytesPerSecond > 0) Text("${Formatter.formatFileSize(context, state.bytesPerSecond)}/秒")
                    }
                },
                confirmButton = { TextButton(onClick = model::cancelDownload) { Text("取消下载") } }
            )
        }
        is UpdateState.Ready -> AlertDialog(
            onDismissRequest = { model.later(state.offer) },
            title = { Text("更新包已验证") },
            text = { Text("通过 Android 安装器确认安装。若系统要求授权，请允许此来源安装，返回后再次点击安装。") },
            confirmButton = { Button(onClick = { startInstall(state.offer) }) { Text("安装更新") } },
            dismissButton = { TextButton(onClick = { model.later(state.offer) }) { Text("稍后") } }
        )
        else -> Unit
    }
}
