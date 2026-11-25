"""Tests of the demo-host tooling (P-32 spec Section 7): the scripts run in a temporary copy of the repository with the
fakes of deploy/tests (the AWS CLI, whose ssm send-command runs the host part locally, docker and curl), a docker stub
of their own where a container must leave a file, stubs of the host's tools, and an in-process control panel."""

import importlib.util
import json
import os
import shutil
import stat
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
    names = ["http-redirect", "https-demo", "actuator-404", "https-control", "unknown-host-404", "tls-1.1-refused",
             "targets-healthy", "published-ports", "DOCKER-USER", "lab-to-app-blocked", "lab-public-name",
             "secrets-readable", "correlation", "clock", "release"]  # fmt: skip
    readable = check_table(harness)
    # host-status.sh checks each unit with docker exec; the fake answers 0 for each, so every unit is readable.
    result = run(harness, "check.sh", "demo", **environment)
    lines = result.stdout.splitlines()
    assert lines[-15:] == [f"PASS {name}" for name in names], result.stdout + result.stderr
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
        verdicts = result.stdout.splitlines()[-15:]
        assert [v for v in verdicts if v.startswith("FAIL")] == [f"FAIL {failing}"], verdicts

    # lab-public-name passes on 404 unknown_host: Vigil answered through the public name before any tenant exists.
    harness.log_path.write_text("")
    check_table(harness, {"public": "lab-public-name 404 unknown_host\n"})
    result = run(harness, "check.sh", "demo", **environment)
    assert result.returncode == 0, result.stdout
    assert "PASS lab-public-name" in result.stdout.splitlines()
