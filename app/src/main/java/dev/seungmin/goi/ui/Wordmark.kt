package dev.seungmin.goi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import dev.seungmin.goi.ui.theme.Goi

/** branding/goi-logo.svg 의 경로. 바꿀 때는 branding/build_logo.py 로 SVG를 다시 만든 뒤 두 값을 옮겨 온다. */
private const val INK = "M70.5 61.78L248.5 61.78C289.04 61.78 319 94.23 319 138.13C319 182.03 289.04 214.48 248.5 214.48L236.5 214.48C195.96 214.48 166 182.03 166 138.13C166 121.18 170.47 105.94 178.28 93.61L150 93.61L150 146.65L114 182.65L150 218.65L150 235.9C150 263.05 125.82 283.12 93.1 283.12L70.9 283.12C39.81 283.12 16.43 265 14.18 239.9L50.64 239.9C52.88 246.65 60.78 251.29 70.9 251.29L93.1 251.29C105.12 251.29 114 244.75 114 235.9L114 182.65L82.17 214.48L70.5 214.48C29.96 214.48 0 182.03 0 138.13C0 94.23 29.96 61.78 70.5 61.78ZM70.75 93.61C51.17 93.61 36.7 112.53 36.7 138.13C36.7 163.73 51.17 182.65 70.75 182.65L114 182.65L114 93.61ZM236.8 93.61C217.19 93.61 202.7 112.53 202.7 138.13C202.7 163.73 217.19 182.65 236.8 182.65L248.2 182.65C267.81 182.65 282.3 163.73 282.3 138.13C282.3 112.53 267.81 93.61 248.2 93.61ZM345 61.78L381 61.78L381 214.48L345 214.48Z"
private const val DOT = "M363 0C374.73 0 384.24 9.51 384.24 21.24C384.24 32.97 374.73 42.48 363 42.48C351.27 42.48 341.76 32.97 341.76 21.24C341.76 9.51 351.27 0 363 0Z"
private const val VIEW_W = 384.24f
private const val VIEW_H = 283.12f

private fun wordmark(ink: Color, accent: Color): ImageVector = ImageVector.Builder(
    name = "GoiWordmark",
    defaultWidth = VIEW_W.dp,
    defaultHeight = VIEW_H.dp,
    viewportWidth = VIEW_W,
    viewportHeight = VIEW_H,
).apply {
    addPath(addPathNodes(INK), fill = SolidColor(ink))
    addPath(addPathNodes(DOT), fill = SolidColor(accent))
}.build()

/** 고이 워드마크. 글자는 본문 색, i 점만 강조색이다. 높이를 정하면 너비는 비율대로 따라온다. */
@Composable
fun GoiWordmark(modifier: Modifier = Modifier) {
    val c = Goi.colors
    val image = remember(c.textPrimary, c.accent) { wordmark(c.textPrimary, c.accent) }
    Image(image, contentDescription = "고이", modifier = modifier.aspectRatio(VIEW_W / VIEW_H))
}
