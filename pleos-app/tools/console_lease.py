"""Keep the official Virtual Console's browser lease alive for emulator tests only."""
import argparse
import time
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("--seconds", type=int, default=7200)
args = parser.parse_args()
try:
    for _ in range(args.seconds):
        try:
            urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:8088/browser/session?client=licensenote-sdk-verification", method="POST"), timeout=3).close()
        except OSError:
            pass
        time.sleep(1)
finally:
    try:
        urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:8088/browser/session?client=licensenote-sdk-verification&closing=1", method="POST"), timeout=3).close()
    except OSError:
        pass
