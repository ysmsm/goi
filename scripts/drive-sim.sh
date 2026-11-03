#!/bin/zsh
# 에뮬레이터에서 기획서 페르소나 시나리오를 실제 주행처럼 재현한다(디버그 빌드 필요).
# 차속은 0.25초 간격으로 조금씩 바꿔 넣고, 단계마다 화면을 캡처하고, 끝에 고이가 한 말과 감지한 이벤트를 출력한다.
#
#   scripts/drive-sim.sh [-s emulator-5554] [-o build/sim] [-c] cake|baby|pet|coffee|all
#   -c: 앱 데이터를 지우고(기본 픽업 장소로 시작) 권한을 다시 준다
#
# 결과: <out>/<시나리오>/NN-단계.png, <out>/<시나리오>/log.txt
set -euo pipefail

SERIAL=emulator-5554
OUT=build/sim
CLEAN=false
while getopts "s:o:c" opt; do
  case $opt in
    s) SERIAL=$OPTARG ;;
    o) OUT=$OPTARG ;;
    c) CLEAN=true ;;
    *) exit 2 ;;
  esac
done
shift $((OPTIND - 1))
SCENARIOS=(${@:-all})
[[ $SCENARIOS == all ]] && SCENARIOS=(cake baby pet coffee)

ADB=(adb -s $SERIAL)
# Pleos는 앱을 운전자 사용자(10 등)로 실행한다
USER_ID=$($ADB shell am get-current-user | tr -d '\r')
SHOT_TMP=$(mktemp -d)/shot.png
SPEED=0
DIR=""
N=0

b() { $ADB shell am broadcast -a dev.seungmin.goi.DEMO -p dev.seungmin.goi "$@" >/dev/null; }

# ramp <목표 km/h> <초> [조향각]: 지금 차속에서 목표까지 일정하게 바꾼다
ramp() {
  local to=$1 secs=$2 steer=${3:-0}
  local steps=$(( secs * 4 < 1 ? 1 : secs * 4 ))
  local from=$SPEED
  for i in $(seq 1 $steps); do
    b --ef speed $(( from + (to - from) * i * 1.0 / steps )) --ef steer $steer
    sleep 0.25
  done
  SPEED=$to
}

# cruise <초>: 지금 차속을 유지한다(거리 적분과 센서 갱신 흉내)
cruise() {
  for i in $(seq 1 $(( $1 * 2 ))); do b --ef speed $SPEED --ef steer 0; sleep 0.5; done
}

shot() {
  sleep ${2:-1.2}
  N=$((N + 1))
  # 에뮬레이터 콘솔은 공백·한글 경로를 못 받아 임시 경로로 찍고 옮긴다
  $ADB emu screenrecord screenshot "$SHOT_TMP" >/dev/null
  mv "$SHOT_TMP" "$DIR/$(printf %02d $N)-$1.png"
  echo "  [$N] $1"
}

