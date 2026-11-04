package dev.seungmin.goi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import dev.seungmin.goi.care.CareEngine
import dev.seungmin.goi.care.CareMode
import dev.seungmin.goi.care.CareUiState
import dev.seungmin.goi.care.Challenge
import dev.seungmin.goi.care.ChallengeProgress
import dev.seungmin.goi.care.HvacItem
import dev.seungmin.goi.care.ModeSettings
import dev.seungmin.goi.care.Sensitivity
import dev.seungmin.goi.care.VoiceExample
import dev.seungmin.goi.data.EventType
import dev.seungmin.goi.data.Place
import dev.seungmin.goi.data.TripRecord
import dev.seungmin.goi.ui.theme.Dimens
import dev.seungmin.goi.ui.theme.Goi

/** 와이어프레임 F1~F7: 좌측 레일 5개 메뉴. 정차 중에만 보인다. [description]은 아래 내용이 말해 주지 않는 것만 둔다. */
private enum class Page(val label: String, val icon: ImageVector, val description: String) {
    HOME("홈", Icons.Outlined.Home, ""),
    MODES("모드 설정", Icons.Outlined.Tune, ""),
    HISTORY("운반 기록", Icons.Outlined.History, "기록은 차 안에만 저장하고 외부로 보내지 않습니다."),
    PLACES("픽업 장소", Icons.Outlined.Place, "스위치를 켠 장소에 도착하면 모드를 켤지 묻습니다. 위치 정보는 차 밖으로 보내지 않습니다."),
    CHALLENGE("챌린지", Icons.Outlined.EmojiEvents, ""),
}

@Composable
fun FullScreen(state: CareUiState, engine: CareEngine) {
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var focused by rememberSaveable { mutableStateOf(state.activeModes.firstOrNull() ?: CareMode.FRAGILE) }
    val c = Goi.colors
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(216.dp).fillMaxHeight().padding(vertical = Dimens.pad, horizontal = Dimens.gap),
            verticalArrangement = Arrangement.spacedBy(Dimens.gap),
        ) {
            Page.entries.forEach { p ->
                val selected = p == page
                Pressable({ page = p }, Modifier.fillMaxWidth().height(112.dp), color = if (selected) c.surfaceHigh else c.background, pressedColor = c.surfaceHigh) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GoiIcon(p.icon, if (selected) c.textPrimary else c.textSecondary, Dimens.icon)
                        Spacer(Modifier.height(6.dp))
                        GoiText(p.label, Goi.type.labelM, if (selected) c.textPrimary else c.textSecondary, maxLines = 1)
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = Dimens.pad, end = Dimens.pad, bottom = Dimens.pad)) {
            // 머리: 제목 + 필요할 때만 한 줄 설명(Pleos 차량 설정과 같은 구조) + 말하기
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GoiText(if (page == Page.HOME) "고이" else page.label, Goi.type.titleL, maxLines = 1)
                    if (page.description.isNotEmpty()) GoiText(page.description, Goi.type.bodyS, c.textSecondary, maxLines = 2)
                }
                Spacer(Modifier.width(Dimens.gapL))
                TalkChip(state.listening, engine::talk)
            }
            Spacer(Modifier.height(Dimens.gapL))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (page) {
                    Page.HOME -> HomePage(state, engine, onOpenChallenge = { page = Page.CHALLENGE })
                    Page.MODES -> ModeSettingsPage(state, focused, { focused = it }, engine)
                    Page.HISTORY -> HistoryPage(state.trips)
                    Page.PLACES -> PlacesPage(state.places, engine)
                    Page.CHALLENGE -> ChallengePage(state.trips)
                }
            }
        }
    }
}

// region F1 홈

