"""Lab tests of P-04 (spec Section 7): monitoring, demo-faults and one storm against the webhook recorder.

They start the lab with deploy/local.sh as project vigil-labtest (VIGIL_LAB_PROFILE chooses the profile, default
test) and run the storm timing spike once with --storms 0 --extra read-only. Calls to Zabbix, Prometheus, Alertmanager
and the recorder are made from lab-api-01 (no port is published); calls to demo-faults go to 172.31.0.1:8091.
"""

import json
import os
import re
import stat
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

import pytest
import yaml

pytestmark = pytest.mark.lab

ROOT = Path(__file__).resolve().parents[2]
PROJECT = "vigil-labtest"
PROFILE = os.environ.get("VIGIL_LAB_PROFILE", "test")
COMPOSE = ROOT / "deploy/local/compose.yaml"
FAULTS = "http://172.31.0.1:8091"
ZABBIX = "http://lab-zabbix-web:8080/api_jsonrpc.php"
PROMETHEUS = "http://lab-prometheus:9090"
ALERTMANAGER = "http://lab-alertmanager:9093"
RECORDER = "http://dev-recorder:8080"
TIMING_CHECKS = {"arrival", "max_gap", "sl_f_3", "resolve_after_clear"}
NEW_SECRETS = {
    "vigil-app": ["webhook-zabbix", "webhook-alertmanager", "alertmanager-password", "zabbix-api"],
    "lab-zabbix-provision": ["webhook-zabbix", "zabbix-admin-password"],
    "lab-alertmanager": ["webhook-alertmanager", "alertmanager-web-config"],
    "demo-control": ["alertmanager-password", "faults-token", "zabbix-api"],
    "lab-prometheus": ["alertmanager-password"],
    "lab-zabbix-db": ["zabbix-db-password"],
    "lab-zabbix-server": ["zabbix-db-password"],
    "lab-zabbix-web": ["zabbix-db-password"],
    "demo-faults": ["faults-token"],
}
NEW_UNITS = ("lab-zabbix-db", "lab-zabbix-server", "lab-zabbix-web", "lab-zabbix-provision", "lab-prometheus")
NEW_UNITS += ("lab-alertmanager", "demo-faults", "dev-recorder")
LOCAL_IMAGES = {
    "vigil/demo-lab:local": "demo/lab",
    "vigil/demo-faults:local": "demo/faults",
    "vigil/lab-zabbix-provision:local": "demo/monitoring/provision",
    "vigil/recorder:local": "dev/recorder",
    "vigil/storm-spike:local": "spikes/storm-timing",
}
# Runs in lab-api-01: one HTTP request read as JSON from standard input; prints {"status", "body"}.
REQUEST = """
import json, sys, urllib.error, urllib.request
r = json.load(sys.stdin)
data = None if r.get("body") is None else json.dumps(r["body"]).encode()
request = urllib.request.Request(r["url"], data=data, method=r.get("method", "GET"), headers=r.get("headers", {}))
try:
    with urllib.request.urlopen(request, timeout=30) as response:
        status, raw = response.status, response.read()
except urllib.error.HTTPError as error:
    status, raw = error.code, error.read()
try:
    body = json.loads(raw)
except ValueError:
    body = raw.decode()
print(json.dumps({"status": status, "body": body}))
"""


def run(args, check=True, env=None, input=None, timeout=1800):
    result = subprocess.run(
        [str(arg) for arg in args], capture_output=True, text=True, env=env, input=input, timeout=timeout, check=False
    )
    if check and result.returncode != 0:
        raise AssertionError(f"{args} exited {result.returncode}: {result.stdout}{result.stderr}")
    return result


