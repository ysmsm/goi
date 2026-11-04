package dev.seungmin.goi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.seungmin.goi.care.CareEngine
import dev.seungmin.goi.care.CareMode
import dev.seungmin.goi.care.CareUiState
import dev.seungmin.goi.care.ForgetAlert
import dev.seungmin.goi.care.HvacItem
import dev.seungmin.goi.care.Suggestion
import dev.seungmin.goi.data.EventType
import dev.seungmin.goi.data.TripEvent
import dev.seungmin.goi.data.TripRecord
import dev.seungmin.goi.ui.theme.Dimens
import dev.seungmin.goi.ui.theme.Goi
import dev.seungmin.goi.ui.theme.GoiTheme

/**
 * 화면 선택 규칙(주행 상태 × 창 크기):
 * - 주행 중: 창 크기와 상관없이 켜진 모드 + 상태 한 줄 + 큰 버튼 하나(G1·G5·F5).
 * - 정차 중 + Grid(세로 창): 모드가 꺼져 있으면 모드 고르기(G3), 켜져 있으면 지금 하는 일(G4).
 * - 정차 중 + Full(가로 창): 홈·모드 설정·운반 기록·픽업 장소·챌린지(F1~F7).
 * - 제안·요약·수면 중 이벤트·하차 알림은 그 위에 한 장씩 덮는다.
 */
@Composable
fun GoiRoot(state: CareUiState, engine: CareEngine) {
    GoiTheme(dark = isSystemInDarkTheme() || state.sleeping) {
        val c = Goi.colors
        BoxWithConstraints(Modifier.fillMaxSize().background(c.background)) {
            val full = maxWidth > maxHeight
            // 수면 모드: 화면 밝기·대비를 낮춘다
            Box(Modifier.fillMaxSize().alpha(if (state.sleeping) 0.7f else 1f)) {
                when {
                    state.suggestion != null -> SuggestionScreen(state.suggestion, state.listening, engine)
                    state.driving -> DrivingScreen(state, full, engine)
                    state.sleepEvents.isNotEmpty() -> SleepEventsScreen(state.sleepEvents, engine)
                    state.summary != null -> SummaryScreen(state.summary, state.trips, full, engine)
                    full -> FullScreen(state, engine)
                    else -> GridScreen(state, engine)
                }
            }
            state.forget?.let { ForgetPopup(it, engine) }
            ToastHost(state.toast?.text, state.toast?.id, engine::clearToast, Modifier.align(Alignment.BottomCenter))
        }
    }
}

// region 주행 중 (G1 · G5 · F5)

@Composable
private fun DrivingScreen(state: CareUiState, full: Boolean, engine: CareEngine) {
    val modes = state.activeModes
    val c = Goi.colors
    val m = state.merged
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        when {
            state.sleeping -> BigBadge(Icons.Outlined.Bedtime, active = true, size = 240)
            modes.isEmpty() -> BigBadge(Icons.Outlined.Tune, active = false, size = 200)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gapL)) {
                val size = when (modes.size) {
                    1 -> 240
                    2 -> 180
                    else -> 128
                }
                modes.forEach { ModeBadge(it, active = true, size = size.dp) }
            }
        }
        Spacer(Modifier.height(48.dp))
        val title = when {
            state.sleeping -> "아기가 자고 있어요"
            modes.isEmpty() -> "켜진 모드가 없어요"
            else -> drivingTitle(modes)
        }
        GoiText(title, Goi.type.headlineS, if (modes.isEmpty()) c.textSecondary else c.textPrimary, center = true)
        Spacer(Modifier.height(Dimens.gap))
        val sub = when {
            state.listening -> "‘치킨 추가’, ‘아기 자요’처럼 말해 보세요"
            state.sleeping -> "효과음 끔 · 안내 음성 짧게"
            modes.isEmpty() -> "‘케이크 옮겨줘’라고 말해 보세요"
            else -> m?.drivingLine().orEmpty()
        }
        GoiText(sub, Goi.type.bodyL, c.textSecondary, center = true, maxLines = 1)
        if (state.sleeping && m != null) {
            // G5: 공조 값은 앱이 정하지 않고 보호자 설정값만 보여 준다
            val baby = state.settingsOf(CareMode.BABY)
            val parts = buildList {
                if (m.rearTempC != null) add("뒷좌석 ${formatTemp(m.rearTempC)}°C")
                if (HvacItem.NO_DIRECT_WIND in m.hvacItems && HvacItem.NO_DIRECT_WIND in baby.hvac) add("직접 바람 피함")
            }
            if (parts.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.gap))
                GoiText(parts.joinToString(" · "), Goi.type.bodyL, c.textSecondary, center = true, maxLines = 1)
            }
        }
        if (full) {
            Spacer(Modifier.height(Dimens.pad))
            GoiText("주행 중에는 간단히 보여 드려요", Goi.type.bodyM, c.textTertiary, center = true, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        if (state.sleeping) {
            GoiButton("수면 모드 끄기", { engine.setSleeping(false) }, Modifier.fillMaxWidth().widthIn(max = 720.dp), ButtonKind.Basic, Icons.Outlined.Bedtime, Dimens.buttonXL, Goi.type.titleL)
        } else {
            TalkButton(state.listening, engine::talk, Modifier.fillMaxWidth().widthIn(max = 720.dp))
        }
    }
}

