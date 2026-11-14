"""Exercise the parked manual on the Pleos emulator without starting a practice session."""
import argparse
import base64
import json
import re
import shlex
import subprocess
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", default="emulator-5580")
parser.add_argument("--adb", default=r"C:\AndroidSDK\platform-tools\adb.exe")
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--dashboard-return", action="store_true",
                    help="From departure check 1/3, 2/3 or 3/3, verify the instrument lesson and return to exactly that check.")
args = parser.parse_args()
args.output.mkdir(exist_ok=True, parents=True)
checks = {}
window_bounds = []


def adb(*parts):
    return subprocess.check_output([args.adb, "-s", args.serial, *parts], timeout=25)


def shell(*parts):
    return adb("shell", shlex.join(parts)).decode("utf-8", errors="replace")


def console(path, **params):
    suffix = "?" + urllib.parse.urlencode(params) if params else ""
    request = urllib.request.Request("http://127.0.0.1:8088" + path + suffix, method="PUT" if params else "GET")
    with urllib.request.urlopen(request, timeout=8) as response:
        return json.load(response)


def probe(*options):
    raw = shell("am", "instrument", "--user", user, "-w", *options, "kr.juhyu.licensenote.driver/.UiDriver")
    if "INSTRUMENTATION_RESULT: error=" in raw:
        error = next(line for line in raw.splitlines() if "RESULT: error=" in line)
        inventory = next((line for line in raw.splitlines() if "windowInventory=" in line), "")
        raise RuntimeError(error + " " + inventory)
    xml = re.search(r"INSTRUMENTATION_RESULT: xml64=(\S+)", raw)
    if not xml:
        raise RuntimeError(raw[:300])
    root = ET.fromstring(base64.b64decode(xml[1]))
    window_bounds[:] = [int(v) for v in re.findall(r"\d+", root.find("node").get("bounds"))]
    return root


def visible(root):
    return [node.get("text") for node in root.iter("node") if node.get("text")]


def wait_text(label, scroll=False):
    deadline = time.monotonic() + 15
    last = ""
    while time.monotonic() < deadline:
        try:
            root = probe()
            labels = visible(root)
            if label in labels:
                return root
            last = labels
            if scroll:
                probe("-e", "scroll", "forward")
        except RuntimeError as exc:
            last = str(exc)
            if "window not found" in last and len(window_bounds) == 4:
                # The native driving view can take focus from the visible app when signals change.
                x, y, _, _ = window_bounds
                shell("input", "tap", str(x + 250), str(y + 80))
        time.sleep(.2)
    raise AssertionError(f"Missing {label}; last UI: {last}")


def click(label):
    root = wait_text(label, scroll=True)
    try:
        probe("-e", "click", label)
    except RuntimeError as exc:
        if "Click rejected" not in str(exc):
            raise
        node = next(n for n in root.iter("node") if n.get("text") == label)
        left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
        shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def capture(name, root):
    (args.output / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))
    checks[name] = {"texts": visible(root), "appBounds": root.find("node").get("bounds")}


def current_signals():
    data = json.loads(shell("run-as", "kr.juhyu.licensenote", "--user", user, "cat", "files/sdk-state.json"))
    return {k: data.get(k) for k in ("speed", "gear", "ux", "turn", "belt", "safety_belt_warning", "safety_washer_warning",
                                     "guide_low_beam", "guide_high_beam", "door:ROW_1_LEFT")}


def wait_signal(key, expected, allow_unsupported=False):
    deadline = time.monotonic() + 12
    while True:
        item = current_signals().get(key) or {}
        if item.get("quality") == "VALID" and item.get("value") == expected:
            return item
        if allow_unsupported and item.get("quality") == "UNSUPPORTED":
            return item
        assert time.monotonic() < deadline, (key, expected, item)
        time.sleep(.25)


def back():
    shell("input", "keyevent", "KEYCODE_BACK")


def dashboard():
    return wait_text("내 차 익히기")