class Lab:
    def __init__(self, home):
        self.home = home
        self.base = home / PROJECT
        self.secrets = self.base / "secrets"
        self.env = {**os.environ, "VIGIL_LOCAL_HOME": str(home)}

    def local(self, *args, check=True):
        return run([ROOT / "deploy/local.sh", *args], check=check, env=self.env)

    def secret(self, unit, name):
        return (self.secrets / unit / name).read_text()

    def request(self, url, method="GET", headers=None, body=None):
        payload = json.dumps({"url": url, "method": method, "headers": headers or {}, "body": body})
        command = ["docker", "exec", "-i", "lab-api-01", "/opt/venv/bin/python", "-c", REQUEST]
        return json.loads(run(command, input=payload).stdout)

    def zabbix(self, method, params, token=None, password=None):
        """A Zabbix API call: with a token, as Admin with a password, or without authorization."""
        headers = {"Content-Type": "application/json-rpc"}
        if password is not None:
            token = self.zabbix("user.login", {"username": "Admin", "password": password})["result"]
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        body = {"jsonrpc": "2.0", "method": method, "params": params, "id": 1}
        answer = self.request(ZABBIX, "POST", headers, body)
        assert answer["status"] == 200, answer
        if password is not None:
            self.request(ZABBIX, "POST", headers, {"jsonrpc": "2.0", "method": "user.logout", "params": [], "id": 2})
        return answer["body"]

    def admin(self, method, params):
        answer = self.zabbix(method, params, password=self.secret("lab-zabbix-provision", "zabbix-admin-password"))
        assert "result" in answer, answer
        return answer["result"]

    def alertmanager(self, path, authenticated=True):
        headers = {}
        if authenticated:
            import base64

            password = self.secret("vigil-app", "alertmanager-password")
            headers["Authorization"] = "Basic " + base64.b64encode(f"vigil:{password}".encode()).decode()
        return self.request(ALERTMANAGER + path, headers=headers)

    def prometheus(self, path):
        answer = self.request(PROMETHEUS + path)
        assert answer["status"] == 200, answer
        return answer["body"]["data"]

    def faults(self, path, body, token=True):
        request = urllib.request.Request(FAULTS + path, data=json.dumps(body).encode(), method="POST")
        if token:
            request.add_header("Authorization", f"Bearer {self.secret('demo-control', 'faults-token')}")
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read())

    def labctl(self, container, *args):
        return run(["docker", "exec", container, "labctl", *args]).stdout


def inspect(container):
    return json.loads(run(["docker", "inspect", container]).stdout)[0]


def wait_for(condition, timeout, interval=2):
    end = time.monotonic() + timeout
    while True:
        value = condition()
        if value or time.monotonic() > end:
            return value
        time.sleep(interval)


@pytest.fixture(scope="module")
def lab(tmp_path_factory):
    home = tmp_path_factory.mktemp("vigil-local")
    lab = Lab(home)
    try:
        lab.local("up", "--lab-only", "--profile", PROFILE, "--project", PROJECT)
        yield lab
    finally:
        lab.local("down", "--volumes", "--project", PROJECT, check=False)
        for folder in home.rglob("*"):
            if folder.is_dir():
                folder.chmod(0o700)


def spike_run(lab, folder):
    folder.mkdir()
    (folder / "save").mkdir()
    env = dict(lab.env, VIGIL_SPIKE_RESULTS=str(folder), VIGIL_SPIKE_SAVE=str(folder / "save"))
    args = ["--storms", "0", "--extra", "read-only", "--foreground", "--project", PROJECT]
    result = run([ROOT / "spikes/storm-timing/run.sh", *args], check=False, env=env, timeout=3600)
    match = re.search(r"^result file (\S+)$", result.stdout, re.MULTILINE)
    assert match, result.stdout + result.stderr
    print(result.stdout)
    return Path(match.group(1)), json.loads(Path(match.group(1)).read_text())


@pytest.fixture(scope="module")
def storm(lab, tmp_path_factory):
    """The result of one storm with SL-F-1, SL-F-2 and SL-F-3, with the gate retry rule for timing checks."""
    folder = tmp_path_factory.mktemp("spike")
    path, result = spike_run(lab, folder / "first")
    if not result["passed"]:
        failed = {name for run in result["runs"] for name, c in run["checks"].items() if not c["passed"]}
        failed |= {name for name, c in result["checks"].items() if not c["passed"]}
        print(f"first result file {path}: failed checks {sorted(failed)}")
        if failed and failed <= TIMING_CHECKS:
            path, result = spike_run(lab, folder / "second")
            print(f"second result file {path}: passed {result['passed']}")
    return result