@Composable
private fun BigBadge(icon: ImageVector, active: Boolean, size: Int) {
    val c = Goi.colors
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (active) c.accent else c.surfaceHigh),
        contentAlignment = Alignment.Center,
    ) { GoiIcon(icon, if (active) c.onAccent else c.textTertiary, (size / 2).dp) }
}

@Composable
private fun TalkButton(listening: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GoiButton(
        text = if (listening) "듣고 있어요" else "말하기",
        onClick = onClick,
        modifier = modifier,
        kind = if (listening) ButtonKind.Accent else ButtonKind.Filled,
        icon = Icons.Outlined.Mic,
        height = Dimens.buttonXL,
        textStyle = Goi.type.titleL,
    )
}

/** 작은 말하기 버튼(정차 화면 머리). */
@Composable
fun TalkChip(listening: Boolean, onClick: () -> Unit) {
    GoiButton(
        if (listening) "듣고 있어요" else "말하기", onClick, Modifier,
        if (listening) ButtonKind.Accent else ButtonKind.Basic, Icons.Outlined.Mic, Dimens.buttonL, Goi.type.titleS,
    )
}

// endregion

// region 정차 중: Grid (G3 · G4)

@Composable
private fun GridScreen(state: CareUiState, engine: CareEngine) {
    var picking by rememberSaveable { mutableStateOf(false) }
    if (state.active.isEmpty() || picking) {
        ModePicker(state, engine, onDone = { picking = false }, onTurnedOn = { picking = false })
    } else {
        ActiveStatus(state, engine, onChange = { picking = true })
    }
}

