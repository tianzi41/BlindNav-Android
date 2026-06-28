/**
 * Theme.kt - Material 3 主题定义
 * 定义深色主题，高对比度，适合视障用户
 */
package com.blindnav.app.ui.theme

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

// 深色主题配色方案
private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryDark,
    onPrimaryContainer = Color.White,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryDark,
    onSecondaryContainer = Color.Black,
    tertiary = Info,
    onTertiary = Color.White,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = Error,
    onError = Color.White,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color.White,
    outline = Color(0xFF8B8B8B),
    outlineVariant = Color(0xFF4A4A4A)
)

// 浅色主题配色方案（备用）
private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryLight,
    onPrimaryContainer = Color.Black,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryLight,
    onSecondaryContainer = Color.Black,
    tertiary = Info,
    onTertiary = Color.White,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    error = Error,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0)
)

/**
 * 盲人导航应用主题
 * 默认使用深色主题，高对比度
 */
@Composable
fun BlindNavTheme(
    darkTheme: Boolean = true,  // 默认深色主题
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
