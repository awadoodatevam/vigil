"""Tests of the demo-host tooling (P-32 spec Section 7; P-34 spec Section 7: assets-gzip, series.sh, series.py,
rehearsal.py): the scripts run in a temporary copy of the repository with the
fakes of deploy/tests (the AWS CLI, whose ssm send-command runs the host part locally, docker and curl), a docker stub
of their own where a container must leave a file, stubs of the host's tools, and an in-process control panel."""

import importlib.util
import json
import os
import shutil
import stat
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent.parent
_spec = importlib.util.spec_from_file_location("deploy_harness", ROOT / "deploy" / "conftest.py")
deploy_harness = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(deploy_harness)

MAIN = {"i-0main": {"Name": "vigil-demo-main", "State": "running"}}
SECRETS = [
    "seed-password-presenter",
    "seed-password-noc-l1",
    "seed-password-dbops-engineer",
    "seed-password-dbops-lead",
    "seed-password-admin",
    "control-password",
    "jira",
]
DOCKER_STUB = """#!/usr/bin/env python3
# A docker stub for storm.sh: records its arguments; a Playwright run leaves its suite's marker (and the suite live its
# AR-SP-01.json) in the run folder; every call answers 0.
import json, os, sys, time
arguments = sys.argv[1:]
with open(os.environ["STUB_LOG"], "a") as log:
    log.write(json.dumps({"args": arguments, "at": time.time()}) + "\\n")
if arguments[:1] == ["run"]:
    run = next((a.split(":")[0] for a in arguments if a.endswith(":/e2e/run")), None)
    if run and "@storm" in arguments:
        open(os.path.join(run, "storm-ready"), "w").write("x")
        time.sleep(0.5)
    if run and "@live" in arguments:
        open(os.path.join(run, "live-ready"), "w").write("x")
        time.sleep(0.5)
        open(os.path.join(run, "AR-SP-01.json"), "w").write(json.dumps({"passed": True}))
"""


@pytest.fixture
def harness(tmp_path):
    harness = deploy_harness.Harness(tmp_path)
    target = harness.root / "tests" / "acceptance" / "demo-host"
    target.mkdir(parents=True)
    for path in HERE.glob("*.sh"):
        shutil.copy2(path, target / path.name)
    (harness.root / "tests" / "e2e").mkdir()
    (harness.base / "home").mkdir()
    harness.home = harness.base / "home"
    return harness


def run(harness, script, *arguments, **extra):
    extra.setdefault("HOME", str(harness.home))
    return harness.run(f"tests/acceptance/demo-host/{script}", *arguments, **extra)


def write_secrets(harness):
    folder = harness.home / ".vigil-demo" / "demo" / "secrets"
    folder.mkdir(parents=True)
    for name in SECRETS:
        (folder / name).write_text(f"value-of-{name}")
    return folder


def tool_stubs(harness, tools):
    """A folder of executable stubs {name: script} placed before the fakes on PATH."""
    folder = harness.base / "stubs"
    folder.mkdir(exist_ok=True)
    for name, text in tools.items():
        (folder / name).write_text(text)
        (folder / name).chmod(0o755)
    return str(folder) + os.pathsep + deploy_harness.search_path()


# e2e.sh


def test_e2e_urls(harness):
    """BP-R-24 e2e.sh passes the three URLs and leaves out @local, @storm, @control, @live."""
    write_secrets(harness)
    result = run(harness, "e2e.sh", "demo")
    assert result.returncode == 0, result.stdout + result.stderr
    call = [c["args"] for c in harness.fake_calls("docker") if c["args"][0] == "run"][0]
    base = harness.home / ".vigil-demo" / "demo"
    assert call == [
        "run", "--rm", "--name", "vigil-demo-e2e-default", "--user", f"{os.getuid()}:{os.getgid()}",
        "-e", "VIGIL_BASE_URL=https://demo.vigil.solviclabs.com",
        "-e", "VIGIL_CONTROL_URL=https://control.vigil.solviclabs.com",
        "-e", "VIGIL_JIRA_URL=https://techfinity-noc.atlassian.net",
        "-v", f"{harness.root}/tests/e2e:/e2e/tests:ro", "--tmpfs", "/e2e/tests/node_modules",
        "-v", f"{base}/e2e:/e2e/run", "-v", f"{base}/secrets:/run/secrets/vigil:ro",
        "-v", f"{base}/state:/run/state:ro", "vigil/e2e:local",
        "/e2e/node_modules/.bin/playwright", "test", "-c", "/e2e/tests/playwright.config.ts",
        "--grep-invert", "@local|@ai-down|@storm|@control|@live",
    ]  # fmt: skip
    assert (base / "state" / "lab-profile").read_text() == "demo\n"

    harness.log_path.write_text("")
    result = run(harness, "e2e.sh", "demo", "control")
    assert result.returncode == 0, result.stdout + result.stderr
    call = [c["args"] for c in harness.fake_calls("docker") if c["args"][0] == "run"][0]
    assert call[-2:] == ["--grep", "@control"] and call[3] == "vigil-demo-e2e-control"
    harness.log_path.write_text("")
    run(harness, "e2e.sh", "demo", "live")
    call = [c["args"] for c in harness.fake_calls("docker") if c["args"][0] == "run"][0]
    assert f"{base}/e2e-live:/e2e/run" in call

    shutil.rmtree(base / "secrets")
    result = run(harness, "e2e.sh", "demo")
    assert result.returncode == 1
    assert result.stdout.strip() == "Run tests/acceptance/demo-host/secrets.sh demo first."
    for arguments in ([], ["unknown"], ["demo", "Bad"], ["demo", "a", "b"]):
        assert run(harness, "e2e.sh", *arguments).returncode == 2


# secrets.sh


