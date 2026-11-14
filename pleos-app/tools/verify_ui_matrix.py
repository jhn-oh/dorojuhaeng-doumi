"""Capture real Pleos UI across two widths/themes/font sizes without relaunching the app."""
import argparse
import base64
import copy
from datetime import datetime, timezone
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
parser.add_argument("--serial", default="emulator-5580")
parser.add_argument("--output", required=True)
parser.add_argument("--stage", required=True, choices=("belt", "mirror", "doors", "course", "driving", "review", "result", "notebook"))
parser.add_argument("--expected-text", action="append", default=[])
parser.add_argument("--vehicle-review", action="store_true", help="Use the automatic vehicle review header and controls for new sessions")
parser.add_argument("--restore-width", type=int, choices=(841, 1682))
parser.add_argument("--font-mode", choices=("system", "app"), default="system")
args = parser.parse_args()
out = Path(args.output)
out.mkdir(parents=True, exist_ok=True)
stage_text = {"belt": "1 / 3", "mirror": "2 / 3", "doors": "3 / 3", "course": "경로 미리보기", "driving": "운전 연습", "review": "주차 후 확인", "result": "연습 결과", "notebook": "오답노트"}
if args.vehicle_review:
    stage_text["notebook"] = "주행 복습"
required = [stage_text[args.stage], *args.expected_text]
body_markers = {
    "belt": ("직접 확인했어요", "벨트를 맸어요", "다음"), "mirror": ("잘 보여요",),
    "doors": ("코스 보기", "모두 닫혔어요"),
    "course": ("이 코스로 시작", "기록 시작"), "driving": (), "review": ("보지 못했어요",),
    "result": ("차량 기록 보기",) if args.vehicle_review else ("오답노트 보기", "기록 보기"),
    "notebook": ("좌회전 다시 연습", "우회전 다시 연습", "두 회전 다시 연습", "다음 연습 준비", "새 연습 고르기"),
}[args.stage]
last_bounds = [853, 90, 2535, 1300]
checks = {"stage": args.stage, "fontMode": args.font_mode, "requiredText": required, "captures": [], "fontTransitions": [], "supplementalCaptures": [], "restorationErrors": [], "vehicleFontTransitions": []}
event_pattern = re.compile(r"(created|changed) activity=(\d+) fontScale=([\d.]+) night=(\d+) widthDp=(\d+) heightDp=(\d+)")
started = time.monotonic()
font_layouts = {}
font_conversions = {}
small_map_labels = {"© OpenStreetMap contributors", "연습 코스"}
header_taps = []
app_action_markers = ("기본 내비 열기",) if args.stage == "driving" else body_markers
checks["allowedHeaderTaps"] = header_taps


def adb(*parts, timeout=15):
    return subprocess.check_output([args.adb, "-s", args.serial, *parts], timeout=timeout).decode("utf-8", errors="replace").strip()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def focus_header(reason):
    # The inert app header is inside the observed window; never tap an app action.
    point = [last_bounds[0] + 250, last_bounds[1] + 80]
    event = {"atHostUtc": datetime.now(timezone.utc).isoformat(), "reason": reason, "point": point, "lastObservedAppBounds": list(last_bounds)}
    header_taps.append(event)
    print("Allowed header focus: " + json.dumps(event), flush=True)
    adb("shell", "input", "tap", *map(str, point))


def probe(allow_focus=False, focus_reason="initial_app_setup", click_text=None):
    global last_bounds
    last_error = ""
    for _ in range(3):
        command = ["am", "instrument", "--user", "10", "-w"]
        if click_text is not None:
            command += ["-e", "click", click_text]
        command += ["kr.juhyu.licensenote.driver/.UiDriver"]
        raw = adb("shell", " ".join(map(shlex.quote, command)), timeout=20)
        match = re.search(r"INSTRUMENTATION_RESULT: xml64=([^\r\n]+)", raw)
        if match:
            assert "INSTRUMENTATION_RESULT: error=" not in raw, raw
            root = ET.fromstring(base64.b64decode(match.group(1)))
            window = root.find("node")
            bounds = list(map(int, re.findall(r"-?\d+", window.get("bounds", "")))) if window is not None else []
            assert len(bounds) == 4, "Actual app window bounds missing"
            last_bounds = bounds
            return root
        last_error = raw
        if allow_focus:
            focus_header(focus_reason)
        time.sleep(.15)
    raise AssertionError("App window disappeared; no relaunch attempted: " + last_error)


