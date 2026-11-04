#!/usr/bin/env python3
"""Turn the Pleos navigation app's route simulation into vehicle signals.

The navigation simulation only moves the car inside the navigation app, at a constant
~60 km/h: VHAL speed, gear and steering stay untouched and GPS stays put. This bridge
follows the simulation in logcat and drives the vehicle through the Virtual Console HTTP
API the way a driver would, keeping both on the same position along the route:

- Positions are metres along the route. The navigation position comes from the 1 Hz
  remaining distance (SdvPlacesenseService); the car position is the integral of the
  speed the bridge sends. Maneuvers (onTBTInfo) and the destination are placed on the
  same axis.
- Speed cruises at the simulation's speed, brakes so it reaches v = sqrt(lateral * R)
  at each turn and 0 at the destination, with the style's acceleration limits.
- The steering wheel turns through each maneuver (angle from turn radius, wheelbase and
  steering ratio), so lateral acceleration v^2/R matches the turn.
- Position lock (default): when the navigation car gets more than --ahead metres in front,
  the bridge taps 정지 in the simulation panel and taps 시작 again once the car has caught
  up. Stopping the car (`stop`, `brake`) therefore also stops the navigation.
- With --gps, the navigation position (GoiNavTrace `loc` lines from a NaviHelper client
  in the app under test), moved back by the gap, is mirrored to the emulator GPS so
  FusedLocation and LocationManager move along the route.

Commands on stdin (or a FIFO given with --control), one per line:
    brake <m/s2> [to_kmh]   hard brake (default to 0 and hold until `follow`)
    launch <m/s2> [to_kmh]  hard start, then follow again
    swerve <deg> <sec>      extra steering on top (sharp swerve or lane change)
    stop                    gentle stop and hold until `follow` (e.g. a red light)
    follow                  drop overrides (and leave a reached waypoint) and follow the route
    gear P|R|N|D            shift
    quit                    stop gently, then exit

    nav-bridge.py [-s SERIAL] [--style calm|normal|brisk] [--start] [--gps] [--log t.jsonl] [--control FIFO]
"""

import argparse
import json
import math
import os
import queue
import re
import signal
import subprocess
import sys
import threading
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import navsim  # noqa: E402

STYLES = {
    # accel, decel (m/s2), lateral acceleration in turns (m/s2)
    "calm": (1.0, 1.3, 1.1),
    "normal": (1.8, 2.3, 2.2),
    "brisk": (3.0, 3.8, 3.8),
}


def maneuver_geometry(t):
    """Turn type -> (radius m, turned angle deg, side: -1 left / +1 right), or None."""
    side = -1 if (t.endswith("_L") or t.endswith("LEFT")) else (1 if (t.endswith("_R") or t.endswith("RIGHT")) else 0)
    if t in ("TURN_LEFT", "TURN_RIGHT"):
        return 12.0, 90.0, side
    if t in ("TURN_SHARP_L", "TURN_SHARP_R"):
        return 8.0, 135.0, side
    if t == "TURN_UTURN":
        return 6.0, 180.0, -1
    if t.startswith("TURN_ENTER_ROUNDABOUT"):
        return 15.0, int(t.rsplit("_", 1)[-1]) * 30.0, -1
    if t.startswith(("TURN_KEEP", "TURN_ENTER_IC", "TURN_EXIT_IC", "TURN_ENTER_UNDER", "TURN_AVOID_UNDER", "TURN_ENTER_OVER", "TURN_AVOID_OVER")) and side:
        return 60.0, 25.0, side
    return None


RE_DIST = re.compile(r"PathPoint\[0\]: .*?remaining_distance=(\d+)")
RE_TBT = re.compile(r"onTBTInfo: \[TBTInfo\(type=(\w+), distance=([\d.]+)")
RE_TRACE_TBT = re.compile(r"GoiNavTrace: tbt (\w+):(\d+)")
RE_LIMIT = re.compile(r"onCurrentRoadSpeedLimit: (\d+)")
RE_LOC = re.compile(r"GoiNavTrace: loc ([\d.\-]+) ([\d.\-]+) ([\d.\-]+)")
RE_EPOCH = re.compile(r"^\s*(\d+\.\d+)\s")


