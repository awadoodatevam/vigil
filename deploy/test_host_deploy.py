"""Tests of the deployment sequence on the main host, deploy/main/host-deploy.sh (P-05 and P-32 spec Section 7)."""

import re
import shutil
import stat

from conftest import REGISTRY, REPOSITORY, tags_json, write_lock

EMBED = "a" * 64
ALERTMANAGER = REPOSITORY / "demo" / "monitoring" / "alertmanager" / "alertmanager.yml"
ENV = [
    f"VIGIL_REGISTRY={REGISTRY}",
    "VIGIL_RELEASE=r1",
    "VIGIL_INSTALLATION=demo",
    "COMPOSE_PROFILES=vigil",
    "VIGIL_DEMO_DOMAIN=vigil.solviclabs.com",
    "VIGIL_WEBHOOK_BASE_URL=https://demo.vigil.solviclabs.com",
    "VIGIL_LAB_PROFILE=demo",
]
OTHER = "b" * 64
STUB = """#!/usr/bin/env bash
# Stub of deploy/secrets.sh: records its arguments in the fakes' log.
printf '{"command": "secrets.sh", "args": [%s]}\\n' "$(printf '"%s",' "$@" | sed 's/,$//')" >> "$FAKE_LOG"
"""


def prepare(harness, services, running, lock_digest=EMBED, extra=()):
    """The deployed files with a secrets.sh stub, a test copy of models.lock and the docker and curl fakes."""
    deploy = harness.vigil_root / "deploy"
    deploy.mkdir()
    harness.log_path.write_text("")
    shutil.copy2(REPOSITORY / "deploy" / "main" / "host-deploy.sh", deploy / "host-deploy.sh")
    shutil.copy2(REPOSITORY / "deploy" / "main" / "compose.yaml", deploy / "compose.yaml")
    (deploy / "values").mkdir()
    shutil.copy2(REPOSITORY / "deploy" / "values" / "demo.yaml", deploy / "values" / "demo.yaml")
    (deploy / "monitoring" / "alertmanager").mkdir(parents=True)
    shutil.copy2(ALERTMANAGER, deploy / "monitoring" / "alertmanager" / "alertmanager.yml")
    (deploy / "secrets.sh").write_text(STUB)
    write_lock(deploy / "models.lock", lock_digest, "unrecorded")
    harness.table(
        list(extra)
        + [
            ("docker", r"^compose .* config --quiet$", [("", 0)]),
            ("docker", r"^compose .* config --services$", [("\n".join(services) + "\n", 0)]),
            ("docker", r"^ps --filter label=com\.docker\.compose\.project=vigil", [("\n".join(running) + "\n", 0)]),
            ("docker", r"^inspect --format \{\{\.Image\}\}", [("sha256:" + "1" * 64 + "\n", 0)]),
            ("docker", r"^image inspect", [(f"{REGISTRY}/demo/demo-lab@sha256:" + "2" * 64 + "\n", 0)]),
            ("docker", r"^inspect --format \{\{with index", [("172.30.0.11\n", 0)]),
            ("curl", r"/api/tags$", [(tags_json({"embeddinggemma:latest": EMBED}), 0)]),
        ]
    )
    return deploy


def run(harness, deploy, profile="vigil"):
    return harness.run(str(deploy / "host-deploy.sh"), "demo", "r1", REGISTRY, profile)


def steps(harness):
    """The fakes' log as short step names."""
    names = []
    for call in harness.fake_calls():
        arguments = call["args"]
        if call["command"] == "secrets.sh":
            names.append(" ".join(["secrets.sh"] + [a for a in arguments if a]))
        elif call["command"] == "curl":
            names.append("curl " + arguments[-1])
        elif arguments[0] == "compose":
            names.append("compose " + " ".join(arguments[7:]))
        else:
            names.append(" ".join(arguments[:2]))
    return names