def text_values(root):
    return [node.get("text") for node in root.iter("node") if node.get("text")]


def text_measurements(root):
    labels = {stage_text[args.stage], *body_markers, *small_map_labels, *(app_action_markers if args.font_mode == "app" else ())}
    measurements = {}
    for node in root.iter("node"):
        text = node.get("text")
        if text not in labels:
            continue
        bounds = list(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))
        if len(bounds) == 4:
            measured = {"bounds": bounds, "width": bounds[2] - bounds[0], "height": bounds[3] - bounds[1]}
            # Prefer the actual Text node over a merging Button's full-width bounds.
            if text not in measurements or measured["width"] < measurements[text]["width"]:
                measurements[text] = measured
    return measurements


def font_conversion(logs, font):
    pattern = re.compile(r"compose_font configuration=([\d.]+) base=([\d.]+) density=([\d.]+) base20px=([\d.]+) base24px=([\d.]+) base34px=([\d.]+)")
    found = []
    for line in logs.splitlines():
        match = pattern.search(line)
        if match:
            configuration, base, density, px20, px24, px34 = map(float, match.groups())
            if abs(configuration - float(font)) < .001 and abs(base - float(font)) < .001:
                found.append({"configuration": configuration, "base": base, "density": density, "sp20px": px20, "sp24px": px24, "sp34px": px34, "line": line})
    return found[-1] if found else None


def record_font_observation(metadata):
    key = (metadata["appWidthPixels"], metadata["nightMode"], metadata["fontScale"])
    observed = font_layouts.setdefault(key, {})
    for label, bounds in metadata["textMeasurements"].items():
        # Across scroll positions, retain the un-clipped observation with the greatest text area.
        if label not in observed or bounds["width"] * bounds["height"] > observed[label]["width"] * observed[label]["height"]:
            observed[label] = {**bounds, "source": metadata["file"]}
    if metadata["fontConversion"]:
        font_conversions[key] = {**metadata["fontConversion"], "source": metadata["file"]}


def wait_stage(width=None, timeout=6, allow_focus=False, focus_reason="initial_app_setup"):
    deadline = time.monotonic() + timeout
    while True:
        root = probe(allow_focus=allow_focus, focus_reason=focus_reason)
        visible = text_values(root)
        current_width = last_bounds[2] - last_bounds[0]
        body_ready = not body_markers or any(text in visible for text in body_markers)
        if all(text in visible for text in required) and body_ready and (width is None or current_width == width):
            return root
        if time.monotonic() >= deadline:
            raise AssertionError(f"Stage/width changed: width={current_width}, expected={width}, visible={visible}")
        time.sleep(.15)


def set_width(width):
    root = wait_stage()
    current_width = last_bounds[2] - last_bounds[0]
    assert current_width in (841, 1682), f"Unsupported initial width: {current_width}"
    if current_width != width:
        source, target = (2546, 1706) if width == 841 else (1706, 2546)
        adb("shell", "input", "swipe", str(source), "695", str(target), "695", "500")
        time.sleep(.2)
        focus_header("window_resize_setup")
        root = wait_stage(width, allow_focus=True, focus_reason="window_resize_setup")
    return root


def font_setting():
    return adb("shell", "settings", "--user", "10", "get", "system", "font_scale")


def app_font_event(logs):
    matches = []
    for line in logs.splitlines():
        match = re.search(r"app_text_scale factor=([\d.]+) system_font_scale=([\d.]+)", line)
        if match:
            matches.append({"factor": float(match.group(1)), "systemFontScale": float(match.group(2)), "line": line})
    return matches[-1] if matches else None


def current_app_font(logs, root=None):
    event = app_font_event(logs)
    if event:
        return {**event, "origin": "DEBUG_APP_TEXT_SCALE"}
    # A quiet page can outlive the log ring. Read the UI, or the real persisted baseline;
    # every changed value still requires a new DEBUG event in set_app_font.
    if root is not None:
        descriptions = {node.get("description") for node in root.iter("node")}
        for description, factor in (("글자 크기: 기본", 1.0), ("글자 크기: 크게", 1.3)):
            if description in descriptions:
                return {"factor": factor, "origin": "VISIBLE_HEADER_BASELINE"}
    data = adb("shell", "run-as", "kr.juhyu.licensenote", "--user", "10", "cat", "shared_prefs/practice_preferences.xml")
    preference = ET.fromstring(data).find("float[@name='ui_text_scale']")
    factor = float(preference.get("value")) if preference is not None else 1.0
    assert factor in (1.0, 1.3), factor
    return {"factor": factor, "origin": "PERSISTED_BASELINE_ONLY"}


