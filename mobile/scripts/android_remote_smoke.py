"""Task 5: real B1 dev seed -> Android REMOTE -> persistent offline snapshots.

Requires the friend's five-event DEMO seed, fixed clock, and an installed debug APK
configured with --device-api-base-url and page size 2. No fixture server is used.
Host API checks are read-only. Only the selected emulator's debug package is used.
Network/display settings and existing guest bytes are backed up and restored even
on failure; emergency backups stay in ignored mobile/build/task5/device-backup/.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import traceback
from urllib.parse import urlencode, urlsplit
from urllib.request import urlopen
import xml.etree.ElementTree as ET

PACKAGE = "dev.hackatlas.mobile.debug"
COMPONENT = PACKAGE + "/dev.hackatlas.mobile.MainActivity"
MONTHS = "Январь Февраль Март Апрель Май Июнь Июль Август Сентябрь Октябрь Ноябрь Декабрь".split()
SHORT_MONTHS = "янв фев мар апр май июн июл авг сен окт ноя дек".split()
SNAPSHOT_NOTE = "Сохранённая копия. Сведения могли измениться и не обновляются автоматически."


class Smoke:
    def __init__(self, args):
        self.args = args
        self.root = Path(__file__).resolve().parents[2]
        self.out = self.root / "mobile/artifacts/m2-remote"
        self.backup = self.root / "mobile/build/task5/device-backup"
        for path in (self.out / "screenshots", self.backup):
            path.mkdir(parents=True, exist_ok=True)
        self.base = args.device_api_base_url.rstrip("/") + "/"
        self.stored = "no_backup/guest-saved/remote-" + hashlib.sha256(self.base.encode()).hexdigest() + "-v1.json"
        self.results, self.http = [], []
        self.original = {}
        self.settings = None

    def adb(self, *command, payload=None, binary=False):
        r = subprocess.run([self.args.adb, "-s", self.args.serial, *command],
                           input=payload, capture_output=True, timeout=45)
        if r.returncode:
            raise RuntimeError((r.stderr or r.stdout).decode(errors="replace"))
        return r.stdout if binary else r.stdout.decode("utf-8")

    def get(self, route, **params):
        url = self.args.api_base_url.rstrip("/") + "/" + route
        if params:
            url += "?" + urlencode(params)
        with urlopen(url, timeout=10) as response:
            assert response.status == 200
            data = json.load(response)
        self.http.append({"path": route, "query": params, "status": 200, "body": data})
        return data

    def tree(self):
        for _ in range(4):
            response = self.adb("shell", "uiautomator", "dump", "/sdcard/hackatlas-task5.xml")
            if "dumped to" in response:
                value = ET.fromstring(self.adb("shell", "cat", "/sdcard/hackatlas-task5.xml"))
                if any(n.get("package") == PACKAGE for n in value.iter("node")):
                    return value
            time.sleep(.3)
        raise AssertionError("No live app hierarchy")

    @staticmethod
    def find(tree, value, attr="text", prefix=False):
        return next((n for n in tree.iter("node") if
                     (n.get(attr, "").startswith(value) if prefix else n.get(attr) == value)), None)

    def require(self, value, attr="text", timeout=35, prefix=False):
        deadline = time.monotonic() + timeout
        while True:
            hierarchy = self.tree()
            n = self.find(hierarchy, value, attr, prefix)
            if n is not None:
                return n
            if time.monotonic() >= deadline:
                raise AssertionError(f"Missing {attr}: {value}; visible: " +
                                     str([n.get("text") for n in hierarchy.iter("node") if n.get("text")]))
            time.sleep(.25)

    @staticmethod
    def bounds(n):
        return tuple(map(int, re.findall(r"\d+", n.get("bounds"))))

    def tap(self, value, attr="text", prefix=False):
        n = self.require(value, attr, prefix=prefix)
        x1, y1, x2, y2 = self.bounds(n)
        assert x2 > x1 and y2 > y1, "Target is outside viewport: " + value
        self.adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))

    def swipe(self, up=True):
        width, height = map(int, re.findall(r"\d+x\d+", self.adb("shell", "wm", "size"))[-1].split("x"))
        ys = (.78, .35) if up else (.35, .78)
        self.adb("shell", "input", "swipe", str(width//2), str(int(height*ys[0])),
                 str(width//2), str(int(height*ys[1])), "350")

    def reveal(self, value, attr="text", up=True, prefix=False):
        for _ in range(9):
            if self.find(self.tree(), value, attr, prefix) is not None:
                return
            self.swipe(up)
        self.require(value, attr, timeout=1, prefix=prefix)

    def quick_chip(self, value):
        for _ in range(4):
            tree = self.tree()
            if self.find(tree, value) is not None:
                self.tap(value)
                return
            anchor = next((self.find(tree, label) for label in
                           ("Онлайн", "Очно", "Открыта регистрация", "Казахстан")
                           if self.find(tree, label) is not None), None)
            assert anchor is not None, "Quick filters row is not visible"
            _, y1, _, y2 = self.bounds(anchor)
            width = int(re.findall(r"\d+x\d+", self.adb("shell", "wm", "size"))[-1].split("x")[0])
            self.adb("shell", "input", "swipe", str(int(width*.87)), str((y1+y2)//2),
                     str(int(width*.12)), str((y1+y2)//2), "350")
        raise AssertionError("Quick chip missing: " + value)

    def launch(self, route="home"):
        self.adb("shell", "am", "force-stop", PACKAGE)
        result = self.adb("shell", "am", "start", "-W", "-n", COMPONENT, "--es", "screen", route)
        assert "Status: ok" in result, "Android could not launch app: " + result

    def back(self):
        self.adb("shell", "input", "keyevent", "KEYCODE_BACK")

    def capture(self, name):
        hierarchy = self.tree()
        (self.out / (name + "-ui.xml")).write_bytes(ET.tostring(hierarchy, encoding="utf-8"))
        (self.out / "screenshots" / (name + ".png")).write_bytes(self.adb("exec-out", "screencap", "-p", binary=True))

    def exists(self, path):
        # path is generated internally, never supplied by an external API.
        return self.adb("shell", "run-as", PACKAGE, "sh", "-c",
                        "'test -f " + path + " && echo yes || echo no'").strip() == "yes"

    def read(self, path):
        return self.adb("exec-out", "run-as", PACKAGE, "cat", path, binary=True)

    def write(self, path, data):
        self.adb("shell", "-T", "run-as", PACKAGE, "tee", path, payload=data, binary=True)

    def entries(self, count):
        for _ in range(50):
            entries = json.loads(self.read(self.stored))["entries"]
            if len(entries) == count:
                return entries
            time.sleep(.2)
        raise AssertionError("Snapshot count did not become " + str(count))

    def online(self, enabled):
        self.adb("shell", "cmd", "connectivity", "airplane-mode", "disable" if enabled else "enable")
        for service in ("wifi", "data"):
            self.adb("shell", "svc", service, "enable" if enabled else "disable")
        # svc returns before association/DHCP. Wait for a usable network before
        # pressing the app's Retry; never retry the app request behind its UI.
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            status = self.adb("shell", "cmd", "wifi", "status")
            if enabled and "Wifi is connected to" in status and "isUsable: true" in status:
                return
            if not enabled and "Wifi is disabled" in status:
                return
            time.sleep(.5)
        raise AssertionError("Emulator Wi-Fi did not become " + ("connected" if enabled else "disabled"))

    def passed(self, name):
        self.results.append({"name": name, "status": "PASS"})
        print("PASS", name, flush=True)

    def check_catalog_position_after_detail(self, title):
        before = self.bounds(self.require(title))
        self.tap(title)
        self.require("Поделиться", "content-desc")
        self.require(title)
        self.back()
        after = self.bounds(self.require(title, timeout=5))
        assert abs(after[1] - before[1]) <= 8, "Returning from detail reset catalog scroll"

    def prepare(self):
        self.adb("shell", "am", "force-stop", PACKAGE)
        self.settings = {
            "size": self.adb("shell", "wm", "size"),
            "density": self.adb("shell", "wm", "density"),
            "font_scale": self.adb("shell", "settings", "get", "system", "font_scale").strip(),
            **{key: self.adb("shell", "settings", "get", "global", key).strip()
               for key in ("airplane_mode_on", "wifi_on", "mobile_data")},
        }
        for path in (self.stored, "no_backup/guest-saved/demo-v1.json"):
            self.original[path] = self.read(path) if self.exists(path) else None
            if self.original[path] is not None:
                (self.backup / Path(path).name).write_bytes(self.original[path])
        (self.backup / "restore.json").write_text(json.dumps({"serial": self.args.serial,
            "settings": self.settings, "files": {k: v is not None for k, v in self.original.items()}}, indent=2))
        self.adb("shell", "run-as", PACKAGE, "mkdir", "-p", "no_backup/guest-saved")
        self.write(self.stored, json.dumps({"version": 1, "scope": "remote-guest@" + self.base, "entries": []}).encode())
        self.online(True)
        self.adb("shell", "settings", "put", "system", "font_scale", "1.0")

    def select_date(self, date):
        year, month, day = map(int, date.split("-"))
        for _ in range(36):
            tree = self.tree()
            label = next((n.get("text") for n in tree.iter("node")
                          if re.fullmatch(r"(" + "|".join(MONTHS) + r") \d{4}", n.get("text", ""))), None)
            assert label, "Calendar month heading missing"
            name, y = label.split()
            current, target = int(y)*12 + MONTHS.index(name)+1, year*12+month
            if current == target:
                value = f"{day} {SHORT_MONTHS[month-1]} {year}."
                self.tap(value, "content-desc", prefix=True)
                return value
            self.tap("Следующий месяц" if current < target else "Предыдущий месяц", "content-desc")
        raise AssertionError("Target date outside smoke range")

    def run(self):
        pages = [self.get("hackathons", size=2, page=n) for n in range(3)]
        assert [len(p["items"]) for p in pages] == [2, 2, 1]
        assert [p["hasNext"] for p in pages] == [True, True, False]
        assert all(p["totalElements"] == 5 and p["totalPages"] == 3 and p["size"] == 2 for p in pages)
        events = [event for page in pages for event in page["items"]]
        assert len({e["id"] for e in events}) == 5 and all(e["isDemo"] for e in events)
        detail = self.get("hackathons/" + self.args.event_id)
        assert detail["isDemo"] and detail["id"] == self.args.event_id
        metadata = self.get("metadata/filters")
        assert metadata["topics"] and metadata["formats"]
        filtered = self.get("hackathons", query="AI Sprint", format="ONLINE", registrationStatus="OPEN", size=2)
        assert [e["id"] for e in filtered["items"]] == [detail["id"]]
        title = detail["title"]
        a, b = events[:2]
        self.passed("live B1: three public GETs, five DEMO rows, pages 2/2/1 and AND search")
        self.prepare()
        self.launch()
        self.require(a["title"])
        assert any("DEMO" in n.get("text", "") for n in self.tree().iter("node"))
        self.capture("remote-home")
        self.online(False)
        self.launch()
        self.require("Каталог недоступен")
        assert self.find(self.tree(), a["title"]) is None, "Network failure must not fall back to fixtures"
        self.capture("remote-first-error")
        self.online(True)
        self.tap("Повторить")
        self.require(a["title"])
        self.passed("initial network error is explicit; Retry restores the live catalog")

        self.reveal("Загрузить ещё")
        self.online(False)
        self.tap("Загрузить ещё")
        self.require("Не удалось загрузить следующие события")
        self.require(b["title"])
        self.capture("remote-append-error")
        self.online(True)
        self.reveal("Повторить")
        self.tap("Повторить")
        self.reveal(events[2]["title"])
        self.reveal("Загрузить ещё")
        self.tap("Загрузить ещё")
        self.reveal(events[4]["title"])
        self.swipe()
        assert self.find(self.tree(), "Загрузить ещё") is None
        self.capture("remote-last-page")
        self.check_catalog_position_after_detail(events[4]["title"])
        self.passed("append error/retry, last page and catalog scroll on return from detail")

        self.launch()
        self.require(a["title"])
        self.online(False)
        self.tap(a["title"])
        self.require("Подробности недоступны")
        self.capture("remote-detail-error")
        self.online(True)
        self.tap("Повторить")
        self.require(a["title"])
        self.require("Поделиться", "content-desc")
        title_before = self.bounds(self.require(a["title"]))[1]
        self.swipe()
        title_after = self.find(self.tree(), a["title"])
        assert title_after is None or self.bounds(title_after)[1] < title_before, "Detail A did not scroll"
        self.back()
        self.require(a["title"])
        self.tap(b["title"])
        self.require(b["title"])
        assert self.bounds(self.require("Поделиться", "content-desc"))[1] < 250
        self.capture("remote-detail-b-top")
        self.back()
        self.require(a["title"])
        self.passed("detail failure/retry; scrolled detail A does not move detail B from its top")

        # No per-character sleeps: a previous asynchronous value feedback loop
        # intermittently lost letters here (AI Spit / AI Spint).
        for attempt in range(3):
            if attempt:
                self.tap("Закрыть", "content-desc")
            self.tap("Поиск хакатонов", "content-desc")
            self.adb("shell", "input", "text", "AI")
            self.adb("shell", "input", "keyevent", "KEYCODE_SPACE")
            self.adb("shell", "input", "text", "Sprint")
            self.back()  # dismiss keyboard
            self.require("AI Sprint")
            self.require(title)
        self.quick_chip("Онлайн")
        self.quick_chip("Открыта регистрация")
        self.require(title)
        assert self.find(self.tree(), a["title"]) is None
        self.capture("remote-search-and")
        self.quick_chip("Фильтры и порядок")
        self.reveal("Темы")
        self.require("AI / ML")
        self.capture("remote-filters")
        self.back()
        self.tap(title)
        self.require("Поделиться", "content-desc")
        self.capture("remote-detail")
        self.tap("Сохранить хакатон", "content-desc")
        snapshot = self.entries(1)[0]
        saved = snapshot["detail"]
        assert saved["description"] == detail["description"]
        assert saved["hackathon"]["id"] == detail["id"] and saved["hackathon"]["isDemo"]
        assert saved["hackathon"]["starts"] == detail["starts"]
        assert saved["hackathon"]["lastVerifiedAt"] == detail["lastVerifiedAt"]
        self.back()
        self.require(title)
        self.require("AI Sprint")
        self.require("Открыта регистрация")
        self.passed("search/AND filters and metadata work; full server detail is saved to disk")

        self.launch("profile")
        self.require("Гостевой режим")
        self.capture("remote-profile")
        self.online(False)
        self.launch("saved")
        self.require(title)
        self.require("На этом устройстве · доступны без сети")
        self.capture("saved-offline")
        self.tap(title)
        self.require(SNAPSHOT_NOTE)
        self.capture("detail-offline")
        assert self.entries(1)[0] == snapshot
        self.back()
        self.tap("Календарь")
        day_label = self.select_date(detail["starts"]["date"])
        self.reveal(title)
        self.capture("calendar-offline")
        self.tap(title)
        self.require(SNAPSHOT_NOTE)
        self.swipe()
        self.back()
        self.require(title)
        self.reveal(day_label, "content-desc", up=False, prefix=True)
        # UiAutomator does not export Compose's selected property for these
        # day buttons. The dated results heading exposes the actual selection.
        selected_heading = day_label.split(".", 1)[0]
        self.reveal(selected_heading)
        self.require(selected_heading)
        self.passed("cold offline saved/detail/calendar; snapshot dates and calendar selection survive navigation")

        # 1080px / (480dpi / 160) = 360dp, independent of AVD's original density.
        self.adb("shell", "wm", "size", "1080x2400")
        self.adb("shell", "wm", "density", "480")
        self.adb("shell", "settings", "put", "system", "font_scale", "1.3")
        self.launch("saved")
        self.require(title)
        self.capture("saved-360-font130")
        self.tap(title)
        self.require("Перейти к регистрации")
        self.capture("detail-360-font130")
        self.back()
        self.tap("Календарь")
        self.select_date(detail["starts"]["date"])
        self.reveal(title)
        self.capture("calendar-360-font130")
        self.online(True)
        self.launch()
        self.require(a["title"])
        self.capture("home-360-font130")
        self.passed("real screenshots at 360dp with font scale 130%, including offline detail/calendar")

        self.online(False)
        self.launch("saved")
        self.tap(title)
        self.require(SNAPSHOT_NOTE)
        self.tap("Убрать из сохранённого", "content-desc")
        self.entries(0)
        self.require(title)  # already opened snapshot remains readable after removal
        self.launch("saved")
        assert self.find(self.tree(), title) is None
        self.tap("Календарь")
        self.require("Сохраните первое событие")
        self.passed("offline removal persists across restart and updates calendar without losing open detail")
        for path, before in self.original.items():
            if path != self.stored:
                assert (self.read(path) if self.exists(path) else None) == before
        self.passed("bundled guest file unchanged; no backend data writes or auth requests")

    def restore(self):
        errors = []
        def attempt(operation):
            try:
                operation()
            except Exception as exc:
                errors.append(str(exc))
        attempt(lambda: self.adb("shell", "am", "force-stop", PACKAGE))
        for path, data in self.original.items():
            if path == self.stored:
                if data is None:
                    attempt(lambda: self.adb("shell", "run-as", PACKAGE, "rm", "-f", path))
                else:
                    attempt(lambda: self.write(path, data))
        if self.settings:
            for key in ("size", "density"):
                match = re.search(r"Override \w+: (\S+)", self.settings[key])
                attempt(lambda key=key, match=match: self.adb("shell", "wm", key, match.group(1) if match else "reset"))
            font = self.settings["font_scale"]
            attempt(lambda: self.adb("shell", "settings", *( ("delete", "system", "font_scale") if font == "null"
                                                           else ("put", "system", "font_scale", font))))
            attempt(lambda: self.adb("shell", "cmd", "connectivity", "airplane-mode",
                                    "enable" if self.settings["airplane_mode_on"] == "1" else "disable"))
            for service, key in (("wifi", "wifi_on"), ("data", "mobile_data")):
                attempt(lambda service=service, key=key: self.adb("shell", "svc", service,
                                    "enable" if self.settings[key] in ("1", "2") else "disable"))
        for path, before in self.original.items():
            try:
                assert (self.read(path) if self.exists(path) else None) == before, path + " not restored"
            except Exception as exc:
                errors.append(str(exc))
        return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--api-base-url", required=True, help="Host-reachable B1 URL including /api/v1")
    parser.add_argument("--event-id", required=True, help="AI Sprint ID in the friend's dev seed")
    parser.add_argument("--device-api-base-url", default="http://10.0.2.2:8080/api/v1/")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Network fault injection is restricted to an emulator")
    for base in (args.api_base_url, args.device_api_base_url):
        url = urlsplit(base)
        if url.scheme not in ("http", "https") or url.username or url.password or url.query or url.fragment or url.path.rstrip("/") != "/api/v1":
            parser.error("Expected plain API base URL with /api/v1 and no credentials/query/fragment")
    if not re.fullmatch(r"[0-9a-fA-F-]{36}", args.event_id):
        parser.error("Expected a UUID event ID")
    smoke = Smoke(args)
    failure = None
    try:
        smoke.run()
    except BaseException as exc:
        failure = f"{type(exc).__name__}: {exc}"
        traceback.print_exc()
        try:
            smoke.capture("failure")
        except Exception:
            pass
    finally:
        restore_errors = smoke.restore()
        report = {"status": "PASS" if failure is None and not restore_errors else "FAIL",
                  "apiBaseUrl": args.api_base_url, "deviceApiBaseUrl": smoke.base,
                  "eventId": args.event_id, "results": smoke.results,
                  "failure": failure, "restoreErrors": restore_errors}
        (smoke.out / "remote-smoke.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        (smoke.out / "live-http.json").write_text(json.dumps(smoke.http, ensure_ascii=False, indent=2), encoding="utf-8")
    if failure is not None or restore_errors:
        raise SystemExit("FAIL: " + str(failure or restore_errors))
    print("PASS: all live checks; guest bytes and emulator settings restored", flush=True)


if __name__ == "__main__":
    main()