def test_sequence_of_this_package(harness):
    """AR-H-11 the steps of host-deploy.sh in order with the profile vigil, deployed.yaml and the models check."""
    deploy = prepare(harness, ["lab-db-01", "vigil-embed"], ["lab-db-01", "vigil-embed"])
    result = run(harness, deploy)
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout.splitlines()[-1] == "deployment r1 done"
    assert steps(harness) == [
        "compose config --quiet",
        "secrets.sh demo",
        "login --username",
        "compose pull --quiet",
        f"logout {REGISTRY}",
        "compose --profile spike rm -s -f dev-recorder",
        "compose config --services",
        "compose up -d --remove-orphans lab-db-01 vigil-embed",
        "secrets.sh demo --generated",
        "ps --filter",
        "inspect --format",
        "image inspect",
        "inspect --format",
        "image inspect",
        "inspect --format",
        "curl http://172.30.0.11:11434/api/tags",
    ]
    assert harness.fake_calls("docker")[0]["args"] == [
        "compose", "-p", "vigil", "-f", "compose.yaml", "--env-file", ".env", "config", "--quiet"
    ]  # fmt: skip
    assert harness.fake_calls("docker")[1]["args"] == ["login", "--username", "AWS", "--password-stdin", REGISTRY]
    assert (deploy / ".env").read_text().splitlines() == ENV
    deployed = (deploy / "deployed.yaml").read_text().splitlines()
    assert deployed[0] == 'release: "r1"'
    assert re.fullmatch(r'deployed_at: "\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ"', deployed[1])
    reference = f'"{REGISTRY}/demo/demo-lab@sha256:' + "2" * 64 + '"'
    assert deployed[2:] == ["images:", f"  lab-db-01: {reference}", f"  vigil-embed: {reference}"]
    assert [call["args"][:2] for call in harness.aws_calls()] == [["ecr", "get-login-password"]]


def test_sequence_with_provision_and_the_rest(harness):
    """AR-H-11 with lab-zabbix-provision, vigil-app and demo-control: docker wait, then --generated, then the rest."""
    services = ["vigil-app", "lab-zabbix-provision", "demo-control", "vigil-embed"]
    deploy = prepare(
        harness, services, ["vigil-embed"], extra=[("docker", r"^wait lab-zabbix-provision$", [("0\n", 0)])]
    )
    result = run(harness, deploy)
    assert result.returncode == 0, result.stdout + result.stderr
    names = steps(harness)
    assert names[:11] == [
        "compose config --quiet",
        "secrets.sh demo",
        "login --username",
        "compose pull --quiet",
        f"logout {REGISTRY}",
        "compose --profile spike rm -s -f dev-recorder",
        "compose config --services",
        "compose up -d --remove-orphans lab-zabbix-provision vigil-embed",
        "wait lab-zabbix-provision",
        "secrets.sh demo --generated",
        "compose up -d vigil-app demo-control",
    ]


def test_values_in_env(harness):
    """AR-H-11 .env holds the profile and the three values; a missing value stops."""
    deploy = prepare(harness, ["vigil-embed"], ["vigil-embed"])
    values = deploy / "values" / "demo.yaml"
    text = values.read_text()
    values.write_text(text.replace("lab_profile: demo", 'lab_profile: "test"'))
    result = run(harness, deploy, "spike")
    assert result.returncode == 0, result.stdout + result.stderr
    assert (deploy / ".env").read_text().splitlines() == ENV[:3] + [
        "COMPOSE_PROFILES=spike",
        "VIGIL_DEMO_DOMAIN=vigil.solviclabs.com",
        "VIGIL_WEBHOOK_BASE_URL=https://demo.vigil.solviclabs.com",
        "VIGIL_LAB_PROFILE=test",
    ]
    for name in ("demo_domain", "webhook_base_url", "lab_profile"):
        (deploy / ".env").unlink(missing_ok=True)
        harness.log_path.write_text("")
        values.write_text("\n".join(line for line in text.splitlines() if not line.startswith(f"{name}: ")) + "\n")
        result = run(harness, deploy)
        assert result.returncode == 1
        assert result.stdout.strip() == f"The value {name} is missing in values/demo.yaml."
        assert harness.fake_calls() == []
        assert not (deploy / ".env").exists()


def test_alertmanager_configuration(harness):
    """AR-H-11 alertmanager.yml is rendered with webhook_base_url."""
    deploy = prepare(harness, ["vigil-embed"], ["vigil-embed"])
    result = run(harness, deploy)
    assert result.returncode == 0, result.stdout + result.stderr
    rendered = harness.vigil_root / "config" / "alertmanager.yml"
    template = ALERTMANAGER.read_text()
    assert "${webhook_base_url}" in template
    assert rendered.read_text() == template.replace("${webhook_base_url}", "https://demo.vigil.solviclabs.com")
    assert stat.S_IMODE(rendered.stat().st_mode) == 0o644
    assert stat.S_IMODE((harness.vigil_root / "generated").stat().st_mode) == 0o700
    assert stat.S_IMODE((harness.vigil_root / "config").stat().st_mode) == 0o755
    assert not (harness.vigil_root / "spike").exists()
    assert not list((harness.vigil_root / "config").glob(".*"))

    rendered.write_text("old\n")
    (deploy / "values" / "demo.yaml").write_text(
        (deploy / "values" / "demo.yaml").read_text().replace("https://demo.vigil.solviclabs.com", "https://x.test")
    )
    result = run(harness, deploy, "spike")
    assert result.returncode == 0, result.stdout + result.stderr
    assert rendered.read_text() == template.replace("${webhook_base_url}", "https://x.test")
    assert stat.S_IMODE((harness.vigil_root / "spike" / "recordings").stat().st_mode) == 0o755