def native_surfaces():
    packages = ("ai.umos.homescreen", "ai.umos.drivingview", "ai.umos.maps.android.navigation.app")
    processes = {}
    for package in packages:
        result = subprocess.run([args.adb, "-s", args.serial, "shell", "pidof", package], capture_output=True, timeout=15)
        processes[package] = result.stdout.decode("utf-8", errors="replace").strip() if result.returncode == 0 else ""
    activities = adb("shell", "dumpsys", "activity", "activities")
    records = {package: sorted(set(re.findall(r"ActivityRecord\{([0-9a-f]+)\s+u\d+\s+" + re.escape(package) + r"/", activities))) for package in packages}
    return {"processes": processes, "activityRecords": records}


def vehicle_request(path, **params):
    suffix = "?" + urllib.parse.urlencode(params) if params else ""
    request = urllib.request.Request("http://127.0.0.1:8088" + path + suffix, method="PUT" if params else "GET")
    return json.load(urllib.request.urlopen(request, timeout=10))


def wait_vehicle(parked, timeout=12):
    deadline = time.monotonic() + timeout
    while True:
        signals = json.loads(adb("shell", "run-as", "kr.juhyu.licensenote", "--user", "10", "cat", "files/sdk-state.json"))
        now = int(float(adb("shell", "cat", "/proc/uptime").split()[0])*1000)
        speed, gear, ux = (signals.get(key, {}) for key in ("speed", "gear", "ux"))
        fresh = all(signal.get("quality") == "VALID" and 0 <= now-signal.get("receivedMs", 0) < 2500 for signal in (speed, gear))
        speed_value = float(speed.get("value") or "nan")
        correct = abs(speed_value) < .05 and gear.get("value") == "4" and ux.get("value") == "false,0" if parked else abs(speed_value-5.0) < .05 and gear.get("value") == "8" and str(ux.get("value")).startswith("true,")
        if fresh and correct and ux.get("quality") == "VALID":
            return {"deviceElapsedMs": now, **{key: signals.get(key) for key in ("speed", "gear", "ux")}}
        assert time.monotonic() < deadline, f"Vehicle did not reach {'parked' if parked else 'moving'} state: {speed, gear, ux}"
        time.sleep(.2)


def set_app_font(value):
    _, logs = ui_events()
    initial = current_app_font(logs)
    if abs(initial["factor"]-float(value)) < .001:
        return False
    system_before = font_setting()
    before_bounds = list(last_bounds)
    vehicle = None
    transition = {"from": initial["factor"], "to": float(value), "fontMode": "app", "systemFontSettingBefore": system_before}
    if args.stage == "driving":
        vehicle = vehicle_request("/vehicle/state")
        assert vehicle["gear"]["actual"]["value"] == "Drive" and abs(vehicle["controls"]["speed_kph"]-18) < .01, vehicle
        transition["vehicleBaseline"] = {"gear": "Drive", "controls": vehicle["controls"]}
    opened = False
    try:
        if vehicle is not None:
            controls = vehicle["controls"]
            transition["parkControlInput"] = dict(speed=0, accelerator=0, brake=0, steering=controls["steering_deg"])
            vehicle_request("/vehicle/controls", **transition["parkControlInput"])
            vehicle_request("/vehicle/gear", gear="Park")
            transition["parkedSignals"] = wait_vehicle(True)
        root = wait_stage()
        ready_until = time.monotonic()+6
        while "글자" not in text_values(root):
            assert time.monotonic() < ready_until, "Parked app text-size control is unavailable"
            time.sleep(.15)
            root = wait_stage()
        transition["nativeBefore"] = native_surfaces()
        probe(click_text="글자")
        opened = True
        probe(click_text="크게" if float(value) > 1 else "기본")
        observed_from = time.monotonic()
        deadline = observed_from+2
        while True:
            _, logs = ui_events()
            event = app_font_event(logs)
            if event and abs(event["factor"]-float(value)) < .001:
                break
            assert time.monotonic() < deadline, f"App text-scale did not apply within two seconds: {event}"
            time.sleep(.1)
        transition["observedAfterClickReturnMs"] = round((time.monotonic()-observed_from)*1000)
        transition["appFontEvent"] = event
        assert abs(event["systemFontScale"]-initial_setting_font) < .001, event
        assert font_setting() == system_before, "App text-size UI changed the global system font setting"
    finally:
        try:
            if opened:
                probe(click_text="닫기")
            root = wait_stage()
            assert last_bounds == before_bounds, (before_bounds, last_bounds)
            if "nativeBefore" in transition:
                transition["nativeAfter"] = native_surfaces()
                assert transition["nativeAfter"] == transition["nativeBefore"], "Native process or Activity restarted during local app text-size change"
            transition["appBoundsUnchanged"] = True
            transition["systemFontSettingAfter"] = font_setting()
            assert transition["systemFontSettingAfter"] == system_before
        finally:
            checks["vehicleFontTransitions"].append(transition)
            if vehicle is not None:
                controls = vehicle["controls"]
                vehicle_request("/vehicle/gear", gear="Drive")
                transition["movingControlInput"] = dict(speed=controls["speed_kph"], accelerator=controls["accelerator_pct"], brake=controls["brake_pct"], steering=controls["steering_deg"])
                vehicle_request("/vehicle/controls", **transition["movingControlInput"])
                transition["movingSignalsRestored"] = wait_vehicle(False)
                moving_root = wait_stage()
                assert "글자" not in text_values(moving_root) and "기록 종료" not in text_values(moving_root), "Moving UI exposed parked text/review actions"
    return True


