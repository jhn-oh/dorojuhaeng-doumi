import concurrent.futures
import json
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from server import Handler, Handoff


class HandoffTest(unittest.TestCase):
    def setUp(self):
        self.now = 1000
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.address = "http://127.0.0.1:" + str(self.server.server_port)
        self.server.handoff = Handoff(self.address, lambda: self.now)
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.addCleanup(self.stop)

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def request(self, path, token="", payload=None):
        req = Request(self.address + path,
                      data=None if payload is None else json.dumps(payload).encode(),
                      headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        try:
            with urlopen(req, timeout=3) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            error.close()
            return error.code, {}

    def pair(self):
        code, link = self.request("/v1/pair", payload={})
        self.assertEqual(code, 201)
        car, phone = link["car"], link["url"].split("#")[1].split(".")[1]
        base = "/v1/pair/" + link["id"] + "/"
        self.assertEqual(self.request(base + "claim", phone, {"alias": "검수 휴대폰"})[0], 200)
        return base, car, phone

    def test_real_http_delivery_requires_matching_phone_revision(self):
        base, car, phone = self.pair()
        report = dict(summary="93점 · 공식 시험 점수가 아니에요.", revision=3)
        for _ in range(2):
            self.assertEqual(self.request(base + "send", car, report)[1]["state"], "sent")
        self.assertEqual(self.request(base + "read", car)[0], 403)
        self.assertEqual(self.request(base + "read", phone)[1]["summary"], report["summary"])
        self.assertEqual(self.request(base + "ack", phone, {"revision": 2})[0], 409)
        self.assertEqual(self.request(base + "status", car)[1]["state"], "sent")
        self.assertEqual(self.request(base + "ack", phone, {"revision": 3})[1]["state"], "received")
        self.assertEqual(self.request(base + "cancel", car, {})[1]["state"], "received")

    def test_cancel_and_expiry(self):
        base, car, phone = self.pair()
        self.request(base + "send", car, dict(summary="연습 결과", revision=4))
        self.assertEqual(self.request(base + "cancel", car, {})[1]["state"], "cancelled")
        self.assertEqual(self.request(base + "ack", phone, {"revision": 4})[0], 409)
        self.assertNotIn("summary", self.request(base + "read", phone)[1])
        self.now += 301
        self.assertEqual(self.request(base + "status", car)[0], 410)

    def test_cancel_ack_race(self):
        base, car, phone = self.pair()
        self.request(base + "send", car, dict(summary="회전 연습", revision=1))
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            cancel = pool.submit(self.request, base + "cancel", car, {})
            ack = pool.submit(self.request, base + "ack", phone, {"revision": 1})
            self.assertIn(cancel.result()[1]["state"], {"received", "cancelled"})
            self.assertIn(ack.result()[0], {200, 409})
        self.assertEqual(self.request(base + "status", car)[1]["state"], cancel.result()[1]["state"])


if __name__ == "__main__":
    unittest.main()
