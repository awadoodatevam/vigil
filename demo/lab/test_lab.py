"""Lab tests (P-03 spec Section 7): they start the lab with deploy/local.sh and need Docker with privileged containers.

VIGIL_LAB_PROFILE (default test) chooses the size profile; VIGIL_LAB_REPORT names a file that receives the df and
du lines of lab-db-01 once the lab is up (owner-run step, Section 6.10).
"""

import json
import os
import re
import shutil
import stat
import subprocess
import time
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

import pytest
import yaml

pytestmark = pytest.mark.lab

ROOT = Path(__file__).resolve().parents[2]
PROJECT = "vigil-labtest"
PROFILE = os.environ.get("VIGIL_LAB_PROFILE", "test")
HOSTS = ("lab-db-01", "lab-api-01", "lab-vas-01", "lab-probe-01", "lab-rpt-01")
IMAGE = "vigil/demo-lab:local"
GIB = 1073741824
MIB = 1048576
SIZES = {"test": {"device": 4, "db": 0.8, "export": 0.1}, "demo": {"device": 16, "db": 6, "export": 1}}
SCHEMAS = ("subscriber_profile", "balance_ledger", "bundle_catalog", "bundle_orders", "ussd_sessions", "sms_delivery")
ROUTES = tuple(f"route-{number:02d}" for number in range(1, 25))
SSH_OPTIONS = (
    "-o",
    "BatchMode=yes",
    "-o",
    "StrictHostKeyChecking=no",
    "-o",
    "UserKnownHostsFile=/dev/null",
    "-o",
    "LogLevel=ERROR",
    "-o",
    "ConnectTimeout=5",
)
FETCH = (
    "import sys, urllib.request\n"
    "for url in sys.argv[1:]:\n"
    "    sys.stdout.write(urllib.request.urlopen(url, timeout=5).read().decode())\n"
)
METRICS_URLS = ("http://lab-api-01:9200/metrics", "http://lab-vas-01:9200/metrics", "http://lab-probe-01:9200/metrics")

# Test-only injection like SL-F-1 (DD-F-10 faults.SL-F-1.inject steps 1 to 4); prints "<N + 1> <bytes>".
INJECT = r"""
set -euo pipefail
free=$(df -B1 --output=avail /data | tail -n 1 | tr -d ' ')
size=268435456
n=0
while [ $((free - (n + 1) * size)) -ge $((320 * 1048576)) ]; do n=$((n + 1)); done
last=$(((free - n * size - 64 * 1048576) / 4096 * 4096))
for i in $(seq 1 $((n + 1))); do
  if [ "$i" -le "$n" ]; then bytes=$size; else bytes=$last; fi
  fallocate -l "$bytes" "$(printf '/data/txlog_archive/txlog_%06d.arc' "$i")"
done
now=$(date +%s)
for i in $(seq 1 $((n + 1))); do
  touch -m -d "@$((now - 47 * 3600 + (i - 1) * 21 * 3600 / n))" "$(printf '/data/txlog_archive/txlog_%06d.arc' "$i")"
done
echo "$((n + 1)) $((n * size + last))"
"""
CLEAR = "find /data/txlog_archive -maxdepth 1 -type f -name 'txlog_*.arc' -delete"


def run(args, check=True, env=None, input=None, timeout=600):
    result = subprocess.run(
        [str(arg) for arg in args], capture_output=True, text=True, env=env, input=input, timeout=timeout, check=False
    )
    if check and result.returncode != 0:
        raise AssertionError(f"{args} exited {result.returncode}: {result.stdout}{result.stderr}")
    return result


