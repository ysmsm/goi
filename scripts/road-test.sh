#!/bin/zsh
# 모의주행 환경(.agents/skills/pleos-drive-sim)에서 고이 시나리오를 실제 주행처럼 돌리고 녹화한다.
# 내비 경로 시뮬레이션이 차를 움직이고, 브리지가 그 진행에 맞춰 차속·조향·기어(VHAL)와 GPS를 바꾼다.
# 음성 응답은 마이크로 넣고(STT), 고이 목소리는 녹화에 함께 담긴다.
#
#   scripts/road-test.sh [-s emulator-5554] [-o build/road-test] [-n] cake|coffee|baby|pet|food|all
#   -n: 녹화하지 않는다
#
# 준비: Pleos 에뮬레이터 부팅, `adb emu grpc 8554`, 고이 디버그 빌드 설치, 고이를 내비 옆 분할 화면에 띄우기.
# 결과: <out>/<시나리오>/video.mp4, NN-단계.png, goi.log, bridge.out, timeline.jsonl, checks.tsv
set -uo pipefail

SERIAL=emulator-5554
OUT=build/road-test
REC=true
while getopts "s:o:n" opt; do
  case $opt in
    s) SERIAL=$OPTARG ;;
    o) OUT=$OPTARG ;;
    n) REC=false ;;
    *) exit 2 ;;
  esac
done
shift $((OPTIND - 1))
SCENARIOS=(${@:-all})
[[ $SCENARIOS == all ]] && SCENARIOS=(cake coffee baby pet food)

ROOT=${0:A:h:h}
K=$ROOT/.agents/skills/pleos-drive-sim/scripts
OUT=${OUT:A}
ADB=(adb -s $SERIAL)
export ANDROID_SERIAL=$SERIAL
# 내비가 스스로 정하는 출발점(판교 창업로)과 경로 위 장소들
ORIGIN=(37.4127212 127.094681)
BAKERY="37.41146,127.09936"     # 출발 약 560m, 판교IC 방면 길가
HOME_UTURN="37.4105,127.0950"   # 창업로 유턴이 들어가는 목적지(약 0.7km)
HOME_FAR="37.4140,127.0950"     # 대왕판교로 유턴이 들어가는 목적지
CAFE="37.40403,127.10425"       # 판교역 가는 길 1.5km 지점
STATION="37.3947,127.1112"      # 판교역

DIR=""; N=0; T0=0; BR=0

b() { $ADB shell am broadcast -a dev.seungmin.goi.DEMO -p dev.seungmin.goi "$@" >/dev/null; }
vc() { $K/vc.sh "$@" >/dev/null; }
cmd() { print -r -- "$*" >&3; }
note() { print "  $(date +%H:%M:%S) $*"; print "$(date +%H:%M:%S)\t$*" >> $DIR/steps.tsv; }

goi_log() { $ADB logcat -d -s GoiVoice:I GoiEngine:I GoiHvac:I | grep -E "speak:|heard:|event|zones|saved" | grep -v "heard: null"; }

# wait_goi <패턴> <초>: 고이 로그에 패턴이 나올 때까지 기다린다
wait_goi() {
  local end=$(( $(date +%s) + $2 ))
  while (( $(date +%s) < end )); do goi_log | grep -qE "$1" && return 0; sleep 1; done
  note "기다림 실패: $1"; return 1
}

# wait_bridge <패턴> <초>
wait_bridge() {
  local end=$(( $(date +%s) + $2 ))
  while (( $(date +%s) < end )); do grep -qE "$1" $DIR/bridge.out && return 0; sleep 1; done
  note "기다림 실패(브리지): $1"; return 1
}

# when_speed <km/h 이상> <초>: 차속이 그만큼 오를 때까지
when_speed() {
  local end=$(( $(date +%s) + $2 )) from=$(( $(wc -l < $DIR/bridge.out) + 1 ))
  while (( $(date +%s) < end )); do
    local v=$(tail -n +$from $DIR/bridge.out | grep -E " (drive|turn) " | tail -1 | sed -E 's/.*cmd= *([0-9.]+).*/\1/')
    [[ -n $v ]] && (( ${v%.*} >= $1 )) && return 0
    sleep 0.5
  done
  note "기다림 실패: 차속 $1km/h"; return 1
}