def test_secrets(harness):
    """SL-B-14 secrets.sh writes the seven secrets with BP-R-26's modes and never prints a value."""
    values = {name: f"secret-{name}-{os.urandom(8).hex()}" for name in SECRETS}
    values["jira"] = json.dumps({"email": "x@y.z", "api_token": os.urandom(8).hex(), "expires_on": "2027-01-01"})
    parameters = {f"/vigil/demo/{name}": {"Value": value, "Type": "SecureString"} for name, value in values.items()}
    harness.seed(parameters=parameters)
    result = run(harness, "secrets.sh", "demo")
    assert result.returncode == 0, result.stdout + result.stderr
    folder = harness.home / ".vigil-demo" / "demo" / "secrets"
    assert sorted(p.name for p in folder.iterdir()) == sorted(SECRETS)
    for name, value in values.items():
        assert (folder / name).read_text() == value
        assert stat.S_IMODE((folder / name).stat().st_mode) == 0o444
        assert value not in result.stdout + result.stderr
    assert stat.S_IMODE(folder.stat().st_mode) == 0o555
    assert result.stdout.splitlines() == [f"secrets in {folder}:"] + [f"  {name}" for name in SECRETS]
    gets = harness.aws_calls("ssm", "get-parameter")
    assert all("--with-decryption" in call["args"] for call in gets) and len(gets) == 7

    result = run(harness, "secrets.sh", "demo")
    assert result.returncode == 0, result.stdout + result.stderr

    harness.seed(parameters={k: v for k, v in parameters.items() if not k.endswith("/jira")})
    result = run(harness, "secrets.sh", "demo")
    assert result.returncode == 1 and result.stdout.splitlines()[-1] == "Missing secret /vigil/demo/jira (AR-S-13)."

    result = run(harness, "secrets.sh", "demo", "--remove")
    assert result.returncode == 0 and not folder.exists()
    assert result.stdout.strip() == f"removed {folder}"
    assert run(harness, "secrets.sh", "demo", "--other").returncode == 2


# storm.sh


def test_storm_starts_the_suites_first(harness, tmp_path):
    """SB-T-02 storm.sh starts the suites before the driver."""
    write_secrets(harness)
    spike = tmp_path / "storm-timing-20261120T000000Z.json"
    spike.write_text("{}")
    log = harness.base / "stub.log"
    log.write_text("")
    path = tool_stubs(harness, {"docker": DOCKER_STUB})
    result = run(
        harness,
        "storm.sh",
        "demo",
        "--mode",
        "script",
        "--with-playwright",
        "--live",
        "--measured",
        str(spike),
        PATH=path,
        STUB_LOG=str(log),
    )
    assert result.returncode == 0, result.stdout + result.stderr
    runs = [json.loads(line)["args"] for line in log.read_text().splitlines() if json.loads(line)["args"][0] == "run"]
    assert len(runs) == 3
    suites, driver = runs[:2], runs[2]
    assert sorted(r[r.index("--grep") + 1] for r in suites) == ["@live", "@storm"]
    assert driver[driver.index("vigil/storm-driver:local") + 1 :] == [
        "--mode", "script", "--deliveries", "checklist", "--acts", "driver", "--via", "control",
        "--measured", f"/measured/{spike.name}",
    ]  # fmt: skip
    secrets = harness.home / ".vigil-demo" / "demo" / "secrets"
    for mount in (
        f"{secrets}:/run/secrets/e2e:ro",
        f"{secrets}/control-password:/run/secrets/demo-control/control-password:ro",
        f"{secrets}/jira:/run/secrets/vigil-app/jira:ro",
        f"{harness.root}/docs/31-demo-data:/demo-data:ro",
        f"{harness.root}/tests/acceptance/results:/results",
        f"{spike}:/measured/{spike.name}:ro",
    ):
        assert mount in driver, mount
    for variable in (
        "VIGIL_BASE_URL=https://demo.vigil.solviclabs.com",
        "VIGIL_CONTROL_URL=https://control.vigil.solviclabs.com",
        "VIGIL_JIRA_URL=https://techfinity-noc.atlassian.net",
        "VIGIL_LAB_PROFILE=demo",
    ):
        assert variable in driver
    base = harness.home / ".vigil-demo" / "demo"
    assert (base / "e2e" / "storm-mode").read_text() == "script\n"
    assert json.loads((harness.root / "spikes" / "results" / "P-32-AR-SP-01.json").read_text()) == {"passed": True}

    result = run(harness, "storm.sh", "demo", "--mode", "fast")
    assert result.returncode == 2
    result = run(
        harness, "storm.sh", "demo", "--measured", str(tmp_path / "missing.json"), PATH=path, STUB_LOG=str(log)
    )
    assert result.returncode == 1 and result.stdout.strip() == f"No file {tmp_path / 'missing.json'}."


# ai-cut.sh


class Control:
    """The control panel's sign-in and state for ai-cut.sh."""

    def __init__(self, active):
        self.active = active
        self.calls = []
        control = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
                control.calls.append(("POST", self.path))
                ok = body == {"username": "operator", "password": "value-of-control-password"}
                self.send_response(204 if ok else 401)
                if ok:
                    self.send_header("Set-Cookie", "control_session=s; Path=/; HttpOnly; Secure")
                self.send_header("Content-Length", "0")
                self.end_headers()

            def do_GET(self):
                control.calls.append(("GET", self.path))
                ok = self.headers.get("Cookie") == "control_session=s"
                data = json.dumps({"active": [{"fault": f} for f in control.active]}).encode() if ok else b"{}"
                self.send_response(200 if ok else 401)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"


def cut_log(change=None, rounds=20):
    lines = []
    for number in range(1, rounds + 1):
        for kind, label in (
            ("claude", "api.anthropic.com:443"),
            ("openai", "api.openai.com:443"),
            ("jira", "techfinity-noc.atlassian.net:443"),
            ("public-name", "demo.vigil.solviclabs.com:443"),
            ("teams", "Teams webhook host:443"),
            ("ses", "email-smtp.us-east-1.amazonaws.com:587"),
        ):
            outcome = "failed TimeoutError: timed out" if kind in ("claude", "openai") else "connected"
            seconds = "5.00" if kind in ("claude", "openai") else "0.31"
            if change and change[0] == number and change[1] == kind:
                outcome = change[2]
            lines.append(f"round {number} 10:{number:02d}:00Z {kind} {label} {seconds} s {outcome}")
    return "\n".join(lines) + "\n"