class Lab:
    def __init__(self, home):
        self.home = home
        self.secrets = home / PROJECT / "secrets"
        self.values = home / PROJECT / "values"
        self.env = {
            **os.environ,
            "VIGIL_LOCAL_HOME": str(home),
            "VIGIL_LOCAL_SECRETS": str(self.secrets),
            "VIGIL_LAB_PROFILE": PROFILE,
        }

    def compose(self, *args, check=True, input=None):
        command = ["docker", "compose", "-f", ROOT / "deploy/local/compose.yaml", "-p", PROJECT, *args]
        return run(command, check=check, env=self.env, input=input)

    def exec(self, service, *command, check=True, input=None):
        return self.compose("exec", "-T", service, *command, check=check, input=input)

    def sh(self, service, script, check=True, input=None):
        return self.exec(service, "bash", "-c", script, check=check, input=input).stdout

    def local(self, *args, home=None, check=True):
        env = dict(self.env, VIGIL_LOCAL_HOME=str(home or self.home))
        return run([ROOT / "deploy/local.sh", *args], check=check, env=env)

    def fetch(self, *urls):
        return self.exec("lab-api-01", "/opt/venv/bin/python", "-c", FETCH, *urls).stdout

    def metrics(self):
        return parse_metrics(self.fetch(*METRICS_URLS))

    def status(self, service, names):
        script = "cd /var/lib/lab/status && for f in " + " ".join(names) + '; do echo "$f $(cat "$f")"; done'
        return {line.split()[0]: int(line.split()[1]) for line in self.sh(service, script).splitlines()}

    def db_counts(self):
        names = [f"db_writes_{s}" for s in SCHEMAS] + [f"db_write_errors_{s}" for s in SCHEMAS]
        values = self.status("lab-db-01", names)
        return {s: values[f"db_writes_{s}"] for s in SCHEMAS}, {s: values[f"db_write_errors_{s}"] for s in SCHEMAS}

    def sms_depths(self):
        values = self.status("lab-vas-01", [f"sms_queue_depth_{route}" for route in ROUTES])
        return {route: values[f"sms_queue_depth_{route}"] for route in ROUTES}

    def put_key(self, path, key):
        """A temporary 0600 copy of a private key in lab-api-01, with a newline at its end."""
        key = key if key.endswith("\n") else key + "\n"
        self.exec("lab-api-01", "sh", "-c", f"umask 077 && cat > {path}", input=key)

    def ssh(self, user, key_path, command, *options):
        return self.exec(
            "lab-api-01", "ssh", *SSH_OPTIONS, *options, "-i", key_path, f"{user}@lab-db-01", command, check=False
        )


def parse_metrics(text):
    """{metric name: {frozenset of (label, value): sample value}} of a Prometheus text exposition."""
    metrics = {}
    for line in text.splitlines():
        match = re.fullmatch(r"(\w+)\{(.*)\} (\S+)", line)
        if match:
            labels = frozenset(re.findall(r'(\w+)="([^"]*)"', match.group(2)))
            metrics.setdefault(match.group(1), {})[labels] = float(match.group(3))
    return metrics


def private_key(lab, kind):
    return json.loads((lab.secrets / "vigil-app" / f"lab-db-01-{kind}").read_text())["private_key"]


@pytest.fixture(scope="session")
def lab(tmp_path_factory):
    home = tmp_path_factory.mktemp("vigil-local")
    lab = Lab(home)
    try:
        lab.local("up", "--lab-only", "--profile", PROFILE, "--project", PROJECT)
        report = os.environ.get("VIGIL_LAB_REPORT")
        if report:
            df = lab.exec("lab-db-01", "df", "-B1", "/data").stdout
            du = lab.exec("lab-db-01", "du", "-B1", "--max-depth=1", "/data").stdout
            with open(ROOT / report, "a") as file:
                file.write(f"lab-db-01 profile {PROFILE}, df -B1 /data:\n{df}du -B1 --max-depth=1 /data:\n{du}")
        yield lab
    finally:
        lab.local("down", "--volumes", "--project", PROJECT, check=False)
        for folder in home.rglob("*"):
            if folder.is_dir():
                folder.chmod(0o700)


def wait_for(condition, timeout, interval=0.5):
    """Calls condition every interval seconds until it returns a true value or timeout seconds pass."""
    end = time.monotonic() + timeout
    while True:
        value = condition()
        if value or time.monotonic() > end:
            return value
        time.sleep(interval)


