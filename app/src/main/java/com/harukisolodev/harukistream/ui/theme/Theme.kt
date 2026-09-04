package com.harukisolodev.harukistream.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val HarukiBg = Color(0xFF050608)
val HarukiSidebar = Color(0xFF090B0F)
val HarukiSurface = Color(0xFF0E1117)
val HarukiCard = Color(0xFF12161E)
val HarukiCard2 = Color(0xFF191E28)
val HarukiCardSoft = Color(0xFF0C1016)
val HarukiBorder = Color(0xFF2A303B)
val HarukiBorderSoft = Color(0xFF1C222B)
val HarukiPrimary = Color(0xFFFF3348)
val HarukiPrimaryDark = Color(0xFFDC1630)
val HarukiViolet = Color(0xFF7B4CFF)
val HarukiCyan = Color(0xFF45D6FF)
val HarukiSuccess = Color(0xFF40D89B)
val HarukiWarning = Color(0xFFFFC35A)
val HarukiDanger = Color(0xFFFF5C70)
val HarukiText = Color(0xFFF8F9FC)
val HarukiMuted = Color(0xFFA4ADBB)
val HarukiMuted2 = Color(0xFF768192)

private val Colors = darkColorScheme(
    primary = HarukiPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF47131C),
    onPrimaryContainer = HarukiText,
    secondary = HarukiViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF261F3F),
    onSecondaryContainer = HarukiText,
    tertiary = HarukiCyan,
    onTertiary = Color(0xFF001C25),
    background = HarukiBg,
    onBackground = HarukiText,
    surface = HarukiSurface,
    onSurface = HarukiText,
    surfaceVariant = HarukiCard,
    onSurfaceVariant = HarukiMuted,
    outline = HarukiBorder,
    outlineVariant = HarukiBorderSoft,
    error = HarukiDanger,
    onError = Color.White
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 31.sp, lineHeight = 37.sp, fontWeight = FontWeight.ExtraBold, color = HarukiText),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold, color = HarukiText),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, color = HarukiText),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, color = HarukiText),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, color = HarukiText),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = HarukiText),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, color = HarukiMuted),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, color = HarukiText),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = HarukiText),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold, color = HarukiMuted)
)

@Composable
fun HarukiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = AppTypography) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = HarukiBg,
            contentColor = HarukiText,
            tonalElevation = 0.dp,
            content = content
        )
    }
}
