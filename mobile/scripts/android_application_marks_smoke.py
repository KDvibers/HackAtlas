"""M3 application marks: real B2 on8774, disposable labelled DB, task AVD only.

Synthetic credentials stay in memory, responses are schema-checked but not logged.
No reminders/email lifecycle. Guest bytes/display/network restored; private files
must initially be absent and are cleaned only for this task's source afterwards.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import traceback
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import yaml
from jsonschema import Draft202012Validator, FormatChecker, RefResolver
import android_auth_smoke as auth_smoke
import android_profile_edit_smoke as profile_smoke
from android_remote_smoke import PACKAGE

ROOT=Path(__file__).resolve().parents[2]
BASE="http://10.0.2.2:8774/api/v1/"
HOST="http://127.0.0.1:8774/api/v1/"
CONTAINER="hackatlas-m3-applications-db"
DB="hackatlas_applications"
AVD="HackAtlas_Applications_API36"
EVENT="39287368-5259-5235-a1c4-25b4e1e8421e"
profile_smoke.BASE=BASE
auth_smoke.BASE=BASE


class ApplicationSmoke(profile_smoke.ProfileSmoke):
    def __init__(self,args):
        super().__init__(args)
        self.out=ROOT/"mobile/artifacts/m3-applications"
        self.backup=ROOT/"mobile/build/m3-applications/device-backup"
        for p in (self.out/"screenshots",self.backup):p.mkdir(parents=True,exist_ok=True)
        self.application_path="no_backup/account-applications/"+hashlib.sha256(BASE.encode()).hexdigest()+".json"
        self.spec=yaml.safe_load((ROOT/"api/openapi.yaml").read_text(encoding="utf-8"))
        self.http_checks=[];self.users={};self.host_tokens={}

    def sql(self,statement):
        r=subprocess.run([self.args.docker,"exec","-i",CONTAINER,"psql","-v","ON_ERROR_STOP=1",
                          "-U",DB,"-d",DB,"-tA"],input=statement.encode(),capture_output=True,timeout=15)
        if r.returncode:raise AssertionError("Task DB query failed")
        return r.stdout.decode().strip()

    def request_schema(self,method,path,body=None,token=None,status=200):
        headers={"Accept":"application/json"}
        if body is not None:headers["Content-Type"]="application/json"
        if token is not None:headers["Authorization"]="Bearer "+token
        request=Request(HOST+path,data=json.dumps(body).encode() if body is not None else None,method=method,headers=headers)
        try:
            with urlopen(request,timeout=15) as response:code=response.status;data=response.read()
        except HTTPError as failed:code=failed.code;data=failed.read()
        assert code==status,"Unexpected HTTP status for "+method+" "+path
        route="/"+path
        if route.startswith("/me/applications/"):route="/me/applications/{id}"
        schema=self.spec["paths"][route][method.lower()]["responses"][str(code)].get("content",{}).get("application/json",{}).get("schema")
        value=json.loads(data) if data else None
        if schema:
            try:Draft202012Validator(schema,resolver=RefResolver.from_schema(self.spec),format_checker=FormatChecker()).validate(value)
            except Exception:raise AssertionError("B2 response schema failed for "+method+" "+route) from None
        self.http_checks.append(dict(method=method,path=route,status=code,schemaChecked=schema is not None))
        return value

    def register(self,email,name):
        assert re.fullmatch(r"m3-[a-f0-9]+@example\.org",email)
        value=self.request_schema("POST","auth/register",dict(email=email,password=self.secret,displayName=name),status=201)
        self.users[email]=value["user"]["id"];self.host_tokens[email]=value["accessToken"]

    def marks(self,email):
        assert re.fullmatch(r"m3-[a-f0-9]+@example\.org",email)
        return json.loads(self.sql("SELECT COALESCE(json_agg(hackathon_id ORDER BY hackathon_id),'[]'::json) "
            "FROM hackatlas.application_mark WHERE user_id=(SELECT id FROM hackatlas.user_account WHERE email='"+email+"');"))

    def doc(self,email,ids=None):
        end=time.monotonic()+20
        while time.monotonic()<end:
            if self.exists(self.application_path):
                value=json.loads(self.read(self.application_path))
                if value["userId"]==self.users[email] and (ids is None or sorted(m["hackathonId"] for m in value["marks"])==sorted(ids)):
                    assert value["apiBaseUrl"]==BASE
                    assert self.secret.encode() not in self.read(self.application_path)
                    return value
            time.sleep(.2)
        raise AssertionError("Expected owner's application cache not available")

    def open_event(self):
        self.launch()
        first=self.get("hackathons",sort="DEADLINE_ASC",size=20)["items"][0]["title"]
        self.require(first);self.reveal("AI Sprint 2026");self.tap("AI Sprint 2026");self.require("AI Sprint 2026")

    def show_mark(self,up=True):self.reveal("Моя заявка",up=up)

    def confirmed(self):
        self.show_mark();self.require("Отмечено вами:",prefix=True);self.reveal("Снять отметку");self.require("Снять отметку")

    def button_enabled(self,label,expected):
        end=time.monotonic()+20
        while time.monotonic()<end:
            tree=self.tree();node=self.find(tree,label)
            parents={child:parent for parent in tree.iter() for child in parent}
            # Compose OutlinedButton exports a clickable View rather than a
            # widget.Button; its child text can stay enabled while it is disabled.
            while node is not None and node.get("clickable")!="true":node=parents.get(node)
            if node is not None and (node.get("enabled")=="true")==expected:return
            time.sleep(.2)
        raise AssertionError("Button enabled state differs: "+label)

    def run(self):
        assert all(not self.exists(p) for p in (self.session_path,self.cache_path,self.application_path)),"Existing source session/private cache"
        self.prepare();self.adb("shell","wm","size","1080x2400");self.adb("shell","wm","density","440")
        events=self.get("hackathons",sort="DEADLINE_ASC",size=20)["items"]
        assert len(events)==5 and all(e["isDemo"] for e in events) and any(e["id"]==EVENT for e in events)
        self.register(self.email,"M3 Applications");self.register(self.second_email,"M3 Other")
        self.open_event();self.capture("detail-top")
        self.tap("Сохранить хакатон","content-desc");self.entries(1);guest_bytes=self.read(self.stored)
        self.show_mark();self.press("Войти, чтобы отметить");self.require("Вход");self.capture("guest-sign-in")
        self.fill(self.email);self.press("Войти");self.require("AI Sprint 2026")
        self.show_mark();self.require("Личной отметки пока нет.");self.capture("unmarked")
        self.passed("guest sign-in returns to the selected event; no automatic application mark")

        self.reveal("Открыть официальный сайт");self.tap("Открыть официальный сайт")
        self.require("DEMO: просмотр ссылки");self.capture("external-preview");self.tap("Понятно")
        assert self.marks(self.email)==[]
        self.show_mark(up=False);self.require("Личной отметки пока нет.")
        self.passed("official-site DEMO preview leaves personal application membership unchanged; no organiser submission")

        self.press("Я подал(а) заявку");self.confirmed()
        assert self.marks(self.email)==[EVENT]
        before=self.request_schema("GET","me/applications",token=self.host_tokens[self.email])["items"][0]
        assert before["selfReported"] is True and before["status"]=="APPLIED"
        assert self.doc(self.email,[EVENT])["marks"][0]["markedAt"]==before["markedAt"]
        assert self.request_schema("GET","me",token=self.host_tokens[self.email])["stats"]["appliedCount"]==1
        self.capture("applied");self.passed("explicit mark has real B2 APPLIED/selfReported/markedAt and server profile count")

        repeated=self.request_schema("PUT","me/applications/"+EVENT,token=self.host_tokens[self.email])
        assert repeated["markedAt"]==before["markedAt"]
        self.open_event();self.confirmed();assert self.doc(self.email)["marks"][0]["markedAt"]==before["markedAt"]
        self.passed("host repeated PUT preserves markedAt; Android restores the same mark after process restart")

        self.reveal("Сохранить хакатон","content-desc",up=False);self.tap("Сохранить хакатон","content-desc")
        self.require("Убрать из сохранённого","content-desc");self.tap("Убрать из сохранённого","content-desc")
        self.require("Сохранить хакатон","content-desc");assert self.marks(self.email)==[EVENT]
        self.tap("Сохранить хакатон","content-desc");self.require("Убрать из сохранённого","content-desc")
        self.show_mark();self.confirmed();assert self.read(self.stored)==guest_bytes
        self.passed("account bookmark add/remove is independent; original guest bytes remain unchanged")

        cache_bytes=self.read(self.application_path)
        self.online(False);self.launch("saved");self.require("AI Sprint 2026");self.tap("AI Sprint 2026")
        self.confirmed();self.require("Сохранённая копия отметок. Сведения могли измениться.")
        self.button_enabled("Снять отметку",False);self.capture("offline")
        assert self.read(self.application_path)==cache_bytes and self.marks(self.email)==[EVENT]
        self.passed("cold offline saved detail reads owner cache and disables changes without an offline queue")

        self.online(True);self.open_event();self.confirmed();self.button_enabled("Снять отметку",True)
        self.press("Снять отметку");self.require("Личной отметки пока нет.")
        assert self.marks(self.email)==[] and self.doc(self.email,[])["marks"]==[]
        self.capture("removed");self.passed("DELETE204 removes only the application mark on server and in cache")

        self.press("Я подал(а) заявку");self.confirmed()
        self.launch("profile");self.require("M3 Applications");self.logout();self.require("Войти")
        self.open_event();self.show_mark();self.press("Войти, чтобы отметить")
        self.fill(self.second_email);self.press("Войти");self.require("AI Sprint 2026")
        self.show_mark();self.require("Личной отметки пока нет.")
        assert self.marks(self.email)==[EVENT] and self.marks(self.second_email)==[]
        assert self.doc(self.second_email,[])["marks"]==[]
        assert self.find(self.tree(),"Отмечено вами:",prefix=True) is None
        self.capture("account-b");self.passed("logout/account switch never shows or imports account A's mark for account B")

        self.press("Я подал(а) заявку");self.confirmed()
        self.adb("shell","wm","density","480");self.adb("shell","settings","put","system","font_scale","1.3")
        self.open_event();self.confirmed();self.button_enabled("Снять отметку",True)
        assert float(self.adb("shell","settings","get","system","font_scale").strip())==1.3
        assert re.findall(r"\d+",self.adb("shell","wm","density"))[-1]=="480"
        self.capture("applied-360-font130");self.passed("360dp/font130 self-reported text and mark control remain reachable")

        count=int(self.sql("WITH revoked AS (UPDATE hackatlas.auth_session SET revoked_at=now() WHERE revoked_at IS NULL AND "
            "user_id=(SELECT id FROM hackatlas.user_account WHERE email='"+self.second_email+"') RETURNING id) SELECT count(*) FROM revoked;"))
        assert count>=1
        self.open_event();self.show_mark();self.require("Войти, чтобы отметить")
        assert all(not self.exists(p) for p in (self.session_path,self.cache_path,self.application_path))
        assert self.read(self.stored)==guest_bytes
        assert self.find(self.tree(),"Отмечено вами:",prefix=True) is None
        self.capture("revoked");self.passed("real revoked401 removes credential and both private caches; guest bytes retained")


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb",required=True);parser.add_argument("--docker",required=True)
    parser.add_argument("--serial",default="emulator-5560");parser.add_argument("--apk",required=True)
    args=parser.parse_args()
    if not re.fullmatch(r"emulator-\d+",args.serial):parser.error("Task emulator required")
    avd=subprocess.check_output([args.adb,"-s",args.serial,"emu","avd","name"],timeout=10).decode().splitlines()[0]
    if avd!=AVD:parser.error("Refusing non-task AVD")
    label=subprocess.check_output([args.docker,"inspect","--format",'{{index .Config.Labels "dev.hackatlas.task"}}',CONTAINER],timeout=10).decode().strip()
    if label!="m3-applications":parser.error("Refusing non-task DB")
    args.device_api_base_url=BASE;args.api_base_url=HOST.rstrip("/")
    smoke=ApplicationSmoke(args);failure=None;installed=None;device={}
    try:
        package_path=smoke.adb("shell","pm","path",PACKAGE).strip().removeprefix("package:")
        assert re.fullmatch(r"/data/app/[A-Za-z0-9_./~+=-]+/base\.apk",package_path)
        installed=smoke.adb("shell","sha256sum",package_path).split()[0]
        assert installed==hashlib.sha256(Path(args.apk).read_bytes()).hexdigest()
        device={key:smoke.adb("shell","getprop",key).strip() for key in ("ro.build.version.sdk","ro.build.version.release")}
        smoke.run()
    except BaseException as exc:
        failure=type(exc).__name__+": "+str(exc).replace(smoke.secret,"[REDACTED]")
        print("FAIL",failure,flush=True)
        print("TRACE",[(Path(f.filename).name,f.name,f.lineno) for f in traceback.extract_tb(exc.__traceback__)],flush=True)
        try:smoke.capture("failure")
        except Exception:pass
    finally:
        errors=smoke.restore()
        if smoke.settings:
            try:
                smoke.adb("shell","run-as",PACKAGE,"rm","-f",smoke.session_path,smoke.cache_path,smoke.application_path)
                assert all(not smoke.exists(p) for p in (smoke.session_path,smoke.cache_path,smoke.application_path))
            except Exception:errors.append("Own source private-file cleanup failed")
        report=dict(date="2026-11-05",source="Real local B2:8774, labelled disposable PostgreSQL:5551, synthetic accounts/DEMO events",
            checks=smoke.results,failure=failure,restoreErrors=errors,fullAcceptance=failure is None and not errors,
            apkSha256=hashlib.sha256(Path(args.apk).read_bytes()).hexdigest(),installedApkSha256=installed,
            device=device,httpChecks=smoke.http_checks,syntheticAccounts=len(smoke.users),
            freshObservationRetries=smoke.observation_retries,contractSha256=hashlib.sha256((ROOT/"api/openapi.yaml").read_bytes()).hexdigest(),
            ios="Not built/run on Windows",registrationBrowser=("Official-site DEMO preview tested; real organiser browser/submission not repeated in this block"
                if any("official-site DEMO preview" in check["name"] for check in smoke.results) else "Not reached; organiser browser/submission not tested"))
        history=smoke.out/"android-history.json";previous=smoke.out/"android-smoke.json"
        attempts=json.loads(history.read_text(encoding="utf-8")) if history.exists() else []
        if previous.exists():attempts.append(json.loads(previous.read_text(encoding="utf-8")))
        history.write_text(json.dumps(attempts,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        (smoke.out/"android-smoke.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    return 1 if failure or errors else 0


if __name__=="__main__":raise SystemExit(main())