def test_ai_cut_verdicts(harness):
    """SB-T-09 ai-cut.sh's verdicts on a fake log."""
    write_secrets(harness)
    harness.seed(instances=MAIN)
    teams = harness.vigil_root / "secrets" / "vigil-app" / "teams-team-noc-l1"
    teams.parent.mkdir(parents=True)
    teams.write_text("https://teams-host-not-to-print.example/workflows/abc")
    control = Control(["SL-F-4"])
    image = f"{deploy_harness.REGISTRY}/demo/demo-faults:p32-1"
    try:
        cases = [
            (None, 20, 0, ["PASS SB-T-09 part one"]),
            ((7, "claude", "connected"), 20, 1, ["FAIL SB-T-09 claude"]),
            ((3, "ses", "failed SMTPServerDisconnected: x"), 20, 1, ["FAIL SB-T-09 ses"]),
            ((2, "teams", "failed TimeoutError"), 20, 1, ["FAIL SB-T-09 teams"]),
            (None, 19, 1, [f"FAIL SB-T-09 {k}" for k in ("claude", "openai", "jira", "public-name", "teams", "ses")]),
        ]
        for change, rounds, code, verdicts in cases:
            harness.log_path.write_text("")
            harness.table(
                [
                    ("docker", r"^inspect --format \{\{\.Config\.Image\}\} demo-faults$", [(image + "\n", 0)]),
                    ("docker", r"^run --rm -i --network container:vigil-app", [(cut_log(change, rounds), 0)]),
                ]
            )
            result = run(harness, "ai-cut.sh", "demo", VIGIL_CONTROL_URL=control.url)
            assert result.returncode == code, result.stdout + result.stderr
            lines = result.stdout.splitlines()
            assert lines[-len(verdicts) :] == verdicts
            assert "teams-host-not-to-print" not in result.stdout + result.stderr
        call = [c["args"] for c in harness.fake_calls("docker") if c["args"][0] == "run"][0]
        assert call[:8] == ["run", "--rm", "-i", "--network", "container:vigil-app", "--entrypoint", "/usr/bin/python3",
                            image]  # fmt: skip
        assert call[-2:] == ["demo.vigil.solviclabs.com", "techfinity-noc.atlassian.net"]
        assert not any("teams-host" in argument for argument in call)

        control.active = ["SL-F-1"]
        harness.log_path.write_text("")
        result = run(harness, "ai-cut.sh", "demo", VIGIL_CONTROL_URL=control.url)
        assert result.returncode == 1
        assert result.stdout.strip() == (
            'SL-F-4 is not active: press "Cut cloud AI: SL-F-4" in the control panel first.'
        )
        assert harness.aws_calls("ssm", "send-command") == [] or harness.fake_calls("docker") == []
    finally:
        control.server.shutdown()


# sample.sh

FETCH_STUB = """#!/usr/bin/env bash
# Stub of deploy/fetch.sh: copies the host file of the test's VIGIL_ROOT.
cp "$VIGIL_ROOT${2#/srv/vigil}" "$3"
"""


def driver_result(path, t0, times):
    path.write_text(json.dumps({"t0": t0, "finished_at": "2026-11-20T11:00:00.000Z", "times": times}))
    return path


def test_sample_criteria(harness, tmp_path):
    """AR-SP-03 sample.sh's criteria on a fake CSV and result files."""
    harness.seed(instances=MAIN)
    (harness.root / "deploy" / "fetch.sh").write_text(FETCH_STUB)
    logs = harness.vigil_root / "logs"
    logs.mkdir()
    rows = ["time,cpu_pct,memory_pct"]
    for storm in range(3):
        for second in range(0, 600, 10):
            cpu = 81.0 if (storm, second) == (1, 300) else 40.0 + second / 100
            rows.append(f"2026-11-20T1{storm}:{second // 60:02d}:{second % 60:02d}Z,{cpu},{55.5}")
    (logs / "sample.csv").write_text("\n".join(rows) + "\n")
    good = {"SB-TL-01": 100.0, "SB-TL-02": 141.5, "SB-TL-03": 150.0, "SB-TL-04": 200.0, "SB-TL-10": 590.0}
    files = [driver_result(tmp_path / f"storm-script-{n}.json", f"2026-11-20T1{n}:00:00.000Z", good) for n in range(3)]
    result = run(harness, "sample.sh", "demo", "stop", *map(str, files))
    report = json.loads((harness.root / "spikes" / "results" / "P-32-AR-SP-03.json").read_text())
    assert report["criteria"] == {
        "cpu_80": False,
        "memory_85": True,
        "sb_tl_02": True,
        "sb_tl_03": True,
        "sb_tl_04": True,
        "storms_3": True,
    }
    assert result.returncode == 1 and report["passed"] is False
    assert [s["cpu_max_pct"] for s in report["storms"]] == [45.9, 81.0, 45.9]
    assert [s["samples"] for s in report["storms"]] == [60, 60, 60]
    assert report["storms"][0]["SB-TL-03"] == 150.0 and report["storms"][0]["end_after_t0_s"] == 590.0
    assert "FAIL cpu_80" in result.stdout.splitlines()

    slow = dict(good, **{"SB-TL-03": 160.0, "SB-TL-04": None})
    files[1] = driver_result(files[1], "2026-11-20T11:00:00.000Z", slow)
    (logs / "sample.csv").write_text("\n".join(r.replace(",81.0,", ",50.0,") for r in rows) + "\n")
    result = run(harness, "sample.sh", "demo", "stop", *map(str, files[:2]))
    report = json.loads((harness.root / "spikes" / "results" / "P-32-AR-SP-03.json").read_text())
    assert report["criteria"] == {
        "cpu_80": True,
        "memory_85": True,
        "sb_tl_02": True,
        "sb_tl_03": False,
        "sb_tl_04": False,
        "storms_3": False,
    }
    assert result.returncode == 1
    assert run(harness, "sample.sh", "demo", "stop").returncode == 2
    assert run(harness, "sample.sh", "demo", "start", "x").returncode == 2


def test_sampler_on_the_host(harness):
    """AR-SP-03 sample-host.sh appends the UTC time, CPU and memory percentages every 10 s, and stops."""
    result = harness.run("tests/acceptance/demo-host/sample-host.sh", "start")
    assert result.returncode == 0, result.stdout + result.stderr
    pid = int((harness.vigil_root / "logs" / "sample.pid").read_text())
    try:
        os.kill(pid, 0)
        assert (harness.vigil_root / "logs" / "sample.csv").read_text() == "time,cpu_pct,memory_pct\n"
        again = harness.run("tests/acceptance/demo-host/sample-host.sh", "start")
        assert again.returncode == 1 and again.stdout.startswith("The sampler runs already")
    finally:
        result = harness.run("tests/acceptance/demo-host/sample-host.sh", "stop")
    assert result.stdout.strip() == "sampler stopped"
    assert not (harness.vigil_root / "logs" / "sample.pid").exists()


