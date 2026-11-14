"""Emit synthetic GPS fixes using the public Android Emulator geo nmea command."""
import argparse
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import subprocess
import time


# Same six OSM vertices and order as verify_turn_flow.py.
YANGJAE = (
    (37.4844415, 127.0321124), (37.4849111, 127.0318935),
    (37.4847141, 127.0304318), (37.4846113, 127.0296948),
    (37.4852267, 127.0295627), (37.4860285, 127.0293493),
)


def checksum(body):
    value = 0
    for byte in body.encode("ascii"):
        value ^= byte
    return f"{value:02X}"


def sentence(body):
    return f"${body}*{checksum(body)}"


def valid_checksum(raw):
    body, star, suffix = raw.partition("*")
    return body.startswith("$") and star == "*" and checksum(body[1:]) == suffix


def coordinate(value, longitude):
    limit = 180 if longitude else 90
    if not math.isfinite(value) or abs(value) > limit:
        raise ValueError("Coordinate outside GPS range")
    # Quantize first so minutes rolling up to 60 carry into the degree field.
    units = round(abs(value) * 60 * 10_000)
    degrees, minutes = divmod(units, 600_000)
    width = 3 if longitude else 2
    hemisphere = ("E" if value >= 0 else "W") if longitude else ("N" if value >= 0 else "S")
    return f"{degrees:0{width}d}{minutes // 10_000:02d}.{minutes % 10_000:04d},{hemisphere}"


def nmea_pair(utc, point, speed_kph, heading, altitude_m, satellites):
    if not (math.isfinite(speed_kph) and speed_kph >= 0 and 0 <= heading < 360
            and math.isfinite(altitude_m) and 1 <= satellites <= 12):
        raise ValueError("Invalid synthetic speed, course, altitude, or satellite count")
    if utc.tzinfo is None:
        raise ValueError("An explicit UTC offset is required")
    utc = utc.astimezone(timezone.utc)
    fix_time = utc.strftime("%H%M%S")  # Integer seconds also supported by the ranchu RMC parser.
    coords = f"{coordinate(point[0], False)},{coordinate(point[1], True)}"
    gga = f"GPGGA,{fix_time},{coords},1,{satellites:02d},0.9,{altitude_m:.1f},M,0.0,M,,"
    rmc = f"GPRMC,{fix_time},A,{coords},{speed_kph / 1.852:.2f},{heading:.2f},{utc:%d%m%y},0.0,W"
    return sentence(gga), sentence(rmc)


def yangjae_fixes(kph):
    yield YANGJAE[0], 0.0, 0.0
    for start, end in zip(YANGJAE, YANGJAE[1:]):
        north = (end[0] - start[0]) * 111195
        east = (end[1] - start[1]) * 111195 * math.cos(math.radians((start[0] + end[0]) / 2))
        meters = math.hypot(north, east)
        seconds = max(1, math.ceil(meters / (kph / 3.6)))
        course = math.degrees(math.atan2(east, north)) % 360
        for step in range(1, seconds + 1):
            point = tuple(a + (b - a) * step / seconds for a, b in zip(start, end))
            yield point, meters / seconds * 3.6, course
    yield YANGJAE[-1], 0.0, course


def self_test():
    known = "$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"
    assert valid_checksum(known) and not valid_checksum(known[:-2] + "00")
    utc = datetime(2026, 11, 14, 1, 2, 3, tzinfo=timezone.utc)
    pair = nmea_pair(utc, YANGJAE[0], 18, 123, 12, 8)
    assert all(valid_checksum(item) for item in pair)
    assert all(",3729.0665,N,12701.9267,E," in item for item in pair)
    assert ",9.72,123.00,141126," in pair[1]
    assert coordinate(-89.99999999, False) == "9000.0000,S"
    fixes = list(yangjae_fixes(18))
    assert fixes[0][0] == YANGJAE[0] and fixes[-1][:2] == (YANGJAE[-1], 0.0)
    assert all(any(max(abs(a-b) for a,b in zip(point, vertex)) < 1e-8 for point, _, _ in fixes) for vertex in YANGJAE)
    print("PASS: standard RMC checksum, corrupt checksum rejection, GGA/RMC fields, Yangjae vertices")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=r"C:\AndroidSDK\platform-tools\adb.exe")
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--output", type=Path, help="New JSONL path. Existing logs are preserved.")
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--self-test", action="store_true", help="Offline. Does not invoke adb.")
    modes.add_argument("--point", nargs=2, type=float, metavar=("LAT", "LON"))
    modes.add_argument("--yangjae", action="store_true", help="Replay the 413 m OSM course at 1 Hz.")
    parser.add_argument("--seconds", type=int, help="Stationary point sample count (default 30).")
    parser.add_argument("--speed-kph", type=float, default=18, help="Target speed for route replay.")
    parser.add_argument("--heading", type=float, default=0, help="Synthetic course (point mode); speed is 0.")
    parser.add_argument("--altitude-m", type=float, default=12)
    parser.add_argument("--satellites", type=int, default=8)
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if args.output is None:
        parser.error("--output is required for a replay")
    if args.seconds is not None and (args.point is None or args.seconds < 1):
        parser.error("--seconds requires --point and must be positive")
    if args.yangjae and not (0 < args.speed_kph <= 200):
        parser.error("Route speed must be in (0, 200] km/h")
    adb = [args.adb, "-s", args.serial]
    probe = subprocess.check_output(adb + ["shell", "getprop", "ro.kernel.qemu"], text=True, timeout=10)
    if probe.strip() != "1":
        raise RuntimeError("The selected device is not an emulator")
    fixes = (yangjae_fixes(args.speed_kph) if args.yangjae else
             ((tuple(args.point), 0.0, args.heading) for _ in range(args.seconds or 30)))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as log:
        due = time.monotonic()
        for index, (point, speed, heading) in enumerate(fixes):
            time.sleep(max(0.0, due - time.monotonic()))
            due = max(due + 1, time.monotonic() + 0.99)
            utc = datetime.now(timezone.utc)
            sentences = nmea_pair(utc, point, speed, heading, args.altitude_m, args.satellites)
            for raw in sentences:
                record = {"index": index, "serial": args.serial, "fixUtc": utc.isoformat(),
                          "latitude": point[0], "longitude": point[1], "speedKph": speed,
                          "headingDegrees": heading, "altitudeMeters": args.altitude_m,
                          "satellites": args.satellites, "sentence": raw, "checksum": raw[-2:],
                          "sendUtc": datetime.now(timezone.utc).isoformat()}
                try:
                    result = subprocess.run(adb + ["emu", "geo", "nmea", raw], capture_output=True,
                                            timeout=6, text=True, check=False)
                    reply = (result.stdout + result.stderr).strip()
                    record.update(returnCode=result.returncode, reply=reply,
                                  acknowledged=result.returncode == 0 and "OK" in reply.splitlines())
                except (OSError, subprocess.TimeoutExpired) as error:
                    record.update(acknowledged=False, error=str(error))
                record["finishUtc"] = datetime.now(timezone.utc).isoformat()
                log.write(json.dumps(record, ensure_ascii=False) + "\n")
                log.flush()
                if not record["acknowledged"]:
                    raise RuntimeError(f"geo nmea not acknowledged; see {args.output}")


if __name__ == "__main__":
    main()
