"""Unit tests of lab-zabbix-provision (P-04 spec Section 7) against a fake Zabbix JSON-RPC server."""

import itertools
import json
import secrets
import stat
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import provision
import pytest
import yaml

ROOT = Path(__file__).resolve().parents[3]
WRONG = {"code": -32602, "message": "Invalid params.", "data": "Incorrect user name or password or account is blocked."}
ID_FIELDS = {
    "hostgroup": "groupid",
    "host": "hostid",
    "hostinterface": "interfaceid",
    "item": "itemid",
    "trigger": "triggerid",
    "mediatype": "mediatypeid",
    "usergroup": "usrgrpid",
    "user": "userid",
    "role": "roleid",
    "action": "actionid",
    "token": "tokenid",
}


class FakeZabbix:
    """The parts of the Zabbix 7.0 API that provision.py uses, in memory."""

    def __init__(self):
        self.ids = itertools.count(100)
        self.objects = {kind: [] for kind in ID_FIELDS}
        self.objects["user"].append({"userid": "1", "username": "Admin", "passwd": "zabbix"})
        self.objects["role"].append({"roleid": "1", "name": "User role"})
        self.sessions = set()
        self.tokens = {}
        self.calls = []

    def error(self, data):
        return {"error": {"code": -32500, "message": "Application error.", "data": data}}

    def handle(self, method, params, bearer):
        self.calls.append(method)
        kind, action = method.split(".")
        if method == "apiinfo.version":
            return {"result": "7.0.31"}
        if method == "user.login":
            user = self.find("user", "username", params["username"])
            if not user or user["passwd"] != params["password"]:
                return {"error": WRONG}
            session = secrets.token_hex(16)
            self.sessions.add(session)
            return {"result": session}
        if bearer not in self.sessions and bearer not in self.tokens.values():
            return self.error("Not authorised.")
        if bearer in self.tokens.values() and method != "host.get":
            return self.error("No permissions.")
        if method == "user.logout":
            self.sessions.discard(bearer)
            return {"result": True}
        if method == "token.generate":
            value = secrets.token_hex(32)
            self.tokens[params[0]] = value
            return {"result": [{"tokenid": params[0], "token": value}]}
        if method == "token.delete":
            for token_id in params:
                self.tokens.pop(token_id, None)
            self.objects["token"] = [t for t in self.objects["token"] if t["tokenid"] not in params]
            return {"result": {"tokenids": params}}
        field = ID_FIELDS[kind]
        if action == "get":
            found = self.objects[kind]
            for key, values in params.get("filter", {}).items():
                values = values if isinstance(values, list) else [values]
                found = [o for o in found if o.get(key) in values]
            if "hostids" in params and kind != "trigger":
                found = [o for o in found if o.get("hostid") in params["hostids"]]
            if "userids" in params:
                found = [o for o in found if o.get("userid") in params["userids"]]
            result = [dict(o) for o in found]
            if kind == "host" and "selectInterfaces" in params:
                for host in result:
                    host["interfaces"] = [i for i in self.objects["hostinterface"] if i["hostid"] == host["hostid"]]
            return {"result": result[: params.get("limit", len(result))]}
        if action == "create":
            values = json.loads(json.dumps(params))
            values[field] = str(next(self.ids))
            if kind == "host":
                for interface in values.pop("interfaces"):
                    interface.update(hostid=values["hostid"], interfaceid=str(next(self.ids)))
                    self.objects["hostinterface"].append(interface)
            self.objects[kind].append(values)
            return {"result": {f"{field}s": [values[field]]}}
        if action == "update":
            target = self.find(kind, field, params[field])
            if kind == "user" and "passwd" in params:
                if params.get("current_passwd") != target["passwd"]:
                    return self.error("Incorrect current password.")
                params = {k: v for k, v in params.items() if k != "current_passwd"}
                self.sessions.clear()
            target.update(json.loads(json.dumps(params)))
            return {"result": {f"{field}s": [params[field]]}}
        return self.error(f"unknown method {method}")

    def find(self, kind, field, value):
        return next((o for o in self.objects[kind] if o.get(field) == value), None)

    def created(self):
        return [call for call in self.calls if call.endswith(".create")]


