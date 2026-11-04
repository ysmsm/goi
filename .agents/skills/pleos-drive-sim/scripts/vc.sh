#!/bin/zsh
# Pleos Connect Virtual Console from the command line (the same HTTP API its web page uses).
#
#   vc.sh gear P|R|N|D
#   vc.sh drive <km/h> [steering_deg] [brake_pct]   # whole numbers only
#   vc.sh door fl|fr|rl|rr open|close
#   vc.sh trunk open|close
#   vc.sh lock on|off
#   vc.sh seat driver|passenger|rear_left|rear_right occupied|empty
#   vc.sh hvac power|ac|auto|recirc on|off  |  vc.sh hvac fan <0-8>  |  vc.sh hvac temp <16-30>
#   vc.sh state [section]                          # JSON (gear, controls, body, hvac, ...)
#
# VC overrides the base URL (default http://127.0.0.1:8088, forwarded by `avdctl start pleos`).
set -euo pipefail
VC=${VC:-http://127.0.0.1:8088}

put() {
  local out code
  out=$(curl -s -m 5 -w '\n%{http_code}' -X PUT "$VC$1")
  code=${out##*$'\n'}
  if [[ $code != 2* ]]; then print -u2 "PUT $1 -> $code ${out%$'\n'*}"; exit 1; fi
}
onoff() { [[ $1 == (on|open|true|1|occupied) ]] && echo ${2:-true} || echo ${3:-false}; }

case ${1:-} in
  gear)
    case ${2:-} in P) g=Park ;; R) g=Reverse ;; N) g=Neutral ;; D) g=Drive ;; *) g=$2 ;; esac
    put "/vehicle/gear?gear=$g" ;;
  drive)
    s=${2:?km/h}; st=${3:-0}; br=${4:-$(( s == 0 ? 30 : 0 ))}
    put "/vehicle/controls?speed=$s&accelerator=$(( s > 0 && br == 0 ? 20 : 0 ))&brake=$br&steering=$st" ;;
  door)  put "/vehicle/body?door_${2:?fl|fr|rl|rr}=$(onoff ${3:?open|close} 1 0)" ;;
  trunk) put "/vehicle/body?trunk=$(onoff ${2:?open|close} 1 0)" ;;
  lock)  put "/vehicle/body?doors_locked=$(onoff ${2:?on|off} 1 0)" ;;
  seat)  put "/vehicle/seat?kind=occupied&area=${2:?area}&value=$(onoff ${3:?occupied|empty})" ;;
  hvac)
    case ${2:-} in
      fan)  put "/vehicle/hvac?fan_speed=${3:?0-8}" ;;
      temp) put "/vehicle/hvac?driver_temp=${3:?16-30}&passenger_temp=$3" ;;
      *)    put "/vehicle/hvac?${2:?power|ac|auto|recirc}=$(onoff ${3:?on|off})" ;;
    esac ;;
  state)
    json=$(curl -s -m 5 "$VC/vehicle/state")
    if [[ -n ${2:-} ]]; then
      python3 -I -c 'import json,sys; print(json.dumps(json.loads(sys.argv[2]).get(sys.argv[1]), ensure_ascii=False, indent=1))' "$2" "$json"
    else
      print -r -- "$json"
    fi ;;
  *) sed -n '2,13p' "$0"; exit 2 ;;
esac
