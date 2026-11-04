---
name: pleos-drive-sim
description: "Set up a simulated drive on the Pleos Connect emulator so the app under test sees real-car-like data: a navigation route driven by the navigation app's hidden route simulation, vehicle speed, steering and gear derived from it through the Virtual Console, GPS following the route, voice in (STT) and out (TTS) through the emulator gRPC audio API, and a screen+audio recording. Use when asked to reproduce 모의주행 / a drive test on the Pleos emulator, to make the cluster speed follow the navigation simulation, to inject speech into the emulator microphone, or to record a drive with sound. Covers environment setup only, not test scenarios or app-specific assertions."
---

# Pleos simulated drive

Builds a drive on the `Pleos_Connect` emulator where every signal an app can read moves
together: the navigation car follows a real route, the cluster speed and steering match
it, GPS moves along, the app can be spoken to and its voice is captured. Scripts are in
`scripts/` next to this file; run them with the serial in `-s` or `ANDROID_SERIAL`.

## What drives what

| Signal | Source in this setup | How an app reads it |
|---|---|---|
| Route, TBT, remaining distance, speed limit, arrival | Navigation route simulation (`navsim.py`) | NaviHelper `onTBTInfo`, `onDrivingInfo`, `onCurrentRoadSpeedLimit`, `onDestinationArrived`, `getCurrentLocationInfo` |
| Speed, steering angle, accelerator, brake, gear | `nav-bridge.py` → Virtual Console `/vehicle/controls`, `/vehicle/gear` | AAOS `PERF_VEHICLE_SPEED`, Vehicle SDK (gear, brake, steering), cluster |
| GPS position | `nav-bridge.py --gps` → `adb emu geo fix` | LocationManager, Pleos FusedLocation (incl. proximity alerts) |
| Doors, trunk, locks, HVAC, seats | `vc.sh` → Virtual Console | Vehicle SDK |
| Speech into the microphone | `emu-audio.py say` → gRPC `injectAudio` | Pleos SpeechToText (on-device ko-KR) |
| Sound out (TTS, chimes, navigation voice) | `emu-audio.py record` / `rec.sh` → gRPC `streamAudio` | — |

## Prerequisites

- The `android-emulators` skill's rules for `Pleos_Connect` (start with `avdctl`, pass the serial).
- `ffmpeg`, `uv` and the macOS Korean voice `Yuna` (`say -v '?' | grep ko_KR`).
- To mirror GPS: a NaviHelper client in the app under test that logs
  `GoiNavTrace: loc <lat> <lng> <heading>` every second (in goi: debug broadcast
  `--ez navtrace true`). Without it, everything except GPS still works.

## Set up

1. **Boot.** `avdctl start pleos` (cold boot, about 40 s; the Virtual Console is forwarded to
   `http://127.0.0.1:8088/`). If the serial never appears and
   `~/Library/Logs/avdctl/Pleos_Connect-<port>.log` ends with `Showing crashdialog to get consent`,
   an emulator crash-report dialog is waiting on the Mac screen: answer it, or stop the
   emulator, move `/tmp/android-$USER/emu-crash-*.db/completed/*.dmp` out of the way and boot again.
2. **Open the gRPC endpoint** (once per boot): `adb -s $S emu grpc 8554`. The scripts read the
   port and token from the emulator discovery file.
3. **Set a route.** The simulation needs active route guidance. Either call
   `NaviHelper.requestRoute(RouteInfo(lat, lng, ...))` from a debug hook in the app under test
   (needs `pleos.car.permission.NAVI_ROUTE_SEARCH`, declare it in the debug manifest only; goi:
   `adb shell am broadcast -a dev.seungmin.goi.DEMO -p dev.seungmin.goi --es route "lat,lng,name"`),
   or pick a destination in the navigation UI (recent, bookmarks, categories). `geo:` intents
   do not set a route. Routes always start at the navigation's own position
   (37.41272, 127.09468, 판교 창업로); the emulator GPS does not move it. A route request
   brings the navigation to full screen.
4. **Lay out the screen** (after the route request, which undoes it). Launch the app, then
   drag the `⋮` handle at the right edge of the app area to the left
   (`adb shell input swipe 2546 720 1700 720 800`): the app becomes a portrait (Grid) pane next
   to the navigation map. Tap an empty spot of the app to give it focus before reading its UI:
   the accessibility tree holds only the focused app window. Keep the app visible or in a
   foreground service: Android 14 freezes a cached background app, and its listeners and logs stop.
5. **Start recording** if needed: `scripts/rec.sh start <out.mp4>` (emulator screen at 1280x720 +
   emulator audio, muxed on `stop`).