def set_font(value):
    if args.font_mode == "app":
        return set_app_font(value)
    current = font_setting()
    current_scale = 1.0 if current == "null" else float(current)
    if abs(current_scale - float(value)) >= .001:
        adb("shell", "settings", "--user", "10", "put", "system", "font_scale", value)
        # Let the Pleos DrivingView restart and any in-app focus restoration settle without external input.
        time.sleep(15)
        return True
    return False


def night_setting():
    value = adb("shell", "cmd", "uimode", "night")
    match = re.search(r"Night mode:\s*(\w+)", value, re.IGNORECASE)
    assert match, "Unknown system night mode: " + value
    return match.group(1).lower()


def set_night(value):
    if night_setting() != value:
        adb("shell", "cmd", "uimode", "night", value)


def ui_events():
    current_pid = adb("shell", "pidof", "kr.juhyu.licensenote")
    assert current_pid == app_pid, f"App process changed: {app_pid} -> {current_pid}"
    raw = adb("shell", "logcat", "-d", "-v", "epoch", "--pid=" + app_pid, "-s", "LicenseUi:I", "*:S")
    events = []
    for line in raw.splitlines():
        match = event_pattern.search(line)
        if match:
            event, activity, font, night, width, height = match.groups()
            events.append({"event": event, "activity": activity, "fontScale": float(font), "night": int(night), "widthDp": int(width), "heightDp": int(height), "line": line})
    return events, raw


def current_configuration(width, font, night, timeout=4):
    deadline = time.monotonic() + timeout
    expected_night = 32 if night == "yes" else 16
    while True:
        events, raw = ui_events()
        # Inspect after every configuration, so an intermediate recreation cannot be hidden by a later capture.
        for event in events:
            if event["event"] == "created" and event["line"] not in creations_before:
                raise AssertionError("Activity recreated during matrix: " + event["line"])
            if event["line"] not in event_lines_before:
                assert event["activity"] == activity_id, "Activity identity changed: " + event["line"]
        current = events[-1] if events else None
        expected_system_font = initial_setting_font if args.font_mode == "app" else float(font)
        app_event = current_app_font(raw) if args.font_mode == "app" else None
        app_ready = args.font_mode != "app" or (app_event and abs(app_event["factor"]-float(font)) < .001 and abs(app_event.get("systemFontScale", expected_system_font)-expected_system_font) < .001)
        if current and current["activity"] == activity_id and current["widthDp"] == width and abs(current["fontScale"] - expected_system_font) < .01 and current["night"] == expected_night and app_ready:
            if args.font_mode == "app":
                assert font_setting() == original_font, "Global system font changed during app-font matrix"
            return current, raw
        if time.monotonic() >= deadline:
            raise AssertionError(f"Actual app configuration did not apply: expected={(width, font, night)}, observed={current}")
        time.sleep(.15)