@pytest.fixture(scope="module")
def run_result(storm):
    assert len(storm["runs"]) == 1 and storm["runs"][0]["kind"] == "read-only"
    return storm["runs"][0]


def recorder_calls(lab):
    answer = lab.request(RECORDER + "/_recorder/calls")
    assert answer["status"] == 200
    return answer["body"]


def depends_on(service):
    config = yaml.safe_load(run(["docker", "compose", "-f", COMPOSE, "config"]).stdout)
    return {name: value["condition"] for name, value in (config["services"][service].get("depends_on") or {}).items()}


def test_start_order(lab):
    """AR-U-08 no alert at lab start, also 60 s later; the lab hosts start in dependency order."""
    for moment in ("now", "60 s later"):
        if moment != "now":
            time.sleep(60)
        assert recorder_calls(lab) == [], moment
        assert lab.prometheus("/api/v1/query?query=ALERTS")["result"] == [], moment
        problems = lab.admin(
            "problem.get", {"output": ["name"], "tags": [{"tag": "vigil", "value": "lab", "operator": 1}]}
        )
        assert problems == [], moment
    assert depends_on("lab-api-01") == {"lab-db-01": "service_healthy"}
    assert depends_on("lab-vas-01") == {"lab-db-01": "service_healthy", "lab-api-01": "service_healthy"}
    assert depends_on("lab-probe-01") == {"lab-vas-01": "service_healthy"}


def compose_images():
    return {
        name: service["image"]
        for name, service in yaml.safe_load(COMPOSE.read_text())["services"].items()
        if "@sha256:" in service["image"]
    }


def zabbix_version(lab):
    version = lab.zabbix("apiinfo.version", {})["result"]
    match = re.fullmatch(r"7\.0\.(\d+)", version)
    assert match and int(match.group(1)) >= 12, version
    return version


def test_zabbix(lab):
    """AR-U-09 Zabbix containers, version, Admin password and the provisioned objects; every item has a value."""
    images = compose_images()
    for name in ("lab-zabbix-db", "lab-zabbix-server", "lab-zabbix-web"):
        assert inspect(name)["Config"]["Image"] == images[name]
    zabbix_version(lab)
    assert "error" in lab.zabbix("user.login", {"username": "Admin", "password": "zabbix"})
    assert "result" in lab.zabbix(
        "user.login", {"username": "Admin", "password": lab.secret("lab-zabbix-provision", "zabbix-admin-password")}
    )

    group = lab.admin("hostgroup.get", {"output": ["groupid"], "filter": {"name": ["Lab hosts"]}})[0]["groupid"]

    def items():
        found = lab.admin("item.get", {"output": ["key_", "lastclock", "lastvalue", "delay"], "groupids": [group]})
        return found if len(found) == 32 and all(int(item["lastclock"]) > 0 for item in found) else None

    found = wait_for(items, 300, 5)
    assert found, lab.admin("item.get", {"output": ["key_", "lastclock", "error"], "groupids": [group]})
    assert {item["delay"] for item in found} == {"10s"}

    triggers = lab.admin(
        "trigger.get",
        {
            "output": ["description", "expression"],
            "expandExpression": True,
            "selectTags": ["tag", "value"],
            "groupids": [group],
        },
    )
    assert len(triggers) == 32
    expected = {
        "Disk full: /data free space below 1 GiB": ("last(/lab-db-01/vfs.fs.size[/data,free])<1G", "srv-db-01"),
        "Report export job failed": (
            "last(/lab-rpt-01/vfs.file.contents[/var/lib/lab/status/report_export_last_result])=0",
            "report-export",
        ),
    }
    for trigger in triggers:
        tags = {(tag["tag"], tag["value"]) for tag in trigger["tags"]}
        assert ("vigil", "lab") in tags and len(tags) == 2
        if trigger["description"] in expected:
            expression, component = expected[trigger["description"]]
            assert trigger["expression"] == expression and ("component", component) in tags
        else:
            assert "#1:now-20s" in trigger["expression"]

    media = lab.admin("mediatype.get", {"output": "extend", "filter": {"name": ["Vigil webhook"]}})
    assert len(media) == 1 and len(media[0]["parameters"]) == 10
    assert "http://demo.vigil.test:8080/api/v1/webhooks/zabbix-lab" in media[0]["script"]
    users = lab.admin(
        "user.get", {"output": ["username"], "filter": {"username": ["vigil"]}, "selectUsrgrps": ["name"]}
    )
    assert [group["name"] for group in users[0]["usrgrps"]] == ["Vigil"]
    groups = lab.admin(
        "usergroup.get", {"output": ["name"], "filter": {"name": ["Vigil"]}, "selectHostGroupRights": "extend"}
    )
    host_group = lab.admin("hostgroup.get", {"output": ["groupid"], "filter": {"name": ["Lab hosts"]}})[0]
    assert groups[0]["hostgroup_rights"] == [{"id": host_group["groupid"], "permission": "2"}]
    action = lab.admin(
        "action.get",
        {
            "output": ["name"],
            "filter": {"name": ["Vigil lab webhook"]},
            "selectFilter": "extend",
            "selectOperations": "extend",
            "selectRecoveryOperations": "extend",
        },
    )[0]
    assert len(action["filter"]["conditions"]) == 1
    assert [(op["esc_step_from"], op["esc_step_to"]) for op in action["operations"]] == [("1", "1")]
    assert len(action["recovery_operations"]) == 1