def test_containers(lab):
    """AR-U-08 five containers named as the lab hosts from one image; only lab-db-01 privileged and on port 22."""
    for host in HOSTS:
        info = json.loads(run(["docker", "inspect", host]).stdout)[0]
        assert info["Name"] == f"/{host}"
        assert info["Config"]["Image"] == IMAGE
        assert info["Config"]["Hostname"] == host
        assert info["Config"]["Labels"]["com.docker.compose.project"] == PROJECT
        assert info["HostConfig"]["Privileged"] is (host == "lab-db-01")
        listening = re.findall(r":(\d+)\s", lab.exec(host, "ss", "-ltn").stdout)
        assert ("22" in listening) is (host == "lab-db-01")


def test_networks(lab):
    """AR-H-03 lab-net 172.31.0.0/24 in Docker; vigil-net 172.30.0.0/24 declared in the compose file."""
    network = json.loads(run(["docker", "network", "inspect", f"{PROJECT}_lab-net"]).stdout)[0]
    assert network["IPAM"]["Config"][0]["Subnet"] == "172.31.0.0/24"
    assert network["IPAM"]["Config"][0]["Gateway"] == "172.31.0.1"
    compose = yaml.safe_load((ROOT / "deploy/local/compose.yaml").read_text())
    assert compose["networks"]["vigil-net"]["ipam"]["config"] == [{"subnet": "172.30.0.0/24", "gateway": "172.30.0.1"}]
    assert compose["networks"]["lab-net"]["ipam"]["config"] == [{"subnet": "172.31.0.0/24", "gateway": "172.31.0.1"}]


def test_time_zone(lab):
    """AR-H-09 every lab container runs in UTC."""
    for host in HOSTS:
        assert lab.exec(host, "date", "+%Z").stdout == "UTC\n"


def test_python(lab):
    """AR-T-21 Ubuntu's Python 3.12 and prometheus_client in /opt/venv."""
    assert re.fullmatch(r"Python 3\.12\.\d+\n", lab.exec("lab-api-01", "/usr/bin/python3", "--version").stdout)
    lab.exec("lab-api-01", "/opt/venv/bin/python", "-c", "import prometheus_client")


def baseline_stats(lab):
    return lab.sh("lab-db-01", "stat -c '%n %Y %Z %s' /data/db/* /data/export/*")


@pytest.mark.mac
def test_image_and_data(lab):
    """AR-X-01 XFS loop mount, image file, users, Zabbix agent 2 7.0; baseline unchanged by a restart."""
    mount = lab.exec("lab-db-01", "findmnt", "-n", "-o", "FSTYPE,SOURCE", "/data").stdout.split()
    assert mount[0] == "xfs" and re.fullmatch(r"/dev/loop\d+", mount[1])
    lab.exec("lab-db-01", "test", "-f", f"/var/lib/lab-data/data-{PROFILE}.xfs")
    users = {
        "zabbix": ("1997", "1997", "/usr/sbin/nologin", {"zabbix"}),
        "vigil-diag": ("2001", "2001", "/bin/bash", {"vigil-diag"}),
        "vigil-action": ("2002", "2002", "/bin/bash", {"vigil-action", "vigil-diag"}),
    }
    for user, (uid, gid, shell, groups) in users.items():
        entry = lab.exec("lab-db-01", "getent", "passwd", user).stdout.strip().split(":")
        assert (entry[2], entry[3], entry[6]) == (uid, gid, shell)
        assert set(lab.exec("lab-db-01", "id", "-nG", user).stdout.split()) == groups
    for user in ("vigil-diag", "vigil-action"):
        assert lab.exec("lab-db-01", "getent", "shadow", user).stdout.split(":")[1] == "*"
    assert re.search(r"zabbix_agent2 \(Zabbix\) 7\.0\.\d+", lab.exec("lab-db-01", "zabbix_agent2", "-V").stdout)

    before = baseline_stats(lab)
    started = run(["docker", "inspect", "-f", "{{.State.StartedAt}}", "lab-db-01"]).stdout
    lab.compose("restart", "lab-db-01")

    def restarted():
        state = run(["docker", "inspect", "-f", "{{.State.StartedAt}} {{.State.Health.Status}}", "lab-db-01"]).stdout
        return state.split()[0] != started.strip() and state.split()[1] == "healthy"

    assert wait_for(restarted, 180, interval=2)
    assert baseline_stats(lab) == before