def map_ready(root, timeout=40, allow_hidden=False):
    if args.stage not in ("course", "driving"):
        return root, None
    deadline = time.monotonic() + timeout
    while True:
        statuses = [value for node in root.iter("node") for value in (node.get("description"), node.get("text"))
                    if value and (value.startswith("지도 준비") or value.startswith("지도 불러오기 실패"))]
        status = statuses[0] if statuses else None
        # A compact course preview may be below the fold. Do not navigate/scroll the app to conceal that layout.
        if (status is None and (args.stage == "course" or allow_hidden)) or (status and status.startswith("지도 준비 완료")):
            return root, status
        if (status and "실패" in status) or time.monotonic() >= deadline:
            raise AssertionError("Map did not become ready: " + str(status))
        time.sleep(.2)
        root = wait_stage()


def capture(name, root, width, font, night, map_status, **extra):
    time.sleep(.6)  # Accessibility semantics can precede the first rendered frame.
    (out / (name + ".xml")).write_text(ET.tostring(root, encoding="unicode"), encoding="utf-8")
    pixels = subprocess.check_output([args.adb, "-s", args.serial, "exec-out", "screencap", "-p"], timeout=20)
    assert pixels.startswith(b"\x89PNG\r\n\x1a\n"), "Screenshot was not PNG"
    (out / (name + ".png")).write_bytes(pixels)
    config, logs = current_configuration(width, font, night)
    metadata = {"file": name + ".png", "capturedAtHostUtc": datetime.now(timezone.utc).isoformat(), "stage": args.stage, "fontMode": args.font_mode, "appBounds": list(last_bounds), "appWidthPixels": width, "appHeightPixels": last_bounds[3] - last_bounds[1], "fontScale": float(font), "systemFontScale": config["fontScale"], "appFontEvent": app_font_event(logs), "nightMode": night, "configuration": config, "fontConversion": font_conversion(logs, font) if args.font_mode == "system" else None, "mapStatus": map_status, "requiredText": required, "visibleText": text_values(root), "visibleDescriptions": [node.get("description") for node in root.iter("node") if node.get("description")], "textMeasurements": text_measurements(root), "allowedHeaderTapCount": len(header_taps), "allowedHeaderTaps": list(header_taps), **extra}
    write_json(out / (name + ".metadata.json"), metadata)
    (out / (name + ".configuration.log")).write_text(logs, encoding="utf-8")
    record_font_observation(metadata)
    return metadata


def verify_font_rendering():
    if args.font_mode == "app":
        results = []
        for width in (1682, 841):
            for night in ("no", "yes"):
                regular, large = font_layouts[(width, night, 1.0)], font_layouts[(width, night, 1.3)]
                matching_actions = set(app_action_markers).intersection(regular, large)
                assert matching_actions, "Matching primary action text is missing at both app text sizes"
                for label in (stage_text[args.stage], *sorted(matching_actions)):
                    assert label in regular and label in large, "Header text observation missing: " + label
                    normal, big = regular[label], large[label]
                    item = {"width": width, "nightMode": night, "label": label, "regular": normal, "large": big,
                            "widthRatio": round(big["width"]/max(normal["width"], 1), 3), "heightRatio": round(big["height"]/max(normal["height"], 1), 3)}
                    results.append(item)
                    assert item["widthRatio"] >= 1.15 and item["heightRatio"] >= 1.15, f"App header/action glyph bounds did not enlarge: {item}"
        checks["fontRenderingPolicy"] = {"policy": "APP_TEXT_TOKEN_MULTIPLIER", "factor": 1.3, "minimumHeaderAndActionGrowthRatio": 1.15, "systemFontSettingUnchanged": True,
                                         "note": "App dialog multiplies its text tokens while the original system font remains in effect; this is separate from system-font verification."}
        checks["fontRendering"] = results
        checks["fontRenderingVerified"] = True
        return
    results = []
    policy = {"policy": "NON_LINEAR_ANDROID14", "fontScale": 1.3,
              "expectedDp": {"20sp": 23.6, "24sp": 26.4, "34sp": 34.0},
              "note": "Large headers/actions may remain unchanged; no uniform 1.3x text-growth requirement."}
    # The converter is independent of window width/theme. Keep its actual DEBUG source when a quiet baseline row has no new log.
    converters = {font: next((value for key, value in font_conversions.items() if key[2] == font), None) for font in (1.0, 1.3)}
    assert all(converters.values()), "Native Compose font conversion evidence is missing"
    base, scaled = converters[1.0], converters[1.3]
    for size, expected in ((20, 23.6), (24, 26.4), (34, 34.0)):
        assert abs(base[f"sp{size}px"] / base["density"] - size) <= .25, f"Unexpected regular {size}sp conversion"
        observed_dp = scaled[f"sp{size}px"] / scaled["density"]
        assert abs(observed_dp - expected) <= .25, f"Unexpected Android 14 nonlinear {size}sp conversion: {observed_dp}"
    policy["converterEvidence"] = {"regular": base, "large": scaled}
    checks["fontRenderingPolicy"] = policy
    for width in (1682, 841):
        for night in ("no", "yes"):
            regular = font_layouts[(width, night, 1.0)]
            large = font_layouts[(width, night, 1.3)]
            labels = set(regular).intersection(large)
            assert stage_text[args.stage] in labels, "Stable header font measurement is missing"
            assert not body_markers or labels.intersection(body_markers), "Matching primary action label is missing"
            for label in sorted(labels):
                normal, big = regular[label], large[label]
                width_ratio = big["width"] / max(normal["width"], 1)
                height_ratio = big["height"] / max(normal["height"], 1)
                item = {"width": width, "nightMode": night, "label": label, "regular": normal, "large": big, "widthRatio": round(width_ratio, 3), "heightRatio": round(height_ratio, 3)}
                results.append(item)
                checks["fontRendering"] = results
                if args.stage in ("course", "driving") and label == "© OpenStreetMap contributors":
                    assert width_ratio >= 1.10, f"The 20sp map caption did not follow nonlinear enlargement: {item}"
            if args.stage in ("course", "driving"):
                assert "© OpenStreetMap contributors" in labels, "Need an actual 20sp caption observation at both font settings; scroll the course preview into view"
    checks["fontRenderingVerified"] = True


