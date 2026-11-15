"""Verify the compact guide against real emulator SDK events, without injecting app signals."""
import argparse
import base64
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--adb", default=r"C:\AndroidSDK\platform-tools\adb.exe")
parser.add_argument("--serial", default="emulator-5592")
parser.add_argument("--console-url", default="http://127.0.0.1:8092")
parser.add_argument("--output", required=True, type=Path)
args = parser.parse_args()
args.output.mkdir(exist_ok=True, parents=True)
checks = {"scope": "Emulator SDK integration; physical stalk operation remains unverified"}


def adb(*parts):
    return subprocess.check_output([args.adb, "-s", args.serial, *parts], timeout=25)


def shell(*parts):
    return adb("shell", shlex.join(parts)).decode("utf-8", errors="replace")


def console(path, **params):
    suffix = "?" + urllib.parse.urlencode(params) if params else ""
    request = urllib.request.Request(args.console_url + path + suffix, method="PUT" if params else "GET")
    with urllib.request.urlopen(request, timeout=8) as response:
        return json.load(response)


assert shell("getprop", "ro.kernel.qemu").strip() == "1", "Emulator-only test"
user = shell("am", "get-current-user").strip()


def ui(click=None):
    options = ("-e", "click", click) if click else ()
    raw = shell("am", "instrument", "--user", user, "-w", *options, "kr.juhyu.licensenote.driver/.UiDriver")
    if "App accessibility window not found" in raw:
        # The native blind-spot camera may cover this task when the indicator changes.
        shell("am", "start", "-n", "kr.juhyu.licensenote/.MainActivity")
        checks["nativeCameraTaskRestorations"] = checks.get("nativeCameraTaskRestorations", 0) + 1
        raw = shell("am", "instrument", "--user", user, "-w", *options, "kr.juhyu.licensenote.driver/.UiDriver")
    encoded = re.search(r"INSTRUMENTATION_RESULT: xml64=(\S+)", raw)
    if not encoded or "INSTRUMENTATION_RESULT: error=" in raw:
        raise RuntimeError(raw[:800])
    root = ET.fromstring(base64.b64decode(encoded[1]))
    return [node.get("text") for node in root.iter("node") if node.get("text")]


def wait_text(text):
    until = time.monotonic() + 18
    last = []
    while time.monotonic() < until:
        last = ui()
        if text in last:
            return last
        time.sleep(.25)
    raise AssertionError(f"Missing {text}: {last}")


def click(text):
    wait_text(text)
    ui(text)


def signals():
    return json.loads(shell("run-as", "kr.juhyu.licensenote", "--user", user, "cat", "files/sdk-state.json"))


def wait_signal(key, value):
    until = time.monotonic() + 12
    last = None
    while time.monotonic() < until:
        last = signals().get(key)
        if last and last["quality"] == "VALID" and last["value"] == value:
            return last
        time.sleep(.2)
    raise AssertionError((key, value, last))


def capture(name, texts):
    (args.output / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))
    checks[name] = texts


initial = console("/vehicle/state")
assert initial["gear"]["actual"]["value"] == "Park" and initial["controls"]["speed_kph"] == 0
initial_turn = initial["lights"]["turn_signal_mode"]
initial_wiper = initial["body"]["wipers"]["front"]["mode"]
try:
    current = ui()
    if "깜빡이·와이퍼 해 보기" not in current:
        if "내 차 빠르게 익히기" in current and "내 차 익히기" not in current:
            shell("input", "keyevent", "KEYCODE_BACK")
            current = wait_text("내 차 익히기")
        if "내 차 익히기" not in current:
            click("설명서")
        click("내 차 빠르게 익히기")
    overview = wait_text("깜빡이·와이퍼 해 보기")
    assert all(label in overview for label in ("속도", "기어", "연료", "배터리"))
    assert "다음 단계" not in overview and "속도계를 찾아요" not in overview
    capture("overview", overview)
    checks["energySignals"] = {key: signals().get(key) for key in ("fuel_percent", "battery_percent")}

    # An already-on indicator must not count as a new user operation.
    console("/vehicle/turn-signal", mode="BlinkLeft")
    wait_signal("turn", "LEFT")
    click("깜빡이·와이퍼 해 보기")
    capture("already-on-blocked", wait_text("이미 켜져 있어요. 먼저 끈 뒤 다시 켜 주세요."))
    assert "와이퍼를 한 번 작동해 보세요" not in ui()
    checks["alreadyOnDidNotAdvance"] = True

    console("/vehicle/turn-signal", mode="Off")
    wait_signal("turn", "OFF")
    console("/vehicle/turn-signal", mode="BlinkRight")
    wait_signal("turn", "RIGHT")
    capture("indicator-confirmed", wait_text("와이퍼를 한 번 작동해 보세요"))
    activation = signals().get("turn_activation")
    assert activation and activation["quality"] == "VALID" and activation["source"] == "Vehicle/TurnSignal"
    checks["indicatorActualSdkTransition"] = activation

    # Send the public emulator control through the actual SDK; never fabricate app signals.
    wiper = signals().get("wiper_front")
    checks["wiperSignal"] = wiper
    if wiper and wiper["quality"] == "UNSUPPORTED":
        capture("wiper-unsupported", wait_text("이 차량은 동작 확인을 지원하지 않아요. 자동 완료할 수 없어요."))
        checks["unsupportedWiperDidNotComplete"] = "조작을 확인했어요" not in ui()
        assert checks["unsupportedWiperDidNotComplete"]
    elif wiper and wiper["quality"] == "VALID":
        console("/vehicle/body", front_wiper="Off")
        wait_signal("wiper_front", "OFF")
        console("/vehicle/body", front_wiper="Low")
        wait_signal("wiper_front", "ON")
        capture("wiper-confirmed", wait_text("기본 조작을 확인했어요"))
        activation = signals().get("wiper_front_activation")
        assert activation and activation["quality"] == "VALID" and activation["source"] == "Vehicle/Wiper"
        checks["wiperActualSdkTransition"] = activation
    else:
        checks["wiperOperation"] = "Awaiting an actual SDK wiper activation; not validated by this script"
    checks["passed"] = True
except Exception as error:
    checks["failure"] = str(error)
    raise
finally:
    console("/vehicle/turn-signal", mode=initial_turn)
    console("/vehicle/body", front_wiper=initial_wiper)
    (args.output / "verification.json").write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding="utf-8")
