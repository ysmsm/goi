package dev.seungmin.goi.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pleos Connect UI Design Guide의 토큰만 쓴다.
 * - 색: Grayscale(basic_00~900) + 강조색 하나(informative_positive). 모드별 색은 쓰지 않는다.
 * - 글꼴: 시스템 sans-serif = Asta Sans(Pleos 시스템 폰트).
 * - 크기: Connect-S는 160dpi라 가이드의 px 값이 그대로 dp다.
 */
@Immutable
data class GoiColors(
    /** background_primary */
    val background: Color,
    /** surface_basic: 카드·타일 */
    val surface: Color,
    /** surface_high: 선택된 항목, 눌림 */
    val surfaceHigh: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** button_basic_enabled */
    val buttonBasic: Color,
    /** button_filled_enabled */
    val buttonFilled: Color,
    val onButtonFilled: Color,
    /** 유일한 강조색. "고이가 지금 지키는 것"과 주 동작에만 쓴다. */
    val accent: Color,
    val onAccent: Color,
    val divider: Color,
    val scrim: Color,
)

private val Light = GoiColors(
    background = Color(0xFFF7F8FA), // basic_50
    surface = Color(0xFFFFFFFF), // basic_00
    surfaceHigh = Color(0xFFEDEEF2), // basic_100
    textPrimary = Color(0xFF131417), // basic_900
    textSecondary = Color(0xA3131417), // static_dark_300 (64%)
    textTertiary = Color(0x52131417), // static_dark_400 (32%)
    buttonBasic = Color(0x0D131417), // alpha_dark_50
    buttonFilled = Color(0xFF44464E), // basic_600
    onButtonFilled = Color(0xFFFFFFFF),
    accent = Color(0xFF0064FF), // informative_positive (light)
    onAccent = Color(0xFFFFFFFF),
    divider = Color(0xFFE0E1E6), // basic_200
    scrim = Color(0x80131417),
)

private val Dark = GoiColors(
    background = Color(0xFF131417), // basic_00 (dark)
    surface = Color(0xFF1D1E21), // basic_50 (dark)
    surfaceHigh = Color(0xFF313236), // basic_100 (dark)
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xA3FFFFFF),
    textTertiary = Color(0x52FFFFFF),
    buttonBasic = Color(0x14FFFFFF),
    buttonFilled = Color(0xFFE0E1E6), // basic_600 (dark)
    onButtonFilled = Color(0xFF131417),
    accent = Color(0xFF0082FF), // informative_positive (dark)
    onAccent = Color(0xFFFFFFFF),
    divider = Color(0xFF313236),
    scrim = Color(0xB3000000),
)

@Immutable
data class GoiType(
    val headlineL: TextStyle,
    val headlineM: TextStyle,
    val headlineS: TextStyle,
    val titleL: TextStyle,
    val titleM: TextStyle,
    val titleS: TextStyle,
    val bodyL: TextStyle,
    val bodyM: TextStyle,
    val bodyS: TextStyle,
    val labelM: TextStyle,
    val labelS: TextStyle,
    /** 운반 요약의 숫자처럼 한눈에 읽어야 하는 값 */
    val display: TextStyle,
)

private fun style(size: Int, line: Int, weight: FontWeight) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = TextUnit.Unspecified,
)

// Pleos Typography: Headline L/M/S 60/80·56/72·48/64 ExtraBold, Title 40/52·36/44·32/40,
// Body 30/38·28/36·26/34, Label 24/28·20/24
private val Type = GoiType(
    headlineL = style(60, 80, FontWeight.ExtraBold),
    headlineM = style(56, 72, FontWeight.ExtraBold),
    headlineS = style(48, 64, FontWeight.ExtraBold),
    titleL = style(40, 52, FontWeight.Bold),
    titleM = style(36, 44, FontWeight.Bold),
    titleS = style(32, 40, FontWeight.Bold),
    bodyL = style(30, 38, FontWeight.Normal),
    bodyM = style(28, 36, FontWeight.Normal),
    bodyS = style(26, 34, FontWeight.Normal),
    labelM = style(24, 28, FontWeight.Normal),
    labelS = style(20, 24, FontWeight.Normal),
    display = style(96, 104, FontWeight.ExtraBold),
)

/** 간격·크기. 버튼 높이는 Basic button L 96 / XL 128. */
object Dimens {
    val gap = 16.dp
    val gapL = 24.dp
    val pad = 32.dp
    val radius = 32.dp
    val radiusS = 20.dp
    val buttonL = 96.dp
    val buttonXL = 128.dp
    val icon = 48.dp
    val iconL = 64.dp
}

private val LocalColors = staticCompositionLocalOf { Light }
private val LocalType = staticCompositionLocalOf { Type }

object Goi {
    val colors: GoiColors @Composable get() = LocalColors.current
    val type: GoiType @Composable get() = LocalType.current
}

@Composable
fun GoiTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalColors provides if (dark) Dark else Light, LocalType provides Type, content = content)
}
