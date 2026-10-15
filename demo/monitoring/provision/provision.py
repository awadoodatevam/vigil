"""lab-zabbix-provision: configures the lab's Zabbix through its API (AR-U-09, AR-X-02; P-04 spec Section 6.2.1).

A one-shot program: waits for the API, logs in as Admin (changing the default password on the first run), creates or
updates the objects of P-04 spec Section 6.2.2, keeps or replaces the API token of the user vigil in the generated
folder, logs out. It never prints a secret, a token or a session identifier.
"""

import json
import os
import secrets
import string
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

SECRETS = Path("/run/secrets/vigil")
GENERATED = Path("/srv/vigil/generated")
TIMEOUT_S = 30
WRONG_LOGIN = "Incorrect user name or password"

SCHEMAS = ("subscriber_profile", "balance_ledger", "bundle_catalog", "bundle_orders", "ussd_sessions", "sms_delivery")
ROUTES = tuple(f"route-{number:02d}" for number in range(1, 25))
HOSTS = ("lab-db-01", "lab-vas-01", "lab-rpt-01")
GROUP = "Lab hosts"
MEDIA_TYPE = "Vigil webhook"
USER_GROUP = "Vigil"
USER = "vigil"
ACTION = "Vigil lab webhook"
TOKEN_NAME = "vigil"
# DD-F-10 zabbix_media_type parameters (complete list, in this order).
PARAMETERS = (
    ("event_id", "{EVENT.ID}"),
    ("event_value", "{EVENT.VALUE}"),
    ("event_name", "{EVENT.NAME}"),
    ("host", "{HOST.HOST}"),
    ("tags", "{EVENT.TAGSJSON}"),
    ("event_date", "{EVENT.DATE}"),
    ("event_time", "{EVENT.TIME}"),
    ("recovery_date", "{EVENT.RECOVERY.DATE}"),
    ("recovery_time", "{EVENT.RECOVERY.TIME}"),
    ("severity", "{EVENT.SEVERITY}"),
)
# Zabbix 7.0 API values (BP-ASM-11).
AGENT_ITEM, NUMERIC_UNSIGNED, TRIM = 0, 3, 4
AGENT_INTERFACE, WEBHOOK, READ = 1, 4, 2
EVENT_TAG_VALUE, EQUALS, SEND_MESSAGE = 26, 0, 0
ALL_SEVERITIES = 63
TRIM_CHARACTERS = "\\s\\t\\r\\n"


class ApiError(Exception):
    pass


class LoginRefused(ApiError):
    """Zabbix refused both the secret and the default password of Admin."""


def status_file(path):
    return f"vfs.file.contents[/var/lib/lab/status/{path}]"


def lab_objects():
    """The 32 items and triggers of P-04 spec 6.2.2: (host, key, item name, trigger name, expression, component)."""
    rows = [
        (
            "lab-db-01",
            "vfs.fs.size[/data,free]",
            "Free bytes on /data",
            "Disk full: /data free space below 1 GiB",
            "last(/lab-db-01/vfs.fs.size[/data,free])<1G",
            "srv-db-01",
        )
    ]
    for schema in SCHEMAS:
        key = status_file(f"db_write_errors_{schema}")
        rows.append(
            (
                "lab-db-01",
                key,
                f"Database write errors, schema {schema}",
                f"Database write errors on schema {schema}",
                f"last(/lab-db-01/{key})>last(/lab-db-01/{key},#1:now-20s)",
                "db-subscriber",
            )
        )
    for route in ROUTES:
        key = status_file(f"sms_queue_depth_{route}")
        rows.append(
            (
                "lab-vas-01",
                key,
                f"SMS queue depth, route {route}",
                f"SMS queue growing on route {route}",
                f"last(/lab-vas-01/{key})>20 and last(/lab-vas-01/{key})>last(/lab-vas-01/{key},#1:now-20s)",
                "sms-queue",
            )
        )
    key = status_file("report_export_last_result")
    rows.append(
        (
            "lab-rpt-01",
            key,
            "Last report export result",
            "Report export job failed",
            f"last(/lab-rpt-01/{key})=0",
            "report-export",
        )
    )
    return rows