@pytest.mark.mac
def test_size_profile(lab):
    """BP-R-30 /data device size and baseline content of the profile."""
    sizes = SIZES[PROFILE]
    size = int(lab.exec("lab-db-01", "df", "-B1", "--output=size", "/data").stdout.split()[-1])
    assert (sizes["device"] - 0.15) * GIB <= size <= sizes["device"] * GIB
    for folder in ("db", "export"):
        used = int(lab.exec("lab-db-01", "du", "-B1", "-s", f"/data/{folder}").stdout.split()[0])
        assert abs(used - sizes[folder] * GIB) <= 0.01 * sizes[folder] * GIB
    assert lab.exec("lab-db-01", "find", "/data/txlog_archive", "-mindepth", "1").stdout == ""


def test_secret_mounts(lab):
    """AR-S-04 lab-db-01 runs as user 0 with its read-only secret folder; no SSH host key in the image."""
    for host in HOSTS:
        info = json.loads(run(["docker", "inspect", host]).stdout)[0]
        mounts = [m for m in info["Mounts"] if m["Destination"] == "/run/secrets/vigil"]
        if host == "lab-db-01":
            assert len(mounts) == 1 and mounts[0]["RW"] is False
            assert info["Config"]["User"] in ("", "0", "root")
            assert lab.exec(host, "id", "-u").stdout == "0\n"
        else:
            assert mounts == []
            assert lab.exec(host, "test", "-e", "/run/secrets/vigil", check=False).returncode == 1
    found = run(["docker", "run", "--rm", "--entrypoint", "find", IMAGE, "/", "-xdev", "-name", "ssh_host_*"])
    assert found.stdout == ""


def public_key_fields(line):
    return line.split()[:2]


def test_secrets(lab):
    """AR-S-13 the five lab-db-01 secrets: JSON private keys and matching public keys."""
    names = ["lab-db-01/lab-db-01-host-key", "lab-db-01/lab-db-01-diag-pub", "lab-db-01/lab-db-01-action-pub"]
    names += ["vigil-app/lab-db-01-diag", "vigil-app/lab-db-01-action"]
    for name in names:
        assert (lab.secrets / name).is_file()
    for kind in ("diag", "action"):
        secret = json.loads((lab.secrets / "vigil-app" / f"lab-db-01-{kind}").read_text())
        assert set(secret) == {"username", "private_key"}
        assert secret["username"] == f"vigil-{kind}"
        lab.put_key("/tmp/p03-key", secret["private_key"])
        try:
            derived = lab.exec("lab-api-01", "ssh-keygen", "-y", "-f", "/tmp/p03-key").stdout
        finally:
            lab.exec("lab-api-01", "rm", "-f", "/tmp/p03-key")
        public = (lab.secrets / "lab-db-01" / f"lab-db-01-{kind}-pub").read_text()
        assert not public.endswith("\n")
        assert public_key_fields(public) == public_key_fields(derived)
        assert public_key_fields(public)[0] == "ssh-ed25519"
    assert not (lab.secrets / "lab-db-01" / "lab-db-01-host-key").read_text().endswith("\n")


def secret_files(lab):
    return {path: (path.read_bytes(), path.stat().st_mtime_ns) for path in lab.secrets.rglob("*") if path.is_file()}