@Composable
private fun HomePage(state: CareUiState, engine: CareEngine, onOpenChallenge: () -> Unit) {
    val c = Goi.colors
    val modes = state.activeModes
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Dimens.gapL)) {
        Card(Modifier.weight(1.1f).fillMaxHeight()) {
            GoiText("활성화된 모드", Goi.type.bodyM, c.textSecondary, maxLines = 1)
            Spacer(Modifier.height(Dimens.gapL))
            if (modes.isEmpty()) {
                Spacer(Modifier.weight(1f))
                GoiText("켜진 모드가 없어요", Goi.type.titleL, c.textSecondary)
                Spacer(Modifier.height(Dimens.gap))
                val example = remember { VoiceExample.pick(emptySet()) }
                GoiText(
                    "‘$example’라고 말해 보세요",
                    Goi.type.bodyM, c.textSecondary, maxLines = 3,
                )
                Spacer(Modifier.weight(1f))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    modes.forEach {
                        ModeBadge(it, active = true, size = if (modes.size == 1) 120.dp else 88.dp)
                        Spacer(Modifier.width(Dimens.gap))
                    }
                    Spacer(Modifier.width(Dimens.gap))
                    Column(Modifier.weight(1f)) {
                        if (modes.size == 1) {
                            GoiText(modes.first().label, Goi.type.titleL, maxLines = 1)
                            GoiText(modes.first().examples, Goi.type.bodyM, c.textSecondary, maxLines = 1)
                        } else {
                            // 여러 모드는 한 줄에 하나씩(이름 중간에서 줄이 바뀌지 않게)
                            modes.forEach { GoiText(it.label, Goi.type.titleM, maxLines = 1) }
                        }
                    }
                }
                Spacer(Modifier.height(Dimens.gapL))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    state.statusRows().forEach { (k, v) -> InfoRow(k, v) }
                }
                Spacer(Modifier.height(Dimens.gap))
                GoiButton("모드 끄기", engine::allOff, Modifier.fillMaxWidth(), ButtonKind.Filled)
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Dimens.gap)) {
            GoiText("빠르게 켜기", Goi.type.bodyM, c.textSecondary, maxLines = 1)
            CareMode.displayOrder.chunked(2).forEach { pair ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
                    pair.forEach { m ->
                        val on = m in state.active
                        ModeTile(m, on, { engine.setMode(m, !on) }, Modifier.weight(1f).fillMaxHeight(), compact = true)
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Challenge.featured(state.trips, state.active)?.let { p ->
                Pressable(onOpenChallenge, Modifier.fillMaxWidth().height(136.dp), color = c.surface, pressedColor = c.surfaceHigh) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.gapL), verticalAlignment = Alignment.CenterVertically) {
                        GoiIcon(Icons.Outlined.EmojiEvents, c.textPrimary, Dimens.icon)
                        Spacer(Modifier.width(Dimens.gapL))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            GoiText(p.challenge.title, Goi.type.bodyL, maxLines = 1)
                            ProgressBar(p.ratio)
                        }
                        Spacer(Modifier.width(Dimens.gapL))
                        GoiText(p.text, Goi.type.titleM, maxLines = 1)
                    }
                }
            }
        }
    }
}

// endregion

// region F2 · F7 모드 설정

