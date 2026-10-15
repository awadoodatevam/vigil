"""Unit tests of the lab mocks and labctl (P-03 spec Section 7): standard library and pytest, fakes, simulated clock."""

import heapq
import os
import subprocess
import threading
from pathlib import Path
from types import SimpleNamespace

import pytest
from labmocks import api, common, db, probe, rpt, vas

MIB = 1048576
LABCTL = Path(__file__).with_name("labctl.sh")


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


def space(bytes_available):
    return lambda _path: SimpleNamespace(f_bavail=bytes_available // 4096, f_frsize=4096)


def read_status(directory, name):
    return (Path(directory) / name).read_text()


def test_database(tmp_path, monkeypatch):
    """DD-F-10 (database): writes, read-only flag, low space, reads, unknown schema, counts and status files."""
    control = tmp_path / "control"
    status = tmp_path / "status"
    control.mkdir()
    status.mkdir()
    available = {"bytes": 300 * MIB}
    database = db.Database("/data", str(control), str(status), lambda path: space(available["bytes"])(path))

    assert database.handle("/write", b'{"schema": "balance_ledger"}') == (200, {"ok": True})
    available["bytes"] = 200 * MIB
    assert database.handle("/write", b'{"schema": "balance_ledger"}') == (
        409,
        {"ok": False, "error": "No space left on device"},
    )
    (control / "db_read_only").touch()
    assert database.write("sms_delivery") == (409, {"ok": False, "error": "database is read-only"})
    available["bytes"] = 300 * MIB
    assert database.write("sms_delivery") == (409, {"ok": False, "error": "database is read-only"})
    (control / "db_read_only").unlink()
    assert database.write("sms_delivery") == (200, {"ok": True})

    available["bytes"] = 0
    for schema in common.SCHEMAS:
        assert database.handle("/read", f'{{"schema": "{schema}"}}'.encode()) == (200, {"ok": True})
    assert database.handle("/write", b'{"schema": "unknown"}')[0] == 400
    assert database.handle("/read", b'{"schema": "unknown"}')[0] == 400
    assert database.handle("/write", b"[]")[0] == 400
    assert database.handle("/write", b"")[0] == 400
    assert database.handle("/other", b'{"schema": "sms_delivery"}')[0] == 404

    replaced = []
    original = os.replace

    def recording_replace(source, target):
        replaced.append((Path(source).parent, Path(target).name))
        original(source, target)

    monkeypatch.setattr(common.os, "replace", recording_replace)
    database.write_status()
    assert read_status(status, "db_writes_balance_ledger") == "2\n"
    assert read_status(status, "db_write_errors_balance_ledger") == "1\n"
    assert read_status(status, "db_writes_sms_delivery") == "3\n"
    assert read_status(status, "db_write_errors_sms_delivery") == "2\n"
    assert read_status(status, "db_writes_bundle_catalog") == "0\n"
    assert len(replaced) == 12 and all(folder == status for folder, _ in replaced)
    assert sorted(path.name for path in status.iterdir()) == sorted(name for _, name in replaced)
    assert all((path.stat().st_mode & 0o777) == 0o644 for path in status.iterdir())


class FakeDb:
    """The database as seen over HTTP: answers each write with answer(schema) after delay(schema) simulated seconds."""

    def __init__(self, clock):
        self.clock = clock
        self.writes = []
        self.answer = lambda schema: (200, {"ok": True})
        self.delay = lambda schema: 0.0
        self.lock = threading.Lock()

    def __call__(self, url, body, timeout):
        assert url == "http://db/write"
        with self.lock:
            self.writes.append(body["schema"])
        self.clock.sleep(self.delay(body["schema"]))
        return self.answer(body["schema"])


def test_api_instances():
    """DD-F-10 (API instances): writes per endpoint, failed and late writes, counters per label, recovery."""
    clock = FakeClock()
    fake = FakeDb(clock)
    counters = api.ApiCounters()
    assert set(counters.requests.snapshot()) == set(api.LABELS)
    assert len(api.LABELS) == 32
    assert set(counters.requests.snapshot().values()) == {0}
    assert set(counters.errors.snapshot().values()) == {0}
    balance = api.ApiInstance("balance", 2, "http://db", fake, clock, counters)
    bundle = api.ApiInstance("bundle", 3, "http://db", fake, clock, counters)
    assert (balance.name, balance.port, bundle.name, bundle.port) == ("api-balance-2", 8102, "api-bundle-3", 8203)

    for endpoint in api.ENDPOINTS["balance"]:
        fake.writes.clear()
        assert balance.handle(endpoint, b"") == (200, {"ok": True})
        assert fake.writes == ["balance_ledger", "subscriber_profile"]
    for endpoint in api.ENDPOINTS["bundle"]:
        fake.writes.clear()
        assert bundle.handle(endpoint, b"{}") == (200, {"ok": True})
        expected = ["bundle_orders", "bundle_catalog"] if endpoint == "/v1/bundles/offers" else ["bundle_orders"]
        assert fake.writes == expected
    assert balance.handle("/v1/bundles/offers", b"")[0] == 404
    assert balance.handle("/v1/balance", b'{"x": 1}')[0] == 400

    fake.answer = lambda schema: (409, {"ok": False, "error": "No space left on device"})
    fake.writes.clear()
    assert balance.request("/v1/balance") == (502, {"ok": False, "error": "No space left on device"})
    assert fake.writes == ["balance_ledger", "subscriber_profile"]

    fake.answer = lambda schema: (200, {"ok": True})
    fake.delay = lambda schema: 1.2 if schema == "bundle_catalog" else 0.0
    assert bundle.request("/v1/bundles/offers") == (502, {"ok": False, "error": "timeout"})
    fake.delay = lambda schema: 0.5
    assert bundle.request("/v1/bundles/offers") == (200, {"ok": True})

    def refused(url, body, timeout):
        raise ConnectionRefusedError("Connection refused")

    failing = api.ApiInstance("balance", 2, "http://db", refused, clock, counters)
    assert failing.request("/v1/balance/main")[0] == 502

    requests = counters.requests.snapshot()
    errors = counters.errors.snapshot()
    assert requests[("balance", "api-balance-2", "/v1/balance")] == 2
    assert errors[("balance", "api-balance-2", "/v1/balance")] == 1
    assert requests[("bundle", "api-bundle-3", "/v1/bundles/offers")] == 3
    assert errors[("bundle", "api-bundle-3", "/v1/bundles/offers")] == 1
    assert errors[("balance", "api-balance-2", "/v1/balance/main")] == 1
    assert requests[("balance", "api-balance-1", "/v1/balance")] == 0
    assert len(balance.stream_tasks()) == 4


class FakeLab:
    """API instances and the database for the gateway: per URL an answer and a simulated delay."""

    def __init__(self, clock):
        self.clock = clock
        self.calls = []
        self.answer = lambda url: (200, {"ok": True})
        self.delay = lambda url: 0.0

    def __call__(self, url, body, timeout):
        self.calls.append(url)
        self.clock.sleep(self.delay(url))
        return self.answer(url)


def test_ussd_gateway():
    """DD-F-10 (USSD gateway): menus, round-robin instances per node, timeouts, counters per node and menu."""
    clock = FakeClock()
    lab = FakeLab(clock)
    gateway = vas.UssdGateway(lambda kind, index: f"http://{kind}-{index}", "http://db", lab, clock)
    assert set(gateway.sessions.snapshot()) == {(n, m) for n in vas.NODES for m in vas.MENUS}
    assert set(gateway.sessions.snapshot().values()) == {0}
    assert set(gateway.timeouts.snapshot().values()) == {0}

    expected = {
        "balance": "/v1/balance",
        "data-bundles": "/v1/bundles/offers",
        "voice-bundles": "/v1/bundles/purchase",
        "sms-bundles": "/v1/bundles/purchase",
        "my-bundles": "/v1/bundles/active",
        "bundle-status": "/v1/bundles/active",
    }
    for menu, endpoint in expected.items():
        lab.calls.clear()
        assert gateway.handle("/session", f'{{"node": "ussd-gw-b", "menu": "{menu}"}}'.encode()) == (
            200,
            {"result": "ok"},
        )
        kind = "balance" if menu == "balance" else "bundle"
        assert lab.calls[0].startswith(f"http://{kind}-") and lab.calls[0].endswith(endpoint)
        assert lab.calls[1] == "http://db/write"

    lab.calls.clear()
    for _ in range(5):
        gateway.session("ussd-gw-a", "balance")
    gateway.session("ussd-gw-a", "my-bundles")
    instances = [url.split("/")[2] for url in lab.calls if url != "http://db/write"]
    assert instances == ["balance-1", "balance-2", "balance-3", "balance-4", "balance-1", "bundle-1"]

    lab.answer = lambda url: (502, {"ok": False, "error": "x"}) if "balance-" in url else (200, {"ok": True})
    lab.calls.clear()
    assert gateway.handle("/session", b'{"node": "ussd-gw-a", "menu": "balance"}') == (200, {"result": "timeout"})
    assert lab.calls[-1] == "http://db/write"
    lab.answer = lambda url: (409, {"ok": False, "error": "database is read-only"}) if "db" in url else (200, {})
    assert gateway.session("ussd-gw-a", "data-bundles") is False
    lab.answer = lambda url: (200, {"ok": True})
    lab.delay = lambda url: 1.0 if "db" not in url else 1.2
    assert gateway.session("ussd-gw-a", "sms-bundles") is False
    lab.delay = lambda url: 1.6 if "db" not in url else 0.0
    assert gateway.session("ussd-gw-a", "sms-bundles") is False
    lab.delay = lambda url: 0.9
    assert gateway.session("ussd-gw-a", "sms-bundles") is True

    assert gateway.handle("/session", b'{"node": "ussd-gw-c", "menu": "balance"}')[0] == 400
    assert gateway.handle("/session", b'{"node": "ussd-gw-a", "menu": "other"}')[0] == 400
    assert gateway.handle("/other", b"{}")[0] == 404
    sessions = gateway.sessions.snapshot()
    timeouts = gateway.timeouts.snapshot()
    assert sessions[("ussd-gw-a", "balance")] == 6 and timeouts[("ussd-gw-a", "balance")] == 1
    assert sessions[("ussd-gw-a", "sms-bundles")] == 3 and timeouts[("ussd-gw-a", "sms-bundles")] == 2
    assert timeouts[("ussd-gw-a", "data-bundles")] == 1
    assert timeouts[("ussd-gw-b", "balance")] == 0
    assert len(gateway.stream_tasks()) == 12


def test_sms_queue(tmp_path):
    """DD-F-10 (SMS queue): queues grow while writes fail, retries once a second, drain at most 20 a second."""
    clock = FakeClock()
    state = {"failing": True, "writes": 0}

    def http(url, body, timeout):
        assert body == {"schema": "sms_delivery"}
        state["writes"] += 1
        if state["failing"]:
            return 409, {"ok": False, "error": "No space left on device"}
        return 200, {"ok": True}

    dispatcher = vas.SmsDispatcher("http://db", http, clock, str(tmp_path))
    deliveries = {route: [] for route in vas.ROUTES}
    events = []
    sequence = [0]
    idle = set(vas.ROUTES)

    def schedule(time, kind, route=None):
        sequence[0] += 1
        heapq.heappush(events, (time, sequence[0], kind, route))

    for second in range(61):
        schedule(float(second), "arrive")

    def run_until(end):
        while events and events[0][0] < end:
            time, _, kind, route = heapq.heappop(events)
            clock.now = time
            if kind == "arrive":
                dispatcher.receive_all()
                for waiting in sorted(idle):
                    idle.discard(waiting)
                    schedule(time, "step", waiting)
                continue
            before = dispatcher.depth(route)
            delay = dispatcher.step(route)
            if dispatcher.depth(route) < before:
                deliveries[route].append(time)
            if delay is None:
                idle.add(route)
            else:
                schedule(time + delay, "step", route)

    run_until(30.0)
    for route in vas.ROUTES:
        assert 29 <= dispatcher.depth(route) <= 31
    assert 24 * 29 <= state["writes"] <= 24 * 31
    dispatcher.write_status()
    assert 29 <= int(read_status(tmp_path, "sms_queue_depth_route-24")) <= 31

    state["failing"] = False
    run_until(60.5)
    for route in vas.ROUTES:
        assert dispatcher.depth(route) <= 1
        times = deliveries[route]
        assert len(times) >= 30
        for first, twenty_first in zip(times, times[20:], strict=False):
            assert twenty_first - first >= 1.0 - 1e-9


def test_probes_and_report_job(tmp_path):
    """DD-F-10 (probes and report job): 50 sessions per round with their menus and nodes, gauge, report runs."""
    bodies = []
    lock = threading.Lock()

    def http(url, body, timeout):
        assert url == "http://vas/session" and timeout == 3.0
        with lock:
            bodies.append(body)
        if body["menu"] == "voice-bundles" and body["node"] == "ussd-gw-b":
            return 200, {"result": "timeout"}
        return 200, {"result": "ok"}

    probes = probe.Probes("http://vas", http, FakeClock())
    assert set(probes.last_results().values()) == {1} and len(probes.last_results()) == 50
    probes.run_round()
    assert len(bodies) == 50
    menus = {
        "balance-check": "balance",
        "bundle-list": "data-bundles",
        "bundle-purchase": "voice-bundles",
        "bundle-status": "bundle-status",
        "sms-confirmation": "sms-bundles",
    }
    nodes_a = {"karachi", "islamabad", "faisalabad", "peshawar", "hyderabad"}
    expected = sorted(
        (("ussd-gw-a" if city in nodes_a else "ussd-gw-b"), menu) for city in probe.CITIES for menu in menus.values()
    )
    assert sorted((body["node"], body["menu"]) for body in bodies) == expected
    results = probes.last_results()
    for (city, journey), value in results.items():
        failed = journey == "bundle-purchase" and city not in nodes_a
        assert value == (0 if failed else 1)

    control = tmp_path / "control"
    status = tmp_path / "status"
    control.mkdir()
    status.mkdir()
    clock = FakeClock()
    job = rpt.ReportJob(str(control), str(status), clock)
    runs = []
    original_run = job.run

    def counting_run():
        runs.append(clock.now)
        return original_run()

    job.run = counting_run
    job.start()
    assert runs == [0.0]
    assert (control / "report_mode").read_text() == "succeed\n"
    assert read_status(status, "report_export_last_result") == "1\n"

    def advance_to(end):
        while clock.now < end:
            clock.sleep(0.5)
            job.tick()

    advance_to(599.5)
    assert runs == [0.0]
    advance_to(600.0)
    assert runs == [0.0, 600.0]

    (control / "report_mode").write_text("fail\n")
    (control / "report_run_now").touch()
    request_time = clock.now
    advance_to(request_time + 1.0)
    assert len(runs) == 3 and runs[2] - request_time <= 1.0
    assert not (control / "report_run_now").exists()
    assert read_status(status, "report_export_last_result") == "0\n"

    (control / "report_run_now").touch()
    request_time = clock.now
    advance_to(request_time + 1.0)
    assert len(runs) == 4 and read_status(status, "report_export_last_result") == "0\n"

    (control / "report_mode").write_text("succeed\n")
    (control / "report_run_now").touch()
    advance_to(clock.now + 1.0)
    assert len(runs) == 5 and read_status(status, "report_export_last_result") == "1\n"
    advance_to(runs[-1] + 600.0)
    assert len(runs) == 6


def test_every_writer_rate():
    """DD-F-10 (every-writer rate): from the configured stream rates, every schema gets at least one write a second."""
    rate = dict.fromkeys(common.SCHEMAS, 0)
    for kind, _index in api.INSTANCES:
        for endpoint in api.ENDPOINTS[kind]:
            for schema in api.writes_of(kind, endpoint):
                rate[schema] += api.STREAM_REQUESTS_PER_SECOND
    rate["ussd_sessions"] += len(vas.SESSION_LABELS) * vas.STREAM_SESSIONS_PER_SECOND
    rate["sms_delivery"] += len(vas.ROUTES) * vas.MESSAGES_PER_SECOND
    assert all(per_second >= 1 for per_second in rate.values()), rate


def labctl(root, role, *args):
    env = {"PATH": os.environ["PATH"], "LABCTL_ROOT": str(root), "LAB_ROLE": role}
    return subprocess.run(["bash", str(LABCTL), *args], env=env, capture_output=True, text=True, check=False)


def test_labctl(tmp_path):
    """DD-F-10 (labctl): the read-only flag on lab-db-01, the report mode on lab-rpt-01, usage errors exit 2."""
    control = tmp_path / "control"
    control.mkdir()
    assert labctl(tmp_path, "lab-db-01", "db", "read-only").stdout == "off\n"
    assert labctl(tmp_path, "lab-db-01", "db", "read-only", "on").returncode == 0
    assert (control / "db_read_only").exists()
    assert labctl(tmp_path, "lab-db-01", "db", "read-only").stdout == "on\n"
    assert labctl(tmp_path, "lab-db-01", "db", "read-only", "off").returncode == 0
    assert not (control / "db_read_only").exists()
    assert labctl(tmp_path, "lab-db-01", "db", "read-only").stdout == "off\n"

    assert labctl(tmp_path, "lab-rpt-01", "report").stdout == "succeed\n"
    assert labctl(tmp_path, "lab-rpt-01", "report", "fail").returncode == 0
    assert (control / "report_mode").read_text() == "fail\n"
    assert (control / "report_run_now").exists()
    assert labctl(tmp_path, "lab-rpt-01", "report").stdout == "fail\n"
    (control / "report_run_now").unlink()
    assert labctl(tmp_path, "lab-rpt-01", "report", "succeed").returncode == 0
    assert (control / "report_mode").read_text() == "succeed\n"
    assert (control / "report_run_now").exists()

    for role, args in [
        ("lab-rpt-01", ["db", "read-only", "on"]),
        ("lab-rpt-01", ["db", "read-only"]),
        ("lab-db-01", ["report", "fail"]),
        ("lab-db-01", ["report"]),
        ("lab-db-01", ["db", "read-only", "maybe"]),
        ("lab-db-01", ["db", "writable"]),
        ("lab-rpt-01", ["report", "later"]),
        ("lab-api-01", ["status"]),
        ("lab-db-01", []),
    ]:
        result = labctl(tmp_path, role, *args)
        assert result.returncode == 2, (role, args)
        assert result.stdout.startswith("Usage: labctl")


@pytest.mark.parametrize("deadline", [0, -1])
def test_call_without_time_left(deadline):
    """DD-F-10 (API instances): a call with no time left fails with timeout and is not made."""
    made = []
    assert common.call(lambda *a: made.append(a), FakeClock(), "http://x", {}, deadline) == (None, {}, "timeout")
    assert made == []
