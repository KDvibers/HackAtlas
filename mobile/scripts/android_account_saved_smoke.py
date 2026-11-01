"""M3 saved acceptance: real isolated B2 on8771, disposable DB, emulator only.

Synthetic accounts, no credentials in artifacts. Guest bytes/display/network restored.
Requires REMOTE debug for http://10.0.2.2:8771/api/v1/ and private-dev signup.
Never target a user/production DB. Do not run another UiAutomator reader concurrently.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import traceback
import time

import android_auth_smoke as auth_smoke
from android_remote_smoke import PACKAGE

ROOT = Path(__file__).resolve().parents[2]
BASE = "http://10.0.2.2:8771/api/v1/"
auth_smoke.BASE = BASE


class SavedSmoke(auth_smoke.AuthSmoke):
    def __init__(self, args):
        super().__init__(args)
        self.out = ROOT / "mobile/artifacts/m3-account-saved"
        self.backup = ROOT / "mobile/build/m3-account-saved/device-backup"
        for folder in (self.out / "screenshots", self.backup):
            folder.mkdir(parents=True, exist_ok=True)
        self.cache_path = "no_backup/account-saved/" + hashlib.sha256(BASE.encode()).hexdigest() + ".json"

    def sql(self, statement):
        r = subprocess.run([self.args.docker, "exec", "-i", "hackatlas-m3-account-saved-db",
                            "psql", "-v", "ON_ERROR_STOP=1", "-U", "hackatlas_m3_saved", "-d", "hackatlas_m3_saved", "-tA"],
                           input=statement.encode(), capture_output=True, timeout=15)
        if r.returncode:
            raise AssertionError("Disposable DB inspection failed")
        return r.stdout.decode().strip()

    def account_count(self, email):
        assert re.fullmatch(r"m3-[a-f0-9]+@example\.org", email)
        return int(self.sql("SELECT count(*) FROM hackatlas.saved_hackathon WHERE user_id IN "
                            "(SELECT id FROM hackatlas.user_account WHERE email='" + email + "');"))

    def cache(self, count):
        for _ in range(70):
            if self.exists(self.cache_path):
                doc = json.loads(self.read(self.cache_path))
                if len(doc["entries"]) == count and doc["cachedAt"] is not None:
                    return doc
            time.sleep(.2)
        raise AssertionError("Account cache did not reach expected count")

    def run(self):
        assert not self.exists(self.session_path), "Existing test-source credential"
        assert not self.exists(self.cache_path), "Existing test-source account cache"
        self.prepare()
        self.adb("shell", "wm", "size", "1080x2400")
        self.adb("shell", "wm", "density", "440")
        events = self.get("hackathons", sort="DEADLINE_ASC", size=20)["items"]
        selected = events[:2]
        self.launch()
        for index, event in enumerate(selected):
            self.require(event["title"]); self.tap(event["title"])
            self.tap("Сохранить хакатон", "content-desc")
            self.entries(index+1); self.back()
        guest_bytes = self.read(self.stored)
        self.tap("Профиль"); self.tap("Создать аккаунт")
        self.fill(self.email, "M3 Saved A"); self.press("Создать аккаунт")
        self.require("M3 Saved A")
        empty = self.cache(0)
        assert self.account_count(self.email) == 0
        assert self.read(self.stored) == guest_bytes
        self.reveal("Скопировать гостевые закладки (2)")
        self.capture("profile-before-copy")
        self.tap("Скопировать гостевые закладки (2)")
        self.require("Скопировать закладки?"); self.capture("copy-confirmation")
        self.tap("Отмена")
        assert self.account_count(self.email) == 0
        self.passed("account starts empty, guest data retained, declining copy makes no server changes")

        self.press("Скопировать гостевые закладки (2)")
        self.tap("Скопировать")
        self.reveal("Гостевые закладки скопированы. Локальные копии сохранены.")
        self.require("Гостевые закладки скопированы. Локальные копии сохранены.")
        first = self.cache(2)
        assert len(first["imported"]) == 2 and self.account_count(self.email) == 2
        assert self.read(self.stored) == guest_bytes
        self.capture("copy-complete")
        self.launch("saved")
        self.require("Сохранено в текущем аккаунте")
        for event in selected:
            self.require(event["title"])
        self.capture("account-saved")
        self.passed("explicit confirmed copy uses real PUTs, durable per-item journal, full snapshots and unchanged guest bytes")

        # Remove first visible card through real UI, then add it from the public catalogue.
        first_id = first["entries"][0]["detail"]["hackathon"]["id"]
        removed = next(e for e in selected if e["id"] == first_id)
        self.tap("Убрать из сохранённого", "content-desc")
        self.cache(1); assert self.account_count(self.email) == 1
        self.tap("Главная"); self.require(removed["title"]); self.tap(removed["title"])
        self.tap("Сохранить хакатон", "content-desc")
        self.cache(2); assert self.account_count(self.email) == 2
        assert self.read(self.stored) == guest_bytes
        self.back(); self.tap("Календарь"); self.capture("account-calendar")
        self.passed("real account DELETE/PUT, catalogue membership and account calendar; guest bytes unchanged")

        self.online(False)
        self.launch("saved")
        self.require("Сохранено в текущем аккаунте")
        for event in selected:
            self.require(event["title"])
        self.require("Сессию или связь с сервером пока не удалось подтвердить. Доступен просмотр копии; изменения требуют сети.")
        for node in self.tree().iter("node"):
            if node.get("content-desc") == "Убрать из сохранённого":
                assert node.get("enabled") == "false", "Offline mutation remained enabled"
        self.capture("account-saved-offline")
        self.tap(selected[0]["title"])
        self.require("Сохранённая копия. Сведения могли измениться и не обновляются автоматически.")
        self.capture("account-detail-offline")
        self.back(); self.tap("Календарь"); self.capture("account-calendar-offline")
        self.tap("Профиль"); self.require("Сохранённая сессия")
        self.capture("account-profile-offline")
        assert self.read(self.stored) == guest_bytes
        self.passed("cold offline restore uses matching account cache; detail/calendar work; mutations disabled; no fake profile")

        self.online(True)
        self.launch("profile"); self.require("M3 Saved A")
        self.logout()
        self.reveal("Вы вышли. Сессия отозвана на сервере.", up=False)
        self.require("Вы вышли. Сессия отозвана на сервере.")
        assert not self.exists(self.cache_path) and not self.exists(self.session_path)
        self.tap("Сохранённое"); self.require("На этом устройстве · доступны без сети")
        for event in selected:
            self.require(event["title"])
        assert self.read(self.stored) == guest_bytes
        self.capture("guest-after-logout")
        self.passed("logout removes private cache/credential and restores original guest saved without conversion")

        self.tap("Профиль"); self.tap("Создать аккаунт")
        self.fill(self.second_email, "M3 Saved B"); self.press("Создать аккаунт")
        self.require("M3 Saved B"); second = self.cache(0)
        assert second["userId"] != first["userId"] and not second["imported"]
        assert self.account_count(self.second_email) == 0
        self.launch("saved"); self.require("Сохранено в текущем аккаунте")
        self.require("Здесь будут ваши хакатоны")
        for event in selected:
            assert self.find(self.tree(), event["title"]) is None
        self.capture("second-account-empty")
        assert self.read(self.stored) == guest_bytes
        self.passed("second account sees neither first account snapshots nor silent guest import")

        self.adb("shell", "wm", "density", "480")
        self.adb("shell", "settings", "put", "system", "font_scale", "1.3")
        self.launch("saved"); self.require("Сохранено в текущем аккаунте")
        self.capture("saved-360-font130")
        self.launch("profile"); self.require("M3 Saved B")
        self.reveal("Скопировать гостевые закладки (2)")
        self.capture("profile-360-font130")
        self.passed("account status/copy controls remain reachable at360dp and font130%")

    def review_error(self):
        assert not self.exists(self.session_path) and not self.exists(self.cache_path)
        self.prepare()
        self.launch("register")
        self.fill(self.email, "M3 Error Check"); self.press("Создать аккаунт")
        self.require("M3 Error Check"); self.cache(0)
        event = self.get("hackathons", sort="DEADLINE_ASC", size=20)["items"][0]
        self.launch(); self.require(event["title"]); self.tap(event["title"])
        self.require("Поделиться", "content-desc")
        for _ in range(20):
            if self.require("Сохранить хакатон", "content-desc").get("enabled") == "true":
                break
            time.sleep(.2)
        self.online(False)
        self.tap("Сохранить хакатон", "content-desc")
        self.require("Не удалось синхронизировать", timeout=25)
        self.require("Синхронизация не завершена. Сервер мог принять изменение. Обновите список или повторите действие.")
        self.capture("mutation-error-detail")
        self.passed("account mutation failure is visible on the detail screen with explicit ambiguous-outcome wording")
        self.online(True)
        self.tap("Обновить закладки"); self.cache(0)
        for _ in range(20):
            if self.require("Сохранить хакатон", "content-desc").get("enabled") == "true":
                break
            time.sleep(.2)
        self.tap("Сохранить хакатон", "content-desc"); self.cache(1)
        self.back(); self.tap("Сохранённое"); self.require(event["title"]); self.tap(event["title"])
        self.require("Сохранённая копия. Сведения могли измениться и не обновляются автоматически.")
        self.adb("shell", "input", "keyevent", "KEYCODE_HOME")
        try:
            self.sql("UPDATE hackatlas.catalog_hackathon SET publication_status='ARCHIVED' WHERE id='" + event["id"] + "';")
            self.adb("shell", "am", "start", "-W", "-n", "dev.hackatlas.mobile.debug/dev.hackatlas.mobile.MainActivity")
            self.cache(0)
            self.require("Подробности недоступны")
            assert self.find(self.tree(), "Перейти к регистрации") is None
            self.capture("archived-open-detail")
            self.passed("authoritative removal/archival clears already-open snapshot and stale registration CTA")
        finally:
            self.sql("UPDATE hackatlas.catalog_hackathon SET publication_status='PUBLISHED' WHERE id='" + event["id"] + "';")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True); parser.add_argument("--docker", required=True)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--review-errors-only", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Emulator only")
    args.device_api_base_url = BASE; args.api_base_url = "http://127.0.0.1:8771/api/v1"
    smoke = SavedSmoke(args); failure = None
    try:
        smoke.review_error() if args.review_errors_only else smoke.run()
    except BaseException as exc:
        failure = type(exc).__name__ + ": " + str(exc).replace(smoke.secret, "[REDACTED]")
        print("FAIL", failure, flush=True)
        print("TRACE", [(Path(f.filename).name, f.name, f.lineno) for f in traceback.extract_tb(exc.__traceback__)], flush=True)
    finally:
        errors = smoke.restore()
        if smoke.settings:
            smoke.adb("shell", "run-as", PACKAGE, "rm", "-f", smoke.session_path, smoke.cache_path)
        report = {"source": "real local B2, disposable PostgreSQL, synthetic accounts and DEMO catalogue",
                  "checks": smoke.results, "failure": failure, "restoreErrors": errors,
                  "fullAcceptance": failure is None and not errors,
                  "ios": "source only; Mac/Xcode required"}
        name = "review-error-smoke.json" if args.review_errors_only else "android-smoke.json"
        (smoke.out / name).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return 1 if failure or errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