@Composable
private fun ModeSettingsPage(state: CareUiState, mode: CareMode, onFocus: (CareMode) -> Unit, engine: CareEngine) {
    val s = state.settingsOf(mode)
    val on = mode in state.active
    fun update(t: (ModeSettings) -> ModeSettings) = engine.updateSettings(mode, t)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Dimens.gapL)) {
        // Tabs: 5개 모드. 모드 = 같은 엔진의 값 세트라 모든 탭이 같은 배치다. 켜진 모드는 점으로 표시한다
        Segmented(CareMode.displayOrder, mode, { it.label }, onFocus, Modifier.fillMaxWidth(), marked = { it in state.active })
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Dimens.gapL)) {
            Card(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), padding = Dimens.pad) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.pad)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ModeBadge(mode, on, 72.dp)
                        Spacer(Modifier.width(Dimens.gapL))
                        Column(Modifier.weight(1f)) {
                            GoiText("${mode.label} 모드", Goi.type.titleS, maxLines = 1)
                            GoiText(if (on) "켜짐" else "꺼짐", Goi.type.bodyS, Goi.colors.textSecondary, maxLines = 1)
                        }
                        ModeSwitch(on) { engine.setMode(mode, !on) }
                    }
                    Divider()
                    SettingItem("민감도", "급제동·급출발·급커브를 알리는 기준을 설정합니다.") {
                        Segmented(Sensitivity.entries, s.sensitivity, { it.label }, { v -> update { it.copy(sensitivity = v) } }, Modifier.fillMaxWidth())
                        GoiText(s.sensitivity.description, Goi.type.bodyS, Goi.colors.textSecondary, maxLines = 2)
                    }
                    SettingItem("커브 사전 안내", "길 안내 중 앞에 급커브나 유턴이 있으면 미리 음성으로 알립니다.") {
                        Segmented(ModeSettings.CURVE_LEAD_OPTIONS, s.curveLeadM, { if (it == 0) "끄기" else "${it}m 전" }, { v -> update { it.copy(curveLeadM = v) } }, Modifier.fillMaxWidth())
                    }
                    ToggleItem(
                        "흔들림 알림",
                        "급제동·급출발·급커브를 효과음으로 알립니다. 꺼도 기록은 남습니다.",
                        s.feedbackSound,
                    ) { update { it.copy(feedbackSound = !it.feedbackSound) } }
                    if (mode.usesRest) {
                        SettingItem("휴식 안내", "반려동물이 지치지 않도록 정한 시간마다 쉬어 가기를 음성으로 권합니다.") {
                            Segmented(ModeSettings.REST_OPTIONS, s.restEveryMin, { if (it == 0) "끄기" else "${it / 60}시간마다" }, { v -> update { it.copy(restEveryMin = v) } }, Modifier.fillMaxWidth())
                        }
                    }
                    if (mode == CareMode.BABY) {
                        ToggleItem(
                            "수면 모드",
                            "아기가 자는 동안 효과음을 끄고 안내 음성을 짧게 합니다. ‘아기 자요’라고 말해도 켜집니다.",
                            state.sleeping,
                        ) { engine.setSleeping(!state.sleeping) }
                    }
                }
            }
            Card(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), padding = Dimens.pad) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.pad)) {
                    val hvacDesc = when {
                        mode.hvacItems.isEmpty() -> "${mode.label} 모드는 공조를 바꾸지 않습니다."
                        else -> "모드를 끄면 원래 공조 설정으로 돌아갑니다."
                    }
                    SettingItem("공조", hvacDesc) {}
                    mode.hvacItems.forEach { item ->
                        val checked = item in s.hvac
                        ToggleItem(item.label, item.description, checked) {
                            update { it.copy(hvac = if (checked) it.hvac - item else it.hvac + item) }
                        }
                        if (item == HvacItem.REAR_TEMP && checked) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GoiText("뒷좌석 온도", Goi.type.bodyL, Goi.colors.textSecondary, modifier = Modifier.weight(1f), maxLines = 1)
                                Stepper(
                                    value = "${formatTemp(s.rearTempC)}°",
                                    onMinus = { update { it.copy(rearTempC = (it.rearTempC - 0.5f).coerceAtLeast(ModeSettings.TEMP_MIN)) } },
                                    onPlus = { update { it.copy(rearTempC = (it.rearTempC + 0.5f).coerceAtMost(ModeSettings.TEMP_MAX)) } },
                                )
                            }
                        }
                        if (item == HvacItem.FRESH_CYCLE && checked) {
                            Segmented(ModeSettings.FRESH_OPTIONS, s.freshEveryMin, { "${it}분마다" }, { v -> update { it.copy(freshEveryMin = v) } }, Modifier.fillMaxWidth())
                        }
                    }
                    Divider()
                    ToggleItem(
                        if (mode.mustConfirm) "하차 확인 알림" else "도착 알림",
                        if (mode.trunkAlert != null) {
                            "내릴 때 챙길 짐을 알립니다. 트렁크에 실은 짐도 알립니다."
                        } else {
                            "내릴 때 ‘${mode.alert}’라고 알립니다."
                        },
                        s.arrivalAlert,
                    ) { update { it.copy(arrivalAlert = !it.arrivalAlert) } }
                    if (mode.hvacOnlyWhileDriving) {
                        // 안전 원칙을 설정 화면에 상시 고지한다(와이어프레임 F7)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.radiusS)).background(Goi.colors.buttonBasic).padding(Dimens.gapL)) {
                            GoiIcon(Icons.Outlined.Info, Goi.colors.textSecondary, 36.dp)
                            Spacer(Modifier.width(Dimens.gap))
                            GoiText(
                                "공조 항목은 주행 중에만 동작하고 기어를 P로 두면 멈춥니다. ${if (mode == CareMode.BABY) "아기를" else "반려동물을"} 차 안에 두고 내리지 마세요.",
                                Goi.type.bodyS, Goi.colors.textSecondary, maxLines = 3,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 모드 켜기 스위치는 강조색. 일반 설정 스위치([GoiSwitch])와 구분한다. */
@Composable
private fun ModeSwitch(on: Boolean, onToggle: () -> Unit) {
    val c = Goi.colors
    Pressable(onToggle, Modifier.width(112.dp).height(64.dp), RoundedCornerShape(32.dp), if (on) c.accent else c.surfaceHigh) {
        Box(Modifier.fillMaxWidth().padding(6.dp), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(c.surface))
        }
    }
}

// endregion

// region 운반 기록

@Composable
private fun HistoryPage(trips: List<TripRecord>) {
    val c = Goi.colors
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Dimens.gapL)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.radius)).background(c.surface).padding(horizontal = 40.dp, vertical = Dimens.gapL),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                GoiText("무사 운반", Goi.type.titleL, maxLines = 1)
                GoiText("급제동·급출발·급커브 없이 도착한 횟수", Goi.type.bodyS, c.textSecondary, maxLines = 1)
            }
            GoiText("${trips.count { it.safe }}", Goi.type.display, c.accent, maxLines = 1)
            GoiText(" / ${trips.size}", Goi.type.titleL, c.textTertiary, maxLines = 1)
        }
        if (trips.isEmpty()) {
            GoiText("아직 기록이 없어요", Goi.type.bodyL, c.textTertiary, Modifier.padding(Dimens.pad))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.gap)) {
            items(trips, key = { it.startedAt }) { trip -> TripRow(trip) }
        }
    }
}