def test_prometheus_and_alertmanager(lab):
    """AR-U-10 targets, rules, Alertmanager authentication and configuration, the rendered webhook address."""
    targets = lab.prometheus("/api/v1/targets")["activeTargets"]
    assert sorted(t["labels"]["job"] for t in targets) == ["lab-api", "lab-db-node", "lab-probe", "lab-vas"]
    assert all(t["health"] == "up" for t in targets)
    files = yaml.safe_load((ROOT / "demo/monitoring/prometheus/lab-rules.yml").read_text())["groups"][0]["rules"]
    rules = lab.prometheus("/api/v1/rules")["groups"][0]["rules"]
    assert len(rules) == 5
    for loaded, written in zip(rules, files, strict=True):
        assert loaded["name"] == written["alert"] and loaded["query"] == written["expr"]
        assert loaded["duration"] == 10 and loaded.get("keepFiringFor", 0) == 0
        assert loaded["labels"] == written["labels"] and loaded["annotations"] == written["annotations"]
    assert len(lab.prometheus("/api/v1/alertmanagers")["activeAlertmanagers"]) == 1
    assert lab.alertmanager("/api/v2/status", authenticated=False)["status"] == 401
    status = lab.alertmanager("/api/v2/status")
    assert status["status"] == 200
    config = yaml.safe_load(status["body"]["config"]["original"])
    route = config["route"]
    assert route["receiver"] == "discard" and len(route["routes"]) == 1
    child = route["routes"][0]
    assert child["receiver"] == "vigil" and child["group_by"] == ["..."]
    assert (child["group_wait"], child["group_interval"], child["repeat_interval"]) == ("5s", "10s", "12h")
    assert [r["name"] for r in config["receivers"]] == ["discard", "vigil"]
    webhook = config["receivers"][1]["webhook_configs"][0]
    assert webhook["send_resolved"] is True and webhook["max_alerts"] == 0
    rendered = (lab.base / "config" / "alertmanager.yml").read_text()
    assert "url: http://demo.vigil.test:8080/api/v1/webhooks/alertmanager-lab" in rendered


def test_versions(lab):
    """AR-T-22 Zabbix 7.0.x (x at least 12), PostgreSQL 16, Prometheus 3.x, Alertmanager 0.28 or later."""
    zabbix_version(lab)
    postgres = run(["docker", "exec", "lab-zabbix-db", "postgres", "--version"]).stdout
    assert re.match(r"postgres \(PostgreSQL\) 16\.\d+", postgres)
    assert lab.prometheus("/api/v1/status/buildinfo")["version"].startswith("3.")
    version = lab.alertmanager("/api/v2/status")["body"]["versionInfo"]["version"]
    major, minor = (int(part) for part in version.split(".")[:2])
    assert (major, minor) >= (0, 28)


