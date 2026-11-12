#!/usr/bin/env python3
"""Run PulseVoicePhone diagnostics over an explicitly authorized ADB connection."""
import argparse
import datetime
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", help="ADB device serial (required if several devices are connected)")
parser.add_argument("--output", default=None, help="Directory for report, log and screenshots")
args = parser.parse_args()
adb = ["adb"] + (["-s", args.serial] if args.serial else [])
out = pathlib.Path(args.output or
    ("phone-diagnostics-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S")))
out.mkdir(parents=True, exist_ok=True)
PACKAGE = "com.pulsevoicephone"

def run(*command, binary=False):
    result = subprocess.run(adb + list(command), capture_output=True, timeout=40)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace").strip())
    return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")

def dump():
    remote = "/sdcard/pulsevoicephone-diagnostics-ui.xml"
    run("shell", "uiautomator", "dump", remote)
    xml = run("exec-out", "cat", remote)
    run("shell", "rm", "-f", remote)
    return ET.fromstring(xml)

size = re.findall(r"(\d+)x(\d+)", run("shell", "wm", "size"))
width, height = map(int, size[-1]) if size else (1080, 1920)

def find(root, text):
    for node in root.iter("node"):
        if node.get("text") == text:
            bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
            if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                return node, bounds
    return None

def tap(text):
    # The diagnostics button can be below the fold on smaller displays.
    for attempt in range(8):
        root = dump()
        found = find(root, text)
        if found:
            node, (left, top, right, bottom) = found
            if node.get("enabled") != "true":
                raise RuntimeError("Button is disabled: " + text)
            run("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
            return
        run("shell", "input", "swipe", str(width // 2), str(height * 3 // 4),
            str(width // 2), str(height // 3), "300")
    raise RuntimeError("Button not found: " + text + ". Unlock the phone and check the installed version.")

def capture(name):
    (out / (name + ".png")).write_bytes(run("exec-out", "screencap", "-p", binary=True))
    root = dump()
    ET.ElementTree(root).write(out / (name + ".xml"), encoding="utf-8", xml_declaration=True)
    return root

try:
    if run("get-state").strip() != "device":
        raise RuntimeError("Authorize USB/wireless debugging on the phone.")
    run("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    time.sleep(1)
    tap("Диагностика и отчёт")
    time.sleep(1)
    tap("Проверить Health Connect")
    deadline = time.monotonic() + 30
    while True:
        root = dump()
        found = find(root, "Проверить Health Connect")
        if found and found[0].get("enabled") == "true":
            break
        if time.monotonic() >= deadline:
            raise RuntimeError("Diagnostics did not complete within 30 seconds.")
        time.sleep(1)
    capture("health-check")
    found = find(root, "Проверить русский голос")
    if found and found[0].get("enabled") == "true":
        tap("Проверить русский голос")
        time.sleep(8)
    # Scroll to the selectable report. The app's own report is the preferred export.
    for _ in range(5):
        run("shell", "input", "swipe", str(width // 2), str(height * 3 // 4),
            str(width // 2), str(height // 3), "300")
    root = capture("report")
    texts = [node.get("text", "") for node in root.iter("node")]
    (out / "visible-report.txt").write_text("\n\n".join(filter(None, texts)), encoding="utf-8")
    print("Diagnostics completed. Confirm audibility on the phone; ADB cannot verify actual sound.")
except (RuntimeError, subprocess.TimeoutExpired, FileNotFoundError) as error:
    print("Diagnostics interrupted:", error)
    raise SystemExit(1)
finally:
    try:
        # run-as is available for the debug APK; only this app's diagnostics are read.
        prefs = run("shell", "run-as", PACKAGE, "cat", "shared_prefs/diagnostics.xml")
        (out / "diagnostic-events.xml").write_text(prefs, encoding="utf-8")
        pid = run("shell", "pidof", PACKAGE).strip().split()[0]
        if not pid.isdecimal():
            raise RuntimeError("Unexpected process id")
        logs = run("logcat", "-d", "--pid=" + pid, "-v", "threadtime",
            "PulseVoicePhone:D", "AndroidRuntime:E", "*:S")
        (out / "logcat.txt").write_text(logs, encoding="utf-8")
    except (RuntimeError, subprocess.TimeoutExpired, FileNotFoundError, IndexError) as error:
        (out / "capture-error.txt").write_text(str(error), encoding="utf-8")
    print("Artifacts:", out.resolve())
