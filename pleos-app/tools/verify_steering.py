"""Measure official console steering inputs against actual public SDK callbacks."""
import argparse
import json
import math
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
    return subprocess.check_output([args.adb, "-s", args.serial, *parts], timeout=20).decode("utf-8", errors="replace")

assert adb("shell", "getprop", "ro.kernel.qemu").strip() == "1", "Emulator-only test"
user = adb("shell", "am", "get-current-user").strip()

def device_json(name):
    return json.loads(adb("shell", "run-as", "kr.juhyu.licensenote", "--user", user, "cat", "files/" + name))

def request(path, **params):
    suffix = "?" + urllib.parse.urlencode(params) if params else ""
    req = urllib.request.Request("http://127.0.0.1:8088" + path + suffix, method="PUT" if params else "GET")
    return json.load(urllib.request.urlopen(req, timeout=10))

baseline = request("/vehicle/state")
assert baseline["gear"]["actual"]["value"] == "Park" and baseline["controls"]["speed_kph"] == 0, "Start parked/stopped"
assert baseline["controls"]["accelerator_pct"] == 0 and baseline["controls"]["brake_pct"] == 0, "Release the emulator pedals first"
assert device_json("last-report.json").get("phase") != "RECORDING", "Finish the practice session before this independent probe"
before = device_json("sdk-state.json")
assert before.get("steering_subscription", {}).get("value") == "READY", "Steering subscription must be READY"
assert before.get("steering_capability", {}).get("quality") == "VALID", "Steering capability is required"
(out / "console-before.json").write_text(json.dumps(baseline, indent=2), encoding="utf-8")
(out / "sdk-before.json").write_text(json.dumps(before, indent=2), encoding="utf-8")
checks = {"capability": before["steering_capability"], "subscription": before["steering_subscription"]}
checks["measurement_complete"] = False
errors = []
try:
    previous_value = None
    for label, command in (("initial_center", 0), ("console_negative", -90), ("console_positive", 90), ("center", 0)):
        # /proc/uptime and Signal.receivedMs both use device boot time. A stale READ
        # snapshot is not a safe boundary for deciding which callbacks followed this command.
        since = int(float(adb("shell", "cat", "/proc/uptime").split()[0]) * 1000)
        request("/vehicle/controls", speed=0, accelerator=0, brake=0, steering=command)
        deadline = time.monotonic() + 15
        while True:
            evidence = adb("shell", "run-as", "kr.juhyu.licensenote", "--user", user, "cat", "files/sdk-evidence.jsonl")
            events = []
            for line in evidence.splitlines():
                try:
                    events.append(json.loads(line))
                except json.JSONDecodeError:
                    pass  # The debug writer may still be appending its last line.
            callbacks = [e for e in events if e.get("key") == "steering" and e.get("origin") == "CALLBACK" and e.get("quality") == "VALID" and e.get("receivedMs", -1) > since]
            observed = next((e for e in reversed(callbacks) if e.get("value") is not None and math.isfinite(float(e["value"])) and (
                abs(float(e["value"])) < .01 if command == 0 else
                abs(float(e["value"])) > .01 and (previous_value is None or abs(float(e["value"]) - previous_value) > .01)
            )), None)
            if observed:
                value = float(observed["value"])
                checks[label] = {"consoleInputDegrees": command, "afterDeviceBootMs": since, "sdkCallback": observed,
                    "sdkToConsoleRatio": value / command if command else None,
                    "directionBySdkDocument": "LEFT" if value < -.01 else "RIGHT" if value > .01 else "CENTER"}
                previous_value = value
                break
            assert time.monotonic() < deadline, f"No actual {label} SDK callback; last={callbacks[-1:]}"
            time.sleep(.2)
    negative = float(checks["console_negative"]["sdkCallback"]["value"])
    positive = float(checks["console_positive"]["sdkCallback"]["value"])
    symmetric = negative * positive < 0 and math.isclose(negative, -positive, rel_tol=.01, abs_tol=.01)
    checks["mapping"] = {"symmetricCallbacks": symmetric,
        "consoleToSdkSign": "INVERTED" if negative > 0 and positive < 0 else "SAME" if negative < 0 and positive > 0 else "INCONSISTENT",
        "negativeInputRatio": checks["console_negative"]["sdkToConsoleRatio"],
        "positiveInputRatio": checks["console_positive"]["sdkToConsoleRatio"],
        "centerReceived": abs(float(checks["center"]["sdkCallback"]["value"])) < .01}
    assert symmetric, f"Console commands did not produce symmetric opposite SDK callbacks: {negative}, {positive}"
    assert checks["mapping"]["centerReceived"], "SDK did not return to center"
    checks["measurement_complete"] = True
finally:
    try:
        request("/vehicle/controls", speed=0, accelerator=0, brake=0, steering=baseline["controls"]["steering_deg"])
        restored = request("/vehicle/state")["controls"]["steering_deg"]
        assert restored == baseline["controls"]["steering_deg"], f"Console steering not restored: {restored}"
    except Exception as error:
        errors.append(str(error))
    checks["restoration_complete"] = not errors
    checks["restore_errors"] = errors
    (out / "steering-checks.json").write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "sdk-after.json").write_text(json.dumps(device_json("sdk-state.json"), indent=2), encoding="utf-8")
    (out / "sdk-events.jsonl").write_text(adb("shell", "run-as", "kr.juhyu.licensenote", "--user", user, "cat", "files/sdk-evidence.jsonl"), encoding="utf-8")
assert not errors, errors
print(json.dumps({"passed": list(checks), "restoration_complete": True}))
