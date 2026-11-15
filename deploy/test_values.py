"""Tests of the deployment values of the demo installation, deploy/values/demo.yaml (P-32 spec Section 7)."""

import io
import sys
import zipfile

import yaml
from conftest import REPOSITORY

sys.path.insert(0, str(REPOSITORY / "deploy" / "local"))

import bundle  # noqa: E402

DEMO = REPOSITORY / "deploy" / "values" / "demo.yaml"
MAILBOXES = [
    "noc-l1",
    "db-ops",
    "vas-service-owners",
    "it-apps",
    "noc-shift-lead",
    "noc-management",
    "customer-care",
    "dbops-engineer",
    "dbops-lead",
]


def lines():
    """(name, value) of each value line, in order."""
    return [
        tuple(line.split(": ", 1))
        for line in DEMO.read_text().splitlines()
        if line.strip() and not line.startswith("#")
    ]


def test_every_value(tmp_path):
    """AR-B-07 demo.yaml holds every value and the bundle has no placeholder."""
    found = lines()
    assert [name for name, _ in found] == [
        "demo_domain",
        "product_display_name",
        "lab_profile",
        "webhook_base_url",
        "endpoint.jira",
        "endpoint.smtp",
        "smtp_starttls",
        "endpoint.claude",
        "endpoint.openai",
        "endpoint.local_embedding",
        "gpu_host_ip",
    ] + [f"mailbox.{key}" for key in MAILBOXES]
    values = dict(found)
    assert values["demo_domain"] == "vigil.solviclabs.com"
    assert values["product_display_name"] == "Techfinity"
    assert values["lab_profile"] == "demo"
    assert values["webhook_base_url"] == "https://demo.vigil.solviclabs.com"
    assert values["endpoint.jira"] == "https://techfinity-noc.atlassian.net"
    assert values["endpoint.smtp"] == "smtp://email-smtp.us-east-1.amazonaws.com:587"
    assert values["smtp_starttls"] == "true"
    assert values["endpoint.claude"] == "https://api.anthropic.com"
    assert values["endpoint.openai"] == "https://api.openai.com"
    assert values["endpoint.local_embedding"] == "http://vigil-embed:11434"
    for key in MAILBOXES:
        assert values[f"mailbox.{key}"] == f"vigil-{key}@techfinity.ae"
    assert "lab_db_host_key_sha256" not in values
    header = DEMO.read_text().splitlines()[0]
    assert "AR-B-07" in header and "P-32 adds" not in DEMO.read_text()
    derived = tmp_path / "derived.yaml"
    derived.write_text('lab_db_host_key_sha256: "SHA256:' + "A" * 43 + '"\n')
    content = bundle.build(REPOSITORY / "docs" / "31-demo-data", [DEMO, derived])
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        texts = {name: archive.read(name).decode("utf-8") for name in archive.namelist()}
    for name, text in texts.items():
        assert "${" not in text, name
    joined = "".join(texts.values())
    for value in ("https://techfinity-noc.atlassian.net", "10.40.1.20", "vigil-noc-l1@techfinity.ae"):
        assert value in joined, value


def test_gpu_host_ip():
    """AR-U-04 demo.yaml's gpu_host_ip is the GPU host's address 10.40.1.20."""
    assert dict(lines())["gpu_host_ip"] == "10.40.1.20"
    gpu = yaml.safe_load((REPOSITORY / "deploy" / "gpu" / "compose.yaml").read_text())
    assert gpu["services"]["vigil-llm"]["ports"] == ["10.40.1.20:11434:11434"]
