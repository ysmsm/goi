package dev.seungmin.goi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import dev.seungmin.goi.care.CareMode
import dev.seungmin.goi.ui.theme.Dimens
import dev.seungmin.goi.ui.theme.Goi
import kotlinx.coroutines.delay

val CareMode.icon: ImageVector
    get() = when (this) {
        CareMode.FRAGILE -> Icons.Outlined.Cake
        CareMode.FOOD -> Drumstick
        CareMode.DRINK -> Icons.Outlined.LocalCafe
        CareMode.PET -> Icons.Outlined.Pets
        CareMode.BABY -> Icons.Outlined.ChildCare
    }

/**
 * Pleos Button interaction: 누르는 동안 95%로 줄어들고 배경이 바뀐다. 리플·애니메이션 효과는 쓰지 않는다.
 */
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(Dimens.radius),
    color: Color,
    pressedColor: Color = color,
    enabled: Boolean = true,
    role: Role = Role.Button,
    content: @Composable (pressed: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier = modifier
            .scale(if (pressed) 0.95f else 1f)
            .clip(shape)
            .background(if (pressed) pressedColor else color)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content(pressed) }
}

enum class ButtonKind { Basic, Filled, Accent }

@Composable
fun GoiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Basic,
    icon: ImageVector? = null,
    height: Dp = Dimens.buttonL,
    textStyle: TextStyle = Goi.type.titleS,
) {
    val c = Goi.colors
    val (bg, fg) = when (kind) {
        ButtonKind.Basic -> c.buttonBasic to c.textPrimary
        ButtonKind.Filled -> c.buttonFilled to c.onButtonFilled
        ButtonKind.Accent -> c.accent to c.onAccent
    }
    Pressable(
        onClick = onClick,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(height / 2),
        color = bg,
        pressedColor = if (kind == ButtonKind.Basic) c.surfaceHigh else bg.copy(alpha = 0.85f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dimens.pad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                GoiIcon(icon, fg, Dimens.icon)
                Spacer(Modifier.width(12.dp))
            }
            BasicText(text, style = textStyle.copy(color = fg), maxLines = 1)
        }
    }
}

@Composable
fun GoiIcon(icon: ImageVector, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Image(icon, contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

@Composable
fun GoiText(text: String, style: TextStyle, color: Color = Goi.colors.textPrimary, modifier: Modifier = Modifier, center: Boolean = false, maxLines: Int = 2) {
    BasicText(
        text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = if (center) TextAlign.Center else TextAlign.Start),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 모드 표식. 켜진 모드는 강조색으로 채운 원, 꺼진 모드는 회색 원. */
@Composable
fun ModeBadge(mode: CareMode, active: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val c = Goi.colors
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(if (active) c.accent else c.surfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        GoiIcon(mode.icon, if (active) c.onAccent else c.textSecondary, size * 0.5f)
    }
}

/** 모드 켜기·끄기 타일. 카드 전체가 하나의 Toggle button이다. 이름 아래에 어떤 짐·동승자일 때 쓰는지 한 줄을 둔다. */
@Composable
fun ModeTile(mode: CareMode, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = Goi.colors
    Pressable(
        onClick = onClick,
        modifier = modifier,
        color = if (on) c.accent else c.surface,
        pressedColor = if (on) c.accent.copy(alpha = 0.85f) else c.surfaceHigh,
        role = Role.Switch,
    ) {
        val fg = if (on) c.onAccent else c.textPrimary
        val sub = if (on) c.onAccent.copy(alpha = 0.8f) else c.textSecondary
        if (compact) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.gapL), verticalAlignment = Alignment.CenterVertically) {
                GoiIcon(mode.icon, fg, Dimens.icon)
                Spacer(Modifier.width(Dimens.gapL))
                Column(Modifier.weight(1f)) {
                    GoiText(mode.label, Goi.type.titleS, fg, maxLines = 1)
                    GoiText(mode.examples, Goi.type.bodyS, sub, maxLines = 1)
                }
            }
        } else {
            Column(Modifier.padding(Dimens.gap), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                GoiIcon(mode.icon, fg, 72.dp)
                Spacer(Modifier.height(Dimens.gap))
                GoiText(mode.label, Goi.type.titleM, fg, maxLines = 1, center = true)
                Spacer(Modifier.height(4.dp))
                GoiText(mode.examples, Goi.type.bodyM, sub, maxLines = 1, center = true)
            }
        }
    }
}

/** 카드 배경(surface_basic, 둥근 모서리). */
@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 40.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(Dimens.radius)).background(Goi.colors.surface).padding(padding), content = content)
}

/**
 * Pleos 설정 항목: 제목 + 설명 한두 문장 + 컨트롤.
 * 차량 설정 앱처럼, 선택지 이름만으로 무엇을 정하는지 알기 어려운 항목에만 [description]을 붙인다.
 */
