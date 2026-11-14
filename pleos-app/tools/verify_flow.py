"""Exercise real UI and public SDK inputs on an emulator; preserve all existing app data."""
import argparse
import json
import re
import subprocess
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import base64
import shlex
from pathlib import Path
from datetime import datetime, timezone

parser = argparse.ArgumentParser()
parser.add_argument("--adb", default=r"C:\AndroidSDK\platform-tools\adb.exe")
parser.add_argument("--serial", default="emulator-5580")
parser.add_argument("--output", required=True)
parser.add_argument("--restore-lat")
parser.add_argument("--restore-lon")
parser.add_argument("--width-label", help="Label saved with each capture, for example wide or narrow")
parser.add_argument("--expected-width", type=int, help="Assert the actual app window width in screenshot pixels")
args = parser.parse_args()
out = Path(args.output)
out.mkdir(parents=True, exist_ok=True)
checks = {}
last_app_bounds = None

def adb(*parts):
    return subprocess.check_output([args.adb, "-s", args.serial, *parts], timeout=20).decode("utf-8", errors="replace")

assert adb("shell", "getprop", "ro.kernel.qemu").strip() == "1", "Emulator-only test"
user = adb("shell", "am", "get-current-user").strip()

def device_json(name, optional=False):
    last_error = None
    for _ in range(8):
        try:
            return json.loads(adb("shell", "run-as", "kr.juhyu.licensenote", "--user", user, "cat", "files/" + name))
        except (ValueError, subprocess.CalledProcessError) as error:
            if optional and isinstance(error, subprocess.CalledProcessError):
                return None
            last_error = error
            time.sleep(.2)
    raise AssertionError("Unreadable " + name) from last_error

def wait_report(predicate, description, timeout=20):
    until = time.monotonic() + timeout
    last = None
    while time.monotonic() < until:
        last = device_json("last-report.json")
        if predicate(last):
            return last
        time.sleep(.2)
    raise AssertionError(f"Report did not reach {description}: {last}")

def request(path, **params):
    suffix = "?" + urllib.parse.urlencode(params) if params else ""
    req = urllib.request.Request("http://127.0.0.1:8088" + path + suffix, method="PUT" if params else "GET")
    return json.load(urllib.request.urlopen(req, timeout=10))

def probe(click_text=None):
    command = ["am", "instrument", "--user", user, "-w"]
    if click_text is not None:
        command += ["-e", "click", click_text]
    command += ["kr.juhyu.licensenote.driver/.UiDriver"]
    for attempt in range(8):
        result = adb("shell", " ".join(shlex.quote(p) for p in command))
        encoded = re.search(r"INSTRUMENTATION_RESULT: xml64=([^\r\n]+)", result)
        if encoded or "App accessibility window not found" not in result:
            break
        focus_visible_app_window()
        time.sleep(.5)
    if not encoded:
        raise AssertionError("UI driver returned no snapshot: " + result)
    root = ET.fromstring(base64.b64decode(encoded.group(1)))
    if click_text is not None and "INSTRUMENTATION_RESULT: error=" in result:
        raise AssertionError(result)
    return root

def nodes():
    return probe()