def test_secrets(lab):
    """AR-S-13 the 16 new secret files, their formats, the bcrypt hash and the copied Zabbix API token."""
    for unit, names in NEW_SECRETS.items():
        for name in names:
            assert (lab.secrets / unit / name).is_file(), (unit, name)
    for name in ("webhook-zabbix", "webhook-alertmanager", "faults-token"):
        assert re.fullmatch(
            r"[A-Za-z0-9_-]{43}", lab.secret("vigil-app" if name != "faults-token" else "demo-faults", name)
        )
    for unit, name in (("vigil-app", "alertmanager-password"), ("lab-zabbix-provision", "zabbix-admin-password")):
        assert re.fullmatch(r"[A-Za-z0-9]{20}", lab.secret(unit, name))
    assert re.fullmatch(r"[A-Za-z0-9]{20}", lab.secret("lab-zabbix-db", "zabbix-db-password"))
    check = (
        "import bcrypt, sys; c = open('/s/lab-alertmanager/alertmanager-web-config').read();"
        "assert c.startswith('basic_auth_users:\\n  vigil: ') and c.endswith('\\n');"
        "sys.exit(0 if bcrypt.checkpw(open('/s/vigil-app/alertmanager-password').read().encode(),"
        " c.split('vigil: ')[1].strip().encode()) else 1)"
    )
    image = "vigil/lab-zabbix-provision:local"
    run(
        [
            "docker",
            "run",
            "--rm",
            "-v",
            f"{lab.secrets}:/s:ro",
            "--entrypoint",
            "/opt/venv/bin/python",
            image,
            "-c",
            check,
        ]
    )
    generated = (lab.base / "generated" / "zabbix-api").read_text()
    assert lab.secret("vigil-app", "zabbix-api") == generated == lab.secret("demo-control", "zabbix-api")
    answer = lab.zabbix("host.get", {"output": ["hostid"], "limit": 1}, token=generated)
    assert isinstance(answer.get("result"), list)


def mounts(container):
    return {m["Destination"]: m for m in inspect(container)["Mounts"]}


def test_secret_mounts(lab):
    """AR-S-04 each new unit mounts only its own secret folder read-only; only the provision has generated/."""
    for unit in NEW_UNITS:
        found = mounts(unit)
        own = "vigil-app" if unit == "dev-recorder" else unit
        assert found["/run/secrets/vigil"]["Source"] == str(lab.secrets / own)
        assert found["/run/secrets/vigil"]["RW"] is False
        assert ("/srv/vigil/generated" in found) is (unit == "lab-zabbix-provision")
        assert not [d for d in found if d.startswith("/run/secrets/") and d != "/run/secrets/vigil"]
    provision = mounts("lab-zabbix-provision")
    assert provision["/srv/vigil/generated"]["Source"] == str(lab.base / "generated")
    assert stat.S_IMODE((lab.base / "generated").stat().st_mode) == 0o700
    spike = yaml.safe_load(
        run(["docker", "compose", "-f", COMPOSE, "--profile", "spike", "config"], env=lab.env).stdout
    )
    volumes = {v["target"]: v for v in spike["services"]["storm-spike"]["volumes"]}
    assert volumes["/run/secrets/vigil-app"]["read_only"] and volumes["/run/secrets/demo-control"]["read_only"]
    assert "/run/secrets/vigil" not in volumes


def secret_files(lab):
    return {p: (p.read_bytes(), p.stat().st_mtime_ns) for p in lab.secrets.rglob("*") if p.is_file()}