def test_secret_files(lab, tmp_path):
    """BP-R-26 secret files 0444 in 0555 unit folders; a second up keeps them; an incomplete pair stops up."""
    assert stat.S_IMODE(lab.secrets.stat().st_mode) == 0o700
    for unit in ("lab-db-01", "vigil-app"):
        folder = lab.secrets / unit
        assert stat.S_IMODE(folder.stat().st_mode) == 0o555
        assert folder.stat().st_uid == os.getuid()
    files = secret_files(lab)
    assert len(files) == 5
    for path in files:
        assert stat.S_IMODE(path.stat().st_mode) == 0o444
        assert path.stat().st_uid == os.getuid()

    lab.local("up", "--lab-only", "--profile", PROFILE, "--project", PROJECT)
    assert secret_files(lab) == files

    copy = tmp_path / "home"
    shutil.copytree(lab.home, copy)
    folder = copy / PROJECT / "secrets" / "lab-db-01"
    folder.chmod(0o755)
    (folder / "lab-db-01-diag-pub").unlink()
    folder.chmod(0o555)
    try:
        result = lab.local("up", "--lab-only", "--profile", PROFILE, "--project", PROJECT, home=copy, check=False)
        assert result.returncode == 1
        expected = (
            f"Secret pair incomplete: delete {copy}/{PROJECT}/secrets/vigil-app/lab-db-01-diag"
            f" or restore {copy}/{PROJECT}/secrets/lab-db-01/lab-db-01-diag-pub."
        )
        assert expected in result.stdout
    finally:
        for path in copy.rglob("*"):
            if path.is_dir():
                path.chmod(0o700)


def test_derived_value(lab):
    """AR-B-07 values/derived.yaml holds the fingerprint of the host key that lab-db-01 presents."""
    presented = lab.sh("lab-api-01", "ssh-keyscan -t ed25519 lab-db-01 2> /dev/null | ssh-keygen -l -E sha256 -f -")
    fingerprint = presented.split()[1]
    assert fingerprint.startswith("SHA256:")
    assert (lab.values / "derived.yaml").read_text() == f'lab_db_host_key_sha256: "{fingerprint}"\n'
    assert stat.S_IMODE((lab.values / "derived.yaml").stat().st_mode) == 0o644


@pytest.fixture
def keys(lab):
    lab.put_key("/tmp/p03-diag", private_key(lab, "diag"))
    lab.put_key("/tmp/p03-action", private_key(lab, "action"))
    yield {"diag": "/tmp/p03-diag", "action": "/tmp/p03-action"}
    lab.exec("lab-api-01", "rm", "-f", "/tmp/p03-diag", "/tmp/p03-action")


@pytest.mark.mac
def test_permissions(lab, keys):
    """DD-F-10 (permissions) each user logs in only with its key; vigil-diag reads, vigil-action deletes archives."""
    assert lab.ssh("vigil-diag", keys["diag"], "true").returncode == 0
    assert lab.ssh("vigil-action", keys["action"], "true").returncode == 0
    for user, key in (("vigil-action", "diag"), ("vigil-diag", "action"), ("root", "diag"), ("root", "action")):
        assert lab.ssh(user, keys[key], "true").returncode == 255, (user, key)
    password = lab.ssh("vigil-diag", keys["diag"], "true", "-o", "PreferredAuthentications=password")
    assert password.returncode == 255

    folders = ("/data", "/data/db", "/data/export", "/data/txlog_archive")
    baseline = ("/data/db/datafile_01.dbf", "/data/export/export_01.dat")
    diag = keys["diag"]
    action = keys["action"]
    for folder in folders:
        assert lab.ssh("vigil-diag", diag, f"ls {folder} > /dev/null").returncode == 0
        assert lab.ssh("vigil-diag", diag, f"touch {folder}/p03-probe").returncode != 0
    for path in baseline:
        assert lab.ssh("vigil-diag", diag, f"head -c 4096 {path} > /dev/null").returncode == 0
    archive = "/data/txlog_archive/p03-probe.arc"
    assert lab.ssh("vigil-action", action, f"touch {archive}").returncode == 0
    assert lab.ssh("vigil-diag", diag, f"unlink {archive}").returncode != 0
    assert lab.ssh("vigil-action", action, f"unlink {archive}").returncode == 0
    for folder in folders[:3]:
        assert lab.ssh("vigil-action", action, f"touch {folder}/p03-probe").returncode != 0
    for path in baseline:
        assert lab.ssh("vigil-diag", diag, f"unlink {path}").returncode != 0
        assert lab.ssh("vigil-action", action, f"unlink {path}").returncode != 0
        lab.exec("lab-db-01", "test", "-f", path)
    assert lab.exec("lab-db-01", "find", "/data", "-name", "p03-probe*").stdout == ""


