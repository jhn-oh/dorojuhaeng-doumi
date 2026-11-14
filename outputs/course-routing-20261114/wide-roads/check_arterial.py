"""Compare each routed edge with nearby same-direction ITS standard links."""
import collections
import hashlib
import json
import math
import argparse
import tempfile
import time
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

HERE = Path(__file__).parent


def read(name):
    return json.loads((HERE / name).read_text(encoding="utf-8-sig"))


def fetch_inputs():
    """Fetch snapshots for an independent, local recheck. No vehicle location is sent."""
    service = "https://portal.esrikr.com/arcgis/rest/services/Hosted/ITS_LINK_Data/FeatureServer/0"
    urls = {
        "its-layer.json": service + "?f=json",
        "its-item.json": "https://portal.esrikr.com/portal/sharing/rest/content/items/215af5a55a0c4769a82297154209b5b2?f=pjson",
        "lane-corridor.json": service + "/query?" + urlencode({
            "f":"json", "geometryType":"esriGeometryEnvelope", "geometry":"127.025,37.483,127.116,37.524",
            "inSR":4326, "outSR":4326, "spatialRel":"esriSpatialRelIntersects", "where":"1=1",
            "outFields":"link_id,lanes,road_name,road_rank,road_type,road_use,max_spd", "returnGeometry":"true"
        }),
    }
    attributes = ["way_id", "lane_count", "road_class", "use", "names", "length", "traversability",
                  "forward", "begin_shape_index", "end_shape_index", "toll", "id"]
    for i, leg in enumerate(read("final.json")["trip"]["legs"], 1):
        query = {"encoded_polyline":leg["shape"], "costing":"auto", "shape_match":"edge_walk", "units":"kilometers",
                 "filters":{"action":"include", "attributes":["edge." + name for name in attributes]}}
        name = f"arterial-edge-{i}.json" if i < 3 else "final-edge-3.json"
        urls[name] = "https://valhalla1.openstreetmap.de/trace_attributes?" + urlencode({"json":json.dumps(query, separators=(",", ":"))})
    for name, url in urls.items():
        path = HERE / name
        if path.exists():
            continue
        request = Request(url, headers={"User-Agent":"Dorojuhaeng-Doumi/Public-Course-Research",
                                        "X-Client-Id":"dorojuhaeng-doumi-course-research"})
        with urlopen(request, timeout=35) as response:
            data = response.read()
        payload = json.loads(data)
        if "error" in payload or (name == "lane-corridor.json" and payload.get("exceededTransferLimit")):
            raise ValueError(f"Incomplete provider response for {name}")
        with tempfile.NamedTemporaryFile(dir=HERE, delete=False) as tmp:
            tmp.write(data)
        Path(tmp.name).replace(path)
        time.sleep(2)


def decode(shape):
    coords = []
    last = [0, 0]
    pos = 0
    while pos < len(shape):
        for axis in range(2):
            n = shift = 0
            while True:
                byte = ord(shape[pos]) - 63
                pos += 1
                n |= (byte & 31) << shift
                shift += 5
                if byte < 32:
                    break
            last[axis] += ~(n >> 1) if n & 1 else n >> 1
        coords.append((last[1] / 1e6, last[0] / 1e6))
    return coords


def xy(p):
    return ((p[0] - 127) * 88215, (p[1] - 37.5) * 111195)


def project(point, start, end):
    dx, dy = end[0] - start[0], end[1] - start[1]
    t = max(0, min(1, ((point[0] - start[0]) * dx + (point[1] - start[1]) * dy) / (dx * dx + dy * dy)))
    return math.dist(point, (start[0] + dx * t, start[1] + dy * t))


def direction(a, b):
    return math.degrees(math.atan2(b[1] - a[1], b[0] - a[0]))


