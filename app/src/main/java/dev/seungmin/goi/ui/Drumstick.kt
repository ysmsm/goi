package dev.seungmin.goi.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 포장 음식 모드 아이콘(닭다리). Material Icons에 없어서 Outlined 아이콘과 같은 24 그리드·2 굵기 선으로 그렸다.
 * 가로로 눕힌 닭다리(왼쪽 뼈, 오른쪽 살, 둘 사이는 살 테두리 선으로 구분)를 -45° 돌려 살이 오른쪽 위를 향하게 한다.
 */
val Drumstick: ImageVector by lazy {
    ImageVector.Builder(
        name = "Outlined.Drumstick",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        group(rotate = -45f, pivotX = 12f, pivotY = 12f) {
            val line = SolidColor(Color.Black)
            // 뼈: 윗선 → 끝마디 두 개 → 아랫선. 살 쪽 끝은 살 테두리에서 멈춘다.
            path(stroke = line, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(11.06f, 10.25f)
                lineTo(5.23f, 10.25f)
                arcTo(1.75f, 1.75f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 2.6f, y1 = 12f)
                arcTo(1.75f, 1.75f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 5.23f, y1 = 13.75f)
                lineTo(11.06f, 13.75f)
            }
            // 살: 닫힌 도형. 뼈 쪽 끝을 곡선으로 닫아 살과 뼈 사이에 선이 생긴다.
            path(stroke = line, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(11.5f, 9.3f)
                curveTo(13f, 8f, 14.5f, 6.7f, 17.5f, 6.7f)
                curveTo(20.5f, 6.7f, 22.5f, 9f, 22.5f, 12f)
                curveTo(22.5f, 15f, 20.5f, 17.3f, 17.5f, 17.3f)
                curveTo(14.5f, 17.3f, 13f, 16f, 11.5f, 14.7f)
                quadTo(10f, 12f, 11.5f, 9.3f)
                close()
            }
        }
    }.build()
}