# Each novel scenario has a deliberately wrong answer and the answer that must receive positive feedback.
new_topics = (
    ("출발 준비", "사이드미러 맞추기", "오른쪽 R", "L 선택", "R 선택"),
    ("출발 준비", "문 열림 위치 찾기", "오른쪽 뒷문", "트렁크까지 확인됨", "트렁크는 따로 확인"),
    ("비·야간", "유리 습기 없애기", "뒷유리", "뒷유리 열선", "앞유리 서리 제거"),
    ("비·야간", "상향등과 하향등 쓰기", "상향등", "상향등", "하향등"),
    ("표시등·경고", "비상등 켜는 법", None, "왼쪽만 점멸", "양쪽 동시에 점멸"),
    ("표시등·경고", "타이어 경고등 읽기", "점멸 후 켜짐", "공기만 보충", "감지 시스템 점검"),
    ("주차·정차", "전자식 주차 브레이크", None, "P단만 확인", "P단과 주차 브레이크 확인"),
    ("주차·정차", "오토홀드 표시 읽기", None, "정차 유지 중", "작동 대기"),
)

assert shell("getprop", "ro.kernel.qemu").strip() == "1"
user = shell("am", "get-current-user").strip()
initial = console("/vehicle/state")
assert initial["gear"]["actual"]["value"] == "Park" and initial["controls"]["speed_kph"] == 0, "Park the emulator first"
initial_mode = initial["lights"]["turn_signal_mode"]
changed_turn = changed_gear = changed_door = changed_light = False
try:
    if "내 차 익히기" in visible(probe()):
        back()
    entry = wait_text("설명서")
    origin = visible(entry)
    if args.dashboard_return:
        assert "출발 준비" in origin, "Open the departure checklist before running --dashboard-return"
        step = next(text for text in origin if text in ("1 / 3", "2 / 3", "3 / 3"))
        report_cmd = ("run-as", "kr.juhyu.licensenote", "--user", user, "cat", "files/last-report.json")
        has_report_cmd = ("run-as", "kr.juhyu.licensenote", "--user", user, "ls", "files")
        previous = shell(*report_cmd) if "last-report.json" in shell(*has_report_cmd).splitlines() else None
        click("설명서")
        wait_text("내 차 익히기")
        click("계기판 읽기")
        wait_text("속도계를 찾아요")
        click("다음 단계")
        wait_text("단위를 함께 읽어요")
        click("다시 보기")
        wait_text("40 km/h")
        click("다음 단계")
        capture("instrument-gear", wait_text("기어 표시를 구별해요"))
        click("출발 점검 계속하기")
        resumed = wait_text(step)
        assert "출발 준비" in visible(resumed) and "내 차 익히기" not in visible(resumed)
        current = shell(*report_cmd) if "last-report.json" in shell(*has_report_cmd).splitlines() else None
        assert current == previous, "Reading the manual must preserve the previous report"
        capture("resumed-checklist", resumed)
        checks.update(returnedStep=step, reportPreserved=True)
        print(f"Dashboard lesson returned to departure check {step}", flush=True)
        raise SystemExit(0)
    click("설명서")
    root = dashboard()
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        signals = current_signals()
        if all(signals[k] for k in ("safety_belt_warning", "safety_washer_warning")):
            break
        time.sleep(.4)
    checks["vehicleSignals"] = signals
    root = dashboard()
    for key in ("safety_belt_warning", "safety_washer_warning"):
        assert signals[key] and signals[key]["source"] == "Vehicle/Safety", (key, signals)
        if signals[key]["error"] == "ERROR_NOT_SUPPORTED_PROPERTY":
            assert signals[key]["quality"] == "UNSUPPORTED"
            assert "경고 신호 미지원" in visible(root)
    capture("dashboard", root)
    for card, title in (("계기판 읽기", "계기판 읽기"), ("깜빡이 켜는 법", "깜빡이 켜는 법"),
                        ("와이퍼 한 번 쓰기", "와이퍼 한 번 쓰기"), ("운전석 안전벨트", "안전벨트 경고 읽기"),
                        ("워셔액 부족", "워셔액 경고 읽기")):
        if card == "와이퍼 한 번 쓰기":
            click("비·야간")
        click(card)
        wait_text(title)
        wait_text("1 / 3")
        click("다음 단계")
        wait_text("2 / 3")
        click("다시 보기")
        if card == "계기판 읽기":
            capture("speed-example", wait_text("40 km/h"))
        if card == "깜빡이 켜는 법":
            click("오른쪽")
            wait_text("오른쪽은 레버를 위로")
            changed_turn = True
            console("/vehicle/turn-signal", mode="BlinkLeft")
            deadline = time.monotonic() + 10
            while True:
                actual = current_signals()["turn"]
                if actual["quality"] == "VALID" and actual["value"] == "LEFT":
                    break
                assert time.monotonic() < deadline, actual
                time.sleep(.2)
            checks["emulatorLeftSignal"] = actual
            console("/vehicle/turn-signal", mode=initial_mode)
            changed_turn = False
            label = {"Off": "꺼짐", "BlinkLeft": "왼쪽 켜짐", "BlinkRight": "오른쪽 켜짐"}[initial_mode]
            capture("signal-example", wait_text("내 차 · 방향지시등 · " + label))
            click("왼쪽")
            wait_text("왼쪽은 레버를 아래로")
        click("다음 단계")
        wait_text("3 / 3")
        click("이전")
        wait_text("2 / 3")
        click("다음 단계")
        click("처음부터")
        wait_text("1 / 3")
        checks.setdefault("openedLessons", []).append(title)
        back()
        dashboard()
        print("Verified", title, flush=True)
    for section, title, example, wrong, correct in new_topics:
        if title not in visible(dashboard()):
            click(section)
        if title == "유리 습기 없애기":
            capture("weather-dashboard", dashboard())
        click(title)
        wait_text("1 / 3")
        click(example or "다음 단계")
        wait_text("2 / 3")
        click("다시 보기")
        if title == "문 열림 위치 찾기":
            changed_door = True
            console("/vehicle/body", door_fl=1)
            checks["driverDoorOpen"] = wait_signal("door:ROW_1_LEFT", "1")
            capture("door-state", wait_text("내 차 · 운전석 문 열림", scroll=True))
            console("/vehicle/body", door_fl=int(initial["body"]["doors"]["front_left"]))
            changed_door = False
        if title == "상향등과 하향등 쓰기":
            changed_light = True
            console("/vehicle/lights", feature="headlamp_mode", value="LowBeam")
            checks["lowBeamRead"] = wait_signal("guide_low_beam", "ON", True)
            console("/vehicle/lights", feature="high_beam", value="true")
            checks["highBeamRead"] = wait_signal("guide_high_beam", "ON", True)
            if checks["highBeamRead"]["quality"] == "VALID":
                console("/vehicle/lights", feature="high_beam", value="false")
                checks["highBeamOff"] = wait_signal("guide_high_beam", "OFF")
            console("/vehicle/lights", feature="headlamp_mode", value=initial["lights"]["headlamp_mode"])
            console("/vehicle/lights", feature="high_beam", value=str(initial["lights"]["high_beam_on"]).lower())
            changed_light = False
        if title == "타이어 경고등 읽기":
            capture("tpms-pattern", wait_text("약 1분 깜빡인 뒤 켜져요", scroll=True))
        click("다음 단계")
        wait_text("3 / 3")
        click("상황 문제")
        click(wrong)
        wait_text("다시 골라보세요", scroll=True)
        click(correct)
        right = wait_text("맞았어요", scroll=True)
        if title == "유리 습기 없애기":
            capture("defog-answer", right)
        click("설명으로 돌아가기")
        wait_text("3 / 3")
        back()
        dashboard()
        wait_text(title, scroll=True)  # Back retains the selected situation.
        checks.setdefault("openedLessons", []).append(title)
        checks.setdefault("quizFeedback", []).append({"topic": title, "retried": True, "correct": correct})
        print("Verified", title, flush=True)
    click("오토홀드 표시 읽기")
    click("다음 단계")
    click("다음 단계")
    click("상황 문제")
    wait_text("작동 대기")
    changed_gear = True
    console("/vehicle/gear", gear="Drive")
    locked = wait_text("주차한 뒤에 이어서 확인해요")
    assert not {"학습용 예시", "다시 보기", "다음 단계", "상황 문제", "작동 대기"}.intersection(visible(locked))
    capture("park-lock", locked)
    console("/vehicle/gear", gear="Park")
    changed_gear = False
    assert "학습용 예시" not in visible(dashboard())
    back()
    returned = visible(wait_text("설명서"))
    assert origin[0] == returned[0], (origin, returned)
    checks["returnedTo"] = returned[0]
except Exception as error:
    checks["failure"] = str(error)
    raise
finally:
    if changed_door:
        console("/vehicle/body", door_fl=int(initial["body"]["doors"]["front_left"]))
    if changed_light:
        console("/vehicle/lights", feature="headlamp_mode", value=initial["lights"]["headlamp_mode"])
        console("/vehicle/lights", feature="high_beam", value=str(initial["lights"]["high_beam_on"]).lower())
    if changed_turn:
        console("/vehicle/turn-signal", mode=initial_mode)
    if changed_gear:
        console("/vehicle/gear", gear="Park")
    (args.output / "verification.json").write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding="utf-8")