@pytest.fixture
def zabbix():
    fake = FakeZabbix()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            pass

        def do_POST(self):
            assert self.path == "/api_jsonrpc.php"
            assert self.headers["Content-Type"] == "application/json-rpc"
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            bearer = (self.headers.get("Authorization") or "").removeprefix("Bearer ")
            outcome = fake.handle(request["method"], request["params"], bearer)
            answer = {"jsonrpc": "2.0", "id": request["id"], **outcome}
            raw = json.dumps(answer).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    fake.url = f"http://127.0.0.1:{server.server_address[1]}"
    yield fake
    server.shutdown()
    server.server_close()


def expected_objects():
    """(host, key, item name, trigger name, expression, component) of P-04 spec Section 6.2.2, built from DD-F-03."""
    data = yaml.safe_load((ROOT / "docs/31-demo-data/03-monitoring.yaml").read_text())
    rows = {row["id"]: row for row in data["lab_alert_catalogue"]}
    f = "vfs.file.contents[/var/lib/lab/status/{}]".format
    la01 = rows["LA-01"]
    objects = [
        (
            "lab-db-01",
            "vfs.fs.size[/data,free]",
            "Free bytes on /data",
            la01["trigger_name"],
            "last(/lab-db-01/vfs.fs.size[/data,free])<1G",
            la01["tag_component"],
        )
    ]
    for schema in rows["LA-02"]["dimensions"]["schema"]:
        key = f(f"db_write_errors_{schema}")
        objects.append(
            (
                "lab-db-01",
                key,
                f"Database write errors, schema {schema}",
                rows["LA-02"]["trigger_name"].format(schema=schema),
                f"last(/lab-db-01/{key})>last(/lab-db-01/{key},#1:now-20s)",
                rows["LA-02"]["tag_component"],
            )
        )
    for route in rows["LA-03"]["dimensions"]["route"]:
        key = f(f"sms_queue_depth_{route}")
        objects.append(
            (
                "lab-vas-01",
                key,
                f"SMS queue depth, route {route}",
                rows["LA-03"]["trigger_name"].format(route=route),
                f"last(/lab-vas-01/{key})>20 and last(/lab-vas-01/{key})>last(/lab-vas-01/{key},#1:now-20s)",
                rows["LA-03"]["tag_component"],
            )
        )
    key = f("report_export_last_result")
    objects.append(
        (
            "lab-rpt-01",
            key,
            "Last report export result",
            rows["LA-09"]["trigger_name"],
            f"last(/lab-rpt-01/{key})=0",
            rows["LA-09"]["tag_component"],
        )
    )
    return objects


def run(zabbix, folders, base="http://demo.vigil.test:8080/"):
    lines = []
    environ = {"WEBHOOK_BASE_URL": base, "ZABBIX_URL": zabbix.url}
    code = provision.main(environ, folders["secrets"], folders["generated"], lines.append, sleep=lambda s: None)
    return code, lines


@pytest.fixture
def folders(tmp_path):
    folder = tmp_path / "secrets"
    folder.mkdir()
    (folder / "zabbix-admin-password").write_text(secrets.token_hex(10))
    (folder / "webhook-zabbix").write_text(secrets.token_urlsafe(32))
    generated = tmp_path / "generated"
    generated.mkdir()
    return {"secrets": folder, "generated": generated}


