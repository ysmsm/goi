# 고이(가칭)

> 무엇을 싣고 누구를 태웠는지 아는 차. 케이크·포장 음식·음료·반려동물·아기에 맞춰 주행 안내와 공조를 알아서 바꾸는 Pleos Connect 앱.

## 실행 방법

1. Android Studio에 Pleos Connect Emulator v2.1.0(API 34)을 설치하고 AVD를 Cold boot로 실행한다.
   ([설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator))
2. 이 폴더에서 빌드·설치한다.
   ```sh
   ./gradlew installDebug        # 개발용(데모 주입 포함)
   ./gradlew assembleRelease     # 제출용 → app/build/outputs/apk/release/app-release.apk
   ```
3. 앱 목록에서 **고이**를 실행하고 차속·위치·마이크·알림 권한을 허용한다.
4. 차량 상태는 Pleos Connect Virtual Console(Gear Selection, Brake Pedal, Steering Angle, Door/Trunk, Climate)로 바꾼다.

## 테스트 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1.0, Android 14(API 34), Connect-S 2560x1440 160dpi |
| 빌드 | Gradle 9.8, AGP 9.4.1, Kotlin 2.4.20, Jetpack Compose, JDK 17+ |
| SDK | Vehicle 2.3.2, NaviHelper 3.0.5, FusedLocation 1.1.3, TextToSpeech 3.3.1, SpeechToText 3.3.1 |
| minSdk / targetSdk | 28 / 34 |

release APK는 지금 debug 키로 서명한다. 제출 파일명은 `팀명_고이_release.apk`로 바꿔 저장소 `apk/` 폴더에 올린다.

## 무엇을 하나

| 기능 | 동작 | 사용 데이터 |
|---|---|---|
| 모드 5종 | 파손주의(케이크·화분·와인)·포장 음식·음료·반려동물·아기. 같은 엔진에 민감도·커브 안내 거리·공조 항목·하차 문구만 바꿔 끼운다 | — |
| 픽업 장소 자동 제안 | 등록 장소에 들어서면 "달콤베이커리에 왔어요. 파손주의 모드를 켤까요?"를 음성으로 묻고 "응"으로 켠다 | FusedLocation 근접 알림, TTS, STT |
| 쏠림 감지 | 급제동·급출발(약 1초 차속 변화), 급커브(a_y ≈ v²·tan(δ/i)/L)를 민감도 기준으로 판정. 한 번의 급가속·급제동은 한 번만 센다(기준 아래로 내려가야 다시 셈). 효과음 1회 + 기록, 주행마다 종류별 처음 한 번은 "급제동이에요"처럼 말로 알린다 | AAOS `PERF_VEHICLE_SPEED`, Vehicle SteeringWheel |
| 커브 사전 안내 | 내비 경로 앞 급커브(TBT `TURN_SHARP_*`, 유턴)가 설정한 거리(100·200·300m) 안에 들어오면 "앞에 커브가 있어요, 천천히" | NaviHelper `onTBTInfo` |
| 공조 항목 | 모드마다 켜고 끌 수 있는 항목: 냄새 빼기(외기+바람 2단계), 실내 온도 낮춤(-2°C), 바람 약하게, 뒷좌석 온도 유지, 직접 바람 피하기, 주기적 환기(10·20·30분). 모드를 모두 끄면 원래 값으로 복원. 아기·반려동물 항목은 기어 P가 아닐 때만 동작(신호 대기에는 유지). 못 바꾸면 "외기로 바꿔 보세요" 음성 권유로 축소 | Vehicle HVAC(capability 확인 후 쓰기) |
| 휴식 안내 | 반려동물 모드로 1·2시간 달리면 "출발한 지 1시간 지났어요. 반려동물과 잠깐 쉬어 가세요" | 주행 시간 |
| 운반 요약 | 기어 P 도착 시 무사히 왔으면 모드별 축하 문구 3개를 차례로("하나도 안 흘렸어요! ☕", "모양 그대로 도착했어요! 🎂" 등), 흔들림이 있었으면 "파손주의 물건을 옮겼어요"처럼 도착 사실만, 출발 장소·시간·거리, 급제동·급커브·커브 안내·급출발 횟수와 순서. "계속 켜 두기 / 모드 끄기" | Vehicle Gear, 차속 적분 |
| 하차(망각) 알림 | 도착 후 운전석 문이 열리면 "뒷좌석에 아기가 있어요", "트렁크에 파손주의 물건이 있어요" 등. 트렁크는 모드별로 기억한다(픽업 때 트렁크를 연 모드만, 짐을 먼저 싣고 10분 안에 모드를 켜도 인정). 아기·반려동물은 확인 버튼을 눌러야 닫힌다 | Vehicle Door(ROW_1_LEFT, REAR) |
| 아기 수면 모드 | "아기 자요"로 켠다. 효과음을 끄고 안내 음성을 짧게, 감지된 일은 신호 대기에서 "수면 중 알림"으로 보여 주고 출발하면 닫는다. 화면은 어둡게. 도착(P)하면 꺼진다 | STT |
| Gleo AI 음성 대화 | 말하기 버튼 없이 Gleo AI에 말하면("케이크 실었어", "치킨도 실었어", "아기 자요", "지금까지 어때?") Gleo가 고른 함수를 받아 실행하고, 결과를 Callback으로 돌려줘 Gleo가 답한다. 함수는 `SET_CARE_MODE`·`SET_BABY_SLEEP`·`GET_CARE_STATUS` 3개(`app/src/main/assets/schema.json`). 수면 중에는 Gleo도 완료음만 낸다 | Gleo Interface |
| 챌린지 | 커피 한 방울도 안 흘리기(음료, 급가감속 없이 5번), 케이크 무사 운반, 멀미 없는 드라이브(반려동물, 급커브 없이 30분), 국물 한 방울도 지키기(포장 음식, 급제동 없이 3번), 깨우지 않고 도착(아기, 5번). 운반 기록에서 계산 | 운반 기록 |
| 백그라운드 | 모드가 켜진 동안 포그라운드 서비스로 리스너를 유지하고, 화면에 없을 때 제안·알림은 System notification으로 | — |