@Composable
private fun TripRow(trip: TripRecord) {
    val c = Goi.colors
    Row(
        Modifier.fillMaxWidth().height(136.dp).clip(RoundedCornerShape(Dimens.radius)).background(c.surface).padding(horizontal = Dimens.gapL),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.gapL),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { trip.modes.forEach { GoiIcon(it.icon, c.textSecondary, Dimens.icon) } }
        Column(Modifier.weight(1f)) {
            GoiText("${dateTimeText(trip.startedAt)} · ${trip.modes.joinToString(" · ") { it.label }}", Goi.type.titleS, maxLines = 1)
            GoiText(trip.detail(), Goi.type.bodyM, c.textSecondary, maxLines = 1)
        }
        if (trip.safe) {
            GoiIcon(Icons.Outlined.Check, c.accent, Dimens.icon)
            GoiText("무사", Goi.type.titleS, c.accent, maxLines = 1)
        } else {
            val text = listOf(EventType.HARD_BRAKE, EventType.HARD_START, EventType.SHARP_TURN)
                .filter { trip.count(it) > 0 }.joinToString(" · ") { "${it.label} ${trip.count(it)}" }
            GoiText(text, Goi.type.bodyL, c.textSecondary, maxLines = 1)
        }
    }
}

// endregion

// region F4 픽업 장소

