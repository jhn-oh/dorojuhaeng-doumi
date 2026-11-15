"""Verify real road-loop responses at two explicit test positions, never physical-GPS evidence."""
from __future__ import annotations
import json
import math
from pathlib import Path
import time
import urllib.request

ENDPOINT = "https://valhalla1.openstreetmap.de/route"
OUTPUT = Path(__file__).resolve().parents[2] / "outputs" / "current-location-loop-20261115"

def decode(shape: str):
    i = lat = lon = 0
    result = []
    def delta():
        nonlocal i
        shift = value = 0
        while True:
            group = ord(shape[i]) - 63
            i += 1
            value |= (group & 31) << shift
            shift += 5
            if group < 32:
                return ~(value >> 1) if value & 1 else value >> 1
    while i < len(shape):
        lat += delta()
        lon += delta()
        result.append([lat / 1e6, lon / 1e6])
    return result

def meters(a, b):
    return math.hypot((a[0] - b[0]) * 111195, (a[1] - b[1]) * 111195 * math.cos(math.radians((a[0] + b[0]) / 2)))

def verify(label, origin):
    radius = 10 * 55
    via = [[origin[0] + math.cos(math.radians(b)) * radius / 111195,
            origin[1] + math.sin(math.radians(b)) * radius / (111195 * math.cos(math.radians(origin[0])))] for b in (0, 120)]
    payload = {"locations": [{"lat": p[0], "lon": p[1], "radius": 100} for p in [origin] + via + [origin]],
               "costing": "auto", "units": "kilometers", "language": "ko-KR",
               "costing_options": {"auto": {"exclude_highways": True, "exclude_tolls": True, "exclude_ferries": True}}}
    req = urllib.request.Request(ENDPOINT, data=json.dumps(payload).encode(),
                                headers={"Content-Type": "application/json", "User-Agent": "RoadDrivingHelper/0.6 prototype verification"})
    with urllib.request.urlopen(req, timeout=25) as stream:
        response = json.load(stream)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / f"{label}-response.json").write_text(json.dumps(response, ensure_ascii=False, indent=2), encoding="utf-8")
    trip = response["trip"]
    summary = trip["summary"]
    legs = trip["legs"]
    points = [p for leg in legs for p in decode(leg["shape"])]
    assert trip["status"] == 0 and len(legs) == 3
    assert all(not summary[k] for k in ("has_highway", "has_toll", "has_ferry"))
    assert meters(origin, points[0]) <= 150 and meters(origin, points[-1]) <= 150
    assert 0.4 <= summary["length"] <= 60
    return {"label": label, "input_origin": origin, "request": payload, "meters": round(summary["length"] * 1000),
            "seconds": summary["time"], "geometry_points": len(points), "start_offset_m": meters(origin, points[0]),
            "return_offset_m": meters(origin, points[-1]), "highway": summary["has_highway"],
            "toll": summary["has_toll"], "ferry": summary["has_ferry"]}

if __name__ == "__main__":
    results = []
    for label, point in [("yangjae", [37.484442, 127.032112]), ("busan", [35.1796, 129.0756])]:
        results.append(verify(label, point))
        time.sleep(1)
    report = {"source": ENDPOINT, "scope": "Test coordinates and real routing HTTP responses. No vehicle GNSS, native navigation path parity or physical-road suitability validation.",
              "provenance": "Valhalla auto routing over OpenStreetMap road graph; no embedded OSM map", "results": results}
    (OUTPUT / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
