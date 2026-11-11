"""Tests of deploy/images.sh --test [--java] (P-42 spec Section 7, BP-R-12, BP-R-04).

deploy/images.sh runs from a temporary copy of the repository with its compose file and Dockerfiles, with a docker
stub first on PATH: it records every call, answers that no image is present (image inspect exits 1) and succeeds
otherwise. Unlike the fake of deploy/tests/bin, it does not read its standard input, as docker pull does not, so the
loops of deploy/images.sh that read image names keep their input.
"""

import json
import os
import re
import shutil
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
USAGE = "Usage: deploy/images.sh --test [--java]\n"
CONTEXTS = {
    "vigil/demo-lab:local": "demo/lab",
    "vigil/demo-faults:local": "demo/faults",
    "vigil/lab-zabbix-provision:local": "demo/monitoring/provision",
    "vigil/recorder:local": "dev/recorder",
    "vigil/storm-spike:local": "spikes/storm-timing",
    "vigil/sse-probe:local": "spikes/sse-probe",
}


STUB = textwrap.dedent(
    """\
    import json
    import os
    import sys

    with open(os.environ["STUB_LOG"], "a") as log:
        log.write(json.dumps(sys.argv[1:]) + "\\n")
    sys.exit(1 if sys.argv[1:3] == ["image", "inspect"] else 0)
    """
)


def base_images(context):
    text = (ROOT / context / "Dockerfile").read_text(encoding="utf-8")
    return [line.split()[1] for line in text.splitlines() if line.split() and line.split()[0].upper() == "FROM"]


def compose_images():
    text = (ROOT / "deploy" / "local" / "compose.yaml").read_text(encoding="utf-8")
    return re.findall(r"^\s*image:\s*(\S+@sha256:\S+)", text, re.MULTILINE)


@pytest.fixture
def tree(tmp_path):
    root = tmp_path / "repository"
    for relative in ["deploy/images.sh", "deploy/local/compose.yaml"] + [f"{c}/Dockerfile" for c in CONTEXTS.values()]:
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / relative, target)
    stubs = tmp_path / "bin"
    stubs.mkdir()
    (stubs / "docker").write_text(f"#!{sys.executable}\n" + STUB, encoding="utf-8")
    (stubs / "docker").chmod(0o755)
    log = tmp_path / "docker.log"
    log.write_text("", encoding="utf-8")

    def run(*arguments):
        environment = dict(os.environ)
        environment.update(PATH=os.pathsep.join([str(stubs), environment.get("PATH", "")]), STUB_LOG=str(log))
        result = subprocess.run(
            ["bash", str(root / "deploy" / "images.sh"), *arguments],
            cwd=tmp_path,
            env=environment,
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            timeout=120,
            check=False,
        )
        calls = [json.loads(line) for line in log.read_text(encoding="utf-8").splitlines() if line.strip()]
        return result, calls

    return run


def built(calls):
    return [call[call.index("-t") + 1] for call in calls if call[0] == "build"]


def pulled(calls):
    return [call[1] for call in calls if call[0] == "pull"]


def test_java_builds_only_the_demo_lab_image(tree):
    """BP-R-12 --test --java builds only vigil/demo-lab:local and pulls only its Dockerfile's base images."""
    result, calls = tree("--test", "--java")
    assert result.returncode == 0, result.stdout + result.stderr
    assert built(calls) == ["vigil/demo-lab:local"]
    assert pulled(calls) == base_images("demo/lab")
    assert not set(pulled(calls)) & set(compose_images())


def test_test_builds_the_six_images(tree):
    """BP-R-12 --test builds the six images and pulls the compose file's images first, as before."""
    result, calls = tree("--test")
    assert result.returncode == 0, result.stdout + result.stderr
    assert built(calls) == list(CONTEXTS)
    images = compose_images()
    assert pulled(calls)[: len(images)] == images
    for context in CONTEXTS.values():
        assert set(base_images(context)) <= set(pulled(calls))


@pytest.mark.parametrize("arguments", [["--java"], ["--java", "--test"], ["--test", "--java", "x"], []])
def test_wrong_arguments_print_usage(tree, arguments):
    """BP-R-12 other arguments print the usage line and exit 2 before any docker call."""
    result, calls = tree(*arguments)
    assert result.returncode == 2
    assert result.stdout == USAGE
    assert calls == []
