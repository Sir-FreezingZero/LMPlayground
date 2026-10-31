package com.druk.lmplayground.theme

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val PlaygroundDarkColorScheme = darkColorScheme(
    primary = Gray88,
    onPrimary = Gray12,
    primaryContainer = Gray24,
    onPrimaryContainer = Gray92,
    inversePrimary = Gray30,
    secondary = Gray80,
    onSecondary = Gray12,
    secondaryContainer = Gray20,
    onSecondaryContainer = Gray92,
    tertiary = Gray70,
    onTertiary = Gray12,
    tertiaryContainer = Gray24,
    onTertiaryContainer = Gray92,
    error = Red80,
    onError = Red20,
    errorContainer = Red30,
    onErrorContainer = Red90,
    background = Gray0,
    onBackground = Gray92,
    surface = Gray0,
    onSurface = Gray88,
    inverseSurface = Gray92,
    inverseOnSurface = Gray12,
    surfaceVariant = Gray20,
    onSurfaceVariant = Gray70,
    outline = Gray55,
    outlineVariant = Gray24,
    surfaceTint = Gray88,
    surfaceDim = Gray0,
    surfaceBright = Gray16,
    surfaceContainerLowest = Gray0,
    surfaceContainerLow = Gray5,
    surfaceContainer = Gray8,
    surfaceContainerHigh = Gray12,
    surfaceContainerHighest = Gray16
)

private val PlaygroundLightColorScheme = lightColorScheme(
    primary = Gray12,
    onPrimary = Gray100,
    primaryContainer = Gray88,
    onPrimaryContainer = Gray5,
    inversePrimary = Gray80,
    secondary = Gray30,
    onSecondary = Gray100,
    secondaryContainer = Gray88,
    onSecondaryContainer = Gray12,
    tertiary = Gray45,
    onTertiary = Gray100,
    tertiaryContainer = Gray92,
    onTertiaryContainer = Gray12,
    error = Red40,
    onError = Color.White,
    errorContainer = Red90,
    onErrorContainer = Red10,
    background = Gray100,
    onBackground = Gray5,
    surface = Gray100,
    onSurface = Gray5,
    inverseSurface = Gray16,
    inverseOnSurface = Gray95,
    surfaceVariant = Gray92,
    onSurfaceVariant = Gray30,
    outline = Gray45,
    outlineVariant = Gray80,
    surfaceTint = Gray12,
    surfaceDim = Gray88,
    surfaceBright = Gray100,
    surfaceContainerLowest = Gray100,
    surfaceContainerLow = Gray97,
    surfaceContainer = Gray95,
    surfaceContainerHigh = Gray92,
    surfaceContainerHighest = Gray88
)

@SuppressLint("NewApi")
@Composable
fun PlaygroundTheme(
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    // Off so the phone's wallpaper colors don't replace the grayscale scheme.
    isDynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val dynamicColor = isDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val myColorScheme = when {
        dynamicColor && isDarkTheme -> {
            dynamicDarkColorScheme(LocalContext.current)
        }
        dynamicColor && !isDarkTheme -> {
            dynamicLightColorScheme(LocalContext.current)
        }
        isDarkTheme -> PlaygroundDarkColorScheme
        else -> PlaygroundLightColorScheme
    }

    MaterialTheme(
        colorScheme = myColorScheme,
        typography = PlaygroundTypography,
        content = content
    )
}
