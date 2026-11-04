# goi

고이(가칭): 차에 실은 짐과 태운 동승자(케이크·음식·음료·반려동물·아기)에 맞춰 주행 안내와 공조를 바꿔 주는 Pleos Connect 앱.
기획·화면의 근거는 `../docs/`의 기획서와 와이어프레임이고, 동작·설계 요약은 `README.md`에 있다.

## 구조

- `app/src/main/java/dev/seungmin/goi/care/`: 모드·공조 항목 정의(`CareMode`, `HvacItem`), 공통 센서 엔진(`CareEngine`), 쏠림 판정(`MotionDetector`), 챌린지(`Challenge`), 음성 명령 파서
- `app/src/main/java/dev/seungmin/goi/car/`: Pleos SDK 연동(Vehicle, HVAC 제어, NaviHelper TBT, FusedLocation 근접 알림)
- `app/src/main/java/dev/seungmin/goi/voice/`: Pleos TextToSpeech·SpeechToText(온디바이스, ko-KR)
- `app/src/main/java/dev/seungmin/goi/ui/`: Compose 화면. 토큰은 `ui/theme/Theme.kt`
- `app/src/debug/`: 에뮬레이터 데모용 adb 브로드캐스트 주입기(`DemoReceiver`). release에는 들어가지 않는다
- `branding/`: 워드마크 `goi-logo.svg`와 그 생성기 `build_logo.py`(`uv run branding/build_logo.py`). 형태와 색 바꾸는 법은 `branding/README.md`
- `scripts/drive-sim.sh`: 기획서 페르소나 시나리오를 앱 안 주입값으로 빠르게 재현하고 단계별 화면과 음성 로그를 `build/sim/`에 남긴다
- `scripts/road-test.sh`: 같은 시나리오를 모의주행(내비 경로 시뮬레이션 + 시스템 차속·조향·GPS + 마이크 음성)으로 돌리고 녹화·확인 결과를 `build/road-test/`에 남긴다
- `.agents/skills/pleos-drive-sim/`: 모의주행 환경 구성 스킬과 도구(`.claude/skills/`는 링크)

## 규칙

- 색은 Pleos Grayscale 토큰과 강조색 하나(`informative_positive`)만 쓴다. 강조색은 켜진 모드와 주 동작에만 쓰고, 모드별 색을 화면에 쓰지 않는다.
- 주행 중 화면에는 켜진 모드, 상태 한 줄(1~2초에 읽히는 분량), 큰 버튼 하나만 둔다. 주행 중 피드백은 화면 변화 없이 TTS·효과음으로 한다.
- 설정 문구는 Pleos 차량 설정 톤(`~합니다`)을 따른다. 페이지 머리 설명은 아래 항목이 말해 주지 않는 것에만 둔다. 스위치에는 늘 한두 문장 설명을 붙이고, 선택지(Tabs)는 선택지 이름만으로 무엇을 정하는지 알기 어려울 때만 설명을 붙인다. 음성·팝업·안내 문구는 `~해요` 체다.
- 문구는 우리말 어순으로 쓴다. 앱을 주어로 한 문장("고이가 ~")과 명사로 끝나는 제목("무사 운반 성공")을 피한다(표 값·버튼 이름은 예외). 아기·반려동물이 함께 들어가는 자리에는 "싣다/실은 것"을 쓰지 않는다. 안내 문구는 사용자가 할 일 하나만 말하고("‘케이크 옮길래’라고 말해 보세요") 그 결과까지 풀어 쓰지 않는다. 말하기 예시는 앱에 시키는 말("케이크 옮겨줘")이 아니라 사용자가 하려는 일을 알리는 말("케이크 옮길래")로 쓴다. 화면 제목에는 "지금 켜진 모드" 같은 관형절 대신 "활성화된 모드"처럼 굳은 말을 쓴다. 토스트는 사용자가 한 행동의 결과(켰어요·저장했어요·못 들었어요)에만 쓰고, 앱이 먼저 건네는 권유("외기로 바꿔 보세요")는 음성으로만 한다. 이모지는 무사 운반 요약의 축하 제목(`CareMode.safeTitles`) 끝에만 하나 쓴다.
- 크기는 Pleos 가이드 px 값을 dp로 쓴다(Connect-S 에뮬레이터 2560x1440, 160dpi). 버튼 높이 96/128, 아이콘 48 그리드.
- Vehicle SDK 쓰기는 `check*Capabilities`로 WRITE를 확인한 영역에만 한다. 매니페스트에는 실제로 쓰는 퍼미션만 선언한다.
- 판정 로직(`MotionDetector`, `VoiceCommand`, `MergedCare`, `Challenge`, `ForgetAlert`)을 바꾸면 `./gradlew testDebugUnitTest`를 함께 고친다.
- 화면·엔진 동작을 바꾸면 `scripts/drive-sim.sh -c all`로 시나리오를 다시 돌려 화면과 음성 로그를 확인한다. 주행 중 동작(차속·주행 규제·근접 알림·음성)을 바꾸면 `scripts/road-test.sh`로 모의주행에서도 확인한다.