/** G3: 모드 고르기. 카드 전체가 켜기·끄기. 켜진 모드가 있을 때 다른 카드를 켜면 "모드 추가". */
@Composable
private fun ModePicker(state: CareUiState, engine: CareEngine, onDone: () -> Unit, onTurnedOn: () -> Unit) {
    val tiles = CareMode.displayOrder
    Column(Modifier.fillMaxSize().padding(Dimens.pad), verticalArrangement = Arrangement.spacedBy(Dimens.gapL)) {
        Row(Modifier.fillMaxWidth().height(Dimens.buttonL), verticalAlignment = Alignment.CenterVertically) {
            GoiText("모드 선택", Goi.type.titleL, maxLines = 1, modifier = Modifier.weight(1f))
            if (state.active.isNotEmpty()) GoiButton("완료", onDone, kind = ButtonKind.Basic)
        }
        tiles.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Dimens.gapL)) {
                pair.forEach { mode ->
                    val on = mode in state.active
                    ModeTile(mode, on, {
                        engine.setMode(mode, !on)
                        if (!on) onTurnedOn()
                    }, Modifier.weight(1f).fillMaxHeight())
                }
                if (row == tiles.lastIndex / 2 && pair.size == 1) {
                    TalkTile(state.listening, engine::talk, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun TalkTile(listening: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Goi.colors
    Pressable(onClick, modifier, color = if (listening) c.accent else c.buttonFilled, pressedColor = c.buttonFilled.copy(alpha = 0.85f)) {
        val fg = if (listening) c.onAccent else c.onButtonFilled
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GoiIcon(Icons.Outlined.Mic, fg, 72.dp)
            Spacer(Modifier.height(Dimens.gap))
            GoiText(if (listening) "듣고 있어요" else "말하기", Goi.type.titleM, fg, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            GoiText("‘케이크 옮겨줘’", Goi.type.bodyM, fg.copy(alpha = 0.7f), maxLines = 1)
        }
    }
}

/** G4: 켜진 모드와 실제로 하는 일. 겹침 규칙(가장 민감한 기준, 우선순위 공조)을 그대로 보여 준다. */
@Composable
private fun ActiveStatus(state: CareUiState, engine: CareEngine, onChange: () -> Unit) {
    val modes = state.activeModes
    val c = Goi.colors
    Column(Modifier.fillMaxSize().padding(Dimens.pad), verticalArrangement = Arrangement.spacedBy(Dimens.gapL)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GoiText("활성화된 모드", Goi.type.bodyL, c.textSecondary, maxLines = 1, modifier = Modifier.weight(1f))
            TalkChip(state.listening, engine::talk)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gap), verticalAlignment = Alignment.CenterVertically) {
            modes.forEach { ModeBadge(it, active = true, size = if (modes.size == 1) 112.dp else 88.dp) }
        }
        GoiText(modes.joinToString("\n") { it.label }, if (modes.size > 2) Goi.type.titleM else Goi.type.titleL, maxLines = 3)
        Card(Modifier.fillMaxWidth().weight(1f), padding = Dimens.pad) {
            state.statusRows().forEach { (k, v) -> InfoRow(k, v) }
        }
        GoiButton("모드 추가·변경", onChange, Modifier.fillMaxWidth(), ButtonKind.Basic)
        val last = state.lastAdded?.takeIf { modes.size > 1 }
        if (last != null) {
            GoiButton("${last.label} 끄기", { engine.setMode(last, false) }, Modifier.fillMaxWidth(), ButtonKind.Filled)
        } else {
            GoiButton("모드 끄기", engine::allOff, Modifier.fillMaxWidth(), ButtonKind.Filled)
        }
    }
}

// endregion

// region 덮는 화면

/** G2: 픽업 장소 진입 → 모드 제안. 음성 응답 우선, 터치는 두 버튼. */
@Composable
private fun SuggestionScreen(s: Suggestion, listening: Boolean, engine: CareEngine) {
    val c = Goi.colors
    Column(Modifier.fillMaxSize().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        s.placeName?.let { GoiText("$it 도착", Goi.type.titleS, c.textSecondary, center = true, maxLines = 1) }
        Spacer(Modifier.height(Dimens.pad))
        ModeBadge(s.mode, active = true, size = 200.dp)
        Spacer(Modifier.height(40.dp))
        GoiText("${s.mode.label} 모드를\n켤까요?", Goi.type.headlineS, center = true)
        Spacer(Modifier.height(Dimens.gap))
        GoiText(s.mode.examples, Goi.type.bodyL, c.textSecondary, center = true, maxLines = 1)
        Spacer(Modifier.weight(1f))
        GoiText("“응” 또는 “아니”라고 말해도 돼요", Goi.type.bodyL, c.textSecondary, center = true, maxLines = 1)
        Spacer(Modifier.height(Dimens.gapL))
        Row(Modifier.fillMaxWidth().widthIn(max = 960.dp), horizontalArrangement = Arrangement.spacedBy(Dimens.gapL)) {
            GoiButton("괜찮아요", engine::dismissSuggestion, Modifier.weight(1f), ButtonKind.Basic, height = Dimens.buttonXL, textStyle = Goi.type.titleL)
            GoiButton("켜기", engine::acceptSuggestion, Modifier.weight(1f), ButtonKind.Accent, Icons.Outlined.Check, Dimens.buttonXL, Goi.type.titleL)
        }
        Spacer(Modifier.height(Dimens.gapL))
        // 자리를 늘 차지해 듣기 시작해도 버튼이 움직이지 않게 한다
        Row(Modifier.alpha(if (listening) 1f else 0f), verticalAlignment = Alignment.CenterVertically) {
            GoiIcon(Icons.Outlined.Mic, c.accent, 32.dp)
            Spacer(Modifier.width(8.dp))
            GoiText("듣고 있어요", Goi.type.bodyM, c.textTertiary, maxLines = 1)
        }
    }
}

/** F3: 운반 요약(도착, 기어 P). 점수가 아니라 무사히 옮겼는지를 말한다. */
@Composable
private fun SummaryScreen(trip: TripRecord, history: List<TripRecord>, full: Boolean, engine: CareEngine) {
    val c = Goi.colors
    Column(Modifier.fillMaxSize().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        BigBadge(Icons.Outlined.Check, active = trip.safe, size = if (full) 160 else 180)
        Spacer(Modifier.height(Dimens.pad))
        GoiText(trip.title(history), if (full) Goi.type.headlineL else Goi.type.headlineS, center = true)
        Spacer(Modifier.height(Dimens.gap))
        GoiText(trip.detail(), Goi.type.bodyL, c.textSecondary, center = true, maxLines = 1)
        Spacer(Modifier.height(40.dp))
        val stats = listOf(EventType.HARD_BRAKE, EventType.SHARP_TURN, EventType.CURVE_NOTICE, EventType.HARD_START)
        val cells: @Composable (EventType, Modifier) -> Unit = { type, m ->
            val n = trip.count(type)
            Column(m.clip(RoundedCornerShape(Dimens.radius)).background(c.surface).padding(vertical = Dimens.gapL), horizontalAlignment = Alignment.CenterHorizontally) {
                GoiText(type.label, Goi.type.bodyL, c.textSecondary, maxLines = 1)
                GoiText("${n}회", Goi.type.headlineS, if (n > 0) c.textPrimary else c.textTertiary, maxLines = 1)
            }
        }
        if (full) {
            Row(Modifier.fillMaxWidth().widthIn(max = 1200.dp), horizontalArrangement = Arrangement.spacedBy(Dimens.gapL)) {
                stats.forEach { cells(it, Modifier.weight(1f)) }
            }
            if (trip.events.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.gapL))
                // 구간별 기록: 일어난 순서대로
                GoiText(trip.events.take(8).joinToString("  →  ") { it.type.label }, Goi.type.bodyL, c.textSecondary, center = true, maxLines = 1)
            }
        } else {
            stats.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) { pair.forEach { cells(it, Modifier.weight(1f)) } }
                Spacer(Modifier.height(Dimens.gap))
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().widthIn(max = 960.dp), horizontalArrangement = Arrangement.spacedBy(Dimens.gapL)) {
            GoiButton("계속 켜 두기", engine::continueCarrying, Modifier.weight(1f), ButtonKind.Basic, height = Dimens.buttonXL, textStyle = Goi.type.titleL)
            GoiButton("모드 끄기", engine::finishCarrying, Modifier.weight(1f), ButtonKind.Filled, height = Dimens.buttonXL, textStyle = Goi.type.titleL)
        }
    }
}