def media_script(address, token):
    """The webhook script of P-04 spec Section 6.2.3, with the address and the token as JavaScript string literals."""
    return f"""var ADDRESS = {json.dumps(address)};
var TOKEN = {json.dumps(token)};
var params = JSON.parse(value);
var recovery = params.event_value === '0';
var message = {{
    event_id: String(params.event_id),
    event_value: String(params.event_value),
    event_name: String(params.event_name),
    host: String(params.host),
    severity: String(params.severity),
    tags: JSON.parse(params.tags),
    event_date: String(params.event_date),
    event_time: String(params.event_time),
    recovery_date: recovery ? String(params.recovery_date) : '',
    recovery_time: recovery ? String(params.recovery_time) : ''
}};
var request = new HttpRequest();
request.addHeader('Content-Type: application/json');
request.addHeader('Authorization: Bearer ' + TOKEN);
request.addHeader('User-Agent: zabbix-webhook');
request.post(ADDRESS, JSON.stringify(message));
if (request.getStatus() !== 200) {{
    throw 'Vigil webhook answered HTTP ' + request.getStatus();
}}
return 'OK';
"""


class Zabbix:
    """JSON-RPC 2.0 calls to the Zabbix API, each with a 30 s deadline."""

    def __init__(self, url):
        self.url = url.rstrip("/") + "/api_jsonrpc.php"
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.auth = None
        self.next_id = 0

    def call(self, method, params, auth=True):
        self.next_id += 1
        body = {"jsonrpc": "2.0", "method": method, "params": params, "id": self.next_id}
        headers = {"Content-Type": "application/json-rpc"}
        bearer = self.auth if auth is True else auth
        if bearer:
            headers["Authorization"] = f"Bearer {bearer}"
        request = urllib.request.Request(self.url, data=json.dumps(body).encode(), headers=headers, method="POST")
        try:
            with self.opener.open(request, timeout=TIMEOUT_S) as response:
                answer = json.loads(response.read())
        except urllib.error.HTTPError as error:
            raise ApiError(f"{method}: HTTP {error.code}") from None
        except (OSError, ValueError) as error:
            raise ApiError(f"{method}: {error}") from None
        if "error" in answer:
            error = answer["error"]
            raise ApiError(f"{method}: {error.get('message', '')} {error.get('data', '')}".strip())
        return answer["result"]


def find(zabbix, method, field, value, output, **extra):
    """The object whose field equals value, or None."""
    found = zabbix.call(method, {"output": output, "filter": {field: [value]}, **extra})
    return found[0] if found else None