## 화면 설계

와이어프레임 v0.1의 화면 구성(G1~G6, F1~F7, A1~A3)을 따른다. 차량 화면이라 다음 원칙을 함께 지킨다.

- **주행 중엔 한 장만.** 켜진 모드 아이콘, 상태 한 줄("파손주의 물건 운반 중"), 보조 한 줄("민감도 최고 · 커브 300m 전 안내"), 큰 버튼 하나(말하기, 수면 중에는 수면 모드 끄기). Full 창에서는 "주행 중에는 간단히 보여 드려요"를 덧붙인다. 경고는 화면을 바꾸지 않고 소리로만 한다.
- **기능 이름 + 무엇을 하는지.** 모드는 이름 아래에 어떤 짐·동승자일 때 쓰는지(케이크·화분·와인)를 둔다. 정차 중 Grid는 켜진 모드가 있으면 그 모드가 하는 일(민감도·커브 안내·공조·도착 알림)을 표로 보여 준다(G4).
- **설정 문구는 Pleos 차량 설정 톤.** 차량 설정 앱처럼 스위치에는 늘 설명(`~합니다`)을 붙인다. 페이지 머리 설명은 아래 내용이 말해 주지 않는 것(운반 기록의 저장 위치, 픽업 장소 스위치의 뜻)에만 둔다. 선택지(Tabs)는 이름만으로 무엇을 정하는지 알기 어려울 때만 설명을 붙인다(민감도·커브 사전 안내·휴식 안내). 민감도는 고른 단계의 뜻을 아래 한 줄로 보여 준다.
- **강조색은 하나.** Pleos Grayscale 토큰 위에 `informative_positive`(#0064FF, 다크 #0082FF) 하나만 켜진 모드와 주 동작에 쓴다. 설정 스위치는 회색. 모드별 앰비언트 색은 쓰지 않는다.
- **큰 표적.** 타일·행 112dp 이상, 버튼 96/128dp, 슬라이더 대신 Tabs, 픽업 장소는 검색·지도 대신 "지금 위치를 픽업 장소로 저장" 후 짐 종류만 고른다.

| 상태 | Grid(세로 창) | Full(가로 창) |
|---|---|---|
| 주행 중 | G1 켜진 모드 + 상태 한 줄 + 말하기 / G5 수면 | F5 같은 화면 + 안내 한 줄 |
| 정차 중 | G3 무엇과 함께 가나요?(모드 5개 + 말하기) → G4 활성화된 모드 | 좌측 레일: F1 홈 · F2/F7 모드 설정 · 운반 기록 · F4 픽업 장소 · F6 챌린지 |
| 이벤트 | G2 모드 제안, F3 운반 요약, A1 하차 알림(popup), G6 수면 중 이벤트 | 같은 화면 |

## Gleo AI 연동

[Gleo Interface 가이드](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts)를 따른다.

- **App Schema**: `app/src/main/assets/schema.json`. Gleo AI가 부팅·앱 설치 때 읽고, Pleos 검토 승인 후 연동이 켜진다. `version`은 `versionName`과 맞춘다.
- **Gleo Action**: 세 함수 모두 `component: "service"`라 Gleo가 `sendBroadcast`로 부르고 `GleoReceiver`(`voice/`)가 받는다. 파라미터는 `ACTION_PARAM` extra의 JSON이다. 주행 중 화면을 띄우지 않는다.
- **Callback**: `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE` 브로드캐스트(`packageName`, `response`). 답은 Gleo가 말하므로 앱 TTS는 겹쳐 말하지 않는다.

| 함수 | 파라미터 | 하는 일 |
|---|---|---|
| `SET_CARE_MODE` | `mode`(fragile·food·drink·pet·baby·all), `enabled` | 모드 켜기·끄기·더하기. 제안 중인 모드를 켜면 제안 수락과 같다 |
| `SET_BABY_SLEEP` | `sleeping` | 수면 모드(아기 모드가 꺼져 있으면 함께 켬) |
| `GET_CARE_STATUS` | — | 켜진 모드, 민감도, 공조·뒷좌석 목표 온도, 이번 주행의 급제동·급출발·급커브·커브 안내 횟수 |

에뮬레이터에 Gleo AI가 없어도 같은 브로드캐스트로 확인할 수 있다(release 빌드에서도 동작).

```sh
adb shell "am broadcast -a SET_CARE_MODE -p dev.seungmin.goi --es ACTION_PARAM '{\"mode\":\"food\",\"enabled\":true}'"
adb shell "am broadcast -a GET_CARE_STATUS -p dev.seungmin.goi --es ACTION_PARAM '{}'"
adb logcat -s GoiGleo   # 받은 함수와 돌려준 Callback
```

## 데모 주입(디버그 빌드)

Virtual Console에는 차속 조절이 없어서, 디버그 빌드는 adb 브로드캐스트로 차량 상태·장소 진입·발화를 넣을 수 있다. 값에 공백을 넣지 않는다.

```sh
B="adb shell am broadcast -a dev.seungmin.goi.DEMO -p dev.seungmin.goi"
$B --es enter seed-bakery            # 달콤베이커리 진입 → 파손주의 모드 제안
$B --es say 응                       # 음성 응답 대신
$B --ez trunk true; $B --ez trunk false   # 픽업 때 트렁크 열고 닫기
$B --es gear D --ef speed 20          # 출발(차속 km/h)
$B --ef speed 50 --es curve SHARP_R:250  # 앞 250m 급커브 → 사전 안내
$B --ef speed 15                      # 1초 안에 50→15: 급제동
$B --ef speed 0; $B --es gear P       # 도착 → 운반 요약
$B --ez door true                     # 운전석 문 열림 → 하차 알림
$B --es say 아기자요                  # 수면 모드
$B --ei elapse 61                     # 주행 시간 61분 앞당김 → 반려동물 휴식 안내
$B --ez reset true                    # 주입값 해제
```

### 시나리오 반복 시뮬레이션

기획서 페르소나 4개를 실제 주행처럼(0.25초 간격으로 차속을 조금씩 바꿔) 재현하고, 단계별 화면을 `build/sim/<시나리오>/`에, 고이가 한 말과 감지한 이벤트를 `log.txt`에 남긴다.

```sh
scripts/drive-sim.sh -c all        # -c: 앱 데이터를 지우고 기본 픽업 장소로 시작
scripts/drive-sim.sh baby          # 하나만
```

| 시나리오 | 흐름 |
|---|---|
| `cake` | 달콤베이커리 제안 → 트렁크 → 커브 사전 안내 → 급제동 → 치킨 드라이브스루에서 Gleo로 포장 음식 추가(겹침) → Gleo 상태 질문 → 도착 요약 → 하차 알림 |
| `baby` | 아기 모드 → Gleo로 "아기 자요"·상태 질문 → 급제동(효과음 없이 기록) → 신호 대기에서 "수면 중 알림" → 출발하면 닫힘 → 도착 → 확인해야 닫히는 하차 확인 |
| `pet` | 반려동물 모드 → 급커브 → 61분 경과 휴식 안내 → 도착 |
| `coffee` | 드라이브스루 진입 중 음료 모드 제안 → 신호 출발 급출발 → 도착 → 컵홀더 알림 |

## 확인된 것과 남은 것

- 에뮬레이터에서 확인: Vehicle SDK로 기어·브레이크·조향각·운전석 문·트렁크 수신, AAOS 차속 수신, 온디바이스 TTS(ko-KR) 발화, STT 녹음 시작, 공조 capability 조회.
- 에뮬레이터 공조 전원이 꺼져 있으면 HVAC 쓰기가 `PropertyNotAvailable`로 실패하고, 앱은 음성 권유로 대체한다. Virtual Console에서 HVAC Power를 켜면 적용된다.
- Light SDK에는 앰비언트 색 제어 API가 없어(실내등 on/off만 제공) 앰비언트 색상 기능은 넣지 않았다.
- 뒷좌석 별도 온도 영역이 없는 차에서는 운전석 영역 온도를 맞춘다.
- 챌린지(F6)는 모드별 5개를 운반 기록으로 계산한다. 외부 공유·인증 배지는 넣지 않았다.
- TTS SDK에 음량 API가 없어 수면 모드의 "안내 음성 작게"는 짧은 문장으로 대신한다.
- 운반 요약의 도착지 이름(예: "→ 집")은 내비 목적지를 읽지 않아 표시하지 않는다. 출발 장소는 픽업 장소 제안으로 모드를 켰을 때만 보인다.
- Gleo AI 연동은 가이드대로 구현했지만 실제 Gleo AI와는 아직 확인하지 않았다(에뮬레이터에 Gleo AI 없음, schema 검토 승인 전). 앱 자체 STT(말하기 버튼)는 Gleo를 쓸 수 없는 경우를 위해 그대로 둔다.
- 단위 테스트: `./gradlew testDebugUnitTest`(쏠림 판정, 음성 명령, Gleo 함수·Callback, 모드 합치기, 하차 문구, 챌린지).
