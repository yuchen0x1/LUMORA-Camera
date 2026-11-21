package com.lumora.camera.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

internal enum class AppThemeMode(val label: String) {
    SYSTEM("跟随系统"), LIGHT("白天"), DARK("夜间");

    @Composable
    fun isDark(): Boolean = when (this) {
        SYSTEM -> isSystemInDarkTheme()
        LIGHT -> false
        DARK -> true
    }
}

internal val LightColors = lightColorScheme(
    primary = Color(0xFF765426), onPrimary = Color.White, surfaceTint = Color(0xFF765426),
    primaryContainer = Color(0xFFF5DFBB), onPrimaryContainer = Color(0xFF2B1900),
    secondary = Color(0xFF54634F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E8CE), onSecondaryContainer = Color(0xFF131F0F),
    tertiary = Color(0xFF35646C), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB8EAF3), onTertiaryContainer = Color(0xFF001F24),
    background = Color(0xFFF8F9F5), onBackground = Color(0xFF1A1D19),
    surface = Color(0xFFF8F9F5), onSurface = Color(0xFF1A1D19),
    surfaceVariant = Color(0xFFE1E5DD), onSurfaceVariant = Color(0xFF545B55),
    surfaceDim = Color(0xFFD9DDD5), surfaceBright = Color(0xFFF8F9F5),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF2F3EE),
    surfaceContainer = Color(0xFFECEEE7), surfaceContainerHigh = Color(0xFFE6E9E1),
    surfaceContainerHighest = Color(0xFFE1E5DD),
    outline = Color(0xFF717970), outlineVariant = Color(0xFFC4CAC2),
    inverseSurface = Color(0xFF2E332D), inverseOnSurface = Color(0xFFEFF2E9),
    inversePrimary = Color(0xFFD6B27A),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    scrim = Color.Black
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFD6B27A), onPrimary = Color(0xFF2B1D07), surfaceTint = Color(0xFFD6B27A),
    primaryContainer = Color(0xFF59421F), onPrimaryContainer = Color(0xFFF5DFBB),
    secondary = Color(0xFFBBCDB2), onSecondary = Color(0xFF273422),
    secondaryContainer = Color(0xFF3D4B37), onSecondaryContainer = Color(0xFFD7E8CE),
    tertiary = Color(0xFF9DCDD6), onTertiary = Color(0xFF00363E),
    tertiaryContainer = Color(0xFF1B4C54), onTertiaryContainer = Color(0xFFB8EAF3),
    background = Color(0xFF171918), onBackground = Color(0xFFE6EAE2),
    surface = Color(0xFF171918), onSurface = Color(0xFFE6EAE2),
    surfaceVariant = Color(0xFF424A43), onSurfaceVariant = Color(0xFFB8C0B8),
    surfaceDim = Color(0xFF101311), surfaceBright = Color(0xFF373D37),
    surfaceContainerLowest = Color(0xFF101311), surfaceContainerLow = Color(0xFF1C211C),
    surfaceContainer = Color(0xFF202620), surfaceContainerHigh = Color(0xFF2A302A),
    surfaceContainerHighest = Color(0xFF353C35),
    outline = Color(0xFF879187), outlineVariant = Color(0xFF424A43),
    inverseSurface = Color(0xFFE6EAE2), inverseOnSurface = Color(0xFF2E332D),
    inversePrimary = Color(0xFF765426),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black
)

internal object LumoraSpacing {
    val xs = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val page = 20.dp
    val section = 24.dp
    val touchTarget = 48.dp
}

internal object LumoraMotion {
    const val shortMs = 150
    const val mediumMs = 250
}

internal object LumoraThemeTokens {
    val photoMatte = Color(0xFF101110)
    val success: Color @Composable get() = if (MaterialTheme.colorScheme.surface.luminance() < .5f) Color(0xFF9DD4A5) else Color(0xFF246534)
    val warning: Color @Composable get() = MaterialTheme.colorScheme.primary
    val info: Color @Composable get() = MaterialTheme.colorScheme.tertiary
    val focus: Color @Composable get() = MaterialTheme.colorScheme.primary
    val hover: Color @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)
    val pressed: Color @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)
    val disabled: Color @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
}

// Photo overlays need predictable contrast regardless of the app theme or scene.
internal object ViewfinderTokens {
    val background = Color.Black
    val ink = Color(0xFFF4F1EB)
    val muted = Color(0xFFB8C0B8)
    val panel = Color(0xEB191D19)
    val accent = Color(0xFFD6B27A)
    val focus = Color(0xFF7AF3B2)
    val failure = Color(0xFFFFB4AB)
    val scrim = Color.Black.copy(alpha = .68f)
}

@Composable
internal fun LumoraTheme(mode: AppThemeMode = AppThemeMode.SYSTEM, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (mode.isDark()) DarkColors else LightColors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}
