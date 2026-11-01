"""M3 UI acceptance against isolated, real local B2 (never a fixture auth server).

Requires REMOTE debug APK for http://10.0.2.2:8770/api/v1/ and a disposable
backend on localhost:8770 with DEMO catalogue and private-dev registration enabled.
Only emulator-* is allowed. Synthetic account credentials stay in memory; do not
print request bodies, tokens or raw password UI. Guest bytes/display/network are
restored. This script intentionally refuses an existing session for this scope.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import secrets
import subprocess
import time
import traceback
import xml.etree.ElementTree as ET

from android_remote_smoke import Smoke, PACKAGE, COMPONENT

ROOT = Path(__file__).resolve().parents[2]
BASE = "http://10.0.2.2:8770/api/v1/"


class AuthSmoke(Smoke):
    def __init__(self, args):
        super().__init__(args)
        self.out = ROOT / "mobile/artifacts/m3-auth-ui"
        self.backup = ROOT / "mobile/build/m3-auth-ui/device-backup"
        for folder in (self.out / "screenshots", self.backup):
            folder.mkdir(parents=True, exist_ok=True)
        self.secret = "M3auth_" + secrets.token_hex(12)
        self.email = "m3-" + secrets.token_hex(5) + "@example.org"
        self.second_email = "m3-" + secrets.token_hex(5) + "@example.org"
        self.session_path = "no_backup/auth-sessions/" + hashlib.sha256(BASE.encode()).hexdigest() + ".bin"

    def adb(self, *command, **kwargs):
        try:
            return super().adb(*command, **kwargs)
        except RuntimeError as exc:
            # Never include input arguments (the `input text` tail is a password).
            detail = str(exc).replace(self.secret, "[REDACTED]")[:180]
            raise RuntimeError("ADB " + " ".join(command[:2]) + " failed: " + detail) from None

    def capture(self, name):
        # UI trees may contain synthetic emails, but never plaintext passwords.
        tree = ET.tostring(self.tree(), encoding="utf-8")
        assert self.secret.encode() not in tree, "Password appeared in a UI capture"
        (self.out / (name + "-ui.xml")).write_bytes(tree)
        (self.out / "screenshots" / (name + ".png")).write_bytes(self.adb("exec-out", "screencap", "-p", binary=True))

    def tree(self):
        # Use shell-owned temporary storage and remove the previous dump before
        # every observation, so a failed dump can never reuse an earlier screen.
        target = "/data/local/tmp/hackatlas-m3-auth-ui.xml"
        for _ in range(5):
            self.adb("shell", "rm", "-f", target)
            result = self.adb("shell", "uiautomator", "dump", target)
            if "dumped to" in result:
                tree = ET.fromstring(self.adb("shell", "cat", target))
                if any(n.get("package") == PACKAGE for n in tree.iter("node")):
                    return tree
            time.sleep(.3)
        raise AssertionError("No live app hierarchy from fresh dump")

    def field(self, label, value):
        for _ in range(6):
            tree = self.tree()
            field = next((n for n in tree.iter("node") if n.get("class") == "android.widget.EditText"
                          and any(c.get("text") == label for c in n.iter("node"))), None)
            if field is not None:
                x1, y1, x2, y2 = self.bounds(field)
                self.adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
                # Select existing text without echoing the input into logs.
                self.adb("shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A")
                self.adb("shell", "input", "keyevent", "KEYCODE_DEL")
                self.adb("shell", "input", "text", value.replace(" ", "%s"))
                return
            self.swipe()
        raise AssertionError("Editable field not reachable: " + label)

    def fill(self, email, name=None, password=None):
        if name is not None:
            self.field("Имя в профиле", name)
        self.field("Email", email)
        self.field("Пароль", password if password is not None else self.secret)
        self.back()  # IME only

    def press(self, text):
        self.reveal(text)
        self.tap(text)

    def logout(self):
        self.press("Выйти из аккаунта")
        self.require("Выйти?")
        # The modal contains the confirmation button with this label.
        self.tap("Выйти из аккаунта")

    def sql(self, statement):
        r = subprocess.run([self.args.docker, "exec", "-i", "hackatlas-m3-auth-ui-db",
                            "psql", "-v", "ON_ERROR_STOP=1", "-U", "hackatlas_m3", "-d", "hackatlas_m3", "-tA"],
                           input=statement.encode(), capture_output=True, timeout=15)
        if r.returncode:
            raise AssertionError("Disposable DB inspection failed")
        return r.stdout.decode().strip()

    def run(self):
        assert not self.exists(self.session_path), "Existing session for smoke source: choose a fresh isolated scope"
        self.prepare()
        self.adb("shell", "wm", "size", "1080x2400")
        self.adb("shell", "wm", "density", "440")  # 393dp
        self.launch()
        events = self.get("hackathons", sort="DEADLINE_ASC", size=20)["items"]
        assert events and all(e["isDemo"] for e in events)
        event = events[0]
        self.require(event["title"])
        self.tap(event["title"])
        self.tap("Сохранить хакатон", "content-desc")
        snapshot = self.entries(1)[0]
        saved_bytes = self.read(self.stored)
        self.back(); self.tap("Профиль")
        self.require("Гостевой режим")
        self.capture("guest-profile")
        self.tap("Войти")
        self.capture("login")
        self.tap("Нет аккаунта? Создать")
        self.capture("register")
        self.fill(self.email, "M3 Android")
        self.press("Создать аккаунт")
        self.require("M3 Android")
        self.require(self.email)
        self.require("Данные аккаунта")
        self.capture("profile")
        assert self.read(self.stored) == saved_bytes
        assert self.exists(self.session_path)
        cipher = self.read(self.session_path)
        assert self.secret.encode() not in cipher and self.email.encode() not in cipher
        self.passed("live B2 registration, actual profile, Keystore ciphertext and unchanged local guest snapshot")

        self.launch("profile")
        self.require("M3 Android")
        self.capture("profile-restored")
        assert self.read(self.stored) == saved_bytes
        self.passed("process restart restores secure session through real GET /me")

        self.logout()
        self.reveal("Вы вышли. Сессия отозвана на сервере.", up=False)
        self.require("Вы вышли. Сессия отозвана на сервере.")
        assert not self.exists(self.session_path)
        self.press("Войти")
        self.fill(self.email, password="wrong-password")
        self.press("Войти")
        self.reveal("Не удалось войти. Проверьте email и пароль.", up=False)
        self.require("Не удалось войти. Проверьте email и пароль.")
        self.capture("login-error")
        self.fill(self.email)
        self.press("Войти"); self.require("M3 Android")
        assert self.read(self.stored) == saved_bytes
        self.passed("confirmed logout, generic wrong-password error and subsequent successful login")

        # Revoke only this disposable user's sessions, then trigger a host foreground event.
        self.sql("UPDATE hackatlas.auth_session SET revoked_at=now() WHERE user_id IN "
                 "(SELECT id FROM hackatlas.user_account WHERE email='" + self.email + "');")
        self.adb("shell", "input", "keyevent", "KEYCODE_HOME")
        self.adb("shell", "am", "start", "-W", "-n", COMPONENT)
        self.reveal("Сессия завершена. Войдите снова.", up=False)
        self.require("Сессия завершена. Войдите снова.")
        assert not self.exists(self.session_path)
        self.capture("session-expired")
        self.passed("real protected 401 after server revocation on foreground; previous profile hidden")

        self.press("Создать аккаунт")
        self.fill(self.second_email, "M3 Second")
        self.press("Создать аккаунт"); self.require("M3 Second")
        assert self.find(self.tree(), "M3 Android") is None
        assert self.read(self.stored) == saved_bytes
        self.capture("profile-second")
        self.online(False)
        self.logout()
        self.reveal("На устройстве выполнен выход. Отзыв сессии на сервере не подтверждён.", up=False)
        self.require("На устройстве выполнен выход. Отзыв сессии на сервере не подтверждён.")
        assert not self.exists(self.session_path)
        self.capture("logout-offline")
        assert self.read(self.stored) == saved_bytes
        self.launch("saved")
        self.require(event["title"]); self.capture("saved-offline")
        self.passed("second account isolated; offline logout honest; guest snapshot survives and opens after cold restart")
        self.online(True)

        self.launch("login")
        self.field("Email", self.email); self.field("Пароль", self.secret)
        self.adb("shell", "input", "keyevent", "KEYCODE_HOME")
        self.adb("shell", "am", "start", "-W", "-n", COMPONENT)
        self.require("Вход")
        fields = [n for n in self.tree().iter("node") if n.get("class") == "android.widget.EditText"]
        assert fields and all(not n.get("text") for n in fields), "Background did not clear form values"
        self.passed("background clears email/password form memory without ending process owner")

        for density, font, suffix in ((480, "1.0", "360"), (480, "1.3", "360-font130"), (400, "1.0", "432")):
            self.adb("shell", "wm", "density", str(density))
            self.adb("shell", "settings", "put", "system", "font_scale", font)
            self.launch("register"); self.require("Регистрация")
            self.capture("register-" + suffix)
            self.field("Пароль", "short")
            self.back(); self.press("Создать аккаунт")
            self.require("Нужно не менее 12 символов.")
            self.capture("register-errors-" + suffix)
            self.launch("login"); self.capture("login-" + suffix)
        self.passed("real normal/narrow/large-text/wide forms, keyboard reachability and local validation")
        assert self.entries(1)[0] == snapshot


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--docker", required=True)
    parser.add_argument("--serial", default="emulator-5554")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only an emulator is supported")
    args.device_api_base_url = BASE
    args.api_base_url = "http://127.0.0.1:8770/api/v1"
    smoke = AuthSmoke(args)
    failure = None
    try:
        smoke.run()
    except BaseException as exc:
        # Do not dump hierarchy/input or raw HTTP, which could contain credentials.
        failure = type(exc).__name__ + ": " + str(exc).replace(smoke.secret, "[REDACTED]")
        print("FAIL", failure, flush=True)
        print("TRACE", [(Path(f.filename).name, f.name, f.lineno) for f in traceback.extract_tb(exc.__traceback__)], flush=True)
    finally:
        restore_errors = smoke.restore()
        # This scope was asserted empty before testing; delete only our generated ciphertext.
        if smoke.settings:
            smoke.adb("shell", "run-as", PACKAGE, "rm", "-f", smoke.session_path)
        report = {"source": "real local B2 private-dev, disposable PostgreSQL, DEMO events",
                  "device": "Pixel 8 Android 17 API 37", "checks": smoke.results,
                  "failure": failure, "restoreErrors": restore_errors, "fullAcceptance": failure is None and not restore_errors,
                  "ios": "not built or run; Mac/Xcode required"}
        (smoke.out / "android-smoke.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return 1 if failure or restore_errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
