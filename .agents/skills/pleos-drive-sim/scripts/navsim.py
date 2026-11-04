#!/usr/bin/env python3
"""Drive the Pleos navigation app's hidden route-simulation panel.

The panel ("주행 시뮬레이션": 시작/정지 toggle, 종료 and a progress bar) opens with a long
press on the navigation map's settings (gear) button while route guidance is active.

    navsim.py [-s SERIAL] start      # open the panel if needed and start (or resume)
    navsim.py [-s SERIAL] pause      # 정지: the navigation car stops where it is
    navsim.py [-s SERIAL] resume     # 시작 again
    navsim.py [-s SERIAL] stop       # 종료: end the simulation (route guidance keeps running)
    navsim.py [-s SERIAL] status     # running | paused | closed | no-route | hidden

When another app shares the screen (split view) and has focus, the navigation window is
not in the accessibility tree. The panel sits at the same place in both layouts, so the
script then taps fixed Connect-S (2560x1440) positions and checks the result in logcat
(navi_engine start/stop_route_simulation); pause and resume cannot be confirmed that way.
"""

import argparse
import os
import re
import subprocess
import sys
import time

NAV = "ai.umos.maps.android.navigation.app"
# Connect-S 2560x1440 positions of the settings button and the panel buttons.
FIXED = {"settings": (2473, 1238), "toggle": (2166, 772), "stop": (2354, 772)}


def adb(serial, *args):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True).stdout


def nodes(serial):
    """Text nodes of every window. A moving map keeps the UI from going idle and the dump
    then comes back empty, so retry."""
    xml = ""
    for _ in range(6):
        xml = adb(serial, "exec-out", "uiautomator", "dump", "--windows", "/dev/tty")
        if "<node" in xml:
            break
        time.sleep(0.3)
    out = []
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m[0]
        g = lambda k: (re.search(k + r'="([^"]*)"', n) or [None, ""])[1]
        b = list(map(int, re.findall(r"\d+", g("bounds"))))
        if len(b) == 4:
            out.append({"text": g("text"), "pkg": g("package"), "long": g("long-clickable") == "true", "b": b})
    return out


def center(n):
    b = n["b"]
    return (b[0] + b[2]) // 2, (b[1] + b[3]) // 2


def find(ns, text):
    return next((n for n in ns if n["text"] == text and n["pkg"] == NAV), None)


def nav_visible(ns):
    return any(n["pkg"] == NAV for n in ns)


def state(ns):
    if not nav_visible(ns):
        return "hidden"
    if not find(ns, "주행 시뮬레이션"):
        return "closed" if find(ns, "안내 종료") else "no-route"
    return "running" if find(ns, "정지") else "paused"


def tap(serial, xy):
    adb(serial, "shell", "input", "tap", str(xy[0]), str(xy[1]))


def long_press(serial, xy):
    adb(serial, "shell", "input", "swipe", str(xy[0]), str(xy[1]), str(xy[0]), str(xy[1]), "1500")


def sim_event(serial, since, word, timeout=4.0):
    """Wait for navi_engine to log `word` (start_route_simulation / stop_route_simulation) after `since`."""
    end = time.time() + timeout
    while time.time() < end:
        out = adb(serial, "logcat", "-d", "-v", "epoch", "-T", f"{since:.3f}", "-s", "navi_engine")
        if word in out:
            return True
        time.sleep(0.4)
    return False


def open_panel(serial):
    """Return the nodes with the panel open, or [] when the navigation window is hidden."""
    ns = nodes(serial)
    if not nav_visible(ns):
        return []
    if find(ns, "주행 시뮬레이션"):
        return ns
    if not find(ns, "안내 종료"):
        sys.exit("no route guidance: set a destination first")
    cands = [n for n in ns if n["pkg"] == NAV and n["long"]]
    xy = center(max(cands, key=lambda n: n["b"][2] + n["b"][3])) if cands else FIXED["settings"]
    long_press(serial, xy)
    for _ in range(10):
        time.sleep(0.4)
        ns = nodes(serial)
        if find(ns, "주행 시뮬레이션"):
            return ns
    sys.exit("simulation panel did not open")


def locate(serial):
    """Positions of the 시작/정지 toggle and 종료 (panel opened if needed)."""
    ns = open_panel(serial)
    n = find(ns, "정지") or find(ns, "시작")
    s = find(ns, "종료")
    return {"toggle": center(n) if n else FIXED["toggle"], "stop": center(s) if s else FIXED["stop"], "nodes": bool(ns)}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("-s", "--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    ap.add_argument("action", choices=["start", "resume", "pause", "stop", "status"])
    a = ap.parse_args()
    s = a.serial
    if a.action == "status":
        print(state(nodes(s)))
        return
    ns = open_panel(s)
    since = time.time() - 0.5
    if ns:  # navigation window in the tree: act on the buttons themselves
        cur = state(ns)
        if a.action in ("start", "resume") and cur == "running" or a.action == "pause" and cur == "paused":
            print(cur)
            return
        text = {"start": "시작", "resume": "시작", "pause": "정지", "stop": "종료"}[a.action]
        n = find(ns, text)
        if not n:
            sys.exit(f"'{text}' not in the panel")
        tap(s, center(n))
        time.sleep(0.8)
        print(state(nodes(s)))
        return
    # split view with another app focused: fixed positions, confirmed through logcat
    if a.action == "start":
        long_press(s, FIXED["settings"])  # opens the panel; does nothing harmful when already open
        time.sleep(1.0)
        tap(s, FIXED["toggle"])
        print("running" if sim_event(s, since, "start_route_simulation") else "start not confirmed (paused before? check the screen)")
    elif a.action == "stop":
        tap(s, FIXED["stop"])
        print("stopped" if sim_event(s, since, "stop_route_simulation") else "stop not confirmed")
    else:
        tap(s, FIXED["toggle"])
        print(f"{a.action} tapped (not confirmable in split view)")


if __name__ == "__main__":
    main()
