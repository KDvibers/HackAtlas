"""Android EventTime regression using a loopback, read-only Starter fixture server.

No backend/auth/DB mutations. Explicit REMOTE debug APK for10.0.2.2:8772.
Fixture values stay unchanged except the deliberate invalid-MINUTE negative case.
Only task-owned emulators; guest files/network/display are restored by the harness.
"""
import argparse
import copy
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import subprocess
import threading
import traceback
from urllib.parse import parse_qs, urlsplit

from android_profile_edit_smoke import ProfileSmoke, BASE, ROOT
from android_remote_smoke import SNAPSHOT_NOTE, PACKAGE


class FixtureServer(ThreadingHTTPServer):
    def __init__(self):
        super().__init__(("127.0.0.1", 8772), FixtureHandler)
        examples=ROOT/"api/examples"
        self.page=json.loads((examples/"catalog-page.json").read_text(encoding="utf-8"))
        self.detail=json.loads((examples/"hackathon-detail.json").read_text(encoding="utf-8"))
        self.metadata=json.loads((examples/"filter-metadata.json").read_text(encoding="utf-8"))
        self.invalid=False
        self.requests=[]
        assert self.page["items"] and all(item["isDemo"] for item in self.page["items"])


class FixtureHandler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass

    def do_GET(self):
        parsed=urlsplit(self.path);server=self.server
        client="host-probe" if self.headers.get("User-Agent","").startswith("Python-urllib") else "android"
        entry=dict(path=parsed.path,query=parsed.query,invalidMinute=server.invalid,client=client)
        server.requests.append(entry)
        if parsed.path=="/api/v1/metadata/filters":body=server.metadata
        elif parsed.path=="/api/v1/hackathons":
            query=parse_qs(parsed.query)
            if set(query)-{"sort","page","size"}:self.send_error(400);return
            try:
                page=int(query.get("page",["0"])[0]);size=int(query.get("size",["20"])[0])
                assert page>=0 and 1<=size<=50 and query.get("sort",["DEADLINE_ASC"])[0]=="DEADLINE_ASC"
            except (ValueError,AssertionError):self.send_error(400);return
            body=copy.deepcopy(server.page)
            items=body["items"]
            items.sort(key=lambda item: ((item["registrationDeadline"] or {}).get("date","9999-12-31"),
                                       (item["registrationDeadline"] or {}).get("time") or "~",item["id"]))
            if server.invalid:
                next(item for item in items if item["id"]==server.detail["id"])["starts"]["instant"]="2026-11-12T10:00:00Z"
            total=len(items);pages=(total+size-1)//size
            body.update(items=items[page*size:(page+1)*size],page=page,size=size,totalElements=total,totalPages=pages,hasNext=page+1<pages)
        elif parsed.path=="/api/v1/hackathons/"+server.detail["id"]:body=server.detail
        else:self.send_error(404);return
        data=json.dumps(body,ensure_ascii=False).encode("utf-8")
        entry.update(status=200,bodySha256=hashlib.sha256(data).hexdigest())
        self.send_response(200);self.send_header("Content-Type","application/json; charset=utf-8")
        self.send_header("Content-Length",str(len(data)));self.end_headers();self.wfile.write(data)