/** G6: 수면 모드에선 효과음 대신, 신호 대기처럼 멈췄을 때 쌓인 일을 보여 준다. 출발하면 자동으로 닫힌다. */
@Composable
private fun SleepEventsScreen(events: List<TripEvent>, engine: CareEngine) {
    val c = Goi.colors
    // 몇 분 전인지 보여 주므로 화면이 떠 있는 동안 1분마다 다시 그린다
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    Column(Modifier.fillMaxSize().padding(48.dp)) {
        Spacer(Modifier.weight(1f))
        GoiText("수면 중 알림", Goi.type.titleL, c.textSecondary)
        Spacer(Modifier.height(Dimens.pad))
        events.groupBy { it.type }.forEach { (type, list) ->
            GoiText("${type.label} ${list.size}회", Goi.type.headlineS, maxLines = 1)
            GoiText(agoText(list.last().atMillis, now), Goi.type.bodyL, c.textSecondary, maxLines = 1)
            Spacer(Modifier.height(Dimens.pad))
        }
        Spacer(Modifier.weight(1f))
        GoiButton("확인", engine::confirmSleepEvents, Modifier.fillMaxWidth().widthIn(max = 720.dp), ButtonKind.Basic, Icons.Outlined.Check, Dimens.buttonXL, Goi.type.titleL)
    }
}

/** A1: 하차 알림(System popup). 아기·반려동물은 확인 버튼을 눌러야 닫히고, 짐은 바깥을 눌러도 닫힌다. */
@Composable
private fun ForgetPopup(alert: ForgetAlert, engine: CareEngine) {
    val c = Goi.colors
    Box(
        Modifier.fillMaxSize().background(c.scrim)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = !alert.mustConfirm, onClick = engine::confirmForget)
            .padding(Dimens.pad),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 800.dp).fillMaxWidth().clip(RoundedCornerShape(40.dp)).background(c.surface)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
                alert.modes.forEachIndexed { i, mode -> ModeBadge(mode, active = true, size = if (i == 0) 144.dp else 88.dp) }
            }
            Spacer(Modifier.height(40.dp))
            GoiText(alert.headline, Goi.type.headlineS, center = true)
            alert.others.forEach {
                Spacer(Modifier.height(Dimens.gap))
                GoiText(it, Goi.type.titleS, center = true, maxLines = 1)
            }
            Spacer(Modifier.height(Dimens.gapL))
            GoiText("내리기 전에 한 번 더 확인해 주세요", Goi.type.bodyL, c.textSecondary, center = true, maxLines = 1)
            Spacer(Modifier.height(48.dp))
            GoiButton("확인했어요", engine::confirmForget, Modifier.fillMaxWidth(), ButtonKind.Accent, Icons.Outlined.Check, Dimens.buttonXL, Goi.type.titleL)
        }
    }
}

// endregion