def scroll_signature(root):
    signature = copy.deepcopy(root)
    if args.stage == "driving":
        # Digits change both the clock text and its intrinsic bounds. Other text/bounds remain strict.
        for parent in signature.iter():
            for child in list(parent):
                if re.fullmatch(r"(?:기록 중\s*·\s*)?\d+분\s*\d+초(?:\s*기록 중|\s*·\s*.+ 모드)?", child.get("text", "").strip()):
                    parent.remove(child)
    return ET.tostring(signature, encoding="unicode")


def swipe_body(forward, width):
    before = wait_stage(width)
    nodes = [node for node in before.iter("node") if node.get("scrollable") == "true"]
    index = 1 if args.stage == "result" and width == 1682 and len(nodes) > 1 else 0
    command = ["am", "instrument", "--user", "10", "-w", "-e", "scroll", "forward" if forward else "backward", "-e", "scrollIndex", str(index), "kr.juhyu.licensenote.driver/.UiDriver"]
    raw = adb("shell", " ".join(map(shlex.quote, command)), timeout=20)
    assert "INSTRUMENTATION_RESULT: error=" not in raw, raw
    status = re.search(r"INSTRUMENTATION_RESULT: scrollStatus=([^\r\n]+)", raw)
    accepted = re.search(r"INSTRUMENTATION_RESULT: scrollAccepted=(true|false)", raw)
    count = re.search(r"INSTRUMENTATION_RESULT: scrollableCount=(\d+)", raw)
    assert status and accepted and count, "UiDriver lacks semantic-scroll support: " + raw
    assert status.group(1) in ("NO_SCROLLABLE", "END_REACHED", "SCROLL_REQUESTED"), raw
    time.sleep(.25)
    after = wait_stage(width)
    changed = scroll_signature(before) != scroll_signature(after)
    action = {"direction": "forward" if forward else "backward", "requestedIndex": index, "scrollableCount": int(count.group(1)), "status": status.group(1), "actionAccepted": accepted.group(1) == "true", "positionChanged": changed, "atHostUtc": datetime.now(timezone.utc).isoformat()}
    checks.setdefault("semanticScrollActions", []).append(action)
    if not action["actionAccepted"]:
        assert not changed, "Driver reported no-scroll/end but static viewport changed: " + str(action)
    return after


def supplemental_scroll(base_name, width, font, night, map_status):
    root = wait_stage(width)
    original = scroll_signature(root)
    previous = original
    map_seen = bool(map_status and map_status.startswith("지도 준비 완료"))
    attempts = 0
    try:
        for index in range(1, 4):
            attempts += 1
            root = swipe_body(True, width)
            root, status = map_ready(root, allow_hidden=True)
            map_seen = map_seen or bool(status and status.startswith("지도 준비 완료"))
            current = scroll_signature(root)
            if current == previous:
                break
            name = f"{base_name}-scrolled-{index}"
            capture(name, root, width, font, night, status, supplemental=True, scrollAttempt=index)
            checks["supplementalCaptures"].append({"file": name + ".png", "width": width, "fontScale": float(font), "nightMode": night, "mapStatus": status})
            previous = current
        if args.stage == "course":
            assert map_seen, "Course map was not accessible after three real body scrolls"
    finally:
        for _ in range(attempts):
            root = swipe_body(False, width)
            if scroll_signature(root) == original:
                break
        assert scroll_signature(root) == original, "Could not restore the original body scroll position"