def test_secret_files(lab):
    """BP-R-26 10 unit folders 0555 and 21 files 0444 of the user; a second up changes no secret and keeps the token."""
    folders = [p for p in lab.secrets.iterdir() if p.is_dir()]
    assert len(folders) == 10
    for folder in folders:
        assert stat.S_IMODE(folder.stat().st_mode) == 0o555 and folder.stat().st_uid == os.getuid()
    files = secret_files(lab)
    assert len(files) == 21
    for path in files:
        assert stat.S_IMODE(path.stat().st_mode) == 0o444 and path.stat().st_uid == os.getuid()
    token = (lab.base / "generated" / "zabbix-api").read_text()
    lab.local("up", "--lab-only", "--profile", PROFILE, "--project", PROJECT)
    assert secret_files(lab) == files
    assert (lab.base / "generated" / "zabbix-api").read_text() == token


def test_storm(storm, run_result):
    """SB-T-01 the storm passed: the connection checks and every check of the read-only run."""
    for name in ("zabbix_connection_test", "alertmanager_connection_test", "alertmanager_requires_auth"):
        assert storm["checks"][name]["passed"], storm["checks"]
    assert storm["checks"]["prometheus_targets_up"]["passed"]
    assert {name for name, c in run_result["checks"].items() if not c["passed"]} == set()
    assert storm["passed"] is True


def test_monitoring_configuration(run_result):
    """DD-F-10 (monitoring_configuration) 126 storm entries (31 + 95), 1 SL-F-3 entry, 124 still firing."""
    counts = run_result["counts"]
    assert (counts["storm_firing"], counts["zabbix_firing"], counts["alertmanager_firing"]) == (126, 31, 95)
    assert (counts["sl_f_3_firing"], counts["still_firing_after_action"]) == (1, 124)
    for name in ("exactly_once", "messages", "sl_f_2"):
        assert run_result["checks"][name]["passed"], run_result["checks"][name]


def test_timing_targets(run_result):
    """DD-F-10 (timing_targets) arrival, gaps, SL-F-3 and the clear meet their targets; the values are recorded."""
    for name in ("arrival", "max_gap", "sl_f_3", "resolve_after_clear"):
        assert run_result["checks"][name]["passed"], run_result["checks"][name]
    timing = run_result["timing"]
    for name in ("first_arrival_s", "last_arrival_s", "max_gap_s", "sl_f_3_arrival_s", "last_resolve_after_clear_s"):
        assert isinstance(timing[name], float), name


def test_sl_f_1(run_result):
    """SL-F-1 inject fills /data until 64 MiB stay free; the action removes every injected file."""
    assert run_result["checks"]["precondition"]["passed"] and run_result["checks"]["action"]["passed"]
    measured = run_result["measured"]
    assert measured["injected_files"] >= 2
    assert 32 * 1048576 <= measured["avail_bytes_after_injection"] <= 96 * 1048576


def test_sl_f_2(run_result):
    """SL-F-2 after the action every storm alert except the 2 disk_full alerts keeps firing."""
    assert run_result["checks"]["sl_f_2"]["passed"], run_result["checks"]["sl_f_2"]


def test_sl_f_3(lab, run_result):
    """SL-F-3 the report export alert arrives within 30 s; after the clear the job succeeds."""
    assert run_result["checks"]["sl_f_3"]["passed"]
    assert lab.labctl("lab-rpt-01", "report") == "succeed\n"


def test_clear_all_faults(run_result):
    """SB-T-05 the clear resolves every lab alert within 40 s."""
    assert run_result["checks"]["resolve_after_clear"]["passed"]
    assert run_result["timing"]["last_resolve_after_clear_s"] <= 40


def test_messages(lab, run_result):
    """AR-X-02 every recorded call was authorized; Zabbix calls carry User-Agent zabbix-webhook and tags as an array."""
    calls = [json.loads(p.read_text()) for p in sorted((lab.base / "recordings").glob("*.json"))]
    assert calls
    assert all(call["authorized"] for call in calls)
    zabbix = [call for call in calls if call["source_key"] == "zabbix-lab"]
    assert zabbix and all(call["headers"]["User-Agent"] == "zabbix-webhook" for call in zabbix)
    assert all(isinstance(json.loads(call["body"])["tags"], list) for call in zabbix)
    assert any(call["source_key"] == "alertmanager-lab" for call in calls)


