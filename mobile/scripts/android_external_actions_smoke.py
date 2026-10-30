"""External actions acceptance on an emulator, with synthetic fixtures (NOT live B1).

Build REMOTE debug for http://10.0.2.2:8769/api/v1/ with explicit debug HTTP.
The fixture server binds only 127.0.0.1. Website targets use reserved example.org.
Only open/cancel the Android Sharesheet; never select a recipient or send anything.
Guest bytes and emulator settings are backed up/restored by the Task5 helper.
"""
import argparse
import copy
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import threading
import time
import traceback
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET

from android_remote_smoke import Smoke, PACKAGE, SNAPSHOT_NOTE

ROOT = Path(__file__).resolve().parents[2]
BASE = "http://10.0.2.2:8769/api/v1/"
REGISTER = "https://example.org/?hackatlas_smoke=registration"
OFFICIAL = "https://example.org/?hackatlas_smoke=official"


class ActionsSmoke(Smoke):
    def __init__(self, args):
        super().__init__(args)
        self.out = ROOT / "mobile/artifacts/m2-external-actions"
        self.backup = ROOT / "mobile/build/external-actions/device-backup"
        for path in (self.out / "screenshots", self.backup):
            path.mkdir(parents=True, exist_ok=True)
        self.control = dict(demo=False, registrationStatus="OPEN", eventStatus="SCHEDULED", registrationUrl=REGISTER)
        self.requests = []
        self.seed = json.loads((ROOT / "api/examples/hackathon-detail.json").read_text(encoding="utf-8"))
        self.metadata = json.loads((ROOT / "api/examples/filter-metadata.json").read_text(encoding="utf-8"))
        self.summary = json.loads((ROOT / "api/examples/catalog-page.json").read_text(encoding="utf-8"))["items"][0]

    def event(self):
        event = copy.deepcopy(self.summary)
        event.update(title="MOCK Link Demo" if self.control["demo"] else "MOCK Link Event",
            isDemo=self.control["demo"], registrationStatus=self.control["registrationStatus"], eventStatus=self.control["eventStatus"])
        return event

    def start_server(self):
        smoke = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                path = urlsplit(self.path).path
                smoke.requests.append({"path": path, "hasAuthorization": self.headers.get("Authorization") is not None,
                    "scenario": dict(smoke.control)})
                status = 200
                if path == "/api/v1/metadata/filters":
                    payload = smoke.metadata
                elif path == "/api/v1/hackathons":
                    payload = dict(items=[smoke.event()], page=0, size=20, totalElements=1, totalPages=1, hasNext=False, asOf="2026-10-12T10:00:00Z")
                elif path == "/api/v1/hackathons/" + smoke.seed["id"]:
                    payload = copy.deepcopy(smoke.seed)
                    payload.update(smoke.event())
                    payload.update(officialUrl=OFFICIAL, registrationUrl=smoke.control["registrationUrl"], sourceUrl="https://example.org/?hackatlas_smoke=source",
                        description="Synthetic external-action test. This is not a real event or registration.")
                else:
                    status, payload = 404, {}
                body = json.dumps(payload).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(body)
        server = ThreadingHTTPServer(("127.0.0.1", 8769), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        return server

    def tree(self):
        # Native browser/chooser windows are intentional targets in this smoke.
        for _ in range(4):
            result = self.adb("shell", "uiautomator", "dump", "/sdcard/hackatlas-external.xml")
            if "dumped to" in result:
                return ET.fromstring(self.adb("shell", "cat", "/sdcard/hackatlas-external.xml"))
            time.sleep(.3)
        raise AssertionError("No foreground hierarchy")

    def resumed(self):
        dump = self.adb("shell", "dumpsys", "activity", "activities")
        return "\n".join(line for line in dump.splitlines() if "mResumedActivity" in line or "topResumedActivity" in line), dump

    def require_native(self, url=None, chooser=False):
        deadline = time.monotonic() + 15
        while True:
            resumed, dump = self.resumed()
            candidate = (chooser and "ChooserActivity" in resumed) or (
                url and resumed.strip() and PACKAGE not in resumed
                and "act=android.intent.action.VIEW" in dump and url in dump)
            if candidate:
                # An activity can be RESUMED before its window receives input.
                # Wait for its real UI before Back, otherwise Back may reach the
                # previous HackAtlas window and navigate away from detail.
                packages = {node.get("package") for node in self.tree().iter("node")}
                if PACKAGE not in packages and any(package and package + "/" in resumed for package in packages):
                    print("NATIVE", "chooser" if chooser else url, resumed.strip(), flush=True)
                    return
            if time.monotonic() >= deadline:
                raise AssertionError("Native action did not open: " + resumed)
            time.sleep(.3)

    def return_to_app(self):
        for _ in range(4):
            self.back()
            # Back is asynchronous; sending another Back during the transition
            # would pop HackAtlas detail after the browser already closed.
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                if PACKAGE in self.resumed()[0]:
                    return
                time.sleep(.2)
        raise AssertionError("Back did not return to HackAtlas")

    def open_detail(self):
        self.launch()
        self.tap(self.event()["title"])
        self.require("Поделиться", "content-desc")

    def assert_disabled(self, label):
        tree = self.tree()
        node = self.find(tree, label)
        assert node is not None, "Missing disabled label " + label
        parents = {child: parent for parent in tree.iter() for child in parent}
        while node is not None:
            if node.get("enabled") == "false":
                return
            node = parents.get(node)
        raise AssertionError("Action still enabled: " + label)

    def run(self):
        self.prepare()
        if self.args.states_only:
            self.check_disabled_states()
            return
        self.open_detail()
        self.capture("detail-active")
        self.reveal("Открыть официальный сайт") if not self.args.registration_only else None
        self.tap("Перейти к регистрации")
        self.require_native(url=REGISTER)
        self.capture("browser-registration")
        self.return_to_app()
        if self.args.registration_only:
            self.passed("registration opens native browser")
            return
        self.require("Открыть официальный сайт")
        self.tap("Открыть официальный сайт")
        self.require_native(url=OFFICIAL)
        self.return_to_app()
        self.require("Открыть официальный сайт")
        self.passed("registration/official browser intents and detail scroll on return")

        self.reveal("Поделиться", "content-desc", up=False)
        self.tap("Поделиться", "content-desc")
        self.require_native(chooser=True)
        self.require("MOCK Link Event", prefix=True)
        self.capture("share-real")
        self.return_to_app()
        self.require("MOCK Link Event")
        self.tap("Сохранить хакатон", "content-desc")
        snapshot = self.entries(1)[0]
        self.passed("native text Sharesheet opens and cancels without changing detail")

        self.online(False)
        self.launch("saved")
        self.tap("MOCK Link Event")
        self.require(SNAPSHOT_NOTE)
        self.capture("detail-offline")
        self.tap("Поделиться", "content-desc")
        self.require_native(chooser=True)
        self.capture("share-offline")
        self.return_to_app()
        self.require(SNAPSHOT_NOTE)
        self.tap("Перейти к регистрации")
        self.require_native(url=REGISTER)
        self.return_to_app()
        self.require(SNAPSHOT_NOTE)
        assert self.entries(1)[0] == snapshot
        self.passed("cold offline snapshot opens browser/share with unchanged stored detail")

        self.online(True)
        self.control["demo"] = True
        self.open_detail()
        self.tap("Посмотреть DEMO-ссылку")
        self.require("DEMO: просмотр ссылки")
        assert PACKAGE in self.resumed()[0]
        self.require(REGISTER)
        self.capture("demo-preview")
        self.tap("Понятно")
        self.tap("Поделиться", "content-desc")
        self.require_native(chooser=True)
        self.require("DEMO — вымышленное событие", prefix=True)
        self.capture("share-demo")
        self.return_to_app()
        self.passed("server DEMO previews instead of browsing and labels shared text")

        self.check_disabled_states()
        assert not any(row["hasAuthorization"] for row in self.requests)
        self.passed("only public fixture GETs, no auth, submissions or recipient sends")

    def check_disabled_states(self):
        self.control["demo"] = False
        for changes, label in [
            (dict(registrationStatus="CLOSED", eventStatus="SCHEDULED", registrationUrl=REGISTER), "Регистрация закрыта"),
            (dict(registrationStatus="OPEN", eventStatus="CANCELLED", registrationUrl=REGISTER), "Событие отменено"),
            (dict(eventStatus="SCHEDULED", registrationUrl=None), "Регистрация недоступна"),
            (dict(registrationUrl="http://example.org/unsafe"), "Регистрация недоступна"),
        ]:
            self.control.update(changes)
            print("STATE", self.control, flush=True)
            self.open_detail()
            self.require(label)
            self.assert_disabled(label)
            if label == "Регистрация закрыта":
                self.capture("registration-closed")
        self.passed("closed/cancelled/missing/unsafe registration stays disabled")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--registration-only", action="store_true", help="Targeted RED/GREEN, not full acceptance")
    parser.add_argument("--states-only", action="store_true", help="Targeted status diagnostics, not full acceptance")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Emulator required")
    args.device_api_base_url, args.api_base_url = BASE, "http://127.0.0.1:8769/api/v1/"
    smoke = ActionsSmoke(args)
    server, failure = None, None
    try:
        server = smoke.start_server()
        smoke.run()
    except BaseException as exc:
        failure = f"{type(exc).__name__}: {exc}"
        traceback.print_exc()
        try:
            smoke.capture("failure")
        except Exception:
            pass
    finally:
        errors = smoke.restore()
        if server:
            server.shutdown()
            server.server_close()
        report = dict(status="PASS" if failure is None and not errors else "FAIL", synthetic=True,
            fullAcceptance=not (args.registration_only or args.states_only), results=smoke.results, failure=failure, restoreErrors=errors,
            apiBaseUrl=BASE, requests=smoke.requests)
        (smoke.out / "smoke.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if failure is not None or errors:
        raise SystemExit("FAIL: " + str(failure or errors))
    print("PASS: external actions; guest bytes/settings restored", flush=True)


if __name__ == "__main__":
    main()
