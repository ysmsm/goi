#!/usr/bin/env -S uv run --quiet --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["grpcio>=1.60", "grpcio-tools>=1.60"]
# ///
"""Feed speech into the emulator microphone and record the emulator's audio output
through the emulator gRPC endpoint.

Open the endpoint once per emulator boot; the port and token are read from the
emulator's discovery file:
    adb -s emulator-5554 emu grpc 8554

    emu-audio.py [-s SERIAL] say "케이크 옮겨줘"        # macOS `say` (Yuna) -> mic, once the app listens
    emu-audio.py [-s SERIAL] inject speech.wav         # 16-bit mono WAV -> mic, once the app listens
    emu-audio.py [-s SERIAL] record out.wav [-t 60]    # emulator output -> WAV until SIGINT/SIGTERM or -t

inject/say wait until a guest app is recording (dumpsys audio) and refuse otherwise:
starting injectAudio while the guest microphone is closed crashes emulator 37.2
(SIGSEGV in audio_forwarder_enable). record pads silent stretches so the WAV
stays aligned with wall-clock time (and with a screen recording started together).
"""

import argparse
import os
import signal
import subprocess
import sys
import tempfile
import threading
import time
import wave
from pathlib import Path

PROTO = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "emulator/lib/emulator_controller.proto"
CACHE = Path.home() / ".cache/pleos-drive-sim/grpc"


def stubs():
    """Compile emulator_controller.proto once into a cache and import it."""
    out = CACHE
    if not (out / "emulator_controller_pb2_grpc.py").exists() or (out / "emulator_controller_pb2.py").stat().st_mtime < PROTO.stat().st_mtime:
        import grpc_tools
        from grpc_tools import protoc

        out.mkdir(parents=True, exist_ok=True)
        include = Path(grpc_tools.__file__).parent / "_proto"
        rc = protoc.main(["protoc", f"-I{PROTO.parent}", f"-I{include}", f"--python_out={out}", f"--grpc_python_out={out}", str(PROTO)])
        if rc != 0:
            sys.exit(f"protoc failed for {PROTO}")
    sys.path.insert(0, str(out))
    import emulator_controller_pb2 as pb
    import emulator_controller_pb2_grpc as rpc

    return pb, rpc


def discovery(serial):
    """Find the gRPC port and token of SERIAL in the emulator discovery files (pid_*.ini)."""
    port = serial.rsplit("-", 1)[-1]
    dirs = [Path.home() / "Library/Caches/TemporaryItems/avd/running", Path(os.environ.get("TMPDIR", "/tmp")) / "avd/running"]
    for d in dirs:
        for ini in d.glob("pid_*.ini"):
            kv = dict(line.split("=", 1) for line in ini.read_text().splitlines() if "=" in line)
            if kv.get("port.serial") == port and kv.get("grpc.port"):
                return kv["grpc.port"], kv.get("grpc.token")
    sys.exit(f"{serial} has no gRPC endpoint: run `adb -s {serial} emu grpc 8554` first")


class _Auth:
    """Adds the bearer token to every call (unary and streaming)."""

    def __new__(cls, token):
        import grpc

        class Auth(grpc.UnaryUnaryClientInterceptor, grpc.UnaryStreamClientInterceptor, grpc.StreamUnaryClientInterceptor):
            md = [("authorization", f"Bearer {token}")]

            def _d(self, d):
                return d._replace(metadata=list(d.metadata or []) + self.md)

            def intercept_unary_unary(self, cont, d, req):
                return cont(self._d(d), req)

            def intercept_unary_stream(self, cont, d, req):
                return cont(self._d(d), req)

            def intercept_stream_unary(self, cont, d, it):
                return cont(self._d(d), it)

        return Auth()


def connect(serial):
    import grpc

    pb, rpc = stubs()
    port, token = discovery(serial)
    channel = grpc.insecure_channel(f"127.0.0.1:{port}")
    if token:
        channel = grpc.intercept_channel(channel, _Auth(token))
    return pb, rpc.EmulatorControllerStub(channel)


def mic_open(serial):
    """True while a guest app records from the microphone (a config under RecordActivityMonitor)."""
    out = subprocess.run(["adb", "-s", serial, "shell", "dumpsys", "audio"], capture_output=True, text=True).stdout
    section = out.split("RecordActivityMonitor", 1)[-1].split("Events log", 1)[0]
    return "source client=" in section


def wait_mic(serial, seconds):
    end = time.time() + seconds
    while time.time() < end:
        if mic_open(serial):
            return True
        time.sleep(0.2)
    return False