# check.sh

HEALTH = '{"status":"UP","components":{"correlation":{"status":"UP"},"db":{"status":"UP"}}}'
UNITS = [
    "vigil-app",
    "demo-control",
    "demo-faults",
    "vigil-db",
    "lab-zabbix-db",
    "lab-zabbix-server",
    "lab-zabbix-web",
    "lab-prometheus",
    "lab-alertmanager",
    "lab-db-01",
]
SERVICES = UNITS + ["lab-api-01", "lab-vas-01", "lab-probe-01", "lab-rpt-01", "vigil-embed", "vigil-migrate",
                    "lab-zabbix-provision"]  # fmt: skip
HOST_TOOLS = {
    "chronyc": "#!/bin/sh\necho 'Reference ID    : A9FEA97B (169.254.169.123)'\n",
    "timedatectl": "#!/bin/sh\necho '                Time zone: Etc/UTC (UTC, +0000)'\n",
    "iptables": "#!/bin/sh\necho '-N DOCKER-USER'\n"
    "echo '-A DOCKER-USER -s 172.31.0.0/24 -d 172.30.0.10/32 -m conntrack --ctstate NEW -j DROP'\n",
    "tls-curl": "#!/bin/sh\necho 'curl: (35) error:0A00042E:SSL routines::tlsv1 alert protocol version' >&2\nexit 35\n",
}


PAGE = '<html><script type="module" crossorigin src="/assets/index-AbC123.js"></script></html>'
ZIPPED = (
    "HTTP/2 200 \r\ncontent-type: text/javascript\r\ncache-control: max-age=31536000, immutable\r\n"
    "content-encoding: gzip\r\nvary: accept-encoding\r\n\r\nsize 98765\n"
)
PLAIN = (
    "HTTP/2 200 \r\ncontent-type: text/javascript\r\ncache-control: max-age=31536000, immutable\r\n\r\nsize 321456\n"
)


def check_table(harness, changes=None):
    changes = changes or {}
    lb = "vigil-alb-1.us-east-1.elb.amazonaws.com"
    ports = changes.get("ports", "vigil-app\t10.40.1.10:8080->8080/tcp\ndemo-control\t10.40.1.10:8090->8090/tcp\n"
                                 "vigil-db\t5432/tcp\ndemo-faults\t\n")  # fmt: skip
    readable = "".join(f"{unit}: secrets readable\n" for unit in UNITS)
    harness.table(
        [
            ("docker", r"^version --format", [("28.2.2\n", 0)]),
            ("docker", r"^compose version --short$", [("2.40.3\n", 0)]),
            ("docker", r"^compose -p vigil ps --all", [("NAME STATE STATUS\nvigil-app running Up\n", 0)]),
            ("docker", r"^ps --filter label=com\.docker\.compose\.project=vigil", [("\n".join(UNITS) + "\n", 0)]),
            ("docker", r"^exec \S+ sh -c", [(changes.get("readable", ""), 0)]),
            ("docker", r"^ps --format", [(ports, 0)]),
            (
                "docker",
                r"(?s)^exec lab-api-01 python3 -c .*172\.30\.0\.10",
                [(changes.get("lab", "lab-to-app blocked after 5.00 s: timed out\n"), 0)],
            ),  # fmt: skip
            ("docker", r"^exec lab-api-01 python3 -c ", [(changes.get("public", "lab-public-name 200\n"), 0)]),
            (
                "docker",
                r"^compose -p vigil -f \S+ --env-file \S+ config --services$",
                [("\n".join(SERVICES) + "\n", 0)],
            ),
            ("curl", r"http://172\.30\.0\.10:8080/actuator/health", [(changes.get("health", HEALTH), 0)]),
            ("curl", r"/actuator/health$", [("status 404\n", 0)]),
            (
                "curl",
                r"http://demo\.",
                [
                    (
                        "HTTP/1.1 301 Moved Permanently\r\nLocation: https://demo.vigil.solviclabs.com:443/\r\n"
                        "\r\nstatus 301\n",
                        0,
                    )
                ],
            ),  # fmt: skip
            # assets-gzip (P-34): the page, then the script with and without Accept-Encoding: gzip.
            ("curl", r"^-sS https://demo\.vigil\.solviclabs\.com/login$", [(changes.get("page", PAGE), 0)]),
            (
                "curl",
                r"Accept-Encoding: gzip https://demo\.vigil\.solviclabs\.com/assets/",
                [(changes.get("zipped", ZIPPED), 0)],
            ),
            (
                "curl",
                r"size_download\}\\n https://demo\.vigil\.solviclabs\.com/assets/",
                [(changes.get("plain", PLAIN), 0)],
            ),
            ("curl", r"https://demo\.vigil\.solviclabs\.com/login$", [("status 200\n", 0)]),
            ("curl", r"https://control\.", [("<html><title>Demo control panel</title></html>\nstatus 200\n", 0)]),
            ("curl", r"https://" + lb.replace(".", r"\."), [("status 404\n", 0)]),
        ]
    )
    harness.seed(
        instances=MAIN,
        load_balancers={"vigil-alb": lb},
        target_groups={"vigil-app": ["healthy"], "demo-control": changes.get("control_targets", ["healthy"])},
    )
    deploy = harness.vigil_root / "deploy"
    deploy.mkdir(exist_ok=True)
    shutil.copy2(ROOT / "deploy" / "units.yaml", deploy / "units.yaml")
    (deploy / ".env").write_text("VIGIL_RELEASE=p32-1\nCOMPOSE_PROFILES=vigil\n")
    listed = "".join(f'  {s}: "x@sha256:1"\n' for s in SERVICES if s not in ("vigil-migrate", "lab-zabbix-provision"))
    (deploy / "deployed.yaml").write_text('release: "p32-1"\ndeployed_at: "x"\nimages:\n' + listed)
    (harness.vigil_root / "bootstrap-done").write_text("bootstrap main\n")
    return readable