def test_profile_switch(harness):
    """AR-H-11 a switch of profile removes the other profile's containers."""
    deploy = prepare(harness, ["dev-recorder", "lab-db-01"], ["dev-recorder", "lab-db-01"])
    result = run(harness, deploy, "spike")
    assert result.returncode == 0, result.stdout + result.stderr
    names = steps(harness)
    assert names[5:8] == [
        "compose --profile vigil rm -s -f vigil-app demo-control",
        "compose config --services",
        "compose up -d --remove-orphans dev-recorder lab-db-01",
    ]
    assert names[8] == "secrets.sh demo --generated"
    assert not [name for name in names if name.startswith("compose up -d vigil-app")]

    shutil.rmtree(deploy)
    services = ["vigil-app", "demo-control", "lab-db-01"]
    deploy = prepare(harness, services, services)
    result = run(harness, deploy)
    assert result.returncode == 0, result.stdout + result.stderr
    names = steps(harness)
    assert names[5:8] == [
        "compose --profile spike rm -s -f dev-recorder",
        "compose config --services",
        "compose up -d --remove-orphans lab-db-01",
    ]
    assert "compose up -d vigil-app demo-control" in names


def test_failed_provision(harness):
    """AR-H-11 a provision that ends with exit code 1 stops the deployment before step 8."""
    deploy = prepare(
        harness,
        ["lab-zabbix-provision", "vigil-embed"],
        ["vigil-embed"],
        extra=[("docker", r"^wait lab-zabbix-provision$", [("1\n", 0)])],
    )
    result = run(harness, deploy)
    assert result.returncode == 1
    assert result.stdout.splitlines()[-1] == "lab-zabbix-provision ended with exit code 1."
    assert steps(harness)[-1] == "wait lab-zabbix-provision"
    assert not (deploy / "deployed.yaml").exists()


def test_models_check(harness):
    """AR-H-11 the models check: a missing model is pulled, then compared; unrecorded and other digests stop it."""
    missing_then_present = [(tags_json({}), 0), (tags_json({"embeddinggemma:latest": EMBED}), 0)]
    deploy = prepare(harness, ["vigil-embed"], ["vigil-embed"], extra=[("curl", r"/api/tags$", missing_then_present)])
    result = run(harness, deploy)
    assert result.returncode == 0, result.stdout + result.stderr
    assert steps(harness)[-3:] == [
        "curl http://172.30.0.11:11434/api/tags",
        "exec vigil-embed",
        "curl http://172.30.0.11:11434/api/tags",
    ]
    assert harness.fake_calls("docker")[-1]["args"] == ["exec", "vigil-embed", "ollama", "pull", "embeddinggemma"]
    assert result.stdout.splitlines()[-1] == "deployment r1 done"

    shutil.rmtree(deploy)
    deploy = prepare(harness, ["vigil-embed"], ["vigil-embed"], lock_digest="unrecorded")
    result = run(harness, deploy)
    assert result.returncode == 1
    assert result.stdout.splitlines()[-1] == (
        "No digest recorded for embeddinggemma: run deploy/models.sh update embeddinggemma"
        " and commit deploy/models.lock."
    )

    shutil.rmtree(deploy)
    deploy = prepare(harness, ["vigil-embed"], ["vigil-embed"], lock_digest=OTHER)
    result = run(harness, deploy)
    assert result.returncode == 1
    assert result.stdout.splitlines()[-1] == f"Model embeddinggemma has digest {EMBED}, models.lock records {OTHER}."

    shutil.rmtree(deploy)
    deploy = prepare(harness, ["vigil-embed"], ["vigil-embed"], extra=[("curl", r"/api/tags$", [(tags_json({}), 0)])])
    result = run(harness, deploy)
    assert result.returncode == 1
    assert result.stdout.splitlines()[-1] == "Model embeddinggemma is not in vigil-embed after ollama pull."


def test_compose_file_rejected(harness):
    """AR-T-18 a compose file that docker compose config rejects stops the deployment before secrets.sh."""
    deploy = prepare(
        harness, ["lab-db-01"], ["lab-db-01"], extra=[("docker", r"^compose .* config --quiet$", [("", 15)])]
    )
    result = run(harness, deploy)
    assert result.returncode == 1
    assert result.stdout.strip() == "The compose file is not valid for this Docker Compose (AR-T-18)."
    assert steps(harness) == ["compose config --quiet"]
