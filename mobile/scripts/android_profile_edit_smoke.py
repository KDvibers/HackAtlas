"""Real B2 profile-edit acceptance, disposable DB on8772, emulator only.
Synthetic account; credentials stay in memory. No marks/reminders/email lifecycle.
Guest files/network/display restored; test-source session/private cache must be empty.
Never run against a user/production database or concurrently with another UI reader.
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
from android_remote_smoke import PACKAGE, COMPONENT

ROOT=Path(__file__).resolve().parents[2]
BASE="http://10.0.2.2:8772/api/v1/"
auth_smoke.BASE=BASE


class ProfileSmoke(auth_smoke.AuthSmoke):
    def __init__(self,args):
        super().__init__(args)
        self.out=ROOT/"mobile/artifacts/m3-profile-edit"
        self.backup=ROOT/"mobile/build/m3-profile-edit/device-backup"
        for folder in (self.out/"screenshots",self.backup):folder.mkdir(parents=True,exist_ok=True)
        self.cache_path="no_backup/account-saved/"+hashlib.sha256(BASE.encode()).hexdigest()+".json"

    def adb(self,*command,**kwargs):
        # The inherited field helper already selects/deletes the text. Android
        # `input text` rejects an empty argument; clearing needs no insertion.
        if command[:3]==("shell","input","text") and len(command)==4 and command[3]=="":
            return ""
        return super().adb(*command,**kwargs)

    def field(self,label,value):
        def editable():
            return next((node for node in self.tree().iter("node") if node.get("class")=="android.widget.EditText"
                         and any(child.get("text")==label for child in node.iter("node"))),None)
        node=None
        for _ in range(7):
            node=editable()
            if node is not None:break
            self.swipe()
        assert node is not None,"Editable field not reachable: "+label
        x1,y1,x2,y2=self.bounds(node)
        self.adb("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2))
        # Ctrl-A is inconsistently handled by emulator IMEs. Every field is
        # single-line: move to end, delete its complete observed length, verify.
        self.adb("shell","input","keyevent","KEYCODE_MOVE_END")
        self.adb("shell","input","keyevent","--delay","10",*(["KEYCODE_DEL"]*(len(node.get("text",""))+4)))
        cleared=editable()
        assert cleared is not None and not cleared.get("text"),"Field did not clear: "+label
        if value:self.adb("shell","input","text",value.replace(" ","%s"))
        if label!="Пароль":
            entered=editable()
            assert entered is not None and entered.get("text","")==value,"Field input mismatch: "+label

    def sql(self,statement):
        r=subprocess.run([self.args.docker,"exec","-i","hackatlas-m3-profile-edit-db","psql","-v","ON_ERROR_STOP=1",
                          "-U","hackatlas_profile","-d","hackatlas_profile","-tA"],input=statement.encode(),capture_output=True,timeout=15)
        if r.returncode:raise AssertionError("Disposable profile DB inspection failed")
        return r.stdout.decode().strip()

    def server_profile(self):
        assert re.fullmatch(r"m3-[a-f0-9]+@example\.org",self.email)
        return json.loads(self.sql("SELECT json_build_object('name',display_name,'city',city,'country',country_code,'role',role_label,'avatar',avatar_key,"
             "'interests',(SELECT json_agg(tag_id ORDER BY tag_id) FROM hackatlas.user_interest WHERE user_id=u.id)) "
             "FROM hackatlas.user_account u WHERE email='"+self.email+"';"))

    def edit(self):
        self.reveal("Редактировать профиль",up=False)
        self.press("Редактировать профиль");self.require("Мой профиль")

    def fill_profile(self,name=None,city=None,country=None,role=None):
        for label,value in (("Имя в профиле",name),("Город",city),("Код страны",country),("Подпись профиля",role)):
            if value is not None:
                self.field(label,value)
                self.back()  # IME

    def run(self):
        assert not self.exists(self.session_path) and not self.exists(self.cache_path),"Existing source session/cache"
        self.prepare()
        self.adb("shell","wm","size","1080x2400");self.adb("shell","wm","density","440")
        self.launch()
        event=self.get("hackathons",sort="DEADLINE_ASC",size=20)["items"][0]
        self.require(event["title"]);self.tap(event["title"]);self.tap("Сохранить хакатон","content-desc")
        self.entries(1);guest_bytes=self.read(self.stored)
        self.back();self.tap("Профиль");self.tap("Создать аккаунт")
        self.fill(self.email,"M3 Profile");self.press("Создать аккаунт");self.require("M3 Profile")
        self.edit();self.capture("editor-initial")
        save=self.require("Сохранить изменения") if self.find(self.tree(),"Сохранить изменения") else None
        # Initially unchanged; server truth is also asserted after cancel below.
        self.fill_profile("Cancelled")
        self.back();self.require("Отменить изменения?");self.capture("discard-confirmation")
        self.tap("Продолжить редактирование");self.require("Мой профиль")
        self.back();self.tap("Отменить изменения");self.require("M3 Profile")
        assert self.server_profile()["name"]=="M3 Profile"
        self.passed("dirty cancel confirmation, continue-edit and discard without PATCH")

        self.edit();self.fill_profile("M3 Edited","Almaty","kz","Kotlin developer")
        self.press("Инициалы")
        metadata=self.get("metadata/filters")
        tag=next((t for t in metadata["interests"] if t["id"]=="kotlin"),metadata["interests"][0])
        self.press(tag["label"]);self.capture("editor-choices")
        self.press("Сохранить изменения");self.require("Профиль");self.require("M3 Edited")
        expected=self.server_profile()
        assert expected==dict(name="M3 Edited",city="Almaty",country="KZ",role="Kotlin developer",avatar="INITIALS",interests=[tag["id"]])
        assert self.read(self.stored)==guest_bytes
        self.capture("profile-edited")
        self.launch("profile");self.require("M3 Edited");self.require("Kotlin developer · Almaty · KZ")
        self.capture("profile-restored")
        self.passed("real PATCH updates six fields, returns actual profile, survives restart and preserves guest bytes")

        self.edit();self.fill_profile(city="",country="",role="")
        self.press("Силуэт");self.press(tag["label"])
        self.press("Сохранить изменения");self.require("Профиль");self.require("M3 Edited")
        cleared=self.server_profile()
        assert cleared["city"] is None and cleared["country"] is None and cleared["role"] is None
        assert cleared["interests"] is None and cleared["avatar"]=="HOODED"
        self.capture("profile-cleared")
        self.passed("nullable fields explicitly clear, interests empty replacement and avatar change confirmed by B2")

        self.edit();self.fill_profile("M3 Retry")
        self.online(False);self.press("Сохранить изменения")
        self.reveal("Не удалось подтвердить сохранение. Сервер мог принять изменения. Проверьте профиль или повторите сохранение.",up=False)
        self.require("Не удалось подтвердить сохранение. Сервер мог принять изменения. Проверьте профиль или повторите сохранение.")
        assert self.server_profile()["name"]=="M3 Edited"
        self.capture("save-error-offline")
        self.online(True);self.press("Сохранить изменения");self.require("Профиль");self.require("M3 Retry")
        assert self.server_profile()["name"]=="M3 Retry"
        self.passed("offline save keeps draft and shows honest error; explicit online retry succeeds")

        self.lifecycle_and_variants("M3 Retry",guest_bytes)

    def run_tail(self):
        assert not self.exists(self.session_path) and not self.exists(self.cache_path),"Existing source session/cache"
        self.prepare()
        self.adb("shell","wm","size","1080x2400");self.adb("shell","wm","density","440")
        self.launch("register");self.fill(self.email,"M3 Tail");self.press("Создать аккаунт");self.require("M3 Tail")
        self.lifecycle_and_variants("M3 Tail",self.read(self.stored))

    def lifecycle_and_variants(self,server_name,guest_bytes):
        self.edit();self.fill_profile("M3 Unsaved")
        self.adb("shell","input","keyevent","KEYCODE_HOME")
        self.adb("shell","am","start","-W","-n",COMPONENT)
        self.require("Мой профиль");self.require("M3 Unsaved")
        self.sql("UPDATE hackatlas.auth_session SET revoked_at=now() WHERE user_id IN (SELECT id FROM hackatlas.user_account WHERE email='"+self.email+"');")
        self.adb("shell","input","keyevent","KEYCODE_HOME")
        self.adb("shell","am","start","-W","-n",COMPONENT)
        self.require("Сессия завершена. Войдите снова.")
        assert self.find(self.tree(),"M3 Unsaved") is None
        assert not self.exists(self.cache_path) and not self.exists(self.session_path)
        assert self.read(self.stored)==guest_bytes
        self.capture("revoked-draft-cleared")
        self.passed("foreground retains same-owner draft; revoked401 discards it and clears private session/cache")

        self.press("Войти");self.fill(self.email);self.press("Войти");self.require(server_name)
        self.adb("shell","wm","density","480");self.adb("shell","settings","put","system","font_scale","1.3")
        self.launch("profile");self.require(server_name);self.edit();self.capture("editor-360-font130")
        self.fill_profile(name="")
        self.press("Сохранить изменения")
        self.reveal("Имя должно содержать от 1 до 60 символов.",up=False)
        self.require("Имя должно содержать от 1 до 60 символов.")
        self.capture("validation-360-font130")
        assert self.server_profile()["name"]==server_name
        self.passed("360dp/font130 form, keyboard, controls and client validation remain reachable")


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb",required=True);parser.add_argument("--docker",required=True);parser.add_argument("--serial",default="emulator-5554")
    parser.add_argument("--tail-only",action="store_true",help="Only lifecycle/font acceptance; use a new disposable account")
    args=parser.parse_args()
    if not re.fullmatch(r"emulator-\d+",args.serial):parser.error("Emulator only")
    args.device_api_base_url=BASE;args.api_base_url="http://127.0.0.1:8772/api/v1"
    smoke=ProfileSmoke(args);failure=None
    try:smoke.run_tail() if args.tail_only else smoke.run()
    except BaseException as exc:
        failure=type(exc).__name__+": "+str(exc).replace(smoke.secret,"[REDACTED]")
        print("FAIL",failure,flush=True)
        print("TRACE",[(Path(f.filename).name,f.name,f.lineno) for f in traceback.extract_tb(exc.__traceback__)],flush=True)
    finally:
        errors=smoke.restore()
        if smoke.settings:smoke.adb("shell","run-as",PACKAGE,"rm","-f",smoke.session_path,smoke.cache_path)
        report=dict(source="Real local B2, disposable DB, synthetic account and DEMO catalogue",checks=smoke.results,
                    failure=failure,restoreErrors=errors,fullAcceptance=failure is None and not errors,ios="Source only; Mac/Xcode required")
        report["scope"]="lifecycle and font variants only" if args.tail_only else "full profile smoke"
        report["apkSha256"]=hashlib.sha256((ROOT/"mobile/build/m3-profile-edit/HackAtlas-profile-edit-debug.apk").read_bytes()).hexdigest()
        name="android-tail-smoke.json" if args.tail_only else "android-smoke.json"
        (smoke.out/name).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    return 1 if failure or errors else 0


if __name__=="__main__":raise SystemExit(main())