def test_check_verdicts(harness):
    """AR-S-08 check.sh's verdicts on fake outputs; lab-public-name passes on 200 and on 404 unknown_host."""
    tools = dict(HOST_TOOLS)
    path = tool_stubs(harness, tools)
    environment = {"PATH": path, "VIGIL_TLS_CURL": str(harness.base / "stubs" / "tls-curl")}
    names = ["http-redirect", "https-demo", "assets-gzip", "actuator-404", "https-control", "unknown-host-404",
             "tls-1.1-refused", "targets-healthy", "published-ports", "DOCKER-USER", "lab-to-app-blocked",
             "lab-public-name", "secrets-readable", "correlation", "clock", "release"]  # fmt: skip
    readable = check_table(harness)
    # host-status.sh checks each unit with docker exec; the fake answers 0 for each, so every unit is readable.
    result = run(harness, "check.sh", "demo", **environment)
    lines = result.stdout.splitlines()
    assert lines[-16:] == [f"PASS {name}" for name in names], result.stdout + result.stderr
    assert result.returncode == 0
    assert readable and all(f"{unit}: secrets readable" in lines for unit in UNITS)
    for part in ("== date", "== status", "== load-balancer", "== main-host", "== https"):
        assert part in lines

    for changes, failing in (
        ({"lab": "lab-to-app opened after 0.01 s\n"}, "lab-to-app-blocked"),
        (
            {
                "ports": "vigil-app\t10.40.1.10:8080->8080/tcp\ndemo-control\t10.40.1.10:8090->8090/tcp\n"
                "lab-zabbix-web\t0.0.0.0:8081->8080/tcp\n"
            },
            "published-ports",
        ),  # fmt: skip
        ({"health": HEALTH.replace('"correlation":{"status":"UP"}', '"correlation":{"status":"DOWN"}')}, "correlation"),
        ({"control_targets": ["unhealthy"]}, "targets-healthy"),
        ({"public": "lab-public-name 404 \n"}, "lab-public-name"),
        ({"public": "lab-public-name 502 \n"}, "lab-public-name"),
        ({"public": "lab-public-name failed: timed out\n"}, "lab-public-name"),
    ):
        harness.log_path.write_text("")
        check_table(harness, changes)
        result = run(harness, "check.sh", "demo", **environment)
        assert result.returncode == 1
        verdicts = result.stdout.splitlines()[-16:]
        assert [v for v in verdicts if v.startswith("FAIL")] == [f"FAIL {failing}"], verdicts

    # lab-public-name passes on 404 unknown_host: Vigil answered through the public name before any tenant exists.
    harness.log_path.write_text("")
    check_table(harness, {"public": "lab-public-name 404 unknown_host\n"})
    result = run(harness, "check.sh", "demo", **environment)
    assert result.returncode == 0, result.stdout
    assert "PASS lab-public-name" in result.stdout.splitlines()


def test_sample_paths_with_spaces(harness, tmp_path):
    """AR-SP-03 sample.sh stop passes driver result files whose path holds a space as one argument each."""
    harness.seed(instances=MAIN)
    (harness.root / "deploy" / "fetch.sh").write_text(FETCH_STUB)
    logs = harness.vigil_root / "logs"
    logs.mkdir()
    rows = ["time,cpu_pct,memory_pct"] + [f"2026-11-20T10:{m:02d}:00Z,30.0,40.0" for m in range(10)]
    (logs / "sample.csv").write_text("\n".join(rows) + "\n")
    folder = tmp_path / "Run results"
    folder.mkdir()
    times = {"SB-TL-01": 100.0, "SB-TL-02": 141.5, "SB-TL-03": 150.0, "SB-TL-04": 200.0, "SB-TL-10": 590.0}
    files = [driver_result(folder / f"storm {n}.json", "2026-11-20T10:00:00.000Z", times) for n in range(3)]
    result = run(harness, "sample.sh", "demo", "stop", *map(str, files))
    report = json.loads((harness.root / "spikes" / "results" / "P-32-AR-SP-03.json").read_text())
    assert [s["result_file"] for s in report["storms"]] == ["storm 0.json", "storm 1.json", "storm 2.json"]
    assert report["passed"] is True and result.returncode == 0, result.stdout + result.stderr


def test_check_assets_gzip(harness):
    """AR-W-01 check.sh's assets-gzip check on fake outputs: gzip, the year's cache and a smaller size pass."""
    path = tool_stubs(harness, dict(HOST_TOOLS))
    environment = {"PATH": path, "VIGIL_TLS_CURL": str(harness.base / "stubs" / "tls-curl")}
    check_table(harness)
    result = run(harness, "check.sh", "demo", **environment)
    assert result.returncode == 0, result.stdout + result.stderr
    lines = result.stdout.splitlines()
    assert "PASS assets-gzip" in lines
    assert "asset /assets/index-AbC123.js" in lines and "size 98765" in lines and "size 321456" in lines
    calls = [c["args"] for c in harness.fake_calls("curl") if any("/assets/" in a for a in c["args"])]
    assert calls == [
        ["-sS", "-o", "/dev/null", "-D", "-", "-w", "size %{size_download}\\n", "-H", "Accept-Encoding: gzip",
         "https://demo.vigil.solviclabs.com/assets/index-AbC123.js"],
        ["-sS", "-o", "/dev/null", "-D", "-", "-w", "size %{size_download}\\n",
         "https://demo.vigil.solviclabs.com/assets/index-AbC123.js"],
    ]  # fmt: skip
    for changes in (
        {"zipped": ZIPPED.replace("content-encoding: gzip\r\n", "")},
        {"zipped": ZIPPED.replace("max-age=31536000, immutable", "no-cache")},
        {"zipped": ZIPPED.replace("size 98765", "size 321456")},
        {"page": "<html></html>"},
        {"plain": "curl: (6) Could not resolve host\n"},
    ):
        harness.log_path.write_text("")
        check_table(harness, changes)
        result = run(harness, "check.sh", "demo", **environment)
        assert result.returncode == 1, changes
        verdicts = result.stdout.splitlines()[-16:]
        assert [v for v in verdicts if v.startswith("FAIL")] == ["FAIL assets-gzip"], (changes, verdicts)
    harness.log_path.write_text("")
    check_table(
        harness,
        {
            "zipped": ZIPPED.replace("content-encoding:", "Content-Encoding:").replace(
                "cache-control:", "Cache-CONTROL:"
            )
        },
    )
    result = run(harness, "check.sh", "demo", **environment)
    assert "PASS assets-gzip" in result.stdout.splitlines(), "header names in any case"


# series.sh

