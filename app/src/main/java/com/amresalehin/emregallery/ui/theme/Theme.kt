package com.amresalehin.emregallery.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Default Emre Gallery Monochrome palette plus all 22 color themes
 * from MSC72m/media_downloader with instant Dark/Light switching.
 */
enum class AppColorTheme(
    val id: String,
    val label: String,
    val lightPrimary: Color,
    val darkPrimary: Color
) {
    MONOCHROME("monochrome", "Monochrome", Color(0xFF202020), Color(0xFFEEEEEE)),
    AMBER("amber", "Amber", Color(0xFFB45309), Color(0xFFFBBF24)),
    BLUE("blue", "Blue", Color(0xFF1D4ED8), Color(0xFF60A5FA)),
    CORAL("coral", "Coral", Color(0xFFC2410C), Color(0xFFFB7185)),
    CYBERPUNK("cyberpunk", "Cyberpunk", Color(0xFFA21CAF), Color(0xFFE879F9)),
    CYAN("cyan", "Cyan", Color(0xFF0E7490), Color(0xFF22D3EE)),
    EMERALD("emerald", "Emerald", Color(0xFF047857), Color(0xFF34D399)),
    ESPRESSO("espresso", "Espresso", Color(0xFF6B4226), Color(0xFFD6A276)),
    GLACIER("glacier", "Glacier", Color(0xFF0369A1), Color(0xFF7DD3FC)),
    GOLD("gold", "Gold", Color(0xFFA16207), Color(0xFFFACC15)),
    GREEN("green", "Green", Color(0xFF15803D), Color(0xFF4ADE80)),
    INDIGO("indigo", "Indigo", Color(0xFF4338CA), Color(0xFF818CF8)),
    LIME("lime", "Lime", Color(0xFF4D7C0F), Color(0xFFA3E635)),
    NAVY("navy", "Navy", Color(0xFF1E3A8A), Color(0xFF93C5FD)),
    ORANGE("orange", "Orange", Color(0xFFC2410C), Color(0xFFFB923C)),
    PINK("pink", "Pink", Color(0xFFBE185D), Color(0xFFF472B6)),
    PURPLE("purple", "Purple", Color(0xFF7E22CE), Color(0xFFC084FC)),
    RED("red", "Red", Color(0xFFB91C1C), Color(0xFFF87171)),
    ROSE("rose", "Rose", Color(0xFFBE123C), Color(0xFFFB7185)),
    SLATE("slate", "Slate", Color(0xFF334155), Color(0xFF94A3B8)),
    SUNSET("sunset", "Sunset", Color(0xFFEA580C), Color(0xFFFDBA74)),
    TEAL("teal", "Teal", Color(0xFF0F766E), Color(0xFF2DD4BF)),
    VIOLET("violet", "Violet", Color(0xFF6D28D9), Color(0xFFA78BFA));

    companion object {
        fun fromId(id: String?): AppColorTheme =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: MONOCHROME
    }
}

private val Light = lightColorScheme(
    primary = Color(0xFF202020),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8E8E8),
    onPrimaryContainer = Color(0xFF141414),
    secondary = Color(0xFF555555),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEEEEEE),
    onSecondaryContainer = Color(0xFF222222),
    tertiary = Color(0xFF383838),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE2E2E2),
    onTertiaryContainer = Color(0xFF141414),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF141414),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141414),
    surfaceVariant = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFF444444),
    surfaceTint = Color(0x00000000),
    inverseSurface = Color(0xFF282828),
    inverseOnSurface = Color(0xFFF5F5F5),
    inversePrimary = Color(0xFFD4D4D4),
    outline = Color(0xFF757575),
    outlineVariant = Color(0xFFD0D0D0),
    scrim = Color(0x99000000)
)

private val Dark = darkColorScheme(
    primary = Color(0xFFEEEEEE),
    onPrimary = Color(0xFF161616),
    primaryContainer = Color(0xFF303030),
    onPrimaryContainer = Color(0xFFF4F4F4),
    secondary = Color(0xFFBBBBBB),
    onSecondary = Color(0xFF1C1C1C),
    secondaryContainer = Color(0xFF2C2C2C),
    onSecondaryContainer = Color(0xFFEAEAEA),
    tertiary = Color(0xFFD4D4D4),
    onTertiary = Color(0xFF1A1A1A),
    tertiaryContainer = Color(0xFF2E2E2E),
    onTertiaryContainer = Color(0xFFEEEEEE),
    background = Color(0xFF101010),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF101010),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFFC0C0C0),
    surfaceTint = Color(0x00000000),
    inverseSurface = Color(0xFFEDEDED),
    inverseOnSurface = Color(0xFF222222),
    inversePrimary = Color(0xFF404040),
    outline = Color(0xFF808080),
    outlineVariant = Color(0xFF404040),
    scrim = Color(0x99000000)
)

private fun buildColorScheme(accent: AppColorTheme, darkTheme: Boolean): ColorScheme {
    if (accent == AppColorTheme.MONOCHROME) {
        return if (darkTheme) Dark else Light
    }
    return if (darkTheme) {
        Dark.copy(
            primary = accent.darkPrimary,
            onPrimary = Color(0xFF101010),
            primaryContainer = accent.lightPrimary.copy(alpha = 0.32f),
            onPrimaryContainer = accent.darkPrimary,
            secondary = accent.darkPrimary.copy(alpha = 0.82f),
            secondaryContainer = accent.lightPrimary.copy(alpha = 0.22f),
            onSecondaryContainer = Color(0xFFF5F5F5),
            tertiary = accent.darkPrimary
        )
    } else {
        Light.copy(
            primary = accent.lightPrimary,
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = accent.lightPrimary.copy(alpha = 0.14f),
            onPrimaryContainer = accent.lightPrimary,
            secondary = accent.lightPrimary.copy(alpha = 0.85f),
            secondaryContainer = accent.lightPrimary.copy(alpha = 0.10f),
            onSecondaryContainer = Color(0xFF181818),
            tertiary = accent.lightPrimary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmreGalleryTheme(
    theme: String = "system",
    colorTheme: String = AppColorTheme.MONOCHROME.id,
    surfaceColor: Color? = null,
    content: @Composable () -> Unit
) {
    val darkTheme = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val resolvedAccent = AppColorTheme.fromId(colorTheme)
    val colors = buildColorScheme(resolvedAccent, darkTheme)
    MaterialTheme(colorScheme = colors) {
        // A null ripple configuration disables Material 3's ripple/press indication
        // throughout the app while preserving click, long-press, and gesture behavior.
        CompositionLocalProvider(LocalRippleConfiguration provides null) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = surfaceColor ?: MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                content = content
            )
        }
    }
}