def test_provision(zabbix, folders):
    """AR-X-02 first run, second run, replaced token, no secret in the output."""
    admin_password = (folders["secrets"] / "zabbix-admin-password").read_text()
    webhook_token = (folders["secrets"] / "webhook-zabbix").read_text()
    code, lines = run(zabbix, folders)
    assert code == 0, lines
    assert zabbix.find("user", "username", "Admin")["passwd"] == admin_password
    assert "Admin password: changed from the default" in lines

    hosts = {h["hostid"]: h for h in zabbix.objects["host"]}
    assert sorted(h["host"] for h in hosts.values()) == ["lab-db-01", "lab-rpt-01", "lab-vas-01"]
    for interface in zabbix.objects["hostinterface"]:
        host = hosts[interface["hostid"]]["host"]
        assert (interface["type"], interface["useip"], interface["dns"], interface["ip"]) == (1, 0, host, "")
        assert interface["port"] == "10050"
    expected = expected_objects()
    items = {(hosts[i["hostid"]]["host"], i["key_"]): i for i in zabbix.objects["item"]}
    assert len(items) == 32 and len(zabbix.objects["trigger"]) == 32
    triggers = {t["description"]: t for t in zabbix.objects["trigger"]}
    for host, key, item_name, trigger_name, expression, component in expected:
        item = items[(host, key)]
        assert (item["name"], item["type"], item["value_type"], item["delay"]) == (item_name, 0, 3, "10s")
        if key.startswith("vfs.file.contents["):
            trim = {"type": 4, "params": "\\s\\t\\r\\n", "error_handler": 0, "error_handler_params": ""}
            assert item["preprocessing"] == [trim]
        else:
            assert item["preprocessing"] == []
        trigger = triggers[trigger_name]
        assert trigger["expression"] == expression
        assert trigger["tags"] == [{"tag": "component", "value": component}, {"tag": "vigil", "value": "lab"}]
        assert (trigger["priority"], trigger["type"], trigger["recovery_mode"], trigger["manual_close"]) == (0, 0, 0, 0)

    media = zabbix.objects["mediatype"]
    assert len(media) == 1 and media[0]["name"] == "Vigil webhook" and media[0]["type"] == 4
    assert [p["name"] for p in media[0]["parameters"]] == [
        "event_id",
        "event_value",
        "event_name",
        "host",
        "tags",
        "event_date",
        "event_time",
        "recovery_date",
        "recovery_time",
        "severity",
    ]
    assert media[0]["parameters"][4]["value"] == "{EVENT.TAGSJSON}"
    assert '"http://demo.vigil.test:8080/api/v1/webhooks/zabbix-lab"' in media[0]["script"]
    assert json.dumps(webhook_token) in media[0]["script"]
    assert "User-Agent: zabbix-webhook" in media[0]["script"]

    group = zabbix.objects["hostgroup"][0]
    user_group = zabbix.find("usergroup", "name", "Vigil")
    assert user_group["hostgroup_rights"] == [{"id": group["groupid"], "permission": 2}]
    user = zabbix.find("user", "username", "vigil")
    assert user["usrgrps"] == [{"usrgrpid": user_group["usrgrpid"]}] and user["roleid"] == "1"
    assert len(user["passwd"]) == 32
    assert user["medias"][0]["mediatypeid"] == media[0]["mediatypeid"] and user["medias"][0]["sendto"] == "vigil"
    action = zabbix.find("action", "name", "Vigil lab webhook")
    assert action["filter"]["conditions"] == [{"conditiontype": 26, "operator": 0, "value": "lab", "value2": "vigil"}]
    assert len(action["operations"]) == 1 and action["operations"][0]["esc_step_to"] == 1
    assert action["operations"][0]["opmessage_usr"] == [{"userid": user["userid"]}]
    assert len(action["recovery_operations"]) == 1 and action["update_operations"] == []

    token_file = folders["generated"] / "zabbix-api"
    token = token_file.read_text()
    assert token in zabbix.tokens.values() and not token.endswith("\n")
    assert stat.S_IMODE(token_file.stat().st_mode) == 0o600

    zabbix.calls.clear()
    code, second = run(zabbix, folders)
    assert code == 0 and zabbix.created() == []
    assert token_file.read_text() == token and "API token of vigil: kept" in second
    assert "Admin password: changed from the default" not in second

    zabbix.tokens.clear()
    code, third = run(zabbix, folders)
    assert code == 0 and zabbix.created() == ["token.create"]
    replaced = token_file.read_text()
    assert replaced != token and replaced in zabbix.tokens.values() and not replaced.endswith("\n")
    assert stat.S_IMODE(token_file.stat().st_mode) == 0o600
    assert len(zabbix.objects["token"]) == 1

    output = "\n".join(lines + second + third)
    for value in (admin_password, webhook_token, token, replaced, user["passwd"], *zabbix.sessions):
        assert value not in output


def test_refusals(zabbix, folders):
    """AR-X-02 no base address, a missing secret and a refused default password each exit 1 with their message."""
    assert run(zabbix, folders, base="") == (1, ["WEBHOOK_BASE_URL is not set"])
    zabbix.find("user", "username", "Admin")["passwd"] = "something else"
    code, lines = run(zabbix, folders)
    assert code == 1 and lines[-1].startswith("Admin login failed with the secret and the default password")
    (folders["secrets"] / "webhook-zabbix").unlink()
    assert run(zabbix, folders) == (1, ["missing secret webhook-zabbix"])
