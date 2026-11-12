"""Tests of bundle.py (P-26 spec Sections 6.4 and 6.8; AR-B-05, AR-B-07, DD-R-11)."""

import io
import re
import shutil
import zipfile
from pathlib import Path

import bundle
import pytest

ROOT = Path(__file__).resolve().parent.parent.parent
DATA = ROOT / "docs" / "31-demo-data"
LOCAL = ROOT / "deploy" / "values" / "local.yaml"
ORDER = [
    "bundle.yaml",
    "01-tenant-identity.yaml",
    "02-service-map.yaml",
    "03-monitoring.yaml",
    "04-operations-actions-recovery.yaml",
    "05-routing-escalation.yaml",
    "06-workflow.yaml",
    "07-settings.yaml",
    "08-runbooks.md",
    "09-past-incidents.csv",
]


@pytest.fixture
def derived(tmp_path):
    path = tmp_path / "derived.yaml"
    path.write_text('lab_db_host_key_sha256: "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"\n')
    return path


def unzip(content):
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        return archive.infolist(), {info.filename: archive.read(info).decode() for info in archive.infolist()}


def test_entries(derived):
    """AR-B-07 the bundle holds the ten entries of AR-B-05 in order with the fixed time, and bundle.yaml (DD-R-11)."""
    infos, files = unzip(bundle.build(DATA, [LOCAL, derived]))
    assert [info.filename for info in infos] == ORDER
    assert all(info.date_time == (1980, 1, 1, 0, 0, 0) for info in infos)
    version = re.search(r"^\| Version \| ([^ |]+) \|$", (DATA / "README.md").read_text(), re.MULTILINE).group(1)
    assert files["bundle.yaml"] == f'bundle_name: "jazz-demo"\nbundle_version: "{version}"\n'
    for name in ORDER[1:]:
        assert "${" not in files[name]


def test_replacement_as_text(derived):
    """AR-B-07 placeholders are replaced as text: false stays false, a quoted value loses its quotes."""
    _, files = unzip(bundle.build(DATA, [LOCAL, derived]))
    settings = files["07-settings.yaml"]
    assert "starttls: false" in settings
    assert 'address: "http://jira.fakes:8000"' in settings
    assert 'address: "http://ollama-llm.fakes:11434"' in settings
    assert "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG" in settings
    assert '"SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"' not in settings.replace('"SHA256:abc', "x")
    assert 'address: "noc-l1@vigil.test"' in settings
    for name in ORDER[1:]:
        original = (DATA / name).read_text()
        if "${" not in original:
            assert files[name] == original


def test_values_lines(tmp_path):
    """AR-B-07 the values files are flat name: value lines; comments and blank lines are ignored."""
    path = tmp_path / "values.yaml"
    path.write_text('# comment\n\nplain: false\nquoted: "a: b"\nurl: http://x:1/y\n  # indented comment\n')
    assert bundle.read_values([path]) == {"plain": "false", "quoted": "a: b", "url": "http://x:1/y"}


def test_name_in_two_files(tmp_path, derived):
    """AR-B-07 a name in two values files is refused."""
    other = tmp_path / "other.yaml"
    other.write_text("demo_domain: example.test\n")
    assert (
        bundle.main(
            [
                "--data",
                str(DATA),
                "--values",
                str(LOCAL),
                "--values",
                str(other),
                "--values",
                str(derived),
                "--out",
                str(tmp_path / "b.zip"),
            ]
        )
        == 1
    )
    assert not (tmp_path / "b.zip").exists()


def test_placeholder_left(tmp_path, capsys):
    """AR-B-07 a placeholder left over stops the build with its names."""
    data = tmp_path / "data"
    shutil.copytree(DATA, data)
    with (data / "02-service-map.yaml").open("a") as file:
        file.write("# ${unknown_value}\n")
    code = bundle.main(["--data", str(data), "--values", str(LOCAL), "--out", str(tmp_path / "b.zip")])
    assert code == 1
    assert capsys.readouterr().out == (
        "The bundle still contains placeholders: unknown_value, lab_db_host_key_sha256.\n"
    )


def test_same_bytes(tmp_path, derived):
    """AR-B-07 the same input gives the same bytes, written to --out."""
    out = tmp_path / "bundle.zip"
    assert bundle.main(["--data", str(DATA), "--values", str(LOCAL), "--values", str(derived), "--out", str(out)]) == 0
    assert out.read_bytes() == bundle.build(DATA, [LOCAL, derived])


@pytest.mark.parametrize("arguments", [[], ["--data", "x"], ["--data", "x", "--out", "y"], ["--other", "x"]])
def test_usage(arguments, capsys):
    """AR-B-07 wrong arguments print the usage line and exit 2."""
    assert bundle.main(arguments) == 2
    assert capsys.readouterr().out.startswith("Usage: bundle.py")
