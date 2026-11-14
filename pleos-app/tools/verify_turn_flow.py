"""Replay a real OSM road shape through emulator GPS and public Vehicle inputs.

This is emulator replay, not a physical-road test. Existing sessions are preserved.
The legacy lane-change verifier remains in verify_flow.py.
"""
import argparse
import base64
import copy
import http.client
import json
import math
import re
import shlex
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import urllib.error
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--adb", default=r"C:\AndroidSDK\platform-tools\adb.exe")
parser.add_argument("--serial", default="emulator-5580")
parser.add_argument("--output", required=True)
parser.add_argument("--width-label")
parser.add_argument("--expected-width", type=int)
parser.add_argument("--ui-matrix", action="store_true", help="Verify two widths, day/night and 1.0/1.3 font at each main stage")
parser.add_argument("--ui-matrix-font-mode", "--font-mode", dest="ui_matrix_font_mode", choices=("system", "app"), default="system", help="Choose system font transitions or the app's actual text-size dialog for the matrix")
parser.add_argument("--ui-matrix-stages", default="belt,mirror,doors,course,driving,review,result,notebook", help="Comma-separated matrix stages; default is all eight")
parser.add_argument("--show-sdk-navigation", action="store_true", help="Open the existing native navigation screen through the real driving UI and SDK, then restore this app")
parser.add_argument("--skip-native-background-check", action="store_true", help="Explicitly skip only the initial four-second Android native-navigation foreground collection check")
parser.add_argument("--request-sdk-route", action="store_true", help="Use the parked course UI to request a native route and preserve full/partial/error evidence before recording")
parser.add_argument("--steering-deg", type=float, default=90, help="Virtual Console steering-wheel input; SDK front-wheel feedback is asserted separately")
parser.add_argument("--console-to-sdk-sign", type=int, choices=(-1, 1), default=1, help="Measured sign of SDK front-wheel feedback relative to Virtual Console input")
parser.add_argument("--left-signal", choices=("Off", "BlinkLeft"), default="Off", help="Use BlinkLeft for a second, correctly signalled practice")
parser.add_argument("--expect-target", help="Assert that this course turn is an inherited practice target")
parser.add_argument("--expect-previous-session", help="Assert the actual previous session linked by the new recording")
parser.add_argument("--restore-lat", type=float)
parser.add_argument("--restore-lon", type=float)
args = parser.parse_args()
args.ui_matrix_stages = tuple(stage.strip() for stage in args.ui_matrix_stages.split(','))
if not args.ui_matrix_stages or len(set(args.ui_matrix_stages)) != len(args.ui_matrix_stages) or set(args.ui_matrix_stages)-{"belt", "mirror", "doors", "course", "driving", "review", "result", "notebook"}:
    parser.error("--ui-matrix-stages must contain unique supported stage names separated by commas")
out = Path(args.output)
out.mkdir(parents=True, exist_ok=True)
checks = {}
if args.ui_matrix:
    checks["ui_matrix_selection"] = {"requestedStages": list(args.ui_matrix_stages), "fontMode": args.ui_matrix_font_mode}
last_bounds = None


def adb(*parts, timeout=30):
    return subprocess.check_output([args.adb, "-s", args.serial, *parts], timeout=timeout).decode("utf-8", errors="replace")


assert adb("shell", "getprop", "ro.kernel.qemu").strip() == "1", "Emulator-only verifier"
user = adb("shell", "am", "get-current-user").strip()


def read_json(name, optional=False):
    last_error = None
    for _ in range(8):
        try:
            return json.loads(adb("shell", "run-as", "kr.juhyu.licensenote", "--user", user, "cat", "files/" + name))
        except (ValueError, subprocess.CalledProcessError) as error:
            if optional and isinstance(error, subprocess.CalledProcessError):
                return None
            last_error = error
            time.sleep(.2)
    raise AssertionError("Cannot read " + name) from last_error


def wait_report(predicate, description, timeout=25):
    until = time.monotonic() + timeout
    while True:
        report = read_json("last-report.json")
        if predicate(report):
            return report
        if time.monotonic() >= until:
            raise AssertionError(f"Timed out: {description}: {report}")
        time.sleep(.25)


def wait_signal(key, predicate, timeout=20):
    until = time.monotonic() + timeout
    while True:
        signal = read_json("sdk-state.json").get(key, {})
        if signal.get("quality") == "VALID" and predicate(signal):
            return signal
        if time.monotonic() >= until:
            raise AssertionError(f"Signal not ready {key}: {signal}")
        time.sleep(.25)


def request(path, **params):
    suffix = "?" + urllib.parse.urlencode(params) if params else ""
    req = urllib.request.Request("http://127.0.0.1:8088" + path + suffix, method="PUT" if params else "GET")
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(req, timeout=10) as response:
                return json.load(response)
        except (http.client.RemoteDisconnected, urllib.error.URLError, TimeoutError, ConnectionError) as error:
            if req.get_method() not in ("GET", "PUT") or (isinstance(error, urllib.error.HTTPError) and error.code not in (502, 503, 504)) or attempt == 3:
                raise
            delay = .25 * attempt
            checks.setdefault("transport_retries", []).append({
                "path": path, "method": req.get_method(), "atHostUtc": datetime.now(timezone.utc).isoformat(),
                "attempt": attempt, "nextAttempt": attempt + 1, "delaySeconds": delay,
                "error": type(error).__name__ + ": " + str(error),
            })
            if isinstance(error, urllib.error.HTTPError):
                error.close()
            time.sleep(delay)


