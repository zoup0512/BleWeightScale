package dev.sopho.scale.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Blue = Color(0xFF1B6CF2)
private val BlueDark = Color(0xFF9CC3FF)
private val Teal = Color(0xFF00897B)

private val LightColors = lightColorScheme(
    primary = Blue,
    secondary = Teal,
)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    secondary = Teal,
)

@Composable
fun BleWeightScaleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Only the icon colour needs setting: from API 35 the status bar background is always
            // drawn by the app's own content, so the platform colour is ignored.
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