shot() {
  N=$((N + 1))
  local tmp=${TMPDIR:-/tmp}/goi-shot-$$.png
  $ADB emu screenrecord screenshot $tmp >/dev/null && mv $tmp "$DIR/$(printf %02d $N)-$1.png"
  note "화면 $1"
}

# 고이 창에 보이는 글자를 눌러 본다(고이가 포커스를 가진 분할 화면에서 동작)
tap_text() {
  local xy
  focus_goi
  xy=$($ADB exec-out uiautomator dump /dev/tty 2>/dev/null | python3 -I -c '
import re,sys
x=sys.stdin.read()
m=re.search(r"text=\"" + re.escape(sys.argv[1]) + r"\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", x)
print("" if not m else f"{(int(m[1])+int(m[3]))//2} {(int(m[2])+int(m[4]))//2}")' "$1")
  [[ -z $xy ]] && return 1
  $ADB shell input tap ${=xy}
}

ui_has() { focus_goi; $ADB exec-out uiautomator dump /dev/tty 2>/dev/null | grep -q "text=\"$1\""; }

say() { note "말하기: $1"; $K/emu-audio.py say "$1" -w ${2:-10} >/dev/null || note "마이크가 열리지 않음: $1"; }

check() {  # check <이름> <고이 로그 패턴>
  if goi_log | grep -qE "$2"; then print "$1\tPASS\t$(goi_log | grep -E "$2" | head -1 | sed -E 's/^.*(Goi[A-Za-z]+): /\1: /')" >> $DIR/checks.tsv
  else print "$1\tFAIL\t-" >> $DIR/checks.tsv; fi
}

check_none() {  # check_none <이름> <나오면 안 되는 패턴>
  if goi_log | grep -qE "$2"; then print "$1\tFAIL\t$(goi_log | grep -E "$2" | head -3 | sed -E 's/^.*(Goi[A-Za-z]+): /\1: /' | paste -sd '|' -)" >> $DIR/checks.tsv
  else print "$1\tPASS\t없음" >> $DIR/checks.tsv; fi
}

check_ui() {  # check_ui <이름> <화면 글자>
  if ui_has "$2"; then print "$1\tPASS\t화면: $2" >> $DIR/checks.tsv; else print "$1\tFAIL\t화면에 없음: $2" >> $DIR/checks.tsv; fi
}

begin() {
  DIR=$OUT/$1; N=0
  rm -rf $DIR; mkdir -p $DIR
  print "== $1"
  print "시각\t단계" > $DIR/steps.tsv
  print "확인 항목\t결과\t근거" > $DIR/checks.tsv
  # 지난 시나리오 정리: 경로·모드·차량 상태·GPS
  b --es routecancel true; b --ez reset true; b --es say 모두꺼
  vc drive 0; vc gear P; vc door fl close; vc trunk close
  $ADB emu geo fix ${ORIGIN[2]} ${ORIGIN[1]} >/dev/null
  sleep 1.5
  tap_text "모드 끄기"; tap_text "확인했어요"; tap_text "확인"
  b --ez navtrace true
  sleep 1
  $ADB logcat -c
  $REC && $K/rec.sh start $DIR/video.mp4 >/dev/null
  T0=$(date +%s)
  sleep 1
}

# 고이를 내비 옆 세로 창(Grid)으로 둔다. 내비 경로를 요청하면 내비가 전체 화면으로 올라와 분할이 풀린다.
goi_width() {
  $ADB shell dumpsys window windows | grep -A30 "dev.seungmin.goi/dev.seungmin.goi.MainActivity}:" \
    | grep -m1 " frame=" | sed -E 's/.* frame=\[([0-9]+),[0-9]+\]\[([0-9]+),.*/\2-\1/'
}
split() {
  $ADB shell am start -n dev.seungmin.goi/.MainActivity >/dev/null; sleep 2
  local w=$(goi_width)
  if [[ -z $w ]] || (( $w > 1200 )); then
    $ADB shell input swipe 2546 720 1700 720 800; sleep 2
  fi
  focus_goi
}

# 접근성 트리에는 포커스를 가진 앱 창만 나온다. 고이 창의 빈 곳(제목 줄)을 눌러 포커스를 옮긴다.
focus_goi() { $ADB shell input tap 1270 150; sleep 0.4; }

route() {  # route <목적지 lat,lng,이름> [경유지 lat,lng,이름]
  if [[ -n ${2:-} ]]; then b --es route "$1" --es via "$2"; else b --es route "$1"; fi
  note "경로 요청: $1 ${2:+경유 $2}"
  sleep 6
  split
}

drive() {  # drive <스타일> [브리지 옵션...]: 브리지를 띄우고 모의주행을 시작한다
  rm -f $DIR/ctl; mkfifo $DIR/ctl
  $K/nav-bridge.py --style $1 --start --gps --log $DIR/timeline.jsonl --control $DIR/ctl "${@:2}" > $DIR/bridge.out 2>&1 &
  BR=$!
  exec 3<>$DIR/ctl
  note "모의주행 시작($1)"
  wait_bridge "drive " 40
}

finish() {
  note "끝"
  cmd quit; wait $BR 2>/dev/null; exec 3>&-
  $K/navsim.py stop >/dev/null 2>&1
  vc drive 0; vc gear P
  sleep 1
  $REC && $K/rec.sh stop >/dev/null
  goi_log | sed -E 's/^([0-9-]+ [0-9:.]+) +[0-9]+ +[0-9]+ I (Goi[A-Za-z]+): /\1 \2: /' > $DIR/goi.log
  print "  -- 확인"; sed 's/^/     /' $DIR/checks.tsv
}

cake() {
  # 박준호: 빵집에 들르면 고이가 먼저 묻고 → 트렁크에 케이크 → 집으로(유턴·급제동) → 도착 요약 → 하차 알림
  begin cake
  b --es place "seed-bakery,달콤베이커리,${BAKERY},50,FRAGILE"
  route "${HOME_UTURN},집" "${BAKERY},달콤베이커리"
  shot 출발-전
  drive calm --stop-at-waypoints
  wait_goi "달콤베이커리에 왔어요" 150 && shot 빵집-접근-제안
  say "응"
  wait_goi "파손주의 모드 켰어요" 10
  wait_bridge "waypoint reached" 120
  cmd gear P; sleep 2.5; shot 빵집-정차
  vc trunk open; note "트렁크 열기"; sleep 2; vc trunk close; note "트렁크 닫기"; sleep 1
  cmd follow; note "다시 출발"
  when_speed 30 90 && { cmd brake 6 10; note "급제동 6m/s²"; sleep 2; shot 급제동-직후; cmd follow; }
  wait_bridge "^arrived" 240
  cmd gear P; sleep 3; shot 도착-요약
  check_ui "도착 요약 표시(P)" "파손주의 물건을 옮겼어요"
  vc door fl open; note "운전석 문 열기"; sleep 3; shot 하차-알림
  check "픽업 장소 근접 제안(FusedLocation)" "speak: 달콤베이커리에 왔어요"
  check "음성 응답 인식(STT)" "heard: 응"
  check "파손주의 모드 켜짐" "speak: 파손주의 모드 켰어요"
  check "급제동 감지" "event HARD_BRAKE"
  check "급제동 음성 안내" "speak: 급제동이에요"
  check "유턴 사전 안내(실제 TBT)" "speak: 앞에 커브가 있어요"
  check "하차 알림(트렁크)" "speak: 트렁크에 파손주의 물건이 있어요"
  vc door fl close
  finish
}

coffee() {
  # 이서연: 출근길 드라이브스루를 지나며 음료 모드 → 신호에서 급출발 → 판교역 도착 → 컵홀더 알림
  begin coffee
  b --es place "seed-cafe,카페 드라이브스루,${CAFE},80,DRINK"
  route "${STATION},판교역" "${CAFE},카페 드라이브스루"
  drive calm
  wait_goi "카페 드라이브스루에 왔어요" 240 && shot 드라이브스루-제안
  say "응 켜줘"
  wait_goi "음료 모드 켰어요" 10
  when_speed 30 90 && { cmd stop; note "신호 대기"; wait_bridge "stop +cmd=  0.0" 40; sleep 4; shot 신호-대기; cmd launch 3.5 40; note "급출발 3.5m/s²"; }
  sleep 6
  wait_bridge "^arrived" 420
  cmd gear P; sleep 3; shot 도착-요약
  vc door fl open; note "운전석 문 열기"; sleep 3; shot 하차-알림
  check "드라이브스루 근접 제안(주행 중)" "speak: 카페 드라이브스루에 왔어요"
  check "음성 응답 인식(STT)" "heard: 응"
  check "음료 모드 켜짐" "speak: 음료 모드 켰어요"
  check "급출발 감지" "event HARD_START"
  check "하차 알림(컵홀더)" "speak: 컵홀더의 음료를 챙기세요"
  vc door fl close
  finish
}

baby() {
  # 정하은: 아기를 태우고 출발 → 신호에서 "아기 자요" 시도 → 수면 중 급제동 → 유턴 → 도착 → 하차 확인
  begin baby
  route "${HOME_UTURN},집"
  tap_text "말하기"; say "아기 태웠어"
  wait_goi "아기 모드 켰어요" 10; shot 아기-모드
  drive calm
  when_speed 25 90 && { cmd stop; note "신호 대기"; wait_bridge "stop +cmd=  0.0" 40; sleep 2; shot 신호-대기-화면; }
  if tap_text "말하기"; then say "아기 자요"; else note "말하기 버튼을 찾지 못함 → 디버그 주입으로 계속"; b --es say 아기자요; fi
  wait_goi "조용히 갈게요" 10 || { note "STT가 '아기 자요'를 못 알아들음 → 디버그 주입으로 계속"; b --es say 아기자요; wait_goi "조용히 갈게요" 5; }
  cmd follow
  when_speed 25 90 && { cmd brake 5.5 5; note "수면 중 급제동"; sleep 2; cmd follow; }
  wait_bridge "^arrived" 240
  cmd gear P; sleep 3; shot 도착-요약
  vc door fl open; note "운전석 문 열기"; sleep 3; shot 하차-확인
  check "음성으로 아기 모드(STT)" "heard: 아기"
  check "아기 모드 켜짐" "speak: 아기 모드 켰어요"
  check "수면 모드" "speak: 조용히 갈게요"
  check "수면 중 급제동 기록" "event HARD_BRAKE"
  check_none "수면 중 급제동 음성 안 함" "speak: 급제동이에요"
  check "유턴 사전 안내(수면 중 짧게)" "speak: 커브, 천천히"
  check "하차 알림(아기)" "speak: 뒷좌석에 아기가 있어요"
  check_ui "확인해야 닫히는 하차 확인" "확인했어요"
  tap_text "확인했어요"
  vc door fl close
  finish
}

pet() {
  # 박준호 주말: 강아지와 빠르게(brisk) 달리면 급커브가 실제 조향으로 잡히고, 1시간이 지나면 휴식 안내
  begin pet
  route "${HOME_FAR},동물병원"
  tap_text "말하기"; say "강아지 태웠어"
  wait_goi "반려동물 모드 켰어요" 10; shot 반려동물-모드
  drive brisk
  wait_goi "event SHARP_TURN" 120 && note "급커브 감지"
  b --ei elapse 61; note "주행 시간 61분 앞당김(디버그)"
  wait_goi "잠깐 쉬어 가세요" 10
  wait_bridge "^arrived" 300
  cmd gear P; sleep 3; shot 도착-요약
  vc door fl open; sleep 3; shot 하차-확인
  check "음성으로 반려동물 모드(STT)" "heard: 강아지"
  check "급커브 감지(실제 조향각)" "event SHARP_TURN"
  check "급커브 음성 안내" "speak: 급커브예요"
  check "유턴 사전 안내" "speak: 앞에 커브가 있어요"
  check "휴식 안내" "speak: 출발한 지 1시간"
  check "하차 알림(반려동물)" "speak: 뒷좌석에 반려동물이 있어요"
  tap_text "확인했어요"
  vc door fl close
  finish
}

food() {
  # 오탐 확인: 포장 음식을 싣고 차분하게(calm) 유턴이 있는 길을 끝까지 → 흔들림 0회, 무사 운반 요약
  begin food
  route "${HOME_FAR},집"
  tap_text "말하기"; say "치킨 실었어"
  wait_goi "포장 음식 모드 켰어요" 10; shot 포장음식-모드
  drive calm
  wait_bridge "^arrived" 300
  cmd gear P; sleep 3; shot 도착-요약
  vc door fl open; sleep 3; shot 하차-알림
  check "음성으로 포장 음식 모드(STT)" "heard: 치킨"
  check_none "차분한 운전에서 흔들림 오탐 없음" "event (HARD_BRAKE|HARD_START|SHARP_TURN)"
  check "유턴 사전 안내" "speak: 앞에 커브가 있어요"
  check "하차 알림(포장 음식)" "speak: (트렁크에 포장 음식이 있어요|포장 음식을 챙기세요)"
  vc door fl close
  finish
}

for s in $SCENARIOS; do $s; done
b --ez reset true
