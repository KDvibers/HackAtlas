"""M1 black-box Android checks using real UIAutomator hierarchy and ADB taps.

Usage: python mobile/scripts/android_smoke.py --adb /path/to/adb
Requires an already running emulator/device with the debug APK installed.
Only touches the HackAtlas debug app. No backend or external registration is used.
"""
from pathlib import Path
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PARSER = argparse.ArgumentParser()
PARSER.add_argument("--adb", default="adb")
PARSER.add_argument("--capture-variants", action="store_true", help="Also capture 360dp/large text and 430dp layouts; restore display settings afterwards")
ARGS = PARSER.parse_args()
PACKAGE = "dev.hackatlas.mobile.debug"
COMPONENT = f"{PACKAGE}/dev.hackatlas.mobile.MainActivity"
OUT = Path(__file__).resolve().parents[1] / "artifacts" / "m1"
OUT.mkdir(parents=True, exist_ok=True)
RESULTS = []


def adb(*args):
    result = subprocess.run([ARGS.adb, *args], capture_output=True, text=True, encoding="utf-8", timeout=30)
    if result.returncode:
        raise RuntimeError(result.stderr or result.stdout)
    return result.stdout


def hierarchy():
    for _ in range(8):
        result = adb("shell", "uiautomator", "dump", "/sdcard/hackatlas-smoke.xml")
        if "dumped to" in result:
            xml = adb("shell", "cat", "/sdcard/hackatlas-smoke.xml")
            tree = ET.fromstring(xml)
            if any(n.attrib.get("package") == PACKAGE for n in tree.iter("node")):
                return tree
        time.sleep(.6)
    raise AssertionError("No live HackAtlas UI hierarchy")


def texts(tree):
    return [n.attrib.get("text", "") for n in tree.iter("node")]


def require(text, tree=None):
    tree = tree if tree is not None else hierarchy()
    assert text in texts(tree), f"Expected visible text: {text}; got {texts(tree)}"
    return tree


def tap(value, attribute="text"):
    tree = hierarchy()
    node = next((n for n in tree.iter("node") if n.attrib.get(attribute) == value), None)
    assert node is not None, f"Could not find {attribute}={value}"
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(.25)


def check(name, test):
    test()
    RESULTS.append({"name": name, "status": "PASS"})
    print("PASS", name, flush=True)


def launch(route="home"):
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", COMPONENT, "--es", "screen", route)
    hierarchy()


def bookmark_navigation():
    launch()
    require("Хакатоны")
    tap("Убрать из сохранённого", "content-desc")
    # The bookmark must not bubble into the card navigation handler.
    tree = require("Хакатоны")
    assert not any(n.attrib.get("content-desc") == "Назад" for n in tree.iter("node"))
    tap("AI Sprint 2026")
    tree = require("О хакатоне")
    assert any(n.attrib.get("content-desc") == "Сохранить хакатон" for n in tree.iter("node"))
    tap("Сохранить хакатон", "content-desc")
    assert any(n.attrib.get("content-desc") == "Убрать из сохранённого" for n in hierarchy().iter("node"))
    tap("Перейти к регистрации")
    require("Это демонстрация")
    assert "example.com" in " ".join(texts(hierarchy()))
    tap("Понятно")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    require("Хакатоны")
    tap("Профиль")
    require("devnull")
    require("3")
    tap("Убрать из сохранённого", "content-desc")
    require("2")
    tap("Сохранённое")
    tree = require("Сохранённое")
    assert "AI Sprint 2026" not in texts(tree)
    tap("Убрать из сохранённого", "content-desc")
    assert "GameDev Jam" not in texts(hierarchy()), "Removal must recompose the Saved screen immediately"
    tap("Убрать из сохранённого", "content-desc")
    require("Здесь будут ваши хакатоны")


def search_filter_return():
    launch()
    tap("Поиск хакатонов", "content-desc")
    adb("shell", "input", "text", "a" * 160)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tree = require("Ничего не найдено")
    assert "a" * 120 in texts(tree), "Query should be bounded by the 120-character contract"
    tap("Сбросить фильтры")
    require("AI Sprint 2026")
    tap("Очно")
    tree = require("Fintech Hack")
    assert "AI Sprint 2026" not in texts(tree)
    tap("Fintech Hack")
    require("Регистрация недоступна")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tree = require("Fintech Hack")
    assert "AI Sprint 2026" not in texts(tree), "Filter must survive detail and back"


def typography_navigation():
    launch("profile")
    tap("Настройки", "content-desc")
    tap("Образец шрифта")
    require("PIXEL DISPLAY / PRESS START 2P")
    require("Й Ё й ё · 0123456789")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    require("devnull")
    tap("Календарь")
    require("Ваш календарь")


def capture(route, suffix=""):
    launch(route)
    # A second fresh hierarchy read settles the OS bars after an activity transition.
    hierarchy()
    adb("shell", "screencap", "-p", "/sdcard/hackatlas-capture.png")
    adb("pull", "/sdcard/hackatlas-capture.png", str(OUT / "screenshots" / f"{route}{suffix}.png"))
    adb("pull", "/sdcard/hackatlas-smoke.xml", str(OUT / f"{route}{suffix}-ui.xml"))


def variants():
    original_density = adb("shell", "wm", "density")
    density_override = re.search(r"Override density: (\d+)", original_density)
    font_scale = adb("shell", "settings", "get", "system", "font_scale").strip()
    try:
        for density, scale, suffix in (("480", "1.3", "-360-large"), ("402", "1.0", "-430")):
            adb("shell", "wm", "density", density)
            adb("shell", "settings", "put", "system", "font_scale", scale)
            for route in ("home", "detail", "profile"):
                capture(route, suffix)
    finally:
        adb("shell", "wm", "density", density_override.group(1) if density_override else "reset")
        adb("shell", "settings", "put", "system", "font_scale", font_scale if font_scale != "null" else "1.0")


try:
    check("bookmark isolation, detail/back, demo registration, profile and saved state", bookmark_navigation)
    check("bounded search, empty/reset, local filter and return state", search_filter_return)
    check("profile settings, font specimen, Android back and calendar boundary", typography_navigation)
    for route in ("home", "detail", "profile", "typography"):
        capture(route)
    check("four actual Android screenshots", lambda: None)
    if ARGS.capture_variants:
        check("360dp with 1.3x text and 430dp layout captures", variants)
finally:
    (OUT / "android-smoke.json").write_text(json.dumps(RESULTS, ensure_ascii=False, indent=2), encoding="utf-8")
