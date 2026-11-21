package com.lumora.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Applies the app's interactive safe area: drawing insets plus gesture insets.
 * The padding modifier consumes the applied insets, so nested content should inherit it
 * instead of applying another safe-area modifier.
 */
@Composable
internal fun Modifier.safeAreaPadding(): Modifier = windowInsetsPadding(WindowInsets.safeContent)

/** Keep white system icons readable over bright edges without moving or intercepting the preview. */
@Composable
internal fun BoxScope.ViewfinderSystemBarScrims() {
    val scrim = ViewfinderTokens.scrim
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()
        .windowInsetsTopHeight(WindowInsets.statusBars).background(scrim))
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        .windowInsetsBottomHeight(WindowInsets.navigationBars).background(scrim))
}