FAKE_STORM = """#!/usr/bin/env bash
# A fake storm.sh: records its arguments, leaves a result file and a checklist (the run's own, or none when the run's
# number is in FAKE_NO_FILES), the suite's screens and results, and exits with the run's code of FAKE_CODES.
set -euo pipefail
n=$(( $(wc -l < "$FAKE_STORM_LOG") + 1 ))
printf '%s\\n' "$*" >> "$FAKE_STORM_LOG"
case " ${FAKE_NO_FILES:-} " in
  *" $n "*) ;;
  *)
    echo "{\\"run\\": $n}" > "tests/acceptance/results/storm-script-2026112${n}T100000Z.json"
    echo "- [ ] run $n" > "tests/acceptance/results/storm-checklist-2026112${n}T101500Z.md"
    ;;
esac
mkdir -p "$HOME/.vigil-demo/demo/e2e/screens" "$HOME/.vigil-demo/demo/e2e/results"
echo "run $n" > "$HOME/.vigil-demo/demo/e2e/screens/run-$n.png"
echo "trace $n" > "$HOME/.vigil-demo/demo/e2e/results/trace.zip"
code=$(echo "${FAKE_CODES:-}" | cut -d, -f"$n")
exit "${code:-0}"
"""


def series_harness(harness):
    (harness.root / "tests" / "acceptance" / "demo-host" / "storm.sh").write_text(FAKE_STORM)
    results = harness.root / "tests" / "acceptance" / "results"
    results.mkdir(parents=True, exist_ok=True)
    (results / "storm-script-20261101T000000Z.json").write_text("{}")
    log = harness.base / "storm.log"
    log.write_text("")
    return results, log


def test_series_runs(harness, tmp_path):
    """SB-T-02 series.sh runs storm.sh five times in mode script with the suite storm, keeps going after a failed run,
    and writes the manifest"""
    results, log = series_harness(harness)
    spike = tmp_path / "storm-timing-20261125T111259Z.json"
    spike.write_text("{}")
    environment = {"FAKE_STORM_LOG": str(log), "FAKE_CODES": "0,1,0,0,0", "FAKE_NO_FILES": "4"}
    result = run(harness, "series.sh", "demo", "--measured", str(spike), **environment)
    assert result.returncode == 1, result.stdout + result.stderr
    assert log.read_text().splitlines() == [f"demo --mode script --with-playwright --measured {spike}"] * 5
    manifests = sorted(results.glob("P-34-series-*.txt"))
    assert len(manifests) == 1
    stamp = manifests[0].name[len("P-34-series-") : -len(".txt")]
    assert result.stdout.splitlines()[-1] == f"manifest tests/acceptance/results/{manifests[0].name}"
    runs = [line for line in manifests[0].read_text().splitlines() if not line.startswith("#")]
    assert runs == [
        "run 1 exit 0 result storm-script-20261121T100000Z.json checklist storm-checklist-20261121T101500Z.md",
        "run 2 exit 1 result storm-script-20261122T100000Z.json checklist storm-checklist-20261122T101500Z.md",
        "run 3 exit 0 result storm-script-20261123T100000Z.json checklist storm-checklist-20261123T101500Z.md",
        "run 4 exit 0 result none checklist none",
        "run 5 exit 0 result storm-script-20261125T100000Z.json checklist storm-checklist-20261125T101500Z.md",
    ]
    series = harness.home / ".vigil-demo" / "demo" / "series" / stamp
    for k in range(1, 6):
        assert sorted(p.name for p in (series / f"run-{k}" / "screens").iterdir()) == [f"run-{k}.png"]
        assert (series / f"run-{k}" / "results" / "trace.zip").read_text() == f"trace {k}\n"

    log.write_text("")
    for path in results.glob("P-34-series-*.txt"):
        path.unlink()
    result = run(harness, "series.sh", "demo", "--runs", "2", FAKE_STORM_LOG=str(log))
    assert result.returncode == 0, result.stdout + result.stderr
    assert log.read_text().splitlines() == ["demo --mode script --with-playwright"] * 2
    for arguments in ([], ["unknown"], ["demo", "--runs", "0"], ["demo", "--runs", "x"], ["demo", "--other"]):
        assert run(harness, "series.sh", *arguments).returncode == 2, arguments
    missing = run(harness, "series.sh", "demo", "--measured", str(tmp_path / "missing.json"))
    assert missing.returncode == 1 and missing.stdout.strip() == f"No file {tmp_path / 'missing.json'}."


# series.py


def driver_run(k, cited=("RB-DB-01",), times=None, checks=None, **settings):
    result = {
        "driver": "storm",
        "mode": "script",
        "deliveries": "checklist",
        "acts": "driver",
        "profile": "demo",
        "finished": True,
        "times": times
        or {
            "SB-TL-01": 95.0,
            "SB-TL-02": 150.04,
            "SB-TL-03": 160.0,
            "SB-TL-04": 171.26,
            "SB-TL-06": 390.5,
            "SB-TL-07": None,
            "approved": 480.4,
            "SB-TL-08": 489.0,
            "SB-TL-10": 700.0,
        },  # fmt: skip
        "investigation": {"runbooks_cited": list(cited), "likely_cause": "x", "summary_length": 300 + k},
        "checks": checks
        or [
            {"id": "SB-S06", "name": "similar incident", "passed": True, "detail": "PI-1001 1.00"},
            {"id": "SB-R-06", "name": "repeatability result", "passed": True, "detail": ""},
        ],
        "passed": True,
    }
    result.update(settings)
    return result


def write_series(folder, runs, exits=None, ticks=None):
    """A manifest with its result files and checklists; runs: one driver result per run."""
    lines = ["# P-34 SB-T-02 series 20261127T090000Z: installation demo, 5 runs, measured x.json"]
    for k, result in enumerate(runs, 1):
        name, checklist = f"storm-script-2026112{k}T100000Z.json", f"storm-checklist-2026112{k}T101500Z.md"
        (folder / name).write_text(json.dumps(result))
        ticked = (ticks or {}).get(k, True)
        last = "- [x] two" if ticked else "- [ ] two"
        (folder / checklist).write_text(f"# Checklist\n\n- [x] Teams one\n{last}\n")
        lines.append(f"run {k} exit {(exits or {}).get(k, 0)} result {name} checklist {checklist}")
    manifest = folder / "P-34-series-20261127T090000Z.txt"
    manifest.write_text("\n".join(lines) + "\n")
    return manifest


