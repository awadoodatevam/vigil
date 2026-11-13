"""Tests of deploy/images.sh --test [--java] | --local (P-42 spec Section 7, P-26 spec Section 6.8, BP-R-12, BP-R-04).

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
USAGE = "Usage: deploy/images.sh --test [--java] | --local\n"
CONTEXTS = {
    "vigil/demo-lab:local": "demo/lab",
    "vigil/demo-faults:local": "demo/faults",
    "vigil/lab-zabbix-provision:local": "demo/monitoring/provision",
    "vigil/recorder:local": "dev/recorder",
    "vigil/storm-spike:local": "spikes/storm-timing",
    "vigil/sse-probe:local": "spikes/sse-probe",
}
LOCAL_CONTEXTS = {
    "vigil/vigil-app:local": "app",
    "vigil/dev-fakes:local": "dev/fakes",
    "vigil/e2e:local": "tests/e2e",
    "vigil/storm-driver:local": "tests/acceptance/storm",
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
    """The pinned images of the compose file, except the lines ending with # optional."""
    text = (ROOT / "deploy" / "local" / "compose.yaml").read_text(encoding="utf-8")
    return re.findall(r"^\s*image:\s*(\S+@sha256:\S+)\s*$", text, re.MULTILINE)


@pytest.fixture
def tree(tmp_path):
    root = tmp_path / "repository"
    contexts = list(CONTEXTS.values()) + list(LOCAL_CONTEXTS.values())
    for relative in ["deploy/images.sh", "deploy/local/compose.yaml"] + [f"{c}/Dockerfile" for c in contexts]:
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / relative, target)
    stubs = tmp_path / "bin"
    stubs.mkdir()
    (stubs / "docker").write_text(f"#!{sys.executable}\n" + STUB, encoding="utf-8")
    (stubs / "docker").chmod(0o755)
    log = tmp_path / "docker.log"
    log.write_text("", encoding="utf-8")

    def run(*arguments, mirror=None, jar=True):
        target = root / "app" / "target" / "vigil-app.jar"
        target.parent.mkdir(parents=True, exist_ok=True)
        if jar:
            target.write_bytes(b"jar")
        else:
            target.unlink(missing_ok=True)
        environment = dict(os.environ)
        environment.pop("VIGIL_UBUNTU_MIRROR", None)
        if mirror is not None:
            environment["VIGIL_UBUNTU_MIRROR"] = mirror
        log.write_text("", encoding="utf-8")
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


def test_test_skips_the_optional_and_local_images(tree):
    """BP-R-04 --test pulls neither the optional Ollama image of the embed profile nor the e2e profile's image."""
    result, calls = tree("--test")
    assert result.returncode == 0, result.stdout + result.stderr
    assert not any(image.startswith("ollama/ollama:") for image in pulled(calls))
    assert not any(image.startswith(("vigil/", "mcr.microsoft.com/playwright")) for image in pulled(calls))
    assert any(image.startswith("pgvector/pgvector:") for image in pulled(calls))
    assert any(image.startswith("axllent/mailpit:") for image in pulled(calls))


def test_local_builds_the_four_images(tree):
    """BP-R-04 --local builds vigil-app from app/, the dev fakes, the Playwright image and the storm driver, each from
    its context."""
    result, calls = tree("--local")
    assert result.returncode == 0, result.stdout + result.stderr
    builds = [call for call in calls if call[0] == "build"]
    assert built(calls) == list(LOCAL_CONTEXTS)
    for call, context in zip(builds, LOCAL_CONTEXTS.values(), strict=True):
        assert call[-1].endswith(context)
        assert call[call.index("-f") + 1].endswith(f"{context}/Dockerfile")
    assert "mcr.microsoft.com/playwright:v1.65.0-noble" in " ".join(pulled(calls))


def test_e2e_image_version_equals_the_package():
    """BP-R-04 the Playwright base image's version equals the @playwright/test version of tests/e2e/package.json."""
    package = json.loads((ROOT / "tests" / "e2e" / "package.json").read_text())
    version = package["devDependencies"]["@playwright/test"]
    assert base_images("tests/e2e") == [base_images("tests/e2e")[0]]
    assert base_images("tests/e2e")[0].startswith(f"mcr.microsoft.com/playwright:v{version}-noble@sha256:")
    # CTX-D-085 (10) amends CTX-D-081 (4): the Maven build's lint makes tests/e2e/node_modules on the host; it stays out
    # of the image's build context and the e2e container hides it, so the image's node_modules is the only one used.
    assert (ROOT / "tests" / "e2e" / ".dockerignore").read_text(encoding="utf-8").split() == ["node_modules"]
    compose = (ROOT / "deploy" / "local" / "compose.yaml").read_text(encoding="utf-8")
    assert "type: tmpfs\n        target: /e2e/tests/node_modules" in compose


def test_local_needs_the_jar(tree):
    """BP-R-04 --local without app/target/vigil-app.jar is refused before any docker call."""
    result, calls = tree("--local", jar=False)
    assert result.returncode == 1
    assert result.stdout == "Run ./mvnw -B -pl app -DskipTests package first.\n"
    assert calls == []


@pytest.mark.parametrize(
    "arguments", [["--java"], ["--java", "--test"], ["--test", "--java", "x"], [], ["--local", "--test"]]
)
def test_wrong_arguments_print_usage(tree, arguments):
    """BP-R-12 other arguments print the usage line and exit 2 before any docker call."""
    result, calls = tree(*arguments)
    assert result.returncode == 2
    assert result.stdout == USAGE
    assert calls == []


def mirror_arguments(call):
    return [
        value
        for flag, value in zip(call, call[1:], strict=False)
        if flag == "--build-arg" and value.startswith("UBUNTU_MIRROR")
    ]


@pytest.mark.parametrize("arguments", [["--test"], ["--test", "--java"], ["--local"]])
def test_mirror_build_argument_only_when_the_variable_is_set(tree, arguments):
    """BP-R-04 every build gets --build-arg UBUNTU_MIRROR={value} only when VIGIL_UBUNTU_MIRROR is set and not empty."""
    mirror = "http://azure.archive.ubuntu.com/ubuntu/"
    result, calls = tree(*arguments, mirror=mirror)
    assert result.returncode == 0, result.stdout + result.stderr
    builds = [call for call in calls if call[0] == "build"]
    assert builds
    for call in builds:
        assert mirror_arguments(call) == [f"UBUNTU_MIRROR={mirror}"]
    for unset in [None, ""]:
        result, calls = tree(*arguments, mirror=unset)
        assert result.returncode == 0, result.stdout + result.stderr
        builds = [call for call in calls if call[0] == "build"]
        assert builds
        for call in builds:
            assert mirror_arguments(call) == []
            assert not any("UBUNTU_MIRROR" in part for part in call)