def quote(value):
    """AR-K-12: the value between single quotes, with every ' written as '\\''."""
    return "'" + str(value).replace("'", "'\\''") + "'"


def render(operation, values):
    command = operation["command_template"]
    for parameter in operation["parameters"]:
        command = command.replace("{" + parameter["name"] + "}", quote(values[parameter["name"]]))
    return command


def gib(value):
    return (Decimal(value) / Decimal(GIB)).quantize(Decimal("0.1"), ROUND_HALF_UP)


# The rules of DD-F-04 output_parsers, in Python.
def df_row(output, values):
    mount, size, used, avail, pcent = output.splitlines()[-1].split()
    size, used, avail = int(size), int(used), int(avail)
    return {
        "mount": mount,
        "size_bytes": size,
        "used_bytes": used,
        "avail_bytes": avail,
        "used_pct": int(pcent.rstrip("%")),
        "size_gib": gib(size),
        "used_gib": gib(used),
        "avail_gib": gib(avail),
    }


def du_top(output, values):
    named = {}
    lines = [line.split("\t", 1) for line in output.splitlines()]
    for position, (size, path) in enumerate([line for line in lines if line[1] != values["path"]], start=1):
        named[f"top{position}_path"] = path
        named[f"top{position}_gib"] = gib(int(size))
    return named


def count_bytes(output, values):
    files, size = output.split()
    return {"files": int(files), "bytes": int(size), "gib": gib(int(size))}


def single_number(output, values):
    return {"value": int(output)}


def removed_files(output, values):
    lines = [line.split(" ", 1) for line in output.splitlines()]
    total = sum(int(size) for size, _ in lines)
    return {"files_removed": len(lines), "bytes_removed": total, "gib_removed": gib(total)}


PARSERS = {
    "df_row": df_row,
    "du_top": du_top,
    "count_bytes": count_bytes,
    "single_number": single_number,
    "removed_files": removed_files,
}


class Runner:
    """Runs DD-F-04 operations over SSH from lab-api-01 as the user of their permission kind."""

    def __init__(self, lab, keys):
        self.lab = lab
        self.keys = keys
        data = yaml.safe_load((ROOT / "docs/31-demo-data/04-operations-actions-recovery.yaml").read_text())
        self.operations = {operation["operation_key"]: operation for operation in data["operations"]}
        self.diagnostics = {d["diagnostic_key"]: d for d in data["diagnostic_definitions"]}
        self.action = data["registered_actions"][0]

    def run(self, operation_key, values):
        operation = self.operations[operation_key]
        kind = "diag" if operation["permission_kind"] == "diagnostic" else "action"
        result = self.lab.ssh(f"vigil-{kind}", self.keys[kind], render(operation, values))
        assert result.returncode == 0, result.stderr
        return PARSERS[operation["output_parser"]](result.stdout, values)

    def diagnostic(self, key):
        definition = self.diagnostics[key]
        return self.run(definition["operation_key"], definition["parameter_values"])

    def used_pct(self):
        precondition = self.action["precondition"]
        return self.run(precondition["operation_key"], precondition["parameters"])["value"]

    def purge(self):
        return self.run(self.action["operation_key"], self.action["parameter_values"])


def inject(lab):
    files, size = lab.sh("lab-db-01", INJECT).split()
    return int(files), int(size)


