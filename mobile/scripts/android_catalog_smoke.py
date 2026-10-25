"""Task 4 emulator smoke against a loopback-only synthetic HTTP fixture server (not B1).
Build/install debug with REMOTE, http://10.0.2.2:8768/api/v1/ and explicit debug HTTP.
Preserves the selected source's existing file and device display settings.
"""
import argparse
import copy
import hashlib
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import subprocess
import threading
import time
from urllib.parse import urlsplit, parse_qs
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial", default="emulator-5554")
args = parser.parse_args()
assert args.serial.startswith("emulator-"), "Fault-injection smoke is emulator-only"
root = Path(__file__).resolve().parents[2]
out = root / "mobile/artifacts/m2-catalog"
(out / "screenshots").mkdir(parents=True, exist_ok=True)
package = "dev.hackatlas.mobile.debug"
component = package + "/dev.hackatlas.mobile.MainActivity"
base = "http://10.0.2.2:8768/api/v1/"
stored = "no_backup/guest-saved/remote-" + hashlib.sha256(base.encode()).hexdigest() + "-v1.json"
results, requests = [], []
control = {"demo": True, "failure": False, "metadata_failure": True, "detail_failure": False, "hold_detail": False}
detail_received, release_detail = threading.Event(), threading.Event()
fixtures = {name: json.loads((root / "api/examples" / name).read_text(encoding="utf-8"))
            for name in ("catalog-page.json", "hackathon-detail.json", "filter-metadata.json")}
event_id = fixtures["hackathon-detail.json"]["id"]

def event():
    result = copy.deepcopy(fixtures["catalog-page.json"]["items"][0])
    result.update(title="MOCK AI Demo" if control["demo"] else "MOCK AI Live", isDemo=control["demo"])
    result["location"]["countryCode"] = "KZ"
    return result

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *unused):
        pass

    def do_GET(self):
        url = urlsplit(self.path)
        query = {k: v[0] for k, v in parse_qs(url.query).items()}
        requests.append({"path": url.path, "query": query, "authorization": self.headers.get("Authorization")})
        status = 200
        if url.path == "/api/v1/metadata/filters":
            status = 503 if control["metadata_failure"] else 200
            payload = fixtures["filter-metadata.json"]
        elif url.path == "/api/v1/hackathons":
            status = 503 if control["failure"] else 200
            page = int(query.get("page", "0"))
            items = [event()]
            if page:
                extra = copy.deepcopy(fixtures["catalog-page.json"]["items"][1])
                extra["title"] = "MOCK Next Page"
                items = [extra]
            payload = dict(items=items, page=page, size=20, totalElements=21, totalPages=2,
                           hasNext=page == 0, asOf="2026-10-26T10:00:00Z")
        elif url.path == "/api/v1/hackathons/" + event_id:
            if control["hold_detail"]:
                detail_received.set()
                release_detail.wait(timeout=20)
            status = 404 if control["detail_failure"] else 200
            payload = copy.deepcopy(fixtures["hackathon-detail.json"])
            payload.update(event())
            payload["description"] = "Synthetic full detail for emulator smoke. Not a real event."
            payload["sourceUrl"] = "https://example.com/mock-source"
        else:
            status, payload = 404, {}
        if status != 200:
            payload = {"type": "about:blank", "title": "Synthetic failure", "status": status}
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        # Explicit close avoids reusing HTTP/1.0 sockets across emulator port forwarding.
        # No transport retry is enabled in the app to conceal a disconnected peer.
        self.send_header("Connection", "close")
        self.close_connection = True
        self.end_headers()
        self.wfile.write(body)

def adb(*command, payload=None, binary=False):
    r = subprocess.run([args.adb, "-s", args.serial, *command], input=payload, capture_output=True, timeout=45)
    if r.returncode:
        raise RuntimeError(r.stderr.decode(errors="replace") or r.stdout.decode(errors="replace"))
    return r.stdout if binary else r.stdout.decode("utf-8")