class TzdataSmoke(ProfileSmoke):
    def __init__(self,args,fixture):
        super().__init__(args)
        self.fixture=fixture
        self.out=ROOT/"mobile/artifacts/tzdata-compat"/args.label
        self.backup=ROOT/"mobile/build/tzdata-compat"/("device-backup-"+args.label)
        for folder in (self.out/"screenshots",self.backup):folder.mkdir(parents=True,exist_ok=True)

    def run(self):
        assert not self.exists(self.session_path) and not self.exists(self.cache_path),"Existing source session/cache"
        self.prepare()
        self.adb("shell","wm","size","1080x2400");self.adb("shell","wm","density","440")
        self.launch()
        first=self.get("hackathons",sort="DEADLINE_ASC",size=20)["items"][0]
        self.require(first["title"])
        self.capture("catalog")
        self.passed("REMOTE catalogue accepts all five unchanged canonical DEMO events")
        detail=self.fixture.detail;title=detail["title"]
        self.reveal(title);self.tap(title);self.require(title)
        self.tap("Сохранить хакатон","content-desc");self.entries(1)
        guest_bytes=self.read(self.stored)
        self.reveal("18:00 · Asia/Almaty");self.require("18:00 · Asia/Almaty")
        self.capture("detail-deadline")
        self.reveal("10:00 · Asia/Almaty");self.require("10:00 · Asia/Almaty")
        self.capture("detail-start")
        self.passed("source deadline18:00 and start10:00 stay exact; canonical instant is not reinterpreted")
        self.online(False);self.launch("saved");self.require(title);self.tap(title)
        self.reveal(SNAPSHOT_NOTE);self.require(SNAPSHOT_NOTE)
        self.reveal("18:00 · Asia/Almaty");self.require("18:00 · Asia/Almaty")
        assert self.read(self.stored)==guest_bytes
        self.capture("detail-offline")
        self.passed("saved EventTime snapshot remains readable after restart offline without changing guest bytes")
        self.online(True);self.fixture.invalid=True;self.launch()
        self.require("Каталог недоступен")
        self.require("Данные источника изменились или не удалось их прочитать. Обновите каталог и повторите действие.")
        assert self.find(self.tree(),title) is None
        self.capture("invalid-minute-rejected")
        self.passed("genuinely inconsistent MINUTE still fails closed; no DEMO fallback")


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb",required=True);parser.add_argument("--serial",default="emulator-5560")
    parser.add_argument("--label",required=True);parser.add_argument("--apk",required=True)
    args=parser.parse_args()
    if not re.fullmatch(r"emulator-\d+",args.serial) or not re.fullmatch(r"[a-z0-9-]{1,32}",args.label):parser.error("Task emulator/label required")
    avd=subprocess.run([args.adb,"-s",args.serial,"emu","avd","name"],capture_output=True,timeout=10,check=True).stdout.decode().splitlines()[0]
    if avd not in {"HackAtlas_Tzdata_API34","HackAtlas_Tzdata_API36"}:parser.error("Refusing a non-task AVD")
    args.device_api_base_url=BASE;args.api_base_url="http://127.0.0.1:8772/api/v1";args.docker=None
    fixture=FixtureServer();worker=threading.Thread(target=fixture.serve_forever,daemon=True);worker.start()
    smoke=TzdataSmoke(args,fixture);failure=None;properties={};installed_sha=None
    try:
        for key in ("ro.build.version.sdk","ro.build.version.release"):
            properties[key]=smoke.adb("shell","getprop",key).strip()
        properties["tzdata"]=smoke.adb("shell","head","-c","11","/apex/com.android.tzdata/etc/tz/tzdata")
        package_path=smoke.adb("shell","pm","path",PACKAGE).strip().removeprefix("package:")
        assert re.fullmatch(r"/data/app/[A-Za-z0-9_./~+=-]+/base\.apk",package_path),"Unexpected installed package path"
        installed_sha=smoke.adb("shell","sha256sum",package_path).split()[0]
        assert installed_sha==hashlib.sha256(Path(args.apk).read_bytes()).hexdigest(),"Installed APK differs from test artifact"
        smoke.run()
    except BaseException as exc:
        failure=type(exc).__name__+": "+str(exc).replace(smoke.secret,"[REDACTED]")
        print("FAIL",failure,flush=True)
        print("TRACE",[(Path(f.filename).name,f.name,f.lineno) for f in traceback.extract_tb(exc.__traceback__)],flush=True)
        try:smoke.capture("failure")
        except Exception:pass
    finally:
        errors=smoke.restore();fixture.invalid=False;fixture.shutdown();fixture.server_close();worker.join(timeout=5)
        report=dict(source="Loopback HTTP replay of Starter canonical DEMO fixtures; not a live backend acceptance",checks=smoke.results,
                    failure=failure,restoreErrors=errors,fullAcceptance=failure is None and not errors,
                    apkSha256=hashlib.sha256(Path(args.apk).read_bytes()).hexdigest(),device=properties,
                    installedApkSha256=installed_sha,apiSha256=hashlib.sha256((ROOT/"api/openapi.yaml").read_bytes()).hexdigest(),
                    freshObservationRetries=smoke.observation_retries,requests=fixture.requests,ios="Not built/run on Windows")
        (smoke.out/"android-smoke.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    return 1 if failure or errors else 0


if __name__=="__main__":raise SystemExit(main())