@pytest.mark.mac
def test_command_templates(lab, keys):
    """DD-ASM-02 the five DD-F-04 templates run on lab-db-01 and their parsers read them, before and after injection."""
    runner = Runner(lab, keys)
    before_pct = runner.used_pct()
    assert before_pct < 90
    usage = runner.diagnostic("disk_usage")
    assert usage["mount"] == "/data" and usage["used_pct"] >= before_pct - 1
    assert runner.diagnostic("largest_folders")["top1_path"] == "/data/db"
    runner.diagnostic("disk_growth")

    files, injected = inject(lab)
    try:
        after_pct = runner.used_pct()
        # fill until 64 MiB stay free: df rounds up to 100 % in demo; in test 64 MiB are 1.6 % of 3.94 GiB (report)
        assert after_pct == 100 if PROFILE == "demo" else after_pct >= 99
        usage = runner.diagnostic("disk_usage")
        assert 32 * MIB <= usage["avail_bytes"] <= 96 * MIB
        assert runner.diagnostic("largest_folders")["top1_path"] == "/data/txlog_archive"
        assert runner.diagnostic("disk_growth")["files"] >= files
        removed = runner.purge()
        assert removed["files_removed"] == files
        assert removed["bytes_removed"] == injected
    finally:
        lab.sh("lab-db-01", CLEAR)
    assert abs(runner.used_pct() - before_pct) <= 1


def labels_of(metric):
    return {frozenset(labels) for labels in metric}


def test_storm(lab):
    """SL-A3-01 a full /data makes every mock fail and the lab recovers when the files are removed."""
    metrics = lab.metrics()
    assert len(metrics["lab_api_requests_total"]) == 32 and len(metrics["lab_api_errors_total"]) == 32
    assert len(metrics["lab_ussd_sessions_total"]) == 12 and len(metrics["lab_ussd_timeouts_total"]) == 12
    assert len(metrics["lab_probe_last_result"]) == 50
    writes, errors = lab.db_counts()
    assert wait_for(lambda: all(lab.db_counts()[0][s] > writes[s] for s in SCHEMAS), 10)

    _, errors = lab.db_counts()
    metrics = lab.metrics()
    api_errors = metrics["lab_api_errors_total"]
    timeouts = metrics["lab_ussd_timeouts_total"]
    inject(lab)
    try:
        assert wait_for(lambda: any(lab.db_counts()[1][s] > errors[s] for s in SCHEMAS), 30)

        def storm():
            now_errors = lab.db_counts()[1]
            now = lab.metrics()
            return (
                all(now_errors[s] > errors[s] for s in SCHEMAS)
                and all(value == 0 for value in now["lab_probe_last_result"].values())
                and all(now["lab_api_errors_total"][k] > v for k, v in api_errors.items())
                and all(now["lab_ussd_timeouts_total"][k] > v for k, v in timeouts.items())
                and all(depth > 20 for depth in lab.sms_depths().values())
            )

        assert wait_for(storm, 45)
    finally:
        lab.sh("lab-db-01", CLEAR)

    def recovered():
        first = lab.db_counts()[1]
        time.sleep(5)
        return (
            lab.db_counts()[1] == first
            and all(value == 1 for value in lab.metrics()["lab_probe_last_result"].values())
            and all(depth <= 2 for depth in lab.sms_depths().values())
        )

    assert wait_for(recovered, 60)


def test_fault_control_points(lab):
    """DD-F-10 (fault control points) the read-only flag and the report job mode."""
    try:
        _, errors = lab.db_counts()
        lab.exec("lab-db-01", "labctl", "db", "read-only", "on")
        assert lab.exec("lab-db-01", "labctl", "db", "read-only").stdout == "on\n"
        assert wait_for(lambda: all(lab.db_counts()[1][s] > errors[s] for s in SCHEMAS), 10)
    finally:
        lab.exec("lab-db-01", "labctl", "db", "read-only", "off")

    def stopped():
        first = lab.db_counts()[1]
        time.sleep(5)
        return lab.db_counts()[1] == first

    assert wait_for(stopped, 30)

    def last_result():
        return lab.exec("lab-rpt-01", "cat", "/var/lib/lab/status/report_export_last_result").stdout

    try:
        lab.exec("lab-rpt-01", "labctl", "report", "fail")
        assert wait_for(lambda: last_result() == "0\n", 2, interval=0.2)
    finally:
        lab.exec("lab-rpt-01", "labctl", "report", "succeed")
    assert wait_for(lambda: last_result() == "1\n", 2, interval=0.2)
    assert lab.exec("lab-rpt-01", "labctl", "report").stdout == "succeed\n"