@Composable
private fun PlacesPage(places: List<Place>, engine: CareEngine) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    val c = Goi.colors
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Dimens.gapL)) {
        if (choosing) {
            // 지금 위치를 저장: 도착하면 켤 모드만 고른다(검색·지도·이름 입력 없음)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.radius)).background(c.surface).padding(Dimens.gapL), verticalArrangement = Arrangement.spacedBy(Dimens.gap)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GoiText("도착하면 켤 모드를 골라 주세요", Goi.type.titleS, modifier = Modifier.weight(1f), maxLines = 1)
                    Pressable({ choosing = false }, Modifier.size(Dimens.buttonL), CircleShape, c.buttonBasic, c.surfaceHigh) {
                        GoiIcon(Icons.Outlined.Close, c.textPrimary, Dimens.icon)
                    }
                }
                Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
                    CareMode.displayOrder.forEach { mode ->
                        Pressable({ engine.saveHere(mode); choosing = false }, Modifier.weight(1f).fillMaxHeight(), color = c.buttonBasic, pressedColor = c.surfaceHigh) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                GoiIcon(mode.icon, c.textPrimary, Dimens.icon)
                                Spacer(Modifier.height(8.dp))
                                GoiText(mode.label, Goi.type.bodyS, maxLines = 1)
                            }
                        }
                    }
                }
            }
        } else {
            GoiButton("지금 위치를 픽업 장소로 저장", { choosing = true }, Modifier.fillMaxWidth(), ButtonKind.Filled, Icons.Outlined.Add)
        }
        if (places.isEmpty()) GoiText("등록한 장소가 없어요", Goi.type.bodyL, c.textTertiary, Modifier.padding(Dimens.pad))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.gap)) {
            items(places, key = { it.id }) { place -> PlaceRow(place, engine) }
        }
    }
}

@Composable
private fun PlaceRow(place: Place, engine: CareEngine) {
    val c = Goi.colors
    Row(
        Modifier.fillMaxWidth().height(136.dp).clip(RoundedCornerShape(Dimens.radius)).background(c.surface).padding(horizontal = Dimens.gapL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeBadge(place.mode, false, 80.dp)
        Spacer(Modifier.width(Dimens.gapL))
        Column(Modifier.weight(1f)) {
            GoiText(place.name, Goi.type.titleS, maxLines = 1)
            GoiText("${place.mode.label} · 반경 ${place.radiusM}m", Goi.type.bodyM, c.textSecondary, maxLines = 1)
        }
        GoiSwitch(place.autoSuggest, { engine.togglePlace(place.id) })
        Spacer(Modifier.width(Dimens.gapL))
        Pressable({ engine.deletePlace(place.id) }, Modifier.size(Dimens.buttonL), CircleShape, c.buttonBasic, c.surfaceHigh) {
            GoiIcon(Icons.Outlined.Close, c.textSecondary, Dimens.icon)
        }
    }
}

// endregion

// region F6 챌린지

@Composable
private fun ChallengePage(trips: List<TripRecord>) {
    val all = Challenge.all(trips)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Dimens.gapL)) {
        all.chunked(3).forEach { row ->
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Dimens.gapL)) {
                row.forEach { ChallengeCard(it, Modifier.weight(1f).fillMaxHeight()) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ChallengeCard(p: ChallengeProgress, modifier: Modifier) {
    val c = Goi.colors
    Card(modifier, padding = Dimens.pad) {
        ModeBadge(p.challenge.mode, active = false, size = 80.dp)
        Spacer(Modifier.height(Dimens.gapL))
        GoiText(p.challenge.title, Goi.type.titleS)
        Spacer(Modifier.weight(1f))
        ProgressBar(p.ratio)
        Spacer(Modifier.height(Dimens.gap))
        val status = p.doneAt?.let { "달성 · ${dayText(it)}" } ?: "${p.challenge.rule} · ${p.text}"
        GoiText(status, Goi.type.bodyS, if (p.done) c.textPrimary else c.textSecondary, maxLines = 2)
    }
}

// endregion