def test_faults_unit(lab):
    """AR-U-07 demo-faults on the host network as user 0 with the Docker socket; token and unknown fault answers."""
    info = inspect("demo-faults")
    assert info["HostConfig"]["NetworkMode"] == "host" and info["Config"]["User"] == "0"
    assert mounts("demo-faults")["/var/run/docker.sock"]["Source"] == "/var/run/docker.sock"
    assert lab.faults("/faults/clear", {"faults": []}, token=False) == (401, {"error": "unauthorized"})
    assert lab.faults("/faults/clear", {"faults": ["SL-F-4"]}) == (500, {"error": "unknown fault SL-F-4"})


def test_faults_interface(lab):
    """AR-X-03 idempotent inject, the state file across a restart, clear."""
    assert lab.faults("/faults/SL-F-2/inject", {}) == (200, {"active": ["SL-F-2"]})
    assert lab.faults("/faults/SL-F-2/inject", {}) == (200, {"active": ["SL-F-2"]})
    assert lab.labctl("lab-db-01", "db", "read-only") == "on\n"
    started = inspect("demo-faults")["State"]["StartedAt"]
    run(["docker", "restart", "demo-faults"])

    def healthy():
        state = inspect("demo-faults")["State"]
        return state["StartedAt"] != started and state.get("Health", {}).get("Status") == "healthy"

    assert wait_for(healthy, 60)
    state = run(["docker", "exec", "demo-faults", "cat", "/var/lib/demo-faults/state.json"]).stdout
    assert json.loads(state) == {"active": ["SL-F-2"]}
    assert lab.faults("/faults/clear", {"faults": ["SL-F-2", "SL-F-3"]}) == (200, {"active": []})
    assert lab.labctl("lab-db-01", "db", "read-only") == "off\n"


def test_faults_on_demand(lab, run_result):
    """SL-A3-02 SL-F-3 injected and cleared on demand; the storm injected and cleared SL-F-1, SL-F-2 and SL-F-3."""
    assert lab.faults("/faults/SL-F-3/inject", {}) == (200, {"active": ["SL-F-3"]})
    assert lab.labctl("lab-rpt-01", "report") == "fail\n"
    assert lab.faults("/faults/clear", {"faults": ["SL-F-3"]}) == (200, {"active": []})
    assert lab.labctl("lab-rpt-01", "report") == "succeed\n"
    assert run_result["faults"] == ["SL-F-1", "SL-F-2", "SL-F-3"] and run_result["checks"]["steps"]["passed"]


def test_images():
    """BP-R-04 the five local images, pinned base and third-party images, pinned Python packages, no build CA file."""
    digest = re.search(r"^FROM (ubuntu:24\.04@sha256:[0-9a-f]{64})$", (ROOT / "demo/lab/Dockerfile").read_text(), re.M)
    for image, folder in LOCAL_IMAGES.items():
        run(["docker", "image", "inspect", image])
        if folder != "demo/lab":
            froms = re.findall(r"^FROM (\S+)", (ROOT / folder / "Dockerfile").read_text(), re.MULTILINE)
            assert froms == [digest.group(1)]
            found = run(["docker", "run", "--rm", "--entrypoint", "ls", image, "/etc/apt/apt.conf.d"]).stdout.split()
            assert "99vigil-build-ca" not in found
    for reference in compose_images().values():
        assert re.fullmatch(r"[\w./-]+:[\w.-]+@sha256:[0-9a-f]{64}", reference), reference
    for image, folder, package in (
        ("vigil/lab-zabbix-provision:local", "demo/monitoring/provision", "bcrypt"),
        ("vigil/storm-spike:local", "spikes/storm-timing", "pyyaml"),
    ):
        pinned = (ROOT / folder / "requirements.txt").read_text().split("==")[1].strip()
        frozen = run(["docker", "run", "--rm", "--entrypoint", "/opt/venv/bin/pip", image, "freeze"]).stdout
        versions = {line.split("==")[0].lower(): line.split("==")[1] for line in frozen.split()}
        assert versions[package] == pinned