def series_py(manifest):
    result = subprocess.run(
        [sys.executable, str(HERE / "series.py"), str(manifest)], capture_output=True, text=True, check=False
    )
    report = manifest.parent / "P-34-SB-T-02.json"
    return result, json.loads(report.read_text()) if report.exists() else None


def failing(report):
    return [name for name, passed in report["criteria"].items() if not passed]


def test_series_py_five_good_runs(tmp_path):
    """SB-T-02 series.py passes five good runs and fails a run whose checklist has an unticked line"""
    manifest = write_series(tmp_path, [driver_run(k) for k in range(1, 6)])
    result, report = series_py(manifest)
    assert result.returncode == 0, result.stdout + result.stderr
    assert list(report) == ["manifest", "runs", "criteria", "passed", "suite_storm_runs_passed", "summary_lengths"]
    assert report["manifest"] == manifest.name and report["passed"] is True
    assert list(report["criteria"]) == ["runs_5", "settings_5", "drivers_5", "checklists_5", "repeatability_5",
                                        "similar_5", "step_times_5", "rb_db_01_4_of_5"]  # fmt: skip
    assert result.stdout.splitlines()[:8] == [f"PASS {name}" for name in report["criteria"]]
    first = report["runs"][0]
    assert list(first) == ["run", "exit", "result", "checklist", "driver_passed", "suite_storm_passed", "settings",
                           "checklist_ticked", "repeatability", "similar", "times", "step_times", "runbooks_cited",
                           "summary_length"]  # fmt: skip
    assert first["settings"] == {"mode": "script", "deliveries": "checklist", "acts": "driver", "profile": "demo",
                                 "finished": True}  # fmt: skip
    assert first["step_times"] == {"diagnostics_s": 10.0, "ai_and_messages_s": 11.3, "action_s": 8.6}
    assert report["summary_lengths"] == [301, 302, 303, 304, 305] and report["suite_storm_runs_passed"] == 5

    manifest = write_series(tmp_path, [driver_run(k) for k in range(1, 6)], ticks={3: False})
    result, report = series_py(manifest)
    assert result.returncode == 1 and failing(report) == ["checklists_5"]
    assert "FAIL checklists_5" in result.stdout.splitlines()
    assert [r["checklist_ticked"] for r in report["runs"]] == [True, True, False, True, True]

    manifest = write_series(tmp_path, [driver_run(k) for k in range(1, 5)])
    _, report = series_py(manifest)
    assert failing(report) == ["runs_5"]
    manifest = write_series(tmp_path, [driver_run(k) for k in range(1, 5)] + [driver_run(5, passed=False)])
    _, report = series_py(manifest)
    assert failing(report) == ["drivers_5"]
    manifest = write_series(tmp_path, [driver_run(k) for k in range(1, 5)] + [driver_run(5, profile="test")])
    _, report = series_py(manifest)
    assert failing(report) == ["settings_5"]
    for arguments in ([], [str(tmp_path / "missing.txt")], [str(manifest), "x"]):
        assert subprocess.run([sys.executable, str(HERE / "series.py"), *arguments], check=False).returncode == 2
    manifest.write_text("run 1 exit 0 result x.json\n")
    assert series_py(manifest)[0].returncode == 2


def test_series_py_repeatability(tmp_path):
    """SL-A3-04 series.py needs the repeatability result in every run"""
    checks = [
        {"id": "SB-S06", "name": "similar incident", "passed": True},
        {"id": "SB-R-06", "name": "repeatability result", "passed": False},
    ]
    runs = [driver_run(k) for k in range(1, 5)] + [driver_run(5, checks=checks)]
    result, report = series_py(write_series(tmp_path, runs))
    assert result.returncode == 1 and "repeatability_5" in failing(report)
    assert [r["repeatability"] for r in report["runs"]] == [True, True, True, True, False]


def test_series_py_similar(tmp_path):
    """SB-T-03 series.py needs PI-1001's similar incident check in every run"""
    checks = [{"id": "SB-R-06", "name": "repeatability result", "passed": True}]
    runs = [driver_run(1, checks=checks)] + [driver_run(k) for k in range(2, 6)]
    result, report = series_py(write_series(tmp_path, runs))
    assert result.returncode == 1 and failing(report) == ["similar_5"]
    assert [r["similar"] for r in report["runs"]] == [False, True, True, True, True]


def test_series_py_step_times(tmp_path):
    """SB-T-04 series.py's step times and their limits 15, 60 and 15 s"""
    edge = {"SB-TL-02": 150.0, "SB-TL-03": 165.0, "SB-TL-04": 225.0, "approved": 480.0, "SB-TL-08": 495.0}
    result, report = series_py(write_series(tmp_path, [driver_run(k, times=dict(edge)) for k in range(1, 6)]))
    assert result.returncode == 0, result.stdout
    assert report["runs"][0]["step_times"] == {"diagnostics_s": 15.0, "ai_and_messages_s": 60.0, "action_s": 15.0}
    for key, value in (("SB-TL-03", 165.1), ("SB-TL-04", 225.2), ("SB-TL-08", 495.1), ("approved", None)):
        late = dict(edge, **{key: value})
        if key == "SB-TL-03":
            late["SB-TL-04"] = 225.0
        runs = [driver_run(k, times=dict(edge)) for k in range(1, 5)] + [driver_run(5, times=late)]
        result, report = series_py(write_series(tmp_path, runs))
        assert result.returncode == 1 and failing(report) == ["step_times_5"], (key, report["runs"][4])


def test_series_py_rb_db_01(tmp_path):
    """AR-G-12 RB-DB-01 cited in 4 of 5 runs passes and in 3 of 5 fails"""
    runs = [driver_run(k) for k in range(1, 5)] + [driver_run(5, cited=["RB-API-01"])]
    result, report = series_py(write_series(tmp_path, runs))
    assert result.returncode == 0 and report["criteria"]["rb_db_01_4_of_5"] is True
    runs = [driver_run(k) for k in range(1, 4)] + [driver_run(4, cited=[]), driver_run(5, cited=["RB-API-01"])]
    result, report = series_py(write_series(tmp_path, runs))
    assert result.returncode == 1 and failing(report) == ["rb_db_01_4_of_5"]
    assert "FAIL rb_db_01_4_of_5" in result.stdout.splitlines()


