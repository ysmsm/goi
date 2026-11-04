#!/bin/zsh
# Record the emulator screen (emulator-side, so it works on Pleos where screencap is black)
# together with the emulator's audio output, and mux both into one MP4.
#
#   rec.sh start <out.mp4> [-s SERIAL]   # screen + audio capture in the background
#   rec.sh stop [-s SERIAL]              # stop both and write <out.mp4>
#
# Audio needs the emulator gRPC endpoint (`adb -s SERIAL emu grpc 8554`); ffmpeg muxes.
# The emulator console rejects paths with spaces or non-ASCII characters, so raw files
# go to a temporary folder first.
set -euo pipefail
here=${0:A:h}
cmd=${1:?start|stop}; shift
out=""
if [[ $cmd == start ]]; then out=${1:?out.mp4}; shift; fi
SERIAL=${ANDROID_SERIAL:-emulator-5554}
while getopts "s:" o; do case $o in s) SERIAL=$OPTARG ;; *) exit 2 ;; esac; done
state=${TMPDIR:-/tmp}/pleos-rec-${SERIAL}

case $cmd in
  start)
    if [[ -e $state/pid ]]; then print -u2 "already recording ($state)"; exit 1; fi
    rm -rf $state; mkdir -p $state
    print -r -- "${out:A}" > $state/out
    adb -s $SERIAL emu screenrecord start --time-limit 3600 --size 1280x720 --bit-rate 3M --fps 24 $state/screen.webm >/dev/null
    nohup $here/emu-audio.py -s $SERIAL record $state/audio.wav > $state/audio.log 2>&1 &
    print $! > $state/pid
    print "recording $SERIAL -> ${out:A}" ;;
  stop)
    if [[ ! -e $state/pid ]]; then print -u2 "not recording"; exit 1; fi
    adb -s $SERIAL emu screenrecord stop >/dev/null
    kill -TERM $(<$state/pid) 2>/dev/null || true
    for i in {1..50}; do kill -0 $(<$state/pid) 2>/dev/null || break; sleep 0.1; done
    sleep 1  # the emulator finishes the webm asynchronously
    out=$(<$state/out); mkdir -p ${out:h}
    if [[ -s $state/audio.wav ]]; then
      ffmpeg -loglevel error -y -i $state/screen.webm -i $state/audio.wav -map 0:v -map 1:a \
        -c:v libx264 -preset veryfast -crf 25 -pix_fmt yuv420p -c:a aac -b:a 128k -shortest "$out"
    else
      ffmpeg -loglevel error -y -i $state/screen.webm -c:v libx264 -preset veryfast -crf 25 -pix_fmt yuv420p "$out"
    fi
    rm -f $state/pid
    print "saved $out ($(ffprobe -v error -show_entries format=duration -of csv=p=0 "$out" | cut -d. -f1)s)" ;;
  *) sed -n '2,10p' "$0"; exit 2 ;;
esac