6. **Start the bridge and the simulation together:**
   ```sh
   mkfifo ctl
   scripts/nav-bridge.py --style calm --start --gps --log timeline.jsonl --control ctl &
   exec 3<>ctl          # keep the FIFO open; send commands with: echo "<cmd>" >&3
   ```
   `--start` opens the panel (long press on the map's settings button) and taps 시작.
   `--stop-at-waypoints` makes the car stop at each waypoint of a
   `requestRouteWithWaypoints` route (goi: `--es via "lat,lng,name"`) until `follow`.
   Styles set acceleration, braking and turn lateral acceleration: `calm` 1.0/1.3/1.1,
   `normal` 1.8/2.3/2.2, `brisk` 3.0/3.8/3.8 m/s². The bridge shifts to D before moving off.
7. **Drive.** Bridge commands: `stop` / `follow` (a red light: the car stops and the
   navigation pauses with it), `brake <m/s²> [km/h]`, `launch <m/s²> [km/h]`,
   `swerve <deg> <s>`, `gear P`, `quit` (stops gently, then exits).
   Speech: `scripts/emu-audio.py say "응"` waits until the app opens the microphone, then
   speaks. Body and climate: `scripts/vc.sh door fl open`, `vc.sh trunk open`, `vc.sh gear P`,
   `vc.sh hvac power on`, `vc.sh state controls`.
8. **Tear down.** `echo quit >&3`, `scripts/navsim.py stop`, `scripts/rec.sh stop`,
   `scripts/vc.sh gear P`, and `avdctl kill` if you booted the emulator.

## Facts and limits

- The route simulation is hidden: long press the navigation settings button (bottom right of
  the map) while guiding. Its bar only shows progress: tapping or dragging it changes neither
  speed nor position. It moves the navigation car at a constant ~60 km/h regardless of turns
  and limits, with no way to change that speed, and never changes VHAL speed, gear,
  steering or GPS. During it the navigation emits real NaviHelper events: `onTBTInfo` (1 Hz,
  10 m steps), `onDrivingInfo`, `onCautionPointInfo`, `onCurrentRoadSpeedLimit`,
  `onDestinationArrived`. In an `onTBTInfo` list the first distance is from the car, the
  others are segment lengths. 종료 in the panel ends the simulation and the route guidance.
- The bridge reads logcat only: `SdvPlacesenseService` `remaining_distance` (1 m, 1 Hz),
  `NavigationEventListener` (from `ai.pleos.playground.service.cap`), `navi_engine`
  `start/stop_route_simulation`, and optional `GoiNavTrace`. It keeps the navigation within
  `--ahead` metres (40) of the car by tapping 정지/시작; `--no-lock` lets it run ahead.
- Because the simulation is fixed at 60 km/h, the navigation is paused for about
  1 − (average car speed / 60) of the drive whatever `--ahead` is (about half of a city drive).
  `--ahead` only trades how often it pauses against how far the map, NaviHelper TBT distances
  and the arrival event run ahead of the car. Pause-plus-resume taps per minute, computed from a
  recorded city drive averaging 26 km/h: 40 m ≈ 8.5, 80 m ≈ 4, 120 m ≈ 2.8. Keep 40 m when the
  app's timing against TBT distance is under test; a larger value suits recordings that should
  show a steadier map.
- Turns: radius 12 m (left/right), 8 m (sharp), 6 m (U-turn), 60 m (keep/ramp); wheel angle =
  ratio × atan(wheelbase / R) (defaults 14.5, 3.0 m: ±204° for a 90° turn), left negative.
  The car reaches √(lateral × R) at the turn, so lateral acceleration v²/R equals the style's value.
- The Virtual Console takes whole km/h and whole degrees only (HTTP 400 otherwise) and accepts
  speed in P; there is no physics interlock, so shift first.
- Pleos applies its driving restriction from these signals. Its UX restriction config
  (`adb shell dumpsys car_service`, CarUxRestrictionsManagerService) requires Distraction
  Optimization in `idling` (0 km/h, not in P) and `moving`; only `parked` (P) is free.
  An activity without `distractionOptimized` meta-data is replaced by "주행 중에는 사용할 수
  없습니다." from the moment the car leaves P, including a stop at a red light. Speed injected
  only inside the app never shows this.
- The navigation app ignores the emulator GPS (its NMEA parser rejects the checksums);
  LocationManager and Pleos FusedLocation follow `adb emu geo fix`.
- Never start `injectAudio` while no guest app is recording: emulator 37.2 crashes
  (SIGSEGV in `audio_forwarder_enable`). `emu-audio.py` checks `dumpsys audio` first.
  On-device STT recognises `say -v Yuna` speech; TTS plays on `bus0_media_out`, which
  `streamAudio` captures.
- `adb exec-out screencap` is black on Pleos; use `adb emu screenrecord` (`rec.sh` does).
  The emulator console rejects paths with spaces or non-ASCII characters.
- In split view the navigation window is not in the accessibility tree while the other app
  has focus; `navsim.py` then taps the fixed Connect-S positions and confirms start/stop in logcat.
  `uiautomator dump` takes 3-5 s while the map moves, so panel lookups run off the bridge's
  control loop.
