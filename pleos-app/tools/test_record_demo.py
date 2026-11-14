"""Offline regression checks: never launch ADB, an encoder, or a scenario."""
import ast
from datetime import datetime, timezone
from pathlib import Path
import subprocess
from types import SimpleNamespace
import unittest


class Clock:
    now = 0.0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class Encoder:
    def __init__(self):
        self.returncode = None
        self.waits = 0

    def poll(self):
        return self.returncode

    def wait(self, timeout):
        self.waits += 1
        self.returncode = 0
        return 0


def harness():
    functions = [node for node in ast.parse(Path(__file__).with_name("record_demo.py").read_text(encoding="utf-8")).body
                 if isinstance(node, ast.FunctionDef)]
    clock, commands, encoders = Clock(), [], []

    def spawn(*args, **kwargs):
        encoder = Encoder()
        encoders.append(encoder)
        return encoder

    env = {"Path": Path, "datetime": datetime, "timezone": timezone, "time": clock,
           "prefix": ["unused-adb"], "args": SimpleNamespace(seconds=180), "capture_id": "offline",
           "metadata": {"parts": []}, "recordings": [],
           "subprocess": SimpleNamespace(Popen=spawn, CalledProcessError=subprocess.CalledProcessError,
                                         TimeoutExpired=subprocess.TimeoutExpired)}
    exec(compile(ast.Module(body=functions, type_ignores=[]), "record_demo.py functions", "exec"), env)
    env["adb"] = lambda *args, **kwargs: commands.append(args)
    return env, clock, commands, encoders


class RecordingOwnershipTests(unittest.TestCase):
    def test_delayed_pid_retries_after_immediate_tracking(self):
        env, clock, _, _ = harness()

        def delayed(remote, deadline):
            self.assertEqual(len(env["recordings"]), 1)
            return "17" if clock.now >= .8 else None

        env["matching_pid"] = delayed
        _, part = env["start_part"](None)
        self.assertEqual(part["remotePid"], "17")
        self.assertGreaterEqual(clock.now, .8)
        self.assertLess(clock.now, 5)

    def test_failed_second_start_is_tracked_and_finalized(self):
        env, clock, commands, encoders = harness()
        env["matching_pid"] = lambda remote, deadline: "17"
        env["start_part"](None)
        env["matching_pid"] = lambda remote, deadline: None
        with self.assertRaisesRegex(AssertionError, "did not start"):
            env["start_part"](None)
        self.assertEqual(len(env["recordings"]), 2)
        self.assertAlmostEqual(clock.now, 5)
        env["matching_pid"] = lambda remote, deadline: "17" if "001.mp4" in remote else "22"
        for encoder, part in env["recordings"]:
            env["finish_part"](encoder, part, interrupt=True)
        self.assertEqual(commands, [("shell", "kill", "-2", "17"), ("shell", "kill", "-2", "22")])
        self.assertTrue(all(part["finalized"] for _, part in env["recordings"]))
        self.assertTrue(env["recordings"][1][1]["pidRecoveredDuringStop"])
        self.assertEqual([encoder.waits for encoder in encoders], [1, 1])

    def test_changed_pid_is_not_signalled(self):
        env, _, commands, _ = harness()
        env["matching_pid"] = lambda remote, deadline: "17"
        encoder, part = env["start_part"](None)
        env["matching_pid"] = lambda remote, deadline: "18"
        with self.assertRaisesRegex(AssertionError, "PID changed"):
            env["finish_part"](encoder, part, interrupt=True)
        self.assertEqual(commands, [])

    def test_completed_part_is_not_finalized_twice(self):
        env, _, _, _ = harness()
        env["matching_pid"] = lambda remote, deadline: "17"
        encoder, part = env["start_part"](None)
        encoder.returncode = 0
        env["finish_part"](encoder, part)
        ended = part["endedAtHostUtc"]
        env["finish_part"](encoder, part, interrupt=True)
        self.assertEqual(part["endedAtHostUtc"], ended)
        self.assertEqual(encoder.waits, 1)

    def test_identity_requires_exact_unique_file_argument(self):
        env, _, _, _ = harness()
        remote = "/data/local/tmp/licensenote-unique.mp4"
        responses = {("shell", "pidof", "screenrecord"): "22 23",
                     ("shell", "cat", "/proc/22/cmdline"): "screenrecord\0--time-limit\0" + "180\0" + remote + "\0",
                     ("shell", "cat", "/proc/23/cmdline"): "screenrecord\0" + remote + ".other\0"}
        env["adb"] = lambda *args, **kwargs: responses[args]
        self.assertEqual(env["matching_pid"](remote, deadline=5), "22")
        self.assertIsNone(env["matching_pid"](remote, deadline=0))


if __name__ == "__main__":
    unittest.main()