class Bridge:
    def __init__(self, a):
        self.a = a
        self.accel, self.decel, self.lateral = STYLES[a.style]
        self.lock = threading.Lock()
        # navigation, in metres along the route
        self.started = False  # a simulation has started since the bridge came up
        self.sim_on = False
        self.last_d = None  # last remaining distance
        self.prog = 0.0  # navigation progress at the last sample
        self.prog_t = 0.0
        self.dest = None  # destination position
        self.cruise = 60 / 3.6
        self.nav_running = True  # what the bridge believes the panel shows
        self.toggle = None  # (x, y) of the 정지/시작 button
        self.last_tap = 0.0
        self.tap_prog = 0.0
        self.man = None  # [kind, pos, done]
        self.limit = 0
        self.loc = None
        self.last_loc_sent = None
        # car
        self.car = 0.0  # car progress
        self.v = 0.0
        self.steer = 0.0
        self.turn = None  # (start, duration, peak_deg, v_turn)
        self.override = None
        self.swerve = None
        self.gear = None
        self.quitting = False
        self.arrived = False
        self.wp_stop = None  # waypoint position the car must stop at
        self.wp_reached = False
        self.cmds = queue.Queue()
        self.stop = threading.Event()
        self.log = open(a.log, "a") if a.log else None

    # --- inputs -----------------------------------------------------------------
    def read_logcat(self):
        # Start from "now" without clearing the buffer (other tools read it too).
        since = f"{time.time() - 1:.3f}"
        p = subprocess.Popen(
            ["adb", "-s", self.a.serial, "logcat", "-v", "epoch", "-T", since,
             "-s", "NavigationEventListener", "SdvPlacesenseService", "navi_engine", "GoiNavTrace"],
            stdout=subprocess.PIPE, text=True, errors="replace",
        )
        for line in p.stdout:
            if self.stop.is_set():
                break
            m = RE_EPOCH.match(line)
            t = float(m[1]) if m else time.time()
            with self.lock:
                self.parse(line, t)
        p.terminate()

    def nav_pos(self, now):
        """Navigation position now, extrapolated from the last sample while it is running."""
        if not self.nav_running or not self.sim_on:
            return self.prog
        return self.prog + self.cruise * min(1.2, max(0.0, now - self.prog_t))

    def begin(self, t):
        self.started = self.sim_on = True
        self.nav_running, self.arrived = True, False
        self.last_d, self.prog, self.prog_t, self.car, self.dest, self.man = None, 0.0, t, 0.0, None, None

    def parse(self, line, t):
        if "start_route_simulation" in line:
            self.begin(t)
        elif "stop_route_simulation" in line:
            self.sim_on = False
        elif (m := RE_DIST.search(line)):
            d = int(m[1])
            if not self.started:
                # Route guidance reports the distance even when standing still; only a
                # shrinking distance means the bridge came up during a running simulation.
                if self.last_d is None or not 5 < self.last_d - d <= 80:
                    self.last_d = d
                    return
                self.begin(t)
                self.last_d = d + 1
            if self.last_d is not None and d > self.last_d + 30 and self.a.stop_at_waypoints and self.dest is not None and self.car < self.dest:
                self.wp_stop = self.dest  # passed a waypoint: the car still stops there
                print(f"waypoint ahead of the car at {self.dest - self.car:.0f} m", flush=True)
            if self.last_d is not None and 0 <= self.last_d - d <= 80:
                step = self.last_d - d
                if step > 0 and t > self.prog_t:
                    v = step / (t - self.prog_t)
                    if 5 < v < 40 and self.nav_running:
                        self.cruise = 0.8 * self.cruise + 0.2 * v
                self.prog += step
            self.prog_t, self.last_d = t, d
            self.dest = self.prog + d
        elif (m := RE_TBT.search(line)) or (m := RE_TRACE_TBT.search(line)):
            kind, d = m[1], float(m[2])
            if kind in ("TURN_ARRIVE", "TURN_STRAIGHT", "TURN_UNKNOWN", "TURN_WAY", "TURN_TUNNEL"):
                return
            pos = self.nav_pos(t) + d
            cur = self.man
            if cur and cur[0] == kind and abs(cur[1] - pos) < 60:
                if not cur[2]:
                    cur[1] = pos
            else:
                self.man = [kind, pos, False]
        elif (m := RE_LIMIT.search(line)):
            self.limit = int(m[1])
        elif (m := RE_LOC.search(line)):
            self.loc = (float(m[1]), float(m[2]), float(m[3]))

    def read_commands(self, src):
        while not self.stop.is_set():
            f = open(src) if src else sys.stdin
            for line in f:
                if line.strip():
                    self.cmds.put(line.split())
            if not src:
                return
            f.close()

    # --- outputs ----------------------------------------------------------------
    def put(self, path):
        try:
            urllib.request.urlopen(urllib.request.Request(self.a.console + path, method="PUT"), timeout=2).read()
        except Exception as e:
            print(f"console {path}: {e}", file=sys.stderr)

    def set_gear(self, g):
        name = {"P": "Park", "R": "Reverse", "N": "Neutral", "D": "Drive"}.get(g.upper(), g)
        self.put(f"/vehicle/gear?gear={name}")
        self.gear = name

    def send_controls(self, a_cmd):
        kmh = self.v * 3.6
        acc = 0 if a_cmd <= 0.05 else min(100, round(12 + a_cmd * 22))
        brk = min(100, round(a_cmd * -14)) if a_cmd < -0.05 else (30 if kmh < 0.5 else 0)
        # The console accepts whole km/h and degrees only (HTTP 400 otherwise).
        self.put(f"/vehicle/controls?speed={round(kmh)}&accelerator={acc}&brake={brk}&steering={round(self.steer)}")

    def mirror_gps(self, gap):
        if not self.a.gps or not self.loc or self.loc == self.last_loc_sent:
            return
        lat, lng, heading = self.loc
        if heading and gap > 0:  # move back to where the car is
            h = math.radians(heading)
            lat -= gap * math.cos(h) / 111320
            lng -= gap * math.sin(h) / (111320 * math.cos(math.radians(lat)))
        subprocess.run(["adb", "-s", self.a.serial, "emu", "geo", "fix", f"{lng:.7f}", f"{lat:.7f}", "0", "8", f"{self.v * 1.943844:.1f}"],
                       capture_output=True, check=False)
        self.last_loc_sent = self.loc

    def locate_toggle(self):
        try:
            self.toggle = navsim.locate(self.a.serial)["toggle"]
        except SystemExit as e:
            print(f"position lock off: {e}", file=sys.stderr)
            self.toggle = None
        print(f"simulation panel button at {self.toggle}", flush=True)

    def tap_toggle(self, now, running):
        x, y = self.toggle
        subprocess.run(["adb", "-s", self.a.serial, "shell", "input", "tap", str(x), str(y)], check=False)
        self.nav_running, self.last_tap, self.tap_prog = running, now, self.prog
        print(f"nav {'resume' if running else 'pause'}", flush=True)

    # --- driver -----------------------------------------------------------------
    def wheel_deg(self, radius):
        return math.degrees(math.atan(self.a.wheelbase / radius)) * self.a.ratio

    def command(self, c, now):
        k = c[0]
        if k == "quit":
            self.quitting = True
            self.override = {"kind": "stop", "a": self.decel, "to": 0.0, "hold": True}
        elif k == "follow":
            self.override = None
            if self.wp_reached:
                self.wp_stop, self.wp_reached = None, False
        elif k == "stop":
            self.override = {"kind": "stop", "a": self.decel, "to": 0.0, "hold": True}
        elif k in ("brake", "launch"):
            to = float(c[2]) / 3.6 if len(c) > 2 else (0.0 if k == "brake" else self.cruise)
            self.override = {"kind": k, "a": float(c[1]), "to": to, "hold": k == "brake" and to == 0}
        elif k == "swerve":
            self.swerve = (now, float(c[2]), float(c[1]))
        elif k == "gear":
            self.set_gear(c[1])
        print(f"cmd {' '.join(c)}", flush=True)

    def step(self, now, dt):
        while not self.cmds.empty():
            self.command(self.cmds.get(), now)
        with self.lock:
            target = 0.0
            d_dest = None
            if self.started and not self.arrived:
                target = self.cruise
                if self.a.obey_limit and self.limit > 0:
                    target = min(target, self.limit / 3.6)
                if self.dest is not None:
                    d_dest = self.dest - self.car
                    target = min(target, math.sqrt(2 * self.decel * 0.9 * max(d_dest - 3, 0)))
                    if d_dest < 4 and self.v < 0.3 and not self.sim_on:
                        self.arrived = True
                        print("arrived", flush=True)
                if self.wp_stop is not None:
                    d_w = self.wp_stop - self.car
                    target = min(target, math.sqrt(2 * self.decel * 0.9 * max(d_w - 3, 0)))
                    if d_w < 5 and self.v < 0.3 and not self.wp_reached:
                        self.wp_reached = True
                        print("waypoint reached (send `follow` to drive on)", flush=True)
                m = self.man
                if m and not m[2] and (geo := maneuver_geometry(m[0])):
                    radius, angle, side = geo
                    v_turn = math.sqrt(self.lateral * radius)
                    d_turn = m[1] - self.car
                    target = min(target, math.sqrt(v_turn ** 2 + 2 * self.decel * 0.9 * max(d_turn, 0)))
                    if d_turn <= 0 and self.turn is None:
                        dur = max(2.5, math.radians(angle) * radius / max(min(self.v, v_turn), 2.0))
                        self.turn = (now, dur, side * self.wheel_deg(radius), v_turn)
                        m[2] = True
            gap = self.nav_pos(now) - self.car
            sim_on = self.sim_on
        # overrides
        a_up, a_down = self.accel, self.decel
        o = self.override
        if o:
            if o["kind"] in ("brake", "stop"):
                target, a_down = min(target, o["to"]), o["a"]
                if self.v <= o["to"] + 0.05 and not o["hold"]:
                    self.override = None
            elif o["kind"] == "launch":
                target, a_up = o["to"], o["a"]
                if self.v >= o["to"] - 0.05:
                    self.override = None
        if self.turn:
            start, dur, peak, v_turn = self.turn
            target = min(target, v_turn)
            k = (now - start) / dur
            if k >= 1:
                self.turn, self.steer = None, 0.0
            else:
                self.steer = peak * min(1.0, k / 0.25, (1 - k) / 0.25)
        else:
            self.steer = 0.0
        if self.swerve:
            s0, sd, deg = self.swerve
            k = (now - s0) / sd
            if k >= 1:
                self.swerve = None
            else:
                self.steer += deg * math.sin(math.pi * k)
        self.steer = max(-540.0, min(540.0, self.steer))
        dv = max(-a_down * dt, min(a_up * dt, target - self.v))
        self.v = max(0.0, self.v + dv)
        self.car += self.v * dt
        if self.a.auto_gear and target > 0.1 and self.gear != "Drive" and not self.quitting:
            self.set_gear("D")
        self.send_controls(dv / dt)
        # position lock
        if self.toggle and sim_on and now - self.last_tap > 1.5:
            if self.nav_running and gap > self.a.ahead:
                self.tap_toggle(now, False)
            elif not self.nav_running and gap < self.a.behind:
                self.tap_toggle(now, True)
            elif not self.nav_running and now - self.last_tap > 4 and self.prog - self.tap_prog > 25:
                print("nav did not pause; looking for the panel again", flush=True)
                self.nav_running = True
                self.locate_toggle()
        if self.quitting and self.v < 0.05:
            self.stop.set()
        return target, gap, d_dest

    def status(self, now, target, gap, d_dest):
        m = self.man
        nxt = f"{m[0]}@{m[1] - self.car:.0f}m" if m and not m[2] else "-"
        phase = self.override["kind"] if self.override else ("turn" if self.turn else ("drive" if self.started and not self.arrived else "idle"))
        rec = {"t": round(now, 2), "phase": phase, "kmh": round(self.v * 3.6, 1), "target_kmh": round(target * 3.6, 1),
               "steer": round(self.steer), "gap_m": round(gap, 1), "nav": "run" if self.nav_running else "pause",
               "next": nxt, "dest_m": None if d_dest is None else round(d_dest), "limit": self.limit, "loc": self.loc}
        print(f"{time.strftime('%H:%M:%S')} {phase:6} cmd={rec['kmh']:5.1f} steer={rec['steer']:4d} gap={rec['gap_m']:5.1f} "
              f"nav={rec['nav']} next={nxt} dest={rec['dest_m']}", flush=True)
        if self.log:
            self.log.write(json.dumps(rec, ensure_ascii=False) + "\n")
            self.log.flush()

    def run(self):
        threading.Thread(target=self.read_logcat, daemon=True).start()
        threading.Thread(target=self.read_commands, args=(self.a.control,), daemon=True).start()
        for sig in (signal.SIGINT, signal.SIGTERM):
            signal.signal(sig, lambda *_: self.cmds.put(["quit"]))

        def panel():  # UI dumps take seconds while the map moves: keep them off the control loop
            if self.a.lock:
                self.locate_toggle()
            if self.a.start:
                subprocess.run([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "navsim.py"), "-s", self.a.serial, "start"], check=False)

        threading.Thread(target=panel, daemon=True).start()
        dt = 1.0 / self.a.rate
        last_status = 0.0
        nxt = time.time()
        deadline = None
        while not self.stop.is_set():
            now = time.time()
            target, gap, d_dest = self.step(now, dt)
            if self.quitting:
                deadline = deadline or now + 30
                if now > deadline:
                    break
            if now - last_status >= 1.0:
                self.status(now, target, gap, d_dest)
                self.mirror_gps(gap)
                last_status = now
            nxt += dt
            time.sleep(max(0.0, nxt - time.time()))
        self.v, self.steer = 0.0, 0.0
        self.send_controls(0)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("-s", "--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    ap.add_argument("--console", default="http://127.0.0.1:8088", help="Virtual Console base URL")
    ap.add_argument("--style", choices=STYLES, default="calm")
    ap.add_argument("--start", action="store_true", help="start the simulation (navsim.py start) after locating the panel")
    ap.add_argument("--no-lock", dest="lock", action="store_false", help="let the navigation run ahead (no pause/resume taps)")
    ap.add_argument("--ahead", type=float, default=40.0, help="pause the navigation when it is this many metres ahead")
    ap.add_argument("--behind", type=float, default=8.0, help="resume the navigation when the gap is below this")
    ap.add_argument("--gps", action="store_true", help="mirror GoiNavTrace `loc` lines to the emulator GPS")
    ap.add_argument("--stop-at-waypoints", action="store_true", help="stop at each waypoint and wait for `follow`")
    ap.add_argument("--obey-limit", action="store_true", help="cap speed at the road speed limit")
    ap.add_argument("--no-auto-gear", dest="auto_gear", action="store_false", help="do not shift to D when moving off")
    ap.add_argument("--control", help="FIFO/file to read commands from instead of stdin")
    ap.add_argument("--log", help="append a JSON line per second (timeline)")
    ap.add_argument("--rate", type=float, default=5.0, help="control updates per second")
    ap.add_argument("--ratio", type=float, default=14.5, help="steering ratio")
    ap.add_argument("--wheelbase", type=float, default=3.0, help="wheelbase in m")
    Bridge(ap.parse_args()).run()


if __name__ == "__main__":
    main()
