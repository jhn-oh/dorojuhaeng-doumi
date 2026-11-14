"""Short-lived, two-party handoff for a parked vehicle and an open phone browser."""
import argparse
import json
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

TTL = 300
MAX_LINKS = 128
PAGE = Path(__file__).with_name("phone.html").read_bytes()


class Handoff:
    def __init__(self, public_url, clock=time.monotonic):
        self.public_url = public_url.rstrip("/")
        self.clock = clock
        self.links = {}
        self.lock = threading.Lock()

    def handle(self, method, path, token="", data=None):
        data = data or {}
        with self.lock:
            now = self.clock()
            self.links = {k: v for k, v in self.links.items() if v["expires"] > now}
            if method == "POST" and path == "/v1/pair":
                if len(self.links) >= MAX_LINKS:
                    return 429, {}
                key, car, phone = (secrets.token_urlsafe(24) for _ in range(3))
                self.links[key] = dict(car=car, phone=phone, expires=now + TTL,
                                       state="waiting", alias="", report=None)
                return 201, dict(id=key, car=car, url=f"{self.public_url}/receive#{key}.{phone}",
                                 expiresIn=TTL)
            parts = path.strip("/").split("/")
            if len(parts) != 4 or parts[:2] != ["v1", "pair"]:
                return 404, {}
            key, action = parts[2:]
            item = self.links.get(key)
            if item is None:
                return 410, {}
            role = "phone" if action in {"claim", "read", "ack"} else "car"
            if not secrets.compare_digest(item[role], token):
                return 403, {}
            state = item["state"]
            if action == "claim" and method == "POST":
                alias = data.get("alias")
                if not isinstance(alias, str) or not 1 <= len(alias.strip()) <= 32:
                    return 400, {}
                if state == "waiting":
                    item.update(state="paired", alias=alias.strip())
                elif item["alias"] != alias.strip() or state == "cancelled":
                    return 409, {}
            elif action == "send" and method == "POST":
                body, revision = data.get("summary"), data.get("revision")
                if not isinstance(body, str) or not 1 <= len(body) <= 1800 or type(revision) != int or revision < 1:
                    return 400, {}
                report = dict(summary=body, revision=revision)
                if state == "paired":
                    item.update(state="sent", report=report)
                elif state not in {"sent", "received"} or report != item["report"]:
                    return 409, {}
            elif action == "ack" and method == "POST":
                if state not in {"sent", "received"} or data.get("revision") != item["report"]["revision"]:
                    return 409, {}
                item["state"] = "received"
            elif action == "cancel" and method == "POST":
                if state != "received":
                    item.update(state="cancelled", report=None)
            elif not (method == "GET" and action in {"status", "read"}):
                return 404, {}
            response = dict(state=item["state"], alias=item["alias"])
            if item["report"] is not None:
                response["revision"] = item["report"]["revision"]
                if action == "read":
                    response["summary"] = item["report"]["summary"]
            return 200, response


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass  # Never log link identifiers, device names, or report text.

    def respond(self, status, body, content_type):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'none'; connect-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/receive":
            self.respond(200, PAGE, "text/html; charset=utf-8")
        else:
            self.api("GET")

    def do_POST(self):
        self.api("POST")

    def api(self, method):
        try:
            size = int(self.headers.get("Content-Length", 0))
            if not 0 <= size <= 8192:
                raise ValueError()
            data = json.loads(self.rfile.read(size)) if size else {}
            if not isinstance(data, dict):
                raise ValueError()
            token = self.headers.get("Authorization", "").removeprefix("Bearer ")
            status, result = self.server.handoff.handle(method, self.path, token, data)
        except (ValueError, UnicodeError):
            status, result = 400, {}
        self.respond(status, json.dumps(result, ensure_ascii=False).encode(), "application/json; charset=utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", default=18771, type=int)
    parser.add_argument("--public-url", default="http://127.0.0.1:18771")
    args = parser.parse_args()
    url = urlparse(args.public_url)
    if not url.hostname or (url.scheme != "https" and not (url.scheme == "http" and url.hostname in {"localhost", "127.0.0.1"})):
        parser.error("--public-url requires HTTPS or loopback HTTP for local testing")
    with ThreadingHTTPServer((args.bind, args.port), Handler) as server:
        server.handoff = Handoff(args.public_url)
        server.serve_forever()
