"""M2 black-box checks on an emulator, preserving the debug app's original saved file.

Run after installing the debug APK:
python mobile/scripts/android_m2_smoke.py --adb /path/to/adb --serial emulator-5554
Only the dedicated debug package is touched; no production app or backend is used.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial", default="emulator-5554")
parser.add_argument("--capture-variants", action="store_true")
args = parser.parse_args()
assert args.serial.startswith("emulator-"), "These fault-injection checks are emulator-only"
package = "dev.hackatlas.mobile.debug"
component = f"{package}/dev.hackatlas.mobile.MainActivity"
stored_file = "no_backup/guest-saved/demo-v1.json"
out = Path(__file__).resolve().parents[1] / "artifacts" / "m2"
(out / "screenshots").mkdir(parents=True, exist_ok=True)
results = []


def run(*command, binary=False, payload=None):
    result = subprocess.run([args.adb, "-s", args.serial, *command], input=payload,
                            capture_output=True, timeout=40)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace"))
    return result.stdout if binary else result.stdout.decode("utf-8")


def tree():
    for _ in range(8):
        dumped = run("shell", "uiautomator", "dump", "/sdcard/hackatlas-m2.xml")
        if "dumped to" in dumped:
            value = ET.fromstring(run("shell", "cat", "/sdcard/hackatlas-m2.xml"))
            if any(node.get("package") == package for node in value.iter("node")):
                return value
        time.sleep(.5)
    raise AssertionError("No app hierarchy")


def require(text):
    nodes = list(tree().iter("node"))
    assert any(n.get("text") == text for n in nodes), f"Missing {text}: {[n.get('text') for n in nodes]}"


def reveal(text):
    """Scroll real content until a row below the fold is visible."""
    for _ in range(5):
        hierarchy = tree()
        if any(n.get("text") == text for n in hierarchy.iter("node")):
            return
        root = next(hierarchy.iter("node"))
        _, _, width, height = map(int, re.findall(r"\d+", root.get("bounds")))
        run("shell", "input", "swipe", str(width // 2), str(int(height * .77)),
            str(width // 2), str(int(height * .42)), "450")
        time.sleep(.4)
    require(text)


def tap(text, attribute="text", prefix=False):
    nodes = list(tree().iter("node"))
    node = next((n for n in nodes if (n.get(attribute, "").startswith(text) if prefix else n.get(attribute) == text)), None)
    assert node is not None, f"No {attribute}: {text}"
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    run("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(.3)


def launch(route="home", saved_only=False):
    run("shell", "am", "force-stop", package)
    run("shell", "am", "start", "-W", "-n", component, "--es", "screen", route,
        "--ez", "savedOnly", str(saved_only).lower())
    tree()


def read_store():
    return run("exec-out", "run-as", package, "cat", stored_file, binary=True)


def write_store(data):
    run("shell", "am", "force-stop", package)
    run("shell", "-T", "run-as", package, "tee", stored_file, binary=True, payload=data)


def capture(name):
    hierarchy = tree()
    time.sleep(.5)
    (out / "screenshots" / f"{name}.png").write_bytes(run("exec-out", "screencap", "-p", binary=True))
    (out / f"{name}-ui.xml").write_bytes(ET.tostring(hierarchy, encoding="utf-8"))


def passed(name):
    results.append({"name": name, "status": "PASS"})
    print("PASS", name, flush=True)


def has_desc(value):
    return any(n.get("content-desc") == value for n in tree().iter("node"))


def select_month(year, month):
    names = "Январь Февраль Март Апрель Май Июнь Июль Август Сентябрь Октябрь Ноябрь Декабрь".split()
    for _ in range(36):
        label = next((n.get("text") for n in tree().iter("node")
                      if re.fullmatch(r"(" + "|".join(names) + r") \d{4}", n.get("text", ""))), None)
        assert label, "Month heading missing"
        name, y = label.split()
        current = int(y) * 12 + names.index(name) + 1
        target = year * 12 + month
        if current == target:
            return
        tap("Следующий месяц" if current < target else "Предыдущий месяц", "content-desc")
    raise AssertionError("Target month too far")


original = None
airplane_before = run("shell", "settings", "get", "global", "airplane_mode_on").strip()
size_before = run("shell", "wm", "size")
density_before = run("shell", "wm", "density")
font_before = run("shell", "settings", "get", "system", "font_scale").strip()


def restore_display():
    for command, previous in (("size", size_before), ("density", density_before)):
        override = re.search(r"Override \w+: (\S+)", previous)
        run("shell", "wm", command, override.group(1) if override else "reset")
    if font_before == "null":
        run("shell", "settings", "delete", "system", "font_scale")
    else:
        run("shell", "settings", "put", "system", "font_scale", font_before)


try:
    launch()
    original = read_store()
    require("Хакатоны")
    tap("AI Sprint 2026")
    if has_desc("Убрать из сохранённого"):
        tap("Убрать из сохранённого", "content-desc")
    launch()
    tap("AI Sprint 2026")
    assert has_desc("Сохранить хакатон"), "Removal did not survive process restart"
    tap("Сохранить хакатон", "content-desc")
    assert has_desc("Убрать из сохранённого")
    saved_before = json.loads(read_store())
    ai = next(e for e in saved_before["entries"] if e["detail"]["hackathon"]["title"] == "AI Sprint 2026")
    passed("bookmark add and removal survive force-stop/relaunch")

    run("shell", "cmd", "connectivity", "airplane-mode", "enable")
    launch("saved", saved_only=True)
    require("На этом устройстве · доступны без сети")
    require("AI Sprint 2026")
    capture("saved-offline")
    tap("AI Sprint 2026")
    require("Сохранённая копия. Сведения могли измениться и не обновляются автоматически.")
    require("AI Sprint 2026")
    capture("detail-offline")
    restored_ai = next(e for e in json.loads(read_store())["entries"]
                       if e["detail"]["hackathon"]["id"] == ai["detail"]["hackathon"]["id"])
    assert restored_ai == ai, "Offline opening changed original snapshot or verification date"
    run("shell", "input", "keyevent", "KEYCODE_BACK")
    tap("Главная")
    require("Каталог недоступен")
    passed("stored details open in airplane mode with catalog loading disabled")

    tap("Календарь")
    require("DEMO")
    select_month(2026, 11)
    tap("12 ноя 2026.", "content-desc", prefix=True)
    require("AI Sprint 2026")
    require("Проведение")
    capture("calendar-event")
    tap("AI Sprint 2026")
    require("Сохранённая копия. Сведения могли измениться и не обновляются автоматически.")
    run("shell", "input", "keyevent", "KEYCODE_BACK")
    require("Ноябрь 2026")
    require("AI Sprint 2026")
    tap("1 ноя 2026.", "content-desc", prefix=True)
    require("AI Sprint 2026")
    reveal("18:00 · Asia/Almaty")
    require("18:00 · Asia/Almaty")
    capture("calendar-deadline")
    passed("event range and registration deadline are distinct; calendar selection survives detail/back")

    # A corrupt/future file must remain intact, never silently replaced with demo seed.
    broken = b'{"version":99,"scope":"demo-guest","entries":[]}'
    write_store(broken)
    launch("saved", saved_only=True)
    require("Сохранённое недоступно")
    tap("Повторить")
    require("Сохранённое недоступно")
    assert read_store() == broken
    passed("unknown storage version is reported without overwriting data")
    write_store(json.dumps(saved_before, ensure_ascii=False).encode("utf-8"))

    launch("saved", saved_only=True)
    for _ in range(len(saved_before["entries"])):
        tap("Убрать из сохранённого", "content-desc")
    require("Здесь будут ваши хакатоны")
    launch("calendar")
    require("Сохраните первое событие")
    assert json.loads(read_store())["entries"] == [], "Demo seed returned after deleting last bookmark"
    capture("calendar-empty")
    passed("empty saved and empty calendar survive restart without reseeding")

    if args.capture_variants:
        write_store(json.dumps(saved_before, ensure_ascii=False).encode("utf-8"))
        launch("calendar", saved_only=True)
        select_month(2026, 11)
        tap("12 ноя 2026.", "content-desc", prefix=True)
        run("shell", "wm", "size", "1080x2400")
        run("shell", "wm", "density", "480")
        run("shell", "settings", "put", "system", "font_scale", "1.3")
        require("Ноябрь 2026")
        capture("calendar-360-large")
        reveal("AI Sprint 2026")
        tap("AI Sprint 2026")
        require("Сохранённая копия. Сведения могли измениться и не обновляются автоматически.")
        capture("detail-360-large")
        run("shell", "input", "keyevent", "KEYCODE_BACK")
        # The calendar also restores scroll: after revealing the card, its month
        # heading is above the viewport. The visible selected date proves restore.
        require("12 ноя 2026")
        tap("Сохранённое")
        require("На этом устройстве · доступны без сети")
        capture("saved-360-large")
        assert json.loads(read_store()) == saved_before
        passed("360 dp and 130% text: navigation and snapshots survive configuration changes")
finally:
    if original is not None:
        write_store(original)
    if airplane_before != "1":
        run("shell", "cmd", "connectivity", "airplane-mode", "disable")
    if args.capture_variants:
        restore_display()
    (out / "android-smoke.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    apk = out.parents[1] / "androidApp" / "build" / "outputs" / "apk" / "debug" / "androidApp-debug.apk"
    (out / "android-smoke-environment.json").write_text(json.dumps({
        "serial": args.serial,
        "package": package,
        "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "display_size": size_before.strip(),
        "display_density": density_before.strip(),
        "font_scale": font_before,
        "capture_variants": args.capture_variants,
        "passed": len(results),
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    launch("saved")
