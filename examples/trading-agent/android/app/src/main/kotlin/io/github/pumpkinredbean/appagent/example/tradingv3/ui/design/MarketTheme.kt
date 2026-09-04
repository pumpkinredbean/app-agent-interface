package io.github.pumpkinredbean.appagent.example.tradingv3.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class MarketColors(
    val page: Color,
    val surface: Color,
    val raised: Color,
    val soft: Color,
    val text: Color,
    val textSoft: Color,
    val textDim: Color,
    val line: Color,
    val lineStrong: Color,
    val accent: Color,
    val accentStrong: Color,
    val accentSoft: Color,
    val amber: Color,
    val amberSoft: Color,
    val red: Color,
    val shadow: Color,
)

private val DarkColors = MarketColors(
    page = Color(0xFF0E100F),
    surface = Color(0xFF151815),
    raised = Color(0xFF1B1F1B),
    soft = Color(0xFF20251F),
    text = Color(0xFFF1F2ED),
    textSoft = Color(0xFFA9AFA8),
    textDim = Color(0xFF737B74),
    line = Color(0x1AF0F5EF),
    lineStrong = Color(0x2EF0F5EF),
    accent = Color(0xFF70DDB2),
    accentStrong = Color(0xFF42C897),
    accentSoft = Color(0x1A70DDB2),
    amber = Color(0xFFE9C978),
    amberSoft = Color(0x1AE9C978),
    red = Color(0xFFF2847A),
    shadow = Color(0x99000000),
)

private val LightColors = MarketColors(
    page = Color(0xFFEEEFE9),
    surface = Color(0xFFFBFCF7),
    raised = Color(0xFFF1F3ED),
    soft = Color(0xFFE9EDE6),
    text = Color(0xFF171B18),
    textSoft = Color(0xFF606A62),
    textDim = Color(0xFF879087),
    line = Color(0x1A141C16),
    lineStrong = Color(0x2E141C16),
    accent = Color(0xFF087E5B),
    accentStrong = Color(0xFF056947),
    accentSoft = Color(0x14087E5B),
    amber = Color(0xFF8C6A16),
    amberSoft = Color(0x178C6A16),
    red = Color(0xFFC75149),
    shadow = Color(0x29302F2B),
)

val LocalMarketColors = staticCompositionLocalOf { DarkColors }

@Composable
fun MarketTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMarketColors provides if (darkTheme) DarkColors else LightColors) {
        content()
    }
}