def test_series_py_failed_suite(tmp_path):
    """SB-T-02 a failed suite storm with a passed driver is recorded, not a failed criterion"""
    manifest = write_series(tmp_path, [driver_run(k) for k in range(1, 6)], exits={2: 1, 4: 1})
    result, report = series_py(manifest)
    assert result.returncode == 0 and report["passed"] is True
    assert [r["suite_storm_passed"] for r in report["runs"]] == [True, False, True, False, True]
    assert [r["driver_passed"] for r in report["runs"]] == [True] * 5
    assert report["suite_storm_runs_passed"] == 3


# rehearsal.py

STARTS = ["0:05", "1:05", "3:05", "4:35", "5:20", "6:20", "8:05", "9:50", "12:05", "12:35", "13:35"]


def rehearsal_file(folder, starts=None, end="14:05", answers=None, name="P-34-rehearsal-1.txt", order=None):
    lines = ["# Rehearsal 1, from the Teams recording", "date 2026-12-01"]
    lines += [f"SB-S{n:02d} {start}" for n, start in enumerate(starts or STARTS)]
    lines.append(f"end {end}")
    answers = answers or {}
    lines += [f"{item} {answers.get(item, 'yes')}" for item in ("DC-ASM-01", "DC-ASM-03", "DC-ASM-04", "SB-ASM-01")]
    if order:
        order(lines)
    path = folder / name
    path.write_text("\n".join(lines) + "\n")
    return path


def rehearsal_py(path):
    result = subprocess.run(
        [sys.executable, str(HERE / "rehearsal.py"), str(path)], capture_output=True, text=True, check=False
    )
    report = path.with_suffix(".json")
    return result, json.loads(report.read_text()) if report.exists() and result.returncode != 2 else None


def test_rehearsal_budgets(tmp_path):
    """SB-T-08 rehearsal.py's budgets ±30 s and 15:00"""
    result, report = rehearsal_py(rehearsal_file(tmp_path))
    assert result.returncode == 0, result.stdout
    assert list(report) == ["date", "scenes", "length_s", "assumptions", "criteria", "passed"]
    assert report["date"] == "2026-12-01" and report["length_s"] == 840
    assert [s["budget_s"] for s in report["scenes"]] == [60, 120, 90, 45, 60, 105, 105, 135, 30, 60, 30]
    assert [s["duration_s"] for s in report["scenes"]] == [s["budget_s"] for s in report["scenes"]]
    assert report["scenes"][0] == {"id": "SB-S00", "start_s": 5, "duration_s": 60, "budget_s": 60, "within": True}
    assert result.stdout.splitlines()[-4:-1] == ["PASS scenes_within_30", "PASS length_900", "PASS assumptions_yes"]

    # SB-S03 30 s over its budget passes; 31 s over fails.
    later = STARTS[:4] + ["5:50", "6:50", "8:35", "10:20", "12:35", "13:05", "14:05"]
    result, report = rehearsal_py(rehearsal_file(tmp_path, later, end="14:35"))
    assert result.returncode == 0 and report["scenes"][3]["duration_s"] == 75
    later = STARTS[:4] + ["5:51", "6:51", "8:36", "10:21", "12:36", "13:06", "14:06"]
    result, report = rehearsal_py(rehearsal_file(tmp_path, later, end="14:36"))
    assert result.returncode == 1 and report["criteria"] == {
        "scenes_within_30": False, "length_900": True, "assumptions_yes": True
    }  # fmt: skip
    assert [s["within"] for s in report["scenes"]].count(False) == 1
    # Every scene 6 s longer: each within its budget, the length 906 s over 15:00.
    budgets = (60, 120, 90, 45, 60, 105, 105, 135, 30, 60, 30)
    seconds = [5 + sum(budgets[:n]) + 6 * n for n in range(11)]
    starts = [f"{s // 60}:{s % 60:02d}" for s in seconds]
    result, report = rehearsal_py(rehearsal_file(tmp_path, starts, end="15:11"))
    assert result.returncode == 1 and report["length_s"] == 906
    assert report["criteria"] == {"scenes_within_30": True, "length_900": False, "assumptions_yes": True}
    for arguments in ([], [str(tmp_path / "missing.txt")], [str(tmp_path / "P-34-rehearsal-1.json")]):
        assert subprocess.run([sys.executable, str(HERE / "rehearsal.py"), *arguments], check=False).returncode == 2


def test_rehearsal_order(tmp_path):
    """SB-T-08 rehearsal.py refuses an item out of order"""

    def swap(lines):
        lines[4], lines[5] = lines[5], lines[4]

    cases = [
        (swap, "Line 5: expected SB-S02, found SB-S03"),
        (lambda lines: lines.insert(6, "SB-S01 1:05"), "Line 7: SB-S01 repeated"),
        (lambda lines: lines.pop(6), "Line 7: expected SB-S04, found SB-S05"),
        (lambda lines: lines.pop(), "Line 18: SB-ASM-01 missing"),
        (lambda lines: lines.__setitem__(6, "SB-S04 4:30"), "Line 7: SB-S04 4:30 is earlier than SB-S03"),
        (lambda lines: lines.__setitem__(6, "SB-S04 5:7"), "Line 7: unreadable time '5:7' (m:ss)"),
        (lambda lines: lines.__setitem__(1, "date 1 Dec 2026"), "Line 2: unreadable date '1 Dec 2026' (YYYY-MM-DD)"),
        (lambda lines: lines.__setitem__(14, "DC-ASM-01 maybe"), "Line 15: DC-ASM-01 must be yes or no"),
        (lambda lines: lines.append("extra 1"), "Line 19: unexpected extra"),
    ]
    for order, message in cases:
        path = rehearsal_file(tmp_path, order=order)
        result, report = rehearsal_py(path)
        assert result.returncode == 2 and result.stdout.strip() == message, (message, result.stdout)


def test_rehearsal_assumptions(tmp_path):
    """SB-ASM-01 rehearsal.py needs the four assumptions answered yes"""
    for item in ("DC-ASM-01", "DC-ASM-03", "DC-ASM-04", "SB-ASM-01"):
        result, report = rehearsal_py(rehearsal_file(tmp_path, answers={item: "no"}))
        assert result.returncode == 1, item
        assert report["criteria"] == {"scenes_within_30": True, "length_900": True, "assumptions_yes": False}
        assert report["assumptions"][item] == "no"