# 화면에 보이는 글자를 눌러 본다(Compose 접근성 노드)
tap_text() {
  local xy
  xy=$($ADB exec-out uiautomator dump /dev/tty 2>/dev/null | python3 -I -c '
import re,sys
x=sys.stdin.read()
m=re.search(r"text=\"" + re.escape(sys.argv[1]) + r"\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", x)
print("" if not m else f"{(int(m[1])+int(m[3]))//2} {(int(m[2])+int(m[4]))//2}")' "$1")
  if [[ -z $xy ]]; then echo "  ! '$1' 없음"; return 0; fi
  $ADB shell input tap ${=xy}
}

begin() {
  DIR=$OUT/$1; N=0; SPEED=0
  rm -rf $DIR; mkdir -p $DIR
  echo "== $1"
  b --ez reset true
  b --es gear P --ef speed 0 --ez door false --ez trunk false
  b --es say 모두꺼
  $ADB logcat -c
  $ADB shell am start -n dev.seungmin.goi/.MainActivity >/dev/null
  sleep 1.5
  # 지난 시나리오가 남긴 요약·알림 닫기
  tap_text "모드 끄기"; tap_text "확인했어요"; tap_text "확인"
  sleep 0.5
}

finish() {
  $ADB logcat -d -s GoiVoice:I GoiEngine:I GoiHvac:I | grep -E "speak:|event|zones|saved" | sed -E 's/^.* (Goi[A-Za-z]+): /\1: /' > $DIR/log.txt || true
  echo "  -- 고이가 한 말·이벤트"; sed 's/^/     /' $DIR/log.txt
}

cake() {
  # 박준호: 케이크 픽업 → 커브 → 급제동 → 치킨 드라이브스루에서 모드 추가 → 집 도착
  begin cake
  b --es enter seed-bakery;               shot 픽업장소-제안 2.5
  b --es say 응;                          shot 모드-켜짐
  b --ez trunk true; sleep 1; b --ez trunk false
  b --es gear D; ramp 30 6;               shot 주행-출발
  ramp 50 4; cruise 3
  b --es curve SHARP_R:450; cruise 2
  b --es curve SHARP_R:280; cruise 2;     shot 커브-사전안내
  b --es curve none
  ramp 15 1; ramp 0 3;                    shot 급제동-후-정차
  b --es enter seed-chicken;              shot 드라이브스루-제안 2.5
  b --es say 응;                          shot 모드-겹침
  ramp 40 6; cruise 20; ramp 0 6
  b --es gear P;                          shot 도착-요약
  b --ez door true;                       shot 하차-알림
  tap_text "확인했어요";                  shot 알림-확인
  finish
}

baby() {
  # 정하은: 아기 모드 → 잠듦 → 급제동은 효과음 대신 기록 → 신호 대기 때 보여 주기 → 도착 하차 확인
  begin baby
  b --es say 아기태웠어;                  shot 아기-모드
  b --es gear D; ramp 40 8; cruise 3
  b --es say 아기자요;                    shot 수면-모드
  ramp 10 1; ramp 0 3;                    shot 신호대기-이벤트
  ramp 30 5;                              shot 출발-자동닫힘
  b --es curve SHARP_L:150; cruise 2; b --es curve none
  cruise 15; ramp 0 6
  b --es gear P;                          shot 도착-요약
  b --ez door true;                       shot 하차-확인
  $ADB shell input tap 1400 300;           shot 바깥-눌러도-유지
  tap_text "확인했어요"
  finish
}

pet() {
  # 박준호 주말: 강아지와 병원 → 1시간 넘게 달리면 휴식 안내
  begin pet
  b --es say 강아지태웠어;                shot 반려동물-모드
  b --es gear D; ramp 60 8;               shot 주행
  b --ef steer 120; ramp 60 2 120; b --ef steer 0
  b --ei elapse 61; cruise 2;             shot 휴식-안내
  ramp 0 8
  b --es gear P;                          shot 도착-요약
  b --ez door true;                       shot 하차-확인
  tap_text "확인했어요"
  finish
}

coffee() {
  # 이서연: 드라이브스루에서 음료 모드 → 신호 출발 때 급출발 → 회사 도착
  begin coffee
  b --es gear D; ramp 5 2
  b --es enter seed-cafe;                 shot 드라이브스루-제안 2.5
  b --es say 응
  ramp 0 2;                               shot 정차-음료모드
  ramp 30 1; ramp 40 4; cruise 20;        shot 급출발-후
  ramp 0 8
  b --es gear P;                          shot 도착-요약
  b --ez door true;                       shot 하차-알림
  finish
}

if $CLEAN; then
  $ADB shell pm clear --user $USER_ID dev.seungmin.goi >/dev/null
  for p in android.car.permission.CAR_SPEED pleos.car.permission.FUSED_LOCATION android.permission.RECORD_AUDIO android.permission.POST_NOTIFICATIONS; do
    $ADB shell pm grant --user $USER_ID dev.seungmin.goi $p 2>/dev/null || true
  done
fi
for s in $SCENARIOS; do $s; done
b --ez reset true