def tree():
    for _ in range(4):
        response = adb("shell", "uiautomator", "dump", "/sdcard/hackatlas-catalog.xml")
        if "dumped to" in response:
            value = ET.fromstring(adb("shell", "cat", "/sdcard/hackatlas-catalog.xml"))
            if any(n.get("package") == package for n in value.iter("node")):
                return value
        time.sleep(.5)
    raise AssertionError("No live app hierarchy")

def node(text, attr="text"):
    return next((n for n in tree().iter("node") if n.get(attr) == text), None)

def require(text):
    # Wait for rendering/network completion, never press Retry or hide an error.
    for _ in range(4):
        hierarchy = tree()
        if any(n.get("text") == text for n in hierarchy.iter("node")):
            return
        time.sleep(.25)
    capture("remote-failure")
    raise AssertionError("Missing text: " + text + "; visible: " +
                         str([n.get("text") for n in hierarchy.iter("node") if n.get("text")]))

def tap(text, attr="text"):
    n = node(text, attr)
    assert n is not None, "Missing " + attr + ": " + text
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(.4)

def launch(route="home"):
    adb("shell", "am", "force-stop", package)
    adb("shell", "am", "start", "-W", "-n", component, "--es", "screen", route)
    time.sleep(1)

def capture(name):
    hierarchy = tree()
    (out / (name + "-ui.xml")).write_bytes(ET.tostring(hierarchy, encoding="utf-8"))
    (out / "screenshots" / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p", binary=True))

def read_file(path):
    return adb("exec-out", "run-as", package, "cat", path, binary=True)

def file_exists(path):
    return adb("shell", "run-as", package, "sh", "-c", "'test -f " + path + " && echo yes || echo no'").strip() == "yes"

def write_file(data):
    adb("shell", "am", "force-stop", package)
    adb("shell", "-T", "run-as", package, "tee", stored, payload=data, binary=True)

def passed(name):
    results.append({"name": name, "status": "PASS"})
    print("PASS", name, flush=True)

def wait_entries(count):
    for _ in range(40):
        entries = json.loads(read_file(stored))["entries"]
        if len(entries) == count:
            return entries
        time.sleep(.25)
    capture("remote-failure")
    raise AssertionError("Snapshot write did not complete: " + str(count))

def swipe_up():
    adb("shell", "input", "swipe", "540", "1800", "540", "700", "350")
    time.sleep(.4)

def reveal(text):
    for _ in range(5):
        if node(text) is not None:
            return
        swipe_up()
    require(text)

server = ThreadingHTTPServer(("127.0.0.1", 8768), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
original = None
existed = file_exists(stored)
if existed:
    original = read_file(stored)
demo_file = "no_backup/guest-saved/demo-v1.json"
demo_before = read_file(demo_file) if file_exists(demo_file) else None
density_before = adb("shell", "wm", "density")

def restore_density():
    override = re.search(r"Override \w+: (\S+)", density_before)
    adb("shell", "wm", "density", override.group(1) if override else "reset")

try:
    launch()
    require("MOCK AI Demo")
    # Empty only this synthetic source, retaining the user's pre-test bytes for finally.
    write_file(json.dumps({"version": 1, "scope": "remote-guest@" + base, "entries": []}).encode())
    launch()
    require("MOCK AI Demo")
    capture("remote-home")
    tap("Сохранить хакатон", "content-desc")
    snapshot = wait_entries(1)[0]
    assert snapshot["detail"]["description"].startswith("Synthetic full detail")
    assert snapshot["detail"]["hackathon"]["starts"] == fixtures["hackathon-detail.json"]["starts"]
    assert any(r["path"].endswith(event_id) for r in requests)
    passed("remote card saves full detail before marking bookmark")
    tap("MOCK AI Demo")
    require("MOCK AI Demo")
    capture("remote-detail")
    tap("Перейти к регистрации")
    require("Функция пока недоступна")
    tap("Понятно")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tap("Онлайн")
    # Quick chips scroll horizontally; use their observed row bounds.
    n = node("Онлайн")
    _, top, _, bottom = map(int, re.findall(r"\d+", n.get("bounds")))
    y = (top+bottom)//2
    adb("shell", "input", "swipe", "940", str(y), "100", str(y), "350")
    tap("Казахстан")
    tap("Открыта регистрация")
    assert any(r["query"].get("format") == "ONLINE" and r["query"].get("countryCode") == "KZ"
               and r["query"].get("registrationStatus") == "OPEN" for r in requests)
    tap("Фильтры и порядок")
    require("Дополнительные фильтры недоступны")
    capture("remote-filters-error")
    control["metadata_failure"] = False
    tap("Повторить")
    reveal("Страна проведения")
    capture("remote-filters")
    reveal("Показать события")
    tap("Показать события")
    passed("AND presets, independent metadata failure and retry")
    tap("Загрузить ещё")
    require("MOCK Next Page")
    assert sum(r["query"].get("page") == "1" for r in requests) == 1
    passed("next page is appended once")
    control["demo"] = False
    launch()
    require("MOCK AI Live")
    control["detail_failure"] = True
    control["hold_detail"] = True
    tap("Сохранить хакатон", "content-desc")
    assert detail_received.wait(timeout=5), "Delayed detail request did not reach fixture server"
    pid_before = adb("shell", "pidof", package).strip()
    current_density = int(re.findall(r"density: (\d+)", density_before)[-1])
    adb("shell", "wm", "density", str(current_density + 1))
    # Density is not handled in configChanges: Android recreates the Activity, not the process.
    assert adb("shell", "pidof", package).strip() == pid_before
    assert node("Сохраняем изменение…", "content-desc") is not None, "Pending state lost on recreation"
    control["hold_detail"] = False
    release_detail.set()
    require("Не удалось сохранить изменение")
    restore_density()
    require("Не удалось сохранить изменение")
    capture("remote-save-error-recreated")
    assert len(json.loads(read_file(stored))["entries"]) == 1
    tap("Понятно")
    passed("pending save and unacknowledged failure survive Activity recreation")
    control["detail_failure"] = False
    tap("Сохранить хакатон", "content-desc")
    entries = wait_entries(2)
    assert len(entries) == 2 and {e["detail"]["hackathon"]["isDemo"] for e in entries} == {True, False}
    passed("404 cannot mark saved; same-ID server DEMO and real copies coexist")
    launch("profile")
    require("Гостевой режим")
    assert node("devnull") is None
    capture("remote-profile")
    launch("saved")
    require("MOCK AI Live")
    require("MOCK AI Demo")
    capture("remote-saved")
    server.shutdown()
    launch("saved")
    require("MOCK AI Live")
    tap("MOCK AI Live")
    capture("remote-detail-offline")
    tap("Убрать из сохранённого", "content-desc")
    require("MOCK AI Live")
    assert len(json.loads(read_file(stored))["entries"]) == 1
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    require("MOCK AI Demo")
    assert node("MOCK AI Live") is None
    tap("Календарь")
    require("Календарь")
    capture("remote-calendar-offline")
    passed("cold offline saved/detail/calendar, exact-key removal, opened copy stays readable")
    assert (read_file(demo_file) if file_exists(demo_file) else None) == demo_before
    assert all(r["authorization"] is None for r in requests)
    assert all(r["path"].startswith(("/api/v1/hackathons", "/api/v1/metadata/filters")) for r in requests)
    passed("bundled guest bytes unchanged; only public requests and no auth")
finally:
    release_detail.set()
    server.shutdown()
    server.server_close()
    adb("shell", "am", "force-stop", package)
    restore_density()
    if original is not None:
        write_file(original)
    elif not existed:
        adb("shell", "run-as", package, "rm", "-f", stored)
    (out / "remote-smoke.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "remote-requests.json").write_text(json.dumps(requests, ensure_ascii=False, indent=2), encoding="utf-8")

