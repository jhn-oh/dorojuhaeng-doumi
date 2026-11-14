"""Record actual Android display while an emulator-only UI/vehicle scenario runs."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--adb", default=r"C:\AndroidSDK\platform-tools\adb.exe")
parser.add_argument("--serial", default="emulator-5580")
parser.add_argument("--output", required=True)
parser.add_argument("--seconds", type=int, default=180)
parser.add_argument("command", nargs=argparse.REMAINDER)
args = parser.parse_args()
assert 1 <= args.seconds <= 180
command = args.command[1:] if args.command[:1] == ["--"] else args.command
assert command, "Provide the real UI scenario after --"
out = Path(args.output).resolve()
out.mkdir(parents=True, exist_ok=True)
prefix = [args.adb, "-s", args.serial]
ffmpeg = Path(__file__).resolve().parents[2] / ".tools/video/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v9.0.2.exe"
assert ffmpeg.is_file(), "Local ffmpeg is required to join the actual recording parts"

def adb(*parts, timeout=30):
    return subprocess.check_output(prefix + list(parts), timeout=timeout).decode("utf-8", errors="replace").strip()

assert adb("shell", "getprop", "ro.kernel.qemu") == "1", "Emulator-only capture"
capture_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%f") + "-" + uuid.uuid4().hex[:12]
started = time.monotonic()
metadata = {"capturedAtHostUtc": datetime.now(timezone.utc).isoformat(), "source": "Actual Android screenrecord",
            "serial": args.serial, "continuous": True, "audio": False, "command": command,
            "vehicleInputs": "Virtual Console and emulator GPS; not physical-road driving",
            "partTimeLimitSeconds": args.seconds, "parts": [], "captureErrors": []}
installed = adb("shell", "pm", "path", "--user", "10", "kr.juhyu.licensenote").removeprefix("package:").strip()
metadata["installedApkSha256"] = adb("shell", "sha256sum", installed).split()[0]
package = adb("shell", "dumpsys", "package", "kr.juhyu.licensenote")
metadata["versionName"] = re.search(r"versionName=([^\s]+)", package).group(1)


def matching_pid(remote, deadline=None):
    def read(*parts):
        remaining = 30 if deadline is None else deadline - time.monotonic()
        if remaining <= 0:
            raise subprocess.TimeoutExpired("screenrecord identity lookup", 0)
        return adb(*parts, timeout=remaining)

    try:
        candidates = read("shell", "pidof", "screenrecord").split()
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        candidates = []
    matches = []
    for pid in candidates:
        if not pid.isdigit():
            continue
        try:
            argv = read("shell", "cat", "/proc/" + pid + "/cmdline").split("\0")
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            continue  # The time-limited encoder may have exited while checking it.
        if argv and Path(argv[0]).name == "screenrecord" and remote in argv:
            matches.append(pid)
    assert len(matches) <= 1, "Capture identity was not unique; left encoders running to their own limits"
    return matches[0] if matches else None


recordings = []


def start_part(log):
    number = len(metadata["parts"]) + 1
    name = f"actual-emulator-{capture_id}-part-{number:03}.mp4"
    part = {"number": number, "file": name, "remoteFile": "/data/local/tmp/licensenote-" + name,
            "startedAtHostUtc": datetime.now(timezone.utc).isoformat(),
            "startTimeBasis": "Host launch request, not an encoded frame timestamp"}
    metadata["parts"].append(part)
    process = subprocess.Popen(prefix + ["shell", "screenrecord", "--size", "1920x1080", "--bit-rate", "6000000",
                                         "--time-limit", str(args.seconds), part["remoteFile"]], stdout=log, stderr=log,
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    # Track before identity lookup can fail, including a failure during the second-part handoff.
    recordings.append((process, part))
    deadline = time.monotonic() + 5
    part["remotePid"] = None
    while process.poll() is None and time.monotonic() < deadline:
        part["remotePid"] = matching_pid(part["remoteFile"], deadline)
        if part["remotePid"]:
            break
        time.sleep(min(.1, max(0, deadline - time.monotonic())))
    assert process.poll() is None and part["remotePid"], "screenrecord did not start; see recording.log"
    return process, part


def finish_part(process, part, interrupt=False):
    if part.get("finalized"):
        return
    if interrupt and process.poll() is None:
        deadline = time.monotonic() + 5
        pid = None
        while process.poll() is None and time.monotonic() < deadline:
            pid = matching_pid(part["remoteFile"], deadline)
            if pid:
                break
            time.sleep(min(.1, max(0, deadline - time.monotonic())))
        if pid:
            assert part.get("remotePid") in (None, pid), "Encoder PID changed; no signal was sent"
            if part.get("remotePid") is None:
                # A failed startup lookup can recover only this exact UUID file's encoder.
                part["remotePid"] = pid
                part["pidRecoveredDuringStop"] = True
            adb("shell", "kill", "-2", pid)
            part["ownedSigintSent"] = True
    part["encoderExitCode"] = process.wait(timeout=20)
    part["endedAtHostUtc"] = datetime.now(timezone.utc).isoformat()
    part["finalized"] = True
    assert part["encoderExitCode"] == 0, "Encoder exited unexpectedly; actual parts will be preserved"


recording = None
current = None
scenario = None
with (out / "recording.log").open("wb") as log:
    try:
        recording, current = start_part(log)
        scenario = subprocess.Popen(command)
        while scenario.poll() is None:
            if recording.poll() is not None:
                finish_part(recording, current)
                assert current["encoderExitCode"] == 0, "Encoder exited unexpectedly; actual parts will be preserved"
                if scenario.poll() is None:
                    # Restart before any pulls or concatenation to keep the part-boundary gap short.
                    recording, current = start_part(log)
            time.sleep(.2)
        metadata["scenarioExitCode"] = scenario.returncode
    except BaseException as error:
        metadata["captureErrors"].append(str(error))
        raise
    finally:
        # Include a just-spawned encoder even when start_part never returned its tuple.
        for process, part in recordings:
            try:
                finish_part(process, part, interrupt=True)
            except Exception as error:
                part["finalizationError"] = str(error)
                metadata["captureErrors"].append("stop part " + str(part["number"]) + ": " + str(error))
        # A capture error must not abandon an already running scenario's input-restoration cleanup.
        if scenario is not None and "scenarioExitCode" not in metadata:
            metadata["scenarioExitCode"] = scenario.wait()
        for part in metadata["parts"]:
            local = out / part["file"]
            try:
                assert part.get("finalized"), "Unfinalized encoder; remote file preserved without pulling partial MP4"
                subprocess.run(prefix + ["pull", part["remoteFile"], str(local)], check=True, timeout=60)
                assert local.stat().st_size > 0, "Empty capture part"
                part.update({"sha256": hashlib.sha256(local.read_bytes()).hexdigest(), "bytes": local.stat().st_size})
            except Exception as error:
                part["pullError"] = str(error)
                metadata["captureErrors"].append("pull part " + str(part["number"]) + ": " + str(error))
        metadata["continuous"] = len(metadata["parts"]) == 1
        if not metadata["continuous"]:
            metadata["possibleGapBetweenParts"] = True
            metadata["gapNote"] = "Android screenrecord was restarted between parts; small capture gaps are possible. Stream-copy concatenation does not invent or fill frames."
        if metadata["parts"] and all("sha256" in part for part in metadata["parts"]):
            manifest = out / ("concat-" + capture_id + ".txt")
            manifest.write_text("".join("file '" + part["file"] + "'\n" for part in metadata["parts"]), encoding="utf-8")
            try:
                with (out / "concatenation.log").open("wb") as concat_log:
                    subprocess.run([str(ffmpeg), "-hide_banner", "-xerror", "-err_detect", "explode", "-y", "-f", "concat", "-safe", "0", "-i", manifest.name,
                                    "-map", "0:v:0", "-an", "-c", "copy", "actual-emulator.mp4"], cwd=out, stdout=concat_log, stderr=concat_log, check=True, timeout=120)
                concat_text = (out / "concatenation.log").read_text(encoding="utf-8", errors="replace")
                assert not re.search(r"moov atom not found|Impossible to open|Invalid data|Error (?:during demuxing|opening input)", concat_text, re.I), "Concatenation reported invalid input; partial output is not a successful capture"
                local = out / "actual-emulator.mp4"
                assert local.stat().st_size > 100_000, "No usable screen recording"
                metadata.update({"file": local.name, "sha256": hashlib.sha256(local.read_bytes()).hexdigest(), "bytes": local.stat().st_size,
                                 "concatenation": "ffmpeg concat demuxer, -c copy; original parts retained", "concatManifest": manifest.name})
            except Exception as error:
                metadata["captureErrors"].append("concat: " + str(error))
        metadata["durationWallSeconds"] = round(time.monotonic() - started, 2)
        (out / "capture.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
assert metadata["scenarioExitCode"] == 0, "Scenario failed; the actual failure footage is preserved"
assert not metadata["captureErrors"] and "file" in metadata, "Capture failed; available actual parts and logs are preserved"
print(json.dumps(metadata, ensure_ascii=False), flush=True)
