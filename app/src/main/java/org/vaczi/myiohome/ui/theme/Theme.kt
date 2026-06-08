package org.vaczi.myiohome.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import org.vaczi.myiohome.data.local.AppTheme

// --- Dynamic/blue theme fallback (when dynamic color is unavailable) ------------------------------
private val BlueDarkColors = darkColorScheme(
    primary = Blue80,
    secondary = BlueGrey80,
    tertiary = Cyan80,
)

private val BlueLightColors = lightColorScheme(
    primary = Blue40,
    secondary = BlueGrey40,
    tertiary = Cyan40,
)

// --- Classic myIO green theme (always dark, AMOLED-black background) -------------------------------
private val GreenDarkColors = darkColorScheme(
    primary = GreenPrimaryDark,
    onPrimary = GreenOnPrimaryDark,
    primaryContainer = GreenPrimaryContainerDark,
    onPrimaryContainer = GreenOnPrimaryContainerDark,
    secondary = GreenSecondaryDark,
    onSecondary = GreenOnSecondaryDark,
    secondaryContainer = GreenSecondaryContainerDark,
    onSecondaryContainer = GreenOnSecondaryContainerDark,
    tertiary = GreenTertiaryDark,
    onTertiary = GreenOnTertiaryDark,
    tertiaryContainer = GreenTertiaryContainerDark,
    onTertiaryContainer = GreenOnTertiaryContainerDark,
    background = GreenBackgroundDark,
    onBackground = GreenOnBackgroundDark,
    surface = GreenSurfaceDark,
    onSurface = GreenOnSurfaceDark,
    surfaceVariant = GreenSurfaceVariantDark,
    onSurfaceVariant = GreenOnSurfaceVariantDark,
    surfaceContainerLowest = GreenSurfaceContainerLowestDark,
    surfaceContainerLow = GreenSurfaceContainerLowDark,
    surfaceContainer = GreenSurfaceContainerDark,
    surfaceContainerHigh = GreenSurfaceContainerHighDark,
    surfaceContainerHighest = GreenSurfaceContainerHighestDark,
    outline = GreenOutlineDark,
)

@Composable
fun MyioHomeTheme(
    theme: AppTheme = AppTheme.GREEN,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when (theme) {
        // Material You: follow the wallpaper on Android 12+, fall back to the fixed blue scheme below.
        AppTheme.DYNAMIC -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> BlueDarkColors
            else -> BlueLightColors
        }
        // Classic myIO look: always the AMOLED-black dark scheme, regardless of system light/dark.
        AppTheme.GREEN -> GreenDarkColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