failure = None
original_font = None
original_app_font = None
original_night = None
restore_width = None
try:
    assert adb("shell", "getprop", "ro.kernel.qemu") == "1", "Emulator-only verification"
    assert adb("shell", "am", "get-current-user") == "10", "Expected the Pleos driver user 10"
    initial_root = wait_stage(allow_focus=True)
    original_width = last_bounds[2] - last_bounds[0]
    restore_width = args.restore_width or original_width
    original_font, original_night = font_setting(), night_setting()
    app_pid = adb("shell", "pidof", "kr.juhyu.licensenote")
    assert app_pid.isdigit(), "Expected exactly one app process"
    events_before, logs_before = ui_events()
    if args.font_mode == "app" and not events_before:
        # Refresh actual width/configuration evidence through allowed window setup, never a font fallback tap.
        set_width(841 if original_width == 1682 else 1682)
        set_width(original_width)
        events_before, logs_before = ui_events()
        checks["configurationBaselineRefreshedByWindowSetup"] = True
    event_lines_before = {event["line"] for event in events_before}
    (out / "configuration-before.log").write_text(logs_before, encoding="utf-8")
    creations_before = {event["line"] for event in events_before if event["event"] == "created"}
    baseline_path = out.parent / "ui-activity-baseline.json"
    if creations_before:
        baseline = [event for event in events_before if event["event"] == "created"][-1]
        write_json(baseline_path, {"pid": app_pid, "creation": baseline})
    else:
        previous = json.loads(baseline_path.read_text(encoding="utf-8")) if baseline_path.exists() else {}
        if previous.get("pid") == app_pid:
            baseline = previous["creation"]
        else:
            assert args.font_mode == "app" and events_before, "Initial Activity creation log missing; start matrix earlier in the same verification run"
            baseline = events_before[-1]
            checks["baselineOrigin"] = "OBSERVED_CONFIGURATION_EVENT"
            write_json(baseline_path, {"pid": app_pid, "creation": baseline, "origin": "OBSERVED_CONFIGURATION_EVENT"})
        creations_before.add(baseline["line"])
    activity_id = baseline["activity"]
    assert events_before, "No actual app configuration event is available"
    initial_actual_font = events_before[-1]["fontScale"]
    initial_setting_font = 1.0 if original_font == "null" else float(original_font)
    assert abs(initial_actual_font - initial_setting_font) < .001, f"Initial font setting/configuration mismatch: {initial_setting_font}/{initial_actual_font}"
    checks.update({"pid": app_pid, "activityIdentity": activity_id, "baselineCreation": baseline if baseline["event"] == "created" else None, "baselineConfigurationEvent": baseline, "originalFontScale": original_font, "originalNightMode": original_night, "originalWidth": original_width})
    if args.font_mode == "app":
        original_app_event = current_app_font(logs_before, initial_root)
        original_app_font = original_app_event["factor"]
        checks["originalAppTextScale"] = original_app_font
        checks["initialAppTextScaleEvidence"] = original_app_event
    checks["initialActualFontScale"] = initial_actual_font
    # Change one dimension at a time to avoid redundant font-triggered system restarts.
    combinations = ((1682, "no", "1.0"), (1682, "no", "1.3"), (1682, "yes", "1.3"), (1682, "yes", "1.0"),
                    (841, "yes", "1.0"), (841, "yes", "1.3"), (841, "no", "1.3"), (841, "no", "1.0"))
    for width, night, font in combinations:
        set_width(width)
        set_night(night)
        taps_before_font = len(header_taps)
        before_events, before_logs = ui_events()
        assert before_events, "Actual app configuration is unavailable before font change"
        actual_font_before = current_app_font(before_logs)["factor"] if args.font_mode == "app" else before_events[-1]["fontScale"]
        setting_before = font_setting()
        setting_font_before = 1.0 if setting_before == "null" else float(setting_before)
        if args.font_mode == "system":
            assert abs(actual_font_before - setting_font_before) < .001, "Font setting and app configuration diverged before transition"
        else:
            assert setting_before == original_font, "App-font matrix changed global font setting"
        font_changed = set_font(font)
        config, logs = current_configuration(width, font, night)
        actual_font_after = current_app_font(logs)["factor"] if args.font_mode == "app" else config["fontScale"]
        assert font_changed == (abs(actual_font_before - actual_font_after) >= .001), "Requested font transition was not an actual font change"
        if font_changed:
            event = app_font_event(logs) if args.font_mode == "app" else None
            checks["fontTransitions"].append({"from": actual_font_before, "to": actual_font_after, "fontMode": args.font_mode, "activityIdentity": config["activity"], "configurationLine": config["line"], "appFontEvent": event})
        if args.font_mode == "app" and args.stage == "driving":
            wait_vehicle(False)
            moving_text = text_values(wait_stage(width))
            assert "글자" not in moving_text and "기록 종료" not in moving_text, "Moving UI exposed parked text/review actions"
        root, map_status = map_ready(wait_stage(width))
        assert len(header_taps) == taps_before_font, "Font verification used an external header focus correction"
        name = f"{args.stage}-{'wide' if width == 1682 else 'narrow'}-{'night' if night == 'yes' else 'day'}-font-{font}"
        metadata = capture(name, root, width, font, night, map_status, actualFontTransition=font_changed, fontTransitionExternalHeaderTaps=len(header_taps) - taps_before_font)
        checks["captures"].append({"file": name + ".png", "width": width, "fontScale": float(font), "nightMode": night, "activityIdentity": config["activity"], "actualFontTransition": font_changed})
        print("Captured " + name, flush=True)
        map_caption_hidden = args.stage in ("course", "driving") and "© OpenStreetMap contributors" not in metadata["textMeasurements"]
        scroll_stages = ("course", "driving", "review", "result", "notebook") if args.font_mode == "app" else ("course", "driving", "notebook")
        if (font == "1.3" and args.stage in scroll_stages) or map_caption_hidden:
            supplemental_scroll(name, width, font, night, map_status)
    transitions = {(round(item["from"], 1), round(item["to"], 1)) for item in checks["fontTransitions"]}
    assert {(1.0, 1.3), (1.3, 1.0)}.issubset(transitions), "Both real font transition directions must be verified"
    checks["fontTransitionsVerified"] = True
    verify_font_rendering()
    checks["matrixPassed"] = len(checks["captures"]) == 8