@Composable
fun SettingItem(title: String, description: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.gap)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GoiText(title, Goi.type.titleS, maxLines = 1)
            if (description != null) GoiText(description, Goi.type.bodyS, Goi.colors.textSecondary, maxLines = 3)
        }
        content()
    }
}

/** Pleos 스위치 행: 제목 + 설명 + 스위치. 스위치는 무엇이 켜지는지 이름만으로 알기 어려워 설명을 늘 붙인다. */
@Composable
fun ToggleItem(title: String, description: String, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GoiText(title, Goi.type.titleS, maxLines = 1)
            GoiText(description, Goi.type.bodyS, Goi.colors.textSecondary, maxLines = 3)
        }
        Spacer(Modifier.width(Dimens.gapL))
        GoiSwitch(checked, onToggle)
    }
}

/** 상태 표의 한 줄: 왼쪽 항목, 오른쪽 값. */
@Composable
fun InfoRow(label: String, value: String, strong: Boolean = true) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        GoiText(label, Goi.type.bodyL, Goi.colors.textSecondary, maxLines = 1, modifier = Modifier.width(200.dp))
        GoiText(value, if (strong) Goi.type.titleS else Goi.type.bodyL, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(2.dp).background(Goi.colors.divider))
}

/** 진행도 막대. 정적 표시만 하고 애니메이션은 쓰지 않는다. */
@Composable
fun ProgressBar(ratio: Float, modifier: Modifier = Modifier) {
    val c = Goi.colors
    Box(modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceHigh)) {
        Box(Modifier.fillMaxWidth(ratio.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(c.buttonFilled))
    }
}

/** Pleos Tabs(Box): 회색 컨테이너 안에서 선택한 칸만 떠 있다. */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** 켜진 모드처럼 따로 알려야 하는 칸에 강조색 점을 붙인다. */
    marked: (T) -> Boolean = { false },
) {
    val c = Goi.colors
    Row(
        modifier = modifier
            .height(Dimens.buttonL)
            .clip(RoundedCornerShape(Dimens.buttonL / 2))
            .background(c.buttonBasic)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Pressable(
                onClick = { onSelect(option) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (isSelected) Modifier.shadow(6.dp, RoundedCornerShape(Dimens.buttonL / 2)) else Modifier),
                shape = RoundedCornerShape(Dimens.buttonL / 2),
                color = if (isSelected) c.surface else Color.Transparent,
                pressedColor = c.surfaceHigh,
                role = Role.Tab,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (marked(option)) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(c.accent))
                        Spacer(Modifier.width(10.dp))
                    }
                    GoiText(label(option), Goi.type.bodyL.copy(fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.Bold else null), if (isSelected) c.textPrimary else c.textSecondary, maxLines = 1)
                }
            }
        }
    }
}

/** Pleos Switch. 설정값의 켜짐은 회색(button_filled)으로, 강조색은 모드에만 쓴다. */
@Composable
fun GoiSwitch(checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = Goi.colors
    Pressable(
        onClick = onToggle,
        modifier = modifier.width(112.dp).height(64.dp),
        shape = RoundedCornerShape(32.dp),
        color = if (checked) c.buttonFilled else c.surfaceHigh,
        role = Role.Switch,
    ) {
        Box(Modifier.fillMaxWidth().padding(6.dp), contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(if (checked) c.onButtonFilled else c.surface).shadow(2.dp, CircleShape))
        }
    }
}

@Composable
fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    val c = Goi.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.gapL)) {
        Pressable(onMinus, Modifier.size(Dimens.buttonL), CircleShape, c.buttonBasic, c.surfaceHigh) {
            GoiIcon(Icons.Outlined.Remove, c.textPrimary, Dimens.icon)
        }
        GoiText(value, Goi.type.headlineS, modifier = Modifier.widthIn(min = 120.dp), center = true, maxLines = 1)
        Pressable(onPlus, Modifier.size(Dimens.buttonL), CircleShape, c.buttonBasic, c.surfaceHigh) {
            GoiIcon(Icons.Outlined.Add, c.textPrimary, Dimens.icon)
        }
    }
}

/** 화면 아래 한 줄 Toast. 3초 뒤 사라진다. 최대 너비 792. */
@Composable
fun ToastHost(text: String?, id: Long?, onDone: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (text == null || id == null) return
    LaunchedEffect(id) {
        delay(3_000)
        onDone(id)
    }
    val c = Goi.colors
    Box(
        modifier
            .padding(bottom = Dimens.gapL, start = Dimens.gapL, end = Dimens.gapL)
            .widthIn(max = 792.dp)
            .clip(RoundedCornerShape(50))
            .background(c.buttonFilled)
            .padding(horizontal = 40.dp, vertical = 24.dp),
    ) {
        GoiText(text, Goi.type.bodyL, c.onButtonFilled, maxLines = 1)
    }
}