def inject(serial, path, lead=0.4, tail=1.6, wait=8.0):
    if not wait_mic(serial, wait):
        sys.exit(f"mic is not open on {serial} (waited {wait:.0f}s): make the app listen first")
    pb, stub = connect(serial)
    with wave.open(str(path), "rb") as w:
        if w.getsampwidth() != 2 or w.getnchannels() != 1:
            sys.exit("inject needs 16-bit mono WAV (afconvert -f WAVE -d LEI16@16000 -c 1 in.aiff out.wav)")
        rate, frames = w.getframerate(), w.readframes(w.getnframes())
    # Real-time mode, paced 20 ms per packet: the call ends on time even if the app stops
    # listening halfway (the default mode blocks until the guest reads every frame).
    fmt = pb.AudioFormat(samplingRate=rate, channels=pb.AudioFormat.Mono, format=pb.AudioFormat.AUD_FMT_S16, mode=pb.AudioFormat.MODE_REAL_TIME)
    # Leading silence covers recorder start-up; trailing silence lets the recognizer detect the end of speech.
    data = b"\0\0" * int(rate * lead) + frames + b"\0\0" * int(rate * tail)
    chunk = rate // 50 * 2

    def packets():
        start = time.time()
        for n, i in enumerate(range(0, len(data), chunk)):
            yield pb.AudioPacket(format=fmt, timestamp=int(time.time() * 1e6), audio=data[i : i + chunk])
            time.sleep(max(0.0, start + (n + 1) * 0.02 - time.time()))

    t0 = time.time()
    stub.injectAudio(packets(), timeout=len(data) / 2 / rate + 10)
    print(f"injected {len(frames) / 2 / rate:.2f}s of speech in {time.time() - t0:.2f}s")


def record(serial, path, seconds=None, rate=44100):
    pb, stub = connect(serial)
    fmt = pb.AudioFormat(samplingRate=rate, channels=pb.AudioFormat.Stereo, format=pb.AudioFormat.AUD_FMT_S16)
    bytes_per_sec = rate * 4
    stop = threading.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: stop.set())
    out = wave.open(str(path), "wb")
    out.setnchannels(2)
    out.setsampwidth(2)
    out.setframerate(rate)
    start = time.time()
    written = 0
    lock = threading.Lock()

    def pad_to(t):
        nonlocal written
        want = int((t - start) * bytes_per_sec) // 4 * 4
        if want > written:
            out.writeframes(b"\0" * (want - written))
            written = want

    stream = stub.streamAudio(fmt)

    def pump():
        nonlocal written
        try:
            for p in stream:
                with lock:
                    if stop.is_set():
                        break
                    pad_to(time.time() - len(p.audio) / bytes_per_sec)
                    out.writeframes(p.audio)
                    written += len(p.audio)
        except Exception as e:
            if "CANCELLED" not in str(e):
                print(f"streamAudio failed: {e}", file=sys.stderr)
                stop.set()

    threading.Thread(target=pump, daemon=True).start()
    print(f"recording emulator audio to {path}", flush=True)
    while not stop.is_set() and (seconds is None or time.time() - start < seconds):
        time.sleep(0.1)
    stop.set()
    stream.cancel()
    with lock:
        pad_to(time.time())
        out.close()
    print(f"saved {written / bytes_per_sec:.1f}s to {path}")


def say(serial, text, voice="Yuna", wait=8.0):
    with tempfile.TemporaryDirectory() as d:
        aiff, wav = Path(d) / "say.aiff", Path(d) / "say.wav"
        subprocess.run(["say", "-v", voice, "-o", str(aiff), text], check=True)
        subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", str(aiff), str(wav)], check=True)
        inject(serial, wav, wait=wait)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("-s", "--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("inject", help="16-bit mono WAV into the mic")
    p.add_argument("wav")
    p.add_argument("-w", "--wait", type=float, default=8.0, help="seconds to wait for the app to open the mic")
    p = sub.add_parser("say", help="synthesize Korean speech with macOS say and inject it")
    p.add_argument("text")
    p.add_argument("--voice", default="Yuna")
    p.add_argument("-w", "--wait", type=float, default=8.0, help="seconds to wait for the app to open the mic")
    p = sub.add_parser("record", help="record emulator audio output to a WAV")
    p.add_argument("wav")
    p.add_argument("-t", "--seconds", type=float)
    a = ap.parse_args()
    if a.cmd == "inject":
        inject(a.serial, a.wav, wait=a.wait)
    elif a.cmd == "say":
        say(a.serial, a.text, a.voice, a.wait)
    else:
        record(a.serial, a.wav, a.seconds)


if __name__ == "__main__":
    main()
