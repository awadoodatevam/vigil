"""Tests of deploy/fetch.sh and deploy/main/host-read.sh (P-32 spec Section 7)."""

import os

MAIN = {"i-0main": {"Name": "vigil-demo-main", "State": "running"}}
REFUSED = "Only files under /srv/vigil/spike/results, /srv/vigil/logs and /srv/vigil/deploy can be fetched."


def test_pieces(harness, tmp_path):
    """AR-H-11 fetch.sh rebuilds a file of several pieces and checks its SHA-256."""
    harness.seed(instances=MAIN)
    results = harness.vigil_root / "spike" / "results"
    results.mkdir(parents=True)
    data = os.urandom(40000)
    (results / "storm-timing-x.json").write_bytes(data)
    target = tmp_path / "out" / "storm-timing-x.json"
    result = harness.run("deploy/fetch.sh", "demo", "/srv/vigil/spike/results/storm-timing-x.json", str(target))
    assert result.returncode == 0, result.stdout + result.stderr
    assert target.read_bytes() == data
    assert oct(target.stat().st_mode & 0o777) == "0o644"
    commands = [c["Parameters"]["commands"][-5] for c in harness.state()["commands"].values()]
    # 40,000 random bytes: about 53,400 base64 characters of gzip, so 3 pieces of at most 18,000.
    assert [command.split()[-1] for command in commands] == ["0", "18000", "36000"]
    assert all("/srv/vigil/spike/results/storm-timing-x.json" in command for command in commands)
    assert result.stdout.splitlines()[-1].startswith(f"fetched {target} (40000 bytes, sha256 ")
    assert not list(target.parent.glob(".fetch-*"))

    small = harness.vigil_root / "logs" / "sample.csv"
    small.parent.mkdir()
    small.write_text("time,cpu,memory\n")
    result = harness.run("deploy/fetch.sh", "demo", "/srv/vigil/logs/sample.csv", str(tmp_path / "sample.csv"))
    assert result.returncode == 0, result.stdout + result.stderr
    assert (tmp_path / "sample.csv").read_text() == "time,cpu,memory\n"

    # A main host whose SHA-256 differs: nothing is written.
    script = harness.root / "deploy" / "main" / "host-read.sh"
    script.write_text(script.read_text().replace("hashlib.sha256(data).hexdigest()", '"0" * 64'))
    other = tmp_path / "other.json"
    result = harness.run("deploy/fetch.sh", "demo", "/srv/vigil/spike/results/storm-timing-x.json", str(other))
    assert result.returncode == 1
    assert result.stdout.splitlines()[-1].endswith(f"the main host's is {'0' * 64}.")
    assert not other.exists()

    result = harness.run("deploy/fetch.sh", "demo", "/srv/vigil/spike/results/missing.json", str(other))
    assert result.returncode == 1
    assert "No file /srv/vigil/spike/results/missing.json on the main host." in result.stdout


def test_refused_paths(harness, tmp_path):
    """AR-H-11 fetch.sh refuses a path outside the three folders."""
    harness.seed(instances=MAIN)
    secret = harness.vigil_root / "secrets" / "vigil-app" / "jira"
    secret.parent.mkdir(parents=True)
    secret.write_text("not to be fetched")
    for path in (
        "/srv/vigil/secrets/vigil-app/jira",
        "/srv/vigil/spike/results/../../secrets/vigil-app/jira",
        "/srv/vigil/logs/..",
        "/srv/vigil/deploy/",
        "/etc/passwd",
        "srv/vigil/logs/x",
    ):
        result = harness.run("deploy/fetch.sh", "demo", path, str(tmp_path / "x"))
        assert result.returncode == 1, path
        assert result.stdout.strip() == REFUSED, path
        result = harness.run("deploy/main/host-read.sh", path, "0")
        assert result.returncode == 1, path
        assert result.stdout.strip() == REFUSED, path
    assert harness.aws_calls() == []
    assert not (tmp_path / "x").exists()
    result = harness.run("deploy/fetch.sh", "demo", "/srv/vigil/logs/x")
    assert result.returncode == 2
    assert result.stdout.strip() == "Usage: deploy/fetch.sh {installation} {host path} {local path}"