def main():
    refs = collections.defaultdict(list)
    for link in read("lane-corridor.json")["features"]:
        a = link["attributes"]
        for path in link["geometry"]["paths"]:
            for start, end in zip(path, path[1:]):
                start, end = xy(start), xy(end)
                if math.dist(start, end) > 0.2:
                    refs[a["road_name"]].append((start, end, direction(start, end), a))
    trip = read("final.json")["trip"]
    assert trip["status"] == 0 and not trip["summary"]["has_highway"]
    edge_count = 0
    checks = []
    for legno, leg in enumerate(trip["legs"], 1):
        points = [xy(p) for p in decode(leg["shape"])]
        edges = read(f"arterial-edge-{legno}.json" if legno < 3 else "final-edge-3.json")["edges"]
        assert edges[0]["begin_shape_index"] == 0 and edges[-1]["end_shape_index"] == len(points) - 1
        assert all(a["end_shape_index"] == b["begin_shape_index"] for a, b in zip(edges, edges[1:]))
        for edge in edges:
            assert edge["use"] == "road" and edge["road_class"] == "primary" and not edge["toll"]
            assert edge["traversability"] in ("both", "forward")
            geom = points[edge["begin_shape_index"]:edge["end_shape_index"] + 1]
            name = edge["names"][0]
            edge_count += 1
            for start, end in zip(geom, geom[1:]):
                length = math.dist(start, end)
                if length < 0.2:
                    continue
                count = math.ceil(length / 5)
                heading = direction(start, end)
                for i in range(count):
                    t = (i + 0.5) / count
                    pt = (start[0] + (end[0] - start[0]) * t, start[1] + (end[1] - start[1]) * t)
                    nearby = [(project(pt, a, b), props) for a, b, angle, props in refs[name]
                              if abs((angle - heading + 180) % 360 - 180) <= 45]
                    if nearby:
                        distance, props = min(nearby, key=lambda x: x[0])
                        checks.append((distance, props["lanes"], props["link_id"], name, length / count, edge["way_id"]))
                    else:
                        checks.append((9999, 0, "", name, length / count, edge["way_id"]))
    covered = [s for s in checks if s[0] <= 20 and s[1] >= 2]
    total = sum(s[4] for s in checks)
    print("Edges", edge_count, "samples", len(checks), "max distance", max(s[0] for s in checks))
    print("Verified same road/direction >=2 lanes within 20m:", round(100 * sum(s[4] for s in covered) / total, 3))
    print("Unconfirmed OSM ways", collections.Counter(s[-1] for s in checks if s not in covered))
    print("Lane counts", collections.Counter(s[1] for s in checks))
    print("Road names", sorted({s[3] for s in checks}))
    assert len(covered) == len(checks) and min(s[1] for s in covered) == 3
    assert 27 * 60 <= trip["summary"]["time"] <= 33 * 60
    hashes = {name: hashlib.sha256((HERE / name).read_bytes()).hexdigest() for name in (
        "final.json", "arterial-edge-1.json", "arterial-edge-2.json", "final-edge-3.json",
        "lane-corridor.json", "its-item.json", "its-layer.json")}
    trace_hash = hashlib.sha256("\n".join(leg["shape"] for leg in trip["legs"]).encode("ascii")).hexdigest()
    report = {"routingSeconds": trip["summary"]["time"], "lengthKm": trip["summary"]["length"], "edges": edge_count,
              "samples": len(checks), "sampledLengthMeters": round(total, 2), "coveredLengthMeters": round(sum(s[4] for s in covered), 2),
              "maxOffsetMeters": round(max(s[0] for s in checks), 2), "minLanes": min(s[1] for s in covered),
              "maxLanes": max(s[1] for s in covered), "matchedStandardLinks": sorted({s[2] for s in covered}),
              "namedRoads": sorted({s[3] for s in covered}), "traceSha256": trace_hash, "sourceSha256": hashes}
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--fetch-missing", action="store_true")
    parser.add_argument("--output", type=Path, default=HERE / "coverage-recheck.json")
    args = parser.parse_args()
    if args.fetch_missing:
        fetch_inputs()
    result = main()
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf8")
    previous = read("coverage.json")["sourceSha256"]
    print("Source byte hashes differing from 2026-11-14:", [name for name, digest in result["sourceSha256"].items() if previous.get(name) != digest])