def focus(reason="app_window_setup"):
    if last_bounds:
        x1, y1, x2, _ = last_bounds
        signals = read_json("sdk-state.json")
        moving = abs(float(signals.get("speed", {}).get("value") or 0)) > .05 or signals.get("gear", {}).get("value") not in (None, "4")
        # The vehicle's left blind-spot camera can cover the header at D+BlinkLeft.
        # The recording guide below that overlay is inert while moving.
        point = [x1 + min(250, (x2-x1)//3), y1 + (600 if moving else 80)]
        checks.setdefault("window_focus_actions", []).append({"focusPoint": point, "atHostUtc": datetime.now(timezone.utc).isoformat(), "reason": reason, "belowVehicleCamera": moving, "lastObservedAppBounds": list(last_bounds)})
        adb("shell", "input", "tap", *map(str, point))


def probe(click_text=None):
    command = ["am", "instrument", "--user", user, "-w"]
    if click_text:
        command += ["-e", "click", click_text]
    command += ["kr.juhyu.licensenote.driver/.UiDriver"]
    for _ in range(8):
        raw = adb("shell", " ".join(map(shlex.quote, command)))
        match = re.search(r"INSTRUMENTATION_RESULT: xml64=([^\r\n]+)", raw)
        if match:
            assert "INSTRUMENTATION_RESULT: error=" not in raw, raw
            return ET.fromstring(base64.b64decode(match.group(1)))
        if "App accessibility window not found" not in raw:
            break
        focus("app_accessibility_root_missing")
        time.sleep(1)
    raise AssertionError(raw)


def visible(root):
    return {n.get("text") for n in root.iter("node") if n.get("text")}


def click(text, optional=False):
    deadline = time.monotonic() + 20
    last = ""
    while True:
        try:
            root = probe()
            candidates = [n for n in root.iter("node") if n.get("text") == text]
            if not candidates and optional:
                return False
            if any(n.get("enabled") == "true" for n in candidates):
                probe(text)
                print("Clicked:", text, flush=True)
                time.sleep(.7)
                return True
            last = f"Missing enabled button {text}: {visible(root)}"
        except AssertionError as error:
            last = str(error)
            # UiDriver throws these before performAction. A rejected or successful click is never retried.
            if not any(reason in last for reason in (
                "java.lang.IllegalStateException: Text not found:",
                "java.lang.IllegalStateException: App accessibility window not found",
            )):
                raise
        assert time.monotonic() < deadline, last
        time.sleep(.25)


def finish_preparation():
    completed = []
    for _ in range(7):
        texts = visible(probe())
        if "어디서 연습할까요?" in texts:
            if not (out / "00-course-selection.png").exists():
                capture("00-course-selection", ("어디서 연습할까요?", "기본 도로주행"))
            click("기본 도로주행")
            continue
        start_label = next((text for text in ("이 코스로 시작", "기록 시작") if text in texts), None)
        if start_label:
            checks.setdefault("preparation_passes", []).append(completed)
            return start_label
        for stage, anchor in (("belt", "1 / 3"), ("mirror", "2 / 3"), ("doors", "3 / 3")):
            if anchor in texts:
                name = "00-preparation-" + stage
                if not (out / (name + ".png")).exists():
                    capture(name, ("출발 준비", anchor))
                if stage not in checks.get("ui_matrix", {}):
                    verify_ui_matrix(stage)
                texts = visible(probe())
        button = next((text for text in ("직접 확인했어요", "벨트를 맸어요", "다음", "잘 보여요", "코스 보기", "모두 닫혔어요") if text in texts), None)
        assert button, f"Preparation cannot continue: {texts}"
        click(button)
        completed.append(button)
    raise AssertionError("Preparation did not reach course selection")


def scroll_body(up=False):
    assert last_bounds, "Capture a live app window before scrolling"
    root = probe()
    nodes = [node for node in root.iter("node") if node.get("scrollable") == "true"]
    index = 1 if "연습 결과" in visible(root) and last_bounds[2]-last_bounds[0] == 1682 and len(nodes) > 1 else 0
    command = ["am", "instrument", "--user", user, "-w", "-e", "scroll", "backward" if up else "forward", "-e", "scrollIndex", str(index), "kr.juhyu.licensenote.driver/.UiDriver"]
    raw = adb("shell", " ".join(map(shlex.quote, command)))
    assert "INSTRUMENTATION_RESULT: error=" not in raw, raw
    status = re.search(r"INSTRUMENTATION_RESULT: scrollStatus=([^\r\n]+)", raw)
    accepted = re.search(r"INSTRUMENTATION_RESULT: scrollAccepted=(true|false)", raw)
    count = re.search(r"INSTRUMENTATION_RESULT: scrollableCount=(\d+)", raw)
    assert status and accepted and count, "UiDriver lacks semantic-scroll support: " + raw
    assert status.group(1) in ("NO_SCROLLABLE", "END_REACHED", "SCROLL_REQUESTED"), raw
    time.sleep(.25)
    after = probe()
    page_labels = {"출발 준비", "경로 미리보기", "운전 연습", "주차 후 확인", "연습 결과", "오답노트"}
    assert visible(root).intersection(page_labels) == visible(after).intersection(page_labels), "Semantic scroll changed the app page"

    def signature(snapshot):
        snapshot = copy.deepcopy(snapshot)
        if "운전 연습" in visible(snapshot):
            for parent in snapshot.iter():
                for child in list(parent):
                    if re.fullmatch(r"\d+분\s*\d+초(?:\s*기록 중)?", child.get("text", "").strip()):
                        parent.remove(child)
        return ET.tostring(snapshot, encoding="unicode")

    changed = signature(root) != signature(after)
    action = {"direction": "backward" if up else "forward", "requestedIndex": index, "scrollableCount": int(count.group(1)), "status": status.group(1), "actionAccepted": accepted.group(1) == "true", "positionChanged": changed}
    checks.setdefault("semantic_scroll_actions", []).append(action)
    if not action["actionAccepted"]:
        assert not changed, "Driver reported no-scroll/end but static viewport changed: " + str(action)


def wait_for_map(name, timeout=40):
    until = time.monotonic() + timeout
    scroll_attempts = 0
    next_scroll = time.monotonic() + 1
    while True:
        root = probe()
        texts = visible(root) | {n.get("description") for n in root.iter("node") if n.get("description")}
        completed = next((text for text in texts if text.startswith("지도 준비 완료 ")), None)
        failed = next((text for text in texts if text.startswith("지도 불러오기 실패 ")), None)
        assert failed is None, failed
        if completed:
            match = re.match(r"지도 준비 완료 (\d+)/(\d+)", completed)
            assert match and int(match.group(1)) == int(match.group(2)) and int(match.group(1)) > 0, completed
            checks.setdefault("actual_raster_map_loaded", {})[name] = completed
            return
        # The narrow course puts its real map below the descriptive text.
        if not any(text.startswith("지도 준비") for text in texts) and time.monotonic() >= next_scroll and scroll_attempts < 3:
            scroll_body()
            scroll_attempts += 1
            next_scroll = time.monotonic() + 1
        assert time.monotonic() < until, f"Actual raster map did not load: {texts}"
        time.sleep(.5)


def verify_ui_matrix(stage):
    if not args.ui_matrix or stage not in args.ui_matrix_stages:
        return
    destination = out / ("matrix-" + stage)
    command = [sys.executable, str(Path(__file__).with_name("verify_ui_matrix.py")),
               "--adb", args.adb, "--serial", args.serial,
               "--stage", stage, "--output", str(destination),
               "--restore-width", str(args.expected_width or 1682),
               "--font-mode", args.ui_matrix_font_mode]
    print("Starting UI matrix:", stage, flush=True)
    subprocess.run(command, check=True, timeout=600)
    result_path = destination / "matrix-checks.json"
    result = json.loads(result_path.read_text(encoding="utf-8-sig"))
    assert result.get("status") == "PASS" and result.get("passed") and result.get("matrixPassed") and result.get("restorationComplete"), result
    assert len(result.get("captures", [])) == 8, result
    checks.setdefault("ui_matrix", {})[stage] = {"evidence": str(result_path), "result": result}
    focus("matrix_return:" + stage)


def capture(name, required=()):
    global last_bounds
    if "운전 연습" in required and last_bounds and last_bounds[2]-last_bounds[0] == 841:
        focus("narrow_driving_capture:" + name)
        time.sleep(1)
    until = time.monotonic() + 25
    while True:
        root = probe()
        if set(required).issubset(visible(root)):
            break
        assert time.monotonic() < until, f"UI not ready {name}: {visible(root)}"
        time.sleep(.3)
    bounds = list(map(int, re.findall(r"-?\d+", root.find("node").get("bounds"))))
    last_bounds = bounds
    split_reapplied = False
    if name == "07-restored-result" and args.expected_width == 841 and bounds[2]-bounds[0] == 1682:
        # A cold task can receive the launcher's wide slot. Restore the user's split.
        adb("shell", "input", "swipe", str(bounds[2]+11), str((bounds[1]+bounds[3])//2), str(bounds[0]+853), str((bounds[1]+bounds[3])//2), "900")
        time.sleep(1)
        focus("cold_start_split_setup")
        root = probe()
        bounds = list(map(int, re.findall(r"-?\d+", root.find("node").get("bounds"))))
        last_bounds = bounds
        split_reapplied = True
    width, height = bounds[2]-bounds[0], bounds[3]-bounds[1]
    if args.expected_width:
        assert abs(width-args.expected_width) <= 1, (name, width, args.expected_width)
    # Accessibility can precede the first rendered Compose frame after a transition.
    time.sleep(.8)
    root = probe()
    assert set(required).issubset(visible(root)), (name, visible(root))
    (out / (name + ".xml")).write_text(ET.tostring(root, encoding="unicode"), encoding="utf-8")
    png = subprocess.check_output([args.adb, "-s", args.serial, "exec-out", "screencap", "-p"], timeout=30)
    assert png.startswith(b"\x89PNG\r\n\x1a\n")
    (out / (name + ".png")).write_bytes(png)
    (out / (name + ".metadata.json")).write_text(json.dumps({
        "capturedAtHostUtc": datetime.now(timezone.utc).isoformat(), "widthLabel": args.width_label,
        "appBounds": bounds, "appWidthPixels": width, "appHeightPixels": height,
        "systemSplitReappliedAfterColdStart": split_reapplied, "requiredText": list(required),
    }, ensure_ascii=False, indent=2), encoding="utf-8")


def show_sdk_navigation():
    global last_bounds
    if not args.show_sdk_navigation:
        return
    package = "ai.umos.maps.android.navigation.app"
    before = read_json("last-report.json")
    assert before["phase"] == "RECORDING", before
    before_signals = read_json("sdk-state.json")
    screen_at = before_signals.get("navigation_screen", {}).get("receivedMs", -1)
    before_bounds = list(last_bounds)
    target_width = args.expected_width or before_bounds[2]-before_bounds[0]
    evidence = {
        "scope": "Moving UI requests the existing native screen; it does not set or verify a course route. Native GPS equivalence is not established.",
        "SDKrequestSent": False, "AndroidfallbackRequested": False,
        "actualNativeWindowCaptured": False, "actualNativeForeground": False,
        "routeReady": False, "TBTcallbackAvailable": False,
        "beforeSampleCount": before["sampleCount"], "continuitySamples": [], "restorationErrors": [],
    }

    def native_root():
        command = ["am", "instrument", "--user", user, "-w", "-e", "package", package, "kr.juhyu.licensenote.driver/.UiDriver"]
        raw = adb("shell", " ".join(map(shlex.quote, command)))
        match = re.search(r"INSTRUMENTATION_RESULT: xml64=([^\r\n]+)", raw)
        assert match and "INSTRUMENTATION_RESULT: error=" not in raw, raw
        return ET.fromstring(base64.b64decode(match.group(1)))

    try:
        wait_signal("speed", lambda s: abs(float(s["value"])-5.0) < .05)
        wait_signal("gear", lambda s: s["value"] == "8")
        click("기본 내비 열기")
        screen = wait_signal("navigation_screen", lambda s: s["value"] == "SDK_FOREGROUND_REQUESTED" and s["receivedMs"] > screen_at)
        evidence["SDKrequestSent"] = True
        evidence["screenSignal"] = screen
        deadline = time.monotonic() + 20
        while True:
            try:
                root = native_root()
                window_info = adb("shell", "dumpsys", "window", "displays")
                focus_lines = [line.strip() for line in window_info.splitlines() if "mCurrentFocus=" in line or "mFocusedApp=" in line]
                if any(package in line for line in focus_lines):
                    break
                problem = "Native window found without native input focus: " + str(focus_lines)
            except AssertionError as error:
                problem = str(error)
                if "App accessibility window not found" not in problem:
                    raise
            assert time.monotonic() < deadline, problem
            time.sleep(.25)
        evidence["actualNativeForeground"] = True
        evidence["nativeFocusLines"] = focus_lines
        evidence["focusQuery"] = "dumpsys window displays"
        evidence["uiDriverTargetPackage"] = package
        # Keep the real native surface in view for ten seconds; never inject app session state.
        for _ in range(5):
            time.sleep(2)
            signals = read_json("sdk-state.json")
            now_ms = int(float(adb("shell", "cat", "/proc/uptime").split()[0])*1000)
            sample = {"deviceElapsedMs": now_ms}
            for key in ("speed", "gear"):
                signal = signals.get(key, {})
                age = now_ms-signal.get("receivedMs", 0)
                sample[key] = {"signal": signal, "ageMs": age}
                assert signal.get("quality") == "VALID" and 0 <= age < 3000, sample
            evidence["continuitySamples"].append(sample)
        root = native_root()
        stem = "02-sdk-native-navigation"
        (out/(stem+".xml")).write_text(ET.tostring(root, encoding="unicode"), encoding="utf-8")
        pixels = subprocess.check_output([args.adb, "-s", args.serial, "exec-out", "screencap", "-p"], timeout=30)
        assert pixels.startswith(b"\x89PNG\r\n\x1a\n")
        (out/(stem+".png")).write_bytes(pixels)
        evidence["actualNativeWindowCaptured"] = True
        evidence["nativeVisibleText"] = sorted(visible(root))
        evidence["nativeGuidanceVisible"] = "안내 종료" in visible(root)
        signals = read_json("sdk-state.json")
        launch = signals.get("navigation_launch", {})
        evidence["AndroidfallbackRequested"] = launch.get("quality") == "VALID" and launch.get("value") == "ANDROID_ACTIVITY_REQUESTED" and launch.get("receivedMs", 0) >= screen["receivedMs"]
        evidence["launchSignal"] = launch
        evidence["navigationSignals"] = {key: signal for key, signal in signals.items() if key.startswith("navigation")}
        evidence["routeReady"] = signals.get("navigation_request", {}).get("value") == "ROUTE_READY"
        tbt = signals.get("navigation_tbt", {})
        try:
            instructions = json.loads(tbt.get("value") or "[]")
            evidence["TBTcallbackAvailable"] = tbt.get("quality") == "VALID" and tbt.get("origin") == "CALLBACK" and isinstance(instructions, list) and bool(instructions)
        except (ValueError, TypeError):
            pass
        after = read_json("last-report.json")
        assert after["id"] == before["id"] and after["phase"] == "RECORDING" and after["sampleCount"] > before["sampleCount"], after
        evidence["afterSampleCount"] = after["sampleCount"]
        evidence["collectionContinued"] = True
    except BaseException as error:
        evidence["failure"] = type(error).__name__ + ": " + str(error)
        raise
    finally:
        try:
            adb("shell", "am", "start", "--user", user, "-n", "kr.juhyu.licensenote/.MainActivity")
            focus("native_navigation_return")
            root = probe()
            bounds = list(map(int, re.findall(r"-?\d+", root.find("node").get("bounds", ""))))
            assert len(bounds) == 4, bounds
            width = bounds[2]-bounds[0]
            split_reapplied = False
            if width != target_width:
                assert width in (841, 1682) and target_width in (841, 1682), (width, target_width)
                adb("shell", "input", "swipe", str(bounds[2]+12), str((bounds[1]+bounds[3])//2), str(bounds[0]+target_width+12), str((bounds[1]+bounds[3])//2), "700")
                time.sleep(.7)
                last_bounds = bounds
                focus("native_navigation_split_setup")
                root = probe()
                bounds = list(map(int, re.findall(r"-?\d+", root.find("node").get("bounds", ""))))
                split_reapplied = True
            assert bounds[2]-bounds[0] == target_width, bounds
            last_bounds = bounds
            evidence["restoredAppBounds"] = bounds
            evidence["systemSplitReappliedAfterNativeReturn"] = split_reapplied
            capture("02-sdk-native-return", ("운전 연습",))
        except Exception as error:
            evidence["restorationErrors"].append(str(error))
        evidence["capturedAtHostUtc"] = datetime.now(timezone.utc).isoformat()
        checks["sdk_navigation_screen"] = evidence
        (out/"02-sdk-native-navigation.metadata.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    assert not evidence["restorationErrors"], evidence


def request_sdk_route():
    global last_bounds
    if not args.request_sdk_route:
        return
    started = time.monotonic()
    native = "ai.umos.maps.android.navigation.app"
    target_width = args.expected_width or last_bounds[2]-last_bounds[0]
    evidence = {"status": "UNCONFIRMED", "routeReady": False, "scope": "Parked course-button request; native route, waypoint and GPS equivalence are separate checks.",
                "targetBudgetSeconds": 140, "maximumObservationSeconds": 120,
                "commandTimeoutsSeconds": {"defaultAdb": 30, "nativeUiProbe": 10, "windowFocusDump": 10, "screenshot": 10},
                "timeline": [], "restorationErrors": []}
    stem = "01-sdk-route"

    def navigation_signals():
        return {key: signal for key, signal in read_json("sdk-state.json").items() if key.startswith("navigation")}

    def decode(signals, key, default):
        try:
            return json.loads(signals.get(key, {}).get("value") or json.dumps(default))
        except (ValueError, TypeError):
            return default

    def route_facts(signals, request_at):
        route = decode(signals, "navigation_route_details", {})
        destination = decode(signals, "navigation_destination", {})
        waypoints = decode(signals, "navigation_waypoints", [])
        tbt = decode(signals, "navigation_tbt", [])

        def matches(point, item):
            try:
                return distance(point, (float(item["latitude"]), float(item["longitude"]))) <= 50
            except (KeyError, TypeError, ValueError):
                return False

        destination_matches = isinstance(destination, dict) and matches(ROUTE[-1], destination)
        waypoints_match = isinstance(waypoints, list) and all(any(matches(point, item) for item in waypoints if isinstance(item, dict)) for point in (ROUTE[1], ROUTE[3]))
        fresh = all(signals.get(key, {}).get("quality") == "VALID" and signals.get(key, {}).get("origin") == "CALLBACK" and signals.get(key, {}).get("receivedMs", 0) >= request_at for key in ("navigation_route_details", "navigation_destination", "navigation_waypoints"))
        guiding = signals.get("navigation_status", {}).get("value") == "ROUTE_DRIVE" or route.get("naviMode") in ("ROUTE_GUIDING", "SIMULATION_GUIDING")
        return {"routeReady": bool(fresh and route.get("isRouting") is True and destination_matches and waypoints_match),
                "guidanceObserved": guiding, "TBTcallbackAvailable": bool(isinstance(tbt, list) and tbt and signals.get("navigation_tbt", {}).get("quality") == "VALID" and signals.get("navigation_tbt", {}).get("origin") == "CALLBACK"),
                "destinationMatches": destination_matches, "twoWaypointCoordinatesMatch": waypoints_match, "isRouting": route.get("isRouting")}

    try:
        path_output = adb("shell", "pm", "path", "--user", user, "kr.juhyu.licensenote")
        apk_path = next(line.removeprefix("package:").strip() for line in path_output.splitlines() if line.strip().endswith("/base.apk"))
        apk_hash = adb("shell", "sha256sum", apk_path).split()[0].lower()
        assert re.fullmatch(r"[0-9a-f]{64}", apk_hash), apk_hash
        evidence["installedApkSha256"] = apk_hash
        stem += "-" + apk_hash[:12]
        wait_signal("speed", lambda s: abs(float(s["value"])) < .05)
        wait_signal("gear", lambda s: s["value"] == "4")
        wait_signal("ux", lambda s: s["value"] == "false,0")
        parked = read_json("sdk-state.json")
        request_at = int(float(adb("shell", "cat", "/proc/uptime").split()[0])*1000)
        assert all(0 <= request_at-parked[key]["receivedMs"] < 2500 for key in ("speed", "gear")), parked
        evidence["parkedSignals"] = {key: parked.get(key) for key in ("speed", "gear", "ux")}
        baseline_screen_at = parked.get("navigation_screen", {}).get("receivedMs", -1)
        click("기본 내비로 경로 보기")
        error_at = None
        while True:
            signals = navigation_signals()
            elapsed = time.monotonic()-started
            facts = route_facts(signals, request_at)
            request = signals.get("navigation_request", {})
            if request.get("quality") == "ERROR":
                facts["routeReady"] = False
            evidence["timeline"].append({"afterSeconds": round(elapsed, 2), "facts": facts, "navigationSignals": signals})
            if request.get("quality") == "ERROR" and error_at is None:
                error_at = time.monotonic()
            if facts["routeReady"] or (elapsed >= 10 and facts["guidanceObserved"] and facts["TBTcallbackAvailable"]) or (error_at is not None and time.monotonic()-error_at >= 8) or elapsed >= 120:
                break
            time.sleep(2)
        evidence.update(facts)
        evidence["SDKrequestSent"] = signals.get("navigation_screen", {}).get("value") == "SDK_FOREGROUND_REQUESTED" and signals.get("navigation_screen", {}).get("receivedMs", 0) > baseline_screen_at
        evidence["status"] = "VERIFIED_ROUTE" if facts["routeReady"] else "PARTIAL" if facts["guidanceObserved"] or facts["TBTcallbackAvailable"] else "ERROR" if request.get("quality") == "ERROR" else "UNCONFIRMED"
        evidence["navigationSignals"] = signals
        command = ["am", "instrument", "--user", user, "-w", "-e", "package", native, "kr.juhyu.licensenote.driver/.UiDriver"]
        raw = adb("shell", " ".join(map(shlex.quote, command)), timeout=10)
        match = re.search(r"INSTRUMENTATION_RESULT: xml64=([^\r\n]+)", raw)
        if match and "INSTRUMENTATION_RESULT: error=" not in raw:
            xml_bytes = base64.b64decode(match.group(1))
            root = ET.fromstring(xml_bytes)
            (out/(stem+".xml")).write_bytes(xml_bytes)
            evidence["nativeWindowCaptured"] = root.find("node") is not None
            evidence["nativeVisibleText"] = sorted(visible(root))
        else:
            evidence["nativeWindowCaptured"] = False
            evidence["nativeProbeError"] = raw
        focus_lines = [line.strip() for line in adb("shell", "dumpsys", "window", "displays", timeout=10).splitlines() if "mCurrentFocus=" in line or "mFocusedApp=" in line]
        evidence["actualNativeForeground"] = any(native in line for line in focus_lines)
        evidence["nativeFocusLines"] = focus_lines
        evidence["focusQuery"] = "dumpsys window displays"
        pixels = subprocess.check_output([args.adb, "-s", args.serial, "exec-out", "screencap", "-p"], timeout=10)
        assert pixels.startswith(b"\x89PNG\r\n\x1a\n")
        (out/(stem+".png")).write_bytes(pixels)
        evidence["deviceScreenshot"] = stem+".png"
    except Exception as error:
        evidence["status"] = "PROBE_ERROR"
        evidence["error"] = type(error).__name__ + ": " + str(error)
        try:
            evidence["navigationSignals"] = navigation_signals()
        except Exception as snapshot_error:
            evidence["stateReadError"] = str(snapshot_error)
    finally:
        if "deviceScreenshot" not in evidence:
            try:
                pixels = subprocess.check_output([args.adb, "-s", args.serial, "exec-out", "screencap", "-p"], timeout=10)
                assert pixels.startswith(b"\x89PNG\r\n\x1a\n")
                (out/(stem+".png")).write_bytes(pixels)
                evidence["deviceScreenshot"] = stem+".png"
            except Exception as capture_error:
                evidence["captureError"] = str(capture_error)
        try:
            adb("shell", "am", "start", "--user", user, "-n", "kr.juhyu.licensenote/.MainActivity")
            focus("native_route_return")
            root = probe()
            bounds = list(map(int, re.findall(r"-?\d+", root.find("node").get("bounds", ""))))
            evidence["systemSplitReappliedAfterNativeReturn"] = bounds[2]-bounds[0] != target_width
            if evidence["systemSplitReappliedAfterNativeReturn"]:
                assert bounds[2]-bounds[0] in (841, 1682) and target_width in (841, 1682), bounds
                adb("shell", "input", "swipe", str(bounds[2]+12), str((bounds[1]+bounds[3])//2), str(bounds[0]+target_width+12), str((bounds[1]+bounds[3])//2), "700")
                time.sleep(.7)
                root = probe()
                bounds = list(map(int, re.findall(r"-?\d+", root.find("node").get("bounds", ""))))
            assert bounds[2]-bounds[0] == target_width, bounds
            last_bounds = bounds
            capture("01-sdk-route-return", ("경로 미리보기",))
        except Exception as error:
            evidence["restorationErrors"].append(str(error))
        evidence["capturedAtHostUtc"] = datetime.now(timezone.utc).isoformat()
        evidence["durationSeconds"] = round(time.monotonic()-started, 2)
        (out/(stem+"-sdk-state.json")).write_text(json.dumps({"installedApkSha256": evidence.get("installedApkSha256"), "navigationSignals": evidence.get("navigationSignals", {})}, ensure_ascii=False, indent=2), encoding="utf-8")
        (out/(stem+"-timeline.json")).write_text(json.dumps({"installedApkSha256": evidence.get("installedApkSha256"), "samples": evidence["timeline"]}, ensure_ascii=False, indent=2), encoding="utf-8")
        (out/(stem+".metadata.json")).write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        checks["sdk_route_request"] = evidence
    assert not evidence["restorationErrors"], evidence


# Original OSM nodes: 11431820866 -> 1859712472 -> 3827169469 ->
# 3827169464 -> 3827169496 -> 11431861628. No road-turn legality claim.
ROUTE = [(37.4844415,127.0321124), (37.4849111,127.0318935),
         (37.4847141,127.0304318), (37.4846113,127.0296948),
         (37.4852267,127.0295627), (37.4860285,127.0293493)]


def distance(a, b):
    return math.hypot((b[0]-a[0])*111195, (b[1]-a[1])*111195*math.cos(math.radians((a[0]+b[0])/2)))


def gps(point):
    adb("emu", "geo", "fix", f"{point[1]:.7f}", f"{point[0]:.7f}")


def turn_results(report):
    return {result["direction"]: result for result in report.get("turnAssessments", [])}


baseline = request("/vehicle/state")
assert baseline["gear"]["actual"]["value"] == "Park" and baseline["controls"]["speed_kph"] == 0, "Start parked at zero speed"
location_dump = adb("shell", "dumpsys", "location")
old_fix = re.findall(r"last location=Location\[gps (-?[\d.]+),\s*(-?[\d.]+)", location_dump)
restore_fix = tuple(map(float, old_fix[0])) if old_fix else None
if args.restore_lat is not None and args.restore_lon is not None:
    restore_fix = (args.restore_lat, args.restore_lon)
assert restore_fix is not None, "Supply --restore-lat/--restore-lon when GPS baseline is unavailable"
restore_errors = []
replay = []
checks["steering_console_to_sdk_sign"] = args.console_to_sdk_sign
checks["left_signal_input"] = args.left_signal
try:
    request("/vehicle/controls", speed=0, accelerator=0, brake=0, steering=0)
    request("/vehicle/gear", gear="Park")
    request("/vehicle/body", door_fl=0, door_fr=0, door_rl=0, door_rr=0)
    request("/vehicle/turn-signal", mode="Off")
    gps(ROUTE[0])
    adb("shell", "am", "start", "--user", user, "-n", "kr.juhyu.licensenote/.MainActivity")
    wait_signal("turn_subscription", lambda s: s["value"] == "READY")
    wait_signal("steering", lambda s: abs(float(s["value"])) < 1)
    previous = read_json("last-report.json", optional=True)
    if args.expect_previous_session:
        assert previous and previous["id"] == args.expect_previous_session, previous
    if args.expect_target and previous and previous.get("assessment", {}).get("mistake") and not previous.get("nextPractice"):
        click("오답노트 보기", optional=True)
        click("좌회전 다시 연습")
        wait_report(lambda r: r["id"] == previous["id"] and r["nextPractice"], "UI recommendation before the target practice")
    elif args.expect_target and previous and previous.get("nextPractice"):
        click("좌회전 다시 연습", optional=True)
    if "이미 운전면허가 있어요" in visible(probe()):
        capture("00-welcome", ("이미 운전면허가 있어요",))
        click("이미 운전면허가 있어요")
    click("다음 연습 준비", optional=True)
    start_label = finish_preparation()
    capture("01-course", (start_label,))
    wait_for_map("course")
    capture("01a-actual-map", (start_label,))
    verify_ui_matrix("course")
    request_sdk_route()
    old = read_json("last-report.json", optional=True)
    old_id = old.get("id") if old else None
    click(start_label)
    started = wait_report(lambda r: r.get("id") != old_id and r.get("phase") == "RECORDING", "new recording session")
    sid = started["id"]
    assert started["courseId"] == "yangjae-turn-course-01" and started["rulesVersion"] >= 2, started
    if args.expect_target:
        assert args.expect_target in started.get("practiceTargets", []), started
        assert started.get("previousTargetStatuses", {}).get(args.expect_target) == "OMISSION", started
        checks["inherited_practice_target"] = args.expect_target
    if args.expect_previous_session:
        assert started.get("previousSessionId") == args.expect_previous_session, started
        checks["previous_session_link"] = started["previousSessionId"]
    wait_signal("location", lambda s: s.get("origin") == "CALLBACK" and abs(float(s["value"].split(',')[0])-ROUTE[0][0]) < .00005)
    if args.skip_native_background_check:
        checks.setdefault("skipped_checks", {})["native_navigation_foreground_collection"] = {
            "skip": True, "reason": "Explicit --skip-native-background-check: preserve the existing user split; native foreground collection was not rechecked in this run."
        }
    else:
        before = read_json("last-report.json")
        adb("shell", "am", "start", "--user", user, "-n", "ai.umos.maps.android.navigation.app/.MainActivity")
        time.sleep(4)
        after = wait_report(lambda r: r["id"] == sid and r["sampleCount"] > before["sampleCount"], "collection while navigation is foreground")
        checks["native_navigation_foreground_collection"] = {"before": before["sampleCount"], "after": after["sampleCount"]}
        adb("shell", "am", "start", "--user", user, "-n", "kr.juhyu.licensenote/.MainActivity")
        focus("native_background_check_return")
    request("/vehicle/gear", gear="Drive")
    # Validate the unobscured driving UI first. The replay sets the requested
    # left signal before its first GPS step; the vehicle camera then has priority.
    request("/vehicle/turn-signal", mode="Off")
    request("/vehicle/controls", speed=18, accelerator=0, brake=0, steering=-args.steering_deg*args.console_to_sdk_sign)
    wait_signal("gear", lambda s: s["value"] == "8")
    wait_signal("steering", lambda s: float(s["value"]) <= -2)
    capture("02-driving", ("운전 연습",))
    wait_for_map("driving")
    capture("02a-actual-map-start", ("운전 연습",))
    verify_ui_matrix("driving")
    show_sdk_navigation()
    assert "기록 종료" not in visible(probe()), "Moving UI exposed review action"
    checks["moving_review_locked"] = True
    for segment, (a, b) in enumerate(zip(ROUTE, ROUTE[1:])):
        steps = max(1, math.ceil(distance(a, b)/6))
        for i in range(1, steps+1):
            point = (a[0]+(b[0]-a[0])*i/steps, a[1]+(b[1]-a[1])*i/steps)
            if segment == 0 or (segment == 1 and distance(ROUTE[1], point) < 50):
                steering, signal = -args.steering_deg*args.console_to_sdk_sign, args.left_signal
            elif segment == 3 or (segment == 2 and distance(point, ROUTE[3]) < 65):
                steering, signal = args.steering_deg*args.console_to_sdk_sign, "BlinkRight"
            else:
                steering, signal = 0, "Off"
            request("/vehicle/controls", speed=18, accelerator=0, brake=0, steering=steering)
            request("/vehicle/turn-signal", mode=signal)
            gps(point)
            replay.append({"hostUtc": datetime.now(timezone.utc).isoformat(), "lat":point[0], "lon":point[1], "steeringConsoleDeg":steering, "signal":signal})
            time.sleep(1.15)
        if segment == 1:
            wait_for_map("after_left")
            capture("02b-map-after-left", ("운전 연습",))
            checks["sdk_map_position_after_left"] = wait_signal("location", lambda s: abs(float(s["value"].split(',')[0])-ROUTE[2][0]) < .00005)
    time.sleep(3)
    request("/vehicle/controls", speed=0, accelerator=0, brake=0, steering=0)
    request("/vehicle/gear", gear="Park")
    wait_signal("speed", lambda s: abs(float(s["value"])) < .05)
    wait_signal("gear", lambda s: s["value"] == "4")
    wait_signal("ux", lambda s: s["value"] == "false,0")
    time.sleep(2)
    recorded = wait_report(lambda r: len([t for t in r.get("turns", []) if t.get("exitMs")]) == 2, "two SDK-derived turns")
    assert [t["direction"] for t in recorded["turns"]] == ["LEFT", "RIGHT"], recorded
    checks["two_sdk_turn_candidates"] = recorded["turns"]
    click("기록 종료")
    capture("03-review-left", ("주차 후 확인", "좌회전했어요"))
    verify_ui_matrix("review")
    click("좌회전했어요")
    capture("03a-review-right", ("주차 후 확인", "우회전했어요"))
    click("우회전했어요")
    confirmed = wait_report(lambda r: r["id"] == sid and r["phase"] == "COMPLETE", "confirmed two turns")
    results = turn_results(confirmed)
    positive = args.left_signal == "BlinkLeft"
    assert results["LEFT"]["status"] == ("OBSERVED" if positive else "OMISSION") and results["LEFT"]["mistake"] == (not positive), confirmed
    assert results["RIGHT"]["status"] == "OBSERVED" and not results["RIGHT"]["mistake"], confirmed
    assert confirmed["assessment"]["score"] is None and not confirmed["dataGap"], confirmed
    checks["both_turn_signals_observed" if positive else "left_omission_right_signal_observed"] = list(results.values())
    (out/"confirmed-report.json").write_text(json.dumps(confirmed, ensure_ascii=False, indent=2), encoding="utf-8")
    record_button = "기록 보기" if positive else "오답노트 보기"
    capture("04-result", ("연습 결과", record_button))
    verify_ui_matrix("result")
    if click("휴대폰으로 보내기", optional=True):
        capture("04a-phone-transfer", ("QR로 받기",))
        click("QR로 받기")
        capture("04a-result-qr", ("휴대폰 카메라로 찍어 주세요", "결과로 돌아가기"))
        assert any(n.get("description") == "휴대폰 카메라로 읽는 QR 코드" for n in probe().iter("node")), "QR image missing"
        click("결과로 돌아가기")
        capture("04b-returned-result", ("연습 결과", record_button))
        checks["phone_transfer_opened_and_returned"] = True
    click(record_button)
    capture("05-notebook", ("오답노트", "첫 번째 좌회전"))
    verify_ui_matrix("notebook")
    for _ in range(3):
        if "두 번째 우회전" in visible(probe()):
            break
        scroll_body()
    capture("05a-notebook-right", ("오답노트", "두 번째 우회전"))
    if positive:
        assert not confirmed["assessment"]["mistake"], confirmed
        if args.expect_target:
            outcomes = {item["courseTurnId"]: item for item in confirmed.get("targetOutcomes", [])}
            outcome = outcomes.get(args.expect_target)
            assert outcome and outcome["previousStatus"] == "OMISSION" and outcome["currentStatus"] == "OBSERVED", confirmed
            checks["target_improved_from_omission_to_observed"] = outcome
        checks["positive_practice_complete"] = True
        capture("10-positive-records", ("오답노트",))
    else:
        click("좌회전 다시 연습")
        recommended = wait_report(lambda r: r["id"] == sid and r["nextPractice"] and r["revision"] > confirmed["revision"], "next practice recommendation")
        capture("06a-recommended-preparation", ("출발 준비", "1 / 3"))
        adb("shell", "input", "keyevent", "4")
        capture("06b-recommended-back-result", ("연습 결과", "오답노트 보기"))
        preserved = wait_report(lambda r: r["id"] == sid and r["nextPractice"] and r["revision"] == recommended["revision"], "recommendation preserved after preparation Back")
        checks["preparation_back_preserves_same_result_and_recommendation"] = preserved["id"]
        click("다음 연습 준비")
        capture("06c-preparation-required-again", ("출발 준비", "1 / 3"))
        start_label = finish_preparation()
        assert checks["preparation_passes"][-1], "Recommendation bypassed per-practice preparation"
        capture("06-next-course", (start_label,))
        checks["next_practice_saved"] = True
        adb("shell", "am", "force-stop", "--user", user, "kr.juhyu.licensenote")
        adb("shell", "am", "start", "--user", user, "-n", "kr.juhyu.licensenote/.MainActivity")
        focus("process_restart_return")
        capture("07-restored-result", ("연습 결과", "오답노트 보기"))
        restored = wait_report(lambda r: r["id"] == sid and r["nextPractice"] and r["revision"] == recommended["revision"], "restored same session and recommendation")
        checks["restart_restored_session_and_recommendation"] = True
        click("좌회전 답변 수정")
        capture("07a-correction-review", ("좌회전하지 않았어요",))
        click("좌회전하지 않았어요")
        corrected = wait_report(lambda r: r["id"] == sid and r["revision"] > restored["revision"], "corrected first event")
        assert turn_results(corrected)["LEFT"]["status"] == "NO_TURN" and not corrected["assessment"]["mistake"] and not corrected["nextPractice"], corrected
        checks["correction_removes_only_left_error_and_recommendation"] = list(turn_results(corrected).values())
        capture("08-no-turn", ("연습 결과", "기록 보기"))
        click("좌회전 답변 수정")
        click("보지 못했어요")
        unseen = wait_report(lambda r: r["id"] == sid and r["revision"] > corrected["revision"], "unseen first event")
        assert turn_results(unseen)["LEFT"]["status"] == "HELD" and not unseen["assessment"]["mistake"], unseen
        checks["unseen_is_unassessed"] = list(turn_results(unseen).values())
        capture("09-unseen", ("연습 결과", "기록 보기"))
        click("좌회전 답변 수정")
        click("좌회전했어요")
        final = wait_report(lambda r: r["id"] == sid and r["revision"] > unseen["revision"], "reconfirmed first event")
        assert turn_results(final)["LEFT"]["mistake"] and turn_results(final)["RIGHT"]["status"] == "OBSERVED", final
        checks["reconfirmed_error_with_right_record_preserved"] = True
        click("오답노트 보기")
        capture("10-final-notebook", ("오답노트", "회전 전 깜빡이", "첫 번째 좌회전"))
except BaseException as error:
    checks["failure"] = type(error).__name__ + ": " + str(error)
    raise
finally:
    (out/"replay-inputs.json").write_text(json.dumps(replay, ensure_ascii=False, indent=2), encoding="utf-8")
    doors = baseline["body"]["doors"]
    for path, params in (
        ("/vehicle/controls", dict(speed=0, accelerator=0, brake=0, steering=baseline["controls"]["steering_deg"])),
        ("/vehicle/gear", dict(gear="Park")),
        ("/vehicle/body", dict(door_fl=int(doors["front_left"]), door_fr=int(doors["front_right"]), door_rl=int(doors["rear_left"]), door_rr=int(doors["rear_right"]))),
        ("/vehicle/turn-signal", dict(mode=baseline.get("lights", {}).get("turn_signal_mode", "Off"))),
    ):
        try:
            request(path, **params)
        except Exception as error:
            restore_errors.append(path + ": " + str(error))
    try:
        gps(restore_fix)
    except Exception as error:
        restore_errors.append("GPS restore: " + str(error))
    checks["restoration_complete"] = not restore_errors
    checks["restore_errors"] = restore_errors
    (out/"flow-checks.json").write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding="utf-8")
assert not restore_errors, restore_errors
print(json.dumps({"passed": [key for key in checks if key not in ("skipped_checks", "transport_retries")], "skipped": checks.get("skipped_checks", {}), "transportRetries": checks.get("transport_retries", [])}, ensure_ascii=False))