class Provisioner:
    def __init__(self, zabbix, webhook_base_url, secrets_dir, generated_dir, log, sleep=time.sleep, clock=None):
        self.zabbix = zabbix
        self.address = webhook_base_url.rstrip("/") + "/api/v1/webhooks/zabbix-lab"
        self.secrets_dir = Path(secrets_dir)
        self.generated = Path(generated_dir)
        self.log = log
        self.sleep = sleep
        self.clock = clock or time.monotonic

    def secret(self, name):
        try:
            return (self.secrets_dir / name).read_text()
        except FileNotFoundError:
            raise ApiError(f"missing secret {name}") from None

    def upsert(self, kind, label, found, id_field, values, create_only=None):
        """Creates the object when found is None, otherwise updates it to values; returns its id."""
        if found is None:
            result = self.zabbix.call(f"{kind}.create", {**values, **(create_only or {})})
            self.log(f"{label}: created")
            return result[f"{id_field}s"][0]
        self.zabbix.call(f"{kind}.update", {id_field: found[id_field], **values})
        self.log(f"{label}: updated")
        return found[id_field]

    # Step 1 and 2.
    def wait_and_login(self):
        admin_password = self.secret("zabbix-admin-password")
        self.secret("webhook-zabbix")
        end = self.clock() + 120
        while True:
            try:
                version = self.zabbix.call("apiinfo.version", {}, auth=None)
                break
            except ApiError as error:
                if self.clock() > end:
                    raise ApiError(f"Zabbix API not available within 120 s: {error}") from None
                self.sleep(2)
        self.log(f"Zabbix API {version}: available")
        end = self.clock() + 60
        while True:
            try:
                self.zabbix.auth = self.login(admin_password)
                return
            except LoginRefused:
                raise
            except ApiError:
                if self.clock() > end:
                    raise
                self.sleep(2)

    def login(self, admin_password):
        try:
            session = self.zabbix.call("user.login", {"username": "Admin", "password": admin_password}, auth=None)
            self.log("Admin login: with the secret")
            return session
        except ApiError as error:
            if WRONG_LOGIN not in str(error):
                raise
        try:
            session = self.zabbix.call("user.login", {"username": "Admin", "password": "zabbix"}, auth=None)
        except ApiError as error:
            if WRONG_LOGIN not in str(error):
                raise
            raise LoginRefused(
                "Admin login failed with the secret and the default password: remove the Zabbix database "
                "(deploy/local.sh down --volumes) or restore zabbix-admin-password"
            ) from None
        admin = self.zabbix.call("user.get", {"output": ["userid"], "filter": {"username": ["Admin"]}}, auth=session)
        self.zabbix.call(
            "user.update",
            {"userid": admin[0]["userid"], "current_passwd": "zabbix", "passwd": admin_password},
            auth=session,
        )
        self.log("Admin password: changed from the default")
        session = self.zabbix.call("user.login", {"username": "Admin", "password": admin_password}, auth=None)
        self.log("Admin login: with the secret")
        return session

    # Step 3.
    def objects(self):
        z = self.zabbix
        group = find(z, "hostgroup.get", "name", GROUP, ["groupid"])
        if group is None:
            group_id = z.call("hostgroup.create", {"name": GROUP})["groupids"][0]
            self.log(f'host group "{GROUP}": created')
        else:
            group_id = group["groupid"]
            self.log(f'host group "{GROUP}": kept')

        interfaces = {}
        for host in HOSTS:
            found = find(z, "host.get", "host", host, ["hostid"], selectInterfaces=["interfaceid", "type", "main"])
            interface = {"type": AGENT_INTERFACE, "main": 1, "useip": 0, "ip": "", "dns": host, "port": "10050"}
            values = {"name": host, "groups": [{"groupid": group_id}], "tags": []}
            if found is None:
                host_id = z.call("host.create", {"host": host, **values, "interfaces": [interface]})["hostids"][0]
                self.log(f'host "{host}": created')
            else:
                host_id = found["hostid"]
                z.call("host.update", {"hostid": host_id, **values, "templates": []})
                main = [i for i in found["interfaces"] if int(i["type"]) == AGENT_INTERFACE and int(i["main"]) == 1]
                if main:
                    z.call("hostinterface.update", {"interfaceid": main[0]["interfaceid"], **interface})
                else:
                    z.call("hostinterface.create", {"hostid": host_id, **interface})
                self.log(f'host "{host}": updated')
            found = find(z, "host.get", "host", host, ["hostid"], selectInterfaces=["interfaceid", "type", "main"])
            agent = [i for i in found["interfaces"] if int(i["type"]) == AGENT_INTERFACE and int(i["main"]) == 1]
            interfaces[host] = (host_id, agent[0]["interfaceid"])

        host_ids = [host_id for host_id, _ in interfaces.values()]
        items = z.call("item.get", {"output": ["itemid", "hostid", "key_"], "hostids": host_ids})
        items = {(item["hostid"], item["key_"]): item for item in items}
        rows = lab_objects()
        for host, key, item_name, *_ in rows:
            host_id, interface_id = interfaces[host]
            steps = []
            if key.startswith("vfs.file.contents["):
                # Space, tab, CR and LF, written as Zabbix's escape sequences: the API splits params at a newline.
                steps = [{"type": TRIM, "params": TRIM_CHARACTERS, "error_handler": 0, "error_handler_params": ""}]
            values = {
                "name": item_name,
                "type": AGENT_ITEM,
                "value_type": NUMERIC_UNSIGNED,
                "delay": "10s",
                "interfaceid": interface_id,
                "preprocessing": steps,
            }
            found = items.get((host_id, key))
            self.upsert("item", f'item "{host} {key}"', found, "itemid", values, {"hostid": host_id, "key_": key})

        triggers = z.call("trigger.get", {"output": ["triggerid", "description"], "hostids": host_ids})
        triggers = {trigger["description"]: trigger for trigger in triggers}
        for _host, _key, _item, name, expression, component in rows:
            values = {
                "description": name,
                "expression": expression,
                "priority": 0,
                "tags": [{"tag": "component", "value": component}, {"tag": "vigil", "value": "lab"}],
                "type": 0,
                "recovery_mode": 0,
                "manual_close": 0,
            }
            self.upsert("trigger", f'trigger "{name}"', triggers.get(name), "triggerid", values)

        media_values = {
            "name": MEDIA_TYPE,
            "type": WEBHOOK,
            "status": 0,
            "parameters": [{"name": name, "value": value} for name, value in PARAMETERS],
            "script": media_script(self.address, self.secret("webhook-zabbix")),
            "message_templates": [
                {"eventsource": 0, "recovery": recovery, "subject": "{EVENT.NAME}", "message": "{EVENT.NAME}"}
                for recovery in (0, 1)
            ],
        }
        found = find(z, "mediatype.get", "name", MEDIA_TYPE, ["mediatypeid"])
        media_id = self.upsert("mediatype", f'media type "{MEDIA_TYPE}"', found, "mediatypeid", media_values)

        found = find(z, "usergroup.get", "name", USER_GROUP, ["usrgrpid"])
        rights = {"hostgroup_rights": [{"id": group_id, "permission": READ}]}
        label = f'user group "{USER_GROUP}"'
        user_group_id = self.upsert("usergroup", label, found, "usrgrpid", rights, {"name": USER_GROUP})

        role = find(z, "role.get", "name", "User role", ["roleid"])
        if role is None:
            raise ApiError('role "User role" not found')
        user_values = {
            "roleid": role["roleid"],
            "usrgrps": [{"usrgrpid": user_group_id}],
            "medias": [
                {
                    "mediatypeid": media_id,
                    "sendto": USER,
                    "active": 0,
                    "severity": ALL_SEVERITIES,
                    "period": "1-7,00:00-24:00",
                }
            ],
        }
        found = find(z, "user.get", "username", USER, ["userid"])
        alphabet = string.ascii_letters + string.digits
        password = {"username": USER, "passwd": "".join(secrets.choice(alphabet) for _ in range(32))}
        user_id = self.upsert("user", f'user "{USER}"', found, "userid", user_values, password)

        message = {"default_msg": 1, "mediatypeid": media_id}
        action_values = {
            "name": ACTION,
            "status": 0,
            "filter": {
                "evaltype": 0,
                "conditions": [
                    {"conditiontype": EVENT_TAG_VALUE, "operator": EQUALS, "value": "lab", "value2": "vigil"}
                ],
            },
            "operations": [
                {
                    "operationtype": SEND_MESSAGE,
                    "esc_step_from": 1,
                    "esc_step_to": 1,
                    "opmessage": message,
                    "opmessage_usr": [{"userid": user_id}],
                }
            ],
            "recovery_operations": [
                {"operationtype": SEND_MESSAGE, "opmessage": message, "opmessage_usr": [{"userid": user_id}]}
            ],
            "update_operations": [],
        }
        found = find(z, "action.get", "name", ACTION, ["actionid"])
        self.upsert("action", f'action "{ACTION}"', found, "actionid", action_values, {"eventsource": 0})
        return user_id

    # Step 4.
    def token(self, user_id):
        z = self.zabbix
        target = self.generated / "zabbix-api"
        if target.exists():
            try:
                z.call("host.get", {"output": ["hostid"], "limit": 1}, auth=target.read_text())
                self.log("API token of vigil: kept")
                return
            except ApiError:
                pass
        old = z.call("token.get", {"output": ["tokenid"], "userids": [user_id], "filter": {"name": TOKEN_NAME}})
        if old:
            z.call("token.delete", [token["tokenid"] for token in old])
        created = z.call("token.create", {"name": TOKEN_NAME, "userid": user_id, "status": 0, "expires_at": 0})
        token_id = created["tokenids"][0]
        value = z.call("token.generate", [token_id])[0]["token"]
        temporary = self.generated / "zabbix-api.tmp"
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w") as file:
            file.write(value)
        os.chmod(temporary, 0o600)
        os.replace(temporary, target)
        self.log("API token of vigil: created")

    def run(self):
        self.wait_and_login()
        user_id = self.objects()
        self.token(user_id)
        self.zabbix.call("user.logout", [])
        self.zabbix.auth = None
        self.log("logout: done")


def main(environ=os.environ, secrets_dir=SECRETS, generated_dir=GENERATED, log=None, sleep=time.sleep):
    log = log or (lambda line: print(line, flush=True))
    base = environ.get("WEBHOOK_BASE_URL", "")
    if not base:
        log("WEBHOOK_BASE_URL is not set")
        return 1
    zabbix = Zabbix(environ.get("ZABBIX_URL", "http://lab-zabbix-web:8080"))
    try:
        Provisioner(zabbix, base, secrets_dir, generated_dir, log, sleep).run()
    except (ApiError, OSError) as error:
        log(str(error))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
