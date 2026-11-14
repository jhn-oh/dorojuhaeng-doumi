"""Real emulator -> public Vehicle/AAOS/FusedLocation SDK -> app evidence smoke test."""
import argparse
import json
import subprocess
import time
import urllib.parse
import urllib.request
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--adb", default=r"C:\AndroidSDK\platform-tools\adb.exe")
parser.add_argument("--serial", default="emulator-5580")
parser.add_argument("--output", required=True)
args = parser.parse_args()
out = Path(args.output)
out.mkdir(parents=True, exist_ok=True)

def adb(*parts):
    return subprocess.check_output([args.adb, "-s", args.serial, *parts], timeout=15).decode("utf-8", errors="replace")

assert adb("shell", "getprop", "ro.kernel.qemu").strip() == "1", "Emulator-only test"
user = adb("shell", "am", "get-current-user").strip()

def request(path, **params):
    suffix = "?" + urllib.parse.urlencode(params) if params else ""
    req = urllib.request.Request("http://127.0.0.1:8088" + path + suffix, method="PUT" if params else "GET")
    return json.load(urllib.request.urlopen(req, timeout=10))

def state():
    return json.loads(adb("shell", "run-as", "kr.juhyu.licensenote", "--user", user, "cat", "files/sdk-state.json"))

def wait(key, predicate, timeout=12):
    until = time.monotonic() + timeout
    last = None
    while time.monotonic() < until:
        try:
            last = state().get(key, {})
            if last.get("quality") == "VALID" and predicate(last.get("value")):
                return last
        except (ValueError, subprocess.SubprocessError):
            pass
        time.sleep(.25)
    raise AssertionError(f"{key}: expected value not received; last={last}")

console_before = request("/vehicle/state")
sdk_before = state()
(out / "console-before.json").write_text(json.dumps(console_before, indent=2), encoding="utf-8")
(out / "sdk-before.json").write_text(json.dumps(sdk_before, indent=2), encoding="utf-8")
checks = {}
try:
    checks["belt_initial"] = wait("belt", lambda v: v in ("true", "false"))
    checks["door_initial"] = wait("door:ROW_1_LEFT", lambda v: v in ("0", "1"))
    request("/vehicle/body", door_fl=1)
    checks["door_open"] = wait("door:ROW_1_LEFT", lambda v: v == "1")
    request("/vehicle/body", door_fl=0)
    checks["door_close"] = wait("door:ROW_1_LEFT", lambda v: v == "0")
    for mode, expected in (("BlinkLeft", "LEFT"), ("BlinkRight", "RIGHT"), ("Off", "OFF")):
        request("/vehicle/turn-signal", mode=mode)
        checks["turn_" + expected] = wait("turn", lambda v, expected=expected: v == expected)
    request("/vehicle/gear", gear="Drive")
    checks["gear_drive"] = wait("gear", lambda v: v == "8")
    request("/vehicle/controls", speed=36, accelerator=0, brake=0)
    checks["speed_36kph"] = wait("speed", lambda v: abs(float(v) - 10) < .2)
    checks["ux_moving"] = wait("ux", lambda v: v.startswith("true,"))
    request("/vehicle/controls", speed=0, accelerator=0, brake=0)
    request("/vehicle/gear", gear="Park")
    checks["parked"] = wait("gear", lambda v: v == "4")
    checks["ux_parked"] = wait("ux", lambda v: v == "false,0")
    adb("emu", "geo", "fix", "127.033", "37.482")
    checks["fused_location"] = wait("location", lambda v: v and abs(float(v.split(',')[0]) - 37.482) < .0001 and abs(float(v.split(',')[1]) - 127.033) < .0001)
finally:
    original_controls = console_before["controls"]
    request("/vehicle/controls", speed=0, accelerator=0, brake=0)
    request("/vehicle/gear", gear="Park")
    request("/vehicle/body", door_fl=int(console_before["body"]["doors"]["front_left"]))
    request("/vehicle/turn-signal", mode=console_before.get("lights", {}).get("turn_signal_mode", "Off"))
    if sdk_before.get("location", {}).get("value"):
        lat, lon, *_ = sdk_before["location"]["value"].split(',')
        adb("emu", "geo", "fix", lon, lat)
    # Original baseline is parked/stopped; never leave the test with moving inputs.
    if console_before["gear"]["actual"]["value"] != "Park" or original_controls["speed_kph"]:
        checks["restoration_note"] = "Test ended parked and stopped; original motion was not re-applied."
    time.sleep(1)
    evidence = adb("shell", "run-as", "kr.juhyu.licensenote", "--user", user, "cat", "files/sdk-evidence.jsonl")
    (out / "sdk-events.jsonl").write_text(evidence, encoding="utf-8")
    callbacks = [json.loads(line) for line in evidence.splitlines() if line.startswith('{') and json.loads(line).get("origin") == "CALLBACK"]
    checks["callback_keys"] = sorted({c["key"] for c in callbacks})
    (out / "sdk-after.json").write_text(json.dumps(state(), indent=2), encoding="utf-8")
    (out / "sdk-checks.json").write_text(json.dumps(checks, indent=2), encoding="utf-8")
print(json.dumps({"completed_checks": list(checks), "callback_keys": checks["callback_keys"]}, ensure_ascii=False))