def test_image():
    """BP-R-04 demo-lab image: pinned base images, prometheus-client version, no build CA file, http sources."""
    run(["docker", "image", "inspect", IMAGE])
    froms = re.findall(r"^FROM (\S+)", (ROOT / "demo/lab/Dockerfile").read_text(), re.MULTILINE)
    assert len(froms) == 2
    assert re.fullmatch(r"zabbix/zabbix-agent2:ubuntu-7\.0\.\d+@sha256:[0-9a-f]{64}", froms[0])
    assert re.fullmatch(r"ubuntu:24\.04@sha256:[0-9a-f]{64}", froms[1])
    required = (ROOT / "demo/lab/requirements.txt").read_text().splitlines()
    assert len(required) == 1 and required[0].startswith("prometheus-client==")
    frozen = run(["docker", "run", "--rm", "--entrypoint", "/opt/venv/bin/pip", IMAGE, "freeze"]).stdout
    versions = {re.sub(r"[-_.]+", "-", line.split("==")[0]).lower(): line.split("==")[1] for line in frozen.split()}
    assert versions["prometheus-client"] == required[0].split("==")[1]
    script = "test ! -e /etc/apt/apt.conf.d/99vigil-build-ca && grep '^URIs:' /etc/apt/sources.list.d/ubuntu.sources"
    uris = run(["docker", "run", "--rm", "--entrypoint", "sh", IMAGE, "-c", script]).stdout.split()
    assert [uri for uri in uris if uri != "URIs:"] and all(uri.startswith("http://") for uri in uris if uri != "URIs:")


def test_interfaces(lab):
    """DD-F-10 (interfaces) Zabbix agent 2 items, the node exporter's /data metrics and the mocks' metric labels."""
    for item in ("vfs.fs.size[/data,free]", "vfs.file.contents[/var/lib/lab/status/db_write_errors_bundle_orders]"):
        output = lab.exec("lab-db-01", "zabbix_agent2", "-t", item).stdout
        assert re.search(r"\[s\|\d+\]", output), output
    for host in ("lab-db-01", "lab-vas-01", "lab-rpt-01"):
        assert lab.exec(host, "pgrep", "-x", "zabbix_agent2").returncode == 0
        assert ":10050 " in lab.exec(host, "ss", "-ltn").stdout
    node = parse_metrics(lab.fetch("http://lab-db-01:9100/metrics"))
    for name in ("node_filesystem_avail_bytes", "node_filesystem_size_bytes"):
        assert any(("mountpoint", "/data") in labels for labels in node[name])

    metrics = lab.metrics()
    balance = ("/v1/balance", "/v1/balance/main", "/v1/balance/bundles", "/v1/balance/history")
    bundle = ("/v1/bundles/offers", "/v1/bundles/purchase", "/v1/bundles/active", "/v1/bundles/renew")
    api_labels = {
        frozenset({("api", api), ("instance", f"api-{api}-{index}"), ("endpoint", endpoint)})
        for api, endpoints in (("balance", balance), ("bundle", bundle))
        for index in range(1, 5)
        for endpoint in endpoints
    }
    assert labels_of(metrics["lab_api_requests_total"]) == api_labels
    assert labels_of(metrics["lab_api_errors_total"]) == api_labels
    menus = ("balance", "data-bundles", "voice-bundles", "sms-bundles", "my-bundles", "bundle-status")
    ussd_labels = {frozenset({("node", node), ("menu", menu)}) for node in ("ussd-gw-a", "ussd-gw-b") for menu in menus}
    assert labels_of(metrics["lab_ussd_sessions_total"]) == ussd_labels
    assert labels_of(metrics["lab_ussd_timeouts_total"]) == ussd_labels
    cities = ("karachi", "lahore", "islamabad", "rawalpindi", "faisalabad")
    cities += ("multan", "peshawar", "quetta", "hyderabad", "sialkot")
    journeys = ("balance-check", "bundle-list", "bundle-purchase", "bundle-status", "sms-confirmation")
    probe_labels = {frozenset({("city", city), ("journey", journey)}) for city in cities for journey in journeys}
    assert labels_of(metrics["lab_probe_last_result"]) == probe_labels