except Exception as error:
    failure = error
    checks["error"] = str(error)
    checks["matrixPassed"] = False
    try:
        (out / "failure.png").write_bytes(subprocess.check_output([args.adb, "-s", args.serial, "exec-out", "screencap", "-p"], timeout=20))
        if "app_pid" in globals():
            _, logs = ui_events()
            (out / "configuration-failure.log").write_text(logs, encoding="utf-8")
    except Exception as capture_error:
        checks["failureCaptureError"] = str(capture_error)
finally:
    for label, action in (
        ("font", lambda: set_font(str(original_app_font)) if args.font_mode == "app" else adb("shell", "settings", "--user", "10", "delete", "system", "font_scale") if original_font == "null" else set_font(original_font)),
        ("night", lambda: set_night(original_night)),
        ("width", lambda: set_width(restore_width)),
    ):
        if {"font": original_app_font if args.font_mode == "app" else original_font, "night": original_night, "width": restore_width}[label] is not None:
            try:
                action()
            except Exception as error:
                checks["restorationErrors"].append(label + ": " + str(error))
    checks["durationSeconds"] = round(time.monotonic() - started, 2)
    if args.font_mode == "app" and original_font is not None:
        try:
            checks["finalSystemFontSetting"] = font_setting()
            assert checks["finalSystemFontSetting"] == original_font, "Global font setting changed in app-font mode"
        except Exception as error:
            checks["restorationErrors"].append("global font unchanged: " + str(error))
    checks["restorationComplete"] = not checks["restorationErrors"]
    checks["passed"] = checks.get("matrixPassed", False) and checks["restorationComplete"]
    checks["status"] = "PASS" if checks["passed"] else "FAIL"
    write_json(out / "matrix-checks.json", checks)
if failure:
    raise failure
assert checks["restorationComplete"], checks["restorationErrors"]
print(json.dumps({"matrixPassed": checks["matrixPassed"], "captures": len(checks["captures"]), "durationSeconds": checks["durationSeconds"], "restorationComplete": True}), flush=True)