def focus_visible_app_window():
    # AAOS can keep both activities resumed while input focus stays with navigation.
    if last_app_bounds is not None:
        x1, y1, x2, y2 = last_app_bounds
        adb("shell", "input", "tap", str(x1 + min(250, (x2-x1)//3)), str(y1+80))

def click(text, optional=False):
    root = nodes()
    candidates = [n for n in root.iter("node") if n.get("text") == text and n.get("enabled") == "true"]
    if not candidates:
        if optional:
            return False
        raise AssertionError("UI button missing: " + text + "; visible=" + str([n.get('text') for n in root.iter('node') if n.get('text')]))
    probe(click_text=text)
    print("Clicked:", text, flush=True)
    time.sleep(.6)
    return True

def wait_ui(required_text=(), any_text=(), timeout=20):
    until = time.monotonic() + timeout
    while True:
        root = nodes()
        visible = {n.get("text") for n in root.iter("node") if n.get("text")}
        if set(required_text).issubset(visible) and (not any_text or set(any_text).intersection(visible)):
            return root
        if time.monotonic() >= until:
            raise AssertionError(f"UI is not ready: required={required_text}; any={any_text}; visible={visible}")
        time.sleep(.2)

def capture(name, required_text=()):
    global last_app_bounds
    # Read the live app window before capturing pixels; never reuse a previous XML dump.
    root = wait_ui(required_text=required_text)
    window = root.find("node")
    bounds = list(map(int, re.findall(r"-?\d+", window.get("bounds", "")))) if window is not None else []
    assert len(bounds) == 4, f"No actual app window bounds for {name}"
    width, height = bounds[2] - bounds[0], bounds[3] - bounds[1]
    last_app_bounds = bounds
    split_restored = False
    if name == "07-restored-result" and args.expected_width == 841 and width == 1682:
        # A cold task starts in the launcher's wide slot. Reapply the user's
        # system split; this is test setup, not app-driven window placement.
        adb("shell", "input", "swipe", str(bounds[2]+11), str((bounds[1]+bounds[3])//2), str(bounds[0]+841+12), str((bounds[1]+bounds[3])//2), "900")
        time.sleep(1)
        focus_visible_app_window()
        root = wait_ui(required_text=required_text)
        bounds = list(map(int, re.findall(r"-?\d+", root.find("node").get("bounds", ""))))
        width, height = bounds[2]-bounds[0], bounds[3]-bounds[1]
        last_app_bounds = bounds
        split_restored = True
    if args.expected_width is not None:
        assert abs(width - args.expected_width) <= 1, f"Unexpected app width: {width}, expected {args.expected_width}"
    (out / (name + ".xml")).write_text(ET.tostring(root, encoding="unicode"), encoding="utf-8")
    pixels = subprocess.check_output([args.adb, "-s", args.serial, "exec-out", "screencap", "-p"], timeout=30)
    assert pixels.startswith(b"\x89PNG\r\n\x1a\n"), "Device did not return PNG pixels"
    (out / (name + ".png")).write_bytes(pixels)
    (out / (name + ".metadata.json")).write_text(json.dumps({
        "capturedAtHostUtc": datetime.now(timezone.utc).isoformat(),
        "widthLabel": args.width_label, "appBounds": bounds, "appWidthPixels": width,
        "systemSplitReappliedAfterColdStart": split_restored,
        "appHeightPixels": height, "requiredText": list(required_text),
    }, ensure_ascii=False, indent=2), encoding="utf-8")

def wait_signal(key, predicate, timeout=12):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        v = device_json("sdk-state.json").get(key, {})
        if v.get("quality") == "VALID" and predicate(v):
            return v
        time.sleep(.3)
    raise AssertionError(f"Signal not received: {key}: {v}")

baseline = request("/vehicle/state")
wait_signal("turn_subscription", lambda v: v.get("quality") == "VALID" and v.get("value") == "READY")
old_location = device_json("sdk-state.json").get("location", {}).get("value")
try:
    request("/vehicle/controls", speed=0, accelerator=0, brake=0)
    request("/vehicle/gear", gear="Park")
    request("/vehicle/body", door_fl=0, door_fr=0, door_rl=0, door_rr=0)
    request("/vehicle/turn-signal", mode="Off")
    adb("emu", "geo", "fix", "127.033", "37.482")
    adb("shell", "am", "start", "-n", "kr.juhyu.licensenote/.MainActivity")
    wait_ui(any_text=("이미 운전면허가 있어요", "다음 코스 고르기", "직접 확인할게요", "벨트를 맸어요", "다음", "이 코스로 출발"))
    click("이미 운전면허가 있어요", optional=True)
    click("다음 코스 고르기", optional=True)
    if click("직접 확인할게요", optional=True) or click("벨트를 맸어요", optional=True) or click("다음", optional=True):
        click("잘 보여요")
        click("코스 고르기")
    capture("01-course", ("경로 미리보기", "이 코스로 출발"))
    previous_report = device_json("last-report.json", optional=True)
    previous_id = previous_report.get("id") if previous_report else None
    click("이 코스로 출발")
    started = wait_report(lambda r: r.get("id") != previous_id and r.get("phase") == "RECORDING", "a new recording session")
    sid = started['id']
    wait_signal("location", lambda v: v.get("origin") == "CALLBACK" and v.get("quality") == "VALID" and abs(float(v['value'].split(',')[0]) - 37.482) < .0001)
    request("/vehicle/gear", gear="Drive")
    request("/vehicle/controls", speed=18, accelerator=0, brake=0)
    wait_signal("gear", lambda v: v.get("value") == "8")
    before = wait_report(lambda r: r.get("id") == sid and r.get("phase") == "RECORDING", "recording before navigation")
    adb("shell", "am", "start", "-n", "ai.umos.maps.android.navigation.app/.MainActivity")
    time.sleep(5)
    background = wait_report(lambda r: r.get("id") == sid and r.get("sampleCount", 0) > before['sampleCount'], "continued background recording")
    assert background['sampleCount'] > before['sampleCount'], "Recording stopped while native navigation was foreground"
    checks["native_navigation_foreground_collection"] = {"before": before['sampleCount'], "after": background['sampleCount']}
    adb("shell", "am", "start", "-n", "kr.juhyu.licensenote/.MainActivity")
    focus_visible_app_window()
    capture("02-driving", ("운전 연습", "에뮬레이터 검증 코스"))
    visible = [n.get("text") for n in nodes().iter("node")]
    assert "연습을 마칠게요" not in visible, "Moving UI exposed end/review control"
    checks["moving_review_locked"] = True
    for i in range(1, 17):
        adb("emu", "geo", "fix", "127.033", f"{37.482 + i * .0001:.6f}")
        time.sleep(1.15)
    time.sleep(3)
    request("/vehicle/controls", speed=0, accelerator=0, brake=0)
    request("/vehicle/gear", gear="Park")
    wait_signal("speed", lambda v: abs(float(v['value'])) < .05)
    wait_signal("gear", lambda v: v.get("value") == "4")
    wait_signal("ux", lambda v: v.get("value") == "false,0")
    time.sleep(2)
    recorded = device_json("last-report.json")
    assert recorded.get("passage", {}).get("exitMs"), "No ordered SDK location passage"
    checks["ordered_location_passage"] = recorded['passage']
    click("연습을 마칠게요")
    review = wait_report(lambda r: r.get("id") == sid and r.get("phase") == "REVIEW", "saved review session")
    capture("03-review", ("주차 후 확인", "왼쪽으로 바꿨어요"))
    click("왼쪽으로 바꿨어요")
    report = wait_report(lambda r: r.get("id") == sid and r.get("phase") == "COMPLETE" and r.get("observation") == "LEFT" and r.get("revision", 0) > review['revision'], "confirmed left-change observation")
    (out / "confirmed-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    assert report['assessment']['mistake'], report
    assert report['assessment']['score'] in (None, 93), report
    checks["confirmed_error"] = report['assessment']
    capture("04-result", ("연습 결과", "오답노트 보기"))
    click("오답노트 보기")
    capture("05-notebook", ("오답노트", "차로 변경 전 깜빡이"))
    click("다음 연습에 반영하기")
    recommended = wait_report(lambda r: r.get("id") == sid and r.get("nextPractice") and r.get("revision", 0) > report['revision'], "saved next practice")
    capture("06-next-course", ("차로 변경 복습", "이 코스로 출발"))
    checks["next_practice_saved"] = True
    sid = report['id']
    adb("shell", "am", "force-stop", "kr.juhyu.licensenote")
    adb("shell", "am", "start", "-n", "kr.juhyu.licensenote/.MainActivity")
    focus_visible_app_window()
    capture("07-restored-result", ("연습 결과", "오답노트 보기"))
    restored = wait_report(lambda r: r.get("id") == sid and r.get("phase") == "COMPLETE" and r.get("nextPractice") and r.get("revision") == recommended['revision'], "restored result after app restart")
    assert restored['id'] == sid and restored['nextPractice']
    checks["restart_restored_session_and_recommendation"] = True
    click("확인 내용 수정")
    capture("07a-correction-review", ("주차 후 확인", "왼쪽으로 바꿨어요"))
    click("왼쪽으로 바꾸지 않았어요")
    corrected = wait_report(lambda r: r.get("id") == sid and r.get("observation") == "NOT_LEFT" and r.get("revision", 0) > restored['revision'], "corrected lane observation")
    assert corrected['id'] == sid and corrected['assessment']['status'] == "NO_DEDUCTION" and not corrected['assessment']['mistake']
    assert not corrected['nextPractice'], "Corrected error still drives next-practice recommendation"
    checks["observer_correction_removes_error"] = corrected['assessment']
    capture("08-no-deduction", ("연습 결과", "이 구간은\n감점하지 않았어요"))
    click("확인 내용 수정")
    capture("08a-unseen-review", ("주차 후 확인", "보지 못했어요"))
    click("보지 못했어요")
    unseen = wait_report(lambda r: r.get("id") == sid and r.get("observation") == "UNSEEN" and r.get("revision", 0) > corrected['revision'], "unseen observation")
    assert unseen['assessment']['status'] == "HELD" and not unseen['assessment']['mistake']
    checks["unseen_is_unassessed"] = unseen['assessment']
    capture("09-unseen-held", ("이번 점수는 보류했어요", "기록 보기"))
    click("확인 내용 수정")
    capture("09a-final-review", ("주차 후 확인", "왼쪽으로 바꿨어요"))
    click("왼쪽으로 바꿨어요")
    confirmed_again = wait_report(lambda r: r.get("id") == sid and r.get("observation") == "LEFT" and r.get("revision", 0) > unseen['revision'], "restored confirmed error")
    assert confirmed_again['assessment']['mistake'], confirmed_again
    checks["restored_confirmed_error"] = confirmed_again['assessment']
finally:
    b = baseline['body']['doors']
    restore_errors = []
    for path, values in (
        ("/vehicle/controls", dict(speed=0, accelerator=0, brake=0)),
        ("/vehicle/gear", dict(gear="Park")),
        ("/vehicle/body", dict(door_fl=int(b['front_left']), door_fr=int(b['front_right']), door_rl=int(b['rear_left']), door_rr=int(b['rear_right']))),
        ("/vehicle/turn-signal", dict(mode=baseline.get('lights', {}).get('turn_signal_mode', 'Off'))),
    ):
        try:
            request(path, **values)
        except Exception as error:
            restore_errors.append(path + ": " + str(error))
    if old_location:
        lat, lon, *_ = old_location.split(',')
        try:
            adb("emu", "geo", "fix", lon, lat)
        except Exception as error:
            restore_errors.append("GPS restore: " + str(error))
    elif args.restore_lat and args.restore_lon:
        try:
            adb("emu", "geo", "fix", args.restore_lon, args.restore_lat)
        except Exception as error:
            restore_errors.append("GPS restore: " + str(error))
    checks['restoration_complete'] = not restore_errors
    checks['restore_errors'] = restore_errors
    (out / "flow-checks.json").write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding="utf-8")
assert not restore_errors, restore_errors
print(json.dumps({"passed": list(checks)}, ensure_ascii=False))
